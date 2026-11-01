package io.github.marcomow.yuvomi.feature.pantry

import java.text.Collator
import java.time.LocalDate
import java.util.Locale

/**
 * What the stock list shows, from the server's answer and the member's choices; ports of
 * `visibleItems`, `flatItems`, `productSections`, `renderFilters`, `placeChips` and `rowOptions`
 * in upstream's `public/pages/pantry.js` (PANTRY-R4 to R10). Pure, so it is unit tested.
 */
object PantryView {
    /** Upstream's `DEFAULT_CATEGORY_NAME` (`public/utils/shopping-categories.js`). */
    const val DEFAULT_CATEGORY = "Sonstiges"
    const val ALL = "all"
    private const val PLACE = "place:"

    data class ProductEntry(
        val key: String,
        val id: Int?,
        val name: String,
        val unit: String?,
        val category: String,
        val batches: List<PantryItem>,
        /** Matched through a batch only (a place, a note): shown open so the hit is visible. */
        val openForSearch: Boolean,
    )

    data class Section(val category: String, val products: List<ProductEntry>)

    /** How a batch row is drawn (`rowOptions`). */
    data class RowOptions(val batch: Boolean, val byDate: Boolean, val total: Double?)

    data class Chip(val id: String, val labelKey: String?, val location: PantryLocation?, val count: Int?)

    /** The input: the server's stock with steps applied and batches in their undo window left out. */
    data class Input(
        val items: List<PantryItem>,
        val products: List<PantryProduct>,
        val locations: List<PantryLocation>,
        val categories: List<PantryCategory>,
        val query: String,
        val filter: String,
        val today: LocalDate,
        /** `locationLabel`: seeded German names translated, others as typed. */
        val placeLabel: (String) -> String,
    )

    private val collator: Collator = Collator.getInstance(Locale.ROOT).apply { strength = Collator.PRIMARY }

    /** `localeCompare(..., { sensitivity: 'base' })`. */
    fun compareNames(a: String, b: String): Int = collator.compare(a, b)

    fun placeFilterId(filter: String): Int? = filter.takeIf { it.startsWith(PLACE) }?.removePrefix(PLACE)?.toIntOrNull()

    fun placeFilter(locationId: Int) = "$PLACE$locationId"

    /** A batch's product; a batch without one stands for itself. */
    fun productKeyOf(item: PantryItem): String = item.productId?.let { "p$it" } ?: "b${item.id}"

    /** `visibleItems`: the chip or place, then the search over name, note and place. */
    fun visibleItems(input: Input): List<PantryItem> {
        val q = input.query.trim().lowercase()
        val place = placeFilterId(input.filter)
        return input.items.filter { item ->
            val passes = if (place != null) item.locationId == place else PantryStatus.matches(item, input.filter, input.today)
            passes && (
                q.isEmpty() ||
                    item.name.lowercase().contains(q) ||
                    item.notes?.lowercase()?.contains(q) == true ||
                    (item.locationName != null && input.placeLabel(item.locationName).lowercase().contains(q))
                )
        }
    }

    /** `flatItems`: with a chip the list is one list of batches, ordered by what the chip asks. */
    fun flatItems(items: List<PantryItem>, filter: String): List<PantryItem> = when {
        placeFilterId(filter) != null -> items.sortedWith(
            compareBy<PantryItem, String>(::compareNames) { it.name }
                .thenBy { if (it.expiresOn.isNullOrEmpty()) 1 else 0 }
                .thenBy { it.expiresOn.orEmpty() }
                .thenBy { it.id },
        )
        // `String(null)` is "null" upstream, which sorts after every date.
        filter == "expired" || filter == "soon" -> items.sortedBy { it.expiresOn ?: "null" }
        else -> items.sortedBy { it.quantity }
    }

    fun batchesByProduct(items: List<PantryItem>): Map<String, List<PantryItem>> = items.groupBy(::productKeyOf)

    /** `productTotal`. */
    fun total(batches: List<PantryItem>): Double = PantryUnits.normalizeQuantity(batches.sumOf { it.quantity }, fallback = 0.0)

    /** Number of distinct places among [batches], "no place" counting as one. */
    fun placeCount(batches: List<PantryItem>): Int = batches.map { it.locationId ?: -1 }.toSet().size

