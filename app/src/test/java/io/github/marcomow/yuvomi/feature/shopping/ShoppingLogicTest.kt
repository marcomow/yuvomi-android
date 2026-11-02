package io.github.marcomow.yuvomi.feature.shopping

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Locale

/** docs/spec/shopping.md: the pure rules, ported from `public/pages/shopping.js`. */
class ShoppingLogicTest {
    private val cats = listOf(
        ShoppingCategory(1, "Obst & Gemüse", sortOrder = 0),
        ShoppingCategory(2, "Milchprodukte", sortOrder = 1),
        ShoppingCategory(9, "Sonstiges", sortOrder = 2),
    )

    private fun item(id: Int, name: String, category: String?, checked: Boolean = false, sort: Int? = null, qty: String? = null) =
        ShoppingItem(id, 1, name, qty, category, checked, sortOrder = sort)

    @Test fun `SHOP-A1 open items follow the category order, checked ones gather at the end, held rows stay`() {
        val items = listOf(
            item(1, "Milk", "Milchprodukte", sort = 2),
            item(2, "Yoghurt", "Milchprodukte", sort = 1),
            item(3, "Apples", "Obst & Gemüse"),
            item(4, "Bananas", "Obst & Gemüse", checked = true),
            item(5, "Glue", "Baumarkt"),
            item(6, "Cheese", "Milchprodukte", checked = true),
        )
        val s = ShoppingLogic.sections(items, cats, { it.isChecked })
        assertEquals(listOf("Obst & Gemüse", "Milchprodukte", "Baumarkt"), s.groups.map { it.category })
        assertEquals(listOf(2, 1), s.groups[1].items.map { it.id })
        assertEquals(listOf(4, 6), s.ticked.map { it.id })

        // Banana was just ticked off: until the pause it stays in its category.
        val held = ShoppingLogic.sections(items, cats, { it.isChecked }, held = mapOf(4 to false))
        assertEquals(listOf(3, 4), held.groups[0].items.map { it.id })
        assertEquals(listOf(6), held.ticked.map { it.id })
    }

    @Test fun `SHOP-A2 quick add defaults to Sonstiges while it exists, else the first category`() {
        assertEquals("Sonstiges", ShoppingLogic.defaultCategory(cats))
        assertEquals("Obst & Gemüse", ShoppingLogic.defaultCategory(cats.filter { it.name != "Sonstiges" }))
        assertNull(ShoppingLogic.defaultCategory(emptyList()))
    }

    @Test fun `an item without a category stands under the first one, and the collapse key survives a rename`() {
        assertEquals("Obst & Gemüse", ShoppingLogic.categoryOf(item(1, "x", null), cats))
        assertEquals("id:2", ShoppingLogic.categoryKey("Milchprodukte", cats))
        assertEquals("name:baumarkt", ShoppingLogic.categoryKey(" Baumarkt", cats))
    }

    @Test fun `a checked item of the same name is found case-insensitively`() {
        val items = listOf(item(1, "Milk", "Milchprodukte", checked = true), item(2, "Bread", "Backwaren"))
        assertEquals(1, ShoppingLogic.tickedItemNamed(items, " milk ", { it.isChecked })?.id)
        assertNull(ShoppingLogic.tickedItemNamed(items, "bread", { it.isChecked }))
        assertNull(ShoppingLogic.tickedItemNamed(items, "  ", { it.isChecked }))
    }

    @Test fun `a move sends the whole category, open rows first`() {
        assertEquals(listOf(2, 1, 3, 9), ShoppingLogic.reorder(listOf(1, 2, 3), listOf(1, 2, 3, 9), 2, -1))
        assertEquals(listOf(1, 3, 2, 9), ShoppingLogic.reorder(listOf(1, 2, 3), listOf(9, 1, 2, 3), 2, 1))
        assertNull(ShoppingLogic.reorder(listOf(1, 2), listOf(1, 2), 1, -1))
    }

