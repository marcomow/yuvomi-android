package io.github.marcomow.yuvomi.core

import io.github.marcomow.yuvomi.feature.Features
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards between the code and the upstream snapshots (ADR 0003, ADR 0008, ADR 0011). */
class ContractFilesTest {
    private val upstream = File(System.getProperty("yuvomi.upstreamDir"))
    private val assets = File(System.getProperty("yuvomi.assetsDir"))
    private val sources = File(System.getProperty("yuvomi.sourceDir"))

    private fun listed(file: File) = file.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }

    /** `operations-used.txt` (core and Tasks) plus one file per module in `operations-used.d/`. */
    private fun usedOperations(): List<String> =
        listed(File(upstream, "operations-used.txt")) +
            File(upstream, "operations-used.d").listFiles { f -> f.extension == "txt" }.orEmpty().sortedBy { it.name }.flatMap(::listed)

    @Test fun `the operation enums match upstream operations-used`() =
        assertEquals(usedOperations().sorted(), Features.operations.map { it.signature }.sorted())

    @Test fun `no operation is listed twice`() {
        val all = Features.operations.map { it.signature }
        assertEquals(all.groupBy { it }.filterValues { it.size > 1 }.keys, emptySet<String>())
    }

    @Test fun `every used operation exists in the OpenAPI snapshot`() {
        val spec = Json.parseToJsonElement(File(upstream, "openapi.json").readText()) as JsonObject
        val paths = spec["paths"] as JsonObject
        val missing = Features.operations.filter { op ->
            (paths[op.template] as? JsonObject)?.containsKey(op.method.lowercase()) != true
        }
        assertTrue("not in openapi.json: $missing", missing.isEmpty())
    }

    /** ADR 0011: a key the code uses and upstream removed fails here, in the weekly sync PR. */
    @Test fun `every literal string key exists in English`() {
        fun flatten(o: JsonObject, p: String = ""): Set<String> = o.entries.flatMap { (k, v) ->
            val key = if (p.isEmpty()) k else "$p.$k"
            if (v is JsonObject) flatten(v, key) else setOf(key)
        }.toSet()
        val androidFiles = File(assets, "i18n-android").walkTopDown().filter { it.name == "en.json" }.toList()
        val english = flatten(Json.parseToJsonElement(File(assets, "i18n/en.json").readText()) as JsonObject) +
            androidFiles.flatMap { flatten(Json.parseToJsonElement(it.readText()) as JsonObject) }
        val namespaces = english.map { it.substringBefore('.') }.toSet()
        val pattern = Regex(""""((?:${namespaces.joinToString("|")})\.[A-Za-z0-9_.]+)"""")
        val used = sources.walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> pattern.findAll(f.readText()).map { it.groupValues[1] } }
            .filterNot { it.endsWith(".") }
            .toSet()
        val missing = used.filter { key ->
            key !in english && "${key}_one" !in english && "${key}_other" !in english && english.none { it.startsWith("$key.") || it.startsWith(key) && it != key }
        }
        assertTrue("missing in en.json: $missing", missing.isEmpty())
    }
}
