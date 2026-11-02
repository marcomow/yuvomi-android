package io.github.marcomow.yuvomi.contract

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.feature.inventory.InventoryApi
import io.github.marcomow.yuvomi.feature.inventory.InventoryItem
import io.github.marcomow.yuvomi.feature.inventory.InventoryRules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * docs/spec/inventory.md against upstream's image at the pinned version, seeded by `seed-demo.js`.
 * Every test makes its own items, categories and places with unique names and removes them.
 */
class InventoryContractTest : ContractTestBase() {
    private fun alex(): InventoryApi = InventoryApi(signedIn("alex").first)

    private fun expectHttp(status: Int, reason: String? = null, block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("expected HTTP $status")
        } catch (e: ApiProblem.Http) {
            assertEquals(status, e.status)
            if (reason != null) assertEquals(reason, e.reason)
        }
    }

    private suspend fun InventoryApi.make(form: InventoryRules.Form, tracks: Boolean = false, price: Double? = null, entryId: Int? = null): InventoryItem =
        create(InventoryRules.body(form, tracks, showsPrice = true, price = price, attachmentIds = emptyList(), entryId = entryId), YuvomiClient.newIdempotencyKey())

    /** INV-A1: the seeded items, categories (vehicles track the odometer) and the place tree decode. */
    @Test fun `INV-A1 the seeded inventory decodes`() = runBlocking {
        val api = alex()
        val items = InventoryApi.decodeItems(api.items())
        assertTrue(items.isNotEmpty())
        assertTrue(items.any { it.trackedDates.isNotEmpty() })
        assertTrue(items.all { it.categoryName != null || it.categoryLabelKey != null })
        val categories = InventoryApi.decodeCategories(api.categories())
        assertTrue(categories.single { it.key == "vehicles" }.tracksOdometer)
        assertTrue(categories.any { it.key == "other" })
        val locations = InventoryApi.decodeLocations(api.locations())
        assertTrue(locations.isNotEmpty())
        assertTrue(locations.all { it.parentId == null })
    }

    /** INV-A2: create with the form's body, read, history, update and delete; the deleted item is a 404 with its reason. */
    @Test fun `INV-A2 create, read, update and delete an item`() = runBlocking {
        val api = alex()
        val name = unique("Car")
        val form = InventoryRules.Form(
            name = name, category = "vehicles", purchaseDate = "2024-01-15", warranty = "24", brand = "VW",
            dates = listOf(InventoryRules.DateRow("TÜV", LocalDate.now().plusMonths(2).toString(), "14", "24", "")),
            odometer = "15000", odometerUnit = "km", odometerOn = "2026-10-01",
        )
        val created = api.make(form, tracks = true, price = 20000.0)
        try {
            assertEquals(name, created.name)
            assertEquals(20000.0, created.purchasePrice!!, 0.0)
            assertEquals(15000L, created.odometer)
            assertEquals(24, created.trackedDates.single().intervalMonths)
            assertEquals(14, created.trackedDates.single().reminderOffsetDays)
            assertTrue(InventoryApi.decodeItems(api.items()).any { it.id == created.id })
            assertEquals(created.id, api.item(created.id).id)
            assertNotNull(api.history(created.id).timeline)

            val edit = InventoryRules.form(created) { a, _ -> a?.toString().orEmpty() }.copy(category = "other", status = "sold", notes = "#contract", dates = emptyList())
            val updated = api.update(created.id, InventoryRules.body(edit, tracksOdometer = false, showsPrice = true, price = 19000.0, attachmentIds = emptyList(), entryId = null))
            assertEquals("sold", updated.status)
            assertEquals("#contract", updated.notes)
            assertTrue(updated.trackedDates.isEmpty())
            // A category without odometer drops the reading.
            assertNull(updated.odometer)
        } finally {
            api.delete(created.id)
        }
        expectHttp(404, "inventory_item_not_found") { api.item(created.id) }
        expectHttp(404, "inventory_item_not_found") { api.delete(created.id) }
        expectHttp(404, "inventory_item_not_found") { api.history(created.id) }
    }

    /** INV-A3: "Done" logs the service, rolls a repeating date forward and moves the odometer; a one-off date goes. */
    @Test fun `INV-A3 completing tracked dates`() = runBlocking {
        val api = alex()
        val due = LocalDate.now().minusDays(3)
        val created = api.make(
            InventoryRules.Form(name = unique("Van"), category = "vehicles", dates = listOf(
                InventoryRules.DateRow("Service", due.toString(), "30", "12", ""),
                InventoryRules.DateRow("Once", due.toString(), "30", "", ""),
            )),
            tracks = true,
        )
        try {
            val repeating = created.trackedDates.single { it.label == "Service" }
            val once = created.trackedDates.single { it.label == "Once" }
            val today = LocalDate.now().toString()
            api.complete(created.id, repeating.id, InventoryRules.completion(today, "20000", "Garage", "Oil", tracksOdometer = true), YuvomiClient.newIdempotencyKey())
            api.complete(created.id, once.id, InventoryRules.completion(today, "", "", "", tracksOdometer = true), YuvomiClient.newIdempotencyKey())
            val after = api.item(created.id)
            assertEquals(listOf("Service"), after.trackedDates.map { it.label })
            assertTrue(after.trackedDates.single().date > today)
            assertEquals(20000L, after.odometer)
            val history = api.history(created.id)
            assertEquals(2, history.timeline.count { it.type == "service_log" })
            assertTrue(history.timeline.any { it.label == "Service" && it.odometer == 20000L && it.vendor == "Garage" })
            expectHttp(404, "inventory_tracked_date_not_found") {
                api.complete(created.id, once.id, InventoryRules.completion(today, "", "", "", false), YuvomiClient.newIdempotencyKey())
            }
            expectHttp(400) {
                api.complete(created.id, after.trackedDates.single().id, InventoryRules.completion(today, "100", "", "", true), YuvomiClient.newIdempotencyKey())
            }
        } finally {
            api.delete(created.id)
        }
    }

    /** INV-A4: a booking links with a role, refuses a second link, and unlinks. */
    @Test fun `INV-A4 linking and unlinking a booking`() = runBlocking {
        val api = alex()
        val month = YearMonth.now()
        var booking: io.github.marcomow.yuvomi.feature.inventory.BudgetBooking? = null
        for (back in 0L..2L) {
            booking = booking ?: api.bookings(month.minusMonths(back).toString()).firstOrNull()
        }
        assumeTrue("no booked budget entry in the seed's recent months", booking != null)
        val created = api.make(InventoryRules.Form(name = unique("Linked")))
        try {
            val linked = api.linkEntry(created.id, booking!!.id, "maintenance", YuvomiClient.newIdempotencyKey())
            assertEquals(listOf("maintenance"), linked.linkedEntries.map { it.role })
            assertEquals(booking.amount, linked.linkedEntriesTotal, 0.001)
            assertTrue(api.history(created.id).timeline.any { it.type == "budget_entry" && it.id == booking.id })
            expectHttp(409, "inventory_booking_already_linked") { api.linkEntry(created.id, booking.id, "maintenance", YuvomiClient.newIdempotencyKey()) }
            val unlinked = api.unlinkEntry(created.id, booking.id)
            assertTrue(unlinked.linkedEntries.isEmpty())
            expectHttp(404, "inventory_booking_not_found") { api.linkEntry(created.id, Int.MAX_VALUE, "purchase", YuvomiClient.newIdempotencyKey()) }
        } finally {
            api.delete(created.id)
        }
        // Creating with `entry_id` links it as the purchase and takes its amount as the price.
        val withEntry = api.make(InventoryRules.Form(name = unique("Bought")), entryId = booking!!.id)
        try {
            assertEquals(listOf("purchase"), withEntry.linkedEntries.map { it.role })
        } finally {
            api.delete(withEntry.id)
        }
    }

    /** INV-A5: categories add, rename, reorder and delete (items fall back to "other"); "other" stays. */
    @Test fun `INV-A5 the category manager's calls`() = runBlocking {
        val api = alex()
        val before = InventoryApi.decodeCategories(api.categories())
        val name = unique("Cat")
        val cat = api.createCategory(name, YuvomiClient.newIdempotencyKey())
        try {
            expectHttp(409, "category_exists") { api.createCategory(name, YuvomiClient.newIdempotencyKey()) }
            assertEquals("$name 2", api.renameCategory(cat.key, "$name 2").name)
            api.reorderCategories(listOf(cat.key) + before.map { it.key })
            assertEquals(cat.key, InventoryApi.decodeCategories(api.categories()).first().key)
            api.reorderCategories(before.map { it.key } + cat.key)
            val item = api.make(InventoryRules.Form(name = unique("In cat"), category = cat.key))
            try {
                api.deleteCategory(cat.key)
                assertEquals("other", api.item(item.id).category)
            } finally {
                api.delete(item.id)
            }
        } finally {
            runCatching { api.deleteCategory(cat.key) }
        }
        expectHttp(404, "inventory_category_not_found") { api.renameCategory(cat.key, "x") }
        expectHttp(400) { api.deleteCategory("other") }
    }

    /** INV-A6: places and sub-places add, rename, reorder and delete; deleting keeps the item without a place. */
    @Test fun `INV-A6 the location manager's calls`() = runBlocking {
        val api = alex()
        val name = unique("Place")
        api.createLocation(name, null, YuvomiClient.newIdempotencyKey())
        val root = InventoryApi.decodeLocations(api.locations()).single { it.name == name }
        try {
            expectHttp(409, "inventory_location_exists") { api.createLocation(name, null, YuvomiClient.newIdempotencyKey()) }
            api.createLocation("Shelf", root.id, YuvomiClient.newIdempotencyKey())
            api.createLocation("Box", root.id, YuvomiClient.newIdempotencyKey())
            var tree = InventoryApi.decodeLocations(api.locations()).single { it.id == root.id }
            assertEquals(listOf("Shelf", "Box"), tree.subcategories.map { it.name })
            val shelf = tree.subcategories.first()
            api.renameLocation(shelf.id, root.id, "Top shelf")
            api.reorderLocations(tree.subcategories.map { it.id }.reversed(), root.id)
            tree = InventoryApi.decodeLocations(api.locations()).single { it.id == root.id }
            assertEquals(listOf("Box", "Top shelf"), tree.subcategories.map { it.name })
            api.renameLocation(root.id, null, "$name 2")
            val all = InventoryApi.decodeLocations(api.locations())
            api.reorderLocations(all.map { it.id }, null)

            val item = api.make(InventoryRules.Form(name = unique("Stored"), locationId = shelf.id))
            try {
                assertEquals("$name 2 · Top shelf", item.locationPath)
                api.deleteLocation(shelf.id, root.id)
                assertNull(api.item(item.id).locationId)
                expectHttp(400, "inventory_selected_location_not_found") {
                    api.update(item.id, InventoryRules.body(InventoryRules.Form(name = "x", locationId = shelf.id), false, true, null, null, null))
                }
            } finally {
                api.delete(item.id)
            }
            expectHttp(404, "inventory_location_not_found") { api.renameLocation(shelf.id, root.id, "y") }
        } finally {
            api.deleteLocation(root.id, null)
        }
        assertFalse(InventoryApi.decodeLocations(api.locations()).any { it.id == root.id })
        expectHttp(404, "inventory_location_not_found") { api.deleteLocation(root.id, null) }
    }

    /** The two reads of other modules the form makes: Budget's month and the documents to link. */
    @Test fun `INV-A7 the booking picker and the documents picker read`() = runBlocking {
        val api = alex()
        val month = api.bookings(YearMonth.now().toString())
        assertTrue(month.all { it.recurrenceParentId == null && !it.isPending })
        assertNotNull(api.documents())
    }
}
