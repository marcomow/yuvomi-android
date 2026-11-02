package io.github.marcomow.yuvomi.contract

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.TasksApi
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.feature.tags.TaskTagsApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/**
 * docs/spec/tags.md, the task tag management (`server/routes/tasks.js` `/tags`), against
 * upstream's image at the pinned version. Every test tags tasks of its own with unique tags and
 * deletes them again.
 */
class TagsContractTest : ContractTestBase() {
    private class Member(val client: YuvomiClient) {
        val tasks = TasksApi(client)
        val tags = TaskTagsApi(client)
    }

    private fun member(username: String) = Member(signedIn(username).first)

    private fun tag(prefix: String) = unique(prefix).replace(' ', '-')

    private fun task(m: Member, tags: List<String>, private: Boolean = false): Int = runBlocking {
        m.tasks.create(
            buildJsonObject {
                put("title", unique("Tagged"))
                putJsonArray("tags") { tags.forEach { add(it) } }
                if (private) put("visibility", "private")
            },
            UUID.randomUUID().toString(),
        ).id
    }

    private fun cleanUp(m: Member, vararg ids: Int) = runBlocking { ids.forEach { runCatching { m.tasks.delete(it) } } }

    private fun expectHttp(status: Int, block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("expected HTTP $status")
        } catch (e: ApiProblem.Http) {
            assertEquals(status, e.status)
        }
    }

    /** GET /tasks/tags: `{ tag, count }` by key, the first spelling, counted over visible tasks. */
    @Test fun `lists task tags with their counts`() {
        val alex = member("alex")
        val name = tag("Garden")
        val a = task(alex, listOf(name))
        val b = task(alex, listOf(name.lowercase()))
        try {
            val entry = runBlocking { alex.tags.list() }.single { it.tag.lowercase() == name.lowercase() }
            assertEquals(2, entry.count)
            // Each task comes back with its tags.
            assertEquals(listOf(name), runBlocking { alex.tasks.get(a) }.tags)
        } finally {
            cleanUp(alex, a, b)
        }
    }

    /** GET /tasks/tags hides the tags of tasks the member cannot see (#474). */
    @Test fun `a private task's tag is not listed for others`() {
        val alex = member("alex")
        val linda = member("linda")
        val name = tag("Surprise")
        val id = task(alex, listOf(name), private = true)
        try {
            assertTrue(runBlocking { alex.tags.list() }.any { it.tag == name })
            assertFalse(runBlocking { linda.tags.list() }.any { it.tag == name })
            expectHttp(404) { linda.tags.delete(name) }
        } finally {
            cleanUp(alex, id)
        }
    }

    /** PUT /tasks/tags/{tag}: renames on every visible task; onto an existing tag it merges. */
    @Test fun `renames and merges a tag`() {
        val alex = member("alex")
        val from = tag("Old")
        val to = tag("New")
        val a = task(alex, listOf(from, "keep-${from}"))
        val b = task(alex, listOf(to))
        try {
            val change = runBlocking { alex.tags.rename(from, to.uppercase()) }
            assertEquals(to.uppercase(), change.tag)
            // Both tasks change: a gets the new name, b takes the typed spelling.
            assertEquals(2, change.updated)
            assertEquals(0, change.skipped)
            assertNull(change.tags.firstOrNull { it.tag == from })
            assertEquals(2, change.tags.single { it.tag.lowercase() == to.lowercase() }.count)
            assertEquals(listOf("keep-$from", to.uppercase()).sorted(), runBlocking { alex.tasks.get(a) }.tags.sorted())
        } finally {
            cleanUp(alex, a, b)
        }
    }

    /** PUT /tasks/tags/{tag}: an empty name is a 400, an unknown tag a 404, both without a reason. */
    @Test fun `rename refuses an empty name and an unknown tag`() {
        val alex = member("alex")
        val name = tag("Refuse")
        val id = task(alex, listOf(name))
        try {
            expectHttp(400) { alex.tags.rename(name, "  ") }
            expectHttp(400) { alex.tags.rename(name, "..") }
            expectHttp(404) { alex.tags.rename(tag("Missing"), "anything") }
        } finally {
            cleanUp(alex, id)
        }
    }

    /** DELETE /tasks/tags/{tag}: takes the tag off every visible task; the tasks stay. */
    @Test fun `deletes a tag everywhere`() {
        val alex = member("alex")
        val name = tag("Gone")
        val other = tag("Stays")
        val a = task(alex, listOf(name, other))
        try {
            val change = runBlocking { alex.tags.delete(name) }
            assertEquals(1, change.updated)
            assertEquals(0, change.skipped)
            assertFalse(change.tags.any { it.tag == name })
            assertEquals(listOf(other), runBlocking { alex.tasks.get(a) }.tags)
            expectHttp(404) { alex.tags.delete(name) }
        } finally {
            cleanUp(alex, a)
        }
    }
}
