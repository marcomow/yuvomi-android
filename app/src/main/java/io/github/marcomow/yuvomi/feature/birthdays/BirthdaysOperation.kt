package io.github.marcomow.yuvomi.feature.birthdays

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Birthdays calls (docs/spec/birthdays.md). Must match
 * `upstream/operations-used.d/birthdays.txt` exactly (ContractFilesTest).
 */
enum class BirthdaysOperation(override val method: String, override val template: String) : ApiOperation
