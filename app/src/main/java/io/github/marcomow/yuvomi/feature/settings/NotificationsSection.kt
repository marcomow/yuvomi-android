package io.github.marcomow.yuvomi.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.t
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The channel form of `notifications.js` (`channelDefaults`, `readChannelForm`), pure. */
data class ChannelForm(
    val provider: String = "gotify",
    val name: String = "",
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val gotifyPriority: String = "5",
    val gotifyToken: String = "",
    val ntfyTopic: String = "",
    val ntfyPriority: String = "default",
    val ntfyAuth: String = "none",
    val ntfyToken: String = "",
    val ntfyUsername: String = "",
    val ntfyPassword: String = "",
    val webhookTemplate: String = "",
    val webhookToken: String = "",
    val emailTo: String = "",
) {
    val usesBaseUrl: Boolean get() = provider != "email"

    /** `readChannelForm`: only what the provider knows; empty secrets keep the stored ones. */
    fun body(): JsonObject = buildJsonObject {
        put("provider", provider)
        put("name", name.trim())
        put("enabled", enabled)
        val secrets = buildMap {
            when (provider) {
                "ntfy" -> {
                    if (ntfyAuth == "token" && ntfyToken.isNotEmpty()) put("token", ntfyToken)
                    if (ntfyAuth == "basic") {
                        if (ntfyUsername.isNotEmpty()) put("username", ntfyUsername)
                        if (ntfyPassword.isNotEmpty()) put("password", ntfyPassword)
                    }
                }
                "webhook" -> if (webhookToken.isNotEmpty()) put("token", webhookToken)
                "gotify" -> if (gotifyToken.isNotEmpty()) put("appToken", gotifyToken)
            }
        }
        putJsonObject("config") {
            if (usesBaseUrl) put("baseUrl", baseUrl.trim())
            when (provider) {
                "email" -> put("toAddress", emailTo.trim())
                "ntfy" -> { put("topic", ntfyTopic.trim()); put("priority", ntfyPriority); put("authType", ntfyAuth) }
                "webhook" -> put("payloadTemplate", webhookTemplate.trim())
                else -> put("priority", gotifyPriority.toIntOrNull() ?: 5)
            }
        }
        if (secrets.isNotEmpty()) putJsonObject("secrets") { secrets.forEach { (k, v) -> put(k, v) } }
    }

    companion object {
        val PROVIDERS = listOf("gotify", "ntfy", "webhook", "email")
        val NTFY_PRIORITIES = listOf("min", "low", "default", "high", "urgent")

        fun of(channel: JsonObject): ChannelForm {
            val c = channel.child("config")
            return ChannelForm(
                provider = channel.str("provider") ?: "gotify",
                name = channel.str("name").orEmpty(),
                enabled = channel.bool("enabled"),
                baseUrl = c.str("baseUrl").orEmpty(),
                gotifyPriority = (c.int("priority") ?: 5).toString(),
                ntfyTopic = c.str("topic").orEmpty(),
                ntfyPriority = c.str("priority")?.takeIf { it in NTFY_PRIORITIES } ?: "default",
                ntfyAuth = c.str("authType") ?: "none",
                webhookTemplate = c.str("payloadTemplate").orEmpty(),
                emailTo = c.str("toAddress").orEmpty(),
            )
        }
    }
}

private const val CHANNELS = "notification-channels"
private const val PROVIDERS = "notification-providers"

