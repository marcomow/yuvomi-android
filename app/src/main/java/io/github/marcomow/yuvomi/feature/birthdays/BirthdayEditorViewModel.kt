package io.github.marcomow.yuvomi.feature.birthdays

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/** docs/spec/birthdays.md, the editor (`openBirthdayModal`, BDAY-R11 to R13). */
class BirthdayEditorViewModel(
    private val container: AppContainer,
    private val host: BirthdaysHost,
    /** null: a new birthday. */
    val birthday: Birthday?,
    private val today: () -> LocalDate,
) : ViewModel() {

    data class Form(
        val name: String = "",
        val photoData: String? = null,
        val yearUnknown: Boolean = false,
        /** With a year: the date picker's value. */
        val birthDate: LocalDate? = null,
        /** Without a year: month and day. */
        val month: Int? = null,
        val day: Int? = null,
        /** `""` none, else the contact's id. */
        val contactId: String = "",
        val nameDayMonth: Int? = null,
        val nameDayDay: Int? = null,
        val notes: String = "",
        val reminder: BirthdaysRules.Reminder = BirthdaysRules.Reminder(BirthdaysRules.NEW_REMINDER, "1", "days"),
    )

    data class UiState(
        val form: Form,
        /** Who the link field offers; null until loaded (or without access). */
        val linkable: List<ImportCandidate>? = null,
        val saving: Boolean = false,
        /** The field a check stopped at, with its sentence. */
        val error: Pair<String, String>? = null,
        val saved: Birthday? = null,
        val gone: Boolean = false,
    )

    val isEdit: Boolean get() = birthday != null

    /** `mayLinkContacts`: a member's birthday belongs to the account, not to a contact. */
    val mayLink: Boolean get() = host.contactsVisible && birthday?.familyUserId == null

    private val opened: Form = birthday?.let(::formOf) ?: Form()
    private val _state = MutableStateFlow(UiState(opened))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val api: BirthdaysApi? get() = container.session.connection?.client?.let(::BirthdaysApi)
    private val strings get() = host.strings

    init {
        if (mayLink) loadLinkable()
    }

    private fun formOf(b: Birthday): Form {
        val date = b.birthDate?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
        val nameDay = b.nameDay?.split('-')?.mapNotNull { it.toIntOrNull() }
        return Form(
            name = b.name,
            photoData = b.photoData,
            yearUnknown = b.yearUnknown,
            birthDate = if (b.yearUnknown) null else date,
            month = date?.monthValue,
            day = date?.dayOfMonth,
            contactId = b.contactId?.toString().orEmpty(),
            nameDayMonth = nameDay?.getOrNull(0),
            nameDayDay = nameDay?.getOrNull(1),
            notes = b.notes.orEmpty(),
            reminder = BirthdaysRules.Reminder(
                BirthdaysRules.storedOffset(b),
                b.reminderCustomAmount?.takeIf { it.isNotEmpty() && it != "0" } ?: "1",
                b.reminderCustomUnit?.takeIf { it.isNotEmpty() } ?: "days",
            ),
        )
    }

    fun edit(change: (Form) -> Form) = _state.update { it.copy(form = change(it.form), error = null) }

    /** `fillContactLinkOptions`; a failure leaves the field as it is. */
    fun loadLinkable() {
        val api = api ?: return
        viewModelScope.launch {
            val candidates = runCatching { api.candidates() }.getOrNull() ?: return@launch
            val current = birthday?.contactId
            val list = BirthdaysRules.linkable(candidates, current)
            _state.update { s ->
                val keep = s.form.contactId
                val stillThere = keep.isEmpty() || list.any { it.id.toString() == keep }
                s.copy(linkable = list, form = if (stillThere) s.form else s.form.copy(contactId = ""))
            }
        }
    }

    /** The checks and the body of `#bd-save`; returns null when saved (see [UiState.saved]). */
    suspend fun save(): String? {
        val f = state.value.form
        val name = f.name.trim()
        if (name.isEmpty()) return fail("name", strings.t("common.nameRequired"))
        val birthDate: String = if (f.yearUnknown) {
            val m = f.month
            val d = f.day
            if (m == null || d == null || d > BirthdaysRules.daysInMonth(m)) return fail("date", strings.t("birthdays.yearlessIncomplete"))
            "2000-%02d-%02d".format(m, d)
        } else {
            val d = f.birthDate ?: return fail("date", strings.t("common.required"))
            if (d.isAfter(today())) return fail("date", strings.t("common.required"))
            d.toString()
        }
        val nameDay = BirthdaysRules.normalizeNameDay(f.nameDayMonth, f.nameDayDay)
        if (!nameDay.complete) return fail("nameDay", strings.t("birthdays.nameDayIncomplete"))
        // The reminder goes out only when someone changed it (#1363); a new birthday writes what it shows.
        val reminderChanged = !isEdit || f.reminder != opened.reminder
        val reminder = if (reminderChanged) {
            BirthdaysRules.reminderToSave(f.reminder)
                ?: return fail("reminder", strings.t("birthdays.reminderAmountInvalid", "max" to BirthdaysRules.REMINDER_AMOUNT_MAX))
        } else {
            emptyMap()
        }
        val body = buildJsonObject {
            put("name", name)
            put("birth_date", birthDate)
            put("year_unknown", f.yearUnknown)
            put("name_day", nameDay.value?.let(::JsonPrimitive) ?: JsonNull)
            put("notes", f.notes.trim())
            put("photo_data", f.photoData?.let(::JsonPrimitive) ?: JsonNull)
            // The link goes out only when it changed, like the reminder.
            if (mayLink && f.contactId != opened.contactId) {
                put("contact_id", f.contactId.toIntOrNull()?.let(::JsonPrimitive) ?: JsonNull)
            }
            reminder.forEach { (k, v) -> put(k, v) }
        }
        if (container.session.connection == null) return strings.t("common.errorGeneric")
        val api = api ?: return strings.t("common.errorGeneric")
        _state.update { it.copy(saving = true) }
        return try {
            val saved = if (birthday != null) api.update(birthday.id, body) else api.create(body, YuvomiClient.newIdempotencyKey())
            container.scope.launch { host.notify(strings.t(if (isEdit) "birthdays.updatedToast" else "birthdays.createdToast")) }
            _state.update { it.copy(saving = false, saved = saved) }
            null
        } catch (e: ApiProblem) {
            val reason = (e as? ApiProblem.Http)?.reason
            if (reason in BirthdaysRules.CONTACT_CHOICE_REFUSALS) loadLinkable()
            val gone = isEdit && BirthdaysRules.isGone(e)
            _state.update { it.copy(saving = false, gone = gone) }
            strings.t(BirthdaysRules.errorKey(e))
        }
    }

    private fun fail(field: String, sentence: String): String {
        _state.update { it.copy(error = field to sentence) }
        return sentence
    }
}
