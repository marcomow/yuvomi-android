package io.github.marcomow.yuvomi.core.api

import io.github.marcomow.yuvomi.core.api.model.CheckResult
import io.github.marcomow.yuvomi.core.api.model.Comment
import io.github.marcomow.yuvomi.core.api.model.LoginOutcome
import io.github.marcomow.yuvomi.core.api.model.Preferences
import io.github.marcomow.yuvomi.core.api.model.SignInPayload
import io.github.marcomow.yuvomi.core.api.model.StatusChange
import io.github.marcomow.yuvomi.core.api.model.Task
import io.github.marcomow.yuvomi.core.api.model.TaskMeta
import io.github.marcomow.yuvomi.core.api.model.VersionInfo
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put

/** Decodes a bare answer. */
fun <T> JsonElement.decode(strategy: DeserializationStrategy<T>): T =
    try {
        ApiJson.decodeFromJsonElement(strategy, this)
    } catch (e: IllegalArgumentException) {
        throw ApiProblem.Malformed(e.message ?: "decode failed", e)
    }

/** Decodes a `{ "data": ... }` answer. */
fun <T> JsonElement.decodeData(strategy: DeserializationStrategy<T>): T {
    val data = (this as? JsonObject)?.get("data") ?: throw ApiProblem.Malformed("missing data envelope")
    return data.decode(strategy)
}

/** docs/spec/signin.md */
class AuthApi(private val client: YuvomiClient) {

    suspend fun version(): VersionInfo = client.call(Operation.VERSION).decode(VersionInfo.serializer())

    suspend fun login(username: String, password: String): LoginOutcome {
        val answer = client.call(Operation.LOGIN, body = buildJsonObject {
            put("username", username)
            put("password", password)
        })
        val obj = answer as? JsonObject ?: throw ApiProblem.Malformed("login answer")
        if ((obj["twoFactorRequired"] as? JsonPrimitive)?.booleanOrNull == true) {
            val recovery = (obj["recoveryAvailable"] as? JsonPrimitive)?.booleanOrNull ?: false
            return LoginOutcome.TwoFactorRequired(recovery)
        }
        return LoginOutcome.SignedIn(signedIn(answer))
    }

    suspend fun verifyTwoFactor(code: String): SignInPayload =
        signedIn(client.call(Operation.VERIFY_2FA, body = buildJsonObject { put("code", code) }))

    suspend fun me(): SignInPayload = signedIn(client.call(Operation.ME))

    suspend fun logout() {
        client.call(Operation.LOGOUT)
    }

    private fun signedIn(answer: JsonElement): SignInPayload =
        answer.decode(SignInPayload.serializer()).also { client.csrf.update(it.csrfToken) }
}

/** Preferences and the dashboard (docs/spec/signin.md, docs/spec/overview.md). */
class HouseholdApi(private val client: YuvomiClient) {
    suspend fun preferences(): Preferences = client.call(Operation.PREFERENCES).decodeData(Preferences.serializer())

    /** Kept raw: each tile decodes its own fields (OVERVIEW-R12). */
    suspend fun dashboard(): JsonObject =
        client.call(Operation.DASHBOARD) as? JsonObject ?: throw ApiProblem.Malformed("dashboard is not an object")
}

/** docs/spec/tasks.md */
class TasksApi(private val client: YuvomiClient) {

    data class Filter(
        val statuses: List<String> = listOf(Task.STATUS_OPEN),
        val priorities: List<String> = emptyList(),
        val assignees: List<Int> = emptyList(),
        val categories: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val includeFuture: Boolean = false,
        /** `archived=1`: archived tasks too (default: left out). */
        val includeArchived: Boolean = false,
    ) {
        fun query(): List<Pair<String, String>> = buildList {
            statuses.forEach { add("status" to it) }
            priorities.forEach { add("priority" to it) }
            assignees.forEach { add("assigned_to" to it.toString()) }
            categories.forEach { add("category" to it) }
            tags.forEach { add("tag" to it) }
            if (includeFuture) add("include_future" to "1")
            if (includeArchived) add("archived" to "1")
        }

        val activeCount: Int
            get() = (if (statuses == listOf(Task.STATUS_OPEN)) 0 else 1) +
                priorities.size + assignees.size + categories.size + tags.size + (if (includeFuture) 1 else 0)
    }

    suspend fun list(filter: Filter): JsonElement = client.call(Operation.TASKS_LIST, query = filter.query())

    suspend fun meta(): JsonElement = client.call(Operation.TASKS_META)

    suspend fun get(id: Int): Task = client.call(Operation.TASK_GET, mapOf("id" to id)).decodeData(Task.serializer())

    suspend fun create(body: JsonObject, idempotencyKey: String): Task =
        client.call(Operation.TASKS_CREATE, body = body, idempotencyKey = idempotencyKey).decodeData(Task.serializer())

    /** Partial: only the fields in [changes] are touched (TASKS-R16). */
    suspend fun update(id: Int, changes: JsonObject): Task =
        client.call(Operation.TASK_UPDATE, mapOf("id" to id), body = changes).decodeData(Task.serializer())

    suspend fun delete(id: Int) {
        client.call(Operation.TASK_DELETE, mapOf("id" to id))
    }

    suspend fun setStatus(id: Int, status: String, doneBy: Int? = null): StatusChange =
        client.call(Operation.TASK_STATUS, mapOf("id" to id), body = buildJsonObject {
            put("status", status)
            if (doneBy != null) put("done_by_user_id", doneBy)
        }).decodeData(StatusChange.serializer())

    suspend fun checkLine(id: Int, line: Int, checked: Boolean, expect: String): CheckResult =
        client.call(Operation.TASK_CHECK, mapOf("id" to id), body = buildJsonObject {
            put("line", line)
            put("checked", checked)
            put("expect", expect)
        }).decodeData(CheckResult.serializer())

    suspend fun comments(id: Int): List<Comment> =
        client.call(Operation.COMMENTS_LIST, mapOf("id" to id)).decodeData(ListSerializer(Comment.serializer()))

    suspend fun addComment(id: Int, text: String, idempotencyKey: String): Comment =
        client.call(Operation.COMMENT_CREATE, mapOf("id" to id), body = buildJsonObject { put("comment", text) },
            idempotencyKey = idempotencyKey).decodeData(Comment.serializer())

    suspend fun editComment(id: Int, commentId: Int, text: String): Comment =
        client.call(Operation.COMMENT_UPDATE, mapOf("id" to id, "commentId" to commentId),
            body = buildJsonObject { put("comment", text) }).decodeData(Comment.serializer())

    suspend fun deleteComment(id: Int, commentId: Int) {
        client.call(Operation.COMMENT_DELETE, mapOf("id" to id, "commentId" to commentId))
    }

    companion object {
        fun decodeList(answer: JsonElement): List<Task> = answer.decodeData(ListSerializer(Task.serializer()))
        fun decodeMeta(answer: JsonElement): TaskMeta = answer.decode(TaskMeta.serializer())
    }
}
