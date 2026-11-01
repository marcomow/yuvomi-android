package io.github.marcomow.yuvomi.feature.shopping

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings
import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * The pure rules of the shopping screen, each a port of the web app's
 * (`public/pages/shopping.js`, `utils/shopping-categories.js`) so they can be tested without
 * Android (docs/spec/shopping.md).
 */
object ShoppingLogic {

    /** `DEFAULT_CATEGORY_NAME`: the household's catch-all category, German as stored. */
    const val DEFAULT_CATEGORY_NAME = "Sonstiges"

    /** `DEFAULT_CATEGORY_I18N`: the seeded German names shown in the UI language. */
    private val CATEGORY_KEYS = mapOf(
        "Obst & Gemüse" to "shopping.catFruitVeg",
        "Backwaren" to "shopping.catBakery",
        "Milchprodukte" to "shopping.catDairy",
        "Fleisch & Fisch" to "shopping.catMeatFish",
        "Tiefkühl" to "shopping.catFrozen",
        "Getränke" to "shopping.catDrinks",
        "Haushalt" to "shopping.catHousehold",
        "Drogerie" to "shopping.catDrugstore",
        "Sonstiges" to "shopping.catMisc",
    )

    fun categoryLabel(name: String?, strings: Strings): String {
        val n = name.orEmpty()
        return CATEGORY_KEYS[n]?.let { strings.t(it) } ?: n
    }

    /** SHOP-R14 (`resetQuickAddCategory`): "Sonstiges" while the household has it, else the first. */
    fun defaultCategory(categories: List<ShoppingCategory>): String? =
        categories.firstOrNull { it.name == DEFAULT_CATEGORY_NAME }?.name ?: categories.firstOrNull()?.name

    /** `categoryOf`: an item without a category stands under the first one. */
    fun categoryOf(item: ShoppingItem, categories: List<ShoppingCategory>): String =
        item.category?.takeIf { it.isNotEmpty() } ?: categories.firstOrNull()?.name ?: DEFAULT_CATEGORY_NAME

    /** `categoryStorageKey`: the id when known, so a rename keeps the collapsed state. */
    fun categoryKey(name: String, categories: List<ShoppingCategory>): String =
        categories.firstOrNull { it.name == name }?.let { "id:${it.id}" } ?: "name:${name.trim().lowercase()}"

    private val byRank = compareBy<ShoppingItem>({ it.sortOrder ?: 0 }, { it.id })

    data class Group(val category: String, val items: List<ShoppingItem>)
    data class Sections(val groups: List<Group>, val ticked: List<ShoppingItem>)

    /**
     * SHOP-R8, SHOP-R12 (`listSections`): open items per category in the categories' order,
     * unknown categories at the end; one "Checked off" section ordered by category, then rank.
     * A row in [held] stays where it stood when tapped ([held] maps id to "shown ticked then").
     * Groups stay even when empty so their headers can be hidden rather than rebuilt.
     */
    fun sections(
        items: List<ShoppingItem>,
        categories: List<ShoppingCategory>,
        checked: (ShoppingItem) -> Boolean,
        held: Map<Int, Boolean> = emptyMap(),
    ): Sections {
        val names = categories.map { it.name }
        val byCategory = LinkedHashMap<String, MutableList<ShoppingItem>>()
        items.forEach { byCategory.getOrPut(categoryOf(it, categories)) { mutableListOf() }.add(it) }
        val ordered = names.filter { it in byCategory } + byCategory.keys.filter { it !in names }
        val position = ordered.withIndex().associate { (i, c) -> c to i }
        val inTicked = { i: ShoppingItem -> held[i.id] ?: checked(i) }
        val groups = ordered.map { c -> Group(c, byCategory.getValue(c).filterNot(inTicked).sortedWith(byRank)) }
        val ticked = items.filter(inTicked)
            .sortedWith(compareBy<ShoppingItem> { position[categoryOf(it, categories)] ?: Int.MAX_VALUE }.then(byRank))
        return Sections(groups, ticked)
    }

    /** `sameName`: case-insensitive, trimmed, as the suggestions search (COLLATE NOCASE). */
    fun sameName(a: String?, b: String?): Boolean = a.orEmpty().trim().lowercase() == b.orEmpty().trim().lowercase()

    /** SHOP-R16 (`tickedItemNamed`): the checked item of this name on the open list. */
    fun tickedItemNamed(items: List<ShoppingItem>, name: String, checked: (ShoppingItem) -> Boolean): ShoppingItem? {
        if (name.isBlank()) return null
        return items.filter { checked(it) && sameName(it.name, name) }.sortedWith(byRank).firstOrNull()
    }

