package io.github.marcomow.yuvomi.feature.inventory

import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.Operation
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.feature.budget.Money
import io.github.marcomow.yuvomi.feature.budget.RegionPresets
import io.github.marcomow.yuvomi.feature.shell.ShellContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate

/**
 * What the inventory screens need from the shell: strings, snackbars, the household's today and
 * what the member may do. An interface so the view models are tested without a shell.
 */
interface InventoryHost {
    val strings: Strings
    val userId: Int

    /** `inventory: write` (`readOnly()`). */
    val canWrite: Boolean

    /** `inventory_edit_others` (`canEditOthers`): change items someone else created. */
    val editOthers: Boolean

    /** `inventory_view_prices` (`canSeeInventoryPrices`). */
    val showsPrices: Boolean

    /** Budget is readable: linked bookings and the booking picker (`pathAccess('/budget')`). */
    val showsBudget: Boolean

    /** Documents is readable: attachments are listed (`pathAccess('/documents')`). */
    val showsDocuments: Boolean

    /** Documents is writable: new files can be uploaded from the form. */
    val uploadsDocuments: Boolean

    fun today(): LocalDate

    suspend fun notify(message: String, action: String? = null): Boolean
}

internal class ShellInventoryHost(private val shell: ShellContext, override val strings: Strings) : InventoryHost {
    override val userId: Int get() = shell.session.user.id
    override val canWrite: Boolean get() = shell.session.access("inventory") == "write"
    override val editOthers: Boolean get() = shell.session.isAdmin || shell.session.permissions.capability("inventory_edit_others")
    override val showsPrices: Boolean get() = shell.session.isAdmin || shell.session.permissions.capability("inventory_view_prices")
    override val showsBudget: Boolean get() = shell.shows("budget")
    override val showsDocuments: Boolean get() = shell.shows("documents")
    override val uploadsDocuments: Boolean get() = shell.shows("documents") && shell.session.access("documents") == "write"

    override fun today(): LocalDate = shell.time.today()

    override suspend fun notify(message: String, action: String?): Boolean = shell.notify(message, action)
}

/** The household's currency and number locale, as `money.js` formats with them. */
data class HouseholdMoney(val currency: String, val money: Money)

/**
 * What the inventory screens share without sharing a view model: a change counter the list and
 * the read view reload on, the items in their undo window, and the household's money.
 */
object InventoryBus {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private val _pending = MutableStateFlow<Set<Int>>(emptySet())
    val pending: StateFlow<Set<Int>> = _pending.asStateFlow()

    /** Per server: a second household in the same process has its own currency. */
    @Volatile private var money: Pair<String, HouseholdMoney>? = null

    fun changed() = _version.update { it + 1 }

    /** `GET /preferences`: `currency` and the region's number locale; EUR and the UI language when it fails. */
    suspend fun money(container: AppContainer, strings: Strings): HouseholdMoney {
        val server = container.session.connection?.server.orEmpty()
        money?.takeIf { it.first == server }?.let { return it.second }
        val data = runCatching { (container.session.connection?.client?.call(Operation.PREFERENCES) as? JsonObject)?.get("data") as? JsonObject }.getOrNull()
        fun s(key: String) = (data?.get(key) as? JsonPrimitive)?.contentOrNull
        val currency = s("currency") ?: "EUR"
        val locale = RegionPresets.numberLocaleFor(s("region"), currency, s("date_format"), s("time_format"))
        return HouseholdMoney(currency, Money.of(locale, strings.locale, currency)).also { if (data != null) money = server to it }
    }

    /** `removeItem`: hidden at once, deleted when the snackbar goes without "Undo" (`scheduleUndoableDelete`). */
    fun delete(container: AppContainer, host: InventoryHost, item: InventoryItem) {
        val api = container.session.connection?.client?.let(::InventoryApi) ?: return
        _pending.update { it + item.id }
        container.scope.launch {
            val strings = host.strings
            if (host.notify(strings.t("common.deletedNamed", "name" to item.name), strings.t("common.undo"))) {
                _pending.update { it - item.id }
                return@launch
            }
            var failure: ApiProblem? = null
            try {
                api.delete(item.id)
            } catch (e: ApiProblem) {
                if (!InventoryRules.isGone(e, "inventory_item_not_found")) failure = e
            }
            _pending.update { it - item.id }
            changed()
            failure?.let { host.notify(InventoryRules.errorSentence(it, strings)) }
        }
    }
}