/** `notifications.js`: the household's channels (admins). Web push is a browser feature (ADR 0005). */
@Composable
fun NotificationsSection(ctx: SectionContext) {
    SettingsGroup(title = t("settings.pushToggleTitle"), footer = t("android.settings.pushInBrowser")) {
        ActionRow(t("android.browser.openWebApp"), hint = t("android.browser.opensInBrowser")) { ctx.openWeb() }
    }
    if (!ctx.isAdmin) {
        SettingsGroup(title = t("settings.notificationChannelsTitle"), footer = t("settings.notificationChannelAdminOnlyHint")) {}
        return
    }
    val slots by ctx.vm.slots.collectAsState()
    LaunchedEffect(Unit) {
        ctx.vm.load(CHANNELS) { it.data(SettingsOperation.NOTIFICATION_CHANNELS) }
        ctx.vm.load(PROVIDERS) { it.data(SettingsOperation.NOTIFICATION_PROVIDERS) }
    }
    fun reload() = ctx.vm.load(CHANNELS, force = true) { it.data(SettingsOperation.NOTIFICATION_CHANNELS) }
    val channelSlot = slots[CHANNELS]
    val channels = channelSlot?.value.arr().map { it.obj() }
    val providers = slots[PROVIDERS]?.value.arr().map { it.obj() }
    var editing by remember { mutableStateOf<JsonObject?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<JsonObject?>(null) }

    SettingsGroup(title = t("settings.notificationChannelsTitle"), footer = t("settings.notificationChannelsDescription")) {
        when {
            channelSlot?.errorKey != null -> ActionRow(t("settings.retry"), hint = t(channelSlot.errorKey)) { reload() }
            channelSlot?.value == null -> Busy(true)
            channels.isEmpty() -> ValueRow(t("settings.notificationChannelEmpty"), null)
        }
        channels.forEachIndexed { i, ch ->
            if (i > 0) RowDivider()
            val id = ch.int("id") ?: 0
            val name = ch.str("name")?.takeIf { it.isNotBlank() } ?: ch.str("provider").orEmpty()
            var menu by remember { mutableStateOf(false) }
            ListItem(
                headlineContent = { Text(name) },
                supportingContent = {
                    Column {
                        Text(providers.firstOrNull { it.str("id") == ch.str("provider") }?.str("name") ?: ch.str("provider").orEmpty(), color = Yuvomi.colors.textSecondary)
                        ch.str("lastError")?.let { Text(ctx.strings.t("settings.notificationChannelError", "error" to it), color = Yuvomi.colors.danger) }
                    }
                },
                trailingContent = {
                    Box {
                        androidx.compose.foundation.layout.Row {
                            Switch(ch.bool("enabled"), enabled = ctx.editable, onCheckedChange = { on ->
                                ctx.run("settings.notificationChannelSaved") {
                                    ctx.vm.write("settings.preferenceSaveFailed") { it.call(SettingsOperation.NOTIFICATION_CHANNEL_UPDATE, mapOf("id" to id), body = buildJsonObject { put("enabled", on) }) }
                                        .also { if (it == null) reload() }
                                }
                            })
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, ctx.strings.t("common.moreActionsNamed", "name" to name)) }
                        }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(ctx.strings.t("common.editNamed", "name" to name)) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; editing = ch })
                            DropdownMenuItem(text = { Text(t("settings.notificationChannelTest")) }, leadingIcon = { Icon(Icons.Outlined.Send, null) }, onClick = {
                                menu = false
                                ctx.run("settings.notificationChannelTestSent") {
                                    ctx.vm.write { it.call(SettingsOperation.NOTIFICATION_CHANNEL_TEST, mapOf("id" to id), idempotencyKey = YuvomiClient.newIdempotencyKey()) }
                                        .also { reload() }
                                }
                            })
                            DropdownMenuItem(
                                text = { Text(t("settings.notificationChannelDelete"), color = Yuvomi.colors.danger) },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger) },
                                onClick = { menu = false; deleting = ch },
                            )
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        if (ctx.editable && channelSlot?.value != null) {
            if (channels.isNotEmpty()) RowDivider()
            ActionRow(t("settings.notificationChannelAdd"), icon = { Icon(Icons.Outlined.Add, null) }) { adding = true }
        }
    }

    if (adding || editing != null) {
        val emailReady = providers.firstOrNull { it.str("id") == "email" }?.bool("ready", true) ?: true
        ChannelDialog(ctx, editing, providers.mapNotNull { p -> p.str("id")?.let { it to (p.str("name") ?: it) } }.ifEmpty { ChannelForm.PROVIDERS.map { it to it } }, emailReady,
            onDismiss = { adding = false; editing = null }) { adding = false; editing = null; reload() }
    }
    deleting?.let { ch ->
        ConfirmDialog(ctx.strings.t("settings.notificationChannelDeleteConfirm", "name" to (ch.str("name") ?: "")), t("settings.notificationChannelDeleteConfirmDetail"), t("settings.notificationChannelDelete"), danger = true, onDismiss = { deleting = null }) {
            deleting = null
            ctx.run("settings.notificationChannelDeleted") {
                ctx.vm.write { it.call(SettingsOperation.NOTIFICATION_CHANNEL_DELETE, mapOf("id" to (ch.int("id") ?: 0))) }.also { if (it == null) reload() }
            }
        }
    }
}

