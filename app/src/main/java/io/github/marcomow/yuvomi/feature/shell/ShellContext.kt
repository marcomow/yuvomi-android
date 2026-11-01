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
) {
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
