package io.github.marcomow.yuvomi.feature.signin

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.R
import io.github.marcomow.yuvomi.core.browser.Browser
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t

@Composable
fun SignInScreen(vm: SignInViewModel) {
    val s by vm.state.collectAsState()
    val context = LocalContext.current
    val strings = LocalStrings.current

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Image(painterResource(R.drawable.ic_brand_mark), contentDescription = null, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(16.dp))
        Text(s.appName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(t("login.tagline"), style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))

        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (s.step) {
                SignInViewModel.Step.SERVER -> ServerStep(s, vm)
                SignInViewModel.Step.PASSWORD -> PasswordStep(s, vm)
                SignInViewModel.Step.TWO_FACTOR -> TwoFactorStep(s, vm)
            }
            s.messageKey?.let { key ->
                Text(strings.t(key, s.messageArgs), color = Yuvomi.colors.danger, style = MaterialTheme.typography.bodyMedium)
            }
            s.browserUrl?.let { url ->
                OutlinedButton(onClick = { Browser.open(context, url) }, modifier = Modifier.fillMaxWidth()) {
                    Text(t("android.browser.open"))
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        Text(t("android.signin.unofficial"), style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.textTertiary)
    }
}

@Composable
private fun ServerStep(s: SignInViewModel.UiState, vm: SignInViewModel) {
    OutlinedTextField(
        value = s.serverInput,
        onValueChange = vm::onServerInput,
        label = { Text(t("android.signin.serverLabel")) },
        placeholder = { Text("https://home.example.org") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { vm.probe() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { vm.probe() }, enabled = !s.busy && s.serverInput.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        if (s.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(t("common.next"))
    }
}

@Composable
private fun PasswordStep(s: SignInViewModel.UiState, vm: SignInViewModel) {
    var show by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(s.server.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary, modifier = Modifier.weight(1f))
        TextButton(onClick = vm::changeServer) { Text(t("android.signin.changeServer")) }
    }
    if (s.cleartext) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WarningAmber, null, tint = Yuvomi.colors.warning, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(t("android.signin.cleartextWarning"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.warning)
        }
    }
    OutlinedTextField(
        value = s.username,
        onValueChange = vm::onUsername,
        label = { Text(t("login.usernameLabel")) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
    )
    OutlinedTextField(
        value = s.password,
        onValueChange = vm::onPassword,
        label = { Text(t("login.passwordLabel")) },
        singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { show = !show }) {
                Icon(
                    if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = t(if (show) "login.hidePassword" else "login.showPassword"),
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { vm.signIn() }),
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
    )
    Button(
        onClick = vm::signIn,
        enabled = !s.busy && s.lockedOutUntil == 0L,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (s.busy) t("login.loggingIn") else t("login.loginButton")) }
}

@Composable
private fun TwoFactorStep(s: SignInViewModel.UiState, vm: SignInViewModel) {
    Text(t("login.twoFactorLead"), style = MaterialTheme.typography.bodyLarge)
    // One field takes the six digits or a recovery code, as upstream's login page (SIGNIN-R7).
    OutlinedTextField(
        value = s.code,
        onValueChange = vm::onCode,
        label = { Text(t("login.twoFactorCodeLabel")) },
        supportingText = { Text(t(if (s.recoveryAvailable) "login.twoFactorHintRecovery" else "login.twoFactorHint")) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (s.recoveryAvailable) KeyboardType.Ascii else KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { vm.verify() }),
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.SmsOtpCode },
    )
    Button(onClick = vm::verify, enabled = !s.busy, modifier = Modifier.fillMaxWidth()) {
        Text(if (s.busy) t("login.twoFactorChecking") else t("login.twoFactorSubmit"))
    }
    TextButton(onClick = vm::cancelTwoFactor, modifier = Modifier.fillMaxWidth()) { Text(t("login.twoFactorCancel")) }
}
