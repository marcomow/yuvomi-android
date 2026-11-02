package io.github.marcomow.yuvomi.feature.contacts

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.Collator

/**
 * The pure ports of `public/pages/contacts.js` and `public/utils/contact-name.js`
 * (docs/spec/contacts.md): names, order, filters, groups, the detail's values, the form's
 * payload and the error sentences.
 */
object ContactsRules {
    const val FALLBACK_CATEGORY = "misc"

    /** Placeholder year of a birthday without a year (`YEAR_UNKNOWN_PLACEHOLDER`). */
    const val YEAR_UNKNOWN_PLACEHOLDER = 2000

    // ── Names (contact-name.js) ──

    private fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    /** `composeDisplayName`: first, middle and last name joined, or null. */
    fun composeDisplayName(first: String?, last: String?, middle: String? = null): String? =
        listOfNotNull(clean(first), clean(middle), clean(last)).joinToString(" ").ifEmpty { null }

    /** `splitDisplayName`: the last word is the last name. */
    fun splitDisplayName(name: String?): Pair<String?, String?> {
        val value = clean(name) ?: return null to null
        val parts = value.split(Regex("\\s+"))
        if (parts.size == 1) return parts[0] to null
        return parts.dropLast(1).joinToString(" ") to parts.last()
    }

    /** `contactSortKey`: the last name where known, else the display name. */
    fun sortKey(c: Contact): String = clean(c.lastName) ?: clean(c.name) ?: ""

    // ── Categories ──

    /** `catLabel`: seeded categories through their `label_key`, own ones by name; unknown keys as they are. */
    fun categoryLabel(key: String, categories: List<ContactCategory>, strings: Strings): String {
        val c = categories.firstOrNull { it.key == key } ?: return key
        return c.labelKey?.let { strings.t(it) } ?: c.name ?: c.key
    }

    fun categoryLabel(c: ContactCategory, strings: Strings): String = c.labelKey?.let { strings.t(it) } ?: c.name ?: c.key

    private fun sortIndex(key: String, categories: List<ContactCategory>): Int =
        categories.indexOfFirst { it.key == key }.let { if (it == -1) Int.MAX_VALUE else it }

    /** The page's one order: category position, then the name sort key in the UI locale (#535). */
    fun sorted(contacts: List<Contact>, categories: List<ContactCategory>, collator: Collator = Collator.getInstance()): List<Contact> =
        contacts.sortedWith(compareBy<Contact> { sortIndex(it.category, categories) }.thenComparator { a, b -> collator.compare(sortKey(a), sortKey(b)) })

