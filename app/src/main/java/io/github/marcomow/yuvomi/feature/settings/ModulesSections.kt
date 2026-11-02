package io.github.marcomow.yuvomi.feature.settings

import io.github.marcomow.yuvomi.feature.shell.BarSlots
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.t
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One row of the module lists: a built-in module, the kitchen group, or an installed module. */
data class ModuleRow(
    val id: String,
    /** The id in `module_order` (`third-party-<id>` for installed modules). */
    val orderId: String,
    val type: String,
    val labelKey: String? = null,
    val label: String? = null,
    val enabled: Boolean,
    val hidden: Boolean = false,
    val locked: Boolean = false,
    val children: List<ModuleRow> = emptyList(),
    val status: String? = null,
) {
    val section: Int get() = ModuleOrder.section(orderId)
}

/** The module lists of `modules-active.js` and `modules-navigation.js`, pure (SET-R30 to R33). */
object ModuleRules {
    private val LABELS = mapOf(
        "dashboard" to "nav.dashboard", "calendar" to "nav.calendar", "tasks" to "nav.tasks", "rewards" to "nav.rewards",
        "notes" to "nav.notes", "schedule" to "nav.schedule", "contacts" to "nav.contacts", "birthdays" to "nav.birthdays",
        "budget" to "nav.budget", "documents" to "nav.documents", "inventory" to "nav.inventory",
        "housekeeping" to "nav.housekeeping", "waste" to "nav.waste", "health" to "nav.health", "settings" to "nav.settings",
        "meals" to "nav.meals", "recipes" to "nav.recipes", "shopping" to "nav.shopping", "pantry" to "nav.pantry", "kitchen" to "nav.kitchen",
    )

    fun labelKey(id: String) = LABELS[id]

    /** `moduleDisplayLabel`: the menu's label, the name, the id. */
    fun thirdPartyLabel(m: JsonObject): String = m.child("menu").str("label") ?: m.str("name") ?: m.str("id").orEmpty()

    /** `buildRows` of both sheets: built-ins, the kitchen before the first later household module, then installed ones. */
    fun rows(prefs: JsonObject, thirdParty: List<JsonObject>, includeLocked: Boolean): List<ModuleRow> {
        val disabled = prefs.strings("disabled_modules").toSet()
        val hidden = prefs.strings("hidden_modules").toSet()
        val children = ModuleOrder.KITCHEN_CHILDREN.map { ModuleRow(it, it, "kitchen-child", LABELS[it], enabled = it !in disabled, hidden = it in hidden) }
        val kitchen = ModuleRow("kitchen", "kitchen", "kitchen", "nav.kitchen", enabled = children.any { it.enabled }, hidden = children.all { it.hidden }, children = children)
        val out = mutableListOf<ModuleRow>()
        var inserted = false
        for (id in ModuleOrder.BUILT_IN) {
            val locked = id in ModuleOrder.LOCKED
            if (locked && !includeLocked) continue
            if (!inserted && id in setOf("housekeeping", "documents", "rewards", "contacts", "birthdays", "health", "budget")) {
                out += kitchen
                inserted = true
            }
            out += ModuleRow(id, id, "built-in", LABELS[id], enabled = locked || id !in disabled, hidden = id in hidden, locked = locked)
        }
        if (!inserted) out += kitchen
        thirdParty.forEach { m ->
            val id = m.str("id") ?: return@forEach
            out += ModuleRow(id, "third-party-$id", "third-party", label = thirdPartyLabel(m), enabled = m.bool("enabled") && m.str("status") == "enabled", status = m.str("status"))
        }
        return out
    }

    /** `collectDisabledModuleIds` after switching [row] (a built-in, the kitchen, or a kitchen child). */
    fun disabledAfter(prefs: JsonObject, rowId: String, on: Boolean): List<String> {
        val disabled = prefs.strings("disabled_modules").toMutableSet()
        val ids = if (rowId == "kitchen") ModuleOrder.KITCHEN_CHILDREN else listOf(rowId)
        if (on) disabled -= ids.toSet() else disabled += ids
        return disabled.toList()
    }

    /** `collectHiddenModuleIds` after showing or hiding [rowId] in my navigation. */
    fun hiddenAfter(prefs: JsonObject, rowId: String, show: Boolean): List<String> {
        val hidden = prefs.strings("hidden_modules").toMutableSet()
        val ids = if (rowId == "kitchen") ModuleOrder.KITCHEN_CHILDREN else listOf(rowId)
        if (show) hidden -= ids.toSet() else hidden += ids
        return hidden.toList()
    }

