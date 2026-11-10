package io.github.marcomow.yuvomi.contract

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.feature.pantry.PantryApi
import io.github.marcomow.yuvomi.feature.pantry.PantryApi.ItemFields
import io.github.marcomow.yuvomi.feature.pantry.PantryApi.TransferEntry
import io.github.marcomow.yuvomi.feature.pantry.PantryItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * docs/spec/pantry.md against the real server (skipped without `YUVOMI_CONTRACT_URL`). Every
 * test makes its own fixtures with unique names and removes them again.
 */
class PantryContractTest : ContractTestBase() {

    /** A category the server knows; the English seed renames "Sonstiges" to "Other". */
    private var category = "Sonstiges"

    private fun alex(): PantryApi {
        val api = PantryApi(signedIn("alex").first)
        category = runBlocking { PantryApi.decodeStock(api.stock()).categories.last().name }
        return api
    }

    private fun fields(name: String, quantity: Double = 1.0, unit: String = "pcs", locationId: Int? = null, min: Double? = null) =
        ItemFields(name, quantity, unit, locationId, category, null, min, null)

    private suspend fun PantryApi.batch(id: Int): PantryItem? = PantryApi.decodeStock(stock()).items.firstOrNull { it.id == id }

    private suspend fun expectHttp(status: Int, block: suspend () -> Unit): ApiProblem.Http {
        try {
            block()
        } catch (e: ApiProblem.Http) {
            assertEquals(status, e.status)
            return e
        }
        fail("expected HTTP $status")
        error("unreachable")
    }

    /** PANTRY-A1. */
    @Test fun `PANTRY-A1 the seeded stock decodes with products, locations and categories`() = runBlocking<Unit> {
        val stock = PantryApi.decodeStock(alex().stock())
        assertTrue(stock.items.isNotEmpty())
        assertTrue(stock.products.isNotEmpty())
        assertTrue(stock.locations.isNotEmpty())
        assertTrue(stock.categories.any { it.name.isNotEmpty() })
        // Every seeded batch belongs to a household product (#1448) and has a known unit.
        assertTrue(stock.items.all { it.productId != null })
        assertTrue(stock.items.all { it.locationId == null || it.locationName != null })
    }

    /** PANTRY-A7 (POST, PUT, PATCH, DELETE /pantry/{itemId}). */
    @Test fun `PANTRY-A7 create, edit, step and delete a batch`() = runBlocking<Unit> {
        val api = alex()
        val location = PantryApi.decodeStock(api.stock()).locations.first()
        val name = unique("Oats")
        val key = YuvomiClient.newIdempotencyKey()
        val created = api.create(fields(name, 2.0, "kg", location.id, min = 1.0), key)
        try {
            assertEquals(name, created.name)
            assertEquals(2.0, created.quantity, 0.0)
            assertEquals("kg", created.unit)
            assertEquals(1.0, created.minQuantity!!, 0.0)
            assertNotNull(created.productId)
            // A retry with the same key makes no second batch.
            assertEquals(created.id, api.create(fields(name, 2.0, "kg", location.id, min = 1.0), key).id)
            assertEquals(location.name, api.batch(created.id)?.locationName)

            val edited = api.update(created.id, ItemFields(name, 3.0, "kg", null, category, "2027-01-31", null, "opened"))
            assertEquals(3.0, edited.quantity, 0.0)
            assertNull(edited.locationId)
            assertEquals("2027-01-31", edited.expiresOn)
            assertNull(edited.minQuantity)
            assertEquals("opened", edited.notes)

            // The stepper writes the quantity only; the rest stays.
            val stepped = api.setQuantity(created.id, 2.5)
            assertEquals(2.5, stepped.quantity, 0.0)
            assertEquals("opened", stepped.notes)
            assertEquals("2027-01-31", stepped.expiresOn)
        } finally {
            api.delete(created.id)
        }
        assertNull(api.batch(created.id))
        // Upstream answers a missing batch with a 404 without reason.
        val gone = expectHttp(404) { api.delete(created.id) }
        assertNull(gone.reason)
        expectHttp(404) { api.setQuantity(created.id, 1.0) }
    }

