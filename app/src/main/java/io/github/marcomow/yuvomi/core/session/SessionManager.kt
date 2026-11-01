package io.github.marcomow.yuvomi.core.session

import io.github.marcomow.yuvomi.BuildConfig
import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.AuthApi
import io.github.marcomow.yuvomi.core.api.CsrfStore
import io.github.marcomow.yuvomi.core.api.HouseholdApi
import io.github.marcomow.yuvomi.core.api.TasksApi
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.api.model.LoginOutcome
import io.github.marcomow.yuvomi.core.api.model.Permissions
import io.github.marcomow.yuvomi.core.api.model.Preferences
import io.github.marcomow.yuvomi.core.api.model.SignInPayload
import io.github.marcomow.yuvomi.core.api.model.User
import io.github.marcomow.yuvomi.core.data.ResponseCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** One server connection: the client and the typed APIs on it. */
class Connection(val server: String, val client: YuvomiClient, val cookies: SessionCookieJar) {
    val auth = AuthApi(client)
    val household = HouseholdApi(client)
    val tasks = TasksApi(client)
}

/** What a signed-in screen needs to know about the member and the household. */
data class Session(
    val server: String,
    val user: User,
    val permissions: Permissions,
    val householdSize: Int,
    val preferences: Preferences,
    val serverVersion: String?,
    val verdict: VersionGate.Verdict,
    val showNewerNotice: Boolean,
) {
    val zone: ZoneId = preferences.timezoneEffective?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

    /** `none`, `read` or `write`; a module switched off for the household is `none` (SIGNIN-R13). */
    fun access(module: String): String = if (module in preferences.disabledModules) "none" else permissions.module(module)
    val isAdmin: Boolean get() = permissions.admin
    val soloHousehold: Boolean get() = householdSize == 1
}

sealed interface SessionState {
    data object Starting : SessionState
    data class SignedOut(val server: String?, val username: String?, val notice: SignOutNotice? = null) : SessionState
    data class SignedIn(val session: Session, val offline: Boolean = false) : SessionState
}

enum class SignOutNotice { SIGNED_OUT_BY_SERVER }

sealed interface ProbeResult {
    data class Ready(val server: String, val appName: String, val cleartext: Boolean) : ProbeResult
    data class SetupRequired(val server: String) : ProbeResult
    data object Invalid : ProbeResult
    data object CleartextRefused : ProbeResult
    data object NotYuvomi : ProbeResult
    data object Unreachable : ProbeResult
    data object Certificate : ProbeResult
}

/**
 * docs/spec/signin.md. Owns the connection, the sign-in flow and the household
 * context; every other screen reads [state].
 */
