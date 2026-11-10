package io.github.marcomow.yuvomi.feature.pantry

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.LocalDate

/** Ports of upstream's pantry helpers, checked against what the web app computes. */
class PantryLogicTest {
    private val today = LocalDate.parse("2026-11-01")
    private val english = Strings.flatten(File(System.getProperty("yuvomi.assetsDir"), "i18n/en.json").readText()) +
        Strings.flatten(File(System.getProperty("yuvomi.assetsDir"), "i18n-android/pantry/en.json").readText())
    private val strings = Strings("en", english, english) { if (it == 1.0) "one" else "other" }

    private val fridge = PantryLocation(2, "Kühlschrank", sortOrder = 1)
    private val cupboard = PantryLocation(1, "Vorratsschrank", sortOrder = 0)
    private val cellar = PantryLocation(4, "Keller", sortOrder = 3)
    private val locations = listOf(cupboard, fridge, cellar)
    private val categories = listOf(PantryCategory(1, "Backwaren", 0), PantryCategory(2, "Milchprodukte", 1), PantryCategory(3, "Sonstiges", 2))

    private fun item(
        id: Int,
        name: String,
        quantity: Double = 1.0,
        unit: String = "pcs",
        location: PantryLocation? = null,
        category: String? = "Sonstiges",
        expires: String? = null,
        min: Double? = null,
        notes: String? = null,
        product: Int? = id,
    ) = PantryItem(id, name, quantity, unit, location?.id, location?.name, null, category, expires, min, notes, product)

    private fun input(items: List<PantryItem>, products: List<PantryProduct> = emptyList(), query: String = "", filter: String = PantryView.ALL) =
        PantryView.Input(items, products, locations, categories, query, filter, today) { PantryText.locationLabel(it, strings) }

    // ── PANTRY-A2: pantry-units.js ──

    @Test fun `steps follow the unit, unknown units step by one`() {
        assertEquals(1.0, PantryUnits.step("pcs"), 0.0)
        assertEquals(100.0, PantryUnits.step("g"), 0.0)
        assertEquals(0.5, PantryUnits.step("kg"), 0.0)
        assertEquals(100.0, PantryUnits.step("ml"), 0.0)
        assertEquals(0.5, PantryUnits.step("l"), 0.0)
        assertEquals(1.0, PantryUnits.step("jar"), 0.0)
        assertEquals(1.0, PantryUnits.step("Stk"), 0.0)
        assertEquals("pcs", PantryUnits.normalizeUnit(" "))
        assertEquals("can", PantryUnits.normalizeUnit("can"))
    }

    @Test fun `quantities round to two decimals and stay within bounds`() {
        assertEquals(0.3, PantryUnits.normalizeQuantity(0.1 + 0.2), 0.0)
        assertEquals(0.0, PantryUnits.normalizeQuantity(-3.0), 0.0)
        assertEquals(1_000_000.0, PantryUnits.normalizeQuantity(2_000_000.0), 0.0)
        assertEquals(5.0, PantryUnits.normalizeQuantity(Double.NaN, fallback = 5.0), 0.0)
        assertEquals(1.0, PantryUnits.normalizeQuantity(null), 0.0)
        assertEquals(2.35, PantryUnits.normalizeQuantity(2.346), 0.0)
        // `Number('')` is 0, junk is not a number.
        assertEquals(0.0, PantryUnits.parse("")!!, 0.0)
        assertEquals(1.5, PantryUnits.parse("1,5")!!, 0.0)
        assertNull(PantryUnits.parse("abc"))
    }

    @Test fun `the unit inflects with the amount and unknown units stay raw`() {
        assertEquals("6 cans", PantryUnits.quantityLabel(6.0, "can", strings))
        assertEquals("1 can", PantryUnits.quantityLabel(1.0, "can", strings))
        assertEquals("1.5 kg", PantryUnits.quantityLabel(1.5, "kg", strings))
        assertEquals("1.5 cans", PantryUnits.quantityLabel(1.5, "can", strings))
        assertEquals("2 Stk", PantryUnits.quantityLabel(2.0, "Stk", strings))
        assertEquals("pack", PantryUnits.unitLabel("pkg", strings))
        assertEquals("Stk", PantryUnits.unitLabel("Stk", strings))
        assertEquals("2,5", PantryUnits.formatNumber(2.5, "de"))
        assertEquals("1,000", PantryUnits.formatNumber(1000.0, "en"))
    }

    // ── PANTRY-A3: pantry-status.js ──