    /** PANTRY-A8 (DELETE /pantry/products/{productId}). */
    @Test fun `PANTRY-A8 a product is deleted only without batches`() = runBlocking<Unit> {
        val api = alex()
        val batch = api.create(fields(unique("Yeast"), 1.0, "pkg"), YuvomiClient.newIdempotencyKey())
        val productId = batch.productId!!
        val refused = expectHttp(409) { api.deleteProduct(productId) }
        assertEquals("pantry_product_has_batches", refused.reason)
        api.delete(batch.id)
        // The product outlives its last batch and is listed without one.
        val left = PantryApi.decodeStock(api.stock()).products.first { it.id == productId }
        assertEquals(0, left.batchCount)
        api.deleteProduct(productId)
        assertTrue(PantryApi.decodeStock(api.stock()).products.none { it.id == productId })
        expectHttp(404) { api.deleteProduct(productId) }
    }

    /** PANTRY-A9 (the five location operations). */
    @Test fun `PANTRY-A9 locations are added, renamed, reordered and deleted`() = runBlocking<Unit> {
        val api = alex()
        val before = api.locations()
        assertEquals(before.sortedBy { it.sortOrder }.map { it.id }, before.map { it.id })
        val name = unique("Shelf")
        val created = api.createLocation(name, YuvomiClient.newIdempotencyKey())
        var batchId: Int? = null
        try {
            assertEquals(name, created.name)
            assertEquals(created.id, api.locations().last().id)
            expectHttp(409) { api.createLocation(name.uppercase(), YuvomiClient.newIdempotencyKey()) }

            val renamed = api.renameLocation(created.id, "$name 2")
            assertEquals("$name 2", renamed.name)
            expectHttp(409) { api.renameLocation(created.id, before.first().name) }

            val reordered = api.reorderLocations(listOf(created.id) + before.map { it.id })
            assertEquals(created.id, reordered.first().id)
            assertEquals(before.map { it.id } + created.id, api.reorderLocations(before.map { it.id } + created.id).map { it.id })

            // Deleting a location keeps its batches, without a place.
            batchId = api.create(fields(unique("Jam"), 1.0, "jar", created.id), YuvomiClient.newIdempotencyKey()).id
            assertEquals(1, api.deleteLocation(created.id))
            assertNull(api.batch(batchId)?.locationId)
            expectHttp(404) { api.deleteLocation(created.id) }
        } finally {
            batchId?.let { runCatching { api.delete(it) } }
            if (api.locations().any { it.id == created.id }) api.deleteLocation(created.id)
        }
    }

    /** PANTRY-A10 (POST /shopping/{listId}/import-pantry; the undo is core). */
    @Test fun `PANTRY-A10 low stock goes on a list once, and undo takes it off`() = runBlocking<Unit> {
        val api = alex()
        val list = api.shoppingLists().first()
        val batch = api.create(fields(unique("Passata"), 1.0, "can", min = 3.0), YuvomiClient.newIdempotencyKey())
        try {
            val entry = TransferEntry.Batch(batch.id, "2 cans")
            val first = api.toShopping(list.id, listOf(entry), YuvomiClient.newIdempotencyKey())
            assertEquals(1, first.added)
            assertEquals(1, first.addedIds.size)
            // Already open on the list: skipped, not doubled.
            val again = api.toShopping(list.id, listOf(entry), YuvomiClient.newIdempotencyKey())
            assertEquals(0, again.added)
            assertEquals(1, again.skipped)
            api.undoTransfer(first.addedIds)
            assertEquals(1, api.toShopping(list.id, listOf(entry), YuvomiClient.newIdempotencyKey()).also { api.undoTransfer(it.addedIds) }.added)

            // PANTRY-A23: the minimum and the total belong to the product (upstream 2.85.0).
            val productId = batch.productId!!
            val listed = PantryApi.decodeStock(api.stock()).products.first { it.id == productId }
            assertEquals(3.0, listed.minQuantity!!, 0.0)
            assertEquals(1.0, listed.totalQuantity!!, 0.0)

            // A product without batches goes over by its id, with the shortfall as display text.
            api.delete(batch.id)
            val product = api.toShopping(list.id, listOf(TransferEntry.Product(productId, "3 cans")), YuvomiClient.newIdempotencyKey())
            assertEquals(1, product.added)
            api.undoTransfer(product.addedIds)
            api.deleteProduct(productId)
        } finally {
            runCatching { api.delete(batch.id) }
        }
        val gone = expectHttp(404) { api.toShopping(Int.MAX_VALUE, listOf(TransferEntry.Batch(batch.id, null)), YuvomiClient.newIdempotencyKey()) }
        assertEquals("shopping_list_not_found", gone.reason)
    }
}
