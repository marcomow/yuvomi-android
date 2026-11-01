package io.github.marcomow.yuvomi.feature.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.model.LoginOutcome
import io.github.marcomow.yuvomi.core.session.ProbeResult
import io.github.marcomow.yuvomi.core.session.SessionManager
import io.github.marcomow.yuvomi.core.session.SignOutNotice
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** docs/spec/signin.md, SIGNIN-R1 to R8. */
class SignInViewModel(
    private val session: SessionManager,
    prefillServer: String?,
    prefillUser: String?,
    notice: SignOutNotice?,
) : ViewModel() {

    enum class Step { SERVER, PASSWORD, TWO_FACTOR }

    data class UiState(
        val step: Step = Step.SERVER,
        val serverInput: String = "",
        val server: String? = null,
        val appName: String = "Yuvomi",
        val cleartext: Boolean = false,
        val username: String = "",
        val password: String = "",
        val code: String = "",
        val recoveryAvailable: Boolean = false,
        val busy: Boolean = false,
        /** An i18n key, with optional browser action. */
        val messageKey: String? = null,
        val messageArgs: Map<String, Any?> = emptyMap(),
        val browserUrl: String? = null,
        val lockedOutUntil: Long = 0,
        val recoveryRemaining: Int? = null,
    )

    private val _state = MutableStateFlow(
        UiState(
            serverInput = prefillServer.orEmpty(),
            username = prefillUser.orEmpty(),
            messageKey = if (notice == SignOutNotice.SIGNED_OUT_BY_SERVER) "android.signin.signedOut" else null,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var lockJob: Job? = null

    init {
        // A remembered server goes straight to the password step (SIGNIN-R4).
        if (!prefillServer.isNullOrBlank()) probe(keepMessage = true)
    }

    fun onServerInput(value: String) = _state.update { it.copy(serverInput = value, messageKey = null, browserUrl = null) }
    fun onUsername(value: String) = _state.update { it.copy(username = value, messageKey = null) }
    fun onPassword(value: String) = _state.update { it.copy(password = value, messageKey = null) }
    fun onCode(value: String) = _state.update { it.copy(code = value.take(64), messageKey = null) }

    fun changeServer() = _state.update { it.copy(step = Step.SERVER, messageKey = null, browserUrl = null) }

    fun probe(keepMessage: Boolean = false) {
        val input = state.value.serverInput
        if (input.isBlank() || state.value.busy) return
        _state.update { it.copy(busy = true, messageKey = if (keepMessage) it.messageKey else null, browserUrl = null) }
        viewModelScope.launch {
            val result = session.probe(input)
            _state.update { s ->
                val done = s.copy(busy = false)
                when (result) {
                    is ProbeResult.Ready -> done.copy(
                        step = Step.PASSWORD, server = result.server, appName = result.appName, cleartext = result.cleartext,
                    )
                    is ProbeResult.SetupRequired -> done.copy(
                        messageKey = "android.signin.setupRequired", browserUrl = result.server + "/setup",
                    )
                    ProbeResult.Invalid -> done.copy(messageKey = "android.signin.invalidAddress")
                    ProbeResult.CleartextRefused -> done.copy(messageKey = "android.signin.cleartextRefused")
                    ProbeResult.NotYuvomi -> done.copy(messageKey = "android.signin.notYuvomi")
                    ProbeResult.Unreachable -> done.copy(messageKey = "android.signin.unreachable")
                    ProbeResult.Certificate -> done.copy(messageKey = "android.signin.certificate")
                }
            }
        }
    }

    fun signIn() {
        val s = state.value
        if (s.busy || System.currentTimeMillis() < s.lockedOutUntil) return
        if (s.username.isBlank() || s.password.isEmpty()) {
            _state.update { it.copy(messageKey = "login.fillAllFields") }
            return
        }
        _state.update { it.copy(busy = true, messageKey = null, browserUrl = null) }
        viewModelScope.launch {
            try {
                when (val outcome = session.login(s.username.trim(), s.password)) {
                    is LoginOutcome.TwoFactorRequired -> _state.update {
                        it.copy(busy = false, step = Step.TWO_FACTOR, recoveryAvailable = outcome.recoveryAvailable, code = "")
                    }
                    is LoginOutcome.SignedIn -> _state.update { it.copy(busy = false, password = "") }
                }
            } catch (e: ApiProblem) {
                _state.update { it.copy(busy = false).withLoginError(e) }
            }
        }
    }

    fun verify() {
        val s = state.value
        if (s.busy) return
        if (s.code.isBlank()) {
            _state.update { it.copy(messageKey = "login.twoFactorMissing") }
            return
        }
        _state.update { it.copy(busy = true, messageKey = null) }
        viewModelScope.launch {
            try {
                session.verifyTwoFactor(s.code.trim().replace(" ", ""))
                _state.update { it.copy(busy = false, password = "", code = "") }
            } catch (e: ApiProblem.Http) {
                _state.update {
                    when {
                        e.status == 401 && e.serverMessage?.startsWith("No pending sign-in") == true -> it.copy(
                            busy = false, step = Step.PASSWORD, password = "", messageKey = "login.twoFactorExpired",
                        )
                        e.status == 401 -> it.copy(busy = false, code = "", messageKey = "login.twoFactorInvalid")
                        else -> it.copy(busy = false).withLoginError(e)
                    }
                }
            } catch (e: ApiProblem) {
                _state.update { it.copy(busy = false).withLoginError(e) }
            }
        }
    }

    fun cancelTwoFactor() = _state.update { it.copy(step = Step.PASSWORD, code = "", password = "", messageKey = null) }

    private fun UiState.withLoginError(e: ApiProblem): UiState = when {
        e is ApiProblem.Network -> copy(messageKey = "login.networkError")
        e is ApiProblem.Http && e.status == 401 -> copy(password = "", messageKey = "login.invalidCredentials")
        e is ApiProblem.Http && e.reason == "password_login_disabled" ->
            copy(messageKey = "android.signin.ssoUnsupported", browserUrl = server)
        e is ApiProblem.Http && e.reason == "account_cannot_sign_in" -> copy(messageKey = "login.accountCannotSignIn")
        e is ApiProblem.Http && e.status == 429 -> {
            startLockout()
            copy(messageKey = "login.tooManyAttempts", lockedOutUntil = System.currentTimeMillis() + LOCKOUT_MS)
        }
        else -> copy(messageKey = "android.error.generic")
    }

    private fun startLockout() {
        lockJob?.cancel()
        lockJob = viewModelScope.launch {
            delay(LOCKOUT_MS)
            _state.update { it.copy(lockedOutUntil = 0) }
        }
    }

    private companion object {
        const val LOCKOUT_MS = 60_000L
    }
}
