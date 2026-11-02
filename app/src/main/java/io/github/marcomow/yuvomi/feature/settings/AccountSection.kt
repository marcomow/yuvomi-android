package io.github.marcomow.yuvomi.feature.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.Tokens
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.t
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import kotlin.math.min

/** docs/spec/settings.md, "Account" (SET-R10 to R17): `personal-account.js`. */
@Composable
fun AccountSection(ctx: SectionContext) {
    val slots by ctx.vm.slots.collectAsState()
    LaunchedEffect(Unit) {
        ctx.vm.load(ME) { it.me() }
        ctx.vm.load(TWO_FACTOR) { it.data(SettingsOperation.TWO_FACTOR_STATUS) }
        ctx.vm.load(OIDC) { it.call(SettingsOperation.OIDC_LINK) }
    }
    val me = slots[ME]
    val user = (me?.value as? JsonObject)?.child("user")
    when {
        user == null && me?.errorKey != null -> SettingsGroup(footer = t(me.errorKey)) {
            ActionRow(t("settings.retry")) { ctx.vm.load(ME, force = true) { it.me() } }
        }
        user == null -> Busy(true)
        else -> {
            ProfileCard(ctx, user, me.value as JsonObject)
            if (!user.bool("sso_only")) PasswordCard(ctx)
            (slots[TWO_FACTOR]?.value as? JsonObject)?.let { TwoFactorCard(ctx, it) }
            OtherSessionsCard(ctx)
            (slots[OIDC]?.value as? JsonObject)?.takeIf { it.bool("enabled") }?.let { OidcCard(ctx, it) }
            SettingsGroup {
                var confirm by remember { mutableStateOf(false) }
                ActionRow(t("settings.logout"), danger = true) { confirm = true }
                if (confirm) {
                    ConfirmDialog(t("settings.logoutConfirm"), null, t("settings.logout"), danger = true, onDismiss = { confirm = false }) {
                        confirm = false
                        ctx.scope.launch { ctx.shell.container.session.signOut() }
                    }
                }
            }
        }
    }
}

private const val ME = "me"
private const val TWO_FACTOR = "2fa"
private const val OIDC = "oidc"

