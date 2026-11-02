package io.github.marcomow.yuvomi.feature.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.reminders.ReminderScheduler
import kotlinx.coroutines.launch

/**
 * REM-R6, R7: reminders as notifications on this phone. App-only, in the Notifications sheet
 * next to upstream's push channels: the switch, whether Android lets the app notify, and
 * whether the server can tell the phone its reminders (2.84.0 and newer).
 */
@Composable
fun PhoneRemindersSection(ctx: SectionContext) {
    val context = LocalContext.current
    val settings = ctx.shell.container.settings
    val on by settings.phoneReminders.collectAsState(initial = true)
    val last by ReminderScheduler.last.collectAsState()
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    val allowed = remember(resumed) { ReminderScheduler.canNotify(context) }
    LaunchedEffect(on, allowed) { ReminderScheduler.refresh(context) }

    Column {
        ListItem(
            headlineContent = { Text(t("android.reminders.phone")) },
            supportingContent = { Text(t("android.reminders.phoneHint"), color = Yuvomi.colors.textSecondary) },
            trailingContent = { Switch(checked = on, onCheckedChange = { v -> ctx.scope.launch { settings.setPhoneReminders(v) } }) },
        )
        if (on && !allowed) {
            ListItem(
                headlineContent = { Text(t("android.reminders.blocked"), color = Yuvomi.colors.danger) },
                trailingContent = {
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }) { Text(t("android.reminders.allow")) }
                },
            )
        }
        if (on && last == ReminderScheduler.Result.UNSUPPORTED) {
            ListItem(headlineContent = { Text(t("android.reminders.unsupported"), color = Yuvomi.colors.textSecondary) })
        }
    }
}
