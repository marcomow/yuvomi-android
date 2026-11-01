package io.github.marcomow.yuvomi.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.BuildConfig
import io.github.marcomow.yuvomi.core.browser.Browser
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.core.session.Session
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.Close

/** SIGNIN-R14: the server is older than the app supports. */
@Composable
fun TooOldScreen(container: AppContainer, session: Session) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            t("android.version.tooOld", "server" to (session.serverVersion ?: "?"), "min" to BuildConfig.UPSTREAM_MIN),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Button(onClick = { Browser.open(context, session.server) }, modifier = Modifier.padding(top = 24.dp).fillMaxWidth()) {
            Text(t("android.browser.open"))
        }
        OutlinedButton(onClick = { scope.launch { container.session.signOut() } }, modifier = Modifier.fillMaxWidth()) {
            Text(t("android.account.signOut"))
        }
    }
}

/** SIGNIN-R14: shown once per server version newer than the pin. */
@Composable
fun NewerServerNotice(onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, null)
            Text(t("android.version.newer"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(12.dp))
            IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, t("common.close")) }
        }
    }
}

/** The member's avatar in every top bar; opens the account sheet (SIGNIN-R16). */
@Composable
fun AccountButton() {
    val shell = LocalShell.current
    val user = shell.session.user
    IconButton(onClick = shell.openAccount) {
        MemberAvatar(user.displayName, user.avatarColor, user.avatarData, size = 32.dp)
    }
}