    /** `productSections`: products in sections by category, batches under them. */
    fun sections(input: Input, matching: List<PantryItem>): List<Section> {
        val byProduct = batchesByProduct(input.items)
        val known = input.products.associateBy { "p${it.id}" }
        val q = input.query.trim().lowercase()
        val keys = LinkedHashSet(matching.map(::productKeyOf))
        for (p in input.products) {
            if (!byProduct.containsKey("p${p.id}") && (q.isEmpty() || p.name.lowercase().contains(q))) keys += "p${p.id}"
        }
        val placeOrder = input.locations.withIndex().associate { (i, loc) -> loc.id to i }
        val last = input.locations.size
        fun placeRank(item: PantryItem) = if (item.locationId == null) last + 1 else placeOrder[item.locationId] ?: last
        val batchOrder = compareBy<PantryItem> { placeRank(it) }
            .thenBy { if (it.expiresOn.isNullOrEmpty()) 1 else 0 }
            .thenBy { it.expiresOn.orEmpty() }
            .thenBy { it.id }

        val products = keys.map { key ->
            val batches = byProduct[key].orEmpty().sortedWith(batchOrder)
            val product = known[key]
            val first = batches.firstOrNull()
            val name = product?.name ?: first?.name ?: ""
            ProductEntry(
                key = key,
                id = product?.id,
                name = name,
                unit = product?.unit ?: first?.unit,
                category = product?.category?.takeIf { it.isNotEmpty() } ?: first?.category?.takeIf { it.isNotEmpty() } ?: DEFAULT_CATEGORY,
                batches = batches,
                openForSearch = q.isNotEmpty() && batches.size > 1 && !name.lowercase().contains(q),
            )
        }
        val catOrder = input.categories.withIndex().associate { (i, c) -> c.name to i }
        fun rank(name: String) = catOrder[name] ?: input.categories.size
        return products.groupBy { it.category }.entries
            .sortedWith(compareBy<Map.Entry<String, List<ProductEntry>>> { rank(it.key) }.then { a, b -> compareNames(a.key, b.key) })
            .map { (category, list) ->
                Section(category, list.sortedWith(compareBy<ProductEntry, String>(::compareNames) { it.name }.thenBy { it.id ?: 0 }))
            }
    }

    /** `rowOptions` for a batch row in the flat (filtered) list. */
    fun flatRowOptions(item: PantryItem, byProduct: Map<String, List<PantryItem>>): RowOptions {
        val siblings = byProduct[productKeyOf(item)] ?: listOf(item)
        return RowOptions(batch = false, byDate = false, total = if (siblings.size > 1) total(siblings) else null)
    }

    /** The most urgent expiry among a product's batches (`productExpiryBadge`): its batch. */
    fun mostUrgent(batches: List<PantryItem>, today: LocalDate): PantryItem? =
        batches.filter { !it.expiresOn.isNullOrEmpty() && PantryStatus.of(it, today).expiry != null }.minByOrNull { it.expiresOn!! }

    /** `placeChips`: one per location that holds a batch, in location order, with the count. */
    fun placeChips(items: List<PantryItem>, locations: List<PantryLocation>): List<Chip> {
        val counts = items.mapNotNull { it.locationId }.groupingBy { it }.eachCount()
        return locations.filter { it.id in counts }.map { Chip(placeFilter(it.id), null, it, counts[it.id]) }
    }

    data class Chips(val chips: List<Chip>, val filter: String)

    /**
     * `renderFilters`: the chips to show (empty when the row is hidden) and the filter in force;
     * an active chip that lost its last hit falls back to "All".
     */
    fun chips(items: List<PantryItem>, locations: List<PantryLocation>, filter: String, today: LocalDate): Chips {
        val counts = PantryStatus.counts(items, today)
        val active = PantryStatus.FILTERS.filter { (counts[it] ?: 0) > 0 }
        val places = placeChips(items, locations)
        val ids = active + places.map { it.id }
        val effective = if (filter != ALL && filter !in ids) ALL else filter
        if (ids.isEmpty() || items.isEmpty()) return Chips(emptyList(), effective)
        val labels = mapOf("expired" to "pantry.filterExpired", "soon" to "pantry.filterSoon", "low" to "pantry.filterLow")
        val chips = listOf(Chip(ALL, "pantry.filterAll", null, null)) +
            active.map { Chip(it, labels.getValue(it), null, counts[it]) } + places
        return Chips(chips, effective)
    }

    /** `shortfallText` as a number: what is missing to the minimum, or null. */
    fun shortfall(item: PantryItem): Double? {
        val min = item.minQuantity ?: return null
        val missing = PantryUnits.normalizeQuantity(min - item.quantity, fallback = 0.0)
        return missing.takeIf { it > 0 }
    }
}
