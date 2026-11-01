package io.github.marcomow.yuvomi.core.api

/**
 * Every upstream operation the app calls. Must match `upstream/operations-used.txt`
 * exactly; `OperationsListTest` enforces it, and the weekly sync checks that file
 * against upstream's OpenAPI document (ADR 0003, ADR 0008).
 */
enum class Operation(val method: String, val template: String) {
    VERSION("GET", "/api/v1/version"),
    LOGIN("POST", "/api/v1/auth/login"),
    VERIFY_2FA("POST", "/api/v1/auth/2fa/verify"),
    ME("GET", "/api/v1/auth/me"),
    LOGOUT("POST", "/api/v1/auth/logout"),
    PREFERENCES("GET", "/api/v1/preferences"),
    AVATAR("GET", "/api/v1/people/{id}/avatar"),
    DASHBOARD("GET", "/api/v1/dashboard"),
    TASKS_LIST("GET", "/api/v1/tasks"),
    TASKS_CREATE("POST", "/api/v1/tasks"),
    TASKS_META("GET", "/api/v1/tasks/meta/options"),
    TASK_GET("GET", "/api/v1/tasks/{id}"),
    TASK_UPDATE("PUT", "/api/v1/tasks/{id}"),
    TASK_DELETE("DELETE", "/api/v1/tasks/{id}"),
    TASK_STATUS("PATCH", "/api/v1/tasks/{id}/status"),
    TASK_CHECK("PATCH", "/api/v1/tasks/{id}/check"),
    COMMENTS_LIST("GET", "/api/v1/tasks/{id}/comments"),
    COMMENT_CREATE("POST", "/api/v1/tasks/{id}/comments"),
    COMMENT_UPDATE("PATCH", "/api/v1/tasks/{id}/comments/{commentId}"),
    COMMENT_DELETE("DELETE", "/api/v1/tasks/{id}/comments/{commentId}"),
    ;

    val stateChanging: Boolean get() = method != "GET"

    /** The auth routes sit before upstream's session guard: a 401 there means "wrong credentials". */
    val isSignIn: Boolean get() = this == LOGIN || this == VERIFY_2FA

    /** `METHOD /path`, as written in `upstream/operations-used.txt`. */
    val signature: String get() = "$method $template"

    fun path(params: Map<String, Any> = emptyMap()): String =
        params.entries.fold(template) { acc, (key, value) ->
            acc.replace("{$key}", java.net.URLEncoder.encode(value.toString(), "UTF-8").replace("+", "%20"))
        }.also { require('{' !in it) { "missing path parameter in $it" } }
}
