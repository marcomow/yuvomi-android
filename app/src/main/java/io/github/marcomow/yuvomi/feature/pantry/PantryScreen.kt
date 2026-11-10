package io.github.marcomow.yuvomi.feature.pantry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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

/** docs/spec/pantry.md, the list (PANTRY-R1 to R19). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantryScreen(vm: PantryViewModel, openItem: (Int?) -> Unit, openLocations: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val s by vm.state.collectAsState()
    // PANTRY-R16: `pantry: read` keeps the list, but no editing affordance at all.
    val canWrite = shell.session.access("pantry") == "write"
    // PANTRY-R17: the cart writes into Shopping, so Shopping's right decides (`mayTransferPantryToShopping`).
    val canShop = shell.session.access("shopping") == "write"
    val editable = canWrite && !s.offline
    var details by remember { mutableStateOf<PantryItem?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (System.currentTimeMillis() - vm.lastLoaded > 60_000) vm.refresh()
    }

    val placeLabel = { name: String -> PantryText.locationLabel(name, strings) }
    val chips = PantryView.chips(s.items, s.stock.locations, s.filter, s.today, s.products)
    val filter = chips.filter
    LaunchedEffect(filter) { if (filter != s.filter) vm.setFilter(filter) }
    val input = PantryView.Input(s.items, s.products, s.stock.locations, s.stock.categories, s.query, filter, s.today, placeLabel)
    val stock = PantryView.stock(input)
    val visible = PantryView.visibleItems(input)
    // PANTRY-R25: "Running low" lists products, as one flat group without a heading.
    val low = if (filter == "low") PantryView.lowProducts(input) else emptyList()
    val sections = when (filter) {
        PantryView.ALL -> PantryView.sections(input, visible)
        "low" -> listOf(PantryView.Section("", low))
        else -> null
    }
    val shown = sections?.sumOf { it.products.size } ?: visible.size
    val showBulk = low.isNotEmpty() && canShop

    val open: (PantryItem) -> Unit = { item -> if (editable) openItem(item.id) else details = item }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("nav.pantry")) },
                actions = {
                    if (editable) {
                        IconButton(onClick = openLocations) { Icon(Icons.Outlined.Place, contentDescription = t("pantry.manageLocations")) }
                    }
                    AccountButton()
                },
            )
        },
        floatingActionButton = {
            if (editable) {
                FloatingActionButton(onClick = { openItem(null) }) { Icon(Icons.Outlined.Add, contentDescription = t("pantry.addItem")) }
            }
        },
        bottomBar = {
            // PANTRY-R19: the bulk pill of the "Running low" chip.
            if (showBulk) {
                Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t("pantry.bulkPillLabel", "count" to low.size), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { vm.cartProducts(low) }, enabled = !s.transferring) { Text(t("pantry.toShoppingAll")) }
                    }
                }
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                s.loading -> LoadingBox()
                s.errorKey != null -> ErrorBox("${t("pantry.loadErrorTitle")}\n${t("common.loadErrorDescription")}", onRetry = { vm.refresh() })
                else -> PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { vm.refresh(pulled = true) }) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                        if (s.offline) {
                            item(key = "offline") { OfflineBanner(shell.formats.dateTime(Instant.ofEpochMilli(s.cachedAt!!).atZone(shell.time.zone))) }
                        }
                        item(key = "search") { SearchField(s.query, vm::setQuery) }
                        if (chips.chips.isNotEmpty()) {
                            item(key = "chips") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(chips.chips, key = { it.id }) { chip ->
                                        val label = chip.labelKey?.let { strings.t(it) } ?: chip.location?.let { placeLabel(it.name) }.orEmpty()
                                        FilterChip(
                                            selected = chip.id == filter,
                                            onClick = { vm.setFilter(chip.id) },
                                            label = { Text(if (chip.count != null) "$label  ${chip.count}" else label) },
                                            leadingIcon = if (chip.id == PantryView.ALL) null else ({ Icon(if (chip.location != null) Icons.Outlined.Place else Icons.Outlined.FilterList, null, Modifier.size(16.dp)) }),
                                        )
                                    }
                                }
                            }
                        }
                        when {
                            s.stock.items.isEmpty() && s.products.isEmpty() -> item(key = "empty") { PantryEmpty(editable) { openItem(null) } }
                            shown == 0 -> item(key = "none") {
                                NoResults(s.query, filter, input) { vm.resetSearch() }
                            }
                            sections != null -> sections.forEach { section ->
                                if (filter == PantryView.ALL) {
                                    item(key = "s-${section.category}") {
                                        SectionHeader(PantryText.categoryLabel(section.category, strings), section.products.size)
                                    }
                                }
                                section.products.forEach { product ->
                                    productItems(product, s, editable, canShop, open, vm)
                                }
                            }
                            else -> {
                                val byProduct = PantryView.batchesByProduct(s.items)
                                items(PantryView.flatItems(visible, filter), key = { "f-${it.id}" }) { item ->
                                    BatchRow(
                                        item = item,
                                        opts = PantryView.flatRowOptions(item, byProduct, stock),
                                        today = s.today,
                                        underPlaceChip = PantryView.placeFilterId(filter) != null,
                                        editable = editable,
                                        canShop = canShop,
                                        onOpen = { open(item) },
                                        onStep = { vm.step(item, it) },
                                        onCart = { vm.cart(item) },
                                        onDelete = { vm.delete(item) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    details?.let { item -> PantryDetailsSheet(item, s.stock.locations) { details = null } }

    s.pickList?.let { choice ->
        // `resolveShoppingTarget` with several lists: ask once.
        AlertDialog(
            onDismissRequest = { vm.pickList(null) },
            title = { Text(t("common.toShoppingListWhich")) },
            text = {
                Column {
                    choice.lists.forEach { list ->
                        ListItem(
                            headlineContent = { Text(list.name) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.pickList(list) },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { vm.pickList(null) }) { Text(t("common.cancel")) } },
        )
    }
}

/** The rows of one product in the sectioned list (PANTRY-R5). */
private fun androidx.compose.foundation.lazy.LazyListScope.productItems(
    product: PantryView.ProductEntry,
    s: PantryViewModel.UiState,
    editable: Boolean,
    canShop: Boolean,
    open: (PantryItem) -> Unit,
    vm: PantryViewModel,
) {
    when (product.batches.size) {
        0 -> item(key = "p-${product.key}") {
            EmptyProductRow(
                product = product,
                editable = editable,
                canShop = canShop,
                onCart = { vm.cartProduct(product) },
                onDelete = { product.id?.let(vm::deleteProduct) },
            )
        }
        1 -> {
            val item = product.batches.first()
            item(key = "b-${item.id}") {
                BatchRow(
                    item = item,
                    opts = PantryView.RowOptions(batch = false, byDate = false, total = null, stock = product.stock),
                    today = s.today,
                    underPlaceChip = false,
                    editable = editable,
                    canShop = canShop,
                    onOpen = { open(item) },
                    onStep = { vm.step(item, it) },
                    onCart = { vm.cart(item) },
                    onDelete = { vm.delete(item) },
                )
            }
        }
        else -> {
            val expanded = product.key in s.expanded || product.openForSearch
            item(key = "p-${product.key}") {
                ProductRow(product, s.today, expanded, canShop, onCart = { vm.cartProduct(product) }) { vm.toggleProduct(product.key) }
            }
            if (expanded) {
                val total = PantryView.total(product.batches)
                val byDate = PantryView.placeCount(product.batches) == 1
                items(product.batches, key = { "b-${it.id}" }) { item ->
                    BatchRow(
                        item = item,
                        opts = PantryView.RowOptions(batch = true, byDate = byDate, total = total),
                        today = s.today,
                        underPlaceChip = false,
                        editable = editable,
                        canShop = canShop,
                        onOpen = { open(item) },
                        onStep = { vm.step(item, it) },
                        onCart = { vm.cart(item) },
                        onDelete = { vm.delete(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text(t("pantry.searchPlaceholder")) },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, t("common.searchClear")) }
        },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SectionHeader(label: String, count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
    }
}

/** PANTRY-R11 (`emptyStateEl`): read-only members get the title only. */
@Composable
private fun PantryEmpty(editable: Boolean, add: () -> Unit) {
    if (!editable) {
        EmptyState(t("pantry.emptyTitle"))
        return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        EmptyState(t("pantry.emptyTitle"), t("pantry.emptyDescription"), action = t("pantry.emptyAction"), onAction = add)
        Text(
            t("emptyHint.pantry"),
            style = MaterialTheme.typography.bodySmall,
            color = Yuvomi.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

/** PANTRY-R10 (`noResultsEl`, `searchEmptyOptions`): term and chip named, one reset for both. */
@Composable
private fun NoResults(query: String, filter: String, input: PantryView.Input, reset: () -> Unit) {
    val strings = LocalStrings.current
    val place = PantryView.placeFilterId(filter)
    val filterName = when {
        filter == PantryView.ALL -> null
        place != null -> input.locations.firstOrNull { it.id == place }?.let { input.placeLabel(it.name) } ?: strings.t("pantry.unlocated")
        else -> strings.t(mapOf("expired" to "pantry.filterExpired", "soon" to "pantry.filterSoon", "low" to "pantry.filterLow")[filter] ?: "pantry.filterAll")
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.SearchOff, null, tint = Yuvomi.colors.textSecondary)
        Text(t("common.searchNoResultsTitle"), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            t(if (filterName != null) "common.searchNoResultsFilteredDescription" else "common.searchNoResultsDescription"),
            style = MaterialTheme.typography.bodyMedium,
            color = Yuvomi.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (query.isNotBlank()) AssistChip(onClick = {}, label = { Text(query.trim()) }, leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(16.dp)) })
            if (filterName != null) AssistChip(onClick = {}, label = { Text(filterName) }, leadingIcon = { Icon(Icons.Outlined.FilterList, null, Modifier.size(16.dp)) })
        }
        Spacer(Modifier.width(1.dp))
        TextButton(onClick = reset) { Text(t("common.searchReset")) }
    }
}

/** Keeps the bottom sheet's content clear of the navigation bar. */
internal val SheetPadding = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)
