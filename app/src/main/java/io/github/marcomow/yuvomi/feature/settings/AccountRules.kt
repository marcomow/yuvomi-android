package io.github.marcomow.yuvomi.feature.settings

import io.github.marcomow.yuvomi.core.api.ApiProblem
import java.text.Normalizer
import java.time.LocalDate

/** The rules of `personal-account.js`, pure (SET-R10 to R16). */
object AccountRules {
    const val PASSWORD_MIN_LENGTH = 8
    const val DISPLAY_NAME_MAX = 128

    /** `USER_COLORS` with their `colorPicker.*` names (`familyColorOptions`). */
    val COLORS = listOf(
        "#78808C" to "colorPicker.gray", "#3B82F6" to "colorPicker.blue", "#16A34A" to "colorPicker.green",
        "#D97706" to "colorPicker.ocher", "#059669" to "colorPicker.emerald", "#D946EF" to "colorPicker.fuchsia",
        "#EF4444" to "colorPicker.red", "#0891B2" to "colorPicker.cyan", "#EA580C" to "colorPicker.orange",
        "#EC4899" to "colorPicker.pink",
    )
    const val COLOR_DEFAULT = "#0891B2"

    /** `passwordProblems`: field (`current`, `next`, `confirm`) to message key. */
    fun passwordProblems(current: String, next: String, confirm: String): Map<String, String> = buildMap {
        if (current.isEmpty()) put("current", "common.required")
        if (next.isEmpty()) put("next", "common.required")
        else if (Normalizer.normalize(next, Normalizer.Form.NFC).length < PASSWORD_MIN_LENGTH) put("next", "settings.passwordTooShort")
        if (confirm.isEmpty()) put("confirm", "common.required")
        else if (next != confirm) put("confirm", "settings.passwordMismatch")
    }

    /** `isEmailAddress`: one @ with text on both sides, as server/routes/email.js. */
    fun isEmail(value: String): Boolean = Regex("^[^\\s@]+@[^\\s@]+$").matches(value)

    /** `hasSavedEmail`: the Gravatar button needs one stored address. */
    fun hasSavedEmail(email: String?): Boolean {
        val raw = email?.trim().orEmpty()
        if (raw.isEmpty() || Regex("[,;\\s]").containsMatchIn(raw)) return false
        val at = raw.indexOf('@')
        return at > 0 && raw.indexOf('@', at + 1) == -1
    }

    /** A birth date the server takes: empty, or a real `YYYY-MM-DD` not in the future. */
    fun birthDateValid(value: String, today: LocalDate): Boolean {
        if (value.isBlank()) return true
        val date = runCatching { LocalDate.parse(value.trim()) }.getOrNull() ?: return false
        return !date.isAfter(today)
    }

    /** Profile form problems: field to message key. */
    fun profileProblems(displayName: String, email: String, birthDate: String, today: LocalDate): Map<String, String> = buildMap {
        if (displayName.isBlank()) put("display_name", "common.required")
        if (email.isNotBlank() && !isEmail(email.trim())) put("email", "common.emailInvalid")
        if (!birthDateValid(birthDate, today)) put("birth_date", "settings.memberBirthDateInvalid")
    }

    private val GRAVATAR_REASONS = mapOf(
        "no_email" to "settings.gravatarNeedsEmail",
        "gravatar_disabled" to "settings.gravatarErrorDisabled",
        "gravatar_not_found" to "settings.gravatarErrorNotFound",
        "gravatar_unreachable" to "settings.gravatarErrorUnreachable",
        "gravatar_too_large" to "settings.gravatarErrorTooLarge",
        "gravatar_not_image" to "settings.gravatarErrorNotImage",
        "gravatar_rate_limited" to "settings.gravatarErrorRateLimited",
        "gravatar_stale" to "settings.gravatarErrorStale",
        "not_a_household_member" to "settings.gravatarErrorNotMember",
    )

    /** `gravatarErrorText`: the key; the sentences take the service's `host`. */
    fun gravatarErrorKey(e: Throwable): String {
        val http = e as? ApiProblem.Http
        GRAVATAR_REASONS[http?.reason]?.let { return it }
        if (http?.status == 429) return "settings.gravatarErrorRateLimited"
        return SettingsErrors.key(e)
    }

    /** `twoFactorErrorText`. */
    fun twoFactorErrorKey(e: Throwable): String {
        val http = e as? ApiProblem.Http
        return when {
            http?.status == 429 -> "settings.twoFactorTooManyAttempts"
            http?.reason == "invalid_code" -> "settings.twoFactorInvalidCode"
            http?.reason == "required" -> "settings.twoFactorRequiredByHousehold"
            http?.reason == "not_enabled" -> "settings.twoFactorNotEnabled"
            e is ApiProblem.Network -> "common.errorOfflineMutation"
            else -> "settings.twoFactorGenericError"
        }
    }

    /** `logoutOthersErrorText`. */
    fun logoutOthersErrorKey(e: Throwable): String =
        if ((e as? ApiProblem.Http)?.status == 429) "settings.otherSessionsTooManyAttempts"
        else if (e is ApiProblem.Network) "common.errorOfflineMutation" else "settings.otherSessionsError"

    /** The TOTP secret in groups of four, easier to type into an authenticator. */
    fun groupSecret(secret: String): String = secret.replace(" ", "").chunked(4).joinToString(" ")

    /** The recovery codes as the web app's download names them: one per line. */
    fun recoveryText(codes: List<String>): String = codes.joinToString("\n")
}
