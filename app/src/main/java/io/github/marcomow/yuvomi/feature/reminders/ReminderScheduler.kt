package io.github.marcomow.yuvomi.feature.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.marcomow.yuvomi.YuvomiApp
import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.decode
import io.github.marcomow.yuvomi.core.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Reminders on the phone (docs/spec/reminders.md, ADR 0021). Reads the member's upcoming
 * reminders (`GET /reminders/upcoming`, upstream 2.84.0 and newer) and sets an alarm for each,
 * which posts the notification when it is due. The schedule is rebuilt from the answer every
 * time, so a reminder that was removed, moved or whose task was completed is dropped or moved.
 */
object ReminderScheduler {
    const val CHANNEL = "reminders"
    private const val WORK = "reminders-refresh"
    private val lock = Mutex()

    private fun app(context: Context) = (context.applicationContext as YuvomiApp).container
    private fun file(context: Context) = File(context.filesDir, "reminders/scheduled.json")

    /** Whether the member wants them (Settings, Notifications, REM-R6), on by default. */
    suspend fun enabled(context: Context): Boolean = app(context).settings.phoneRemindersNow()

    /** Whether Android lets the app show notifications (Android 13 asks, REM-R7). */
    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** REM-R1: the periodic refresh, every 6 hours with a connection; idempotent. */
    fun start(context: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * REM-R2: read the next [PhoneReminders.WINDOW_DAYS] days and schedule exactly those. Signed
     * out or switched off: everything is cancelled. A server without the endpoint (older than
     * 2.84.0) leaves nothing scheduled; a network failure keeps what is scheduled.
     */
    suspend fun refresh(context: Context): Result = lock.withLock {
        val c = app(context)
        val strings = c.strings.filterNotNull().first()
        withContext(Dispatchers.Main) { c.session.start() }
        val state = withTimeoutOrNull(20_000) { c.session.state.first { it !is SessionState.Starting } }
        val conn = c.session.connection
        if (state !is SessionState.SignedIn || conn == null || !enabled(context)) {
            replace(context, emptyList())
            return@withLock Result.OFF
        }
        val now = System.currentTimeMillis()
        val from = Instant.ofEpochMilli(now).truncatedTo(ChronoUnit.SECONDS)
        val to = from.plus(PhoneReminders.WINDOW_DAYS, ChronoUnit.DAYS)
        val rows = try {
            conn.client.call(RemindersOperation.UPCOMING, query = listOf("from" to from.toString(), "to" to to.toString()))
                .decode(UpcomingAnswer.serializer()).data
        } catch (e: ApiProblem.Http) {
            if (e.operationUnsupported) {
                replace(context, emptyList())
                return@withLock Result.UNSUPPORTED
            }
            return@withLock Result.FAILED
        } catch (e: ApiProblem) {
            return@withLock Result.FAILED
        }
        replace(context, PhoneReminders.plan(rows, now, strings))
        Result.OK
    }.also { last.value = it }

    enum class Result { OK, OFF, UNSUPPORTED, FAILED }

    /** The last refresh's outcome, for the settings section (REM-R6). */
    val last = kotlinx.coroutines.flow.MutableStateFlow<Result?>(null)

    private var watching = false

    /**
     * REM-R1: refresh now, when the app goes to the background (changes made in the app reach
     * the schedule) and when the member signs in or out. Once per process.
     */
    fun watch(app: android.app.Application, container: io.github.marcomow.yuvomi.AppContainer) {
        if (watching) return
        watching = true
        start(app)
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) container.scope.launch { runCatching { refresh(app) } }
            },
        )
        container.scope.launch {
            container.session.state
                .filter { it !is SessionState.Starting }
                .map { it is SessionState.SignedIn }
                .distinctUntilChanged()
                .collect { runCatching { refresh(app) } }
        }
    }

    /** What is scheduled now (REM-R2, after a reboot REM-R5). */
    fun scheduled(context: Context): List<Scheduled> =
        runCatching { ApiJson.decodeFromString(ListSerializer(Scheduled.serializer()), file(context).readText()) }.getOrDefault(emptyList())

    private fun replace(context: Context, next: List<Scheduled>) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        for (old in scheduled(context)) if (next.none { it.id == old.id }) alarms.cancel(alarm(context, old))
        for (s in next) set(context, alarms, s)
        file(context).parentFile?.mkdirs()
        file(context).writeText(ApiJson.encodeToString(ListSerializer(Scheduled.serializer()), next))
    }

    /** REM-R5: after a reboot Android has dropped every alarm; set the saved ones again. */
    fun restore(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        scheduled(context).filter { it.at > now }.forEach { set(context, alarms, it) }
    }

    /** Sign-out (REM-R6): nothing of the household stays scheduled. */
    fun clear(context: Context) = replace(context, emptyList())

    private fun set(context: Context, alarms: AlarmManager, s: Scheduled) {
        val pi = alarm(context, s)
        // Exact when Android allows it; otherwise within a few minutes, also in Doze.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms()) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.at, pi)
        } else {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.at, pi)
        }
    }

    private fun alarm(context: Context, s: Scheduled): PendingIntent =
        PendingIntent.getBroadcast(
            context, s.id,
            Intent(context, ReminderAlarmReceiver::class.java).putExtra(ReminderAlarmReceiver.EXTRA_ID, s.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** The notification channel, named in the member's language. */
    fun channel(context: Context, name: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_HIGH))
    }

    class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): androidx.work.ListenableWorker.Result =
            if (refresh(applicationContext) == ReminderScheduler.Result.FAILED) androidx.work.ListenableWorker.Result.retry()
            else androidx.work.ListenableWorker.Result.success()
    }
}
