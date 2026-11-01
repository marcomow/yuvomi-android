package io.github.marcomow.yuvomi.core.api.model

import io.github.marcomow.yuvomi.core.api.FlexBool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /version` (SIGNIN-R3, SIGNIN-R14). */
@Serializable
data class VersionInfo(
    @SerialName("app_name") val appName: String? = null,
    @SerialName("setup_required") val setupRequired: FlexBool = false,
    @SerialName("password_reset_enabled") val passwordResetEnabled: FlexBool = false,
    val version: String? = null,
)

@Serializable
data class User(
    val id: Int,
    val username: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_color") val avatarColor: String? = null,
    @SerialName("avatar_data") val avatarData: String? = null,
    val role: String? = null,
    @SerialName("family_role") val familyRole: String? = null,
    @SerialName("access_scope") val accessScope: String? = null,
)

/**
 * Upstream's sparse permission maps (`clientPermissions()`): a key that is missing
 * means full access, exactly like the PWA's fail-open `permissions.js`.
 */
@Serializable
data class Permissions(
    val admin: FlexBool = false,
    val modules: Map<String, String> = emptyMap(),
    val widgets: Map<String, String> = emptyMap(),
    val capabilities: Map<String, String> = emptyMap(),
) {
    /** `none`, `read` or `write`. */
    fun module(key: String): String = modules[key] ?: "write"
    fun widgetAllowed(id: String): Boolean = widgets[id] != "none"
    fun capability(key: String): Boolean = capabilities[key] != "none"
}

/** The sign-in payload of `/auth/login`, `/auth/2fa/verify` and `/auth/me`. */
@Serializable
data class SignInPayload(
    val user: User,
    val permissions: Permissions = Permissions(),
    val householdSize: Int = 0,
    val csrfToken: String? = null,
    val recoveryRemaining: Int? = null,
)

sealed interface LoginOutcome {
    data class TwoFactorRequired(val recoveryAvailable: Boolean) : LoginOutcome
    data class SignedIn(val payload: SignInPayload) : LoginOutcome
}
