package io.github.marcomow.yuvomi

import android.app.Application
import android.content.res.Resources
import io.github.marcomow.yuvomi.core.data.ResponseCache
import io.github.marcomow.yuvomi.core.design.AvatarLoader
import io.github.marcomow.yuvomi.core.design.Tokens
import io.github.marcomow.yuvomi.core.i18n.LocalePicker
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.i18n.StringsLoader
import io.github.marcomow.yuvomi.core.session.KeystoreSecretBox
import io.github.marcomow.yuvomi.core.session.SecretBox
import io.github.marcomow.yuvomi.core.session.SessionManager
import io.github.marcomow.yuvomi.core.session.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File

/** Manual dependency wiring (ADR 0001). One instance per process. */
class AppContainer(app: Application, box: SecretBox = KeystoreSecretBox()) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(app, box)
    val cache = ResponseCache(File(app.filesDir, "cache"))
    val session = SessionManager(settings, cache, scope)
    val tokens: Tokens = Tokens.load(app.assets)
    val avatars = AvatarLoader { session.connection?.client }

    /** SIGNIN-R17: the chosen language, or the device's mapped to an upstream locale. */
    val strings: StateFlow<Strings?> = settings.language
        .map { chosen -> StringsLoader.load(app.assets, chosen ?: deviceLocale()) }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, null)

    private fun deviceLocale(): String {
        val locales = Resources.getSystem().configuration.locales
        return LocalePicker.pick((0 until locales.size()).map { locales[it].toLanguageTag() })
    }
}

class YuvomiApp : Application() {
    /** Created on first use, so tests can build their own with a test secret box. */
    val container: AppContainer by lazy { AppContainer(this) }
}
