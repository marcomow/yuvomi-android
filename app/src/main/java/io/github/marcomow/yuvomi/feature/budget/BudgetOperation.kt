package io.github.marcomow.yuvomi.feature.budget

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Budget calls (docs/spec/budget.md). Must match
 * `upstream/operations-used.d/budget.txt` exactly (ContractFilesTest).
 */
enum class BudgetOperation(override val method: String, override val template: String) : ApiOperation
