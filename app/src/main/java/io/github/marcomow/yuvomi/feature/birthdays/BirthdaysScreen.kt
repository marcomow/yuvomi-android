package io.github.marcomow.yuvomi.feature.birthdays

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.OfflineBanner
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.AccountButton
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import java.time.Instant
import java.time.YearMonth

/** The page's overlays, one at a time. */
internal sealed interface BirthdaysOverlay {
    data class Detail(val id: Int) : BirthdaysOverlay
    data object Import : BirthdaysOverlay
    data object Feed : BirthdaysOverlay
}

/** docs/spec/birthdays.md, the list (BDAY-R1 to R9). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirthdaysScreen(
    vm: BirthdaysViewModel,
    host: BirthdaysHost,
    format: BirthdaysFormat,
    openEditor: (Int?) -> Unit,
    openContact: ((Int) -> Unit)?,
    initialOpen: Int? = null,
) {
    val shell = LocalShell.current
    val s by vm.state.collectAsState()
    val writable = !host.readOnly && !s.offline
    var overlay by remember { mutableStateOf<BirthdaysOverlay?>(null) }
    var menu by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (System.currentTimeMillis() - vm.lastLoaded > 60_000) vm.refresh()
    }
    // `?open=<id>` (the overview's birthday row): its sheet opens once the list is there.
    var openedLink by remember { mutableStateOf(false) }
    LaunchedEffect(initialOpen, s.loading) {
        if (!openedLink && initialOpen != null && !s.loading && vm.find(initialOpen) != null) {
            openedLink = true
            overlay = BirthdaysOverlay.Detail(initialOpen)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("birthdays.title")) },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActions")) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            // `importActionHtml`: writes birthdays and reads contacts, so both rights.
                            if (writable && host.contactsVisible) {
                                DropdownMenuItem(text = { Text(t("birthdays.importButton")) }, onClick = { menu = false; overlay = BirthdaysOverlay.Import })
                            }
                            DropdownMenuItem(text = { Text(t("settings.birthdayFeedTitle")) }, onClick = { menu = false; overlay = BirthdaysOverlay.Feed })
                        }
                    }
                    AccountButton()
                },
            )
        },
        floatingActionButton = {
            if (writable && !s.loading) {
                FloatingActionButton(onClick = { openEditor(null) }) { Icon(Icons.Outlined.Add, t("birthdays.addButton")) }
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                s.loading -> LoadingBox()
                s.errorKey != null -> ErrorBox("${t(s.errorKey!!)}\n${t("common.loadErrorDescription")}", onRetry = { vm.refresh() })
                else -> PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { vm.refresh(pulled = true) }) {
                    val list = s.visible
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                        if (s.offline) {
                            item(key = "offline") { OfflineBanner(shell.formats.dateTime(Instant.ofEpochMilli(s.cachedAt!!).atZone(shell.time.zone))) }
                        }
                        item(key = "search") { SearchField(s.query, vm::setQuery) }
                        when {
                            list.isEmpty() && s.query.isNotBlank() -> item(key = "no-results") {
                                EmptyState(t("common.searchNoResultsTitle"), t("common.searchNoResultsDescription"), t("common.searchReset")) { vm.setQuery("") }
                            }
                            list.isEmpty() -> item(key = "empty") {
                                // Read-only keeps the title only (#1348).
                                EmptyState(
                                    t("birthdays.emptyTitle"),
                                    if (writable) t("birthdays.emptyDescription") else null,
                                    if (writable) t("birthdays.addButton") else null,
                                ) { openEditor(null) }
                            }
                            else -> {
                                val grouped = BirthdaysRules.grouped(list)
                                var month = ""
                                list.forEachIndexed { index, b ->
                                    val key = b.nextBirthday?.take(7).orEmpty()
                                    if (grouped && key.isNotEmpty() && key != month) {
                                        val head = runCatching { format.monthYear(YearMonth.parse(key)) }.getOrDefault(key)
                                        item(key = "month-$key") {
                                            Text(
                                                head,
                                                Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                                                style = MaterialTheme.typography.titleSmall,
                                                color = Yuvomi.colors.textSecondary,
                                            )
                                        }
                                    }
                                    if (key.isNotEmpty()) month = key
                                    item(key = "b-${b.id}") {
                                        BirthdayRow(b, next = index == 0, writable, format,
                                            open = { overlay = BirthdaysOverlay.Detail(b.id) },
                                            edit = { openEditor(b.id) },
                                            delete = { vm.delete(b.id) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    when (val o = overlay) {
        is BirthdaysOverlay.Detail -> {
            val b = vm.find(o.id)
            if (b == null) {
                LaunchedEffect(o) { overlay = null }
            } else {
                BirthdaySheet(b, vm, host, writable, format, openContact, close = { overlay = null },
                    edit = { overlay = null; openEditor(b.id) },
                    delete = { overlay = null; vm.delete(b.id) })
            }
        }
        BirthdaysOverlay.Import -> ImportSheet(vm, format) { overlay = null }
        BirthdaysOverlay.Feed -> FeedSheet(vm) { overlay = null }
        null -> Unit
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(t("birthdays.searchPlaceholder")) },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = if (query.isEmpty()) null else ({
            IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, t("common.searchClear")) }
        }),
        singleLine = true,
    )
}

@Composable
internal fun BirthdayAvatar(b: Birthday, size: androidx.compose.ui.unit.Dp) {
    val a = BirthdaysRules.avatar(b)
    MemberAvatar(a.name, a.color, a.picture, size = size)
}

/** The countdown chip (`countdownChip`): today and within a week in the module's tone. */
@Composable
internal fun CountdownChip(days: Int, format: BirthdaysFormat, highlight: Boolean = false) {
    val strings = LocalStrings.current
    val tone = Yuvomi.colors.module("birthdays")
    val chip = BirthdaysRules.chip(days)
    val (bg, fg) = when {
        chip == BirthdaysRules.Chip.TODAY -> tone to Color.White
        chip == BirthdaysRules.Chip.SOON || highlight -> tone.copy(alpha = 0.16f) to tone
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(8.dp), color = bg) {
        Text(BirthdaysRules.countdown(days, strings, format), Modifier.padding(horizontal = 6.dp, vertical = 1.dp), color = fg, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun BirthdayRow(b: Birthday, next: Boolean, writable: Boolean, format: BirthdaysFormat, open: () -> Unit, edit: () -> Unit, delete: () -> Unit) {
    val strings = LocalStrings.current
    val today = b.daysUntil == 0
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = open),
        leadingContent = { BirthdayAvatar(b, 40.dp) },
        headlineContent = {
            Text(if (today) "${b.name} 🎂" else b.name, fontWeight = if (today) FontWeight.SemiBold else null)
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    b.daysUntil?.let { CountdownChip(it, format, highlight = next) }
                    Spacer(Modifier.width(8.dp))
                    Text(BirthdaysRules.ageMeta(b, strings, format))
                }
                val nd = b.nextNameDay?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                if (nd != null && b.nameDayDaysUntil != null) {
                    Text("${BirthdaysRules.countdown(b.nameDayDaysUntil, strings, format)} · ${format.dateText(nd, weekday = true)} · ${strings.t("birthdays.celebratesNameDay")}")
                }
                b.notes?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Yuvomi.colors.textTertiary) }
            }
        },
        trailingContent = if (!writable) null else ({
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, strings.t("common.moreActionsNamed", "name" to b.name)) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(t("common.edit")) }, onClick = { menu = false; edit() })
                    DropdownMenuItem(text = { Text(t("common.delete"), color = Yuvomi.colors.danger) }, onClick = { menu = false; delete() })
                }
            }
        }),
    )
}
