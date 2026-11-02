package io.github.marcomow.yuvomi.feature.contacts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.shell.Placement

/** Contacts (docs/spec/contacts.md). */
object ContactsFeature : Feature {
    override val id = "contacts"
    override val placement = Placement.MORE
    override val startRoute = ContactsRoutes.START
    override val routePrefixes = listOf("contacts")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(ContactsRoutes.START) {
            val host = rememberHost()
            val shell = LocalShell.current
            ContactsScreen(
                vm = viewModel(key = "contacts") { ContactsViewModel(shell.container, host) },
                openContact = { nav.navigate(ContactsRoutes.contact(it)) },
                newContact = { nav.navigate(ContactsRoutes.editor(null, it)) },
                manageCategories = { nav.navigate(ContactsRoutes.CATEGORIES) },
            )
        }
        graph.composable(ContactsRoutes.CONTACT, arguments = listOf(navArgument("id") { type = NavType.IntType })) { entry ->
            val id = entry.arguments?.getInt("id") ?: return@composable
            val host = rememberHost()
            val shell = LocalShell.current
            ContactDetailScreen(
                vm = viewModel(key = "contact-$id") { ContactDetailViewModel(shell.container, host, id) },
                back = { nav.popBackStack() },
                edit = { nav.navigate(ContactsRoutes.editor(it)) },
            )
        }
        graph.composable(
            ContactsRoutes.EDITOR,
            arguments = listOf(
                navArgument("id") { type = NavType.IntType; defaultValue = -1 },
                navArgument("category") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            val id = entry.arguments?.getInt("id")?.takeIf { it > 0 }
            val category = entry.arguments?.getString("category")
            val host = rememberHost()
            val shell = LocalShell.current
            ContactEditorScreen(
                vm = viewModel(key = "contact-editor-${id ?: "new"}") { ContactEditorViewModel(shell.container, host, id, category) },
                close = { nav.popBackStack() },
            )
        }
        graph.composable(ContactsRoutes.CATEGORIES) {
            val host = rememberHost()
            val shell = LocalShell.current
            ContactCategoriesScreen(
                vm = viewModel(key = "contact-categories") { ContactCategoriesViewModel(shell.container, host) },
                back = { nav.popBackStack() },
            )
        }
    }
}

@Composable
private fun rememberHost(): ContactsHost {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    return remember(shell, strings) { ShellContactsHost(shell, strings) }
}

/**
 * Contacts's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [ContactsFeature.register].
 */
object ContactsRoutes {
    const val START = "contacts"
    internal const val CONTACT = "contacts/{id}"
    internal const val EDITOR = "contacts-editor?id={id}&category={category}"
    internal const val CATEGORIES = "contacts-categories"

    /** One contact's read view (the web app's `/contacts?open=<id>`). */
    fun contact(id: Int) = "contacts/$id"

    internal fun editor(id: Int?, category: String? = null): String {
        val params = listOfNotNull(id?.let { "id=$it" }, category?.let { "category=" + android.net.Uri.encode(it) })
        return "contacts-editor" + if (params.isEmpty()) "" else params.joinToString("&", prefix = "?")
    }
}
