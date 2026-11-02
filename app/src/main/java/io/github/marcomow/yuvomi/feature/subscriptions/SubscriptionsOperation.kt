package io.github.marcomow.yuvomi.feature.subscriptions

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations the budget page's Subscriptions tab calls (docs/spec/subscriptions.md). Must
 * match `upstream/operations-used.d/subscriptions.txt` exactly (ContractFilesTest).
 */
enum class SubscriptionsOperation(override val method: String, override val template: String) : ApiOperation
