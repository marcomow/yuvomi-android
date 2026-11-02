package io.github.marcomow.yuvomi.feature.settings

/**
 * Upstream's settings tree (`public/settings/registry.js` at the pinned version): three
 * domains, their sheets ("leaves") and the sections inside each sheet. Pure data and rules,
 * so the visibility logic is tested without Android (SET-R1 to R4).
 */
object SettingsRegistry {

    data class Domain(val id: String, val labelKey: String, val adminOnly: Boolean)

    /** A sheet: one screen, `settings/<id>` in the app, [path] in the web app. */
    data class Leaf(
        val id: String,
        val domainId: String,
        val path: String,
        val labelKey: String,
        val descriptionKey: String,
        /** A module sheet follows that module's access (`sheetModuleAccessible`). */
        val module: String? = null,
        val placementEnd: Boolean = false,
        /** Shown only where the app has it (app updates in a release build); never opened in the browser. */
        val appOnly: Boolean = false,
    )

    /** A section of a sheet: "for me" ([MINE]) or "for the household" ([HOUSEHOLD]). */
    data class Section(
        val id: String,
        val sheetId: String,
        val scope: String,
        val labelKey: String,
        val descriptionKey: String,
        val adminOnly: Boolean,
    )

    const val APP_UPDATES = "app-updates"
    const val MINE = "mine"
    const val HOUSEHOLD = "household"

    val domains = listOf(
        Domain("personal", "settings.domainAccount", adminOnly = false),
        Domain("admin", "settings.domainHousehold", adminOnly = true),
        Domain("modules", "settings.domainModules", adminOnly = false),
    )

    val leaves = listOf(
        Leaf("personal-account", "personal", "/settings/personal/account", "settings.pageAccount", "settings.pageAccountDescription"),
        Leaf("personal-appearance", "personal", "/settings/personal/appearance", "settings.pageAppearance", "settings.pageAppearanceDescription"),
        Leaf("personal-device", "personal", "/settings/personal/device", "settings.pageDevice", "settings.pageDeviceDescription"),
        Leaf("personal-notifications", "personal", "/settings/personal/notifications", "settings.pageNotifications", "settings.pageNotificationsDescription"),
        Leaf("personal-weather", "personal", "/settings/personal/weather", "settings.pageWeather", "settings.pageWeatherDescription"),
        // App-only (ADR 0018): this app's own updates. Not in upstream's registry; no web page.
        Leaf(APP_UPDATES, "personal", "", "android.update.title", "android.update.description", appOnly = true),
        Leaf("admin-family", "admin", "/settings/admin/family", "settings.pageFamilyRoles", "settings.pageFamilyRolesDescription"),
        Leaf("admin-permissions", "admin", "/settings/admin/permissions", "settings.pagePermissions", "settings.pagePermissionsDescription"),
        Leaf("admin-displays", "admin", "/settings/admin/displays", "settings.pageDisplays", "settings.pageDisplaysDescription"),
        Leaf("admin-email", "admin", "/settings/admin/email", "settings.pageEmail", "settings.pageEmailDescription"),
        Leaf("admin-backup", "admin", "/settings/admin/backup", "settings.pageBackupRestore", "settings.pageBackupRestoreDescription"),
        Leaf("admin-system", "admin", "/settings/admin/system", "settings.pageSystem", "settings.pageSystemDescription"),
        Leaf("admin-api", "admin", "/settings/admin/api", "settings.pageApiAccess", "settings.pageApiAccessDescription"),
        Leaf("admin-integrations", "admin", "/settings/admin/integrations", "settings.pageIntegrations", "settings.pageIntegrationsDescription"),
        Leaf("modules-active", "modules", "/settings/modules/active", "settings.pageActiveModules", "settings.pageActiveModulesDescription"),
        Leaf("modules-navigation", "modules", "/settings/modules/navigation", "settings.pageNavigation", "settings.pageNavigationDescription"),
        Leaf("module-dashboard", "modules", "/settings/modules/overview", "nav.dashboard", "settings.sheetDashboardDescription", module = "dashboard"),
        Leaf("module-calendar", "modules", "/settings/modules/calendar", "nav.calendar", "settings.sheetCalendarDescription", module = "calendar"),
        Leaf("module-calendar-sync", "modules", "/settings/modules/calendar-sync", "settings.pageSyncCalendar", "settings.pageSyncCalendarDescription", module = "calendar"),
        Leaf("module-tasks", "modules", "/settings/modules/tasks", "nav.tasks", "settings.sheetTasksDescription", module = "tasks"),
        Leaf("module-rewards", "modules", "/settings/modules/rewards", "nav.rewards", "settings.sheetRewardsDescription", module = "rewards"),
        Leaf("module-schedule", "modules", "/settings/modules/schedule", "nav.schedule", "settings.sheetScheduleDescription", module = "schedule"),
        Leaf("module-kitchen", "modules", "/settings/modules/kitchen", "nav.kitchen", "settings.pageKitchenDescription", module = "kitchen"),
        Leaf("module-housekeeping", "modules", "/settings/modules/housekeeping", "nav.housekeeping", "settings.sheetHousekeepingDescription", module = "housekeeping"),
        Leaf("module-waste", "modules", "/settings/modules/waste", "nav.waste", "settings.sheetWasteDescription", module = "waste"),
        Leaf("module-documents", "modules", "/settings/modules/documents", "nav.documents", "settings.sheetDocumentsDescription", module = "documents"),
        Leaf("module-inventory", "modules", "/settings/modules/inventory", "nav.inventory", "settings.sheetInventoryDescription", module = "inventory"),
        Leaf("module-contacts", "modules", "/settings/modules/contacts", "nav.contacts", "settings.pageSyncContactsDescription", module = "contacts"),
        Leaf("module-health", "modules", "/settings/modules/health", "nav.health", "settings.sheetHealthDescription", module = "health"),
        Leaf("module-budget", "modules", "/settings/modules/budget", "nav.budget", "settings.sheetBudgetDescription", module = "budget"),
        Leaf("modules-install", "modules", "/settings/modules/install", "settings.pageInstallModule", "settings.pageInstallModuleDescription", placementEnd = true),
    )

