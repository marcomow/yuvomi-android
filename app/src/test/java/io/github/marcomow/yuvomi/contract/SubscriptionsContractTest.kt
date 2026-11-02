package io.github.marcomow.yuvomi.contract

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.feature.subscriptions.Subscription
import io.github.marcomow.yuvomi.feature.subscriptions.SubscriptionErrors
import io.github.marcomow.yuvomi.feature.subscriptions.SubscriptionForm
import io.github.marcomow.yuvomi.feature.subscriptions.SubscriptionFormRules
import io.github.marcomow.yuvomi.feature.subscriptions.SubscriptionMeta
import io.github.marcomow.yuvomi.feature.subscriptions.SubscriptionQuery
import io.github.marcomow.yuvomi.feature.subscriptions.SubscriptionSettings
import io.github.marcomow.yuvomi.feature.subscriptions.SubscriptionsApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/**
 * docs/spec/subscriptions.md against upstream's image at the pinned version, seeded by `seed-demo.js`.
 * Every test makes its own subscriptions, categories and payment methods and removes them again.
 */
class SubscriptionsContractTest : ContractTestBase() {
    private fun api(user: String = "alex") = SubscriptionsApi(signedIn(user).first)

    private fun expectHttp(status: Int, reason: String? = null, block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("expected HTTP $status")
        } catch (e: ApiProblem.Http) {
            assertEquals(status, e.status)
            if (reason != null) assertEquals(reason, e.reason)
        }
    }

    private val today = LocalDate.now()

    private fun body(name: String, extra: Map<String, Any?> = emptyMap()): JsonObject {
        val form = SubscriptionForm.blank(SubscriptionSettings(90.0, "EUR"), today.plusDays(10)).copy(name = name, amount = "9.99")
        val base = SubscriptionFormRules.body(form, null, SubscriptionMeta(), "en")
        return JsonObject(base + extra.mapValues { (_, v) -> when (v) { null -> kotlinx.serialization.json.JsonNull; is Number -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) } })
    }

    /** SUBS-A13: list, meta and settings decode; the seeded list sorts by next payment. */
    @Test fun `SUBS-A13 the seeded subscriptions decode with totals`() = runBlocking {
        val api = api()
        val list = SubscriptionsApi.decodeList(api.list(SubscriptionQuery()))
        assertTrue(list.subscriptions.size >= 8)
        assertEquals(list.subscriptions.map { it.nextPaymentDate }.sorted(), list.subscriptions.map { it.nextPaymentDate })
        assertNotNull(list.summary)
        assertEquals("EUR", list.summary!!.baseCurrency)
        assertTrue(list.subscriptions.any { it.endType == Subscription.END_ON_DATE })
        val meta = SubscriptionsApi.decodeMeta(api.meta())
        assertTrue(meta.categories.size >= 6)
        assertTrue(meta.paymentMethods.isNotEmpty())
        assertEquals(listOf("daily", "weekly", "monthly", "yearly"), meta.billingCycles)
        val settings = SubscriptionsApi.decodeSettings(api.settings())
        assertEquals("EUR", settings.baseCurrency)
        val paused = SubscriptionsApi.decodeList(api.list(SubscriptionQuery(status = "paused"))).subscriptions
        assertTrue(paused.all { it.effectiveStatus == Subscription.STATUS_PAUSED })
        val search = SubscriptionsApi.decodeList(api.list(SubscriptionQuery(q = "Netflix"))).subscriptions
        assertTrue(search.isNotEmpty() && search.all { "Netflix" in it.name })
    }

    /** SUBS-A14: create, edit, a payment and its undo, delete; deleting twice is "already deleted". */
    @Test fun `SUBS-A14 create, renew with undo and delete`() = runBlocking {
        val api = api()
        val name = unique("Sub")
        val created = api.create(body(name), YuvomiClient.newIdempotencyKey())
        try {
            assertEquals(name, created.name)
            assertEquals(9.99, created.amount, 0.001)
            assertTrue(created.enabled)
            val edited = api.update(created.id, JsonObject(body(name + " edited") + ("notes" to JsonPrimitive("#tag"))))
            assertEquals("#tag", edited.notes)
            val renewal = api.renew(created.id, YuvomiClient.newIdempotencyKey())
            assertEquals(LocalDate.parse(created.nextPaymentDate).plusMonths(1).toString(), renewal.subscription.nextPaymentDate)
            assertNotNull(renewal.undoToken)
            val undone = api.undoRenew(created.id, renewal.undoToken!!, YuvomiClient.newIdempotencyKey())
            assertEquals(created.nextPaymentDate, undone.nextPaymentDate)
            expectHttp(409, SubscriptionErrors.UNDO_STALE) { api.undoRenew(created.id, renewal.undoToken!!, YuvomiClient.newIdempotencyKey()) }
        } finally {
            api.delete(created.id)
        }
        expectHttp(404, SubscriptionErrors.SUBSCRIPTION_GONE) { api.delete(created.id) }
    }

    /** SUBS-A15: the server's own checks with their reasons. */
    @Test fun `SUBS-A15 refusals carry their reasons`() = runBlocking {
        val api = api()
        expectHttp(400, SubscriptionErrors.SELECTED_CATEGORY_GONE) { api.create(body(unique("Sub"), mapOf("category_id" to 999_999)), YuvomiClient.newIdempotencyKey()) }
        expectHttp(400, "subscriptions_end_before_next_payment") {
            api.create(body(unique("Sub"), mapOf("end_type" to "on_date", "end_date" to today.toString())), YuvomiClient.newIdempotencyKey())
        }
        val last = api.create(body(unique("Sub"), mapOf("end_type" to "after_count", "occurrence_count" to 1)), YuvomiClient.newIdempotencyKey())
        try {
            assertTrue(api.renew(last.id, YuvomiClient.newIdempotencyKey()).subscription.isCompleted)
            expectHttp(409, SubscriptionErrors.COMPLETED) { api.renew(last.id, YuvomiClient.newIdempotencyKey()) }
        } finally {
            api.delete(last.id)
        }
        expectHttp(400) { api.logoSearch("", YuvomiClient.newIdempotencyKey()) }
    }

    /** SUBS-A16: categories and payment methods: create, rename, order, delete; a duplicate is refused. */
    @Test fun `SUBS-A16 categories and payment methods`() = runBlocking {
        val api = api()
        val name = unique("Cat")
        val category = api.createCategory(name, "#3B82F6", YuvomiClient.newIdempotencyKey())
        try {
            assertEquals("#3B82F6", category.color)
            expectHttp(409, "subscriptions_category_exists") { api.createCategory(name, "#3B82F6", YuvomiClient.newIdempotencyKey()) }
            assertEquals("$name 2", api.updateCategory(category.id, "$name 2", "#16A34A").name)
            val ids = SubscriptionsApi.decodeMeta(api.meta()).categories.map { it.id }
            api.reorder("categories", ids)
            assertEquals(ids, SubscriptionsApi.decodeMeta(api.meta()).categories.map { it.id })
        } finally {
            api.deleteCategory(category.id)
        }
        expectHttp(404, SubscriptionErrors.CATEGORY_GONE) { api.deleteCategory(category.id) }

        val method = api.createMethod(unique("Pay"), YuvomiClient.newIdempotencyKey())
        try {
            assertEquals("${method.name}!", api.updateMethod(method.id, "${method.name}!").name)
            val ids = SubscriptionsApi.decodeMeta(api.meta()).paymentMethods.map { it.id }
            api.reorder("payment_methods", ids)
        } finally {
            api.deleteMethod(method.id)
        }
        expectHttp(404, SubscriptionErrors.METHOD_GONE) { api.updateMethod(method.id, "x") }
    }

    /** SUBS-A17: the settings write back what they read. */
    @Test fun `SUBS-A17 settings round trip`() = runBlocking {
        val api = api()
        val before = SubscriptionsApi.decodeSettings(api.settings())
        val saved = api.saveSettings(before.monthlyBudget, before.baseCurrency)
        assertEquals(before, saved)
        expectHttp(400) { api.saveSettings(-1.0, "EUR") }
        assertFalse(SubscriptionsApi.decodeSettings(api.settings()).monthlyBudget < 0)
    }
}