    /**
     * SHOP-R21 (`persistItemOrder`): the complete order of [category] after moving [id] by
     * [delta] among the open rows; the rest of the category (checked rows) follows.
     * Null when the move goes nowhere.
     */
    fun reorder(openInCategory: List<Int>, allInCategory: List<Int>, id: Int, delta: Int): List<Int>? {
        val idx = openInCategory.indexOf(id)
        val target = idx + delta
        if (idx == -1 || target !in openInCategory.indices) return null
        val moved = openInCategory.toMutableList().apply { removeAt(idx); add(target, id) }
        return moved + allInCategory.filter { it !in moved }
    }

    // ── Pantry transfer (SHOP-R30) ──

    /** `PANTRY_UNITS`. */
    val PANTRY_UNITS = listOf("pcs", "g", "kg", "ml", "l", "pkg", "can", "bottle", "jar", "bag")

    /** `UNIT_SHORTHANDS`: English short forms that labels and recipes use across languages. */
    private val UNIT_SHORTHANDS = mapOf(
        "pcs" to listOf("pc", "pcs", "piece", "pieces"),
        "pkg" to listOf("pkg", "pack", "packs", "pck", "pkt"),
        "can" to listOf("can", "cans", "tin", "tins"),
        "bottle" to listOf("bottle", "bottles", "btl"),
        "jar" to listOf("jar", "jars"),
        "bag" to listOf("bag", "bags"),
    )

    /** `pantryUnitWords`: every word that names a unit: the unit, its labels in all plural forms, short forms. */
    fun unitWords(labels: (unit: String) -> List<String>): Map<String, String> {
        val words = LinkedHashMap<String, String>()
        fun add(word: String, unit: String) {
            val key = word.trim().lowercase().removeSuffix(".")
            if (key.isNotEmpty() && key !in words) words[key] = unit
        }
        for (unit in PANTRY_UNITS) {
            add(unit, unit)
            labels(unit).forEach { add(it, unit) }
            UNIT_SHORTHANDS[unit].orEmpty().forEach { add(it, unit) }
        }
        return words
    }

    /** The unit labels of [unit] as the pantry shows them, in every plural form. */
    fun unitLabels(strings: Strings, unit: String): List<String> {
        val base = "pantry.units.$unit"
        val counts = listOf<Number?>(null, 0, 1, 2, 3, 5, 11, 21, 0.5)
        return counts.map { c -> if (c == null) strings.t(base) else strings.t(base, "count" to c) }.filter { it != base }
    }

    data class Amount(val quantity: Double, val unit: String)

    /**
     * `parseShoppingQuantity`: the number at the start of a free-text quantity and the unit word
     * right after it ("500g", "2 Dosen"). A grouped or cut-off number, or no number, falls back
     * to 1 pcs; the dialog shows both so they can be corrected.
     */
    fun parseQuantity(raw: String?, unitWords: Map<String, String>, decimalSeparator: Char = '.'): Amount {
        val fallback = Amount(1.0, "pcs")
        val text = toAsciiDigits(raw.orEmpty().trim())
        if (text.isEmpty()) return fallback
        val m = Regex("""^(\d+)(?:([.,٫])(\d+))?""").find(text) ?: return fallback
        val whole = m.groupValues[1]
        val sep = m.groupValues[2]
        val frac = m.groupValues[3]
        val number: String = when {
            sep.isEmpty() -> whole
            sep[0] == decimalSeparator || sep == "٫" -> "$whole.$frac"
            // The region's grouping separator with three digits: "1,000 g" is ambiguous, not 1.
            frac.length == 3 -> return fallback
            else -> "$whole.$frac"
        }
        val rest = text.substring(m.range.last + 1)
        // A number that breaks off at another separator ("2,5,0") is cut off, not read.
        if (Regex("""^[.,٫]\d""").containsMatchIn(rest)) return fallback
        val quantity = number.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return fallback
        val word = Regex("""^\s*(\p{L}+\.?)""").find(rest)?.groupValues?.get(1).orEmpty()
        val unit = word.takeIf { it.isNotEmpty() }?.let { unitWords[it.lowercase().removeSuffix(".")] }
        return Amount(quantity, unit ?: "pcs")
    }

    private fun toAsciiDigits(s: String): String = buildString {
        s.forEach { ch -> val d = Character.digit(ch, 10); append(if (d >= 0 && !ch.isLetter()) ('0' + d) else ch) }
    }

    /** `ingredientMatchKey`: trimmed, inner spaces collapsed, case-insensitive. */
    fun matchKey(name: String?): String = name.orEmpty().trim().replace(Regex("\\s+"), " ").lowercase()

    data class Preset(val quantity: Double, val unit: String, val locationId: Int?, val source: String)