    val sections = listOf(
        Section("personal-account", "personal-account", MINE, "settings.pageAccount", "settings.pageAccountDescription", false),
        Section("personal-appearance", "personal-appearance", MINE, "settings.pageAppearance", "settings.pageAppearanceDescription", false),
        Section("personal-device", "personal-device", MINE, "settings.pageDevice", "settings.pageDeviceDescription", false),
        Section("device-display", "personal-device", MINE, "settings.sectionDeviceDisplay", "settings.wallModeHint", false),
        Section("personal-notifications", "personal-notifications", MINE, "settings.pageNotifications", "settings.pageNotificationsDescription", false),
        Section("personal-weather", "personal-weather", MINE, "settings.pageWeather", "settings.pageWeatherDescription", false),
        Section(APP_UPDATES, APP_UPDATES, MINE, "android.update.title", "android.update.description", false),
        Section("admin-family", "admin-family", HOUSEHOLD, "settings.pageFamilyRoles", "settings.pageFamilyRolesDescription", true),
        Section("admin-permissions", "admin-permissions", HOUSEHOLD, "settings.pagePermissions", "settings.pagePermissionsDescription", true),
        Section("admin-displays", "admin-displays", HOUSEHOLD, "settings.pageDisplays", "settings.pageDisplaysDescription", true),
        Section("admin-email", "admin-email", HOUSEHOLD, "settings.pageEmail", "settings.pageEmailDescription", true),
        Section("admin-backup", "admin-backup", HOUSEHOLD, "settings.pageBackupRestore", "settings.pageBackupRestoreDescription", true),
        Section("admin-system", "admin-system", HOUSEHOLD, "settings.pageSystem", "settings.pageSystemDescription", true),
        Section("admin-api", "admin-api", HOUSEHOLD, "settings.pageApiAccess", "settings.pageApiAccessDescription", true),
        Section("admin-immich", "admin-integrations", HOUSEHOLD, "settings.pageImmich", "settings.pageImmichDescription", true),
        Section("admin-weather", "admin-integrations", HOUSEHOLD, "settings.pageHouseholdWeather", "settings.pageHouseholdWeatherDescription", true),
        Section("modules-active", "modules-active", HOUSEHOLD, "settings.pageActiveModules", "settings.pageActiveModulesDescription", true),
        Section("modules-navigation", "modules-navigation", MINE, "settings.pageNavigation", "settings.pageNavigationDescription", false),
        Section("modules-install", "modules-install", HOUSEHOLD, "settings.pageInstallModule", "settings.pageInstallModuleDescription", true),
        Section("modules-countdowns", "module-dashboard", HOUSEHOLD, "settings.pageCountdownsModule", "settings.pageCountdownsModuleDescription", true),
        Section("personal-calendar", "module-calendar", MINE, "settings.pageCalendarDefaults", "settings.pageCalendarDefaultsDescription", false),
        Section("personal-calendar-subscriptions", "module-calendar", MINE, "settings.pageCalendarSubscriptions", "settings.pageCalendarSubscriptionsDescription", false),
        Section("personal-feeds", "module-calendar", MINE, "settings.pageFeeds", "settings.pageFeedsDescription", false),
        Section("feed-birthdays", "module-calendar", MINE, "settings.birthdayFeedTitle", "settings.birthdayFeedDescription", false),
        Section("modules-calendar", "module-calendar", HOUSEHOLD, "settings.pageCalendarModule", "settings.pageCalendarModuleDescription", true),
        Section("sync-calendar", "module-calendar-sync", HOUSEHOLD, "settings.pageSyncCalendar", "settings.pageSyncCalendarDescription", true),
        Section("personal-schedule", "module-schedule", MINE, "schedule.mySettings", "settings.pageScheduleMineDescription", false),
        Section("options-schedule", "module-schedule", HOUSEHOLD, "settings.scheduleTemplatesTitle", "settings.scheduleTemplatesHint", true),
        Section("personal-tasks", "module-tasks", MINE, "settings.pageTaskDefaults", "settings.pageTaskDefaultsDescription", false),
        Section("options-tasks", "module-tasks", HOUSEHOLD, "settings.tasksSubtasksExpandedTitle", "settings.tasksSubtasksExpandedHint", true),
        Section("sync-reminders", "module-tasks", HOUSEHOLD, "settings.pageSyncReminders", "settings.pageSyncRemindersDescription", true),
        Section("modules-kitchen", "module-kitchen", HOUSEHOLD, "settings.pageKitchen", "settings.pageKitchenDescription", true),
        Section("sync-contacts", "module-contacts", HOUSEHOLD, "settings.pageSyncContacts", "settings.pageSyncContactsDescription", true),
        Section("options-budget", "module-budget", HOUSEHOLD, "settings.budgetModeTitle", "settings.budgetModeHint", true),
        Section("documents-storage", "module-documents", HOUSEHOLD, "settings.pageDocumentStorage", "settings.pageDocumentStorageDescription", true),
        Section("documents-dms", "module-documents", HOUSEHOLD, "settings.pageDocumentDms", "settings.pageDocumentDmsDescription", true),
        Section("options-housekeeping", "module-housekeeping", HOUSEHOLD, "settings.housekeepingPaymentsTitle", "settings.housekeepingPaymentTasksHint", true),
        Section("modules-rewards", "module-rewards", HOUSEHOLD, "settings.pageRewardsModule", "settings.sheetRewardsDescription", true),
        Section("personal-health", "module-health", MINE, "settings.pageHealthPersonal", "settings.pageHealthPersonalDescription", false),
        Section("options-health", "module-health", HOUSEHOLD, "health.tabs.cycle", "settings.healthCycleHint", true),
        Section("modules-health", "module-health", HOUSEHOLD, "settings.pageHealthModule", "settings.pageHealthModuleDescription", true),
        Section("feed-schedule", "module-schedule", MINE, "settings.scheduleFeedTitle", "settings.scheduleFeedDescription", false),
        Section("feed-cycle", "module-health", MINE, "settings.cycleFeedTitle", "settings.cycleFeedDescription", false),
        Section("feed-inventory", "module-inventory", MINE, "settings.inventoryFeedTitle", "settings.inventoryFeedDescription", false),
        Section("feed-waste", "module-waste", MINE, "settings.wasteFeedTitle", "settings.wasteFeedDescription", false),
    )

