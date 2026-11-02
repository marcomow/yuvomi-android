package io.github.marcomow.yuvomi.feature.contacts

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.FeaturePlaceholder
import io.github.marcomow.yuvomi.feature.shell.Placement

/** Contacts (docs/spec/contacts.md). */
object ContactsFeature : Feature {
    override val id = "contacts"
    override val placement = Placement.MORE
    override val startRoute = ContactsRoutes.START
    override val routePrefixes = listOf("contacts")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(ContactsRoutes.START) { FeaturePlaceholder(id) }
    }
}

/**
 * Contacts's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [ContactsFeature.register].
 */
object ContactsRoutes {
    const val START = "contacts"
}