    /**
     * Moves [orderId] one place within its group and answers the new `module_order`
     * (`buildOrderPayload`: expanded kitchen, ids not shown kept at the end).
     */
    fun moved(sorted: List<ModuleRow>, orderId: String, delta: Int, saved: List<String>): List<String> {
        val ids = sorted.filterNot { it.locked }.map { it.orderId }.toMutableList()
        val from = ids.indexOf(orderId)
        val to = from + delta
        val section = ModuleOrder.section(orderId)
        if (from < 0 || to !in ids.indices || ModuleOrder.section(ids[to]) != section) return expand(ids) + saved.filter { it !in expand(ids) }
        ids.add(to, ids.removeAt(from))
        val visible = expand(ids)
        return visible + saved.filter { it !in visible }
    }

    fun expand(order: List<String>): List<String> = ModuleOrder.normalize(order).flatMap { if (it == "kitchen") ModuleOrder.KITCHEN_CHILDREN else listOf(it) }
}

@Composable
private fun modulesData(ctx: SectionContext): Pair<JsonObject, List<JsonObject>>? {
    val slots by ctx.vm.slots.collectAsState()
    LaunchedEffect(Unit) {
        ctx.vm.load("modules") { it.data(SettingsOperation.MODULES, query = if (ctx.isAdmin) listOf("admin" to "1") else emptyList()) }
    }
    val prefSlot = slots[SettingsSheetViewModel.PREFERENCES]
    val prefs = prefSlot?.value as? JsonObject
    if (prefs == null) {
        if (prefSlot?.errorKey != null) SettingsGroup(footer = t(prefSlot.errorKey)) { ActionRow(t("settings.retry")) { ctx.vm.loadPreferences(force = true) } }
        else Busy(true)
        return null
    }
    val third = slots["modules"]?.value.arr().map { it.obj() }
    return prefs to third
}

private val transparent @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
private fun rowLabel(row: ModuleRow) = row.label ?: row.labelKey?.let { t(it) } ?: row.id

/** `modules-active.js`: switch a module on or off for the whole household (SET-R30, R31). */
@Composable
fun ActiveModulesSection(ctx: SectionContext) {
    val (prefs, third) = modulesData(ctx) ?: return
    val rows = ModuleRules.rows(prefs, third, includeLocked = false)
    fun save(rowId: String, on: Boolean, thirdParty: Boolean) {
        ctx.scope.launch {
            if (thirdParty) {
                val failed = ctx.vm.write { it.call(SettingsOperation.MODULE_UPDATE, mapOf("id" to rowId), body = buildJsonObject { put("enabled", on) }) }
                if (failed != null) { ctx.notifyKey(failed); return@launch }
                ctx.vm.load("modules", force = true) { it.data(SettingsOperation.MODULES, query = listOf("admin" to "1")) }
            }
            val disabled = if (thirdParty) prefs.strings("disabled_modules") else ModuleRules.disabledAfter(prefs, rowId, on)
            val error = ctx.vm.savePreferences(buildJsonObject { put("disabled_modules", JsonArray(disabled.map(::JsonPrimitive))) })
            ctx.notifyKey(error ?: "settings.thirdPartyModulesSaved")
        }
    }
    Hint(t("settings.activeModulesHint"))
    rows.groupBy { it.section }.toSortedMap().forEach { (section, inSection) ->
        SettingsGroup(title = ModuleOrder.NAV_SECTION_LABEL_KEYS[section]?.let { t(it) }) {
            inSection.forEachIndexed { i, row ->
                if (i > 0) RowDivider()
                val label = rowLabel(row)
                SwitchRow(label, row.enabled, ctx.editable && row.status != "error", hint = row.status?.takeIf { it != "enabled" && row.type == "third-party" }) {
                    save(row.id, it, row.type == "third-party")
                }
                row.children.forEach { child ->
                    ListItem(
                        headlineContent = { Text(t(child.labelKey!!)) },
                        supportingContent = null,
                        trailingContent = { Switch(child.enabled, onCheckedChange = { save(child.id, it, false) }, enabled = ctx.editable) },
                        colors = transparent,
                        leadingContent = { Text("  ") },
                    )
                }
                if (row.children.isNotEmpty()) Hint(ctx.strings.t("settings.kitchenActiveCount", "count" to row.children.count { it.enabled }))
            }
        }
    }
}

