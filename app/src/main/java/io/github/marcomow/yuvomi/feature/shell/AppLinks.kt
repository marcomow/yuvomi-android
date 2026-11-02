package io.github.marcomow.yuvomi.feature.shell

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.marcomow.yuvomi.MainActivity
import io.github.marcomow.yuvomi.feature.Features

/**
 * Opening the app at a route from outside it: a widget row (WIDGETS-R2, R3) or a reminder's
 * notification (REM-R4). The activity is exported (it is the launcher), so any app could hand
 * it an extra; only the routes [allowed] names are followed.
 */
object AppLinks {
    const val EXTRA_ROUTE = "io.github.marcomow.yuvomi.route"

    private val PATTERNS = listOf(
        Regex("tasks"), Regex("task/[0-9]+"),
        Regex("shopping\\?list=[0-9]+"),
        Regex("calendar\\?open=[0-9]+"),
        Regex("documents\\?open=[0-9]+"),
        Regex("health/(vitals|cycle|fasting|meds|prevention)"),
    )

    /** [route] if the app may open it from outside: a module's start or one of [PATTERNS]. */
    fun allowed(route: String): String? {
        if (Features.all.any { it.startRoute == route }) return route
        return route.takeIf { r -> PATTERNS.any { it.matches(r) } }
    }

    /** An intent that opens the app at [route] (or its start when null). Unique per route, so each tap stays its own. */
    fun open(context: Context, route: String?): Intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        if (route != null) {
            putExtra(EXTRA_ROUTE, route)
            data = Uri.parse("yuvomi-link://open/" + Uri.encode(route))
        }
    }
}
