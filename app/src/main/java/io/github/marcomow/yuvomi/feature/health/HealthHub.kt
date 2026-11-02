package io.github.marcomow.yuvomi.feature.health

import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.session.Session
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import java.time.LocalDate
import java.time.LocalDateTime

/** What the health view models need from the shell; an interface so they are tested without one. */
interface HealthHost {
    val strings: Strings

    fun today(): LocalDate

    /** The household's wall clock now. */
    fun now(): LocalDateTime

    /** A `Z` or offset stamp in household wall time. */
    fun wall(instant: String): LocalDateTime?

    /** A `YYYY-MM-DD` key as the UI shows dates (`dateText`). */
    fun date(key: String): String

    /** Shows [message]; true when the member tapped [action]. */
    suspend fun notify(message: String, action: String? = null): Boolean
}

/**
 * What every health area shares for one signed-in member (docs/spec/health.md, HEALTH-R2 to R6):
 * the household's people, who the member may record for (`/caregivers/me`), their default
 * visibility per area (`/visibility-defaults`), whether cycle and fasting are on, and the ONE
 * person chosen for the whole module (`utils/health-person.js`, kept for the app's lifetime the
 * way the web app keeps it for the tab's session).
 */
class HealthHub(private val container: AppContainer, val meId: Int, private val fallback: Session) {
    /** The live session: permissions can change while the screens are open. */
    private val session: Session
        get() = (container.session.state.value as? io.github.marcomow.yuvomi.core.session.SessionState.SignedIn)?.session ?: fallback


    data class State(
        val loaded: Boolean = false,
        val members: List<HealthMember> = emptyList(),
        val careFor: Set<Int> = emptySet(),
        val defaults: Map<String, String> = emptyMap(),
        val cycleEnabled: Boolean = true,
        val personId: Int? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val mutex = Mutex()

    /** `isNavModuleReadOnly('health')`: the server refuses every write under `/health` then. */
    val readOnly: Boolean get() = session.access("health") == "read"

    /** `canUseFasting()`: admins, or the `health_use_fasting` capability set to `allow`. */
    val fastingEnabled: Boolean get() = session.isAdmin || session.permissions.capabilities["health_use_fasting"] == "allow"

    val personId: Int get() = state.value.personId ?: meId

    val isOwn: Boolean get() = personId == meId

    /** `canEditFor`: the module right first, then own data or a person in the member's care. */
    fun canEdit(person: Int = personId): Boolean = !readOnly && (person == meId || person in state.value.careFor)

    /** `cycleCanEdit`: the cycle is never written for someone else, not even in care. */
    fun cycleCanEdit(person: Int = personId): Boolean = !readOnly && person == meId

    /** `defaultVisibility(scope)`: sparse, a missing scope is private. */
    fun defaultVisibility(scope: String): String = if (state.value.defaults[scope] == "family") "family" else "private"

    fun name(id: Int): String = state.value.members.firstOrNull { it.id == id }?.displayName.orEmpty()

    /** Loads the shared answers once; a failure leaves the narrower default (no care, private). */
    suspend fun ensure(force: Boolean = false) = mutex.withLock {
        if (state.value.loaded && !force) return@withLock
        val api = container.session.connection?.client?.let(::HealthApi) ?: return@withLock
        coroutineScope {
            val members = async { runCatching { api.members() } }
            val care = async { runCatching { api.careFor() }.getOrDefault(emptyList()) }
            val defaults = async { runCatching { api.visibilityDefaults() }.getOrDefault(emptyMap()) }
            val cycle = async { runCatching { api.cycleEnabled() }.getOrDefault(true) }
            val list = members.await().onSuccess {
                container.cache.write(MEMBERS_CACHE, ApiJson.encodeToString(ListSerializer(HealthMember.serializer()), it))
            }.getOrElse { e ->
                if (e !is ApiProblem) throw e
                container.cache.read(MEMBERS_CACHE)?.let { c ->
                    runCatching { ApiJson.decodeFromString(ListSerializer(HealthMember.serializer()), c.json) }.getOrNull()
                } ?: emptyList()
            }
            // `withChosenPeople`: the member is always in the list, even when not a household member.
            val me = HealthMember(meId, session.user.displayName, session.user.avatarColor)
            val all = if (list.any { it.id == meId }) list else listOf(me) + list
            _state.update { s ->
                val keep = s.personId?.takeIf { p -> p == meId || all.any { it.id == p } }
                s.copy(
                    loaded = true, members = all, careFor = care.await().toSet(), defaults = defaults.await(),
                    cycleEnabled = cycle.await(), personId = keep ?: meId,
                )
            }
        }
    }

    /** The overview's capture menu opens an area with its editor already up (`onCaptureMenuClick`). */
    var pendingAdd: String? = null

    /** Takes the pending "add" of [area], once. */
    fun takeAdd(area: String): Boolean = (pendingAdd == area).also { if (it) pendingAdd = null }

    /** `writeHealthPerson`: the choice holds for every area. */
    fun choose(id: Int) = _state.update { it.copy(personId = id) }

    companion object {
        const val MEMBERS_CACHE = "health-members"
        private val hubs = mutableMapOf<String, HealthHub>()

        /** One hub per server and member; a sign-in as someone else starts afresh. */
        fun of(container: AppContainer, session: Session): HealthHub = synchronized(hubs) {
            hubs.getOrPut("${session.server}#${session.user.id}") { HealthHub(container, session.user.id, session) }
        }
    }
}
