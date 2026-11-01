package io.github.marcomow.yuvomi.feature.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.LocalAvatarLoader
import io.github.marcomow.yuvomi.core.design.YuvomiTheme
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.session.SessionState
import io.github.marcomow.yuvomi.core.session.VersionGate
import io.github.marcomow.yuvomi.feature.signin.SignInScreen
import io.github.marcomow.yuvomi.feature.signin.SignInViewModel

/** Chooses sign-in, the version block, or the signed-in shell from the session state. */
@Composable
fun YuvomiRoot(container: AppContainer, deepLinkTask: Int?, onDeepLinkHandled: () -> Unit) {
    val strings by container.strings.collectAsState()
    val state by container.session.state.collectAsState()

    LaunchedEffect(Unit) { container.session.start() }

    YuvomiTheme(container.tokens) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val loaded = strings
            if (loaded == null) {
                LoadingBox()
                return@Surface
            }
            CompositionLocalProvider(
                LocalStrings provides loaded,
                LocalAvatarLoader provides container.avatars,
                LocalLayoutDirection provides if (loaded.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                when (val s = state) {
                    SessionState.Starting -> LoadingBox()
                    is SessionState.SignedOut -> {
                        val vm = viewModel(key = "signin-${s.server}-${s.notice}") {
                            SignInViewModel(container.session, s.server, s.username, s.notice)
                        }
                        Box(Modifier.fillMaxSize()) { SignInScreen(vm) }
                    }
                    is SessionState.SignedIn -> {
                        if (s.session.verdict == VersionGate.Verdict.TOO_OLD) {
                            TooOldScreen(container, s.session)
                        } else {
                            MainShell(container, s, deepLinkTask, onDeepLinkHandled)
                        }
                    }
                }
            }
        }
    }
}