class SessionManager(
    private val settings: SettingsStore,
    private val cache: ResponseCache,
    private val scope: CoroutineScope,
    private val httpBuilder: () -> OkHttpClient.Builder = { defaultHttp() },
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Starting)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    var connection: Connection? = null
        private set

    private val signedIn: Session? get() = (state.value as? SessionState.SignedIn)?.session

    @Serializable
    private data class Snapshot(val payload: SignInPayload, val preferences: Preferences, val version: String? = null)

    private var started = false

    /**
     * SIGNIN-R10: restore a stored session, show it at once, then check it with
     * the server. Runs once per process; a recreated activity keeps the session.
     */
    suspend fun start() {
        if (started || state.value != SessionState.Starting) return
        started = true
        val server = settings.server()
        val cookies = settings.cookies()
        if (server == null || cookies.isNullOrEmpty()) {
            _state.value = SessionState.SignedOut(server, settings.username())
            return
        }
        val conn = connect(server, cookies)
        val snapshot = settings.username()?.let { readSnapshot(server) }
        if (snapshot != null) {
            cache.open(server, snapshot.payload.user.id)
            _state.value = SessionState.SignedIn(sessionOf(server, snapshot, showNewer = false), offline = true)
        }
        try {
            val payload = conn.auth.me()
            completeSignIn(conn, payload)
        } catch (e: ApiProblem.SessionExpired) {
            onSessionExpired()
            if (state.value !is SessionState.SignedOut) {
                clearLocal(null)
                _state.value = SessionState.SignedOut(server, settings.username(), SignOutNotice.SIGNED_OUT_BY_SERVER)
            }
        } catch (e: ApiProblem) {
            if (snapshot == null) _state.value = SessionState.SignedOut(server, settings.username())
        }
    }

    /** SIGNIN-R1 to R3. */
    suspend fun probe(input: String): ProbeResult {
        val normalized = when (val r = ServerAddress.normalize(input)) {
            is ServerAddress.Result.Ok -> r
            ServerAddress.Result.Invalid -> return ProbeResult.Invalid
            ServerAddress.Result.CleartextRefused -> return ProbeResult.CleartextRefused
        }
        val sameServer = normalized.url == settings.server()
        val conn = connect(normalized.url, if (sameServer) settings.cookies() else null)
        return try {
            val version = conn.auth.version()
            when {
                version.appName == null -> ProbeResult.NotYuvomi
                version.setupRequired -> ProbeResult.SetupRequired(normalized.url)
                else -> ProbeResult.Ready(normalized.url, version.appName, normalized.cleartext)
            }
        } catch (e: ApiProblem.Network) {
            if (e.isCertificate) ProbeResult.Certificate else ProbeResult.Unreachable
        } catch (e: ApiProblem) {
            ProbeResult.NotYuvomi
        }
    }

    /** SIGNIN-R6. Throws [ApiProblem] for the screen to map. */
    suspend fun login(username: String, password: String): LoginOutcome {
        val conn = connection ?: error("probe first")
        settings.setServerAndUser(conn.server, username)
        val outcome = conn.auth.login(username, password)
        if (outcome is LoginOutcome.SignedIn) completeSignIn(conn, outcome.payload)
        return outcome
    }

    /** SIGNIN-R8. */
    suspend fun verifyTwoFactor(code: String): SignInPayload {
        val conn = connection ?: error("probe first")
        val payload = conn.auth.verifyTwoFactor(code)
        completeSignIn(conn, payload)
        return payload
    }

    /** Re-reads member, permissions, preferences and version (pull to refresh, foreground). */
    suspend fun refresh() {
        val conn = connection ?: return
        try {
            completeSignIn(conn, conn.auth.me())
        } catch (e: ApiProblem.SessionExpired) {
            onSessionExpired()
        } catch (e: ApiProblem) {
            (state.value as? SessionState.SignedIn)?.let { _state.value = it.copy(offline = e is ApiProblem.Network) }
        }
    }

    fun dismissNewerNotice() {
        val current = state.value as? SessionState.SignedIn ?: return
        _state.value = current.copy(session = current.session.copy(showNewerNotice = false))
    }

    /** SIGNIN-R15: local state is cleared whatever the server answers. */
    suspend fun signOut() {
        val conn = connection
        val session = signedIn
        runCatching { conn?.auth?.logout() }
        clearLocal(session)
        _state.value = SessionState.SignedOut(conn?.server, settings.username())
    }

    /** The household's preferences changed elsewhere; re-read them. */
    suspend fun refreshPreferences(): Preferences? {
        val conn = connection ?: return null
        val current = state.value as? SessionState.SignedIn ?: return null
        val prefs = runCatching { conn.household.preferences() }.getOrNull() ?: return null
        val session = current.session.copy(preferences = prefs)
        writeSnapshot(session.server, SignInPayload(session.user, session.permissions, session.householdSize), prefs, session.serverVersion)
        _state.value = current.copy(session = session, offline = false)
        return prefs
    }

    private suspend fun completeSignIn(conn: Connection, payload: SignInPayload) = coroutineScope {
        val prefs = async { runCatching { conn.household.preferences() }.getOrNull() }
        val version = async { runCatching { conn.auth.version().version }.getOrNull() }
        val preferences = prefs.await() ?: signedIn?.preferences ?: Preferences()
        val serverVersion = version.await() ?: signedIn?.serverVersion
        settings.setServerAndUser(conn.server, payload.user.username)
        cache.open(conn.server, payload.user.id)
        writeSnapshot(conn.server, payload, preferences, serverVersion)
        val verdict = VersionGate.verdict(serverVersion, BuildConfig.UPSTREAM_MIN, BuildConfig.UPSTREAM_PIN)
        val showNewer = verdict == VersionGate.Verdict.NEWER_THAN_TESTED &&
            settings.firstTime("newer:${conn.server}:$serverVersion")
        val snapshot = Snapshot(payload, preferences, serverVersion)
        _state.value = SessionState.SignedIn(sessionOf(conn.server, snapshot, showNewer), offline = false)
    }

    private fun sessionOf(server: String, s: Snapshot, showNewer: Boolean) = Session(
        server = server,
        user = s.payload.user,
        permissions = s.payload.permissions,
        householdSize = s.payload.householdSize,
        preferences = s.preferences,
        serverVersion = s.version,
        verdict = VersionGate.verdict(s.version, BuildConfig.UPSTREAM_MIN, BuildConfig.UPSTREAM_PIN),
        showNewerNotice = showNewer,
    )

    private fun connect(server: String, cookies: String?): Connection {
        val csrf = CsrfStore()
        val jar = SessionCookieJar(cookies) { serialized -> scope.launch { settings.setCookies(serialized) } }
        val http = httpBuilder().cookieJar(jar).addInterceptor(csrf.interceptor).build()
        val client = YuvomiClient(server, http, csrf) { scope.launch { onSessionExpired() } }
        return Connection(server, client, jar).also { connection = it }
    }

    private val expiry = Mutex()

    /** SIGNIN-R12: any 401 outside sign-in. Runs once, however many calls notice it. */
    private suspend fun onSessionExpired() = expiry.withLock {
        if (state.value !is SessionState.SignedIn) return@withLock
        val session = signedIn
        clearLocal(session)
        _state.value = SessionState.SignedOut(session?.server, session?.user?.username, SignOutNotice.SIGNED_OUT_BY_SERVER)
    }

    private suspend fun clearLocal(session: Session?) {
        connection?.cookies?.clear()
        connection?.client?.csrf?.clear()
        settings.clearCookies()
        if (session != null) cache.clear(session.server, session.user.id)
        connection?.server?.let { cache.deleteGlobal(snapshotKey(it)) }
        cache.close()
    }

    private fun readSnapshot(server: String): Snapshot? {
        val entry = cache.readGlobal(snapshotKey(server)) ?: return null
        return runCatching { ApiJson.decodeFromString(Snapshot.serializer(), entry) }.getOrNull()
    }

    private fun writeSnapshot(server: String, payload: SignInPayload, prefs: Preferences, version: String?) {
        val clean = payload.copy(csrfToken = null)
        cache.writeGlobal(snapshotKey(server), ApiJson.encodeToString(Snapshot.serializer(), Snapshot(clean, prefs, version)))
    }

    private fun snapshotKey(server: String) = "session-" + server.hashCode().toUInt().toString(16)

    companion object {
        fun defaultHttp(): OkHttpClient.Builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
    }
}
