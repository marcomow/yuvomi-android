package io.github.marcomow.yuvomi.feature.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.launch
import java.time.Instant

/** docs/spec/notes.md, the board (NOTES-R1 to R16). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesBoardScreen(openNote: (Int) -> Unit, newNote: () -> Unit, manageCategories: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val store = remember(shell.connection) { NotesStore.of(shell.container) } ?: return
    val s by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val viewer = shell.noteViewer(s.offline)
    val moduleReadOnly = shell.notesReadOnly()

    LaunchedEffect(store) { store.refresh() }
    // Back in the foreground after more than a minute (as Tasks, TASKS-R9).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (store.lastLoaded != 0L && System.currentTimeMillis() - store.lastLoaded > 60_000) store.refresh()
    }

    fun report(key: String?) {
        if (key != null) scope.launch { shell.notify(strings.t(key)) }
    }

    val notes = s.shown
    val visible = NotesRules.visible(notes, s.filter)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("notes.title")) },
                actions = {
                    if (!moduleReadOnly) {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActions")) }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(t("category.manageTitle")) },
                                    leadingIcon = { Icon(Icons.Outlined.Sell, null) },
                                    enabled = !s.offline,
                                    onClick = { menu = false; manageCategories() },
                                )
                            }
                        }
                    }
                    AccountButton()
                },
            )
        },
        floatingActionButton = {
            if (!viewer.readOnly) {
                FloatingActionButton(onClick = newNote) { Icon(Icons.Outlined.Add, contentDescription = t("notes.addNoteLabel")) }
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                s.loading && notes.isEmpty() -> LoadingBox()
                s.errorKey != null && notes.isEmpty() -> ErrorBox(strings.t(s.errorKey!!), onRetry = { store.refresh() })
                else -> PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { store.refresh(pulled = true) }) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val wide = maxWidth >= 600.dp
                        Board(
                            s = s,
                            notes = notes,
                            visible = visible,
                            viewer = viewer,
                            moduleReadOnly = moduleReadOnly,
                            wide = wide,
                            onFilter = store::setFilter,
                            onOpen = openNote,
                            onNew = newNote,
                            onPin = { note -> scope.launch { report(store.togglePin(note)) } },
                            onDelete = { note -> store.scheduleDelete(note, strings) { m, a -> shell.notify(m, a) } },
                            onCheck = { note, line, checked -> scope.launch { report(store.check(note.id, line, checked)) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Board(
    s: NotesStore.State,
    notes: List<Note>,
    visible: List<Note>,
    viewer: NoteViewer,
    moduleReadOnly: Boolean,
    wide: Boolean,
    onFilter: (BoardFilter) -> Unit,
    onOpen: (Int) -> Unit,
    onNew: () -> Unit,
    onPin: (Note) -> Unit,
    onDelete: (Note) -> Unit,
    onCheck: (Note, Int, Boolean) -> Unit,
) {
    val shell = LocalShell.current
    val groups = NotesRules.groups(visible)
    val full = StaggeredGridItemSpan.FullLine
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(300.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 10.dp,
    ) {
        if (s.offline) {
            item(key = "offline", span = full) {
                OfflineBanner(shell.formats.dateTime(Instant.ofEpochMilli(s.cachedAt!!).atZone(shell.time.zone)))
            }
        }
        item(key = "search", span = full) { SearchField(s.filter, onFilter) }
        item(key = "filters", span = full) { FilterRow(notes, s.categories, s.filter, onFilter) }
        if (visible.isEmpty()) {
            item(key = "empty", span = full) {
                if (s.filter.isFiltered) {
                    NoResults(s, onReset = { onFilter(BoardFilter()) })
                } else if (moduleReadOnly) {
                    EmptyState(t("notes.emptyTitle"))
                } else {
                    EmptyState(t("notes.emptyTitle"), t("notes.emptyDescription") + "\n" + t("emptyHint.notes"), t("notes.emptyAction"), onNew)
                }
            }
        }
        fun section(key: String, labelKey: String) = item(key = key, span = full) {
            Text(
                t(labelKey).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Yuvomi.colors.textSecondary,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp).semantics { heading() },
            )
        }
        fun cards(list: List<Note>) = items(list, key = { "n-${it.id}" }) { note ->
            NoteCard(note, viewer, wide, onOpen, onPin, onDelete, onCheck)
        }
        // Two headed groups only when both exist (`drawGrid`).
        if (groups.headed) {
            section("h-pinned", "notes.groupPinned")
            cards(groups.pinned)
            section("h-others", "notes.groupOthers")
            cards(groups.others)
        } else {
            cards(visible)
        }
    }
}

@Composable
private fun SearchField(filter: BoardFilter, onFilter: (BoardFilter) -> Unit) {
    OutlinedTextField(
        value = filter.query,
        onValueChange = { onFilter(filter.copy(query = it)) },
        placeholder = { Text(t("notes.searchPlaceholder")) },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = {
            if (filter.query.isNotEmpty()) {
                IconButton(onClick = { onFilter(filter.copy(query = "")) }) { Icon(Icons.Outlined.Close, t("common.searchClear")) }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

/** NOTES-R5, R6: creators from two on, then the categories in use. A second tap on an active creator clears it. */
@Composable
private fun FilterRow(notes: List<Note>, categories: List<NoteCategory>, filter: BoardFilter, onFilter: (BoardFilter) -> Unit) {
    val creators = NotesRules.creators(notes)
    val chips = NotesRules.filterCategories(notes, categories, filter.categoryIds)
    if (creators.size < 2 && chips.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (creators.size >= 2) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(t("notes.filterCreatorLabel"), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
                FilterChip(filter.creator.isEmpty(), onClick = { onFilter(filter.copy(creator = "")) }, label = { Text(t("common.all")) })
                creators.forEach { c ->
                    FilterChip(
                        selected = filter.creator == c.name,
                        onClick = { onFilter(filter.copy(creator = if (filter.creator == c.name) "" else c.name)) },
                        label = { Text(c.name) },
                        leadingIcon = { MemberAvatar(c.name, c.color, c.avatar, size = 18.dp) },
                    )
                }
            }
        }
        if (chips.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(t("noteCategories.categories"), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
                FilterChip(filter.categoryIds.isEmpty(), onClick = { onFilter(filter.copy(categoryIds = emptyList())) }, label = { Text(t("common.all")) })
                chips.forEach { c ->
                    val active = c.id in filter.categoryIds
                    val scope = scopeLabel(c)
                    FilterChip(
                        selected = active,
                        onClick = { onFilter(filter.copy(categoryIds = if (active) filter.categoryIds - c.id else filter.categoryIds + c.id)) },
                        label = { Text(c.name) },
                        leadingIcon = { Icon(scopeIcon(c), contentDescription = scope, modifier = Modifier.size(16.dp)) },
                    )
                }
            }
        }
    }
}

