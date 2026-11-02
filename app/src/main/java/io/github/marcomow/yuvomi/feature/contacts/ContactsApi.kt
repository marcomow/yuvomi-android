package io.github.marcomow.yuvomi.feature.contacts

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.FlexBool
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.api.decodeData
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One phone number or e-mail address of a contact (`loadMultiValueFields`). */
@Serializable
data class ContactValue(
    val id: Int? = null,
    val label: String? = null,
    val value: String = "",
    val isPrimary: FlexBool = false,
)

/** One structured address of a contact. */
@Serializable
data class ContactAddress(
    val id: Int? = null,
    val label: String? = null,
    val street: String? = null,
    val city: String? = null,
    val state: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
    val isPrimary: FlexBool = false,
)

/**
 * A contact as `GET /contacts` lists it (`CONTACT_SELECT` in `server/routes/contacts.js`) and
 * `GET /contacts/{id}` answers it, the latter with [phones], [emails], [addresses] and the
 * birthday suggestion.
 */
@Serializable
data class Contact(
    val id: Int,
    val name: String = "",
    val category: String = "misc",
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val notes: String? = null,
    val birthday: String? = null,
    @SerialName("birthday_year_unknown") val birthdayYearUnknown: FlexBool = false,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("middle_name") val middleName: String? = null,
    @SerialName("name_prefix") val namePrefix: String? = null,
    @SerialName("name_suffix") val nameSuffix: String? = null,
    val organization: String? = null,
    @SerialName("job_title") val jobTitle: String? = null,
    val website: String? = null,
    val nickname: String? = null,
    @SerialName("family_user_id") val familyUserId: Int? = null,
    @SerialName("family_display_name") val familyDisplayName: String? = null,
    @SerialName("family_avatar_color") val familyAvatarColor: String? = null,
    @SerialName("family_avatar_data") val familyAvatarData: String? = null,
    @SerialName("birthday_id") val birthdayId: Int? = null,
    @SerialName("birthday_link_date") val birthdayLinkDate: String? = null,
    @SerialName("birthday_link_year_unknown") val birthdayLinkYearUnknown: FlexBool = false,
    @SerialName("suggested_birthday_id") val suggestedBirthdayId: Int? = null,
    @SerialName("suggested_birthday_name") val suggestedBirthdayName: String? = null,
    val phones: List<ContactValue>? = null,
    val emails: List<ContactValue>? = null,
    val addresses: List<ContactAddress>? = null,
) {
    /** A household member's own contact: never deleted from here (`family_member_contact`). */
    val isMember: Boolean get() = familyUserId != null
}

/** A contact category (`contact_categories`): seeded ones carry a `label_key`, own ones a name. */
@Serializable
data class ContactCategory(
    val key: String,
    val name: String? = null,
    @SerialName("label_key") val labelKey: String? = null,
    val icon: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    val color: String? = null,
)

/** The editable fields of the contact form, as the web app sends them (`buildContactForm`). */
data class ContactDraft(
    val firstName: String = "",
    val lastName: String = "",
    val category: String = ContactsRules.FALLBACK_CATEGORY,
    val phones: List<Pair<String, String>> = listOf("" to ""),
    val emails: List<Pair<String, String>> = listOf("" to ""),
    val address: String = "",
    val birth: BirthField = BirthField(),
    val notes: String = "",
)

/**
 * The birthday control of `birth-date.js`: a date picker, or month and day when the year is
 * unknown; switching keeps month and day, and the picker's year comes back when switched off.
 */
data class BirthField(
    val picker: String? = null,
    val yearUnknown: Boolean = false,
    val month: Int? = null,
    val day: Int? = null,
    val rememberedYear: String? = null,
) {
    data class Read(val date: String?, val yearUnknown: Boolean, val complete: Boolean)

    fun toggle(on: Boolean): BirthField = if (on) {
        val parts = picker?.split('-').orEmpty()
        copy(
            yearUnknown = true,
            rememberedYear = parts.getOrNull(0),
            month = parts.getOrNull(1)?.toIntOrNull() ?: month,
            day = parts.getOrNull(2)?.toIntOrNull() ?: day,
        )
    } else {
        val restored = if (rememberedYear != null && month != null && day != null) String.format(java.util.Locale.ROOT, "%s-%02d-%02d", rememberedYear, month, day) else picker
        copy(yearUnknown = false, picker = restored)
    }

    /** A month change keeps the day only where the month has it (`refreshDays`). */
    fun withMonth(m: Int?): BirthField = copy(month = m, day = day?.takeIf { m != null && it <= daysInMonth(m) })

    fun read(): Read {
        if (!yearUnknown) return Read(picker, false, true)
        if (month == null && day == null) return Read(null, true, true)
        if (month == null || day == null) return Read(null, true, false)
        return Read(String.format(java.util.Locale.ROOT, "%d-%02d-%02d", ContactsRules.YEAR_UNKNOWN_PLACEHOLDER, month, day), true, true)
    }

    companion object {
        /** `daysInMonthOfLeapYear`: 29 February is a valid yearless birthday. */
        fun daysInMonth(month: Int): Int = java.time.YearMonth.of(ContactsRules.YEAR_UNKNOWN_PLACEHOLDER, month).lengthOfMonth()

        fun of(date: String?, yearUnknown: Boolean): BirthField {
            if (date.isNullOrBlank()) return BirthField(yearUnknown = false)
            if (!yearUnknown) return BirthField(picker = date.take(10))
            val parts = date.split('-')
            return BirthField(yearUnknown = true, month = parts.getOrNull(1)?.toIntOrNull(), day = parts.getOrNull(2)?.take(2)?.toIntOrNull())
        }
    }
}

