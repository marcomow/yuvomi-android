package io.github.marcomow.yuvomi.core.update

import io.github.marcomow.yuvomi.core.api.ApiJson
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.marcomow.yuvomi.BuildConfig
import io.github.marcomow.yuvomi.core.session.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * In-app updates (ADR 0018): asks GitHub for this app's newest release, downloads its APK,
 * checks the download against the digest GitHub publishes, and hands it to Android's package
 * installer. Android installs it only when it is signed with the same key as the installed app,
 * so a tampered or foreign APK cannot replace this one.
 *
 * This is the only traffic that does not go to the household's server. It uses its own HTTP
 * client, without the session's cookies, and runs automatically only while the member keeps
 * "Check automatically" on.
 */
class Updater(
    private val context: Context,
    private val settings: SettingsStore,
    private val http: OkHttpClient = OkHttpClient(),
    private val installed: String = BuildConfig.VERSION_NAME,
    private val repo: String = BuildConfig.UPDATE_REPO,
    /** Release builds only: a debug build is signed with another key and could not be updated. */
    val enabled: Boolean = BuildConfig.UPDATES_ENABLED,
    private val now: () -> Long = System::currentTimeMillis,
    private val apiBase: String = "https://api.github.com",
) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val version: String) : State
        data class Available(val update: Releases.Update) : State
        data class Downloading(val update: Releases.Update, val progress: Float?) : State

        /** Android asks the member to allow installs from this app first (Android 8+). */
        data class NeedsPermission(val update: Releases.Update) : State
        data class Installing(val update: Releases.Update) : State

        /** [messageKey] is an `android.update.*` string. */
        data class Failed(val messageKey: String, val update: Releases.Update?) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    val automatic: Flow<Boolean> = settings.autoUpdates
    private val lock = Mutex()

    private val dir get() = File(context.cacheDir, DIR)

    suspend fun setAutomatic(on: Boolean) = settings.setAutoUpdates(on)

    /** The automatic check: at most every [INTERVAL_MS], and only while the member wants it. */
    suspend fun checkIfDue(): Releases.Update? {
        if (!enabled || !settings.autoUpdatesNow()) return null
        if (now() - settings.lastUpdateCheck() < INTERVAL_MS) return restore()
        return check()
    }

    /**
     * Between checks, the update the last one found (ADR 0018): a restarted app still offers it
     * without asking GitHub again. Dropped once the installed version has caught up.
     */
    private suspend fun restore(): Releases.Update? {
        (state.value as? State.Available)?.let { return it.update }
        if (state.value != State.Idle) return null
        val saved = settings.knownUpdate()?.let { runCatching { ApiJson.decodeFromString(Releases.Update.serializer(), it) }.getOrNull() }
        if (saved == null || !Releases.isNewer(saved, installed)) return null
        _state.value = State.Available(saved)
        return saved
    }

    /** The banner (ADR 0018): the member closed it for [version]. */
    val dismissed: Flow<String?> = settings.dismissedUpdate
    suspend fun dismiss(version: String) = settings.setDismissedUpdate(version)

    /** Asks GitHub now; the result is also in [state]. Network failures leave a [State.Failed]. */
    suspend fun check(): Releases.Update? = lock.withLock {
        if (!enabled) return null
        _state.value = State.Checking
        val result = runCatching { fetch() }
        settings.setLastUpdateCheck(now())
        val update = result.getOrNull()
        if (result.isSuccess) settings.setKnownUpdate(update?.let { ApiJson.encodeToString(Releases.Update.serializer(), it) })
        _state.value = when {
            result.isFailure -> State.Failed("android.update.checkFailed", null)
            update == null -> State.UpToDate(installed)
            else -> State.Available(update)
        }
        withContext(Dispatchers.IO) { dropOldDownloads() }
        update
    }

    /**
     * Downloads [update] (or reuses a finished download), verifies it, and opens Android's
     * installer. Without the "install unknown apps" permission it stops at [State.NeedsPermission];
     * [allowInstalls] opens that setting, and calling this again continues.
     */
    suspend fun install(update: Releases.Update) = lock.withLock {
        if (!canInstall()) {
            _state.value = State.NeedsPermission(update)
            return@withLock
        }
        val apk = try {
            download(update)
        } catch (e: ChecksumMismatch) {
            _state.value = State.Failed("android.update.corrupt", update)
            return@withLock
        } catch (e: IOException) {
            _state.value = State.Failed("android.update.downloadFailed", update)
            return@withLock
        }
        _state.value = State.Installing(update)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            _state.value = State.Failed("android.update.noInstaller", update)
        }
    }

    /** Opens the system setting that lets this app install updates. */
    fun allowInstalls() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    private suspend fun fetch(): Releases.Update? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$apiBase/repos/$repo/releases?per_page=$PAGE")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "yuvomi-android/$installed")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            Releases.newest(Releases.parse(response.body.string()), installed)
        }
    }

    private suspend fun download(update: Releases.Update): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val target = File(dir, "yuvomi-android-${update.version}.apk")
        if (target.exists() && (update.sha256 == null || sha256(target) == update.sha256)) return@withContext target
        val part = File(dir, target.name + ".part")
        _state.value = State.Downloading(update, null)
        val request = Request.Builder().url(update.apkUrl).header("User-Agent", "yuvomi-android/$installed").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val total = response.body.contentLength().takeIf { it > 0 } ?: update.size.takeIf { it > 0 }
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            response.body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        if (total != null) _state.value = State.Downloading(update, (read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (update.sha256 != null && actual != update.sha256) {
                part.delete()
                throw ChecksumMismatch()
            }
        }
        target.delete()
        if (!part.renameTo(target)) throw IOException("rename failed")
        target
    }

    /** Downloads of this version or older are of no further use. */
    private fun dropOldDownloads() {
        val current = Releases.Version.parse(installed) ?: return
        dir.listFiles()?.forEach { f ->
            val v = Releases.Version.parse(f.name.removePrefix("yuvomi-android-").removeSuffix(".part").removeSuffix(".apk"))
            if (v == null || v <= current) f.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class ChecksumMismatch : IOException("checksum mismatch")

    companion object {
        const val DIR = "updates"
        const val APK_TYPE = "application/vnd.android.package-archive"
        const val INTERVAL_MS = 12 * 60 * 60 * 1000L
        private const val PAGE = 10
    }
}