    @Test fun `expiry status and phrases around today`() {
        fun phrase(date: String): String? {
            val it = item(1, "x", expires = date)
            return PantryText.expiry(it, PantryStatus.of(it, today), today, strings)
        }
        assertEquals(PantryStatus.Expiry.EXPIRED, PantryStatus.of(item(1, "x", expires = "2026-10-31"), today).expiry)
        assertEquals("Expired 2 days ago", phrase("2026-10-30"))
        assertEquals("Expired yesterday", phrase("2026-10-31"))
        assertEquals("Expires today", phrase("2026-11-01"))
        assertEquals("Expires tomorrow", phrase("2026-11-02"))
        assertEquals("4 days left", phrase("2026-11-05"))
        assertEquals("7 days left", phrase("2026-11-08"))
        assertEquals(PantryStatus.Expiry.SOON, PantryStatus.of(item(1, "x", expires = "2026-11-08"), today).expiry)
        assertNull(phrase("2026-11-09"))
        assertNull(PantryStatus.of(item(1, "x"), today).expiry)
    }

    @Test fun `stock status - out excludes low, low includes the minimum`() {
        assertTrue(PantryStatus.of(item(1, "x", quantity = 0.0), today).out)
        assertFalse(PantryStatus.of(item(1, "x", quantity = 0.0, min = 2.0), today).low)
        assertTrue(PantryStatus.of(item(1, "x", quantity = 2.0, min = 2.0), today).low)
        assertFalse(PantryStatus.of(item(1, "x", quantity = 3.0, min = 2.0), today).low)
        assertFalse(PantryStatus.of(item(1, "x", quantity = 3.0), today).low)
        // "Running low" shows empty ones too.
        assertTrue(PantryStatus.matches(item(1, "x", quantity = 0.0), "low", today))
        assertEquals("expired", PantryStatus.filterFromLink("expired"))
        assertNull(PantryStatus.filterFromLink("place:3"))
    }

    // ── PANTRY-A4: productSections ──

    @Test fun `products in category sections, batches in place and date order`() {
        val items = listOf(
            item(10, "Milk", 1.0, "l", cellar, "Milchprodukte", product = 1),
            item(11, "Milk", 2.0, "l", fridge, "Milchprodukte", expires = "2026-11-20", product = 1),
            item(12, "Milk", 0.5, "l", fridge, "Milchprodukte", expires = "2026-11-03", product = 1),
            item(20, "flour", 1.0, "kg", cupboard, "Sonstiges", product = 2),
            item(30, "Loose", 1.0, category = null, product = null),
            item(40, "Kimchi", 1.0, category = "Zzz", product = 4),
        )
        val products = listOf(
            PantryProduct(1, "Milk", "l", "Milchprodukte", 3),
            PantryProduct(2, "flour", "kg", "Sonstiges", 1),
            PantryProduct(3, "Yeast", "pkg", "Backwaren", 0),
            PantryProduct(4, "Kimchi", "pcs", "Zzz", 1),
        )
        val sections = PantryView.sections(input(items, products), items)
        assertEquals(listOf("Backwaren", "Milchprodukte", "Sonstiges", "Zzz"), sections.map { it.category })
        assertEquals(listOf("Yeast"), sections[0].products.map { it.name })
        assertTrue(sections[0].products[0].batches.isEmpty())
        val milk = sections[1].products.single()
        assertEquals(listOf(12, 11, 10), milk.batches.map { it.id })
        assertEquals(3.5, PantryView.total(milk.batches), 0.0)
        assertEquals("3.5 l in all", PantryText.total(PantryView.total(milk.batches), milk.unit, strings))
        assertEquals("2 places", PantryText.where(milk.batches, strings))
        assertEquals(12, PantryView.mostUrgent(milk.batches, today)?.id)
        // Case-insensitive name order; a batch without product stands for itself.
        assertEquals(listOf("flour", "Loose"), sections[2].products.map { it.name })
        assertEquals("b30", sections[2].products[1].key)
        assertFalse(milk.openForSearch)
    }

