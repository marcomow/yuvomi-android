package io.github.marcomow.yuvomi.core.api

/**
 * The upstream operations of the core and of Tasks. Must match
 * `upstream/operations-used.txt` exactly; `ContractFilesTest` enforces it, and the
 * weekly sync checks that file against upstream's OpenAPI document (ADR 0003,
 * ADR 0008). Other features list theirs in their own [ApiOperation] enum.
 */
enum class Operation(override val method: String, override val template: String) : ApiOperation {
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

    // Kitchen: read and create calls that more than one kitchen module makes (KitchenApi).
    SHOPPING_LISTS("GET", "/api/v1/shopping"),
    SHOPPING_CATEGORIES("GET", "/api/v1/shopping/categories"),
    SHOPPING_UNDO_TRANSFER("POST", "/api/v1/shopping/items/undo-transfer"),
    PANTRY_LIST("GET", "/api/v1/pantry"),
    RECIPES_LIST("GET", "/api/v1/recipes"),
    RECIPE_CREATE("POST", "/api/v1/recipes"),
    MEALS_LIST("GET", "/api/v1/meals"),
    MEAL_CREATE("POST", "/api/v1/meals"),
    KITCHEN_SUMMARY("GET", "/api/v1/kitchen/summary"),

    // Household directory and sync targets that several modules read.
    FAMILY_MEMBERS("GET", "/api/v1/family/members"),
    AUTH_USERS("GET", "/api/v1/auth/users"),
    CALENDAR_SYNC_TARGETS("GET", "/api/v1/calendar/sync-targets"),
    ;

    override val isSignIn: Boolean get() = this == LOGIN || this == VERIFY_2FA
}
