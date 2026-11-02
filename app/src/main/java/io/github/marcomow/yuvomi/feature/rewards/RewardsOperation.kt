package io.github.marcomow.yuvomi.feature.rewards

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Rewards calls (docs/spec/rewards.md). Must match
 * `upstream/operations-used.d/rewards.txt` exactly (ContractFilesTest).
 */
enum class RewardsOperation(override val method: String, override val template: String) : ApiOperation