    private val german = mapOf("pantry.units.can_one" to "Dose", "pantry.units.can_other" to "Dosen", "pantry.units.l" to "l", "pantry.units.g" to "g")
    private val strings = Strings("de", german, emptyMap()) { if (it == 1.0) "one" else "other" }
    private val words = ShoppingLogic.unitWords { ShoppingLogic.unitLabels(strings, it) }

    @Test fun `SHOP-A3 the quantity parser reads number and unit, and falls back to one piece`() {
        assertEquals(ShoppingLogic.Amount(500.0, "g"), ShoppingLogic.parseQuantity("500g", words))
        assertEquals(ShoppingLogic.Amount(2.0, "can"), ShoppingLogic.parseQuantity("2 Dosen", words, ','))
        assertEquals(ShoppingLogic.Amount(1.5, "l"), ShoppingLogic.parseQuantity("1,5 l", words, ','))
        assertEquals(ShoppingLogic.Amount(1.0, "pcs"), ShoppingLogic.parseQuantity("1,000 g", words, '.'))
        assertEquals(ShoppingLogic.Amount(6.0, "pcs"), ShoppingLogic.parseQuantity("6 × 1 l", words))
        assertEquals(ShoppingLogic.Amount(2.0, "pcs"), ShoppingLogic.parseQuantity("2x500 g", words))
        assertEquals(ShoppingLogic.Amount(3.0, "pkg"), ShoppingLogic.parseQuantity("3 packs", words))
        assertEquals(ShoppingLogic.Amount(1.0, "pcs"), ShoppingLogic.parseQuantity("a head", words))
        assertEquals(ShoppingLogic.Amount(1.0, "pcs"), ShoppingLogic.parseQuantity(null, words))
    }

    @Test fun `SHOP-A4 pantry presets follow the stock, then the category's usual place, then the default`() {
        val pantry = PantryStock(
            data = listOf(
                PantryRow("Milk", "l", 2, "Milchprodukte"),
                PantryRow("Butter", "pcs", 2, "Milchprodukte"),
                PantryRow("Cream", "ml", 3, "Milchprodukte"),
                PantryRow("Rice", "kg", 1, "Sonstiges"),
            ),
            locations = listOf(PantryLocation(1, "Vorratsschrank"), PantryLocation(2, "Kühlschrank"), PantryLocation(3, "Keller")),
        )
        val milk = ShoppingLogic.preset(item(1, " milk", "Milchprodukte"), pantry, words, 1)
        assertEquals(ShoppingLogic.Preset(1.0, "l", 2, "stock"), milk)
        val quark = ShoppingLogic.preset(item(2, "Quark", "Milchprodukte", qty = "500 g"), pantry, words, 1)
        assertEquals(ShoppingLogic.Preset(500.0, "g", 2, "category"), quark)
        val glue = ShoppingLogic.preset(item(3, "Glue", "Baumarkt"), pantry, words, 1)
        assertEquals(ShoppingLogic.Preset(1.0, "pcs", 1, "default"), glue)
    }

    @Test fun `SHOP-A5 prices are read in the currency's minor units`() {
        assertEquals(ShoppingLogic.Price.Ok(150), ShoppingLogic.parsePrice("1,50", "EUR", Locale.GERMAN))
        assertEquals(ShoppingLogic.Price.Ok(150), ShoppingLogic.parsePrice("1.5", "EUR", Locale.ENGLISH))
        assertEquals(ShoppingLogic.Price.Ok(300), ShoppingLogic.parsePrice("300", "JPY", Locale.JAPANESE))
        assertEquals(ShoppingLogic.Price.Ok(0), ShoppingLogic.parsePrice("0", "EUR", Locale.ENGLISH))
        assertEquals(ShoppingLogic.Price.Grouped, ShoppingLogic.parsePrice("1.200", "EUR", Locale.GERMAN))
        assertEquals(ShoppingLogic.Price.Grouped, ShoppingLogic.parsePrice("1.200,50", "EUR", Locale.GERMAN))
        assertEquals(ShoppingLogic.Price.Precision, ShoppingLogic.parsePrice("1.505", "EUR", Locale.ENGLISH))
        assertEquals(ShoppingLogic.Price.Invalid, ShoppingLogic.parsePrice("abc", "EUR", Locale.ENGLISH))
        assertEquals(ShoppingLogic.Price.Invalid, ShoppingLogic.parsePrice("-2", "EUR", Locale.ENGLISH))
        assertEquals("1,50", ShoppingLogic.centsToInput(150, "EUR", Locale.GERMAN))
        assertEquals("300", ShoppingLogic.centsToInput(300, "JPY", Locale.JAPANESE))
    }

