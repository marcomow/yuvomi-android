package io.github.marcomow.yuvomi.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.i18n.t
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** One subject's stored deviations, per axis (`getSubjectPermissions`). */
data class PermissionDraft(
    val modules: Map<String, String> = emptyMap(),
    val widgets: Map<String, String> = emptyMap(),
    val capabilities: Map<String, String> = emptyMap(),
) {
    /** `payloadOf`: only own entries, never `inherit`; the PUT replaces the subject's set. */
    fun payload(): JsonObject = buildJsonObject {
        put("modules", JsonObject(modules.filterValues { it != INHERIT }.mapValues { JsonPrimitive(it.value) }))
        put("widgets", JsonObject(widgets.filterValues { it != INHERIT }.mapValues { JsonPrimitive(it.value) }))
        put("capabilities", JsonObject(capabilities.filterValues { it != INHERIT }.mapValues { JsonPrimitive(it.value) }))
    }

    fun with(axis: String, key: String, value: String): PermissionDraft = when (axis) {
        "modules" -> copy(modules = modules + (key to value))
        "widgets" -> copy(widgets = widgets + (key to value))
        else -> copy(capabilities = capabilities + (key to value))
    }

    companion object {
        const val INHERIT = "inherit"

        fun of(data: JsonObject) = PermissionDraft(
            data.child("modules").mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() },
            data.child("widgets").mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() },
            data.child("capabilities").mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() },
        )
    }
}

/** The labels of `admin-permissions.js`. */
object PermissionRules {
    val WIDGET_LABEL_KEYS = mapOf(
        "tasks" to "nav.tasks", "calendar" to "nav.calendar", "meals" to "nav.meals", "shopping" to "nav.shopping",
        "birthdays" to "nav.birthdays", "budget" to "nav.budget", "rewards" to "nav.rewards", "health" to "nav.health",
        "fasting" to "health.fasting.title", "nutrition" to "health.nutrition.title", "cycle" to "settings.permWidgetCycle",
        "housekeeping" to "nav.housekeeping", "schedule" to "nav.schedule", "waste" to "nav.waste",
        "pantry" to "dashboard.pantryExpiringTitle", "notes" to "nav.notes", "family" to "settings.permWidgetFamily",
        "weather" to "settings.permWidgetWeather", "clock" to "dashboard.clock", "metrics" to "dashboard.metrics",
        "countdown" to "dashboard.countdownTitle", "quicklinks" to "dashboard.quickLinksTitle",
    )

    /** `effectiveCapabilityAccess`. */
    fun capability(default: String?, mode: String, draft: String?, inherited: String?): String = when {
        draft != null && draft != PermissionDraft.INHERIT -> draft
        mode == "user" -> inherited ?: default ?: "none"
        else -> default ?: "none"
    }

    /** What a module resolves to for this subject, for locking its widgets (`effectiveModuleAccess`). */
    fun module(mode: String, draft: String?, inherited: String?): String =
        if (draft != null && draft != PermissionDraft.INHERIT) draft else if (mode == "user") inherited ?: "write" else "write"
}

