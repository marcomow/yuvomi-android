package io.github.marcomow.yuvomi.feature.calendar

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DirectionsBike
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.Healing
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.LocalFlorist
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.LocalLaundryService
import androidx.compose.material.icons.outlined.LocalPizza
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.SportsBar
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material.icons.outlined.Vaccines
import androidx.compose.material.icons.outlined.WbCloudy
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.Weekend
import androidx.compose.material.icons.outlined.WineBar
import androidx.compose.material.icons.outlined.Work
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The event icons of the web form (`EVENT_ICON_CATEGORIES`, public/utils/event-icon.js) with
 * the closest Material glyph for each Lucide name. Names outside the picker that the server
 * still accepts (`VALID_EVENT_ICONS`) fall back to the calendar glyph.
 */
object CalendarIcons {
    data class Category(val labelKey: String, val icons: List<Pair<String, String>>)

    val CATEGORIES = listOf(
        Category("calendar.iconCategoryGeneral", listOf(
            "calendar" to "calendar.iconCalendar", "alarm-clock" to "calendar.iconAlarm", "clock" to "calendar.iconClock",
            "bell" to "calendar.iconBell", "map-pin" to "calendar.iconLocation", "star" to "calendar.iconStar",
            "flag" to "calendar.iconFlag", "target" to "calendar.iconTarget", "flame" to "calendar.iconFlame",
        )),
        Category("calendar.iconCategoryHealth", listOf(
            "tooth" to "calendar.iconTooth", "hospital" to "calendar.iconHospital", "stethoscope" to "calendar.iconDoctor",
            "syringe" to "calendar.iconVaccine", "pill" to "calendar.iconMedicine", "bandage" to "calendar.iconBandage",
            "heart-pulse" to "calendar.iconHealth", "activity" to "calendar.iconActivity", "scissors" to "calendar.iconHaircut",
            "dumbbell" to "calendar.iconSports", "trophy" to "calendar.iconTrophy",
        )),
        Category("calendar.iconCategoryTransport", listOf(
            "car" to "calendar.iconCar", "bus" to "calendar.iconBus", "train" to "calendar.iconTrain", "plane" to "calendar.iconPlane",
            "plane-takeoff" to "calendar.iconFlight", "fuel" to "calendar.iconFuel", "navigation" to "calendar.iconNavigation",
            "bike" to "calendar.iconBike",
        )),
        Category("calendar.iconCategoryWork", listOf(
            "briefcase" to "calendar.iconWork", "laptop" to "calendar.iconLaptop", "presentation" to "calendar.iconPresentation",
            "school" to "calendar.iconSchool", "graduation-cap" to "calendar.iconEducation", "book-open" to "calendar.iconReading",
            "pencil" to "calendar.iconStudy", "calculator" to "calendar.iconCalculator",
        )),
        Category("calendar.iconCategoryFood", listOf(
            "utensils" to "calendar.iconMeal", "cooking-pot" to "calendar.iconCooking", "coffee" to "calendar.iconCoffee",
            "cake" to "calendar.iconCake", "pizza" to "calendar.iconPizza", "wine" to "calendar.iconWine", "beer" to "calendar.iconBeer",
        )),
        Category("calendar.iconCategoryShopping", listOf(
            "shopping-bag" to "calendar.iconShopping", "shopping-cart" to "calendar.iconGroceries", "gift" to "calendar.iconGift",
            "credit-card" to "calendar.iconCard", "wallet" to "calendar.iconWallet", "piggy-bank" to "calendar.iconSavings",
            "landmark" to "calendar.iconBank",
        )),
        Category("calendar.iconCategoryLeisure", listOf(
            "music" to "calendar.iconMusic", "film" to "calendar.iconMovie", "ticket" to "calendar.iconTicket",
            "gamepad-2" to "calendar.iconGame", "camera" to "calendar.iconPhoto", "party-popper" to "calendar.iconParty",
        )),
        Category("calendar.iconCategoryFamily", listOf(
            "users" to "calendar.iconFamily", "baby" to "calendar.iconBaby", "dog" to "calendar.iconDog", "cat" to "calendar.iconCat",
            "paw-print" to "calendar.iconPet",
        )),
        Category("calendar.iconCategoryHome", listOf(
            "home" to "calendar.iconHome", "building" to "calendar.iconBuilding", "wrench" to "calendar.iconRepair",
            "hammer" to "calendar.iconMaintenance", "paintbrush" to "calendar.iconCleaning", "sofa" to "calendar.iconFurniture",
            "washing-machine" to "calendar.iconLaundry",
        )),
        Category("calendar.iconCategoryNature", listOf(
            "leaf" to "calendar.iconLeaf", "tree-pine" to "calendar.iconTree", "flower" to "calendar.iconFlower",
            "sun" to "calendar.iconSun", "moon" to "calendar.iconMoon", "cloud-sun" to "calendar.iconWeather",
        )),
    )

