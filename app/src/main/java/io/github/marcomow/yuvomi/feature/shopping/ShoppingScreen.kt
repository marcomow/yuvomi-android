package io.github.marcomow.yuvomi.feature.shopping

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.OfflineBanner
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.AccountButton
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.launch
import java.time.Instant

/** What the screen has open on top of the list. */
internal sealed interface Overlay {
    data object NewList : Overlay
    data object RenameList : Overlay
    data object Duplicate : Overlay
    data object DeleteList : Overlay
    data object ClearChecked : Overlay
    data object Categories : Overlay
    data object Stores : Overlay
    data object MealImport : Overlay
    data class EditItem(val id: Int) : Overlay
    data class ReadItem(val id: Int) : Overlay
    data class Send(val listId: Int, val recipients: List<Recipient>) : Overlay
    data class Pantry(val stock: PantryStock) : Overlay
}

/** What the member may do here: `shopping` write, and the transfers' other modules (SHOP-R35). */
internal data class Rights(val write: Boolean, val pantry: Boolean, val meals: Boolean)

/** docs/spec/shopping.md: the shopping lists screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingScreen(requestedList: Int?, highlight: Int? = null) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val vm = viewModel(key = "shopping") { ShoppingViewModel(shell.container, requestedList) }
    vm.notifier = { message, action -> shell.notify(message, action) }
    vm.strings = strings
    val s by vm.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(requestedList) { if (requestedList != null) vm.switchList(requestedList) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (System.currentTimeMillis() - vm.lastLoaded > 60_000 && vm.lastLoaded > 0) vm.refresh()
        vm.setResumed(true)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.setResumed(false) }

    val canWrite = shell.session.access("shopping") == "write"
    // SHOP-R34: while the banner shows, only ticking stays (it rolls back by itself).
    val editable = canWrite && !s.offline
    val rights = Rights(
        write = editable,
        pantry = shell.shows("pantry") && shell.session.access("pantry") == "write",
        meals = canWrite && shell.session.access("meals") == "write",
    )
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var quickOpen by rememberSaveable { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(highlight, s.itemsListId) {
        if (highlight != null && s.items.any { it.id == highlight }) {
            val item = s.items.first { it.id == highlight }
            val key = ShoppingLogic.categoryKey(ShoppingLogic.categoryOf(item, s.categories), s.categories)
            when {
                s.checked(item) -> if (!s.tickedOpen) vm.toggleTicked()
                key in s.collapsed -> vm.toggleCollapsed(key)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("nav.shopping")) },
                actions = {
                    if (editable && s.activeList != null) ListMenu(s, rights, vm, onOverlay = { overlay = it })
                    AccountButton()
                },
            )
        },
        floatingActionButton = {
            if (editable) {
                FloatingActionButton(onClick = {
                    if (s.activeList == null) overlay = Overlay.NewList
                    else quickOpen = !quickOpen
                }) {
                    Icon(if (quickOpen) Icons.Outlined.Close else Icons.Outlined.Add, contentDescription = t("shopping.addItemLabel"))
                }
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (s.lists.isNotEmpty() || (editable && s.listsError == null && !s.loading)) {
                ListChips(s, editable, onSwitch = vm::switchList, onNew = { overlay = Overlay.NewList })
            }
            if (quickOpen && editable && s.activeList != null) {
                QuickAdd(s, vm, nameFocus, onClose = { quickOpen = false })
                LaunchedEffect(Unit) { runCatching { nameFocus.requestFocus() } }
            }
            Box(Modifier.weight(1f)) {
                when {
                    s.loading && s.lists.isEmpty() -> LoadingBox()
                    s.listsError != null -> ErrorBox("${t("shopping.listsLoadError")} ${t(s.listsError!!)}", onRetry = { vm.refresh() })
                    s.activeList == null -> Column {
                        if (s.offline) OfflineBanner(null)
                        EmptyState(
                            t("shopping.noLists"),
                            if (canWrite) t("shopping.noListsDescription") + "\n" + t("shopping.noListsHint") else null,
                            action = if (editable) t("shopping.newListButton") else null,
                            onAction = { overlay = Overlay.NewList },
                        )
                    }
                    s.itemsError != null && s.itemsListId == s.activeListId -> ErrorBox(
                        "${t("shopping.itemsLoadError")} ${t(s.itemsError!!)}",
                        onRetry = { s.activeListId?.let { vm.switchList(it); vm.refresh() } },
                    )
                    s.itemsListId != s.activeListId -> LoadingBox()
                    else -> PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { vm.refresh(pulled = true) }) {
                        ItemList(s, vm, rights, canTick = canWrite, listState = listState, highlight = highlight, onOverlay = { overlay = it })
                    }
                }
                if (s.pill && canWrite) {
                    CheckedPill(
                        s.checkedItems.size,
                        pantry = rights.pantry,
                        onPantry = { vm.hidePill(); scope.launch { vm.pantry()?.let { overlay = Overlay.Pantry(it) } } },
                        onDelete = { vm.hidePill(); overlay = Overlay.ClearChecked },
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 88.dp, bottom = 16.dp),
                    )
                }
            }
        }
    }

    overlay?.let { o -> ShoppingOverlay(o, s, vm, rights, close = { overlay = null }) }
}

/** SHOP-R1: one chip per list with its open count; `+` for a new one. */
@Composable
private fun ListChips(s: ShoppingViewModel.UiState, editable: Boolean, onSwitch: (Int) -> Unit, onNew: () -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        items(s.lists, key = { it.id }) { list ->
            val open = s.openOf(list)
            val total = s.totalOf(list)
            FilterChip(
                selected = list.id == s.activeListId,
                onClick = { onSwitch(list.id) },
                label = {
                    Text(list.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (total > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text(if (open > 0) "$open" else "✓", style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.textSecondary)
                    }
                },
                modifier = Modifier.semantics { if (total > 0) contentDescription = "${list.name}, $open" },
            )
        }
        if (editable) {
            item(key = "new") {
                FilterChip(selected = false, onClick = onNew, label = { Icon(Icons.Outlined.Add, contentDescription = t("shopping.newListButton")) })
            }
        }
    }
}

