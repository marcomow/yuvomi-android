package io.github.marcomow.yuvomi.feature.pantry

import io.github.marcomow.yuvomi.core.i18n.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Upstream 2.85.0, part 3 of #1448: low and out belong to the product (PANTRY-R23 to R27). */
class PantryProductStockTest {
    private val today = LocalDate.parse("2026-11-01")
    private val english = Strings.flatten(File(System.getProperty("yuvomi.assetsDir"), "i18n/en.json").readText())
    private val strings = Strings("en", english, english) { if (it == 1.0) "one" else "other" }
    private val bathroom = PantryLocation(1, "Bad")
    private val attic = PantryLocation(2, "Dachboden")

    private fun batch(id: Int, product: Int?, quantity: Double, location: PantryLocation = bathroom, unit: String = "pcs", min: Double? = null) =
        PantryItem(id, "P$product", quantity, unit, location.id, location.name, null, "Sonstiges", null, min, null, product)

    private fun product(id: Int, min: Double?, unit: String = "pcs") = PantryProduct(id, "P$id", unit, "Sonstiges", minQuantity = min)

    private fun input(items: List<PantryItem>, products: List<PantryProduct>, query: String = "", filter: String = PantryView.ALL) =
        PantryView.Input(items, products, listOf(bathroom, attic), emptyList(), query, filter, today) { it }

    /** PANTRY-A17: the total over all batches against the product's minimum. */
    @Test fun `running low is read over all batches of a product`() {
        // Toothpaste: 1 in the bathroom, 8 in the attic, minimum 3: not low, the household has 9.
        val toothpaste = PantryStatus.productStock(listOf(batch(1, 10, 1.0), batch(2, 10, 8.0, attic)), listOf(product(10, 3.0)))
        assertEquals(9.0, toothpaste.getValue("p10").total, 0.0)
        assertFalse(toothpaste.getValue("p10").low)
        // Milk: 1 l, 1 l and 2 l against 5 l: low once, as one product.
        val milk = listOf(batch(3, 11, 1.0, unit = "l"), batch(4, 11, 1.0, unit = "l"), batch(5, 11, 2.0, unit = "l"))
        val stock = PantryStatus.productStock(milk, listOf(product(11, 5.0, "l")))
        assertTrue(stock.getValue("p11").low)
        assertEquals(1, PantryStatus.counts(milk, today, listOf(product(11, 5.0, "l"))).getValue("low"))
        // Every batch of a low product matches "Running low".
        assertTrue(milk.all { PantryStatus.matches(it, "low", today, stock) })
        // A server before 2.85.0 sends no minimum on the product: the highest of its batches counts.
        val old = PantryStatus.productStock(listOf(batch(7, 12, 1.0, min = 2.0), batch(8, 12, 1.0, min = 3.0)), listOf(product(12, null)))
        assertTrue(old.getValue("p12").low)
        // A batch without a product stands for itself, with its own minimum.
        val lone = PantryStatus.productStock(listOf(batch(6, null, 1.0, min = 2.0)), emptyList())
        assertTrue(lone.getValue("b6").low)
    }

    /** PANTRY-A18: a product whose last batch is gone is out only with a minimum. */
    @Test fun `a product without batches is out only with a minimum`() {
        val stock = PantryStatus.productStock(emptyList(), listOf(product(1, 3.0), product(2, null), product(3, 0.0)))
        assertTrue(stock.getValue("p1").out)
        assertFalse(stock.getValue("p1").low)
        assertFalse(stock.getValue("p2").out)
        assertFalse(stock.getValue("p3").out)
        assertEquals(1, PantryStatus.counts(emptyList(), today, listOf(product(1, 3.0), product(2, null))).getValue("low"))
    }

    /** PANTRY-A19: the chip lists products, out first, then the lowest share of the minimum. */
    @Test fun `running low lists products in order of need`() {
        val items = listOf(
            batch(1, 1, 2.0), // 2 of 3: share 0.67
            batch(2, 2, 1.0), batch(3, 2, 1.0, attic), // 2 of 5: share 0.4
            batch(4, 3, 9.0), // not low
        )
        val products = listOf(product(1, 3.0), product(2, 5.0), product(3, 3.0), product(4, 2.0))
        val low = PantryView.lowProducts(input(items, products, filter = "low"))
        assertEquals(listOf("p4", "p2", "p1"), low.map { it.key })
        // The search narrows it like the plain list.
        assertEquals(listOf("p2"), PantryView.lowProducts(input(items, products, query = "P2", filter = "low")).map { it.key })
    }

    /** PANTRY-A20: the cart hands a product over with the shortfall over all its batches. */
    @Test fun `the shopping entry is the product with its shortfall`() {
        val items = listOf(batch(1, 2, 1.0), batch(2, 2, 1.0, attic), batch(3, 1, 2.0))
        val products = listOf(product(1, 3.0), product(2, 5.0), product(4, 2.0))
        val entries = PantryView.lowProducts(input(items, products, filter = "low")).associateBy { it.key }
        val label = { q: Double -> PantryUnits.quantityLabel(q, "pcs", strings) }
        assertEquals(PantryApi.TransferEntry.Product(2, label(3.0)), PantryView.shoppingEntry(entries.getValue("p2"), label))
        assertEquals(PantryApi.TransferEntry.Batch(3, label(1.0)), PantryView.shoppingEntry(entries.getValue("p1"), label))
        assertEquals(PantryApi.TransferEntry.Product(4, label(2.0)), PantryView.shoppingEntry(entries.getValue("p4"), label))
    }

    /** PANTRY-A21: a batch under a product with several carries no stock state; a lone one does. */
    @Test fun `only the row that stands for the product carries low and out`() {
        val items = listOf(batch(1, 2, 1.0), batch(2, 2, 1.0, attic), batch(3, 1, 2.0))
        val stock = PantryStatus.productStock(items, listOf(product(1, 3.0), product(2, 5.0)))
        val byProduct = PantryView.batchesByProduct(items)
        assertNull(PantryView.flatRowOptions(items[0], byProduct, stock).stock)
        assertTrue(PantryView.flatRowOptions(items[2], byProduct, stock).stock!!.low)
    }
}