/** `searchEmptyHTML`: the query and the active filters, and one way back. */
@Composable
private fun NoResults(s: NotesStore.State, onReset: () -> Unit) {
    val names = listOfNotNull(s.filter.creator.takeIf { it.isNotEmpty() }) +
        s.filter.categoryIds.mapNotNull { id -> s.categories.firstOrNull { it.id == id }?.name }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(t("common.searchNoResultsTitle"), style = MaterialTheme.typography.titleMedium)
        Text(
            t(if (names.isNotEmpty()) "common.searchNoResultsFilteredDescription" else "common.searchNoResultsDescription"),
            style = MaterialTheme.typography.bodyMedium,
            color = Yuvomi.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        val terms = listOfNotNull(s.filter.query.trim().takeIf { it.isNotEmpty() }) + names
        if (terms.isNotEmpty()) {
            Text(terms.joinToString(" · "), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
        OutlinedButton(onClick = onReset) { Text(t("common.searchReset")) }
    }
}

/** NOTES-R8 to R10: one note on the board. The card opens the note; pin, menu and boxes sit on top. */
@Composable
private fun NoteCard(
    note: Note,
    viewer: NoteViewer,
    wide: Boolean,
    onOpen: (Int) -> Unit,
    onPin: (Note) -> Unit,
    onDelete: (Note) -> Unit,
    onCheck: (Note, Int, Boolean) -> Unit,
) {
    val name = NotesRules.name(note) ?: t("notes.viewNote")
    val mayChange = viewer.mayChange(note)
    val surface = MaterialTheme.colorScheme.surfaceContainer
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = NoteColors.tint(note.color, surface),
        shadowElevation = if (note.pinned) 1.5.dp else 0.5.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.clickable(onClickLabel = t("notes.openNamed", "name" to name)) { onOpen(note.id) }.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val title = note.title?.trim().orEmpty()
                if (title.isNotEmpty()) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(top = 6.dp).semantics { heading() },
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                PinMark(note, mayChange) { onPin(note) }
            }
            Column(Modifier.padding(end = 10.dp)) {
                NoteMarkdownView(
                    note.content,
                    onCheck = if (viewer.mayCheck(note)) ({ line, checked, _ -> onCheck(note, line, checked) }) else null,
                    previewLines = if (wide) 12 else if (note.title.isNullOrBlank()) 3 else 2,
                )
                if (note.categories.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    CategoryBadges(note.categories)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                CreatorLine(note, Modifier.weight(1f))
                if (mayChange) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Outlined.MoreVert, t("common.moreActionsNamed", "name" to name))
                        }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(t("common.delete"), color = Yuvomi.colors.danger) },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger) },
                                onClick = { menu = false; onDelete(note) },
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
}

/**
 * `pinMarkup`: a control for whoever may change the note; for everyone else a pinned note keeps
 * its pin as a sign that names the state, and an unpinned one shows nothing.
 */
@Composable
fun PinMark(note: Note, mayChange: Boolean, onToggle: () -> Unit) {
    when {
        mayChange -> IconButton(onClick = onToggle) {
            Icon(
                if (note.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                contentDescription = t(if (note.pinned) "notes.unpinAction" else "notes.pinAction"),
                tint = if (note.pinned) MaterialTheme.colorScheme.onSurface else Yuvomi.colors.textTertiary,
            )
        }
        note.pinned -> Icon(
            Icons.Filled.PushPin,
            contentDescription = t("notes.pinnedState"),
            modifier = Modifier.padding(12.dp).size(20.dp),
        )
        else -> Spacer(Modifier.height(40.dp))
    }
}
