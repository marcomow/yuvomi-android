package io.github.marcomow.yuvomi.feature.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.BuildConfig
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.Operation
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Rules of the admin sheets, pure (SET-R70 to R78). */
object AdminRules {
    const val PORT_MIN = 1
    const val PORT_MAX = 65535

    val EMAIL_TEST_FAILURES = mapOf(
        "not_configured" to "email.testFailedNotConfigured", "auth" to "email.testFailedAuth",
        "connection" to "email.testFailedConnection", "tls" to "email.testFailedTls", "rejected" to "email.testFailedRejected",
    )

    /** `emailConfigProblems`: fields locked by the environment are not checked (nor sent). */
    fun emailProblems(port: String, fromAddress: String, env: Set<String>): Map<String, String> = buildMap {
        val n = port.trim().toIntOrNull()
        if ("port" !in env && (n == null || n !in PORT_MIN..PORT_MAX)) put("port", "email.portInvalid")
        if ("fromAddress" !in env && fromAddress.isNotBlank() && !AccountRules.isEmail(fromAddress.trim())) put("fromAddress", "common.emailInvalid")
    }

    /** API token scopes: `module:read`/`module:write` for every module given access; null means full access. */
    fun scopes(full: Boolean, access: Map<String, String>): List<String>? =
        if (full) null else access.filterValues { it == "read" || it == "write" }.map { (k, v) -> "$k:$v" }.sorted()
}

// ── E-mail (admin-email.js) ──────────────────────────────────────────────────