/** docs/spec/contacts.md, "Upstream operations". */
class ContactsApi(private val client: YuvomiClient) {

    suspend fun list(): JsonElement = client.call(ContactsOperation.CONTACTS_LIST)

    suspend fun categories(): JsonElement = client.call(ContactsOperation.CATEGORIES_LIST)

    suspend fun meta(): JsonElement = client.call(ContactsOperation.CONTACTS_META)

    suspend fun get(id: Int): Contact =
        client.call(ContactsOperation.CONTACT_GET, mapOf("id" to id)).decodeData(Contact.serializer())

    suspend fun create(body: JsonObject, idempotencyKey: String): Contact =
        client.call(ContactsOperation.CONTACT_CREATE, body = body, idempotencyKey = idempotencyKey).decodeData(Contact.serializer())

    suspend fun update(id: Int, body: JsonObject): Contact =
        client.call(ContactsOperation.CONTACT_UPDATE, mapOf("id" to id), body = body).decodeData(Contact.serializer())

    suspend fun delete(id: Int) {
        client.call(ContactsOperation.CONTACT_DELETE, mapOf("id" to id))
    }

    /** The vCard 3.0 text the server builds (`text/vcard`, not JSON). */
    suspend fun vcard(id: Int): ByteArray = client.bytes(ContactsOperation.CONTACT_VCARD.path(mapOf("id" to id)))

    suspend fun createCategory(name: String, idempotencyKey: String): ContactCategory =
        client.call(ContactsOperation.CATEGORY_CREATE, body = buildJsonObject { put("name", name) }, idempotencyKey = idempotencyKey)
            .decodeData(ContactCategory.serializer())

    suspend fun renameCategory(key: String, name: String): ContactCategory =
        client.call(ContactsOperation.CATEGORY_UPDATE, mapOf("key" to key), body = buildJsonObject { put("name", name) })
            .decodeData(ContactCategory.serializer())

    /** Only the colour: the seeded name keeps its translation (`req.body.name === undefined`). */
    suspend fun recolorCategory(key: String, color: String?): ContactCategory =
        client.call(ContactsOperation.CATEGORY_UPDATE, mapOf("key" to key), body = buildJsonObject { put("color", color) })
            .decodeData(ContactCategory.serializer())

    suspend fun reorderCategories(keys: List<String>): List<ContactCategory> =
        client.call(ContactsOperation.CATEGORIES_REORDER, body = buildJsonObject {
            put("order", JsonArray(keys.map { JsonPrimitive(it) }))
        }).decodeData(ListSerializer(ContactCategory.serializer()))

    suspend fun deleteCategory(key: String) {
        client.call(ContactsOperation.CATEGORY_DELETE, mapOf("key" to key))
    }

    /** `linkContactToBirthday`. */
    suspend fun linkBirthday(birthdayId: Int, contactId: Int) {
        client.call(ContactsOperation.BIRTHDAY_UPDATE, mapOf("id" to birthdayId), body = buildJsonObject { put("contact_id", contactId) })
    }

    /** `addContactToBirthdays`. */
    suspend fun importBirthday(contactId: Int, idempotencyKey: String) {
        client.call(
            ContactsOperation.BIRTHDAYS_IMPORT,
            body = buildJsonObject { put("contact_ids", JsonArray(listOf(JsonPrimitive(contactId)))) },
            idempotencyKey = idempotencyKey,
        )
    }

    companion object {
        fun decodeContacts(answer: JsonElement): List<Contact> = answer.decodeData(ListSerializer(Contact.serializer()))

        fun decodeCategories(answer: JsonElement): List<ContactCategory> = answer.decodeData(ListSerializer(ContactCategory.serializer()))

        /** `GET /contacts/meta`: the tones a category may take (`categoryColors`). */
        fun decodeColors(answer: JsonElement): List<String> {
            val data = (answer as? JsonObject)?.get("data") as? JsonObject ?: return emptyList()
            return (data["categoryColors"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        }

        fun encodeContacts(list: List<Contact>): String =
            ApiJson.encodeToString(JsonObject.serializer(), JsonObject(mapOf("data" to ApiJson.encodeToJsonElement(ListSerializer(Contact.serializer()), list))))

        fun encodeCategories(list: List<ContactCategory>): String =
            ApiJson.encodeToString(JsonObject.serializer(), JsonObject(mapOf("data" to ApiJson.encodeToJsonElement(ListSerializer(ContactCategory.serializer()), list))))
    }
}