    private val leafById = leaves.associateBy { it.id }

    fun leaf(id: String): Leaf? = leafById[id]

    /** The viewer's role and module access, as the shell knows them. */
    data class Viewer(val isAdmin: Boolean, val appUpdates: Boolean = false, val moduleAccess: (String) -> String)

    /** `sectionVisible`: admin-only sections are for admins. */
    fun sectionVisible(section: Section, viewer: Viewer): Boolean = !section.adminOnly || viewer.isAdmin

    /** `settingsSheetSections`: the visible sections of [leaf], "for me" first. */
    fun sectionsOf(leaf: Leaf, viewer: Viewer): List<Section> {
        val own = sections.filter { it.sheetId == leaf.id && sectionVisible(it, viewer) }
        return own.filter { it.scope == MINE } + own.filter { it.scope == HOUSEHOLD }
    }

    /**
     * `sheetModuleAccessible`: a module sheet follows the module's access, the way the
     * navigation does (`canAccessNavModule`: admins and modules without a key always pass).
     */
    fun moduleAccessible(leaf: Leaf, viewer: Viewer): Boolean {
        val module = leaf.module ?: return true
        if (viewer.isAdmin) return true
        val key = PERMISSION_KEYS[module] ?: return true
        return viewer.moduleAccess(key) != "none"
    }

