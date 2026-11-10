package io.github.marcomow.yuvomi.feature.pantry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddShoppingCart
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.core.time.HouseholdTime
import kotlinx.coroutines.launch
import java.time.LocalDate

/** A status label (`.pantry-badge--danger|warning`). */
@Composable
internal fun Badge(text: String, tone: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = tone,
        maxLines = 1,
        modifier = Modifier
            .background(tone.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun dateText(key: String?, today: LocalDate): String? {
    val shell = LocalShell.current
    return HouseholdTime.date(key)?.let { shell.formats.date(it, today) }
}

/** Swipe to the row end deletes (`wirePantrySwipe`); the row springs back, the list hides it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Swipeable(onDelete: (() -> Unit)?, content: @Composable () -> Unit) {
    if (onDelete == null) {
        content()
        return
    }
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
            scope.launch { state.reset() }
        },
        backgroundContent = {
            // Only while swiping: a hidden "Delete" would otherwise be read out with every row.
            if (state.dismissDirection != SwipeToDismissBoxValue.EndToStart) return@SwipeToDismissBox
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Yuvomi.colors.danger)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Delete, null, tint = Color.White)
                    Text(t("common.delete"), color = Color.White, modifier = Modifier.padding(start = 6.dp))
                }
            }
        },
    ) {
        Surface(color = MaterialTheme.colorScheme.background) { content() }
    }
}

/**
 * One batch (`rowEl`, PANTRY-R6): name or batch title, the meta line, cart and stepper.
 * [underPlaceChip]: the place chip already names the place.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BatchRow(
    item: PantryItem,
    opts: PantryView.RowOptions,
    today: LocalDate,
    underPlaceChip: Boolean,
    editable: Boolean,
    canShop: Boolean,
    onOpen: () -> Unit,
    onStep: (Int) -> Unit,
    onCart: () -> Unit,
    onDelete: () -> Unit,
) {
    val strings = LocalStrings.current
    val status = PantryStatus.of(item, today)
    // PANTRY-R24: low and out are the product's, and only on the row that stands for it.
    val stock = opts.stock
    val place = PantryText.place(item, strings)
    val title = when {
        !opts.batch -> item.name
        !opts.byDate -> place
        item.expiresOn != null -> strings.t("pantry.bestBefore", "date" to (dateText(item.expiresOn, today) ?: item.expiresOn))
        else -> strings.t("pantry.noBestBefore")
    }
    // Controls name the batch too under its product, or "Increase: Milk" would fit every batch.
    val controlName = if (opts.batch) "${item.name}, $title" else item.name
    val where = buildList {
        val date = dateText(item.expiresOn, today) ?: item.expiresOn
        if (date != null && opts.batch && !opts.byDate) add(date)
        else if (date != null && !opts.batch) add(strings.t("pantry.bestBefore", "date" to date))
        if (!opts.batch && !underPlaceChip) add(place)
        if (!opts.batch && opts.total != null) add(PantryText.total(opts.total, item.unit, strings))
    }

    Swipeable(if (editable) onDelete else null) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = if (opts.batch) 32.dp else 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        PantryText.quantity(item, strings),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.out) Yuvomi.colors.danger else Yuvomi.colors.textSecondary,
                    )
                    PantryText.expiry(item, status, today, strings)?.let {
                        Badge(it, if (status.expiry == PantryStatus.Expiry.EXPIRED) Yuvomi.colors.danger else Yuvomi.colors.warning)
                    }
                    StockBadge(stock)
                    if (where.isNotEmpty()) {
                        Text(where.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                    }
                }
            }
            if (stock != null && (stock.out || stock.low) && canShop) {
                IconButton(onClick = onCart) {
                    Icon(Icons.Outlined.AddShoppingCart, contentDescription = "${strings.t("common.toShoppingList")}: $controlName")
                }
            }
            if (editable) {
                IconButton(onClick = { onStep(-1) }, enabled = item.quantity > 0) {
                    Icon(Icons.Outlined.Remove, contentDescription = "${strings.t("pantry.decrease")}: $controlName")
                }
                IconButton(onClick = { onStep(1) }) {
                    Icon(Icons.Outlined.Add, contentDescription = "${strings.t("pantry.increase")}: $controlName")
                }
            }
        }
    }
    HorizontalDivider(color = Yuvomi.colors.border.copy(alpha = 0.5f))
}

/** A product with several batches (`productEl`): total, most urgent expiry, where; opens its batches. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProductRow(
    product: PantryView.ProductEntry,
    today: LocalDate,
    expanded: Boolean,
    canShop: Boolean = false,
    onCart: () -> Unit = {},
    onToggle: () -> Unit,
) {
    val strings = LocalStrings.current
    val urgent = PantryView.mostUrgent(product.batches, today)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(product.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(PantryText.total(PantryView.total(product.batches), product.unit, strings), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                if (urgent != null) {
                    val status = PantryStatus.of(urgent, today)
                    PantryText.expiry(urgent, status, today, strings)?.let {
                        Badge(it, if (status.expiry == PantryStatus.Expiry.EXPIRED) Yuvomi.colors.danger else Yuvomi.colors.warning)
                    }
                }
                StockBadge(product.stock)
                Text(PantryText.where(product.batches, strings), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            }
        }
        // PANTRY-R26: the product's cart hands it over with the shortfall over all batches.
        if (canShop && product.id != null && (product.stock.out || product.stock.low)) {
            IconButton(onClick = onCart) {
                Icon(Icons.Outlined.AddShoppingCart, contentDescription = "${strings.t("common.toShoppingList")}: ${product.name}")
            }
        }
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
    }
    HorizontalDivider(color = Yuvomi.colors.border.copy(alpha = 0.5f))
}

/** A product without batches (`emptyProductEl`): out of stock, the cart, and delete. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmptyProductRow(
    product: PantryView.ProductEntry,
    editable: Boolean,
    canShop: Boolean,
    onCart: () -> Unit,
    onDelete: () -> Unit,
) {
    val strings = LocalStrings.current
    val deletable = editable && product.id != null
    var menu by remember { mutableStateOf(false) }
    Swipeable(if (deletable) onDelete else null) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(product.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // PANTRY-R27: out of stock only with a minimum; without one it is listed plainly.
                val out = product.stock.out
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        PantryUnits.quantityLabel(0.0, product.unit, strings),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (out) Yuvomi.colors.danger else Yuvomi.colors.textSecondary,
                    )
                    if (out) Badge(strings.t("pantry.badgeOut"), Yuvomi.colors.danger)
                    if (PantryStatus.hasMinimum(product.minQuantity)) {
                        Text(
                            strings.t("pantry.minimumShort", "quantity" to PantryUnits.quantityLabel(product.minQuantity!!, product.unit, strings)),
                            style = MaterialTheme.typography.bodySmall,
                            color = Yuvomi.colors.textSecondary,
                        )
                    }
                }
            }
            if (product.id != null && canShop && product.stock.out) {
                IconButton(onClick = onCart) {
                    Icon(Icons.Outlined.AddShoppingCart, contentDescription = "${strings.t("common.toShoppingList")}: ${product.name}")
                }
            }
            if (deletable) {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = strings.t("common.moreActionsNamed", "name" to product.name))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(t("common.delete"), color = Yuvomi.colors.danger) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
    HorizontalDivider(color = Yuvomi.colors.border.copy(alpha = 0.5f))
}

/** The read-only view of a batch (`openItemReadModal`, PANTRY-R16). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PantryDetailsSheet(item: PantryItem, locations: List<PantryLocation>, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    val shell = LocalShell.current
    val today = shell.time.today()
    @Composable
    fun row(label: String, value: String) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
            Text(value.ifEmpty { "-" }, style = MaterialTheme.typography.bodyLarge)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(SheetPadding) {
            Text(item.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
            row(t("pantry.quantityLabel"), PantryText.quantity(item, strings))
            val location = item.locationName ?: locations.firstOrNull { it.id == item.locationId }?.name
            row(t("pantry.locationLabel"), location?.let { PantryText.locationLabel(it, strings) } ?: strings.t("pantry.unlocated"))
            row(t("pantry.categoryLabel"), item.category?.let { PantryText.categoryLabel(it, strings) }.orEmpty())
            row(t("pantry.expiresLabel"), HouseholdTime.date(item.expiresOn)?.let { shell.formats.date(it, today) }.orEmpty())
            row(t("pantry.minQuantityLabel"), item.minQuantity?.let { PantryUnits.quantityLabel(it, item.unit, strings) }.orEmpty())
            row(t("pantry.notesLabel"), item.notes.orEmpty())
            Box(Modifier.size(8.dp))
        }
    }
}

/** `stockBadge`: "Out of stock" or "Running low" of a product's stock, or nothing. */
@Composable
private fun StockBadge(stock: PantryStatus.Stock?) {
    val strings = LocalStrings.current
    when {
        stock == null -> Unit
        stock.out -> Badge(strings.t("pantry.badgeOut"), Yuvomi.colors.danger)
        stock.low -> Badge(strings.t("pantry.badgeLow"), Yuvomi.colors.warning)
    }
}