/** SHOP-R2: the list's tools in the web app's groups. */
@Composable
private fun ListMenu(s: ShoppingViewModel.UiState, rights: Rights, vm: ShoppingViewModel, onOverlay: (Overlay) -> Unit) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val list = s.activeList ?: return
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = t("shopping.listActionsLabel", "name" to list.name)) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            val pick = { action: () -> Unit -> open = false; action() }
            val checked = s.checkedItems.size
            if (checked > 0) {
                Text(
                    t("shopping.checkedHint", "count" to checked),
                    style = MaterialTheme.typography.labelMedium,
                    color = Yuvomi.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                if (rights.pantry) MenuEntry(t("shopping.toPantry")) { pick { scope.launch { vm.pantry()?.let { onOverlay(Overlay.Pantry(it)) } } } }
                MenuEntry(t("shopping.clearChecked", "count" to checked), danger = true) { pick { onOverlay(Overlay.ClearChecked) } }
                HorizontalDivider()
            }
            MenuEntry(t("shopping.renameListLabel")) { pick { onOverlay(Overlay.RenameList) } }
            MenuEntry(t("shopping.duplicateListLabel")) { pick { onOverlay(Overlay.Duplicate) } }
            if (rights.meals) MenuEntry(t("shopping.importMeals")) { pick { onOverlay(Overlay.MealImport) } }
            MenuEntry(t("shopping.sendList")) {
                pick { scope.launch { vm.recipients()?.let { onOverlay(Overlay.Send(list.id, it)) } } }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(t("common.sortMode")) },
                trailingIcon = { if (s.sorting) Icon(Icons.Outlined.Check, null) },
                onClick = { open = false; vm.toggleSorting() },
            )
            MenuEntry(t("shopping.manageCategories")) { pick { onOverlay(Overlay.Categories) } }
            MenuEntry(t("shopping.manageStores")) { pick { onOverlay(Overlay.Stores) } }
            HorizontalDivider()
            MenuEntry(t("shopping.deleteListLabel"), danger = true) { pick { onOverlay(Overlay.DeleteList) } }
        }
    }
}

