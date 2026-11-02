package io.github.marcomow.yuvomi.feature.birthdays

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.feature.contacts.ContactsRoutes
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.shell.Placement
import io.github.marcomow.yuvomi.feature.shell.ShellContext

/** Birthdays (docs/spec/birthdays.md). */
object BirthdaysFeature : Feature {
    override val id = "birthdays"
    override val placement = Placement.MORE
    override val startRoute = BirthdaysRoutes.START
    override val routePrefixes = listOf("birthdays")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        // One destination answers both `birthdays` (START) and `birthdays?open=<id>`.
        graph.composable(
            BirthdaysRoutes.LIST,
            arguments = listOf(navArgument("open") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            val shell = LocalShell.current
            val host = birthdaysHost()
            val format = remember(shell) { IcuBirthdaysFormat(host.strings.locale) { shell.time.today() } }
            BirthdaysScreen(
                vm = birthdaysViewModel(entry, host),
                host = host,
                format = format,
                openEditor = { id -> nav.navigate(BirthdaysRoutes.editor(id)) },
                openContact = if (shell.shows("contacts")) ({ _ -> shell.navigate(ContactsRoutes.START) }) else null,
                initialOpen = entry.arguments?.getString("open")?.toIntOrNull(),
            )
        }
        graph.composable(
            BirthdaysRoutes.EDITOR,
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            val shell = LocalShell.current
            val host = birthdaysHost()
            val listEntry = remember(entry) { nav.previousBackStackEntry?.takeIf { it.destination.route == BirthdaysRoutes.LIST } }
                ?: return@composable
            val list = birthdaysViewModel(listEntry, host)
            val id = entry.arguments?.getString("id")?.toIntOrNull()
            val birthday = remember(id) { id?.let(list::find) }
            val editor = viewModel(viewModelStoreOwner = entry, key = "birthday-editor") {
                BirthdayEditorViewModel(shell.container, host, birthday) { shell.time.today() }
            }
            BirthdayEditorScreen(
                vm = editor,
                format = remember(shell) { IcuBirthdaysFormat(host.strings.locale) { shell.time.today() } },
                close = { nav.popBackStack() },
                saved = list::saved,
                gone = list::gone,
                delete = list::delete,
            )
        }
    }
}

@Composable
private fun birthdaysHost(): BirthdaysHost {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    return remember(shell, strings) { ShellBirthdaysHost(shell, strings) }
}

/** The list's view model, owned by the list's back stack entry and shared with the editor. */
@Composable
private fun birthdaysViewModel(owner: NavBackStackEntry, host: BirthdaysHost): BirthdaysViewModel {
    val shell = LocalShell.current
    return viewModel(viewModelStoreOwner = owner, key = "birthdays") { BirthdaysViewModel(shell.container, host) }
}

/** [BirthdaysHost] on the signed-in shell. */
internal class ShellBirthdaysHost(private val shell: ShellContext, override val strings: Strings) : BirthdaysHost {
    // The module's right is `calendar` (ModuleCatalog; `readOnly()` in birthdays.js).
    override val readOnly: Boolean get() = shell.session.access("calendar") != "write"
    override val contactsVisible: Boolean get() = shell.shows("contacts")

    override suspend fun notify(message: String, action: String?): Boolean = shell.notify(message, action)
}

/**
 * Birthdays's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [BirthdaysFeature.register].
 */
object BirthdaysRoutes {
    const val START = "birthdays"
    internal const val LIST = "birthdays?open={open}"
    internal const val EDITOR = "birthdays/edit?id={id}"

    /** The list with one birthday's sheet open (`/birthdays?open=<id>`). */
    fun birthday(id: Int) = "birthdays?open=$id"

    internal fun editor(id: Int?) = "birthdays/edit" + (id?.let { "?id=$it" } ?: "")
}