    fun leafVisible(leaf: Leaf, viewer: Viewer): Boolean =
        (!leaf.appOnly || (leaf.id == APP_UPDATES && viewer.appUpdates)) && moduleAccessible(leaf, viewer) && sectionsOf(leaf, viewer).isNotEmpty()

    /**
     * `settingsSheetsForDomain`: general sheets first, then the module sheets in the
     * navigation's order (`sortNavigationItems`: group, then [moduleOrder]), then the
     * `placement: 'end'` ones.
     */
    fun leavesOf(domainId: String, viewer: Viewer, moduleOrder: List<String> = emptyList()): List<Leaf> {
        val visible = leaves.filter { it.domainId == domainId && leafVisible(it, viewer) }
        val end = visible.filter { it.placementEnd }
        val sheets = visible.filterNot { it.placementEnd }
        return sheets.filter { it.module == null } + ModuleOrder.sort(sheets.filter { it.module != null }, moduleOrder) { it.module!! } + end
    }

    /** `filterSettingsDomains`. */
    fun domainsFor(viewer: Viewer): List<Domain> =
        domains.filter { (viewer.isAdmin || !it.adminOnly) && leavesOf(it.id, viewer).isNotEmpty() }

    /** The web app's address of a section, for the parts that stay in the browser (`settingsSectionUrl`). */
    fun webPath(section: Section): String = (leaf(section.sheetId)?.path ?: "/settings") + "?section=" + section.id

    /** Navigation module id to permission key (`navPermissionKey` via `PERMISSION_MODULES.navIds`). */
    val PERMISSION_KEYS = mapOf(
        "calendar" to "calendar", "birthdays" to "calendar", "tasks" to "tasks", "notes" to "notes",
        "contacts" to "contacts", "meals" to "meals", "recipes" to "meals", "shopping" to "shopping",
        "pantry" to "pantry", "inventory" to "inventory", "budget" to "budget", "documents" to "documents",
        "housekeeping" to "housekeeping", "waste" to "waste", "rewards" to "rewards", "health" to "health",
        "schedule" to "schedule",
    )
}

