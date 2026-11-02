package io.github.marcomow.yuvomi.feature.reminders

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.marcomow.yuvomi.R
import io.github.marcomow.yuvomi.YuvomiApp
import io.github.marcomow.yuvomi.feature.shell.AppLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** REM-R3: a reminder is due; post its notification with what was scheduled for it. */
class ReminderAlarmReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission") // checked by canNotify
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val s = ReminderScheduler.scheduled(context).firstOrNull { it.id == id } ?: return
        if (!ReminderScheduler.canNotify(context)) return
        val strings = (context.applicationContext as YuvomiApp).container.strings.value
        ReminderScheduler.channel(context, strings?.t("nav.reminders") ?: "Reminders")
        val open = PendingIntent.getActivity(
            context, id, AppLinks.open(context, s.route), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismiss = PendingIntent.getBroadcast(
            context, id, Intent(context, ReminderDismissReceiver::class.java).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(s.title)
            .setContentText(s.body.ifEmpty { null })
            .setStyle(NotificationCompat.BigTextStyle().bigText(s.body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(s.at)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, strings?.t("reminders.dismiss") ?: "Dismiss", dismiss)
            .build()
        NotificationManagerCompat.from(context).notify(TAG, id, notification)
    }

    companion object {
        const val EXTRA_ID = "io.github.marcomow.yuvomi.reminder"
        const val TAG = "reminder"
    }
}

/** REM-R3: "Dismiss" on the notification dismisses the reminder on the server too, as the web app's toast does. */
class ReminderDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(ReminderAlarmReceiver.EXTRA_ID, -1)
        if (id < 0) return
        NotificationManagerCompat.from(context).cancel(ReminderAlarmReceiver.TAG, id)
        val pending = goAsync()
        receiverScope.launch {
            try {
                val c = (context.applicationContext as YuvomiApp).container
                runCatching {
                    kotlinx.coroutines.withContext(Dispatchers.Main) { c.session.start() }
                    c.session.connection?.client?.call(RemindersOperation.DISMISS, mapOf("id" to id))
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** REM-R5: alarms do not survive a reboot or an app update; set them again and refresh. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        ReminderScheduler.restore(context)
        ReminderScheduler.start(context)
    }
}