@Composable
fun EmailSection(ctx: SectionContext, openLeaf: (String) -> Unit) {
    val slots by ctx.vm.slots.collectAsState()
    LaunchedEffect(Unit) { ctx.vm.load("email") { it.data(SettingsOperation.EMAIL_CONFIG) } }
    val slot = slots["email"]
    val cfg = slot?.value as? JsonObject ?: run {
        if (slot?.errorKey != null) SettingsGroup(footer = t(slot.errorKey)) { ActionRow(t("settings.retry")) { ctx.vm.load("email", true) { it.data(SettingsOperation.EMAIL_CONFIG) } } }
        else Busy(true)
        return
    }
    val env = cfg.child("envControlled").filterValues { (it as? JsonPrimitive)?.content == "true" }.keys
    var host by rememberSaveable { mutableStateOf(cfg.str("host").orEmpty()) }
    var port by rememberSaveable { mutableStateOf((cfg.int("port") ?: 587).toString()) }
    var secure by rememberSaveable { mutableStateOf(cfg.str("secure") ?: "starttls") }
    var user by rememberSaveable { mutableStateOf(cfg.str("user").orEmpty()) }
    var pass by remember { mutableStateOf("") }
    var from by rememberSaveable { mutableStateOf(cfg.str("fromAddress").orEmpty()) }
    var fromName by rememberSaveable { mutableStateOf(cfg.str("fromName") ?: "Yuvomi") }
    var problems by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var notice by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var noRecipient by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val envHint = t("settings.documentStorageEnvHint")
    fun body() = buildJsonObject {
        if ("host" !in env) put("host", host.trim())
        if ("port" !in env) put("port", port.trim().toIntOrNull() ?: 0)
        if ("secure" !in env) put("secure", secure)
        if ("user" !in env) put("user", user.trim())
        if ("pass" !in env && pass.isNotEmpty()) put("pass", pass)
        if ("fromAddress" !in env) put("fromAddress", from.trim())
        if ("fromName" !in env) put("fromName", fromName.trim())
    }
    suspend fun save(): Boolean {
        problems = AdminRules.emailProblems(port, from, env)
        if (problems.isNotEmpty()) return false
        return ctx.vm.attempt { it.data(SettingsOperation.EMAIL_CONFIG_UPDATE, body = body()) }.fold(
            onSuccess = { pass = ""; ctx.vm.set("email", it); true },
            onFailure = { e ->
                when ((e as? ApiProblem.Http)?.reason) {
                    "email_port_invalid" -> problems = mapOf("port" to "email.portInvalid")
                    "email_from_invalid" -> problems = mapOf("fromAddress" to "common.emailInvalid")
                    else -> notice = ctx.strings.t(ctx.emailKey("email.saveFailed"), "error" to ctx.strings.t(SettingsErrors.key(e))) to false
                }
                false
            },
        )
    }
    SettingsGroup(footer = tOr("email.purposeHint", "android.settings.email.purposeHint")) {
        GroupBody {
            Field(tOr("email.host", "android.settings.email.host"), host, { host = it }, enabled = ctx.editable && "host" !in env, hint = if ("host" in env) envHint else null, keyboard = KeyboardType.Uri)
            Field(tOr("email.port", "android.settings.email.port"), port, { port = it.filter(Char::isDigit).take(5) }, problems["port"]?.let { ctx.strings.t(ctx.emailKey(it), "min" to AdminRules.PORT_MIN, "max" to AdminRules.PORT_MAX) },
                enabled = ctx.editable && "port" !in env, hint = if ("port" in env) envHint else null, keyboard = KeyboardType.Number)
            ChoiceRow(
                tOr("email.security", "android.settings.email.security"),
                listOf("ssl" to tOr("email.securitySsl", "android.settings.email.securitySsl"), "starttls" to tOr("email.securityStarttls", "android.settings.email.securityStarttls"), "none" to tOr("email.securityNone", "android.settings.email.securityNone")),
                secure, ctx.editable && "secure" !in env, if ("secure" in env) envHint else null,
            ) { secure = it }
            Field(tOr("email.user", "android.settings.email.user"), user, { user = it }, enabled = ctx.editable && "user" !in env, hint = if ("user" in env) envHint else null)
            Field(tOr("email.password", "android.settings.email.password"), pass, { pass = it }, password = true, enabled = ctx.editable && "pass" !in env,
                hint = if ("pass" in env) envHint else tOr("email.passwordKeep", "android.settings.email.passwordKeep"))
            Field(tOr("email.fromAddress", "android.settings.email.fromAddress"), from, { from = it }, problems["fromAddress"]?.let { t(it) }, enabled = ctx.editable && "fromAddress" !in env, keyboard = KeyboardType.Email)
            Field(tOr("email.fromName", "android.settings.email.fromName"), fromName, { fromName = it }, enabled = ctx.editable && "fromName" !in env)
            notice?.let { (text, ok) -> Hint(text, if (ok) Yuvomi.colors.success else Yuvomi.colors.danger) }
            if (noRecipient) {
                Hint(tOr("email.testNoRecipient", "android.settings.email.testNoRecipient"), Yuvomi.colors.danger)
                TextButton(onClick = { openLeaf("personal-account") }) { Text(tOr("email.testNoRecipientLink", "android.settings.email.testNoRecipientLink")) }
            }
            if (ctx.editable) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true; notice = null; noRecipient = false
                    ctx.scope.launch {
                        if (save()) {
                            ctx.vm.attempt { it.data(SettingsOperation.EMAIL_TEST, idempotencyKey = YuvomiClient.newIdempotencyKey()) }
                                .onSuccess { r ->
                                    val o = r.obj()
                                    notice = if (o.bool("ok")) ctx.strings.t(ctx.emailKey("email.testSuccess")) to true
                                    else ctx.strings.t(ctx.emailKey(AdminRules.EMAIL_TEST_FAILURES[o.str("reason")] ?: "email.testFailedUnknown")) to false
                                }
                                .onFailure { e ->
                                    if ((e as? ApiProblem.Http)?.reason == "email_no_recipient") noRecipient = true
                                    else notice = ctx.strings.t(ctx.emailKey("email.testFailed"), "error" to ctx.strings.t(SettingsErrors.key(e))) to false
                                }
                        }
                        busy = false
                    }
                }) { Text(tOr("email.test", "android.settings.email.test")) }
                Button(enabled = !busy, onClick = {
                    busy = true; notice = null
                    ctx.scope.launch { if (save()) notice = ctx.strings.t(ctx.emailKey("email.saved")) to true; busy = false }
                }) { Text(tOr("email.save", "android.settings.email.save")) }
            }
        }
    }
}

/** The `email` namespace is not shipped yet: our English copy stands in (see the spec, "Needs"). */
private fun SectionContext.emailKey(key: String): String = if (strings.has(key)) key else "android.settings.$key"

// ── Backup (admin-backup.js) ─────────────────────────────────────────────────