    @Test fun `SHOP-A6 the live feed reloads for others' changes, not for its own`() {
        val feed = LiveFeed()
        feed.acknowledge(ListChange(1, before = 5, after = 6)) // before the first answer: replayed
        assertEquals(emptySet<Int>(), feed.apply(listOf(ListVersion(1, 5), ListVersion(2, 9))))
        // The acknowledgement moved list 1 to 6: version 6 is our own.
        assertEquals(emptySet<Int>(), feed.apply(listOf(ListVersion(1, 6), ListVersion(2, 9))))
        // Someone else ticked something on list 2.
        assertEquals(setOf(2), feed.apply(listOf(ListVersion(1, 6), ListVersion(2, 10))))
        // An acknowledgement that does not start from the known version is not ours alone.
        feed.acknowledge(ListChange(1, before = 7, after = 8))
        assertEquals(setOf(1), feed.apply(listOf(ListVersion(1, 8), ListVersion(2, 10))))
        // A list deleted elsewhere, and a new one.
        assertEquals(setOf(2, 3), feed.apply(listOf(ListVersion(1, 8), ListVersion(3, 1))))
        // A loaded answer holds its own version.
        feed.hold(3, 4)
        assertEquals(emptySet<Int>(), feed.apply(listOf(ListVersion(1, 8), ListVersion(3, 4))))
    }

    @Test fun `errors map to the web app's sentences`() {
        assertEquals("shopping.errorItemGone", ShoppingLogic.errorKey(ApiProblem.Http(404, "shopping_item_not_found", null)))
        assertEquals("category.errorExists", ShoppingLogic.errorKey(ApiProblem.Http(409, "category_exists", null)))
        assertEquals("common.errorNoPermission", ShoppingLogic.errorKey(ApiProblem.Http(403, "cross_module_access", null)))
        assertEquals("android.shopping.errorReadOnly", ShoppingLogic.errorKey(ApiProblem.Http(403, "module_read_only", null)))
        assertEquals("common.errorRateLimited", ShoppingLogic.errorKey(ApiProblem.Http(429, null, null)))
        assertEquals("common.errorOfflineMutation", ShoppingLogic.errorKey(ApiProblem.Network(IOException())))
        assertEquals("shopping.sendListError", ShoppingLogic.errorKey(ApiProblem.Http(502, null, null), "shopping.sendListError"))
        assertTrue(ShoppingLogic.isGone(ApiProblem.Http(404, "shopping_list_not_found", null)))
        assertFalse(ShoppingLogic.isGone(ApiProblem.Http(403, "shopping_list_not_found", null)))
        assertTrue(ShoppingLogic.isAlreadyDeleted(ApiProblem.Http(404, "shopping_item_not_found", null), "shopping_item_not_found"))
        assertFalse(ShoppingLogic.isAlreadyDeleted(ApiProblem.Http(404, "shopping_list_not_found", null), "shopping_item_not_found"))
    }

    @Test fun `seeded German category names show translated, others as they are`() {
        val en = Strings("en", mapOf("shopping.catDairy" to "Dairy"), emptyMap()) { "other" }
        assertEquals("Dairy", ShoppingLogic.categoryLabel("Milchprodukte", en))
        assertEquals("Hardware", ShoppingLogic.categoryLabel("Hardware", en))
    }
}