    /**
     * `pantryTransferPreset`: the same item's place (and unit when the quantity names none),
     * else the place where most items of this category are kept, else [fallbackLocation].
     */
    fun preset(item: ShoppingItem, pantry: PantryStock, unitWords: Map<String, String>, fallbackLocation: Int?, decimalSeparator: Char = '.'): Preset {
        val parsed = parseQuantity(item.quantity, unitWords, decimalSeparator)
        val key = matchKey(item.name)
        val stock = pantry.data.filter { key.isNotEmpty() && matchKey(it.name) == key }
        if (stock.isNotEmpty()) {
            val same = stock.firstOrNull { it.unit == parsed.unit } ?: stock.first()
            return Preset(parsed.quantity, if (parsed.unit == "pcs") same.unit ?: "pcs" else parsed.unit, same.locationId, "stock")
        }
        val best = pantry.data.filter { it.category == item.category && it.locationId != null }
            .groupingBy { it.locationId!! }.eachCount().entries.maxByOrNull { it.value }?.key
        if (best != null) return Preset(parsed.quantity, parsed.unit, best, "category")
        return Preset(parsed.quantity, parsed.unit, fallbackLocation, "default")
    }

    /** `DEFAULT_LOCATION_I18N`: the seeded German storage places in the UI language. */
    fun locationLabel(name: String, strings: Strings): String = when (name) {
        "Vorratsschrank" -> strings.t("pantry.locPantry")
        "Kühlschrank" -> strings.t("pantry.locFridge")
        "Gefrierschrank" -> strings.t("pantry.locFreezer")
        "Keller" -> strings.t("pantry.locCellar")
        "Sonstiges" -> strings.t("pantry.locOther")
        else -> name
    }

    // ── Money (SHOP-R18) ──

    fun fractionDigits(currency: String): Int =
        runCatching { Currency.getInstance(currency).defaultFractionDigits }.getOrNull()?.takeIf { it >= 0 } ?: 2

    /** `amountInputProblem` and `amountInputToCents` in one answer. */
    sealed interface Price {
        data class Ok(val cents: Long) : Price
        /** Thousands grouping ("1.200" in de): rejected with an example, as in the web app. */
        data object Grouped : Price
        data object Invalid : Price
        /** More decimals than the currency has. */
        data object Precision : Price
    }

    /**
     * A typed price in [currency]'s minor units, read with the UI language's decimal separator
     * (`utils/money.js`). Zero is allowed (free); grouping is refused rather than guessed.
     */
    fun parsePrice(input: String, currency: String, locale: Locale): Price {
        val text = toAsciiDigits(input.trim())
        if (text.isEmpty()) return Price.Invalid
        val decimal = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        val m = Regex("""^(\d+)(?:([.,٫])(\d+))?$""").matchEntire(text)
            ?: return if (Regex("""^\d{1,3}([.,'   ]\d{3})+([.,]\d+)?$""").matches(text)) Price.Grouped else Price.Invalid
        val sep = m.groupValues[2]
        val frac = m.groupValues[3]
        if (sep.isNotEmpty() && sep[0] != decimal && sep != "٫" && frac.length == 3) return Price.Grouped
        val digits = fractionDigits(currency)
        if (frac.length > digits) return Price.Precision
        val value = BigDecimal(m.groupValues[1] + if (frac.isNotEmpty()) ".$frac" else "")
        val cents = value.movePointRight(digits).toLong()
        return if (cents in 0..100_000_000) Price.Ok(cents) else Price.Invalid
    }

    /** `amountExample`: 1250 in the currency's digits, for the error sentence. */
    fun amountExample(currency: String, locale: Locale): String {
        val f = NumberFormat.getNumberInstance(locale)
        f.isGroupingUsed = false
        f.minimumFractionDigits = fractionDigits(currency)
        f.maximumFractionDigits = fractionDigits(currency)
        return f.format(1250)
    }

    /** `smallestUnitLabel`: "0.01" for EUR in en, "1" for JPY. */
    fun smallestUnit(currency: String, locale: Locale): String {
        val digits = fractionDigits(currency)
        val f = NumberFormat.getNumberInstance(locale)
        f.isGroupingUsed = false
        f.minimumFractionDigits = digits
        f.maximumFractionDigits = digits
        return f.format(if (digits == 0) BigDecimal.ONE else BigDecimal.ONE.movePointLeft(digits))
    }

    /** `centsToAmountInput`: the editable text of a stored price. */
    fun centsToInput(cents: Long, currency: String, locale: Locale): String {
        val digits = fractionDigits(currency)
        if (digits == 0) return cents.toString()
        val value = BigDecimal.valueOf(cents).movePointLeft(digits).toPlainString()
        return value.replace('.', DecimalFormatSymbols.getInstance(locale).decimalSeparator)
    }