/** `public/settings/module-order.js`: navigation groups and the household's or member's order. */
object ModuleOrder {
    val KITCHEN_CHILDREN = listOf("meals", "recipes", "shopping", "pantry")
    private val PLAN = setOf("calendar", "tasks", "rewards", "notes", "schedule")
    private val HOUSEHOLD = setOf("kitchen", "meals", "recipes", "shopping", "housekeeping", "waste", "documents", "inventory")
    private val PEOPLE = setOf("contacts", "birthdays", "health")
    private val FINANCE = setOf("budget")

    /** `BUILT_IN_MODULES` in their file order, without the kitchen children. */
    val BUILT_IN = listOf(
        "dashboard", "calendar", "tasks", "rewards", "notes", "schedule", "contacts", "birthdays",
        "budget", "documents", "inventory", "housekeeping", "waste", "health", "settings",
    )
    val LOCKED = setOf("dashboard", "settings")
    val NAV_SECTION_LABEL_KEYS = mapOf(
        1 to "nav.sectionPlan", 2 to "nav.sectionHousehold", 3 to "nav.sectionPeople",
        4 to "nav.sectionFinance", 5 to "nav.sectionCustomModules",
    )
    val DEFAULT_MOBILE = listOf("calendar", "tasks", "kitchen")

    /** `moduleSection`. */
    fun section(id: String): Int = when {
        id == "dashboard" -> 0
        id in PLAN -> 1
        id in HOUSEHOLD -> 2
        id in PEOPLE -> 3
        id in FINANCE -> 4
        id.startsWith("third-party-") -> 5
        else -> 2
    }

    /** `normalizeModuleOrder`: kitchen children collapse into one `kitchen`, no duplicates. */
    fun normalize(order: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (id in order) {
            val key = if (id == "kitchen" || id in KITCHEN_CHILDREN) "kitchen" else id
            if (key !in out) out += key
        }
        return out
    }

    /** `sortNavigationItems`: dashboard first, settings last, then group, then [order], then source order. */
    fun <T> sort(items: List<T>, order: List<String>, id: (T) -> String): List<T> {
        val rank = normalize(order).withIndex().associate { it.value to it.index }
        fun orderId(x: String) = if (x in KITCHEN_CHILDREN) "kitchen" else x
        return items.withIndex().sortedWith { a, b ->
            val l = id(a.value)
            val r = id(b.value)
            when {
                l == "dashboard" || r == "dashboard" -> if (l == r) a.index - b.index else if (l == "dashboard") -1 else 1
                l == "settings" || r == "settings" -> if (l == r) a.index - b.index else if (l == "settings") 1 else -1
                section(l) != section(r) -> section(l) - section(r)
                else -> {
                    val lr = rank[orderId(l)] ?: Int.MAX_VALUE
                    val rr = rank[orderId(r)] ?: Int.MAX_VALUE
                    if (lr != rr) lr.compareTo(rr) else a.index - b.index
                }
            }
        }.map { it.value }
    }

    /** `normalizeMobileNavOrder`. */
    fun normalizeMobile(order: List<String>): List<String> =
        normalize(order).filter { it != "dashboard" && it != "settings" && Regex("^[a-z0-9][a-z0-9-]*$").matches(it) }.take(3)

    /** `resolveMobileNavOrder`: the three bar slots, filled from the defaults and then any available module. */
    fun resolveMobile(order: List<String>, available: List<String>): List<String> {
        val pool = normalize(available).filter { it != "dashboard" && it != "settings" }
        val out = mutableListOf<String>()
        for (id in normalizeMobile(order) + DEFAULT_MOBILE + pool) {
            if (id in pool && id !in out) out += id
            if (out.size == 3) break
        }
        return out
    }
}
