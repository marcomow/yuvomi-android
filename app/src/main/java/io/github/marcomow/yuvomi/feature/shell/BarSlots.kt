package io.github.marcomow.yuvomi.feature.shell

import io.github.marcomow.yuvomi.core.api.model.Preferences
import io.github.marcomow.yuvomi.feature.settings.ModuleOrder

/**
 * Which modules sit in the bottom bar and which ones "More" lists (docs/spec/overview.md,
 * SHELL rules; upstream `mobileFavoriteItems` / `secondaryMobileItems` in public/router.js).
 *
 * The bar is Overview, the member's three slots from `mobile_nav_order` (chosen in Settings,
 * "My navigation"), then More. The four kitchen modules share one slot, `kitchen`, which opens
 * the one used last, as the web app's kitchen tab does. Pure, so it is unit tested.
 */
object BarSlots {
    const val KITCHEN = "kitchen"

    /** The kitchen modules in the order upstream's `getLastKitchenRoute` falls back through. */
    val KITCHEN_MODULES = ModuleOrder.KITCHEN_CHILDREN

    /**
     * The three slot ids for a member who can open [shown] (module ids, kitchen modules
     * individually), following their saved choice, then upstream's defaults, then any module.
     * App-only modules (Tags) cannot be chosen in upstream's settings and are never in the bar.
     */
    fun bar(prefs: Preferences, shown: List<String>): List<String> =
        ModuleOrder.resolveMobile(prefs.mobileNavOrder, shown.filter { it in UPSTREAM_IDS })

    /**
     * What "More" lists: every module in [shown] that is not in [bar], minus the member's hidden
     * modules, in their navigation order. Kitchen modules stay listed even when the kitchen slot
     * is in the bar, because the slot opens only one of them.
     */
    fun more(prefs: Preferences, shown: List<String>, bar: List<String>): List<String> {
        val rest = shown.filter { it !in bar && it !in prefs.hiddenModules }
        return ModuleOrder.sort(rest, prefs.moduleOrder) { it }
    }

    /** The kitchen module the kitchen slot opens: the last one used, else the first one shown. */
    fun kitchenTarget(last: String?, shown: List<String>): String? =
        last?.takeIf { it in shown && it in KITCHEN_MODULES } ?: KITCHEN_MODULES.firstOrNull { it in shown }

    private val UPSTREAM_IDS = ModuleOrder.BUILT_IN.toSet() + KITCHEN_MODULES
}
