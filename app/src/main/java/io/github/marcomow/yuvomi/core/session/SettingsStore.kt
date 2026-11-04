package io.github.marcomow.yuvomi.core.session

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "yuvomi")

/**
 * Per-device settings: last server and username (SIGNIN-R4), the encrypted
 * session cookie (ADR 0004), language (SIGNIN-R17), task list grouping
 * (TASKS-R4) and one-time notices.
 */
class SettingsStore(private val context: Context, private val box: SecretBox) {

    private object Keys {
        val server = stringPreferencesKey("server")
        val username = stringPreferencesKey("username")
        val cookies = stringPreferencesKey("cookies")
        val language = stringPreferencesKey("language")
        val grouping = stringPreferencesKey("taskGrouping")
        val collapsed = stringSetPreferencesKey("taskCollapsed")
        val notices = stringSetPreferencesKey("notices")
        val autoUpdates = booleanPreferencesKey("autoUpdates")
        val lastUpdateCheck = longPreferencesKey("lastUpdateCheck")
        val knownUpdate = stringPreferencesKey("knownUpdate")
        val phoneReminders = booleanPreferencesKey("phoneReminders")
        val tagsBarSlot = intPreferencesKey("tagsBarSlot")
        val tagsShowDone = booleanPreferencesKey("tagsShowDone")
        val dismissedUpdate = stringPreferencesKey("dismissedUpdate")
    }

    suspend fun server(): String? = read(Keys.server)
    suspend fun username(): String? = read(Keys.username)
    suspend fun setServerAndUser(server: String, username: String?) = context.dataStore.edit {
        it[Keys.server] = server
        if (username != null) it[Keys.username] = username
    }

    suspend fun cookies(): String? = read(Keys.cookies)?.let(box::open)
    suspend fun setCookies(serialized: String) = context.dataStore.edit { it[Keys.cookies] = box.seal(serialized) }
    suspend fun clearCookies() = context.dataStore.edit { it.remove(Keys.cookies) }

    /** `null` means "Automatic" (device locale). */
    val language: Flow<String?> = context.dataStore.data.map { it[Keys.language] }
    suspend fun setLanguage(code: String?) = context.dataStore.edit {
        if (code == null) it.remove(Keys.language) else it[Keys.language] = code
    }

    /** In-app updates (ADR 0018): check GitHub for a newer release automatically. On by default. */
    val autoUpdates: Flow<Boolean> = context.dataStore.data.map { it[Keys.autoUpdates] ?: true }
    suspend fun autoUpdatesNow(): Boolean = autoUpdates.first()
    suspend fun setAutoUpdates(on: Boolean) = context.dataStore.edit { it[Keys.autoUpdates] = on }
    suspend fun lastUpdateCheck(): Long = context.dataStore.data.first()[Keys.lastUpdateCheck] ?: 0L
    suspend fun setLastUpdateCheck(at: Long) = context.dataStore.edit { it[Keys.lastUpdateCheck] = at }

    /** The newest update the last check found (JSON of `Releases.Update`), so the banner survives a restart. */
    suspend fun knownUpdate(): String? = read(Keys.knownUpdate)
    suspend fun setKnownUpdate(json: String?) = context.dataStore.edit { if (json == null) it.remove(Keys.knownUpdate) else it[Keys.knownUpdate] = json }

    /**
     * The bar slot (0 to 2) that shows Tags instead of the module saved there, or null. Kept on
     * the phone: upstream's `mobile_nav_order` accepts only its own modules and drops `tags`.
     */
    val tagsBarSlot: Flow<Int?> = context.dataStore.data.map { it[Keys.tagsBarSlot]?.takeIf { s -> s in 0..2 } }
    suspend fun setTagsBarSlot(slot: Int?) = context.dataStore.edit { if (slot == null) it.remove(Keys.tagsBarSlot) else it[Keys.tagsBarSlot] = slot }

    /** The Tags view shows completed and archived items too (TAGS-R14); off by default. */
    val tagsShowDone: Flow<Boolean> = context.dataStore.data.map { it[Keys.tagsShowDone] ?: false }
    suspend fun setTagsShowDone(on: Boolean) = context.dataStore.edit { it[Keys.tagsShowDone] = on }

    /** Reminders as phone notifications (REM-R6), on by default. */
    val phoneReminders: Flow<Boolean> = context.dataStore.data.map { it[Keys.phoneReminders] ?: true }
    suspend fun phoneRemindersNow(): Boolean = phoneReminders.first()
    suspend fun setPhoneReminders(on: Boolean) = context.dataStore.edit { it[Keys.phoneReminders] = on }

    /** The version whose banner the member closed; the next version shows it again. */
    val dismissedUpdate: Flow<String?> = context.dataStore.data.map { it[Keys.dismissedUpdate] }
    suspend fun setDismissedUpdate(version: String) = context.dataStore.edit { it[Keys.dismissedUpdate] = version }

    suspend fun grouping(): String = read(Keys.grouping) ?: "category"
    suspend fun setGrouping(mode: String) = context.dataStore.edit { it[Keys.grouping] = mode }
    suspend fun collapsed(): Set<String> = context.dataStore.data.first()[Keys.collapsed] ?: emptySet()
    suspend fun setCollapsed(value: Set<String>) = context.dataStore.edit { it[Keys.collapsed] = value }

    /** One-time notices: returns true the first time [id] is asked about. */
    suspend fun firstTime(id: String): Boolean {
        var first = false
        context.dataStore.edit {
            val seen = it[Keys.notices] ?: emptySet()
            first = id !in seen
            if (first) it[Keys.notices] = seen + id
        }
        return first
    }

    private suspend fun read(key: Preferences.Key<String>): String? = context.dataStore.data.first()[key]
}