/** The profile card: picture, name, colour, username, contact details (SET-R10, R11). */
@Composable
private fun ProfileCard(ctx: SectionContext, user: JsonObject, me: JsonObject) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(user.str("display_name").orEmpty()) }
    var color by rememberSaveable { mutableStateOf(user.str("avatar_color") ?: AccountRules.COLOR_DEFAULT) }
    var picture by rememberSaveable { mutableStateOf(user.str("avatar_data")) }
    var phone by rememberSaveable { mutableStateOf(user.str("phone").orEmpty()) }
    var email by rememberSaveable { mutableStateOf(user.str("email").orEmpty()) }
    var birth by rememberSaveable { mutableStateOf(user.str("birth_date")?.take(10).orEmpty()) }
    var yearUnknown by rememberSaveable { mutableStateOf(user.bool("birth_year_unknown")) }
    var problems by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }
    val gravatarHost = me.str("gravatarHost").orEmpty()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) when (val r = AvatarImage.encode(context, uri)) {
            is AvatarImage.Result.Ok -> picture = r.dataUrl
            is AvatarImage.Result.Problem -> ctx.notifyKey(r.key)
        }
    }

    fun save(extra: JsonObject? = null) {
        problems = AccountRules.profileProblems(name, email, birth, ctx.shell.time.today())
        if (problems.isNotEmpty() || busy) return
        busy = true
        ctx.scope.launch {
            val result = ctx.vm.attempt { api ->
                api.call(SettingsOperation.PROFILE_UPDATE, body = extra ?: buildJsonObject {
                    put("display_name", name.trim())
                    put("avatar_color", color)
                    if (picture != null) put("avatar_data", picture) else put("avatar_data", JsonNull)
                    if (phone.isBlank()) put("phone", JsonNull) else put("phone", phone.trim())
                    if (email.isBlank()) put("email", JsonNull) else put("email", email.trim())
                    if (birth.isBlank()) put("birth_date", JsonNull) else put("birth_date", birth.trim())
                    put("birth_year_unknown", yearUnknown && birth.isNotBlank())
                })
            }
            busy = false
            result.onSuccess { answer ->
                val saved = answer.obj().child("user")
                if (saved.isNotEmpty()) ctx.vm.set(ME, JsonObject(me + ("user" to JsonObject(user + saved))))
                ctx.vm.refreshSession()
                ctx.notifyKey("settings.profileSavedToast")
            }.onFailure { e ->
                if ((e as? ApiProblem.Http)?.reason == "email_in_use") problems = mapOf("email" to "common.emailInUse")
                else ctx.notifyKey(SettingsErrors.key(e))
            }
        }
    }

    SettingsGroup(title = t("settings.profileCardTitle")) {
        GroupBody {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clickable(enabled = ctx.editable, onClickLabel = t("settings.profilePictureLabel")) {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    MemberAvatar(name.ifBlank { "?" }, color, picture, size = 72.dp)
                }
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = ctx.editable, onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text(t("settings.profilePictureLabel"))
                    }
                    if (picture != null && ctx.editable) TextButton(onClick = { picture = null }) { Text(t("settings.profilePictureRemove")) }
                    if (me.bool("gravatarAvailable") && ctx.editable) {
                        val ready = AccountRules.hasSavedEmail(user.str("email"))
                        TextButton(enabled = ready && !busy, onClick = {
                            busy = true
                            ctx.scope.launch {
                                ctx.vm.attempt { it.data(SettingsOperation.GRAVATAR, body = buildJsonObject { put("email", user.str("email")!!.trim()) }) }
                                    .onSuccess { data ->
                                        val d = data.obj()
                                        picture = d.str("avatar_data")
                                        ctx.vm.set(ME, JsonObject(me + ("user" to JsonObject(user + d))))
                                        ctx.vm.refreshSession()
                                        ctx.notifyKey("settings.gravatarDone")
                                    }
                                    .onFailure { ctx.notifyKey(AccountRules.gravatarErrorKey(it), "host" to gravatarHost) }
                                busy = false
                            }
                        }) { Text(t("settings.gravatarUse")) }
                    }
                }
            }
            if (me.bool("gravatarAvailable")) {
                val hint = when {
                    !AccountRules.hasSavedEmail(user.str("email")) -> t("settings.gravatarNeedsEmail")
                    gravatarHost.isNotEmpty() -> t("settings.gravatarHint", "email" to user.str("email"), "host" to gravatarHost)
                    else -> t("settings.gravatarHintUnnamed", "email" to user.str("email"))
                }
                Hint(hint)
            }
            Field(t("settings.displayNameLabel"), name, { name = it.take(AccountRules.DISPLAY_NAME_MAX) }, problems["display_name"]?.let { t(it) }, enabled = ctx.editable)
            Text(t("settings.colorLabel"), style = MaterialTheme.typography.labelLarge)
            ColorSwatches(color, ctx.editable) { color = it }
            ValueRow(t("settings.usernameLabel"), "@" + user.str("username").orEmpty(), t("settings.usernameFixedHint"))
            Text(t("settings.contactDetailsLegend"), style = MaterialTheme.typography.labelLarge)
            Field(t("settings.memberPhoneLabel"), phone, { phone = it }, enabled = ctx.editable, keyboard = KeyboardType.Phone)
            Field(t("settings.memberEmailLabel"), email, { email = it }, problems["email"]?.let { t(it) }, enabled = ctx.editable, keyboard = KeyboardType.Email)
            DateField(t("settings.memberBirthDateLabel"), birth, problems["birth_date"]?.let { t(it) }, ctx.editable) { birth = it }
            SwitchRow(t("birthdays.yearUnknown"), yearUnknown, enabled = ctx.editable && birth.isNotBlank()) { yearUnknown = it }
            Hint(t("settings.memberContactBirthdayHint"))
            if (ctx.editable) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = { save() }, enabled = !busy) { Text(t("common.save")) }
                }
            }
        }
    }
}

