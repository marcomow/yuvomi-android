package io.github.marcomow.yuvomi.feature.waste

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Agriculture
import androidx.compose.material.icons.outlined.AllInbox
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.Egg
import androidx.compose.material.icons.outlined.Forest
import androidx.compose.material.icons.outlined.FormatPaint
import androidx.compose.material.icons.outlined.Grass
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.LocalDrink
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.LocalFlorist
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Recycling
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.SmokingRooms
import androidx.compose.material.icons.outlined.SportsBar
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Weekend
import androidx.compose.material.icons.outlined.WineBar
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Waste type glyphs. Upstream stores a Lucide icon name per type; the app draws the closest
 * Material Symbol (docs/UPSTREAM-SYNC.md, "Not automated yet"). [SUGGESTIONS] is the type
 * picker's row (`TYPE_ICON_SUGGESTIONS`); any other stored name keeps its value and draws
 * the bin.
 */
object WasteIcons {
    /** `TYPE_ICON_SUGGESTIONS` of waste.js, in its order. */
    val SUGGESTIONS = listOf(
        "trash2", "trash", "container", "recycle", "box",
        "leaf", "sprout", "apple", "carrot", "egg",
        "newspaper", "archive", "package", "boxes", "shopping-bag",
        "wine", "glass-water", "milk", "beer", "droplet",
        "battery", "cpu", "monitor", "smartphone", "lightbulb",
        "biohazard", "flame", "pill", "paint-bucket", "cigarette",
        "sofa", "construction", "hammer", "truck", "shirt",
        "tree-deciduous", "trees", "flower2", "wheat", "bone",
    )

    private val MAP: Map<String, ImageVector> = mapOf(
        "trash2" to Icons.Outlined.Delete, "trash-2" to Icons.Outlined.Delete, "trash" to Icons.Outlined.DeleteOutline,
        "container" to Icons.Outlined.Inventory2, "recycle" to Icons.Outlined.Recycling, "box" to Icons.Outlined.Inventory2,
        "leaf" to Icons.Outlined.Eco, "sprout" to Icons.Outlined.Grass, "apple" to Icons.Outlined.Restaurant,
        "carrot" to Icons.Outlined.Agriculture, "egg" to Icons.Outlined.Egg,
        "newspaper" to Icons.Outlined.Newspaper, "archive" to Icons.Outlined.Archive, "package" to Icons.Outlined.Inventory2,
        "boxes" to Icons.Outlined.AllInbox, "shopping-bag" to Icons.Outlined.ShoppingBag,
        "wine" to Icons.Outlined.WineBar, "glass-water" to Icons.Outlined.LocalDrink, "milk" to Icons.Outlined.LocalCafe,
        "beer" to Icons.Outlined.SportsBar, "droplet" to Icons.Outlined.WaterDrop,
        "battery" to Icons.Outlined.BatteryFull, "cpu" to Icons.Outlined.Memory, "monitor" to Icons.Outlined.Monitor,
        "smartphone" to Icons.Outlined.Smartphone, "lightbulb" to Icons.Outlined.Lightbulb,
        "biohazard" to Icons.Outlined.Science, "flame" to Icons.Outlined.LocalFireDepartment, "pill" to Icons.Outlined.Medication,
        "paint-bucket" to Icons.Outlined.FormatPaint, "cigarette" to Icons.Outlined.SmokingRooms,
        "sofa" to Icons.Outlined.Weekend, "armchair" to Icons.Outlined.Weekend, "construction" to Icons.Outlined.Construction,
        "hammer" to Icons.Outlined.Handyman, "truck" to Icons.Outlined.LocalShipping, "shirt" to Icons.Outlined.Checkroom,
        "tree-deciduous" to Icons.Outlined.Park, "trees" to Icons.Outlined.Forest, "flower2" to Icons.Outlined.LocalFlorist,
        "flower-2" to Icons.Outlined.LocalFlorist, "wheat" to Icons.Outlined.Grass, "bone" to Icons.Outlined.Pets,
    )

    fun of(name: String?): ImageVector = MAP[name?.trim()?.lowercase()] ?: Icons.Outlined.Delete
}