    /** `formatMoney`. */
    fun formatCents(cents: Long, currency: String, locale: Locale): String {
        val format = NumberFormat.getCurrencyInstance(locale)
        runCatching { format.currency = Currency.getInstance(currency) }
        format.maximumFractionDigits = fractionDigits(currency)
        format.minimumFractionDigits = fractionDigits(currency)
        return format.format(BigDecimal.valueOf(cents).movePointLeft(fractionDigits(currency)))
    }

    // ── Errors (SHOP-R36, SHOP-R37; `utils/friendly-error.js`) ──

    private val CASES = mapOf(
        "shopping_list_not_found" to "shopping.errorListGone",
        "shopping_item_not_found" to "shopping.errorItemGone",
        "shopping_store_not_found" to "shopping.errorStoreGone",
        "shopping_store_exists" to "shopping.errorStoreExists",
        "shopping_category_not_found" to "shopping.errorCategoryGone",
        "category_exists" to "category.errorExists",
        "category_last" to "category.errorLast",
    )

    private val REFUSALS = mapOf(
        "module_access_denied" to "common.errorModuleNoAccess",
        "module_read_only" to "android.shopping.errorReadOnly",
        "csrf_invalid" to "common.errorFormExpired",
    )

    private val GONE = setOf("shopping_list_not_found", "shopping_item_not_found", "shopping_store_not_found", "shopping_category_not_found")

    /** `errorSentence`: the known case's key, else [fallback]. */
    fun errorKey(e: Throwable, fallback: String = "common.errorGeneric"): String = when (e) {
        is ApiProblem.Network -> "common.errorOfflineMutation"
        is ApiProblem.Http -> when {
            e.status == 403 -> REFUSALS[e.reason] ?: "common.errorNoPermission"
            e.status == 429 -> CASES[e.reason] ?: "common.errorRateLimited"
            e.reason != null && e.reason in CASES -> CASES.getValue(e.reason)
            e.operationUnsupported -> "android.error.unsupported"
            else -> fallback
        }
        else -> fallback
    }

    /** `isGone`: the server says the target no longer exists; the page reloads. */
    fun isGone(e: Throwable): Boolean = e is ApiProblem.Http && e.status != 403 && e.reason in GONE

    /** `isAlreadyDeleted`: deleting something someone else already deleted is a success. */
    fun isAlreadyDeleted(e: Throwable, reason: String): Boolean = isGone(e) && (e as ApiProblem.Http).reason == reason

    /** The load-error description: no connection, server error, or the generic one. */
    fun loadErrorKey(e: Throwable): String = when {
        e is ApiProblem.Network -> "common.errorOffline"
        e is ApiProblem.Http && e.operationUnsupported -> "android.error.unsupported"
        e is ApiProblem.Http && e.status >= 500 -> "common.errorServer"
        e is ApiProblem.Http && e.status == 403 -> "common.errorNoPermission"
        else -> "common.loadErrorDescription"
    }
}

/**
 * `startLiveFeed` (`utils/live-feed.js`): remembers each list's change counter and reports the
 * lists that moved. Own writes [acknowledge] their `list_change`, so they do not cost a reload;
 * a loaded item answer [hold]s its own version. Pure, so the rules are unit tested (SHOP-A6).
 */
class LiveFeed {
    private var seen: MutableMap<Int, Long>? = null
    private val pending = mutableListOf<() -> Unit>()

    /** Applies a `GET /shopping/versions` answer; returns the lists that moved. */
    @Synchronized
    fun apply(rows: List<ListVersion>): Set<Int> {
        val fresh = LinkedHashMap<Int, Long>()
        rows.forEach { fresh[it.listId] = it.version }
        if (seen == null) {
            seen = fresh.toMutableMap()
            val replay = pending.toList()
            pending.clear()
            replay.forEach { it() }
        }
        val known = seen!!
        val moved = LinkedHashSet<Int>()
        for ((listId, version) in fresh.toMap()) {
            val before = known[listId]
            when {
                before == null -> moved += listId
                version > before -> moved += listId
                version < before -> fresh[listId] = before
            }
        }
        known.keys.filter { it !in fresh }.forEach { moved += it }
        seen = fresh.toMutableMap()
        return moved
    }

    @Synchronized
    fun acknowledge(change: ListChange?) {
        val c = change ?: return
        val after = c.after ?: return
        val known = seen ?: run { pending += { acknowledge(c) }; return }
        if (known[c.listId] == c.before) known[c.listId] = after
    }

    @Synchronized
    fun hold(listId: Int, version: Long?) {
        if (version == null) return
        val known = seen ?: run { pending += { hold(listId, version) }; return }
        known[listId] = version
    }
}
