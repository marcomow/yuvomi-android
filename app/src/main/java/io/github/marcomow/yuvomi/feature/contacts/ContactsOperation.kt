package io.github.marcomow.yuvomi.feature.contacts

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Contacts calls (docs/spec/contacts.md). Must match
 * `upstream/operations-used.d/contacts.txt` exactly (ContractFilesTest).
 */
enum class ContactsOperation(override val method: String, override val template: String) : ApiOperation {
    CONTACTS_LIST("GET", "/api/v1/contacts"),
    CONTACT_CREATE("POST", "/api/v1/contacts"),
    CONTACTS_META("GET", "/api/v1/contacts/meta"),
    CATEGORIES_LIST("GET", "/api/v1/contacts/categories"),
    CATEGORY_CREATE("POST", "/api/v1/contacts/categories"),
    CATEGORIES_REORDER("PATCH", "/api/v1/contacts/categories/reorder"),
    CATEGORY_UPDATE("PUT", "/api/v1/contacts/categories/{key}"),
    CATEGORY_DELETE("DELETE", "/api/v1/contacts/categories/{key}"),
    CONTACT_GET("GET", "/api/v1/contacts/{id}"),
    CONTACT_UPDATE("PUT", "/api/v1/contacts/{id}"),
    CONTACT_DELETE("DELETE", "/api/v1/contacts/{id}"),
    CONTACT_VCARD("GET", "/api/v1/contacts/{id}/vcard"),

    // Birthdays (permission `calendar`): the detail's "Link" and "Add to birthdays".
}
