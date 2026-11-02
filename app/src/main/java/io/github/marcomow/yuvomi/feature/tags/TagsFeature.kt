package io.github.marcomow.yuvomi.feature.tags

import android.net.Uri
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.FeaturePlaceholder
import io.github.marcomow.yuvomi.feature.shell.Placement

/** Tags: everything that shares a tag, across modules (docs/spec/tags.md). */
object TagsFeature : Feature {
    override val id = "tags"
    override val placement = Placement.MORE
    override val startRoute = TagsRoutes.START
    override val routePrefixes = listOf("tags")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(TagsRoutes.START) { FeaturePlaceholder(id) }
        graph.composable(TagsRoutes.TAG) { FeaturePlaceholder(id) }
    }
}

/** Tags' routes. Any module's tag chip opens [tag]. */
object TagsRoutes {
    const val START = "tags"
    const val TAG = "tags/{tag}"

    /** Everything carrying [tag] (with or without `#`). */
    fun tag(tag: String) = "tags/" + Uri.encode(tag.removePrefix("#"))
}
