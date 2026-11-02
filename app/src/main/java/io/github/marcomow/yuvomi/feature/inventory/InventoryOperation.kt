package io.github.marcomow.yuvomi.feature.inventory

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Inventory calls (docs/spec/inventory.md). Must match
 * `upstream/operations-used.d/inventory.txt` exactly (ContractFilesTest).
 */
enum class InventoryOperation(override val method: String, override val template: String) : ApiOperation