/** `admin-permissions.js`: role defaults and member exceptions, saved on every choice (SET-R60 to R63). */
@Composable
fun PermissionsSection(ctx: SectionContext) {
    val slots by ctx.vm.slots.collectAsState()
    LaunchedEffect(Unit) { ctx.vm.load("permissions-catalog") { it.data(SettingsOperation.PERMISSIONS_CATALOG) } }
    val catalogSlot = slots["permissions-catalog"]
    val catalog = catalogSlot?.value as? JsonObject
    if (catalog == null) {
        if (catalogSlot?.errorKey != null) SettingsGroup(footer = t("settings.permLoadError")) {
            ActionRow(t("settings.retry")) { ctx.vm.load("permissions-catalog", force = true) { it.data(SettingsOperation.PERMISSIONS_CATALOG) } }
        } else Busy(true)
        return
    }
    var mode by rememberSaveable { mutableStateOf("role") }
    var subject by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<PermissionDraft?>(null) }
    var inherited by remember { mutableStateOf(PermissionDraft()) }
    var confirmReset by remember { mutableStateOf(false) }
    val queue = remember { Mutex() }
    val members = catalog["members"].arr().map { it.obj() }
    val member = members.firstOrNull { it.int("id")?.toString() == subject }

    LaunchedEffect(mode, subject) {
        draft = null
        val id = subject ?: return@LaunchedEffect
        if (mode == "role") {
            ctx.vm.attempt { it.data(SettingsOperation.PERMISSIONS_ROLE, mapOf("familyRole" to id)) }
                .onSuccess { draft = PermissionDraft.of(it.obj()); inherited = PermissionDraft() }
                .onFailure { ctx.notifyKey("settings.permLoadError") }
        } else if (member?.str("role") != "admin") {
            ctx.vm.attempt { api ->
                val own = api.data(SettingsOperation.PERMISSIONS_USER, mapOf("userId" to id)).obj()
                val role = member?.str("family_role")?.let { api.data(SettingsOperation.PERMISSIONS_ROLE, mapOf("familyRole" to it)).obj() }
                PermissionDraft.of(own) to (role?.let(PermissionDraft::of) ?: PermissionDraft())
            }.onSuccess { (own, role) -> draft = own; inherited = role }
                .onFailure { ctx.notifyKey("settings.permLoadError") }
        }
    }

    fun persist(next: PermissionDraft, title: String) {
        val previous = draft
        draft = next
        val id = subject ?: return
        ctx.scope.launch {
            queue.withLock {
                val op = if (mode == "role") SettingsOperation.PERMISSIONS_ROLE_UPDATE else SettingsOperation.PERMISSIONS_USER_UPDATE
                val params = if (mode == "role") mapOf("familyRole" to id) else mapOf("userId" to id)
                val failed = ctx.vm.write { it.call(op, params, body = next.payload()) }
                if (failed != null) { draft = previous; ctx.notifyKey(failed) }
                else { ctx.vm.refreshSession(); ctx.notifyKey("settings.permSaved", "name" to title) }
            }
        }
    }

    Hint(t("settings.permIntro"))
    SettingsGroup(title = t("settings.permModeLabel")) {
        GroupBody {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(mode == "role", onClick = { mode = "role"; subject = null }, label = { Text(t("settings.permByRole")) })
                FilterChip(mode == "user", onClick = { mode = "user"; subject = null }, label = { Text(t("settings.permByMember")) })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mode == "role") catalog.strings("roles").forEach { role ->
                    FilterChip(subject == role, onClick = { subject = role }, label = { Text(t(FamilyRules.roleKey(role))) })
                } else members.forEach { m ->
                    val label = m.str("display_name").orEmpty() + if (m.str("role") == "admin") " · " + t("settings.systemAdminBadge") else ""
                    FilterChip(subject == m.int("id")?.toString(), onClick = { subject = m.int("id")?.toString() }, label = { Text(label) })
                }
            }
            if (mode == "user" && members.isEmpty()) Hint(t("settings.permNoMembers"))
        }
    }

    val id = subject
    if (id == null) {
        Hint(t(if (mode == "role") "settings.permSelectRolePrompt" else "settings.permSelectMemberPrompt"))
        return
    }
    if (mode == "user" && member?.str("role") == "admin") {
        Hint(t("settings.permAdminBadgeHint"))
        return
    }
    val d = draft ?: run { Busy(true); return }
    val title = if (mode == "role") ctx.strings.t(FamilyRules.roleKey(id)) else member?.str("display_name").orEmpty()
    val inherit = if (mode == "user") listOf(PermissionDraft.INHERIT to t("settings.permInherit")) else emptyList()
    fun accessShort(v: String) = ctx.strings.t(when (v) { "none" -> "settings.permAccessNone"; "read" -> "settings.permAccessRead"; "allow" -> "settings.permWidgetAllowed"; else -> "settings.permAccessWrite" })

    Hint(t(if (mode == "role") "settings.permRoleLegend" else "settings.permMemberLegend"))
    val widgets = catalog["widgets"].arr().map { it.obj() }
    val capabilities = catalog["capabilities"].arr().map { it.obj() }
    catalog["modules"].arr().map { it.obj() }.forEach { mod ->
        val key = mod.str("key") ?: return@forEach
        val label = mod.str("labelKey")?.let { t(it) } ?: mod.str("label") ?: key
        val moduleAccess = PermissionRules.module(mode, d.modules[key], inherited.modules[key])
        SettingsGroup(title = label) {
            ChoiceRow(
                label,
                inherit + listOf("none" to t("settings.permAccessNone"), "read" to t("settings.permAccessRead"), "write" to t("settings.permAccessWrite")),
                d.modules[key] ?: if (mode == "user") PermissionDraft.INHERIT else "write",
                ctx.editable,
                hint = if (mode == "user") ctx.strings.t("settings.permInheritedHint", "value" to accessShort(inherited.modules[key] ?: "write")) else null,
            ) { persist(d.with("modules", key, it), title) }
            widgets.filter { it.str("module") == key }.forEach { w ->
                val wid = w.str("id") ?: return@forEach
                RowDivider()
                ChoiceRow(
                    w.str("label") ?: t(PermissionRules.WIDGET_LABEL_KEYS[wid] ?: wid),
                    inherit + listOf("none" to t("settings.permWidgetBlocked"), "allow" to t("settings.permWidgetAllowed")),
                    d.widgets[wid] ?: if (mode == "user") PermissionDraft.INHERIT else "allow",
                    ctx.editable && moduleAccess != "none",
                    hint = if (moduleAccess == "none") t("settings.permModuleLockedHint") else null,
                ) { persist(d.with("widgets", wid, it), title) }
            }
            capabilities.filter { it.str("module") == key }.forEach { c ->
                val ckey = c.str("key") ?: return@forEach
                RowDivider()
                ChoiceRow(
                    c.str("labelKey")?.let { t(it) } ?: ckey,
                    inherit + listOf("none" to t("settings.permCapabilityBlocked"), "allow" to t("settings.permCapabilityAllowed")),
                    d.capabilities[ckey] ?: if (mode == "user") PermissionDraft.INHERIT else PermissionRules.capability(c.str("default"), mode, null, null),
                    ctx.editable,
                ) { persist(d.with("capabilities", ckey, it), title) }
            }
        }
    }
    val general = widgets.filter { it.str("module") == null }
    if (general.isNotEmpty()) SettingsGroup(title = t("settings.permWidgetsHeading")) {
        general.forEachIndexed { i, w ->
            val wid = w.str("id") ?: return@forEachIndexed
            if (i > 0) RowDivider()
            ChoiceRow(
                w.str("label") ?: t(PermissionRules.WIDGET_LABEL_KEYS[wid] ?: wid),
                inherit + listOf("none" to t("settings.permWidgetBlocked"), "allow" to t("settings.permWidgetAllowed")),
                d.widgets[wid] ?: if (mode == "user") PermissionDraft.INHERIT else "allow",
                ctx.editable,
            ) { persist(d.with("widgets", wid, it), title) }
        }
    }
    if (ctx.editable) SettingsGroup {
        ActionRow(t("settings.permResetSubject"), danger = true) { confirmReset = true }
    }
    if (confirmReset) {
        ConfirmDialog(ctx.strings.t("settings.permResetConfirm", "name" to title), t("settings.permResetConfirmDetail"), t("settings.permResetSubject"), danger = true, onDismiss = { confirmReset = false }) {
            confirmReset = false
            persist(PermissionDraft(), title)
        }
    }
}