    @Test fun `a search hit through a place opens the product, a name hit does not`() {
        val items = listOf(
            item(10, "Milk", 1.0, "l", cellar, "Milchprodukte", product = 1),
            item(11, "Milk", 2.0, "l", fridge, "Milchprodukte", product = 1),
        )
        val products = listOf(PantryProduct(1, "Milk", "l", "Milchprodukte", 2), PantryProduct(3, "Yeast", "pkg", "Backwaren", 0))
        val byPlace = input(items, products, query = "cellar")
        val sections = PantryView.sections(byPlace, PantryView.visibleItems(byPlace))
        assertEquals(listOf("Milk"), sections.flatMap { s -> s.products.map { it.name } })
        assertTrue(sections.single().products.single().openForSearch)
        // The whole product shows, not only the matching batch.
        assertEquals(2, sections.single().products.single().batches.size)
        val byName = input(items, products, query = "yea")
        assertEquals(listOf("Yeast"), PantryView.sections(byName, PantryView.visibleItems(byName)).flatMap { s -> s.products.map { it.name } })
    }

    @Test fun `one place for all batches - batches by date and the place on the product`() {
        val batches = listOf(item(1, "Eggs", 6.0, location = fridge, product = 9), item(2, "Eggs", 4.0, location = fridge, product = 9))
        assertEquals(1, PantryView.placeCount(batches))
        assertEquals("2 batches · Fridge", PantryText.where(batches, strings))
    }

    // ── PANTRY-A5: renderFilters, placeChips ──

    @Test fun `chips with hits only, then places in location order`() {
        val items = listOf(
            item(1, "A", expires = "2026-10-01", location = fridge),
            item(2, "B", quantity = 0.0, location = cupboard),
            item(3, "C", location = fridge),
            item(4, "D"),
        )
        val chips = PantryView.chips(items, locations, PantryView.ALL, today)
        assertEquals(listOf("all", "expired", "low", "place:1", "place:2"), chips.chips.map { it.id })
        assertEquals(listOf(null, 1, 1, 1, 2), chips.chips.map { it.count })
        assertEquals(PantryView.ALL, PantryView.chips(items, locations, "soon", today).filter)
        assertEquals("place:2", PantryView.chips(items, locations, "place:2", today).filter)
        assertEquals(PantryView.ALL, PantryView.chips(items, locations, "place:4", today).filter)
        assertTrue(PantryView.chips(emptyList(), locations, PantryView.ALL, today).chips.isEmpty())
        // Without status hits and places, "All" alone filters nothing: no row.
        assertTrue(PantryView.chips(listOf(item(5, "E")), locations, PantryView.ALL, today).chips.isEmpty())
    }

    // ── PANTRY-A6: flatItems, visibleItems ──

    @Test fun `flat lists sort by what the chip asks`() {
        val items = listOf(
            item(1, "b", quantity = 3.0, min = 5.0, expires = "2026-10-20", location = fridge),
            item(2, "A", quantity = 0.0, expires = "2026-10-10", location = fridge),
            item(3, "a", quantity = 1.0, min = 2.0, location = fridge),
        )
        assertEquals(listOf(2, 3, 1), PantryView.flatItems(items, "low").map { it.id })
        assertEquals(listOf(2, 1, 3), PantryView.flatItems(items, "expired").map { it.id })
        assertEquals(listOf(2, 3, 1), PantryView.flatItems(items, "place:2").map { it.id })
        val byProduct = PantryView.batchesByProduct(items + item(4, "b", 2.0, product = 1))
        assertEquals(5.0, PantryView.flatRowOptions(items[0], byProduct).total!!, 0.0)
    }

    @Test fun `search matches name, note and place label`() {
        val items = listOf(
            item(1, "Rice", notes = "Opened", location = cupboard),
            item(2, "Butter", location = fridge),
            item(3, "Beans"),
        )
        assertEquals(listOf(1), PantryView.visibleItems(input(items, query = "open")).map { it.id })
        assertEquals(listOf(2), PantryView.visibleItems(input(items, query = "FRIDGE")).map { it.id })
        assertEquals(listOf(1), PantryView.visibleItems(input(items, query = "rice", filter = "place:1")).map { it.id })
        assertEquals(emptyList<Int>(), PantryView.visibleItems(input(items, filter = "place:4")).map { it.id })
    }

    // ── PANTRY-R13, R17, R18, R21 ──

