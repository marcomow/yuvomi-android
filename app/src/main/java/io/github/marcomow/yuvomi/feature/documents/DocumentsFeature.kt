package io.github.marcomow.yuvomi.feature.documents

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
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.shell.Placement
import io.github.marcomow.yuvomi.feature.shell.ShellContext
import java.time.LocalDate

/** Documents (docs/spec/documents.md). */
object DocumentsFeature : Feature {
    override val id = "documents"
    override val placement = Placement.MORE
    override val startRoute = DocumentsRoutes.START
    override val routePrefixes = listOf("documents")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        // One destination answers both `documents` (START) and `documents?open=<id>` (DOCS-R14).
        graph.composable(
            DocumentsRoutes.LIST,
            arguments = listOf(navArgument("open") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            DocumentsScreen(vm = documentsViewModel(entry, entry.arguments?.getString("open")?.toIntOrNull()))
        }
    }
}

/** The page's state, owned by its back stack entry; a new sign-in starts afresh. */
@Composable
internal fun documentsViewModel(owner: NavBackStackEntry, openId: Int?): DocumentsViewModel {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val host = remember(shell, strings) { ShellDocumentsHost(shell, strings) }
    return viewModel(viewModelStoreOwner = owner, key = "documents-${shell.session.server}-${shell.session.user.id}") {
        DocumentsViewModel(shell.container, host, openId)
    }
}

/** [DocumentsHost] on the signed-in shell. */
internal class ShellDocumentsHost(private val shell: ShellContext, override val strings: Strings) : DocumentsHost {
    override val userId: Int get() = shell.session.user.id
    override val isAdmin: Boolean get() = shell.session.isAdmin
    override val canWrite: Boolean get() = shell.session.access("documents") == "write"

    override fun today(): LocalDate = shell.time.today()

    override suspend fun notify(message: String, action: String?): Boolean = shell.notify(message, action)
}

/**
 * Documents's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [DocumentsFeature.register].
 */
object DocumentsRoutes {
    const val START = "documents"
    internal const val LIST = "documents?open={open}"

    /** A document's detail (the web app's `/documents?open=<id>`). */
    fun document(id: Int) = "documents?open=$id"
}