@Composable
fun BackupSection(ctx: SectionContext) {
    val context = LocalContext.current
    val slots by ctx.vm.slots.collectAsState()
    LaunchedEffect(Unit) {
        ctx.vm.load("backup-status") { it.data(SettingsOperation.BACKUP_STATUS) }
        ctx.vm.load("webdav") { it.data(SettingsOperation.WEBDAV_CONFIG) }
    }
    var busy by remember { mutableStateOf(false) }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingBytes
        pendingBytes = null
        if (uri != null && bytes != null) ctx.scope.launch {
            val ok = withContext(Dispatchers.IO) { writeTo(context, uri, bytes) }
            ctx.notifyKey(if (ok) "android.settings.backupSaved" else "common.errorGeneric")
        }
    }
    val scheduler = slots["backup-status"]?.value?.obj()?.child("scheduler")
    SettingsGroup(title = t("settings.backupDownloadTitle"), footer = t("settings.backupDownloadHint")) {
        ActionRow(t("settings.backupDownloadButton"), enabled = !busy && ctx.editable) {
            busy = true
            ctx.scope.launch {
                ctx.vm.attempt { it.bytes(SettingsOperation.BACKUP_DATABASE) }
                    .onSuccess { pendingBytes = it; saver.launch("yuvomi-backup-${ctx.shell.time.today()}.db") }
                    .onFailure { ctx.notifyKey(SettingsErrors.key(it)) }
                busy = false
            }
        }
    }
    SettingsGroup(title = t("settings.backupRestoreTitle"), footer = t("settings.backupRestoreHint")) {
        ActionRow(t("settings.backupRestoreButton"), hint = t("android.browser.opensInBrowser")) { ctx.openWeb() }
    }
    if (scheduler != null) {
        SettingsGroup(title = t("settings.backupSchedulerTitle"), footer = t("settings.backupSchedulerHint")) {
            ValueRow(t("settings.backupSchedulerStatus"), t(if (scheduler.bool("enabled")) "settings.backupSchedulerEnabled" else "settings.backupSchedulerDisabled"))
            if (scheduler.bool("enabled")) {
                ValueRow(t("settings.backupSchedulerSchedule"), scheduler.str("schedule"))
                ValueRow(t("settings.backupSchedulerKeep"), ctx.strings.t("settings.backupSchedulerKeepCount", "count" to (scheduler.int("keepCount") ?: 0)))
            }
            val last = scheduler.child("lastBackup")
            val lastText = last.str("timestamp")?.let { ts ->
                ctx.strings.t(if (last.bool("success")) "settings.backupSchedulerLastSuccess" else "settings.backupSchedulerLastFail", "date" to ctx.dateTime(ts))
            } ?: t("settings.backupSchedulerNever")
            ValueRow(t("settings.backupSchedulerLastBackup"), lastText)
            if (ctx.editable) ActionRow(t("settings.backupSchedulerTrigger"), enabled = !busy) {
                busy = true
                ctx.scope.launch {
                    val failed = ctx.vm.write { it.call(SettingsOperation.BACKUP_TRIGGER, idempotencyKey = YuvomiClient.newIdempotencyKey()) }
                    ctx.notifyKey(failed ?: "settings.backupSchedulerTriggeredToast")
                    ctx.vm.load("backup-status", force = true) { it.data(SettingsOperation.BACKUP_STATUS) }
                    busy = false
                }
            }
        }
    }
    (slots["webdav"]?.value as? JsonObject)?.let { WebdavGroup(ctx, it) }
}

private fun writeTo(context: Context, uri: Uri, bytes: ByteArray): Boolean =
    runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null }.getOrDefault(false)

