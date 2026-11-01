package io.github.marcomow.yuvomi.core.api

/**
 * One upstream operation: `METHOD /api/v1/path` with OpenAPI path templates.
 * Core and Tasks list theirs in [Operation]; every other feature keeps its own
 * enum in its own package (for example `ShoppingOperation`), listed in
 * `upstream/operations-used.d/<feature>.txt`. `Features.operations` gathers
 * them all for the contract-file test (ADR 0003, ADR 0008, ADR 0015).
 */
interface ApiOperation {
    val method: String
    val template: String

    /** The auth routes sit before upstream's session guard: a 401 there means "wrong credentials". */
    val isSignIn: Boolean get() = false

    val stateChanging: Boolean get() = method != "GET"

    /** `METHOD /path`, as written in `upstream/operations-used*.txt`. */
    val signature: String get() = "$method $template"

    fun path(params: Map<String, Any> = emptyMap()): String =
        params.entries.fold(template) { acc, (key, value) ->
            acc.replace("{$key}", java.net.URLEncoder.encode(value.toString(), "UTF-8").replace("+", "%20"))
        }.also { require('{' !in it) { "missing path parameter in $it" } }
}
