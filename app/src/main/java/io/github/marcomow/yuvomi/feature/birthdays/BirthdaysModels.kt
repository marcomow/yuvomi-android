package io.github.marcomow.yuvomi.feature.birthdays

import io.github.marcomow.yuvomi.core.api.FlexBool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A birthday as `GET /birthdays` answers it (`BIRTHDAY_SELECT` and `hydrateBirthday`). */
@Serializable
data class Birthday(
    val id: Int,
    val name: String = "",
    /** `YYYY-MM-DD`; without a year it carries the placeholder year 2000. */
    @SerialName("birth_date") val birthDate: String? = null,
    @SerialName("year_unknown") val yearUnknown: FlexBool = false,
    /** `MM-DD`. */
    @SerialName("name_day") val nameDay: String? = null,
    val notes: String? = null,
    /** A data URL. */
    @SerialName("photo_data") val photoData: String? = null,
    /** `''` none, null on the day, `custom`, else minutes before noon. */
    @SerialName("reminder_offset") val reminderOffset: String? = null,
    @SerialName("reminder_custom_amount") val reminderCustomAmount: String? = null,
    @SerialName("reminder_custom_unit") val reminderCustomUnit: String? = null,
    @SerialName("contact_id") val contactId: Int? = null,
    @SerialName("contact_name") val contactName: String? = null,
    @SerialName("suggested_contact_id") val suggestedContactId: Int? = null,
    @SerialName("family_user_id") val familyUserId: Int? = null,
    @SerialName("family_display_name") val familyDisplayName: String? = null,
    @SerialName("family_avatar_color") val familyAvatarColor: String? = null,
    @SerialName("family_avatar_data") val familyAvatarData: String? = null,
    @SerialName("next_birthday") val nextBirthday: String? = null,
    @SerialName("next_age") val nextAge: Int? = null,
    @SerialName("days_until") val daysUntil: Int? = null,
    @SerialName("next_name_day") val nextNameDay: String? = null,
    @SerialName("name_day_days_until") val nameDayDaysUntil: Int? = null,
)

/** A contact the import and the link field offer (`listBirthdayImportCandidates`). */
@Serializable
data class ImportCandidate(
    val id: Int,
    val name: String = "",
    val birthday: String? = null,
    @SerialName("birthday_year_unknown") val birthdayYearUnknown: FlexBool = false,
    @SerialName("already_imported") val alreadyImported: FlexBool = false,
    @SerialName("suggested_birthday_id") val suggestedBirthdayId: Int? = null,
    @SerialName("suggested_birthday_name") val suggestedBirthdayName: String? = null,
)

@Serializable
data class ImportCandidates(
    val withBirthday: List<ImportCandidate> = emptyList(),
    val withoutBirthday: List<ImportCandidate> = emptyList(),
)

@Serializable
data class ImportResult(val imported: Int = 0, val skipped: Int = 0)

/** The member's birthday feed (`GET /birthdays/feed`); null when it is off. */
@Serializable
data class BirthdayFeed(val token: String? = null, val url: String? = null)