@Composable
private fun WebdavGroup(ctx: SectionContext, cfg: JsonObject) {
    val env = cfg.bool("envControlled")
    var enabled by rememberSaveable { mutableStateOf(cfg.bool("enabled")) }
    var url by rememberSaveable { mutableStateOf(cfg.str("url").orEmpty()) }
    var username by rememberSaveable { mutableStateOf(cfg.str("username").orEmpty()) }
    var password by remember { mutableStateOf("") }
    var path by rememberSaveable { mutableStateOf(cfg.str("remotePath").orEmpty()) }
    var keep by rememberSaveable { mutableStateOf((cfg.int("keep") ?: 7).toString()) }
    var busy by remember { mutableStateOf(false) }
    fun body() = buildJsonObject {
        put("enabled", enabled)
        if (!env) { put("url", url.trim()); put("username", username.trim()) }
        if (password.isNotEmpty()) put("password", password)
        put("remotePath", path.trim())
        put("keep", keep.toIntOrNull() ?: 7)
    }
    fun failure(e: Throwable): String =
        if ((e as? ApiProblem.Http)?.status == 400 && e.serverMessage?.contains("password", true) == true) "settings.backupWebdavPasswordRequired" else SettingsErrors.key(e)
    SettingsGroup(title = t("settings.backupWebdavTitle"), footer = if (env) t("settings.backupWebdavEnvHint") else t("settings.backupWebdavHint")) {
        GroupBody {
            SwitchRow(t("settings.backupWebdavEnabled"), enabled, ctx.editable) { enabled = it }
            Field(t("settings.backupWebdavUrl"), url, { url = it }, enabled = ctx.editable && !env, keyboard = KeyboardType.Uri)
            Field(t("settings.backupWebdavUsername"), username, { username = it }, enabled = ctx.editable && !env)
            Field(t("settings.backupWebdavPassword"), password, { password = it }, password = true, enabled = ctx.editable, hint = if (cfg.str("password") != null) t("settings.backupWebdavPasswordPlaceholder") else null)
            Field(t("settings.backupWebdavPath"), path, { path = it }, enabled = ctx.editable)
            Field(t("settings.backupWebdavKeep"), keep, { keep = it.filter(Char::isDigit).take(3) }, enabled = ctx.editable, keyboard = KeyboardType.Number)
            ValueRow(t("settings.backupWebdavLastUpload"), cfg.str("lastUpload")?.let { ctx.dateTime(it) } ?: t("settings.backupWebdavNeverUploaded"))
            cfg.str("lastError")?.let { Hint(ctx.strings.t("settings.backupWebdavLastError", "error" to it), Yuvomi.colors.danger) }
            if (ctx.editable) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    ctx.scope.launch {
                        ctx.vm.attempt { it.data(SettingsOperation.WEBDAV_TEST, body = body(), idempotencyKey = YuvomiClient.newIdempotencyKey()) }
                            .onSuccess { ctx.notifyKey("settings.backupWebdavTestSuccess") }
                            .onFailure { ctx.notify(ctx.strings.t("settings.backupWebdavTestFailed", "error" to ctx.strings.t(failure(it)))) }
                        busy = false
                    }
                }) { Text(t("settings.backupWebdavTestBtn")) }
                OutlinedButton(enabled = !busy && cfg.bool("configured"), onClick = {
                    busy = true
                    ctx.scope.launch {
                        val failed = ctx.vm.write { it.call(SettingsOperation.WEBDAV_TRIGGER, idempotencyKey = YuvomiClient.newIdempotencyKey()) }
                        ctx.notifyKey(failed ?: "settings.backupWebdavTriggeredToast")
                        ctx.vm.load("webdav", force = true) { it.data(SettingsOperation.WEBDAV_CONFIG) }
                        busy = false
                    }
                }) { Text(t("settings.backupWebdavTriggerBtn")) }
                Button(enabled = !busy, onClick = {
                    busy = true
                    ctx.scope.launch {
                        ctx.vm.attempt { it.data(SettingsOperation.WEBDAV_CONFIG_UPDATE, body = body()) }
                            .onSuccess { password = ""; ctx.vm.set("webdav", it); ctx.notifyKey("settings.backupWebdavSaved") }
                            .onFailure { ctx.notifyKey(failure(it)) }
                        busy = false
                    }
                }) { Text(t("settings.backupWebdavSaveBtn")) }
            }
        }
    }
}

// ── System (admin-system.js) ─────────────────────────────────────────────────

@Composable
fun SystemSection(ctx: SectionContext) {
    val slots by ctx.vm.slots.collectAsState()
    LaunchedEffect(Unit) { ctx.vm.load("version") { it.call(Operation.VERSION) } }
    val prefs = slots[SettingsSheetViewModel.PREFERENCES]?.value as? JsonObject
    val saved = prefs?.str("app_name") ?: "Yuvomi"
    var name by rememberSaveable(saved) { mutableStateOf(saved) }
    SettingsGroup(title = t("settings.appNameTitle"), footer = t("settings.appNameHint")) {
        GroupBody {
            Field(t("settings.appNameLabel"), name, { name = it.take(60) }, enabled = ctx.editable && prefs != null)
            if (ctx.editable) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
                OutlinedButton(onClick = { ctx.save("settings.appNameSavedToast", buildJsonObject { put("app_name", "") }) }) { Text(t("common.reset")) }
                Button(onClick = { ctx.save("settings.appNameSavedToast", buildJsonObject { put("app_name", name.trim()) }) }) { Text(t("common.save")) }
            }
        }
    }
    val info = slots["version"]?.value?.obj()
    SettingsGroup(title = t("settings.systemTitle"), footer = t("settings.systemDescription")) {
        if (info == null) Busy(slots["version"]?.loading == true)
        else {
            info.str("version")?.let { ValueRow(t("settings.systemVersionLabel"), ctx.strings.t("settings.systemVersionValue", "version" to it)) }
            ValueRow(t("settings.systemLicenseLabel"), "MIT")
            ValueRow(t("settings.systemSetupStatusLabel"), t(if (info.bool("setup_required")) "settings.systemSetupRequired" else "settings.systemSetupComplete"))
            ValueRow(t("android.settings.appVersion"), ctx.strings.t("android.account.versions", "app" to BuildConfig.VERSION_NAME, "server" to (info.str("version") ?: "?"), "pin" to BuildConfig.UPSTREAM_PIN))
        }
    }
}

