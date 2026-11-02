package io.github.marcomow.yuvomi.feature.contacts

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Contacts calls (docs/spec/contacts.md). Must match
 * `upstream/operations-used.d/contacts.txt` exactly (ContractFilesTest).
 */
enum class ContactsOperation(override val method: String, override val template: String) : ApiOperation
