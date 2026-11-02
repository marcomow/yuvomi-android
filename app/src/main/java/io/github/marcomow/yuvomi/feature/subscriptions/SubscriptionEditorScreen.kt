package io.github.marcomow.yuvomi.feature.subscriptions

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** docs/spec/subscriptions.md, the editor (SUBS-R17 to R24): `openSubscriptionModal()` as a screen. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SubscriptionEditorScreen(id: Int?, close: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val store = remember(shell.connection) { SubscriptionsStore.of(shell.container) } ?: return
    val s by store.state.collectAsState()
    val existing = id?.let { s.subscription(it) }
    if (id != null && existing == null) {
        if (s.loaded) close() else LoadingBox()
        return
    }
    val scope = rememberCoroutineScope { kotlinx.coroutines.Dispatchers.Main.immediate }
    val context = LocalContext.current
    var form by remember(existing?.id) {
        mutableStateOf(existing?.let { SubscriptionForm.of(it, strings.locale) } ?: SubscriptionForm.blank(s.settings, shell.time.today()))
    }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var advanced by remember { mutableStateOf(SubscriptionForm.advancedOpen(existing, s.meta)) }
    var saving by remember { mutableStateOf(false) }
    var logoSearch by remember { mutableStateOf(false) }
    val idempotencyKey = remember { YuvomiClient.newIdempotencyKey() }
    fun edit(change: (SubscriptionForm) -> SubscriptionForm) { form = change(form) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val r = withContext(Dispatchers.IO) { readLogo(context, uri) }) {
                is LogoRead.Ok -> edit { it.copy(logoData = r.dataUrl) }
                is LogoRead.Problem -> shell.notify(strings.t(r.key))
            }
        }
    }

    fun save() {
        val found = SubscriptionFormRules.errors(form, existing, strings)
        errors = found
        if (found.isNotEmpty()) {
            scope.launch { shell.notify(found.values.first()) }
            return
        }
        saving = true
        scope.launch {
            try {
                store.save(existing, SubscriptionFormRules.body(form, existing, s.meta, strings.locale), idempotencyKey)
                close()
                shell.notify(strings.t(if (existing == null) "subscriptions.addedToast" else "subscriptions.savedToast"))
            } catch (e: ApiProblem) {
                shell.notify(strings.t(SubscriptionErrors.key(e)))
                when (SubscriptionErrors.reason(e)) {
                    SubscriptionErrors.SELECTED_CATEGORY_GONE -> edit { it.copy(categoryId = null) }
                    SubscriptionErrors.SELECTED_METHOD_GONE -> edit { it.copy(paymentMethodId = null) }
                    SubscriptionErrors.ACCOUNT_GONE -> { store.load(); edit { it.copy(accountId = null) } }
                    SubscriptionErrors.SUBSCRIPTION_GONE -> close()
                }
            } finally {
                saving = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(if (existing == null) "subscriptions.addTitle" else "subscriptions.editTitle")) },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Outlined.Close, t("common.cancel")) } },
                actions = {
                    if (existing != null) {
                        IconButton(onClick = {
                            close()
                            store.scheduleDelete(existing, strings) { m, a -> shell.notify(m, a) }
                        }) { Icon(Icons.Outlined.Delete, strings.t("common.deleteNamed", "name" to existing.name), tint = Yuvomi.colors.danger) }
                    }
                    TextButton(onClick = ::save, enabled = !saving) { Text(t(if (existing == null) "common.add" else "common.save")) }
                },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Identity: logo, name, description.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.clickable { picker.launch("image/*") }.semantics { contentDescription = strings.t("subscriptions.logoLabel") },
                ) { BrandTile(form.name, form.logoData, parseHex(form.brandColor, Yuvomi.colors.module("budget")), size = 56.dp) }
                Spacer(Modifier.width(12.dp))
                Column {
                    OutlinedButton(onClick = { logoSearch = true }) { Text(t("subscriptions.findLogo")) }
                    if (form.logoData != null) TextButton(onClick = { edit { it.copy(logoData = null) } }) { Text(t("android.subscriptions.removeLogo")) }
                }
            }
            Field(t("subscriptions.nameLabel") + " *", form.name, errors[SubscriptionFormRules.NAME]) { v -> edit { it.copy(name = v.take(200)) } }
            Field(t("subscriptions.descriptionLabel"), form.description) { v -> edit { it.copy(description = v) } }

            SectionTitle(t("subscriptions.billingDetails"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(t("subscriptions.amountLabel") + " *", form.amount, errors[SubscriptionFormRules.AMOUNT], Modifier.weight(1f), KeyboardType.Decimal) { v -> edit { it.copy(amount = v) } }
                ChoiceField(t("subscriptions.currencyLabel"), form.currency, BudgetMoney.CURRENCY_CODES.map { it to it }, Modifier.width(120.dp)) { v -> edit { it.copy(currency = v) } }
            }
            Text(t("subscriptions.billingCycleLabel"), style = MaterialTheme.typography.labelLarge)
            val count = form.interval.toIntOrNull() ?: 1
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Field(SubscriptionRules.cycleSentenceParts(strings, form.cycle, count).first, form.interval, errors[SubscriptionFormRules.INTERVAL], Modifier.width(110.dp), KeyboardType.Number) { v -> edit { it.copy(interval = v.filter(Char::isDigit).take(3)) } }
                ChoiceField(
                    t("subscriptions.billingCycleLabel"),
                    form.cycle,
                    s.meta.billingCycles.filter { it in SubscriptionRules.UNIT_SENTENCE_KEYS }.map { it to SubscriptionRules.cycleUnitWord(strings, it, count) },
                    Modifier.weight(1f),
                ) { v -> edit { it.copy(cycle = v) } }
            }
            DateField(t("subscriptions.nextPaymentLabel") + " *", form.nextDate, error = errors[SubscriptionFormRules.NEXT_DATE]) { v -> edit { it.copy(nextDate = v) } }
            if (existing?.isCompleted == true) Hint(t("subscriptions.nextPaymentCompletedHint"))
            if (s.meta.accounts.isNotEmpty()) {
                val options = listOf<Pair<Int?, String>>(null to t("budget.noAccount")) +
                    SubscriptionFormRules.accountOptions(s.meta, form.accountId).map { a ->
                        (a.id as Int?) to if (a.archived) strings.t("subscriptions.accountArchivedOption", "name" to a.name) else a.name
                    }
                ChoiceField(t("budget.accountLabel"), form.accountId, options) { v -> edit { it.copy(accountId = v) } }
                Hint(t("subscriptions.accountHint"))
                if (s.meta.accounts.any { it.id == form.accountId && it.archived }) Hint(t("subscriptions.accountArchivedHint"))
            }

            SectionTitle(t("subscriptions.renewalDetails"))
            SwitchRow(t("subscriptions.reminderEnabledLabel"), form.reminderEnabled) { v -> edit { it.copy(reminderEnabled = v) } }
            if (form.reminderEnabled) Field(t("subscriptions.reminderDaysLabel"), form.reminderDays, errors[SubscriptionFormRules.REMINDER], keyboard = KeyboardType.Number) { v -> edit { it.copy(reminderDays = v.filter(Char::isDigit).take(3)) } }
            ChoiceField(
                t("subscriptions.endLabel"),
                form.endType,
                listOf(
                    Subscription.END_NEVER to t("subscriptions.endNever"),
                    Subscription.END_ON_DATE to t("subscriptions.endOnDate"),
                    Subscription.END_AFTER_COUNT to t("subscriptions.endAfterCount"),
                ),
            ) { v -> edit { it.copy(endType = v) } }
            when (form.endType) {
                Subscription.END_ON_DATE -> DateField(t("subscriptions.endDateLabel"), form.endDate, error = errors[SubscriptionFormRules.END_DATE]) { v -> edit { it.copy(endDate = v) } }
                Subscription.END_AFTER_COUNT -> Field(t("subscriptions.endCountLabel"), form.endCount, errors[SubscriptionFormRules.END_COUNT], keyboard = KeyboardType.Number) { v -> edit { it.copy(endCount = v.filter(Char::isDigit).take(4)) } }
            }

            Row(Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("android.subscriptions.moreDetails"), style = MaterialTheme.typography.titleSmall)
                    Text(t("subscriptions.moreDetailsHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }
                Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (advanced) {
                SectionTitle(t("subscriptions.organizationDetails"))
                if (s.meta.personal) {
                    ChoiceField(t("budget.visibilityLabel"), form.visibility, listOf("private" to t("budget.visibility_private"), "shared" to t("budget.visibility_shared"))) { v -> edit { it.copy(visibility = v) } }
                    Hint(strings.t(if (form.visibility == "private") "subscriptions.visibilityHint_private" else "subscriptions.visibilityHint_shared"))
                }
                ChoiceField(
                    t("subscriptions.categoryLabel"),
                    form.categoryId,
                    listOf<Pair<Int?, String>>(null to t("subscriptions.uncategorized")) + s.meta.categories.map { it.id to SubscriptionRules.categoryLabel(strings, it) },
                ) { v -> edit { it.copy(categoryId = v) } }
                ChoiceField(
                    t("subscriptions.paymentMethodLabel"),
                    form.paymentMethodId,
                    listOf<Pair<Int?, String>>(null to t("subscriptions.unspecified")) + s.meta.paymentMethods.map { it.id to SubscriptionRules.methodLabel(strings, it) },
                ) { v -> edit { it.copy(paymentMethodId = v) } }
                Text(t("subscriptions.brandColorLabel"), style = MaterialTheme.typography.labelLarge)
                ColorRow(form.brandColor) { v -> edit { it.copy(brandColor = v) } }
                SectionTitle(t("subscriptions.serviceDetails"))
                Field(t("subscriptions.accountUsernameLabel"), form.accountUsername) { v -> edit { it.copy(accountUsername = v.take(200)) } }
                Hint(t("subscriptions.accountUsernameHint"))
                Field(t("subscriptions.notesLabel"), form.notes, singleLine = false) { v -> edit { it.copy(notes = v) } }
                SwitchRow(t("subscriptions.enabledLabel"), form.enabled) { v -> edit { it.copy(enabled = v) } }
                Hint(t("subscriptions.enabledHint"))
            }
        }
    }

    if (logoSearch) LogoSearchDialog(store, existing?.websiteUrl ?: form.name.trim(), close = { logoSearch = false }) { data ->
        logoSearch = false
        edit { it.copy(logoData = data) }
        scope.launch { shell.notify(strings.t("subscriptions.logoFound")) }
    }
}