// ── API access (admin-api.js) ────────────────────────────────────────────────

private const val TOKENS = "api-tokens"

@Composable
fun ApiTokensSection(ctx: SectionContext) {
    val slots by ctx.vm.slots.collectAsState()
    LaunchedEffect(Unit) {
        ctx.vm.load(TOKENS) { it.call(SettingsOperation.API_TOKENS) }
        ctx.vm.load("permissions-catalog") { it.data(SettingsOperation.PERMISSIONS_CATALOG) }
    }
    fun reload() = ctx.vm.load(TOKENS, force = true) { it.call(SettingsOperation.API_TOKENS) }
    val answer = slots[TOKENS]?.value?.obj()
    val tokens = answer?.get("data").arr().map { it.obj() }
    val subjects = answer?.get("subjects").arr().map { it.obj() }
    var creating by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<JsonObject, Boolean>?>(null) }
    Hint(t("settings.apiTokensHint"))
    listOf(true, false).forEach { active ->
        val list = tokens.filter { it.bool("active", it.str("revoked_at") == null) == active }
        if (!active && list.isEmpty()) return@forEach
        SettingsGroup(title = t(if (active) "settings.apiTokensCardTitle" else "settings.apiTokensInactiveTitle"), footer = if (active) null else t("settings.apiTokensInactiveHint")) {
            if (answer == null) Busy(true)
            list.forEachIndexed { i, tok ->
                if (i > 0) RowDivider()
                var menu by remember { mutableStateOf(false) }
                val name = tok.str("name").orEmpty()
                ListItem(
                    headlineContent = { Text(name) },
                    supportingContent = {
                        Column {
                            Text(listOfNotNull(tok.str("token_prefix")?.let { ctx.strings.t("settings.apiTokenPrefix", "prefix" to it) }, tok.str("subject_name")).joinToString(" · "), color = Yuvomi.colors.textSecondary)
                            Text(tok.str("last_used_at")?.let { ctx.strings.t("settings.apiTokenLastUsed", "date" to ctx.dateTime(it)) } ?: t("settings.apiTokenNeverUsed"), color = Yuvomi.colors.textSecondary)
                            Text(tok.str("expires_at")?.let { ctx.strings.t(if (active) "settings.apiTokenExpires" else "settings.apiTokenExpired", "date" to ctx.dateTime(it)) } ?: t("settings.apiTokenNeverExpires"), color = Yuvomi.colors.textSecondary)
                        }
                    },
                    trailingContent = if (ctx.editable) ({
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, ctx.strings.t("common.moreActionsNamed", "name" to name)) }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                if (active) DropdownMenuItem(text = { Text(t("settings.apiTokenRevoke")) }, onClick = { menu = false; confirm = tok to true })
                                DropdownMenuItem(
                                    text = { Text(t(if (active) "settings.apiTokenRemoveActive" else "settings.apiTokenRemove"), color = Yuvomi.colors.danger) },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger) },
                                    onClick = { menu = false; confirm = tok to false },
                                )
                            }
                        }
                    }) else null,
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            if (active && ctx.editable && answer != null) {
                if (list.isNotEmpty()) RowDivider()
                ActionRow(t("settings.apiTokenCreate"), icon = { Icon(Icons.Outlined.Add, null) }) { creating = true }
            }
        }
    }
    confirm?.let { (tok, revoke) ->
        val name = tok.str("name").orEmpty()
        ConfirmDialog(
            ctx.strings.t(if (revoke) "settings.apiTokenRevokeConfirm" else "settings.apiTokenRemoveConfirm", "name" to name),
            t(if (revoke) "settings.apiTokenRevokeDetail" else "settings.apiTokenRemoveDetail"),
            t(if (revoke) "settings.apiTokenRevoke" else "settings.apiTokenRemove"), danger = true, onDismiss = { confirm = null },
        ) {
            confirm = null
            ctx.run(if (revoke) "settings.apiTokenRevokedToast" else "settings.apiTokenRemovedToast") {
                ctx.vm.write { api ->
                    val id = mapOf("id" to (tok.int("id") ?: 0))
                    if (revoke) api.call(SettingsOperation.API_TOKEN_REVOKE, id)
                    else api.call(SettingsOperation.API_TOKEN_REMOVE, id, idempotencyKey = YuvomiClient.newIdempotencyKey())
                }.also { if (it == null) reload() }
            }
        }
    }
    if (creating) {
        val modules = (slots["permissions-catalog"]?.value as? JsonObject)?.strings("scopeModuleKeys").orEmpty()
        TokenDialog(ctx, subjects, modules, onDismiss = { creating = false }) { reload() }
    }
}

