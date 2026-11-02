package io.github.marcomow.yuvomi.feature.settings

import io.github.marcomow.yuvomi.core.api.FriendlyError
import io.github.marcomow.yuvomi.core.api.ApiOperation
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.Operation
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * docs/spec/settings.md, "Upstream operations". Settings reads many small, loosely shaped
 * answers (preferences, configs, catalogues); they stay [JsonObject]s read through the
 * tolerant accessors below, so a missing or new field never breaks a sheet (ARCHITECTURE.md,
 * rule 2).
 */
class SettingsApi(val client: YuvomiClient) {

    suspend fun call(
        op: ApiOperation,
        params: Map<String, Any> = emptyMap(),
        query: List<Pair<String, String>> = emptyList(),
        body: JsonElement? = null,
        idempotencyKey: String? = null,
    ): JsonElement = client.call(op, params, query, body, idempotencyKey)

    /** The `data` of an answer, or the answer itself when it has no envelope. */
    suspend fun data(
        op: ApiOperation,
        params: Map<String, Any> = emptyMap(),
        query: List<Pair<String, String>> = emptyList(),
        body: JsonElement? = null,
        idempotencyKey: String? = null,
    ): JsonElement {
        val answer = call(op, params, query, body, idempotencyKey)
        return (answer as? JsonObject)?.get("data") ?: answer
    }

    suspend fun preferences(): JsonObject = data(Operation.PREFERENCES).obj()

    /** `savePreferences(patch)`: PUT answers the whole new set. */
    suspend fun savePreferences(patch: JsonObject): JsonObject = data(SettingsOperation.PREFERENCES_UPDATE, body = patch).obj()

    suspend fun me(): JsonObject = call(Operation.ME).obj()

    /** Raw bytes of a GET, e.g. the database backup. */
    suspend fun bytes(op: ApiOperation): ByteArray = client.bytes(op.path())
}

fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray)?.toList() ?: emptyList()

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() ?: it.contentOrNull?.toIntOrNull() }

fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

/** Flags arrive as JSON booleans, SQLite integers or strings. */
fun JsonObject.bool(key: String, default: Boolean = false): Boolean {
    val p = this[key] as? JsonPrimitive ?: return default
    if (p is JsonNull) return default
    p.booleanOrNull?.let { return it }
    p.doubleOrNull?.let { return it != 0.0 }
    return p.content == "true" || p.content == "1"
}

fun JsonObject.strings(key: String): List<String> = this[key].arr().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

fun JsonObject.child(key: String): JsonObject = this[key].obj()

/** `knownKey` of `public/utils/friendly-error.js`, for the reasons the settings sheets meet. */
object SettingsErrors {
    private val REFUSALS = mapOf(
        "module_access_denied" to "common.errorModuleNoAccess",
        "module_read_only" to "settings.permReadOnlyBanner",
        "csrf_invalid" to "common.errorFormExpired",
        "not_owner" to "common.errorNotOwner",
    )

    /** Reasons with their own sentence, from the settings pages' own error texts. */
    private val CASES = mapOf(
        "email_in_use" to "common.emailInUse",
        "wrong_password" to "settings.currentPasswordWrong",
        "invalid_code" to "settings.twoFactorInvalidCode",
        "required" to "settings.twoFactorRequiredByHousehold",
        "not_enabled" to "settings.twoFactorNotEnabled",
        "gravatar_disabled" to "settings.gravatarErrorDisabled",
        "gravatar_stale" to "settings.gravatarErrorStale",
        "gravatar_rate_limited" to "settings.gravatarErrorRateLimited",
    )

    /** The i18n key for [e]; [fallback] is the place's general sentence (`errorSentence(err, fallbackKey)`). */
    fun key(e: Throwable, fallback: String = "common.errorGeneric"): String =
        FriendlyError.finish(e, keyBase(e, fallback), fallback)

    private fun keyBase(e: Throwable, fallback: String): String = when (e) {
        is ApiProblem.Network -> "common.errorOfflineMutation"
        is ApiProblem.Http -> when {
            e.reason == "restore_in_progress" -> "common.errorRestoreInProgress"
            e.status == 403 -> REFUSALS[e.reason] ?: "common.errorNoPermission"
            e.status == 429 -> CASES[e.reason] ?: "common.errorRateLimited"
            e.reason != null && e.reason in CASES -> CASES.getValue(e.reason)
            e.operationUnsupported -> "android.error.unsupported"
            else -> fallback
        }
        else -> fallback
    }

    /** A load error: offline gets its own sentence, everything else the sheet's. */
    fun loadKey(e: Throwable): String = if (e is ApiProblem.Network) "common.errorOffline" else "settings.loadError"
}
