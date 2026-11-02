package io.github.marcomow.yuvomi.feature.shell

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.i18n.Formats
import io.github.marcomow.yuvomi.core.session.Connection
import io.github.marcomow.yuvomi.core.session.Session
import io.github.marcomow.yuvomi.core.time.HouseholdTime

/** What every signed-in screen gets from the shell. */
class ShellContext(
    val container: AppContainer,
    val session: Session,
    val connection: Connection,
    val time: HouseholdTime,
    val formats: Formats,
    val snackbar: SnackbarHostState,
    val openAccount: () -> Unit,
    val openWeb: (path: String) -> Unit,
    /** Opens a native route, for links between modules (for example a meal to its recipe). */
    val navigate: (route: String) -> Unit = {},
) {
    /** Whether the member sees module [id]: not switched off for the household, and some access (ADR 0006). */
    /**
     * The module ids the member can open natively: Tasks, then every native module [shows]
     * allows (kitchen modules individually). The bar and "More" are drawn from this.
     */
    fun shownModules(): List<String> =
        listOfNotNull("tasks".takeIf { session.access("tasks") != "none" }) +
            io.github.marcomow.yuvomi.feature.Features.all.map { it.id }.filter(::shows)

    fun shows(id: String): Boolean {
        val module = io.github.marcomow.yuvomi.core.design.ModuleCatalog.get(id) ?: return false
        return id !in session.preferences.disabledModules &&
            (module.permission == null || session.access(module.permission) != "none")
    }

    /** Shows [message]; returns true when the user tapped [action]. */
    suspend fun notify(message: String, action: String? = null, long: Boolean = false): Boolean {
        snackbar.currentSnackbarData?.dismiss()
        val result = snackbar.showSnackbar(
            message,
            actionLabel = action,
            withDismissAction = action == null,
            duration = if (action != null || long) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        return result == SnackbarResult.ActionPerformed
    }
}

val LocalShell = staticCompositionLocalOf<ShellContext> { error("not inside the signed-in shell") }