@Composable
private fun MenuEntry(label: String, danger: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label, color = if (danger) Yuvomi.colors.danger else Color.Unspecified) }, onClick = onClick)
}

/** SHOP-R14 to R16: name with suggestions, quantity, category, Add. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickAdd(s: ShoppingViewModel.UiState, vm: ShoppingViewModel, focus: FocusRequester, onClose: () -> Unit) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(s.activeListId) { mutableStateOf("") }
    var qty by rememberSaveable(s.activeListId) { mutableStateOf("") }
    var category by rememberSaveable(s.activeListId) { mutableStateOf<String?>(null) }
    var chosen by rememberSaveable(s.activeListId) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val shownCategory = category?.takeIf { c -> s.categories.any { it.name == c } } ?: ShoppingLogic.defaultCategory(s.categories)

    fun submit() {
        if (busy || name.isBlank()) return
        busy = true
        scope.launch {
            try {
                if (vm.add(name, qty, shownCategory, chosen)) {
                    name = ""
                    qty = ""
                    category = null
                    chosen = false
                    runCatching { focus.requestFocus() }
                }
            } finally {
                busy = false
            }
        }
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; vm.suggest(it) },
                    placeholder = { Text(t("shopping.itemNamePlaceholder")) },
                    label = { Text(t("shopping.itemNameLabel")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
                IconButton(onClick = { vm.clearSuggestions(); onClose() }) { Icon(Icons.Outlined.Close, contentDescription = t("common.close")) }
            }
            if (s.suggestions.isNotEmpty() && name.isNotBlank()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(s.suggestions, key = { it.name }) { sug ->
                        val ticked = vm.tickedNamed(sug.name)
                        SuggestionChip(
                            onClick = {
                                chosen = false
                                if (ticked != null) {
                                    name = ticked.name
                                    ticked.category?.takeIf { c -> s.categories.any { it.name == c } }?.let { category = it }
                                    ticked.quantity?.takeIf { it.isNotEmpty() }?.let { qty = it }
                                } else {
                                    name = sug.name
                                    sug.category?.takeIf { c -> s.categories.any { it.name == c } }?.let { category = it }
                                    sug.quantity?.takeIf { it.isNotEmpty() }?.let { qty = it }
                                }
                                vm.clearSuggestions()
                            },
                            label = { Text(if (ticked != null) "${sug.name} · ${t("shopping.itemStateChecked")}" else sug.name) },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = qty,
                    onValueChange = { qty = it },
                    placeholder = { Text(t("shopping.itemQtyPlaceholder")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.weight(0.8f),
                )
                Picker(
                    label = t("shopping.categoryLabel"),
                    value = shownCategory,
                    options = s.categories.map { it.name },
                    display = { ShoppingLogic.categoryLabel(it, strings) },
                    onPick = { category = it; chosen = true },
                    modifier = Modifier.weight(1.2f),
                )
            }
            Button(onClick = ::submit, enabled = !busy && name.isNotBlank(), modifier = Modifier.align(Alignment.End)) { Text(t("common.add")) }
        }
    }
}

/** SHOP-R8 to R10, R21, R23, R24: the grouped list and the "Checked off" section. */
@Composable
private fun ItemList(
    s: ShoppingViewModel.UiState,
    vm: ShoppingViewModel,
    rights: Rights,
    canTick: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    highlight: Int?,
    onOverlay: (Overlay) -> Unit,
) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val sections = ShoppingLogic.sections(s.items, s.categories, s::checked, s.held)
    // The row keys in the order the list shows them, to bring a search hit into view.
    val keys = buildList {
        if (s.offline) add("offline")
        if (s.items.isEmpty()) add("empty")
        sections.groups.filter { it.items.isNotEmpty() }.forEach { g ->
            val key = ShoppingLogic.categoryKey(g.category, s.categories)
            add("h-$key")
            if (key !in s.collapsed) g.items.forEach { add("i-${it.id}") }
        }
        if (sections.ticked.isNotEmpty()) {
            add("h-ticked")
            if (s.tickedOpen) sections.ticked.forEach { add("i-${it.id}") }
        }
    }
    var revealed by rememberSaveable(highlight) { mutableStateOf(false) }
    val target = highlight?.let { keys.indexOf("i-$it") } ?: -1
    LaunchedEffect(target) {
        if (!revealed && target >= 0) {
            revealed = true
            listState.scrollToItem(target)
        }
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 112.dp)) {
        if (s.offline) {
            item(key = "offline") { OfflineBanner(shell.formats.dateTime(Instant.ofEpochMilli(s.cachedAt!!).atZone(shell.time.zone))) }
        }
        if (s.items.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    t("shopping.emptyList"),
                    if (rights.write) listOfNotNull(t("shopping.emptyListDescription"), t("emptyHint.shopping").takeIf { rights.pantry }).joinToString("\n") else null,
                )
            }
        }
        sections.groups.filter { it.items.isNotEmpty() }.forEach { group ->
            val key = ShoppingLogic.categoryKey(group.category, s.categories)
            val collapsed = key in s.collapsed
            item(key = "h-$key") {
                GroupHeader(ShoppingLogic.categoryLabel(group.category, strings), group.items.size, collapsed) { vm.toggleCollapsed(key) }
            }
            if (!collapsed) {
                group.items.forEachIndexed { index, item ->
                    item(key = "i-${item.id}") {
                        ItemRow(
                            item, s, vm, rights, canTick, onOverlay,
                            sorting = s.sorting && rights.write,
                            first = index == 0,
                            last = index == group.items.lastIndex,
                        )
                    }
                }
            }
        }
        if (sections.ticked.isNotEmpty()) {
            item(key = "h-ticked") {
                GroupHeader(t("shopping.tickedSection"), sections.ticked.size, !s.tickedOpen) { vm.toggleTicked() }
            }
            if (s.tickedOpen) {
                items(sections.ticked, key = { "i-${it.id}" }) { item ->
                    ItemRow(item, s, vm, rights, canTick, onOverlay, sorting = false, first = true, last = true)
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(label: String, count: Int, collapsed: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
    }
}

/** SHOP-R10, SHOP-R11, SHOP-R20, SHOP-R24: one item, ticked by tap or swipe. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemRow(
    item: ShoppingItem,
    s: ShoppingViewModel.UiState,
    vm: ShoppingViewModel,
    rights: Rights,
    canTick: Boolean,
    onOverlay: (Overlay) -> Unit,
    sorting: Boolean,
    first: Boolean,
    last: Boolean,
) {
    val scope = rememberCoroutineScope()
    val done = s.checked(item)
    val tick = { scope.launch { vm.toggle(item) }; Unit }
    val row = @Composable {
        Surface(color = MaterialTheme.colorScheme.background) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (canTick) Modifier.clickable(onClick = tick) else Modifier)
                    .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canTick) {
                    val label = t(if (done) "shopping.markUndoneLabel" else "shopping.markDoneLabel", "name" to item.name)
                    Checkbox(
                        checked = done,
                        onCheckedChange = { tick() },
                        modifier = Modifier.semantics { contentDescription = label },
                    )
                } else {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = "${item.name}: ${t(if (done) "shopping.itemStateChecked" else "shopping.itemStateOpen")}",
                        tint = if (done) Yuvomi.colors.success else Yuvomi.colors.border,
                        modifier = Modifier.padding(12.dp).size(24.dp),
                    )
                }
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            item.name,
                            style = MaterialTheme.typography.bodyLarge,
                            textDecoration = if (done) TextDecoration.LineThrough else null,
                            color = if (done) Yuvomi.colors.textSecondary else MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (!item.url.isNullOrBlank()) Icon(Icons.Outlined.Link, null, Modifier.padding(start = 4.dp).size(14.dp), tint = Yuvomi.colors.textTertiary)
                        if (!item.notes.isNullOrBlank()) Icon(Icons.Outlined.StickyNote2, null, Modifier.padding(start = 4.dp).size(14.dp), tint = Yuvomi.colors.textTertiary)
                    }
                    val meta = listOfNotNull(
                        item.quantity?.takeIf { it.isNotBlank() },
                        item.tags.firstOrNull(),
                        (item.tags.size - 1).takeIf { it > 0 }?.let { "+$it" },
                    )
                    if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }
                when {
                    sorting && !done -> {
                        IconButton(onClick = { vm.move(item, -1) }, enabled = !first) {
                            Icon(Icons.Outlined.ArrowUpward, contentDescription = t("category.moveUpNamed", "name" to item.name))
                        }
                        IconButton(onClick = { vm.move(item, 1) }, enabled = !last) {
                            Icon(Icons.Outlined.ArrowDownward, contentDescription = t("category.moveDownNamed", "name" to item.name))
                        }
                    }
                    rights.write -> RowMenu(item, vm, onOverlay)
                    !rights.write && (item.priceCents != null || item.storeId != null || !item.url.isNullOrBlank() || !item.notes.isNullOrBlank()) ->
                        IconButton(onClick = { onOverlay(Overlay.ReadItem(item.id)) }) {
                            Icon(Icons.Outlined.Info, contentDescription = t("shopping.detailsLabel", "name" to item.name))
                        }
                }
            }
        }
    }
    if (!rights.write || sorting) {
        row()
        return
    }
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val toEnd = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Row(
                Modifier
                    .fillMaxSize()
                    .background(if (toEnd) Yuvomi.colors.success else Yuvomi.colors.danger)
                    .padding(horizontal = 20.dp),
                horizontalArrangement = if (toEnd) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (toEnd) Icons.Outlined.Check else Icons.Outlined.Delete, null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(
                    t(if (toEnd) (if (done) "shopping.swipeBack" else "shopping.swipeCheck") else "shopping.swipeDelete"),
                    color = Color.White,
                )
            }
        },
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> tick()
                SwipeToDismissBoxValue.EndToStart -> vm.delete(item)
                else -> Unit
            }
            scope.launch { state.reset() }
        },
    ) { row() }
}

@Composable
private fun RowMenu(item: ShoppingItem, vm: ShoppingViewModel, onOverlay: (Overlay) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = t("common.moreActionsNamed", "name" to item.name)) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(t("common.edit")) }, onClick = { open = false; onOverlay(Overlay.EditItem(item.id)) })
            DropdownMenuItem(
                text = { Text(t("common.delete"), color = Yuvomi.colors.danger) },
                onClick = { open = false; vm.delete(item) },
            )
        }
    }
}

/** SHOP-R13: "n items checked off" with "Into pantry" and "Delete", for five seconds. */
@Composable
private fun CheckedPill(count: Int, pantry: Boolean, onPantry: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 4.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("shopping.checkedHint", "count" to count), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
            if (pantry) TextButton(onClick = onPantry) { Text(t("shopping.toPantry"), color = MaterialTheme.colorScheme.inversePrimary) }
            TextButton(onClick = onDelete) { Text(t("common.delete"), color = MaterialTheme.colorScheme.inversePrimary) }
        }
    }
}
