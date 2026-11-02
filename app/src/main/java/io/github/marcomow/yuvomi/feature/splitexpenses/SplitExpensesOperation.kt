package io.github.marcomow.yuvomi.feature.splitexpenses

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations the budget page's SplitExpenses tab calls (docs/spec/splitexpenses.md). Must
 * match `upstream/operations-used.d/splitexpenses.txt` exactly (ContractFilesTest).
 */
enum class SplitExpensesOperation(override val method: String, override val template: String) : ApiOperation