@Composable
internal fun Field(
    label: String,
    value: String,
    error: String? = null,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier,
    )
}

@Composable
internal fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

/** `openLogoPickerModal()`: search a site or a name, pick one of the logos found. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogoSearchDialog(store: SubscriptionsStore, initial: String, close: () -> Unit, pick: (String) -> Unit) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope { kotlinx.coroutines.Dispatchers.Main.immediate }
    var query by remember { mutableStateOf(initial) }
    var options by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(strings.t("subscriptions.findLogoHint")) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(t("subscriptions.logoSearchTitle")) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text(t("subscriptions.logoSearchLabel")) }, placeholder = { Text(t("subscriptions.logoSearchPlaceholder")) }, singleLine = true)
                TextButton(enabled = !busy && query.isNotBlank(), onClick = {
                    busy = true
                    status = strings.t("subscriptions.logoSearching")
                    scope.launch {
                        val (found, key) = store.findLogos(query.trim())
                        options = found
                        status = key?.let { strings.t(it) }
                        busy = false
                    }
                }) { Text(t("subscriptions.searchLogo")) }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { data ->
                        val image = remember(data) { LogoImages.decode(data) }
                        Column(
                            Modifier.size(64.dp).clickable { pick(data) }.semantics { contentDescription = strings.t("subscriptions.useLogo") },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (image != null) Image(image, null, Modifier.size(48.dp)) else Text("SVG")
                            Text(t("subscriptions.logoSourceWebsite"), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(t("common.close")) } },
    )
}

private sealed interface LogoRead {
    data class Ok(val dataUrl: String) : LogoRead
    data class Problem(val key: String) : LogoRead
}

/** `fileToDataUrl()`: type and size first, then the file as a data URL. */
private fun readLogo(context: Context, uri: Uri): LogoRead {
    val mime = context.contentResolver.getType(uri)
    val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        ?: return LogoRead.Problem("documents.fileReadError")
    SubscriptionFormRules.logoProblem(mime, bytes.size.toLong())?.let { return LogoRead.Problem(it) }
    return LogoRead.Ok("data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
}
