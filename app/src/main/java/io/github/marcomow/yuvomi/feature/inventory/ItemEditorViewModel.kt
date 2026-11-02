package io.github.marcomow.yuvomi.feature.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.feature.budget.Money
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Base64

/** docs/spec/inventory.md, the form (INV-R13 to R18). */
class ItemEditorViewModel(
    private val container: AppContainer,
    private val host: InventoryHost,
    private val itemId: Int?,
) : ViewModel() {

    /** One entry of the documents field (`renderDocumentAttachField`): linked, or a file waiting for the save. */
    sealed interface Attachment {
        val label: String

        data class Linked(val documentId: Int, override val label: String) : Attachment
        data class Pending(val fileName: String, val mimeType: String, val bytes: ByteArray) : Attachment {
            override val label: String get() = fileName
            override fun equals(other: Any?): Boolean = other is Pending && other.fileName == fileName && other.bytes.contentEquals(bytes)
            override fun hashCode(): Int = fileName.hashCode() * 31 + bytes.contentHashCode()
        }
    }

    /** The booking picker (`openBookingPicker`): a month of bookings, then a role when linking to a saved item. */
    data class Picker(
        val month: String,
        val includeRole: Boolean,
        val entries: List<BudgetBooking>? = null,
        val failed: Boolean = false,
        val picked: BudgetBooking? = null,
        val role: String = "purchase",
    )

    data class UiState(
        val original: InventoryItem? = null,
        val categories: List<InventoryCategory> = emptyList(),
        val locations: List<InventoryLocation> = emptyList(),
        val money: HouseholdMoney? = null,
        val form: InventoryRules.Form = InventoryRules.Form(),
        val attachments: List<Attachment> = emptyList(),
        val pickedBooking: BudgetBooking? = null,
        val picker: Picker? = null,
        val documents: List<LinkableDocument>? = null,
        val loading: Boolean = true,
        val error: String? = null,
        val saving: Boolean = false,
        val nameError: Boolean = false,
        val priceError: String? = null,
        val done: Boolean = false,
        val today: LocalDate = LocalDate.now(),
    ) {
        val isEdit: Boolean get() = original != null
        val tracksOdometer: Boolean get() = InventoryRules.tracksOdometer(form.category, categories)

        /** `itemCurrency`. */
        val currency: String get() = original?.currency ?: money?.currency ?: "EUR"
    }

    private val _state = MutableStateFlow(UiState(today = host.today()))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val api: InventoryApi? get() = container.session.connection?.client?.let(::InventoryApi)
    private val createKey = YuvomiClient.newIdempotencyKey()

    init {
        load()
    }

    private suspend fun <T> orCache(key: String, decode: (String) -> T, call: suspend () -> kotlinx.serialization.json.JsonElement, parse: (kotlinx.serialization.json.JsonElement) -> T): T = try {
        parse(call())
    } catch (e: ApiProblem.Network) {
        container.cache.read(key)?.let { decode(it.json) } ?: throw e
    }

    fun load() {
        val api = api ?: return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val categories = orCache(InventoryViewModel.CACHE_CATEGORIES, { InventoryApi.decodeCategories(ApiJson.parseToJsonElement(it)) }, { api.categories() }, InventoryApi::decodeCategories)
                val locations = orCache(InventoryViewModel.CACHE_LOCATIONS, { InventoryApi.decodeLocations(ApiJson.parseToJsonElement(it)) }, { api.locations() }, InventoryApi::decodeLocations)
                val original = itemId?.let { api.item(it) }
                val money = InventoryBus.money(container, host.strings)
                val form = InventoryRules.form(original) { amount, currency -> money.money.toInput(amount, currency ?: money.currency) }
                _state.update {
                    it.copy(
                        original = original,
                        categories = categories,
                        locations = locations,
                        money = money,
                        form = form,
                        attachments = original?.attachments.orEmpty().mapNotNull { a -> a.documentId?.let { id -> Attachment.Linked(id, a.label) } },
                        loading = false,
                    )
                }
            } catch (e: ApiProblem) {
                if (InventoryRules.isGone(e, "inventory_item_not_found")) {
                    host.notify(InventoryRules.errorSentence(e, host.strings))
                    InventoryBus.changed()
                    _state.update { it.copy(done = true) }
                    return@launch
                }
                _state.update { it.copy(loading = false, error = InventoryRules.loadErrorSentence(e, host.strings)) }
            }
        }
    }

    fun edit(change: (InventoryRules.Form) -> InventoryRules.Form) = _state.update { s ->
        val next = change(s.form)
        s.copy(form = next, nameError = s.nameError && next.name.isBlank(), priceError = if (next.price != s.form.price) null else s.priceError)
    }

    fun setAttachments(list: List<Attachment>) = _state.update { it.copy(attachments = list) }

    fun loadDocuments() {
        val api = api ?: return
        viewModelScope.launch {
            try {
                _state.update { it.copy(documents = api.documents()) }
            } catch (e: ApiProblem) {
                host.notify(host.strings.t("documentAttach.loadFailed"))
            }
        }
    }

    fun closeDocuments() = _state.update { it.copy(documents = null) }

    // ── Booking picker ──

    fun openPicker(includeRole: Boolean) {
        val s = state.value
        val month = (if (includeRole) s.original?.purchaseDate?.take(7) else null) ?: s.today.toString().take(7)
        _state.update { it.copy(picker = Picker(month, includeRole)) }
        loadMonth()
    }

    fun shiftPicker(n: Int) {
        _state.update { s -> s.copy(picker = s.picker?.let { Picker(InventoryRules.shiftMonth(it.month, n), it.includeRole) }) }
        loadMonth()
    }

    private fun loadMonth() {
        val api = api ?: return
        val month = state.value.picker?.month ?: return
        viewModelScope.launch {
            val result = runCatching { api.bookings(month) }
            _state.update { s ->
                val p = s.picker?.takeIf { it.month == month } ?: return@update s
                s.copy(picker = p.copy(entries = result.getOrNull() ?: emptyList(), failed = result.isFailure))
            }
        }
    }

    fun closePicker() = _state.update { it.copy(picker = null) }

    fun pickBooking(entry: BudgetBooking?) {
        val p = state.value.picker ?: return
        if (entry == null) {
            _state.update { it.copy(picker = p.copy(picked = null)) }
            return
        }
        if (p.includeRole) {
            _state.update { it.copy(picker = p.copy(picked = entry)) }
            return
        }
        // Create flow: remembered until the save; an empty price takes the booking's amount.
        _state.update { s ->
            val money = s.money?.money
            val price = if (s.form.price.isBlank() && money != null) money.toInput(kotlin.math.abs(entry.amount), s.currency) else s.form.price
            s.copy(picker = null, pickedBooking = entry, form = s.form.copy(price = price))
        }
    }

    fun setRole(role: String) = _state.update { s -> s.copy(picker = s.picker?.copy(role = role)) }

    fun clearPickedBooking() = _state.update { it.copy(pickedBooking = null) }

    /** Edit flow: the booking links at once (`add-booking`). */
    fun confirmLink() {
        val api = api ?: return
        val p = state.value.picker ?: return
        val entry = p.picked ?: return
        val item = state.value.original ?: return
        _state.update { it.copy(picker = null) }
        viewModelScope.launch {
            try {
                val updated = api.linkEntry(item.id, entry.id, p.role, YuvomiClient.newIdempotencyKey())
                _state.update { it.copy(original = updated) }
                InventoryBus.changed()
            } catch (e: ApiProblem) {
                gone(e)
            }
        }
    }

    fun unlink(entry: LinkedEntry) {
        val api = api ?: return
        val item = state.value.original ?: return
        viewModelScope.launch {
            try {
                val updated = api.unlinkEntry(item.id, entry.entryId)
                _state.update { it.copy(original = updated) }
                InventoryBus.changed()
            } catch (e: ApiProblem) {
                gone(e)
            }
        }
    }

    private suspend fun gone(e: ApiProblem) {
        host.notify(InventoryRules.errorSentence(e, host.strings))
        if (InventoryRules.isGone(e, "inventory_item_not_found")) {
            InventoryBus.changed()
            _state.update { it.copy(done = true) }
        }
    }

    // ── Save ──

    private suspend fun commitAttachments(api: InventoryApi, s: UiState): List<Int> {
        val ids = mutableListOf<Int>()
        for (a in s.attachments) {
            when (a) {
                is Attachment.Linked -> ids += a.documentId
                is Attachment.Pending -> {
                    val name = host.strings.t("inventory.attachmentDocumentName", "name" to s.form.name.trim().ifEmpty { a.fileName })
                    val dataUrl = "data:${a.mimeType};base64," + Base64.getEncoder().encodeToString(a.bytes)
                    ids += api.upload(name, a.fileName, dataUrl, s.form.attachmentCategory, host.strings.t("documents.inventoryFolder"), YuvomiClient.newIdempotencyKey()).id
                }
            }
        }
        return ids
    }

    fun save() {
        val api = api ?: return
        val s = state.value
        if (!host.canWrite || s.saving || s.loading) return
        if (s.form.name.isBlank()) {
            _state.update { it.copy(nameError = true) }
            return
        }
        val money = s.money?.money
        if (host.showsPrices && money != null) {
            val problem = money.fieldError(s.form.price, s.currency, host.strings, required = false, sign = Money.Sign.NON_NEGATIVE, original = s.original?.purchasePrice, originalCurrency = s.original?.currency)
            if (problem != null) {
                _state.update { it.copy(priceError = problem) }
                return
            }
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val attachments = if (host.showsDocuments) commitAttachments(api, s) else null
                val price = if (host.showsPrices) money?.parse(s.form.price)?.takeIf { !it.isNaN() } else null
                val body = InventoryRules.body(s.form, s.tracksOdometer, host.showsPrices, price, attachments, s.pickedBooking?.id?.takeIf { s.original == null })
                val original = s.original
                if (original == null) api.create(body, createKey) else api.update(original.id, body)
                InventoryBus.changed()
                _state.update { it.copy(saving = false, done = true) }
                host.notify(host.strings.t(if (original == null) "inventory.created" else "inventory.updated"))
            } catch (e: ApiProblem) {
                _state.update { it.copy(saving = false) }
                host.notify(InventoryRules.errorSentence(e, host.strings))
                when ((e as? ApiProblem.Http)?.reason) {
                    "inventory_selected_location_not_found" -> offerCurrentLocations()
                    "inventory_booking_not_found" -> clearPickedBooking()
                    "inventory_item_not_found" -> if (s.original != null) {
                        InventoryBus.changed()
                        _state.update { it.copy(done = true) }
                    }
                }
            }
        }
    }

    /** `offerCurrentLocations`: today's places, and no place chosen. */
    private suspend fun offerCurrentLocations() {
        val api = api ?: return
        val locations = runCatching { InventoryApi.decodeLocations(api.locations()) }.getOrNull() ?: return
        _state.update { it.copy(locations = locations, form = it.form.copy(locationId = null)) }
    }

    fun delete() {
        val item = state.value.original ?: return
        if (!InventoryRules.mayChange(item, host.canWrite, host.userId, host.editOthers)) return
        _state.update { it.copy(done = true) }
        InventoryBus.delete(container, host, item)
    }
}
