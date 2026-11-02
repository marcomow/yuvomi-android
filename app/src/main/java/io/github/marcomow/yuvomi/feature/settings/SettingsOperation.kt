package io.github.marcomow.yuvomi.feature.settings

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Settings calls (docs/spec/settings.md). Must match
 * `upstream/operations-used.d/settings.txt` exactly (ContractFilesTest).
 */
enum class SettingsOperation(override val method: String, override val template: String) : ApiOperation {
    // Account (personal-account.js)
    PROFILE_UPDATE("PATCH", "/api/v1/auth/me/profile"),
    PASSWORD_CHANGE("PATCH", "/api/v1/auth/me/password"),
    GRAVATAR("POST", "/api/v1/auth/me/avatar/gravatar"),
    TWO_FACTOR_STATUS("GET", "/api/v1/auth/2fa"),
    TWO_FACTOR_SETUP("POST", "/api/v1/auth/2fa/setup"),
    TWO_FACTOR_ENABLE("POST", "/api/v1/auth/2fa/enable"),
    TWO_FACTOR_DISABLE("POST", "/api/v1/auth/2fa/disable"),
    TWO_FACTOR_RECOVERY("POST", "/api/v1/auth/2fa/recovery-codes"),
    LOGOUT_OTHERS("POST", "/api/v1/auth/logout-others"),
    OIDC_LINK("GET", "/api/v1/auth/oidc/link"),
    OIDC_UNLINK("DELETE", "/api/v1/auth/oidc/link"),

    // Preferences (personal-appearance.js, modules-*.js, personal-*.js)
    PREFERENCES_UPDATE("PUT", "/api/v1/preferences"),

    // Family and roles (admin-family.js)
    USERS("GET", "/api/v1/auth/users"),
    USER_CREATE("POST", "/api/v1/auth/users"),
    USER_UPDATE("PATCH", "/api/v1/auth/users/{id}"),
    USER_DELETE("DELETE", "/api/v1/auth/users/{id}"),
    FAMILY_REORDER("PATCH", "/api/v1/family/members/reorder"),
    INVITES("GET", "/api/v1/auth/invites"),
    INVITE_CREATE("POST", "/api/v1/auth/invites"),
    INVITE_REVOKE("DELETE", "/api/v1/auth/invites/{id}"),
    TWO_FACTOR_OVERVIEW("GET", "/api/v1/auth/2fa/overview"),
    TWO_FACTOR_REQUIRE("PUT", "/api/v1/auth/2fa/require"),
    OIDC_CONFIG("GET", "/api/v1/auth/oidc/config"),

    // Permissions (admin-permissions.js)
    PERMISSIONS_CATALOG("GET", "/api/v1/permissions/catalog"),
    PERMISSIONS_ROLE("GET", "/api/v1/permissions/role/{familyRole}"),
    PERMISSIONS_ROLE_UPDATE("PUT", "/api/v1/permissions/role/{familyRole}"),
    PERMISSIONS_USER("GET", "/api/v1/permissions/user/{userId}"),
    PERMISSIONS_USER_UPDATE("PUT", "/api/v1/permissions/user/{userId}"),

    // Modules (modules-active.js)
    MODULES("GET", "/api/v1/modules"),
    MODULE_UPDATE("PATCH", "/api/v1/modules/{id}"),

    // Notification channels (notifications.js)
    NOTIFICATION_PROVIDERS("GET", "/api/v1/notifications/providers"),
    NOTIFICATION_CHANNELS("GET", "/api/v1/notifications/channels"),
    NOTIFICATION_CHANNEL_CREATE("POST", "/api/v1/notifications/channels"),
    NOTIFICATION_CHANNEL_UPDATE("PUT", "/api/v1/notifications/channels/{id}"),
    NOTIFICATION_CHANNEL_DELETE("DELETE", "/api/v1/notifications/channels/{id}"),
    NOTIFICATION_CHANNEL_TEST("POST", "/api/v1/notifications/channels/{id}/test"),

    // E-mail (admin-email.js)
    EMAIL_CONFIG("GET", "/api/v1/email/config"),
    EMAIL_CONFIG_UPDATE("PUT", "/api/v1/email/config"),
    EMAIL_TEST("POST", "/api/v1/email/test"),

    // Backup (admin-backup.js)
    BACKUP_STATUS("GET", "/api/v1/backup/status"),
    BACKUP_DATABASE("GET", "/api/v1/backup/database"),
    BACKUP_TRIGGER("POST", "/api/v1/backup/trigger"),
    WEBDAV_CONFIG("GET", "/api/v1/backup/webdav/config"),
    WEBDAV_CONFIG_UPDATE("PUT", "/api/v1/backup/webdav/config"),
    WEBDAV_TEST("POST", "/api/v1/backup/webdav/test"),
    WEBDAV_TRIGGER("POST", "/api/v1/backup/webdav/trigger"),

    // API access (admin-api.js)
    API_TOKENS("GET", "/api/v1/auth/api-tokens"),
    API_TOKEN_CREATE("POST", "/api/v1/auth/api-tokens"),
    API_TOKEN_REVOKE("DELETE", "/api/v1/auth/api-tokens/{id}"),
    API_TOKEN_REMOVE("POST", "/api/v1/auth/api-tokens/{id}/remove"),

    // Module sheets: sync targets for new tasks and events (personal-tasks.js, personal-calendar.js)
    TASKS_SYNC_TARGETS("GET", "/api/v1/tasks/sync-targets"),
    CALENDAR_SYNC_TARGETS("GET", "/api/v1/calendar/sync-targets"),

    // Default points and moving open tasks along (modules-rewards.js)
    TASKS_POINTS_AFFECTED("GET", "/api/v1/tasks/points/affected"),
    TASKS_POINTS_REBASE("POST", "/api/v1/tasks/points/rebase"),

    // Weather location (personal-weather.js, weather-location.js)
    WEATHER_GEOCODE("GET", "/api/v1/weather/geocode"),

    // About (admin-system.js)
    CHANGELOG("GET", "/api/v1/changelog"),
}
