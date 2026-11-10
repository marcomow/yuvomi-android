package io.github.marcomow.yuvomi.feature.pantry

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings

/** Labels and sentences the web app derives from data (PANTRY-R4, R6, R18, R21). */
object PantryText {
    /** `DEFAULT_LOCATION_I18N` (`public/utils/pantry-locations.js`): only seeded names are translated. */
    private val LOCATIONS = mapOf(
        "Vorratsschrank" to "pantry.locPantry",
        "Kühlschrank" to "pantry.locFridge",
        "Gefrierschrank" to "pantry.locFreezer",
        "Keller" to "pantry.locCellar",
        "Sonstiges" to "pantry.locOther",
    )

    /** `DEFAULT_CATEGORY_I18N` (`public/utils/shopping-categories.js`). */
    private val CATEGORIES = mapOf(
        "Obst & Gemüse" to "shopping.catFruitVeg",
        "Backwaren" to "shopping.catBakery",
        "Milchprodukte" to "shopping.catDairy",
        "Fleisch & Fisch" to "shopping.catMeatFish",
        "Tiefkühl" to "shopping.catFrozen",
        "Getränke" to "shopping.catDrinks",
        "Haushalt" to "shopping.catHousehold",
        "Drogerie" to "shopping.catDrugstore",
        "Sonstiges" to "shopping.catMisc",
    )

    fun locationLabel(name: String, strings: Strings): String = LOCATIONS[name]?.let { strings.t(it) } ?: name

    fun categoryLabel(name: String, strings: Strings): String = CATEGORIES[name]?.let { strings.t(it) } ?: name

    /** `placeText`: a batch's place as the row names it. */
    fun place(item: PantryItem, strings: Strings): String =
        item.locationName?.let { locationLabel(it, strings) } ?: strings.t("pantry.unlocated")

    fun quantity(item: PantryItem, strings: Strings) = PantryUnits.quantityLabel(item.quantity, item.unit, strings)

    /** "4 l in all". */
    fun total(total: Double, unit: String?, strings: Strings) =
        strings.t("pantry.productTotal", "quantity" to PantryUnits.quantityLabel(total, unit, strings))

    /** `productWhereText` without the leading separator: "2 places", or "2 batches · Fridge". */
    fun where(batches: List<PantryItem>, strings: Strings): String {
        val places = PantryView.placeCount(batches)
        return if (places > 1) {
            strings.t("pantry.productPlaces", "count" to places)
        } else {
            "${strings.t("pantry.productBatches", "count" to batches.size)} · ${place(batches.first(), strings)}"
        }
    }

    /** `expiryBadge`'s text, or null when the batch is neither expired nor soon. */
    fun expiry(item: PantryItem, status: PantryStatus.Status, today: java.time.LocalDate, strings: Strings): String? {
        if (status.expiry == null) return null
        val days = PantryStatus.daysUntil(item.expiresOn ?: return null, today) ?: return null
        val phrase = PantryStatus.phrase(days)
        return if (phrase.count == null) strings.t(phrase.key) else strings.t(phrase.key, "count" to phrase.count)
    }

    /** `shortfallText`: the missing amount as display text for the shopping list, or null. */
    fun shortfall(item: PantryItem, strings: Strings): String? =
        PantryView.shortfall(item)?.let { PantryUnits.quantityLabel(it, item.unit, strings) }

    /** `transferMessage('pantry.toShoppingDone', ...)`: two whole sentences. */
    fun transferMessage(added: Int, skipped: Int, list: String, strings: Strings): String {
        val done = strings.t("pantry.toShoppingDone", "count" to added, "list" to list)
        return if (skipped > 0) "$done ${strings.t("kitchen.transferSkipped", "count" to skipped)}" else done
    }

    /** Upstream's `errorSentence` (`public/utils/friendly-error.js`) as an i18n key (PANTRY-R21). */
    fun errorKey(e: Throwable): String = when (e) {
        is ApiProblem.Network -> "common.errorOfflineMutation"
        is ApiProblem.Http -> when {
            e.reason == "restore_in_progress" -> "common.errorRestoreInProgress"
            e.status == 403 -> when (e.reason) {
                "csrf_invalid" -> "common.errorFormExpired"
                "module_access_denied" -> "common.errorModuleNoAccess"
                "not_owner" -> "common.errorNotOwner"
                else -> "common.errorNoPermission"
            }
            e.reason == "shopping_list_not_found" -> "shopping.errorListGone"
            e.reason == "pantry_product_has_batches" -> "pantry.errorProductHasStock"
            e.status == 429 -> "common.errorRateLimited"
            // Upstream's pantry routes answer a missing row with a 404 without reason, so only
            // a 405 means the operation itself is gone here (spec, "Upstream operations").
            e.status == 405 -> "android.error.unsupported"
            else -> "common.errorGeneric"
        }
        else -> "common.errorGeneric"
    }

    /**
     * The location manager's sentences (PANTRY-R20). Since 2.86.0 the server names each case
     * (`pantry_location_*`, PANTRY-R28); an older one answers without a reason.
     */
    fun locationErrorKey(e: Throwable, deleting: Boolean = false): String = when {
        e is ApiProblem.Http && e.reason == "pantry_location_not_found" -> "pantry.errorLocationGone"
        e is ApiProblem.Http && e.reason == "pantry_location_exists" -> "pantry.errorLocationExists"
        e is ApiProblem.Http && e.reason == "pantry_location_last" -> "pantry.errorLocationLast"
        e is ApiProblem.Http && e.status == 409 -> "android.pantry.locationExists"
        // The one 400 a delete can get: the last location stays.
        deleting && e is ApiProblem.Http && e.status == 400 -> "android.pantry.locationLast"
        else -> errorKey(e)
    }
}