/** The household's ten colours as swatches (`colorPickerHtml`); a custom colour stays selectable. */
@Composable
fun ColorSwatches(selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val options = AccountRules.COLORS.let { list ->
        if (list.none { it.first.equals(selected, true) } && Regex("^#[0-9A-Fa-f]{6}$").matches(selected)) list + (selected to "colorPicker.custom") else list
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (hex, labelKey) ->
            val on = hex.equals(selected, true)
            val label = t(labelKey)
            Box(
                Modifier.size(36.dp).clip(CircleShape)
                    .background(Tokens.parseColor(hex) ?: MaterialTheme.colorScheme.primary)
                    .border(if (on) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    .clickable(enabled = enabled) { onSelect(hex) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) { if (on) androidx.compose.material3.Icon(Icons.Outlined.Check, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(18.dp)) }
        }
    }
}

/** `PATCH /auth/me/password` (SET-R12). */
@Composable
private fun PasswordCard(ctx: SectionContext) {
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var problems by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }
    SettingsGroup(title = t("settings.changePassword")) {
        GroupBody {
            Field(t("settings.currentPasswordLabel"), current, { current = it }, problems["current"]?.let { t(it) }, password = true, enabled = ctx.editable)
            Field(t("settings.newPasswordLabel"), next, { next = it }, problems["next"]?.let { t(it) }, password = true, enabled = ctx.editable)
            Field(t("settings.confirmPasswordLabel"), confirm, { confirm = it }, problems["confirm"]?.let { t(it) }, password = true, enabled = ctx.editable)
            if (ctx.editable) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(enabled = !busy, onClick = {
                    problems = AccountRules.passwordProblems(current, next, confirm)
                    if (problems.isNotEmpty()) return@Button
                    busy = true
                    ctx.scope.launch {
                        ctx.vm.attempt { it.call(SettingsOperation.PASSWORD_CHANGE, body = buildJsonObject { put("current_password", current); put("new_password", next) }) }
                            .onSuccess { current = ""; next = ""; confirm = ""; ctx.notifyKey("settings.passwordSavedToast") }
                            .onFailure { e ->
                                if ((e as? ApiProblem.Http)?.reason == "wrong_password") problems = mapOf("current" to "settings.currentPasswordWrong")
                                else ctx.notifyKey(SettingsErrors.key(e))
                            }
                        busy = false
                    }
                }) { Text(t("settings.savePassword")) }
            }
        }
    }
}