    /** `filterContacts`: the chosen category, then name, phone or e-mail containing the query. */
    fun filter(contacts: List<Contact>, category: String?, query: String): List<Contact> {
        var list = contacts
        if (category != null) list = list.filter { it.category == category }
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) {
            list = list.filter { c ->
                c.name.lowercase().contains(q) || c.phone?.lowercase()?.contains(q) == true || c.email?.lowercase()?.contains(q) == true
            }
        }
        return list
    }

    /**
     * `filterCategoryKeys`: chips only for categories in use, the active one kept; with at most
     * one category in use there is nothing to filter.
     */
    fun filterCategoryKeys(contacts: List<Contact>, categories: List<ContactCategory>, active: String?): List<String> {
        val used = contacts.mapTo(HashSet()) { it.category }
        val keys = categories.map { it.key }.filter { it in used || it == active }
        return if (keys.size < 2 && active == null) emptyList() else keys
    }

    /** `drawList`'s groups: by category, in the categories' order. */
    fun groups(contacts: List<Contact>, categories: List<ContactCategory>): List<Pair<String, List<Contact>>> =
        contacts.groupBy { it.category }.entries.sortedBy { sortIndex(it.key, categories) }.map { it.key to it.value }

    /** The form's preselection: the active filter, else "misc", else the first category. */
    fun defaultCategory(active: String?, categories: List<ContactCategory>): String = when {
        active != null && categories.any { it.key == active } -> active
        categories.any { it.key == FALLBACK_CATEGORY } -> FALLBACK_CATEGORY
        else -> categories.firstOrNull()?.key ?: FALLBACK_CATEGORY
    }

    /**
     * `emailsLockedFor`: a member's addresses lead to their account; only the member and an
     * admin change them, and the form neither edits nor sends them otherwise.
     */
    fun emailsLocked(contact: Contact?, userId: Int, isAdmin: Boolean): Boolean {
        val member = contact?.familyUserId ?: return false
        return !isAdmin && member != userId
    }

    // ── Detail (renderContactDetail) ──

    /** `mvSubLabel`: "other" and blank labels are not shown. */
    fun subLabel(label: String?): String? = label?.takeIf { it.isNotBlank() && it != "other" }

    fun phones(c: Contact): List<ContactValue> =
        c.phones?.takeIf { it.isNotEmpty() } ?: listOfNotNull(c.phone?.takeIf { it.isNotBlank() }?.let { ContactValue(value = it) })

    fun emails(c: Contact): List<ContactValue> =
        c.emails?.takeIf { it.isNotEmpty() } ?: listOfNotNull(c.email?.takeIf { it.isNotBlank() }?.let { ContactValue(value = it) })

    /** The addresses as one line each, with their label. */
    fun addresses(c: Contact): List<Pair<String, String?>> {
        val list = c.addresses?.takeIf { it.isNotEmpty() }?.map { formatAddress(it) to subLabel(it.label) }
            ?: listOfNotNull(c.address?.let { it to null })
        return list.filter { it.first.isNotBlank() }
    }

    /** `formatAddress`: street, "postal code city", "state, country"; missing parts drop out. */
    fun formatAddress(a: ContactAddress): String = listOf(
        a.street,
        listOfNotNull(a.postalCode, a.city).filter { it.isNotBlank() }.joinToString(" "),
        listOfNotNull(a.state, a.country).filter { it.isNotBlank() }.joinToString(", "),
    ).filter { !it.isNullOrBlank() }.joinToString(", ")

    /** The card's quick actions use the first of each (`contactCardEl`). */
    fun firstPhone(c: Contact): String? = c.phones?.firstOrNull()?.value?.takeIf { it.isNotBlank() } ?: c.phone?.takeIf { it.isNotBlank() }

    fun firstEmail(c: Contact): String? = c.emails?.firstOrNull()?.value?.takeIf { it.isNotBlank() } ?: c.email?.takeIf { it.isNotBlank() }

    fun firstAddress(c: Contact): String? =
        (c.addresses?.firstOrNull()?.let(::formatAddress) ?: c.address)?.takeIf { it.isNotBlank() }

    /** The birthday row shows the contact's own date, else the linked birthday's. */
    fun birthdayShown(c: Contact): Pair<String, Boolean>? = when {
        !c.birthday.isNullOrBlank() -> c.birthday to c.birthdayYearUnknown
        !c.birthdayLinkDate.isNullOrBlank() -> c.birthdayLinkDate to c.birthdayLinkYearUnknown
        else -> null
    }

    /** What the birthday row offers (`birthdayRowAction`). */
    enum class BirthdayAction { SHOW, ADD }

    fun birthdayAction(c: Contact, mayOpenBirthdays: Boolean, mayImport: Boolean): BirthdayAction? = when {
        c.birthdayId != null -> if (mayOpenBirthdays) BirthdayAction.SHOW else null
        c.birthday.isNullOrBlank() || c.suggestedBirthdayId != null || !mayImport -> null
        else -> BirthdayAction.ADD
    }

    /** `birthdaySuggestionRow`: offered while unlinked and the member may write birthdays. */
    fun showsSuggestion(c: Contact, mayLink: Boolean): Boolean = c.suggestedBirthdayId != null && c.birthdayId == null && mayLink

    /** The OpenStreetMap search the web app opens for an address. */
    fun mapsUrl(address: String): String = "https://www.openstreetmap.org/search?query=" + java.net.URLEncoder.encode(address, "UTF-8").replace("+", "%20")

    /** `externalHref`: a website without a scheme gets `https://`. */
    fun externalHref(url: String): String = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(url.trim())) url.trim() else "https://${url.trim()}"

    /** `tel()` without libphonenumber: the dialable characters, or the raw value. */
    fun dialable(raw: String): String = raw.replace(Regex("[^\\d+*#]"), "").ifEmpty { raw }

    // ── Form (buildContactForm) ──

    /** The form's prefill for [c], or an empty form with [category] preselected. */
    fun draft(c: Contact?, category: String): ContactDraft {
        if (c == null) return ContactDraft(category = category)
        val structured = !c.firstName.isNullOrBlank() || !c.lastName.isNullOrBlank()
        val (first, last) = if (structured) c.firstName to c.lastName else splitDisplayName(c.name)
        fun rows(list: List<ContactValue>?, single: String?): List<Pair<String, String>> {
            val rows = list?.takeIf { it.isNotEmpty() }?.map { (if (it.label == "other") "" else it.label.orEmpty()) to it.value }
                ?: single?.takeIf { it.isNotBlank() }?.let { listOf("" to it) }
                ?: emptyList()
            return rows.ifEmpty { listOf("" to "") }
        }
        return ContactDraft(
            firstName = first.orEmpty(),
            lastName = last.orEmpty(),
            category = c.category,
            phones = rows(c.phones, c.phone),
            emails = rows(c.emails, c.email),
            address = c.address.orEmpty(),
            birth = BirthField.of(c.birthday, c.birthdayYearUnknown),
            notes = c.notes.orEmpty(),
        )
    }

    /** Whether the edited contact had a structured name (`hadStructure`). */
    fun hadStructure(c: Contact?): Boolean = c != null && (!c.firstName.isNullOrBlank() || !c.lastName.isNullOrBlank())

    /**
     * The save payload of `buildContactForm`. [nameTouched] keeps an unstructured name untouched
     * unless the member edited it; [categoryKnown] false leaves an orphaned category out.
     */
    fun body(
        draft: ContactDraft,
        original: Contact?,
        nameTouched: Boolean,
        emailsLocked: Boolean,
        categoryKnown: Boolean,
    ): JsonObject {
        val first = draft.firstName.trim()
        val last = draft.lastName.trim()
        val structured = original == null || hadStructure(original) || nameTouched
        val name = if (structured) composeDisplayName(first, last).orEmpty() else original!!.name
        val phones = draft.phones.map { it.first.trim() to it.second.trim() }.filter { it.second.isNotEmpty() }
        val emails = draft.emails.map { it.first.trim() to it.second.trim() }.filter { it.second.isNotEmpty() }
        fun entries(rows: List<Pair<String, String>>) = JsonArray(rows.mapIndexed { i, (label, value) ->
            buildJsonObject {
                put("label", label.ifEmpty { "other" })
                put("value", value)
                put("isPrimary", i == 0)
            }
        })
        fun orNull(v: String) = v.trim().ifEmpty { null }
        val birth = draft.birth.read()
        return buildJsonObject {
            put("name", name)
            if (categoryKnown) put("category", draft.category)
            put("phone", phones.firstOrNull()?.second)
            if (!emailsLocked) put("email", emails.firstOrNull()?.second)
            put("address", orNull(draft.address))
            put("notes", orNull(draft.notes))
            put("birthday", birth.date)
            put("birthday_year_unknown", birth.date != null && birth.yearUnknown)
            put("phones", entries(phones))
            if (!emailsLocked) put("emails", entries(emails))
            if (structured) {
                put("firstName", first)
                put("lastName", last)
            }
        }
    }

    /** The name the form would save, for the "enter a first or last name" check. */
    fun formName(draft: ContactDraft, original: Contact?, nameTouched: Boolean): String {
        val structured = original == null || hadStructure(original) || nameTouched
        return if (structured) composeDisplayName(draft.firstName, draft.lastName).orEmpty() else original!!.name
    }

    // ── vCard import (nameVariants, contactExistsByName, resolveVCardCategory) ──

    private fun norm(s: String?): String = s.orEmpty().trim().lowercase().replace(Regex("\\s+"), " ")

    /** `nameVariants`: display name, "Last, First" swapped, and both orders of the parts. */
    fun nameVariants(name: String?, first: String?, last: String?): Set<String> {
        val out = LinkedHashSet<String>()
        val display = norm(name)
        if (display.isNotEmpty()) {
            out += display
            val swapped = norm(Regex("^([^,]+),\\s*(.+)$").replace(display, "$2 $1"))
            if (swapped.isNotEmpty()) out += swapped
        }
        val f = norm(first)
        val l = norm(last)
        if (f.isNotEmpty() || l.isNotEmpty()) {
            out += norm("$f $l")
            out += norm("$l $f")
        }
        out.remove("")
        return out
    }

    fun existsByName(card: VCard.Parsed, contacts: List<Contact>): Boolean {
        val variants = nameVariants(card.name, card.firstName, card.lastName)
        if (variants.isEmpty()) return false
        return contacts.any { c -> nameVariants(c.name, c.firstName, c.lastName).any { it in variants } }
    }

    /** `resolveVCardCategory`: the first category whose key or label the CATEGORIES value contains. */
    fun resolveCategory(raw: String?, categories: List<ContactCategory>, strings: Strings): String? {
        val lower = raw.orEmpty().lowercase()
        if (lower.isEmpty()) return null
        return categories.firstOrNull { lower.contains(it.key.lowercase()) || lower.contains(categoryLabel(it, strings).lowercase()) }?.key
    }

    /** The `POST /contacts` body of one imported card (`importParsedContacts`). */
    fun importBody(card: VCard.Parsed, category: String): JsonObject = buildJsonObject {
        put("name", card.name)
        put("firstName", card.firstName)
        put("lastName", card.lastName)
        put("middleName", card.middleName)
        put("namePrefix", card.namePrefix)
        put("nameSuffix", card.nameSuffix)
        put("phone", card.phone)
        put("email", card.email)
        put("address", card.address)
        put("notes", card.notes)
        put("birthday", card.birthday)
        put("category", category)
        put("birthday_year_unknown", card.birthday != null && card.birthdayYearUnknown)
    }

    // ── Errors (friendly-error.js) ──

    /** `CASE_PARAMS`: the limit the sentence names. The server's `params.limit` first; the pinned server's limits stand in when it sends none. */
    private val LIMITS = mapOf(
        "contacts_name_too_long" to 200,
        "contacts_phone_too_long" to 100,
        "contacts_email_too_long" to 255,
        "contacts_address_too_long" to 5000,
        "contacts_notes_too_long" to 5000,
        "contacts_phones_too_many" to 20,
        "contacts_phone_label_too_long" to 50,
        "contacts_emails_too_many" to 20,
        "contacts_email_label_too_long" to 50,
        "contacts_addresses_too_many" to 20,
        "contacts_address_label_too_long" to 50,
        "contacts_address_part_too_long" to 255,
    )

    private val CASES = mapOf(
        "contacts_contact_not_found" to "contacts.errorContactGone",
        "contacts_selected_category_not_found" to "contacts.errorSelectedCategoryGone",
        "contacts_name_too_long" to "contacts.errorNameTooLong",
        "contacts_phone_too_long" to "contacts.errorPhoneTooLong",
        "contacts_email_too_long" to "contacts.errorEmailTooLong",
        "contacts_address_too_long" to "contacts.errorAddressTooLong",
        "contacts_notes_too_long" to "contacts.errorNotesTooLong",
        "contacts_birthday_invalid" to "contacts.errorBirthdayInvalid",
        "contacts_phone_entry_invalid" to "contacts.errorPhoneEntryInvalid",
        "contacts_phones_too_many" to "contacts.errorPhonesTooMany",
        "contacts_phone_label_too_long" to "contacts.errorPhoneLabelTooLong",
        "contacts_email_entry_invalid" to "contacts.errorEmailEntryInvalid",
        "contacts_email_invalid" to "contacts.errorEmailInvalid",
        "contacts_emails_too_many" to "contacts.errorEmailsTooMany",
        "contacts_email_label_too_long" to "contacts.errorEmailLabelTooLong",
        "contacts_address_entry_invalid" to "contacts.errorAddressEntryInvalid",
        "contacts_addresses_too_many" to "contacts.errorAddressesTooMany",
        "contacts_address_label_too_long" to "contacts.errorAddressLabelTooLong",
        "contacts_address_part_too_long" to "contacts.errorAddressPartTooLong",
        "birthdays_birthday_not_found" to "birthdays.errorBirthdayGone",
        "birthdays_contact_linked" to "birthdays.errorContactLinked",
        "birthdays_selected_contact_not_found" to "contacts.errorContactGone",
        "category_exists" to "category.errorExists",
        "category_last" to "category.errorLast",
    )

    private val REFUSALS = mapOf(
        "module_access_denied" to "common.errorModuleNoAccess",
        "module_read_only" to "settings.permReadOnlyBanner",
        "csrf_invalid" to "common.errorFormExpired",
        "contact_email_protected" to "contacts.emailLockedHint",
        "not_owner" to "common.errorNotOwner",
    )

    /** `errorSentence`: the sentence for [e], never the server's English text. */
    fun errorSentence(e: Throwable, strings: Strings, fallback: String = "common.errorGeneric"): String {
        val http = e as? ApiProblem.Http
        return when {
            e is ApiProblem.Network -> strings.t("common.errorOfflineMutation")
            http == null -> strings.t(fallback)
            http.reason == "restore_in_progress" -> strings.t("common.errorRestoreInProgress")
            http.status == 403 -> strings.t(REFUSALS[http.reason] ?: "common.errorNoPermission")
            http.status == 429 -> strings.t(CASES[http.reason] ?: "common.errorRateLimited")
            http.reason == "email_in_use" -> strings.t("common.emailInUse")
            http.reason in CASES -> {
                val limit = http.params["limit"] ?: LIMITS[http.reason]?.toLong()
                if (limit != null) strings.t(CASES.getValue(http.reason!!), "limit" to limit) else strings.t(CASES.getValue(http.reason!!))
            }
            http.operationUnsupported -> strings.t("android.error.unsupported")
            else -> strings.t(fallback)
        }
    }

    /** `loadErrorSentence` for a failed read. */
    fun loadErrorSentence(e: Throwable, strings: Strings): String {
        val http = e as? ApiProblem.Http
        return when {
            e is ApiProblem.Network -> strings.t("common.errorOffline")
            http != null && (http.status == 403 || http.reason in CASES) -> errorSentence(e, strings)
            http?.status == 404 -> strings.t("common.errorNotFound")
            http != null && http.status >= 500 -> strings.t("common.errorServer")
            else -> strings.t("common.errorGeneric")
        }
    }

    /** `isAlreadyDeleted(err, 'contacts_contact_not_found')`. */
    fun isGone(e: Throwable): Boolean = (e as? ApiProblem.Http)?.let { it.status != 403 && it.reason == "contacts_contact_not_found" } == true

    /** The category manager's own sentences (`_errMsg`). */
    fun categoryError(e: Throwable, strings: Strings): String {
        val http = e as? ApiProblem.Http
        return when (http?.reason) {
            "category_in_use" -> strings.t("category.errorInUse", "count" to (http.serverMessage?.let { Regex("by (\\d+) contact").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0))
            "category_last" -> strings.t("category.errorLast")
            "category_exists" -> strings.t("category.errorExists")
            else -> errorSentence(e, strings)
        }
    }
}
