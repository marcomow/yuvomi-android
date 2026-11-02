package io.github.marcomow.yuvomi.feature.inventory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.OfflineBanner
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.AccountButton
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import java.time.Instant

/** docs/spec/inventory.md, the list (INV-R1 to R6). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InventoryScreen(
    vm: InventoryViewModel,
    host: InventoryHost,
    openItem: (Int) -> Unit,
    newItem: () -> Unit,
    manage: (locations: Boolean) -> Unit,
) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val s by vm.state.collectAsState()
    val editable = host.canWrite && !s.offline
    val date = dateFormatter()

    val active = s.activeCategory
    val chips = InventoryRules.chips(s.shown, s.categories, strings)
    val groups = InventoryRules.visibleGroups(s.shown, active, s.query, s.attention, s.today, s.categories, s.locations, strings)
    val activeLabel = active?.let { key -> s.categories.firstOrNull { it.key == key }?.let { InventoryRules.categoryLabel(it, strings) } ?: chips.firstOrNull { it.key == key }?.name }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("nav.inventory")) },
                actions = {
                    if (editable) {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActions")) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(t("inventory.manageLocations")) }, leadingIcon = { Icon(Icons.Outlined.Place, null) }, onClick = { menu = false; manage(true) })
                                DropdownMenuItem(text = { Text(t("inventory.manageCategories")) }, leadingIcon = { Icon(Icons.Outlined.Sell, null) }, onClick = { menu = false; manage(false) })
                            }
                        }
                    }
                    AccountButton()
                },
            )
        },
        floatingActionButton = {
            if (editable) FloatingActionButton(onClick = newItem) { Icon(Icons.Outlined.Add, contentDescription = t("inventory.addItem")) }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                s.loading -> LoadingBox()
                s.error != null -> ErrorBox("${t("inventory.loadErrorTitle")}\n${s.error}", onRetry = { vm.refresh() })
                else -> PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { vm.refresh(pulled = true) }) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                        if (s.offline) {
                            item(key = "offline") { OfflineBanner(shell.formats.dateTime(Instant.ofEpochMilli(s.cachedAt!!).atZone(shell.time.zone))) }
                        }
                        item(key = "search") {
                            OutlinedTextField(
                                value = s.query,
                                onValueChange = vm::setQuery,
                                placeholder = { Text(if (activeLabel != null) t("inventory.searchInCategoryPlaceholder", "category" to activeLabel) else t("inventory.searchPlaceholder")) },
                                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                trailingIcon = { if (s.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Outlined.Close, t("common.searchClear")) } },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        if (chips.isNotEmpty()) {
                            item(key = "chips") {
                                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item(key = "all") { FilterChip(selected = active == null, onClick = { vm.setCategory(null) }, label = { Text("${t("common.all")}  ${s.shown.size}") }) }
                                    items(chips, key = { it.key }) { c ->
                                        FilterChip(selected = active == c.key, onClick = { vm.setCategory(c.key) }, label = { Text("${c.name}  ${c.items.size}") })
                                    }
                                }
                            }
                        }
                        if (s.shown.isEmpty()) {
                            item(key = "empty") {
                                if (editable) EmptyState(t("inventory.emptyTitle"), t("inventory.emptyDescription"), t("inventory.addItem"), newItem)
                                else EmptyState(t("inventory.emptyTitle"))
                            }
                        } else {
                            item(key = "metrics") {
                                // `renderMetrics`: the count, the value (with the price right), the attention chip.
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val value = if (host.showsPrices) s.money?.let { m -> " · ${t("inventory.metricValueLabel")} ${m.money.format(InventoryRules.totalValue(s.shown, m.currency), m.currency)}" }.orEmpty() else ""
                                    Text("${t("inventory.metricItemsLabel")} ${s.shown.size}$value", style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary)
                                    if (s.attentionCount > 0 || s.attention) {
                                        FilterChip(
                                            selected = s.attention,
                                            onClick = vm::toggleAttention,
                                            leadingIcon = { Icon(Icons.Outlined.EventNote, null, Modifier.size(16.dp)) },
                                            label = { Text("${t("inventory.metricAttentionLabel")}  ${s.attentionCount}") },
                                        )
                                    }
                                }
                            }
                            if (groups.isEmpty()) {
                                item(key = "none") {
                                    if (s.query.isNotBlank()) NoResults(s.query, if (s.attention) t("inventory.metricAttentionLabel") else null, vm::resetFilters)
                                    else EmptyState(t("inventory.attentionEmptyTitle"), t("inventory.attentionEmptyDescription"), t("inventory.clearAttentionFilter"), vm::resetFilters)
                                }
                            }
                            groups.forEach { g ->
                                item(key = "g-${g.key}") {
                                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
                                        Text(g.name.uppercase(), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary, modifier = Modifier.weight(1f))
                                        Text("${g.items.size}", style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
                                    }
                                }
                                items(g.items, key = { "i-${g.key}-${it.id}" }) { item -> ItemRow(item, s, host, date) { openItem(item.id) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** `renderItemRow`: name, paperclip and receipt marks, status and deadline badges, place, price. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemRow(item: InventoryItem, s: InventoryViewModel.UiState, host: InventoryHost, date: (java.time.LocalDate) -> String, onOpen: () -> Unit) {
    val strings = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.attachments.isNotEmpty()) Icon(Icons.Outlined.AttachFile, t("inventory.hasAttachmentsLabel"), Modifier.size(16.dp), tint = Yuvomi.colors.textSecondary)
                if (item.linkedEntries.isNotEmpty()) Icon(Icons.Outlined.Receipt, t("inventory.hasBookingsLabel"), Modifier.size(16.dp), tint = Yuvomi.colors.textSecondary)
                if (item.status != "active") Badge(InventoryRules.statusLabel(item.status, strings), Yuvomi.colors.textSecondary)
                InventoryRules.deadline(item, s.today)?.let { d ->
                    Badge(InventoryRules.deadlineText(d, date, strings), if (d.state == InventoryRules.State.EXPIRED) Yuvomi.colors.danger else Yuvomi.colors.warning)
                }
            }
            item.locationPath?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (host.showsPrices && item.purchasePrice != null) {
            Spacer(Modifier.width(8.dp))
            Text(s.money?.money?.format(item.purchasePrice, item.currency) ?: item.purchasePrice.toString(), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun NoResults(query: String, filter: String?, reset: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.SearchOff, null, tint = Yuvomi.colors.textSecondary)
        Text(t("common.searchNoResultsTitle"), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            t(if (filter != null) "common.searchNoResultsFilteredDescription" else "common.searchNoResultsDescription"),
            style = MaterialTheme.typography.bodyMedium,
            color = Yuvomi.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {}, label = { Text(query.trim()) }, leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(16.dp)) })
            if (filter != null) AssistChip(onClick = {}, label = { Text(filter) })
        }
        TextButton(onClick = reset) { Text(t("common.searchReset")) }
    }
}