    @Test fun `the editor sends what saveItem sends`() {
        val form = PantryEditorViewModel.Form(name = "  Oats ", quantity = "", unit = "kg", locationId = 2, category = "Sonstiges",
            expiresOn = LocalDate.parse("2027-01-31"), minQuantity = "0.333", notes = "  ")
        val json = PantryEditorViewModel.fieldsOf(form)!!.toJson()
        assertEquals("Oats", (json["name"] as JsonPrimitive).content)
        assertEquals(0.0, (json["quantity"] as JsonPrimitive).content.toDouble(), 0.0)
        assertEquals("2027-01-31", (json["expires_on"] as JsonPrimitive).content)
        assertEquals(0.33, (json["min_quantity"] as JsonPrimitive).content.toDouble(), 0.0)
        assertEquals(JsonNull, json["notes"])
        assertEquals(2, (json["location_id"] as JsonPrimitive).content.toInt())
        assertNull(PantryEditorViewModel.fieldsOf(form.copy(name = " ")))
        val empty = PantryEditorViewModel.fieldsOf(form.copy(minQuantity = "", locationId = null, expiresOn = null))!!.toJson()
        // PANTRY-A22: the minimum is the product's and goes only when the field changed.
        assertFalse("min_quantity" in empty)
        val cleared = PantryEditorViewModel.fieldsOf(form.copy(minQuantity = "", initialMinQuantity = "3"))!!.toJson()
        assertEquals(JsonNull, cleared["min_quantity"])
        val kept = PantryEditorViewModel.fieldsOf(form.copy(minQuantity = "3", initialMinQuantity = "3"))!!.toJson()
        assertFalse("min_quantity" in kept)
        assertEquals(JsonNull, empty["location_id"])
        assertEquals(JsonNull, empty["expires_on"])
    }

    @Test fun `the editor starts from the batch, a new one from the default category`() {
        val form = PantryEditorViewModel.initialForm(null, categories)
        assertEquals("Sonstiges", form.category)
        assertEquals("1", form.quantity)
        assertEquals("pcs", form.unit)
        assertEquals("Backwaren", PantryEditorViewModel.initialForm(null, categories.take(1)).category)
        val edit = PantryEditorViewModel.initialForm(item(1, "Rice", 2.0, "kg", min = 0.5), categories)
        assertEquals("2", edit.quantity)
        assertEquals("0.5", edit.minQuantity)
        assertTrue(edit.detailsOpen)
        assertFalse(PantryEditorViewModel.initialForm(item(1, "Rice"), categories).detailsOpen)
    }

    @Test fun `the shortfall to the minimum goes to the shopping list as text`() {
        assertEquals("2 cans", PantryText.shortfall(item(1, "Passata", 1.0, "can", min = 3.0), strings))
        assertNull(PantryText.shortfall(item(1, "Passata", 0.0, "can"), strings))
        assertNull(PantryText.shortfall(item(1, "Passata", 4.0, "can", min = 3.0), strings))
        assertEquals("1 item added to Weekly shop.", PantryText.transferMessage(1, 0, "Weekly shop", strings))
        assertEquals("2 items added to Weekly shop. 1 was already on it.", PantryText.transferMessage(2, 1, "Weekly shop", strings))
    }

    @Test fun `labels translate only seeded names`() {
        assertEquals("Fridge", PantryText.locationLabel("Kühlschrank", strings))
        assertEquals("Garage", PantryText.locationLabel("Garage", strings))
        assertEquals("Dairy", PantryText.categoryLabel("Milchprodukte", strings))
        assertEquals("No storage location", PantryText.place(item(1, "x"), strings))
    }

    @Test fun `errors read as upstream's errorSentence`() {
        assertEquals("common.errorOfflineMutation", PantryText.errorKey(ApiProblem.Network(IOException())))
        assertEquals("common.errorNoPermission", PantryText.errorKey(ApiProblem.Http(403, "module_read_only", null)))
        assertEquals("common.errorModuleNoAccess", PantryText.errorKey(ApiProblem.Http(403, "module_access_denied", null)))
        assertEquals("pantry.errorProductHasStock", PantryText.errorKey(ApiProblem.Http(409, "pantry_product_has_batches", null)))
        assertEquals("shopping.errorListGone", PantryText.errorKey(ApiProblem.Http(404, "shopping_list_not_found", null)))
        assertEquals("common.errorGeneric", PantryText.errorKey(ApiProblem.Http(404, null, "Item not found.")))
        assertEquals("android.error.unsupported", PantryText.errorKey(ApiProblem.Http(405, null, null)))
        assertEquals("android.pantry.locationExists", PantryText.locationErrorKey(ApiProblem.Http(409, null, null)))
        assertEquals("android.pantry.locationLast", PantryText.locationErrorKey(ApiProblem.Http(400, null, null), deleting = true))
        assertEquals("common.errorGeneric", PantryText.locationErrorKey(ApiProblem.Http(400, null, null)))
    }
}