/** The second factor: set up, recovery codes, turn off (SET-R13, R14). */
@Composable
private fun TwoFactorCard(ctx: SectionContext, status: JsonObject) {
    val context = LocalContext.current
    var setup by remember { mutableStateOf<JsonObject?>(null) }
    var codes by remember { mutableStateOf<List<String>?>(null) }
    var ask by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun reload() = ctx.vm.load(TWO_FACTOR, force = true) { it.data(SettingsOperation.TWO_FACTOR_STATUS) }

    SettingsGroup(title = t("settings.twoFactorTitle")) {
        GroupBody {
            val shownCodes = codes
            val pending = setup
            when {
                shownCodes != null -> {
                    Text(t("settings.twoFactorRecoveryTitle"), style = MaterialTheme.typography.titleSmall)
                    Hint(t("settings.twoFactorRecoveryLead"))
                    SelectionContainer {
                        Text(shownCodes.joinToString("\n"), fontFamily = FontFamily.Monospace, modifier = Modifier.semantics { contentDescription = ctx.strings.t("settings.twoFactorRecoveryTitle") })
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            copy(context, AccountRules.recoveryText(shownCodes))
                            ctx.notifyKey("settings.twoFactorCopied")
                        }) { Text(t("settings.twoFactorCopyCodes")) }
                        OutlinedButton(onClick = { share(context, AccountRules.recoveryText(shownCodes)) }) { Text(t("settings.twoFactorDownloadCodes")) }
                        Button(onClick = { codes = null; reload() }) { Text(t("settings.twoFactorCodesSaved")) }
                    }
                }
                pending != null -> {
                    Text("1. " + t("settings.twoFactorStepScan"))
                    Text("2. " + t("settings.twoFactorStepEnter"))
                    pending.str("uri")?.let { uri ->
                        OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }.onFailure { ctx.notifyKey("settings.twoFactorManualHint") } }) {
                            Text(t("android.settings.openAuthenticator"))
                        }
                    }
                    Hint(t("settings.twoFactorManualHint"))
                    SelectionContainer { Text(AccountRules.groupSecret(pending.str("secret").orEmpty()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium) }
                    Field(t("settings.twoFactorCodeLabel"), code, { code = it.take(64) }, error, keyboard = KeyboardType.Number)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        TextButton(onClick = { setup = null; code = ""; error = null; reload() }) { Text(t("common.cancel")) }
                        Button(enabled = !busy, onClick = {
                            if (code.isBlank()) { error = ctx.strings.t("settings.twoFactorCodeMissing"); return@Button }
                            busy = true
                            ctx.scope.launch {
                                ctx.vm.attempt { it.data(SettingsOperation.TWO_FACTOR_ENABLE, body = buildJsonObject { put("code", code.trim()) }) }
                                    .onSuccess { codes = it.obj().strings("recovery_codes"); setup = null; code = ""; error = null }
                                    .onFailure { error = ctx.strings.t(AccountRules.twoFactorErrorKey(it)) }
                                busy = false
                            }
                        }) { Text(t("settings.twoFactorConfirm")) }
                    }
                }
                status.bool("enabled") -> {
                    Text(t("settings.twoFactorActive"), color = Yuvomi.colors.success, style = MaterialTheme.typography.titleSmall)
                    val left = status.int("recovery_remaining") ?: 0
                    if (left > 0) Hint(t("settings.twoFactorRecoveryLeft", "count" to left))
                    else Hint(t("settings.twoFactorNoRecoveryLeft"), Yuvomi.colors.danger)
                    if (ctx.editable) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { ask = "recovery"; code = ""; error = null }) { Text(t("settings.twoFactorNewCodes")) }
                        if (!status.bool("required")) OutlinedButton(onClick = { ask = "disable"; code = ""; error = null }) {
                            Text(t("settings.twoFactorDisable"), color = Yuvomi.colors.danger)
                        }
                    }
                    if (status.bool("required")) Hint(t("settings.twoFactorRequiredByHousehold"))
                }
                else -> {
                    Hint(t("settings.twoFactorHint"))
                    if (status.bool("required")) Hint(t("settings.twoFactorRequiredSetUpNow"), Yuvomi.colors.danger)
                    if (ctx.editable) Button(enabled = !busy, onClick = {
                        busy = true
                        ctx.scope.launch {
                            ctx.vm.attempt { it.data(SettingsOperation.TWO_FACTOR_SETUP) }
                                .onSuccess { setup = it.obj() }
                                .onFailure { ctx.notifyKey(AccountRules.twoFactorErrorKey(it)) }
                            busy = false
                        }
                    }) { Text(t("settings.twoFactorSetUp")) }
                }
            }
        }
    }

    ask?.let { which ->
        val disable = which == "disable"
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(t(if (disable) "settings.twoFactorDisableTitle" else "settings.twoFactorNewCodes")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t(if (disable) "settings.twoFactorDisableLead" else "settings.twoFactorNewCodesLead"))
                    Field(t("settings.twoFactorCodeOrRecoveryLabel"), code, { code = it.take(64) }, error)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    if (code.isBlank()) { error = ctx.strings.t("settings.twoFactorCodeMissing"); return@TextButton }
                    busy = true
                    ctx.scope.launch {
                        val op = if (disable) SettingsOperation.TWO_FACTOR_DISABLE else SettingsOperation.TWO_FACTOR_RECOVERY
                        ctx.vm.attempt { it.data(op, body = buildJsonObject { put("code", code.trim()) }) }
                            .onSuccess {
                                ask = null
                                code = ""
                                if (disable) reload() else codes = it.obj().strings("recovery_codes")
                            }
                            .onFailure { error = ctx.strings.t(AccountRules.twoFactorErrorKey(it)) }
                        busy = false
                    }
                }) { Text(t(if (disable) "settings.twoFactorDisable" else "settings.twoFactorNewCodes"), color = if (disable) Yuvomi.colors.danger else MaterialTheme.colorScheme.primary) }
            },
            dismissButton = { TextButton(onClick = { ask = null }) { Text(t("common.cancel")) } },
        )
    }
}

