package io.github.marcomow.yuvomi.core.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Upstream's modules as the app presents them: name key, web route, tone and
 * permission key (from upstream's `public/router.js` and `permissions.js`).
 * Icons are Material Symbols until the sync converts upstream's own glyphs
 * (docs/UPSTREAM-SYNC.md, "Not automated yet").
 */
data class ModuleInfo(
    val id: String,
    val labelKey: String,
    val route: String,
    val tone: String,
    val permission: String?,
    val icon: ImageVector,
)

object ModuleCatalog {
    val all: List<ModuleInfo> = listOf(
        ModuleInfo("dashboard", "nav.dashboard", "/", "dashboard", null, Icons.Outlined.Dashboard),
        ModuleInfo("tasks", "nav.tasks", "/tasks", "tasks", "tasks", Icons.Outlined.CheckCircle),
        ModuleInfo("calendar", "nav.calendar", "/calendar", "calendar", "calendar", Icons.Outlined.CalendarMonth),
        ModuleInfo("rewards", "nav.rewards", "/rewards", "rewards", "rewards", Icons.Outlined.EmojiEvents),
        ModuleInfo("notes", "nav.notes", "/notes", "notes", "notes", Icons.Outlined.StickyNote2),
        ModuleInfo("contacts", "nav.contacts", "/contacts", "contacts", "contacts", Icons.Outlined.Contacts),
        ModuleInfo("schedule", "nav.schedule", "/schedule", "schedule", "schedule", Icons.Outlined.Schedule),
        ModuleInfo("meals", "nav.meals", "/meals", "meals", "meals", Icons.Outlined.Restaurant),
        ModuleInfo("recipes", "nav.recipes", "/recipes", "recipes", "meals", Icons.Outlined.MenuBook),
        ModuleInfo("shopping", "nav.shopping", "/shopping", "shopping", "shopping", Icons.Outlined.ShoppingCart),
        ModuleInfo("pantry", "nav.pantry", "/pantry", "pantry", "pantry", Icons.Outlined.Kitchen),
        ModuleInfo("housekeeping", "nav.housekeeping", "/housekeeping", "housekeeping", "housekeeping", Icons.Outlined.CleaningServices),
        ModuleInfo("waste", "nav.waste", "/waste", "waste", "waste", Icons.Outlined.DeleteSweep),
        ModuleInfo("documents", "nav.documents", "/documents", "documents", "documents", Icons.Outlined.Description),
        ModuleInfo("inventory", "nav.inventory", "/inventory", "inventory", "inventory", Icons.Outlined.Inventory2),
        ModuleInfo("health", "nav.health", "/health", "health", "health", Icons.Outlined.Favorite),
        ModuleInfo("birthdays", "nav.birthdays", "/birthdays", "birthdays", "calendar", Icons.Outlined.Cake),
        ModuleInfo("budget", "nav.budget", "/budget", "budget", "budget", Icons.Outlined.AccountBalanceWallet),
        ModuleInfo("settings", "nav.settings", "/settings", "settings", null, Icons.Outlined.Settings),
    )

    private val byId = all.associateBy { it.id }

    fun get(id: String): ModuleInfo? = byId[id]

    /** Widget id to module, for the overview's generic tile (OVERVIEW-R10). */
    fun forWidget(widgetId: String): ModuleInfo? = when (widgetId) {
        "countdown", "family", "metrics", "clock", "weather", "quicklinks" -> byId["dashboard"]
        "cycle", "fasting", "nutrition" -> byId["health"]
        else -> byId[widgetId]
    }
}
