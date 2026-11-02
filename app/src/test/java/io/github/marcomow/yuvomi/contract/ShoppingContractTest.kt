package io.github.marcomow.yuvomi.contract

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.feature.shopping.ShoppingApi
import io.github.marcomow.yuvomi.feature.shopping.PantryTransferRow
import io.github.marcomow.yuvomi.feature.shopping.ShoppingList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/**
 * docs/spec/shopping.md against the real server (SHOP-A8 to SHOP-A13). Every test makes its own
 * lists with [unique] names and deletes them again; the seeded "Weekly Shop" is only read.
 */
class ShoppingContractTest : ContractTestBase() {

    private fun alex(): Pair<ShoppingApi, Int> = signedIn("alex").let { (c, me) -> ShoppingApi(c) to me.user.id }

    private fun key() = YuvomiClient.newIdempotencyKey()

    private fun <T> withList(api: ShoppingApi, block: suspend (ShoppingList) -> T): T = runBlocking {
        val list = api.createList(unique("Contract list"), key())
        try {
            block(list)
        } finally {
            runCatching { api.deleteList(list.id) }
        }
    }

    private fun expectReason(status: Int, reason: String?, block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("expected $status $reason")
        } catch (e: ApiProblem.Http) {
            assertEquals(status, e.status)
            if (reason != null) assertEquals(reason, e.reason)
        }
    }

    // ── SHOP-A8: reads ──

    @Test fun `SHOP-A8 lists, items, categories, shops, versions and suggestions decode`() = runBlocking {
        val (api, _) = alex()
        val lists = ShoppingApi.decodeLists(api.lists())
        val weekly = lists.first { it.name == "Weekly Shop" }
        assertTrue(weekly.itemTotal >= 20)
        assertTrue(weekly.acceptsNewItems)

        val answer = ShoppingApi.decodeItems(api.items(weekly.id))
        assertEquals(weekly.itemTotal, answer.data.size)
        assertEquals(weekly.id, answer.list?.id)
        assertNotNull(answer.version)
        assertTrue(answer.categories.isNotEmpty())
        assertTrue(answer.data.any { it.isChecked } && answer.data.any { !it.isChecked })
        assertTrue(answer.data.first { it.name == "Whole milk" }.tags.contains("organic"))

        val categories = ShoppingApi.decodeCategories(api.categories())
        assertEquals(answer.categories.map { it.name }, categories.map { it.name })
        ShoppingApi.decodeStores(api.stores())

        val versions = api.versions()
        assertTrue(versions.any { it.listId == weekly.id })

        val suggestions = api.suggestions("Whole")
        assertTrue(suggestions.any { it.name == "Whole milk" && it.category != null })
        assertTrue(api.suggestions(" ").isEmpty())

        api.recipients().forEach { assertTrue(it.displayName.isNotEmpty()) }
    }

    // ── SHOP-A9: lists and items ──

    @Test fun `SHOP-A9 create, rename and delete a list`() {
        val (api, _) = alex()
        runBlocking {
            val list = api.createList(unique("Contract list"), key())
            assertEquals(0, list.itemTotal)
            val renamed = api.renameList(list.id, list.name + " renamed")
            assertEquals(list.name + " renamed", renamed.name)
            assertTrue(ShoppingApi.decodeLists(api.lists()).any { it.id == list.id && it.name == renamed.name })
            api.deleteList(list.id)
            assertFalse(ShoppingApi.decodeLists(api.lists()).any { it.id == list.id })
        }
        expectReason(404, "shopping_list_not_found") { api.renameList(999_999, "x") }
        expectReason(404, "shopping_list_not_found") { api.deleteList(999_999) }
    }

    @Test fun `SHOP-A9 an idempotency key makes a repeated create one list`() {
        val (api, _) = alex()
        runBlocking {
            val k = key()
            val name = unique("Contract list")
            val a = api.createList(name, k)
            val b = api.createList(name, k)
            try {
                assertEquals(a.id, b.id)
            } finally {
                api.deleteList(a.id)
                if (b.id != a.id) api.deleteList(b.id)
            }
        }
    }

    @Test fun `SHOP-A9 add, tick, edit and delete an item`() {
        val (api, _) = alex()
        withList(api) { list ->
            val category = ShoppingApi.decodeCategories(api.categories()).first().name
            val v0 = api.versions().first { it.listId == list.id }.version
            val added = api.addItem(list.id, "Oat milk", "2 l", category, key())
            // SHOP-R32: the write frames itself with the list's counter before and after.
            assertEquals(v0, added.listChange?.before)
            assertEquals(api.versions().first { it.listId == list.id }.version, added.listChange?.after)
            assertEquals("Oat milk", added.data.name)
            assertEquals("2 l", added.data.quantity)
            assertEquals(category, added.data.category)
            assertEquals(list.id, added.listChange?.listId)
            assertNotNull(added.listChange?.after)

            val ticked = api.updateItem(added.data.id, buildJsonObject { put("is_checked", 1) })
            assertTrue(ticked.data.isChecked)
            assertEquals("2 l", ticked.data.quantity) // a partial PATCH keeps the rest
            assertTrue(ShoppingApi.decodeItems(api.items(list.id)).data.single().isChecked)
            assertEquals(1, ShoppingApi.decodeLists(api.lists()).first { it.id == list.id }.itemChecked)

            val gone = api.deleteItem(added.data.id)
            assertEquals(list.id, gone.listChange?.listId)
            assertTrue(ShoppingApi.decodeItems(api.items(list.id)).data.isEmpty())
            try {
                api.deleteItem(added.data.id)
                fail()
            } catch (e: ApiProblem.Http) {
                assertEquals("shopping_item_not_found", e.reason)
            }
        }
    }

    @Test fun `SHOP-A9 an item without a category lands in the default one`() {
        val (api, _) = alex()
        withList(api) { list ->
            val names = ShoppingApi.decodeCategories(api.categories()).map { it.name }
            val added = api.addItem(list.id, "Something", null, null, key())
            assertEquals(if ("Sonstiges" in names) "Sonstiges" else names.last(), added.data.category)
            expectReason(400, null) { api.addItem(list.id, "Wrong", null, "No such category ${key()}", key()) }
        }
    }

    @Test fun `SHOP-A9 price, shop, link and note, and moving to another list`() {
        val (api, _) = alex()
        withList(api) { list ->
            val other = api.createList(unique("Contract target"), key())
            val store = api.addStore(unique("Contract shop"), key())
            try {
                // The same name again answers with the existing shop.
                assertEquals(store.id, api.addStore(store.name.uppercase(), key()).id)
                val item = api.addItem(list.id, "Coffee", null, null, key()).data
                val edited = api.updateItem(item.id, buildJsonObject {
                    put("name", "Coffee beans")
                    put("quantity", "500 g")
                    put("price_cents", 899)
                    put("store_id", store.id)
                    put("url", "https://example.org/coffee")
                    put("notes", "whole beans")
                }).data
                assertEquals("Coffee beans", edited.name)
                assertEquals(899L, edited.priceCents)
                assertEquals(store.id, edited.storeId)
                assertEquals("https://example.org/coffee", edited.url)
                assertEquals("whole beans", edited.notes)

                expectReason(400, "shopping_store_not_found") { api.updateItem(item.id, buildJsonObject { put("store_id", 999_999) }) }
                expectReason(400, null) { api.updateItem(item.id, buildJsonObject { put("price_cents", -1) }) }

                // Clearing the price with null keeps the shop.
                val cleared = api.updateItem(item.id, buildJsonObject { put("price_cents", JsonNull) }).data
                assertNull(cleared.priceCents)
                assertEquals(store.id, cleared.storeId)

                val moved = api.updateItem(item.id, buildJsonObject { put("list_id", other.id) }).data
                assertEquals(other.id, moved.listId)
                assertTrue(ShoppingApi.decodeItems(api.items(list.id)).data.isEmpty())
                assertEquals(listOf("Coffee beans"), ShoppingApi.decodeItems(api.items(other.id)).data.map { it.name })
                expectReason(400, "shopping_list_not_found") { api.updateItem(item.id, buildJsonObject { put("list_id", 999_999) }) }

                val renamed = api.renameStore(store.id, store.name + " 2")
                assertEquals(store.name + " 2", renamed.name)
            } finally {
                runCatching { api.deleteList(other.id) }
                runCatching { api.deleteStore(store.id) }
            }
            expectReason(404, "shopping_store_not_found") { api.deleteStore(store.id) }
        }
    }

    @Test fun `SHOP-A9 reorder sends the whole category`() {
        val (api, _) = alex()
        withList(api) { list ->
            val category = ShoppingApi.decodeCategories(api.categories()).first().name
            val a = api.addItem(list.id, "A", null, category, key()).data
            val b = api.addItem(list.id, "B", null, category, key()).data
            val c = api.addItem(list.id, "C", null, category, key()).data
            val answer = api.reorder(list.id, category, listOf(c.id, a.id, b.id))
            assertEquals(listOf(c.id, a.id, b.id), answer.data?.map { it.id })
            assertNotNull(answer.listChange)
            assertEquals(listOf("C", "A", "B"), ShoppingApi.decodeItems(api.items(list.id)).data.map { it.name })
            // A partial order is refused: the ranks would collide.
            expectReason(400, null) { api.reorder(list.id, category, listOf(a.id, b.id)) }
        }
    }

    @Test fun `SHOP-A9 clear checked removes exactly the given ids`() {
        val (api, _) = alex()
        withList(api) { list ->
            val a = api.addItem(list.id, "A", null, null, key()).data
            val b = api.addItem(list.id, "B", null, null, key()).data
            val c = api.addItem(list.id, "C", null, null, key()).data
            listOf(a, b).forEach { api.updateItem(it.id, buildJsonObject { put("is_checked", 1) }) }
            val answer = api.deleteChecked(list.id, listOf(a.id, c.id))
            // c is not checked, so only a goes.
            assertEquals(1, answer.deleted)
            assertNotNull(answer.listChange)
            assertEquals(listOf("B", "C"), ShoppingApi.decodeItems(api.items(list.id)).data.map { it.name }.sorted())
        }
    }

    @Test fun `SHOP-A9 duplicate copies items, resets checks and can drop quantities`() {
        val (api, _) = alex()
        withList(api) { list ->
            val a = api.addItem(list.id, "Rice", "1 kg", null, key()).data
            api.updateItem(a.id, buildJsonObject { put("is_checked", 1); put("notes", "basmati") })
            val copy = api.duplicateList(list.id, list.name + " copy", resetChecked = true, keepQuantities = false, keepNotes = true, key = key())
            try {
                val items = ShoppingApi.decodeItems(api.items(copy.id)).data
                assertEquals(listOf("Rice"), items.map { it.name })
                assertFalse(items.single().isChecked)
                assertNull(items.single().quantity)
                assertEquals("basmati", items.single().notes)
            } finally {
                api.deleteList(copy.id)
            }
            expectReason(404, "shopping_list_not_found") { api.duplicateList(999_999, "x", true, true, true, key()) }
        }
    }

    // ── SHOP-A10: categories ──

    @Test fun `SHOP-A10 add, rename, reorder and delete a category, and its items move on`() {
        val (api, _) = alex()
        val original = runBlocking { ShoppingApi.decodeCategories(api.categories()) }
        val name = unique("Contract cat")
        val created = runBlocking { api.addCategory(name, key()) }
        try {
            expectReason(409, "category_exists") { api.addCategory(name.lowercase(), key()) }
            val renamed = runBlocking { api.renameCategory(created.id, "$name 2") }
            assertEquals("$name 2", renamed.name)
            expectReason(409, "category_exists") { api.renameCategory(created.id, original.first().name) }

            val reordered = runBlocking { api.reorderCategories(listOf(created.id) + original.map { it.id }) }
            assertEquals(created.id, reordered.first().id)
            runBlocking { api.reorderCategories(original.map { it.id } + created.id) }

            withList(api) { list ->
                val item = api.addItem(list.id, "Nails", null, "$name 2", key()).data
                assertEquals("$name 2", item.category)
                api.deleteCategory(created.id)
                val after = ShoppingApi.decodeItems(api.items(list.id)).data.single()
                assertEquals(original.first().name, after.category)
            }
            expectReason(404, "shopping_category_not_found") { api.deleteCategory(created.id) }
        } finally {
            runBlocking {
                runCatching { api.deleteCategory(created.id) }
                runCatching { api.reorderCategories(original.map { it.id }) }
            }
        }
    }

    // ── SHOP-A11: send ──

    @Test fun `SHOP-A11 send answers sent or a named reason`() {
        val (api, me) = alex()
        withList(api) { list ->
            try {
                api.send(list.id, me, key())
                fail("an empty list cannot be sent")
            } catch (e: ApiProblem.Http) {
                assertEquals(422, e.status)
                assertTrue(e.reason in setOf("recipient_no_email", "smtp_unconfigured", "nothing_open"))
            }
            api.addItem(list.id, "Tea", null, null, key())
            try {
                api.send(list.id, me, key())
            } catch (e: ApiProblem.Http) {
                assertEquals(422, e.status)
                assertTrue(e.reason in setOf("recipient_no_email", "smtp_unconfigured"))
            }
            try {
                api.send(list.id, 999_999, key())
                fail()
            } catch (e: ApiProblem.Http) {
                assertEquals(404, e.status)
            }
        }
    }

    // ── SHOP-A12: meal plan ──

    @Test fun `SHOP-A12 meal plan preview and import agree, and undo takes the items back`() {
        val (api, _) = alex()
        withList(api) { list ->
            val from = LocalDate.now().minusDays(7).toString()
            val to = LocalDate.now().plusDays(14).toString()
            val preview = api.importMealPlan(list.id, from, to, preview = true, key = null)
            assertTrue(ShoppingApi.decodeItems(api.items(list.id)).data.isEmpty())
            val result = api.importMealPlan(list.id, from, to, preview = false, key = key())
            assertEquals(preview.transferred, result.transferred)
            assertEquals(preview.added, result.added)
            assertEquals(result.added, result.addedIds.size)
            assertEquals(result.added, ShoppingApi.decodeItems(api.items(list.id)).data.size)
            if (result.addedIds.isNotEmpty()) {
                api.undoTransfer(result.addedIds, result.ingredientIds)
                assertTrue(ShoppingApi.decodeItems(api.items(list.id)).data.isEmpty())
            }
            expectReason(400, null) { api.importMealPlan(list.id, to, from, preview = false, key = key()) }
        }
    }

    // ── SHOP-A13: pantry ──

    @Test fun `SHOP-A13 checked items go into the pantry, and undo takes them out again`() {
        val (api, _) = alex()
        withList(api) { list ->
            val name = unique("Contract jam")
            val item = api.addItem(list.id, name, "2 jars", null, key()).data
            val stock = api.pantry()
            val place = stock.locations.firstOrNull()?.id
            // Not checked yet: skipped.
            val skipped = api.toPantry(list.id, listOf(PantryTransferRow(item.id, 2.0, "jar", place)), key())
            assertEquals(1, skipped.skipped)

            api.updateItem(item.id, buildJsonObject { put("is_checked", 1) })
            val result = api.toPantry(list.id, listOf(PantryTransferRow(item.id, 2.0, "jar", place)), key())
            assertEquals(1, result.added + result.merged)
            assertEquals(1, result.changes.size)
            val row = api.pantry().data.firstOrNull { it.name == name }
            assertNotNull(row)
            assertEquals("jar", row!!.unit)
            assertEquals(place, row.locationId)
            // The transfer deletes nothing on the list; the app clears it with DELETE …/checked.
            assertEquals(1, ShoppingApi.decodeItems(api.items(list.id)).data.size)

            api.undoPantry(result.changes, key())
            assertTrue(api.pantry().data.none { it.name == name })
        }
    }
}
