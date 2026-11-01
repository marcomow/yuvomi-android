package io.github.marcomow.yuvomi.feature.notes

import io.github.marcomow.yuvomi.core.api.ApiProblem
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A note as `GET /notes` and every note write answer it (`server/routes/notes.js`). */
@Serializable
data class Note(
    val id: Int,
    val title: String? = null,
    val content: String = "",
    val color: String? = null,
    val pinned: FlexBool = false,
    @SerialName("created_by") val createdBy: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("creator_name") val creatorName: String? = null,
    @SerialName("creator_color") val creatorColor: String? = null,
    @SerialName("creator_avatar") val creatorAvatar: String? = null,
    val visibility: String = VISIBILITY_ALL,
    val locked: FlexBool = false,
    val categories: List<NoteCategory> = emptyList(),
) {
    val isPrivate: Boolean get() = visibility == VISIBILITY_PRIVATE

    companion object {
        const val VISIBILITY_ALL = "all"
        const val VISIBILITY_PRIVATE = "private"
    }
}

/** A note category (`categoryPayload()` upstream): personal to its owner, or the household's. */
@Serializable
data class NoteCategory(
    val id: Int,
    val name: String = "",
    val scope: String = SCOPE_PERSONAL,
    @SerialName("owner_user_id") val ownerUserId: Int? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
) {
    val isPersonal: Boolean get() = scope == SCOPE_PERSONAL

    companion object {
        const val SCOPE_PERSONAL = "personal"
        const val SCOPE_HOUSEHOLD = "household"
    }
}

/** `GET /notes/categories`: the categories this member sees, and whether they may manage the household's. */
data class CategoryList(val categories: List<NoteCategory>, val canManageHousehold: Boolean)

/** docs/spec/notes.md, "Upstream operations". */
class NotesApi(private val client: YuvomiClient) {

    suspend fun list(): JsonElement = client.call(NotesOperation.NOTES_LIST)

    suspend fun categories(): JsonElement = client.call(NotesOperation.CATEGORIES_LIST)

    suspend fun create(body: JsonObject, idempotencyKey: String): Note =
        client.call(NotesOperation.NOTE_CREATE, body = body, idempotencyKey = idempotencyKey).decodeData(Note.serializer())

    suspend fun update(id: Int, body: JsonObject): Note =
        client.call(NotesOperation.NOTE_UPDATE, mapOf("id" to id), body = body).decodeData(Note.serializer())

    suspend fun delete(id: Int) {
        client.call(NotesOperation.NOTE_DELETE, mapOf("id" to id))
    }

    /** The server flips the pin and answers `{ id, pinned }`. */
    suspend fun pin(id: Int): Boolean {
        val data = (client.call(NotesOperation.NOTE_PIN, mapOf("id" to id)) as? JsonObject)?.get("data") as? JsonObject
            ?: throw ApiProblem.Malformed("pin answer")
        val pinned = data["pinned"] as? JsonPrimitive ?: throw ApiProblem.Malformed("pin answer")
        return pinned.booleanOrNull ?: (pinned.content != "0")
    }

    /** NOTES-R15: one checklist line, addressed by its zero-based index and checked against [expect]. */
    suspend fun check(id: Int, line: Int, checked: Boolean, expect: String): Note =
        client.call(NotesOperation.NOTE_CHECK, mapOf("id" to id), body = buildJsonObject {
            put("line", line)
            put("checked", checked)
            put("expect", expect)
        }).decodeData(Note.serializer())

    suspend fun createCategory(name: String, scope: String, idempotencyKey: String): NoteCategory =
        client.call(NotesOperation.CATEGORY_CREATE, body = buildJsonObject {
            put("name", name)
            put("scope", scope)
        }, idempotencyKey = idempotencyKey).decodeData(NoteCategory.serializer())

    suspend fun renameCategory(id: Int, name: String): NoteCategory =
        client.call(NotesOperation.CATEGORY_UPDATE, mapOf("id" to id), body = buildJsonObject { put("name", name) })
            .decodeData(NoteCategory.serializer())

    /** The new order of one scope; the answer is the whole list again. */
    suspend fun reorderCategories(ids: List<Int>, scope: String): CategoryList =
        decodeCategories(client.call(NotesOperation.CATEGORIES_REORDER, body = buildJsonObject {
            put("order", JsonArray(ids.map { JsonPrimitive(it) }))
            put("scope", scope)
        }))

    suspend fun deleteCategory(id: Int) {
        client.call(NotesOperation.CATEGORY_DELETE, mapOf("id" to id))
    }

    companion object {
        fun decodeNotes(answer: JsonElement): List<Note> = answer.decodeData(ListSerializer(Note.serializer()))

        fun decodeCategories(answer: JsonElement): CategoryList {
            val categories = answer.decodeData(ListSerializer(NoteCategory.serializer()))
            val meta = (answer as? JsonObject)?.get("meta") as? JsonObject
            val manage = (meta?.get("can_manage_household") as? JsonPrimitive)?.booleanOrNull ?: false
            return CategoryList(categories, manage)
        }
    }
}