/** "Sign out on other devices" (SET-R15). */
@Composable
private fun OtherSessionsCard(ctx: SectionContext) {
    var confirm by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    SettingsGroup(title = t("settings.otherSessionsTitle"), footer = status ?: t("settings.otherSessionsHint")) {
        ActionRow(t("settings.otherSessionsButton"), danger = true, enabled = ctx.editable) { confirm = true }
    }
    if (confirm) {
        ConfirmDialog(t("settings.otherSessionsConfirm"), t("settings.otherSessionsConfirmDetail"), t("settings.otherSessionsButton"), danger = true, onDismiss = { confirm = false }) {
            confirm = false
            ctx.scope.launch {
                ctx.vm.attempt { it.call(SettingsOperation.LOGOUT_OTHERS) }
                    .onSuccess {
                        val ended = it.obj().int("ended") ?: 0
                        status = if (ended > 0) ctx.strings.t("settings.otherSessionsEnded", "count" to ended) else ctx.strings.t("settings.otherSessionsNone")
                    }
                    .onFailure { ctx.notifyKey(AccountRules.logoutOthersErrorKey(it)) }
            }
        }
    }
}

/** Single sign-on link: linking runs through the provider in the browser; unlinking is native (SET-R16). */
@Composable
private fun OidcCard(ctx: SectionContext, state: JsonObject) {
    var confirm by remember { mutableStateOf(false) }
    SettingsGroup(title = t("settings.oidcLinkTitle")) {
        if (state.bool("linked")) {
            val provider = state.str("provider")
            ValueRow(if (provider != null) t("settings.oidcLinkedWithProvider", "provider" to provider) else t("settings.oidcLinked"), null)
            if (state.bool("can_unlink")) ActionRow(t("settings.oidcUnlink"), danger = true, enabled = ctx.editable) { confirm = true }
            else ValueRow(t("settings.oidcUnlinkNeedsPassword"), null)
        } else {
            ValueRow(t("settings.oidcLinkHint"), null)
            ActionRow(t("settings.oidcLink"), hint = t("android.browser.opensInBrowser")) { ctx.openWeb() }
        }
    }
    if (confirm) {
        ConfirmDialog(t("settings.oidcUnlink"), null, t("settings.oidcUnlink"), danger = true, onDismiss = { confirm = false }) {
            confirm = false
            ctx.run(null) {
                ctx.vm.write("settings.oidcLinkErrorGeneric") { it.call(SettingsOperation.OIDC_UNLINK) }
                    .also { if (it == null) ctx.vm.load(OIDC, force = true) { api -> api.call(SettingsOperation.OIDC_LINK) } }
            }
        }
    }
}

@Composable
fun ConfirmDialog(title: String, detail: String?, confirmLabel: String, danger: Boolean = false, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = detail?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = if (danger) Yuvomi.colors.danger else MaterialTheme.colorScheme.primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}

/** A date as `YYYY-MM-DD`, typed or picked. */
@Composable
fun DateField(label: String, value: String, error: String?, enabled: Boolean, onChange: (String) -> Unit) {
    Field(label, value, { onChange(it.take(10)) }, error, hint = if (error == null) "YYYY-MM-DD" else null, enabled = enabled, keyboard = KeyboardType.Number)
}

internal fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Yuvomi", text))
}

internal fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** A picked picture as upstream's avatar: a square, centred, 256-pixel JPEG data URL (`avatar-crop.js`). */
object AvatarImage {
    private const val OUTPUT = 256
    private const val MAX_FILE_BYTES = 15 * 1024 * 1024
    private val ACCEPTED = setOf("image/png", "image/jpeg", "image/webp")

    sealed interface Result {
        data class Ok(val dataUrl: String) : Result
        data class Problem(val key: String) : Result
    }

    fun encode(context: Context, uri: Uri): Result {
        val type = context.contentResolver.getType(uri)
        if (type != null && type !in ACCEPTED) return Result.Problem("android.settings.imageType")
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            ?: return Result.Problem("android.settings.imageRead")
        if (bytes.size > MAX_FILE_BYTES) return Result.Problem("android.settings.imageType")
        val source: Bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            ?: return Result.Problem("android.settings.imageRead")
        val side = min(source.width, source.height)
        val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val out = ByteArrayOutputStream()
        square.scale(OUTPUT, OUTPUT).compress(Bitmap.CompressFormat.JPEG, 88, out)
        return Result.Ok("data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
    }
}
