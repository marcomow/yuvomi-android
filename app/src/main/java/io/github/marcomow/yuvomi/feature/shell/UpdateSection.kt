package io.github.marcomow.yuvomi.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.core.update.Updater
import io.github.marcomow.yuvomi.core.update.Updater.State
import kotlinx.coroutines.launch

/** The account sheet's "App updates" block (ADR 0018). Nothing in builds that cannot update. */
@Composable
fun UpdateSection(updater: Updater) {
    if (!updater.enabled) return
    val state by updater.state.collectAsState()
    val automatic by updater.automatic.collectAsState(initial = true)
    val scope = rememberCoroutineScope()
    val check = { scope.launch { updater.check() }; Unit }

    ListItem(
        headlineContent = { Text(t("android.update.title")) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(statusText(state), color = if (state is State.Failed) Yuvomi.colors.danger else Yuvomi.colors.textSecondary)
                (state as? State.Downloading)?.let { d ->
                    if (d.progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        leadingContent = { Icon(Icons.Outlined.SystemUpdate, null) },
        trailingContent = {
            when (val s = state) {
                State.Checking -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                is State.Downloading -> Unit
                is State.Available -> Button(onClick = { scope.launch { updater.install(s.update) } }) { Text(t("android.update.install")) }
                is State.Installing -> TextButton(onClick = { scope.launch { updater.install(s.update) } }) { Text(t("android.update.install")) }
                is State.NeedsPermission -> Unit
                is State.Failed -> TextButton(onClick = {
                    val update = s.update
                    if (update != null) scope.launch { updater.install(update) } else check()
                }) { Text(t("android.update.retry")) }
                State.Idle, is State.UpToDate -> TextButton(onClick = check) { Text(t("android.update.check")) }
            }
        },
    )
    (state as? State.NeedsPermission)?.let { s ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = updater::allowInstalls) { Text(t("android.update.allow")) }
            Button(onClick = { scope.launch { updater.install(s.update) } }) { Text(t("android.update.install")) }
        }
    }
    ListItem(
        headlineContent = { Text(t("android.update.automatic")) },
        supportingContent = { Text(t("android.update.automaticHint"), color = Yuvomi.colors.textSecondary) },
        trailingContent = {
            Switch(checked = automatic, onCheckedChange = { on -> scope.launch { updater.setAutomatic(on) } })
        },
        modifier = Modifier.padding(start = 40.dp),
    )
}

@Composable
private fun statusText(state: State): String = when (state) {
    State.Idle -> t("android.update.idle")
    State.Checking -> t("android.update.checking")
    is State.UpToDate -> t("android.update.upToDate", "version" to state.version)
    is State.Available -> t("android.update.available", "version" to state.update.version)
    is State.Downloading -> t("android.update.downloading", "version" to state.update.version)
    is State.NeedsPermission -> t("android.update.needsPermission")
    is State.Installing -> t("android.update.installing")
    is State.Failed -> t(state.messageKey)
}