    private val GLYPHS: Map<String, ImageVector> = mapOf(
        "calendar" to Icons.Outlined.CalendarMonth, "alarm-clock" to Icons.Outlined.Alarm, "clock" to Icons.Outlined.Schedule,
        "bell" to Icons.Outlined.Notifications, "map-pin" to Icons.Outlined.Place, "star" to Icons.Outlined.StarOutline,
        "flag" to Icons.Outlined.Flag, "target" to Icons.Outlined.TrackChanges, "flame" to Icons.Outlined.LocalFireDepartment,
        "tooth" to Icons.Outlined.MedicalServices, "hospital" to Icons.Outlined.LocalHospital, "stethoscope" to Icons.Outlined.MedicalServices,
        "syringe" to Icons.Outlined.Vaccines, "pill" to Icons.Outlined.Medication, "tablets" to Icons.Outlined.Medication,
        "bandage" to Icons.Outlined.Healing, "heart-pulse" to Icons.Outlined.MonitorHeart, "activity" to Icons.Outlined.ShowChart,
        "cross" to Icons.Outlined.LocalHospital, "ambulance" to Icons.Outlined.LocalHospital, "scissors" to Icons.Outlined.ContentCut,
        "dumbbell" to Icons.Outlined.FitnessCenter, "trophy" to Icons.Outlined.EmojiEvents, "car" to Icons.Outlined.DirectionsCar,
        "bus" to Icons.Outlined.DirectionsBus, "train" to Icons.Outlined.Train, "tram-front" to Icons.Outlined.Train,
        "plane" to Icons.Outlined.Flight, "plane-takeoff" to Icons.Outlined.FlightTakeoff, "fuel" to Icons.Outlined.LocalGasStation,
        "navigation" to Icons.Outlined.Navigation, "bike" to Icons.Outlined.DirectionsBike, "briefcase" to Icons.Outlined.Work,
        "laptop" to Icons.Outlined.Laptop, "monitor" to Icons.Outlined.Laptop, "presentation" to Icons.Outlined.Slideshow,
        "school" to Icons.Outlined.School, "graduation-cap" to Icons.Outlined.School, "book-open" to Icons.Outlined.MenuBook,
        "library" to Icons.Outlined.MenuBook, "pencil" to Icons.Outlined.Edit, "notebook-pen" to Icons.Outlined.Edit,
        "calculator" to Icons.Outlined.Calculate, "utensils" to Icons.Outlined.Restaurant, "cooking-pot" to Icons.Outlined.Kitchen,
        "coffee" to Icons.Outlined.LocalCafe, "cake" to Icons.Outlined.Cake, "pizza" to Icons.Outlined.LocalPizza,
        "beer" to Icons.Outlined.SportsBar, "wine" to Icons.Outlined.WineBar, "shopping-bag" to Icons.Outlined.ShoppingBag,
        "shopping-cart" to Icons.Outlined.ShoppingCart, "gift" to Icons.Outlined.CardGiftcard, "credit-card" to Icons.Outlined.CreditCard,
        "wallet" to Icons.Outlined.AccountBalanceWallet, "piggy-bank" to Icons.Outlined.Savings, "landmark" to Icons.Outlined.AccountBalance,
        "music" to Icons.Outlined.MusicNote, "guitar" to Icons.Outlined.MusicNote, "film" to Icons.Outlined.Movie,
        "ticket" to Icons.Outlined.ConfirmationNumber, "gamepad-2" to Icons.Outlined.SportsEsports, "camera" to Icons.Outlined.PhotoCamera,
        "party-popper" to Icons.Outlined.Celebration, "balloon" to Icons.Outlined.Celebration, "users" to Icons.Outlined.Group,
        "baby" to Icons.Outlined.ChildCare, "dog" to Icons.Outlined.Pets, "cat" to Icons.Outlined.Pets, "paw-print" to Icons.Outlined.Pets,
        "home" to Icons.Outlined.Home, "house" to Icons.Outlined.Home, "building" to Icons.Outlined.Business,
        "wrench" to Icons.Outlined.Build, "hammer" to Icons.Outlined.Handyman, "paintbrush" to Icons.Outlined.Brush,
        "sofa" to Icons.Outlined.Weekend, "washing-machine" to Icons.Outlined.LocalLaundryService, "refrigerator" to Icons.Outlined.Kitchen,
        "leaf" to Icons.Outlined.Eco, "tree-pine" to Icons.Outlined.Park, "flower" to Icons.Outlined.LocalFlorist,
        "sun" to Icons.Outlined.WbSunny, "moon" to Icons.Outlined.DarkMode, "cloud-sun" to Icons.Outlined.WbCloudy,
    )

    /** `eventIconName()`: `drill` became `tooth`; empty is the calendar. */
    fun normalise(name: String?): String {
        val raw = name?.trim()?.takeIf { it.isNotEmpty() } ?: return "calendar"
        return if (raw == "drill") "tooth" else raw
    }

    fun glyph(name: String?): ImageVector = GLYPHS[normalise(name)] ?: Icons.Outlined.CalendarMonth

    /** `hasEventIcon()`: an icon other than the default calendar is worth showing on a chip. */
    fun shown(name: String?): Boolean = normalise(name) != "calendar"

    fun labelKey(name: String): String? = CATEGORIES.flatMap { it.icons }.firstOrNull { it.first == name }?.second
}