/** `modules-navigation.js`: my bar's three slots, and which modules my sidebar shows in which order (SET-R32, R33). */
@Composable
fun NavigationSection(ctx: SectionContext) {
    val (prefs, third) = modulesData(ctx) ?: return
    val all = ModuleRules.rows(prefs, third, includeLocked = true)
    val sorted = ModuleOrder.sort(all, prefs.strings("module_order")) { it.orderId }
    val savedOrder = prefs.strings("module_order")

    // The phone bar: three slots from the modules switched on for the household.
    val available = all.filter { it.enabled && !it.locked && it.type != "third-party" }.map { it.id } +
        all.filter { it.type == "third-party" && it.enabled }.map { it.orderId }
    val saved = ModuleOrder.resolveMobile(prefs.strings("mobile_nav_order"), available)
    // Tags is the app's own module: the slot showing it is kept on the phone (BarSlots.TAGS).
    val settings = ctx.shell.container.settings
    val tagsSlot by settings.tagsBarSlot.collectAsState(initial = null)
    val tagsOffered = ctx.shell.shows(BarSlots.TAGS)
    val mobile = saved.toMutableList().also { m -> tagsSlot?.takeIf { tagsOffered && it < m.size }?.let { m[it] = BarSlots.TAGS } }
    SettingsGroup(title = t("settings.mobileNavigationTitle"), footer = t("settings.mobileNavigationHint")) {
        mobile.indices.forEach { slot ->
            if (slot > 0) RowDivider()
            val options = available.filter { it == mobile[slot] || it !in mobile }.map { id ->
                id to (all.firstOrNull { it.id == id || it.orderId == id }?.let { rowLabel(it) } ?: id)
            } + listOfNotNull((BarSlots.TAGS to t("android.tags.title")).takeIf { tagsOffered && (mobile[slot] == BarSlots.TAGS || BarSlots.TAGS !in mobile) })
            ChoiceRow(ctx.strings.t("settings.mobileNavigationSlotLabel", "position" to slot + 1), options, mobile[slot], ctx.editable) { id ->
                if (id == BarSlots.TAGS) {
                    ctx.scope.launch { settings.setTagsBarSlot(slot) }
                    return@ChoiceRow
                }
                if (slot == tagsSlot) ctx.scope.launch { settings.setTagsBarSlot(null) }
                // The server keeps its own three; the slot under Tags keeps what it had.
                val next = saved.toMutableList().also { it[slot] = id }
                ctx.save("settings.mobileNavigationSaved", buildJsonObject { put("mobile_nav_order", JsonArray(next.map(::JsonPrimitive))) })
            }
        }
    }

    Hint(t("settings.desktopNavigationHint"))
    sorted.groupBy { it.section }.toSortedMap().forEach { (section, inSection) ->
        SettingsGroup(title = ModuleOrder.NAV_SECTION_LABEL_KEYS[section]?.let { t(it) }) {
            inSection.forEachIndexed { i, row ->
                if (i > 0) RowDivider()
                val label = rowLabel(row)
                ListItem(
                    headlineContent = { Text(label) },
                    supportingContent = when {
                        row.locked -> ({ Text(t("settings.modulesBuiltInBadge"), color = Yuvomi.colors.textSecondary) })
                        !row.enabled -> ({ Text(t("settings.thirdPartyModulesStatusDisabled"), color = Yuvomi.colors.textSecondary) })
                        else -> null
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!row.locked && ctx.editable) {
                                IconButton(enabled = i > 0, onClick = {
                                    ctx.save("settings.modulesOrderSaved", buildJsonObject { put("module_order", JsonArray(ModuleRules.moved(sorted, row.orderId, -1, savedOrder).map(::JsonPrimitive))) })
                                }) { Icon(Icons.Outlined.KeyboardArrowUp, ctx.strings.t("category.moveUpNamed", "name" to label)) }
                                IconButton(enabled = i < inSection.lastIndex, onClick = {
                                    ctx.save("settings.modulesOrderSaved", buildJsonObject { put("module_order", JsonArray(ModuleRules.moved(sorted, row.orderId, 1, savedOrder).map(::JsonPrimitive))) })
                                }) { Icon(Icons.Outlined.KeyboardArrowDown, ctx.strings.t("category.moveDownNamed", "name" to label)) }
                            }
                            if (!row.locked && row.type != "third-party") {
                                Switch(!row.hidden, enabled = ctx.editable && row.enabled, onCheckedChange = { show ->
                                    ctx.save("settings.modulesSaved", buildJsonObject { put("hidden_modules", JsonArray(ModuleRules.hiddenAfter(prefs, row.id, show).map(::JsonPrimitive))) })
                                })
                            }
                        }
                    },
                    colors = transparent,
                )
                row.children.forEach { child ->
                    SwitchRow("  " + t(child.labelKey!!), !child.hidden, ctx.editable && child.enabled) { show ->
                        ctx.save("settings.modulesSaved", buildJsonObject { put("hidden_modules", JsonArray(ModuleRules.hiddenAfter(prefs, child.id, show).map(::JsonPrimitive))) })
                    }
                }
            }
        }
    }
    Hint(t("settings.modulesHiddenScopeHint"))
}