@Composable
private fun ChannelDialog(ctx: SectionContext, channel: JsonObject?, providers: List<Pair<String, String>>, emailReady: Boolean, onDismiss: () -> Unit, saved: () -> Unit) {
    var form by remember { mutableStateOf(channel?.let(ChannelForm::of) ?: ChannelForm()) }
    var busy by remember { mutableStateOf(false) }
    val secretSet = channel?.bool("secretSet") == true
    val keep = if (secretSet) t("settings.notificationChannelSecretKeep") else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(if (channel == null) "settings.notificationChannelAdd" else "settings.notificationChannelSave")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (channel == null) ChoiceRow(t("settings.notificationChannelProvider"), providers, form.provider) { form = ChannelForm(provider = it, name = form.name) }
                Field(t("settings.notificationChannelName"), form.name, { form = form.copy(name = it) })
                SwitchRow(t("settings.notificationChannelEnabled"), form.enabled) { form = form.copy(enabled = it) }
                if (form.usesBaseUrl) Field(t("settings.notificationChannelBaseUrl"), form.baseUrl, { form = form.copy(baseUrl = it) }, keyboard = KeyboardType.Uri)
                when (form.provider) {
                    "email" -> {
                        Field(t("settings.notificationChannelEmailTo"), form.emailTo, { form = form.copy(emailTo = it) }, hint = t("settings.notificationChannelEmailToHint"), keyboard = KeyboardType.Email)
                        if (!emailReady) Hint(t("settings.notificationChannelEmailNotConfigured"), Yuvomi.colors.warning)
                    }
                    "ntfy" -> {
                        Field(t("settings.notificationChannelNtfyTopic"), form.ntfyTopic, { form = form.copy(ntfyTopic = it) })
                        ChoiceRow(t("settings.notificationChannelNtfyPriority"), ChannelForm.NTFY_PRIORITIES.map { it to it }, form.ntfyPriority) { form = form.copy(ntfyPriority = it) }
                        ChoiceRow(t("settings.notificationChannelNtfyAuth"), listOf("none" to t("settings.notificationChannelNtfyAuthNone"), "token" to t("settings.notificationChannelNtfyAuthToken"), "basic" to t("settings.notificationChannelNtfyAuthBasic")), form.ntfyAuth) { form = form.copy(ntfyAuth = it) }
                        if (form.ntfyAuth == "token") Field(t("settings.notificationChannelNtfyToken"), form.ntfyToken, { form = form.copy(ntfyToken = it) }, hint = keep, password = true)
                        if (form.ntfyAuth == "basic") {
                            Field(t("settings.notificationChannelNtfyUsername"), form.ntfyUsername, { form = form.copy(ntfyUsername = it) }, hint = keep)
                            Field(t("settings.notificationChannelNtfyPassword"), form.ntfyPassword, { form = form.copy(ntfyPassword = it) }, hint = keep, password = true)
                        }
                    }
                    "webhook" -> {
                        Field(t("settings.notificationChannelWebhookToken"), form.webhookToken, { form = form.copy(webhookToken = it) }, hint = keep, password = true)
                        Field(t("settings.notificationChannelWebhookTemplate"), form.webhookTemplate, { form = form.copy(webhookTemplate = it) }, hint = t("settings.notificationChannelWebhookTemplateHint"), singleLine = false)
                    }
                    else -> {
                        Field(t("settings.notificationChannelGotifyToken"), form.gotifyToken, { form = form.copy(gotifyToken = it) }, hint = keep, password = true)
                        Field(t("settings.notificationChannelGotifyPriority"), form.gotifyPriority, { form = form.copy(gotifyPriority = it.filter(Char::isDigit).take(2)) }, keyboard = KeyboardType.Number)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                ctx.scope.launch {
                    val failed = ctx.vm.write("settings.preferenceSaveFailed") { api ->
                        if (channel == null) api.call(SettingsOperation.NOTIFICATION_CHANNEL_CREATE, body = form.body(), idempotencyKey = YuvomiClient.newIdempotencyKey())
                        else api.call(SettingsOperation.NOTIFICATION_CHANNEL_UPDATE, mapOf("id" to (channel.int("id") ?: 0)), body = form.body())
                    }
                    busy = false
                    if (failed == null) { ctx.notifyKey("settings.notificationChannelSaved"); saved() } else ctx.notifyKey(failed)
                }
            }) { Text(t("settings.notificationChannelSave")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}
