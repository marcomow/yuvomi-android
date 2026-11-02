package io.github.marcomow.yuvomi.feature.shell

import io.github.marcomow.yuvomi.core.api.model.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarSlotsTest {
    private val everything = listOf(
        "tasks", "shopping", "meals", "tags", "recipes", "notes", "pantry", "calendar", "rewards", "schedule",
        "housekeeping", "waste", "documents", "inventory", "contacts", "birthdays", "health", "budget", "settings",
    )

    @Test fun `without a choice the bar is upstream's default`() =
        assertEquals(listOf("calendar", "tasks", "kitchen"), BarSlots.bar(Preferences(), everything))

    @Test fun `the member's three slots win, in their order`() =
        assertEquals(
            listOf("budget", "notes", "health"),
            BarSlots.bar(Preferences(mobileNavOrder = listOf("budget", "notes", "health")), everything),
        )

    @Test fun `a slot the member cannot open is filled from the defaults`() =
        assertEquals(
            listOf("notes", "calendar", "kitchen"),
            BarSlots.bar(Preferences(mobileNavOrder = listOf("notes", "budget")), everything - "budget" - "tasks"),
        )

    @Test fun `a kitchen module in the saved order means the kitchen slot`() =
        assertEquals(listOf("kitchen", "tasks", "calendar"), BarSlots.bar(Preferences(mobileNavOrder = listOf("shopping", "tasks")), everything))

    @Test fun `app-only modules and settings never sit in the bar`() {
        val bar = BarSlots.bar(Preferences(mobileNavOrder = listOf("tags", "settings")), everything)
        assertFalse("tags" in bar || "settings" in bar)
    }

    @Test fun `more lists the rest without hidden modules, settings last`() {
        val prefs = Preferences(hiddenModules = listOf("waste"))
        val more = BarSlots.more(prefs, everything, BarSlots.bar(prefs, everything))
        assertFalse("calendar" in more || "tasks" in more || "waste" in more)
        assertTrue("shopping" in more && "meals" in more)
        assertEquals("settings", more.last())
    }

    @Test fun `more follows the member's order within a group`() {
        val prefs = Preferences(moduleOrder = listOf("contacts", "birthdays", "health"), mobileNavOrder = listOf("tasks", "calendar", "budget"))
        val more = BarSlots.more(prefs, everything, BarSlots.bar(prefs, everything))
        assertTrue(more.indexOf("contacts") < more.indexOf("health"))
        val prefs2 = prefs.copy(moduleOrder = listOf("health", "birthdays", "contacts"))
        val more2 = BarSlots.more(prefs2, everything, BarSlots.bar(prefs2, everything))
        assertTrue(more2.indexOf("health") < more2.indexOf("contacts"))
    }

    @Test fun `the kitchen slot opens the last kitchen module, else the first one shown`() {
        assertEquals("pantry", BarSlots.kitchenTarget("pantry", everything))
        assertEquals("meals", BarSlots.kitchenTarget(null, everything))
        assertEquals("recipes", BarSlots.kitchenTarget("pantry", everything - "meals" - "pantry"))
        assertEquals(null, BarSlots.kitchenTarget(null, listOf("tasks")))
    }
}
