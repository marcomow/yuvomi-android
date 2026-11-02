package io.github.marcomow.yuvomi.feature.contacts

import android.telephony.PhoneNumberUtils
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.feature.shell.ShellContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the contacts screens need from the shell: strings, snackbars, who is signed in and what
 * they may do. An interface so the view models are tested without a shell.
 */
interface ContactsHost {
    val strings: Strings
    val userId: Int
    val isAdmin: Boolean

    /** `contacts: write` (CONT-R19); read-only members get no editing affordance. */
    val canWrite: Boolean

    /** Birthdays is on and readable: "Show in birthdays" (`mayOpenBirthdays`). */
    val mayOpenBirthdays: Boolean

    /** Birthdays is on and `calendar` writable: "Add to birthdays", "Link" (`mayWritePath('/birthdays')`). */
    val mayWriteBirthdays: Boolean

    /** Shows [message]; true when the member tapped [action]. */
    suspend fun notify(message: String, action: String? = null): Boolean
}

/** [ContactsHost] on the signed-in shell. */
internal class ShellContactsHost(private val shell: ShellContext, override val strings: Strings) : ContactsHost {
    override val userId: Int get() = shell.session.user.id
    override val isAdmin: Boolean get() = shell.session.user.role == "admin" || shell.session.isAdmin
    override val canWrite: Boolean get() = shell.session.access("contacts") == "write"
    override val mayOpenBirthdays: Boolean get() = shell.shows("birthdays")
    override val mayWriteBirthdays: Boolean get() = shell.shows("birthdays") && shell.session.access("calendar") == "write"

    override suspend fun notify(message: String, action: String?): Boolean = shell.notify(message, action)
}

/**
 * What the contacts screens share without sharing a view model: a change counter the list
 * reloads on, the contacts in their undo window, and the household's country for phone
 * numbers (`countryFromRegion`).
 */
object ContactsBus {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private val _pending = MutableStateFlow<Set<Int>>(emptySet())

    /** Contacts deleted with an undo still open: every screen hides them (`scheduleUndoableDelete`). */
    val pending: StateFlow<Set<Int>> = _pending.asStateFlow()

    @Volatile var country: String? = null

    fun changed() = _version.update { it + 1 }

    /**
     * Deletes [contacts] after the undo window, like the web app's `deleteContact` and
     * `deleteSelected`: hidden at once, sent when the snackbar goes without "Undo", a contact
     * deleted meanwhile counts as deleted, any other failure brings them back.
     */
    fun delete(container: AppContainer, host: ContactsHost, ids: List<Int>, message: String) {
        val api = container.session.connection?.client?.let(::ContactsApi) ?: return
        if (ids.isEmpty()) return
        _pending.update { it + ids }
        container.scope.launch {
            val strings = host.strings
            if (host.notify(message, strings.t("common.undo"))) {
                _pending.update { it - ids.toSet() }
                return@launch
            }
            var failure: ApiProblem? = null
            for (id in ids) {
                try {
                    api.delete(id)
                } catch (e: ApiProblem) {
                    if (!ContactsRules.isGone(e)) failure = e
                }
            }
            _pending.update { it - ids.toSet() }
            changed()
            failure?.let { host.notify(ContactsRules.errorSentence(it, strings)) }
        }
    }

    /** `countryFromRegion`: the last part of the household region when it is a country code. */
    fun countryFromRegion(region: String?): String? {
        val cc = region?.split('-')?.lastOrNull() ?: return null
        return if (Regex("^[A-Za-z]{2}$").matches(cc)) cc.uppercase() else null
    }
}

/** Phone numbers as the web app shows and dials them (`getPhoneFormatter`), through the platform's formatter. */
object ContactPhones {
    fun display(raw: String, country: String? = ContactsBus.country): String {
        if (raw.isBlank() || country == null) return raw
        return runCatching { PhoneNumberUtils.formatNumber(raw, country) }.getOrNull()?.takeIf { it.isNotBlank() } ?: raw
    }

    fun dial(raw: String, country: String? = ContactsBus.country): String {
        val e164 = country?.let { runCatching { PhoneNumberUtils.formatNumberToE164(raw, it) }.getOrNull() }
        return e164 ?: ContactsRules.dialable(raw)
    }
}
