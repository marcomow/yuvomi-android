package io.github.marcomow.yuvomi.feature.birthdays

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Birthdays calls (docs/spec/birthdays.md). Must match
 * `upstream/operations-used.d/birthdays.txt` exactly (ContractFilesTest).
 */
enum class BirthdaysOperation(override val method: String, override val template: String) : ApiOperation {
    LIST("GET", "/api/v1/birthdays"),
    CREATE("POST", "/api/v1/birthdays"),
    UPDATE("PUT", "/api/v1/birthdays/{id}"),
    DELETE("DELETE", "/api/v1/birthdays/{id}"),
    IMPORT_CANDIDATES("GET", "/api/v1/birthdays/import/candidates"),
    IMPORT("POST", "/api/v1/birthdays/import"),
    FEED("GET", "/api/v1/birthdays/feed"),
    FEED_REGENERATE("POST", "/api/v1/birthdays/feed/regenerate"),
    FEED_DISABLE("DELETE", "/api/v1/birthdays/feed"),
}