@Composable
private fun TokenDialog(ctx: SectionContext, subjects: List<JsonObject>, modules: List<String>, onDismiss: () -> Unit, created: () -> Unit) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable { mutableStateOf(ctx.shell.session.user.id.toString()) }
    var until by rememberSaveable { mutableStateOf("") }
    var full by rememberSaveable { mutableStateOf(true) }
    var access by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var token by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("settings.apiTokenCreate")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val shown = token
                if (shown != null) {
                    Text(t("settings.apiTokenCreatedLabel"))
                    SelectionContainer { Text(shown, fontFamily = FontFamily.Monospace) }
                    Hint(t("settings.apiTokenCreatedHint"))
                } else {
                    Field(t("settings.apiTokenNameLabel"), name, { name = it.take(100) }, error)
                    if (subjects.isNotEmpty()) ChoiceRow(t("settings.apiTokenSubjectLabel"), subjects.mapNotNull { s -> s.int("id")?.let { it.toString() to s.str("display_name").orEmpty() } }, subject) { subject = it }
                    DateField(t("settings.apiTokenValidityUntil"), until, null, true) { until = it }
                    Hint(t("settings.apiTokenValidityNeverHint"))
                    SwitchRow(t("settings.apiTokenScopeFull"), full, hint = t("settings.apiTokenScopeHint")) { full = it }
                    if (!full) modules.forEach { key ->
                        ChoiceRow(
                            ModuleRules.labelKey(key)?.let { t(it) } ?: key,
                            listOf("none" to t("settings.permAccessNone"), "read" to t("settings.apiTokenScopeRead"), "write" to t("settings.apiTokenScopeWrite")),
                            access[key] ?: "none",
                        ) { access = access + (key to it) }
                    }
                }
            }
        },
        confirmButton = {
            val shown = token
            if (shown != null) TextButton(onClick = { copy(context, shown); ctx.notifyKey("settings.apiTokenCopied") }) { Text(t("settings.apiTokenCopy")) }
            else TextButton(enabled = !busy, onClick = {
                if (name.isBlank()) { error = ctx.strings.t("common.required"); return@TextButton }
                val scopes = AdminRules.scopes(full, access)
                if (scopes != null && scopes.isEmpty()) { error = ctx.strings.t("settings.apiTokenScopeRequired"); return@TextButton }
                busy = true
                ctx.scope.launch {
                    ctx.vm.attempt { api ->
                        api.call(SettingsOperation.API_TOKEN_CREATE, idempotencyKey = YuvomiClient.newIdempotencyKey(), body = buildJsonObject {
                            put("name", name.trim())
                            subject.toIntOrNull()?.let { put("subject_user_id", it) }
                            if (until.isNotBlank()) put("expires_at", until.trim() + "T23:59:59") else put("expires_at", JsonNull)
                            if (scopes == null) put("scopes", JsonNull) else put("scopes", JsonArray(scopes.map(::JsonPrimitive)))
                        })
                    }.onSuccess { d ->
                        token = d.obj().str("token")
                        ctx.notifyKey("settings.apiTokenCreatedToast")
                        created()
                    }.onFailure { error = ctx.strings.t(SettingsErrors.key(it)) }
                    busy = false
                }
            }) { Text(t("settings.apiTokenCreate")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(if (token != null) "settings.invites.done" else "common.cancel")) } },
    )
}
