package io.github.marcomow.yuvomi.feature.tags

import android.net.Uri
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.Placement

/** Tags: everything that shares a tag, across modules (docs/spec/tags.md). */
object TagsFeature : Feature {
    override val id = "tags"
    override val placement = Placement.MORE
    override val startRoute = TagsRoutes.START
    override val routePrefixes = listOf("tags")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(TagsRoutes.START) {
            TagsScreen(openTag = { nav.navigate(TagsRoutes.tag(it)) })
        }
        graph.composable(TagsRoutes.TAG, arguments = listOf(navArgument("tag") { type = NavType.StringType })) { entry ->
            val tag = entry.arguments?.getString("tag")?.takeIf { it.isNotBlank() } ?: return@composable
            TagDetailScreen(
                tag = tag,
                back = { nav.popBackStack() },
                // After a rename the page shows the new name in place of the old one (TAGS-R10).
                retag = { renamed -> nav.navigate(TagsRoutes.tag(renamed)) { popUpTo(TagsRoutes.TAG) { inclusive = true } } },
            )
        }
    }
}

/** Tags' routes. Any module's tag chip opens [tag]. */
object TagsRoutes {
    const val START = "tags"
    const val TAG = "tags/{tag}"

    /** Everything carrying [tag] (with or without `#`). */
    fun tag(tag: String) = "tags/" + Uri.encode(tag.removePrefix("#"))
}
