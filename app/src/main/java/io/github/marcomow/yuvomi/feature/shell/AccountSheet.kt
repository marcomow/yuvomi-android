package io.github.marcomow.yuvomi.feature.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.BuildConfig
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalePicker
import io.github.marcomow.yuvomi.core.i18n.t
import kotlinx.coroutines.launch
import java.util.Locale

/** SIGNIN-R16, SIGNIN-R17. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(onDismiss: () -> Unit) {
    val shell = LocalShell.current
    val user = shell.session.user
    val scope = rememberCoroutineScope()
    var languageDialog by remember { mutableStateOf(false) }
    val chosen by shell.container.settings.language.collectAsState(initial = null)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                MemberAvatar(user.displayName, user.avatarColor, user.avatarData, size = 48.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(user.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(shell.session.server, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ListItem(
                headlineContent = { Text(t("android.account.language")) },
                supportingContent = { Text(chosen?.let(::languageName) ?: t("android.account.automatic")) },
                leadingContent = { Icon(Icons.Outlined.Language, null) },
                modifier = Modifier.clickable { languageDialog = true },
            )
            ListItem(
                headlineContent = { Text(t("android.browser.openWebApp")) },
                leadingContent = { Icon(Icons.Outlined.OpenInBrowser, null) },
                modifier = Modifier.clickable { shell.openWeb("/") },
            )
            UpdateSection(shell.container.updater)
            ListItem(
                headlineContent = { Text(t("android.account.signOut")) },
                leadingContent = { Icon(Icons.AutoMirrored.Outlined.Logout, null, tint = Yuvomi.colors.danger) },
                modifier = Modifier.clickable {
                    onDismiss()
                    scope.launch { shell.container.session.signOut() }
                },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(t("android.signin.unofficial"), style = MaterialTheme.typography.labelMedium)
                Text(
                    t("android.account.versions", "app" to BuildConfig.VERSION_NAME, "server" to (shell.session.serverVersion ?: "?"),
                        "pin" to BuildConfig.UPSTREAM_PIN),
                    style = MaterialTheme.typography.bodySmall,
                    color = Yuvomi.colors.textSecondary,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (languageDialog) {
        AlertDialog(
            onDismissRequest = { languageDialog = false },
            title = { Text(t("android.account.language")) },
            text = {
                LazyColumn {
                    val options = listOf<String?>(null) + LocalePicker.SUPPORTED.sortedBy { languageName(it).lowercase() }
                    items(options) { code ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    languageDialog = false
                                    scope.launch { shell.container.settings.setLanguage(code) }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = chosen == code, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(code?.let(::languageName) ?: t("android.account.automatic"))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { languageDialog = false }) { Text(t("common.close")) } },
        )
    }
}

/** A language in its own name, e.g. "Deutsch", "日本語". */
fun languageName(code: String): String {
    val locale = Locale.forLanguageTag(code)
    return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
}
