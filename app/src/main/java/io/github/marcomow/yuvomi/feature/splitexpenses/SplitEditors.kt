package io.github.marcomow.yuvomi.feature.splitexpenses

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.subscriptions.BudgetMoney
import io.github.marcomow.yuvomi.feature.subscriptions.ChoiceField
import io.github.marcomow.yuvomi.feature.subscriptions.DateField
import io.github.marcomow.yuvomi.feature.subscriptions.Field
import io.github.marcomow.yuvomi.feature.subscriptions.Hint
import io.github.marcomow.yuvomi.feature.subscriptions.SectionTitle
import io.github.marcomow.yuvomi.feature.subscriptions.budgetReadOnly
import io.github.marcomow.yuvomi.feature.subscriptions.dateText
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScaffold(title: String, close: () -> Unit, saveLabel: String, saving: Boolean, save: () -> Unit, delete: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Outlined.Close, t("common.cancel")) } },
                actions = {
                    if (delete != null) IconButton(onClick = delete) { Icon(Icons.Outlined.Delete, t("common.delete"), tint = Yuvomi.colors.danger) }
                    TextButton(onClick = save, enabled = !saving) { Text(saveLabel) }
                },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** The fields an expense and a series share: amount, title, date, payer, split, category, participants, notes. */
@Composable
private fun SplitFields(f: ExpenseForm, s: SplitStore.State, errors: Map<String, String>, recurring: Boolean, formerPayer: Pair<Int, String>? = null, onChange: (ExpenseForm) -> Unit) {
    val strings = LocalStrings.current
    val people = s.people
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(t("splitExpenses.amount") + " *", f.amount, errors[SplitFormRules.AMOUNT], Modifier.weight(1f), KeyboardType.Decimal) { onChange(f.copy(amount = it)) }
        ChoiceField(t("splitExpenses.currency"), f.currency, s.data.meta.currencies.ifEmpty { BudgetMoney.CURRENCY_CODES }.map { it to it }, Modifier.width(120.dp)) { onChange(f.copy(currency = it)) }
    }
    Field(t("splitExpenses.titleLabel") + " *", f.title, errors[SplitFormRules.TITLE]) { onChange(f.copy(title = it.take(200))) }
    if (recurring) {
        ChoiceField(t("budget.recurringIntervalLabel"), f.frequency, s.data.meta.frequencies.map { it to strings.t(SplitRules.FREQUENCY_KEYS[it] ?: "budget.intervalMonthly") }) { onChange(f.copy(frequency = it)) }
        DateField(t("splitExpenses.recurring.nextDate"), f.date) { onChange(f.copy(date = it)) }
    } else {
        DateField(t("splitExpenses.date"), f.date) { onChange(f.copy(date = it)) }
    }
    val payers = listOfNotNull(formerPayer?.let { (it.first as Int?) to "${it.second} (${strings.t("settings.memberFormerBadge")})" }) + people.map { (it.uid as Int?) to it.displayName }
    ChoiceField(t("splitExpenses.paidBy"), f.payerId, payers) { onChange(f.copy(payerId = it)) }
    ChoiceField(t("splitExpenses.splitMethod"), f.method, SplitRules.METHODS.map { it to strings.t(SplitRules.methodKey(it)) }) { onChange(f.copy(method = it)) }
    val cats = s.data.budgetCategories.map { it.key }.let { if (f.budgetCategory != null && f.budgetCategory !in it) it + f.budgetCategory else it }
    ChoiceField(t("splitExpenses.categoryLabel"), f.budgetCategory, listOf<Pair<String?, String>>(null to strings.t("splitExpenses.noCategory")) + cats.map { it to SplitRules.categoryLabel(strings, it, s.data.budgetCategories) }) { onChange(f.copy(budgetCategory = it)) }
    SectionTitle(t("splitExpenses.participants") + " *")
    people.forEach { p ->
        val on = p.uid in f.participants
        Row(Modifier.fillMaxWidth().clickable { onChange(f.copy(participants = if (on) f.participants - p.uid else f.participants + p.uid)) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(on, { onChange(f.copy(participants = if (on) f.participants - p.uid else f.participants + p.uid)) })
            MemberAvatar(p.displayName, p.avatarColor, null, size = 28.dp)
            Spacer(Modifier.width(8.dp))
            Text(p.displayName, modifier = Modifier.weight(1f))
            if (f.method != SplitRules.EQUAL && on) {
                Field(
                    "${p.displayName} ${strings.t("splitExpenses.splitValue")}", f.values[p.uid].orEmpty(), modifier = Modifier.width(120.dp), keyboard = KeyboardType.Decimal,
                ) { onChange(f.copy(values = f.values + (p.uid to it))) }
            }
        }
    }
    val check = SplitRules.validate(strings, f.method, f.amount, f.currency, f.participants, f.values)
    Text(errors[SplitFormRules.SPLIT] ?: check.hint, style = MaterialTheme.typography.bodySmall, color = if (errors[SplitFormRules.SPLIT] != null) Yuvomi.colors.danger else Yuvomi.colors.textSecondary)
    Field(t("splitExpenses.notes"), f.description, singleLine = false) { onChange(f.copy(description = it)) }
}

/** `openExpenseModal()`, as a screen (SPLIT-R8 to R11). */
@Composable
fun ExpenseEditorScreen(id: Int?, prefill: SplitPrefill?, close: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val store = remember(shell.connection) { SplitStore.of(shell.container) } ?: return
    val s by store.state.collectAsState()
    val group = s.group
    val existing = id?.let { i -> s.expenses.firstOrNull { it.id == i } }
    if (group == null || (id != null && existing == null) || shell.budgetReadOnly()) {
        LaunchedEffect(s.loaded) { if (s.loaded || shell.budgetReadOnly()) close() }
        LoadingBox()
        return
    }
    val scope = rememberCoroutineScope()
    var form by remember(existing?.id, group.id) {
        mutableStateOf(existing?.let { ExpenseForm.of(it, strings.locale) } ?: ExpenseForm.blank(group, shell.session.user.id, shell.time.today(), s.people, prefill, strings.locale))
    }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var saving by remember { mutableStateOf(false) }
    val key = remember { YuvomiClient.newIdempotencyKey() }
    EditorScaffold(
        t(if (existing == null) "splitExpenses.addExpense" else "splitExpenses.editExpense"), close,
        t(if (existing == null) "common.add" else "common.save"), saving,
        save = {
            errors = SplitFormRules.errors(form, strings, existing?.let { it.amount to it.currency })
            if (errors.isNotEmpty()) {
                scope.launch { shell.notify(errors.values.first()) }
                return@EditorScaffold
            }
            saving = true
            scope.launch {
                try {
                    store.saveExpense(existing, SplitFormRules.expenseBody(form, s.people, strings.locale), key)
                    close()
                } catch (e: ApiProblem) {
                    shell.notify(strings.t(SplitErrors.key(e)))
                } finally {
                    saving = false
                }
            }
        },
        delete = existing?.let { e -> { close(); store.scheduleDeleteExpense(e, strings) { m, a -> shell.notify(m, a) } } },
    ) {
        SplitFields(form, s, errors, recurring = false) { form = it }
        if (existing != null && !existing.attachments.isNullOrEmpty()) Hint(strings.t("splitExpenses.receiptsAttachedLabel", "count" to existing.attachments.size))
    }
}

/** `openRecurringModal()`, as a screen (SPLIT-R13, R14). */
@Composable
fun RecurringEditorScreen(id: Int?, close: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val store = remember(shell.connection) { SplitStore.of(shell.container) } ?: return
    val s by store.state.collectAsState()
    val group = s.group
    val existing = id?.let { i -> s.data.recurring.firstOrNull { it.id == i } }
    if (group == null || (id != null && existing?.canEdit != true) || shell.budgetReadOnly() || s.archived) {
        LaunchedEffect(Unit) { close() }
        return
    }
    val scope = rememberCoroutineScope()
    val initial = remember(existing?.id) { existing?.let { ExpenseForm.of(it, strings.locale) } ?: ExpenseForm.blankRecurring(group, shell.session.user.id, shell.time.today(), s.people) }
    var form by remember(existing?.id) { mutableStateOf(initial) }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var saving by remember { mutableStateOf(false) }
    var past by remember { mutableStateOf<Pair<java.time.LocalDate, Int>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val key = remember { YuvomiClient.newIdempotencyKey() }
    val former = existing?.takeIf { r -> s.data.groupMembers.none { it.uid == r.payerId } }?.let { r -> r.payerId?.let { it to r.payerName.orEmpty() } }

    fun send(missed: String?) {
        val now = SplitFormRules.recurringBody(form, s.people, strings.locale)
        val shown = SplitFormRules.recurringBody(initial, s.people, strings.locale)
        var body: JsonObject = if (existing == null) now else SplitRules.changedRecurringFields(shown, now, existing.participants)
        if (missed != null) body = JsonObject(body + ("missed" to kotlinx.serialization.json.JsonPrimitive(missed)))
        saving = true
        scope.launch {
            try {
                store.saveRecurring(existing, body, key)
                close()
            } catch (e: ApiProblem) {
                if (e is ApiProblem.Http && e.reason == "next_run_not_after_last_booking") {
                    errors = mapOf("date" to strings.t("splitExpenses.recurring.dateBooked", "date" to ""))
                }
                shell.notify(strings.t(SplitErrors.key(e)))
            } finally {
                saving = false
            }
        }
    }

    EditorScaffold(
        t(if (existing == null) "splitExpenses.recurring.add" else "splitExpenses.recurring.edit"), close,
        t(if (existing == null) "common.add" else "common.save"), saving,
        save = {
            errors = SplitFormRules.errors(form, strings, existing?.let { it.amount to it.currency })
            if (former != null && form.payerId == former.first) errors = errors + (SplitFormRules.SPLIT to strings.t("splitExpenses.recurring.reason.not_a_member"))
            if (errors.isNotEmpty()) {
                scope.launch { shell.notify(errors.values.first()) }
                return@EditorScaffold
            }
            val dateSent = existing == null || form.date != initial.date
            val p = if (dateSent && existing?.paused != true) SplitFormRules.pastDate(form, shell.time.today()) else null
            if (p != null) past = p else send(null)
        },
        delete = existing?.let { { confirmDelete = true } },
    ) {
        existing?.let { r ->
            listOfNotNull(if (r.paused) strings.t("splitExpenses.recurring.paused") else null, recurringReason(strings, r)).joinToString(" · ").takeIf { it.isNotEmpty() }
                ?.let { Text(it, color = Yuvomi.colors.warning, style = MaterialTheme.typography.bodySmall) }
        }
        SplitFields(form, s, errors, recurring = true, formerPayer = former) { form = it }
        errors["date"]?.let { Text(it, color = Yuvomi.colors.danger, style = MaterialTheme.typography.bodySmall) }
        if (existing != null) Hint(t("splitExpenses.recurring.editHint"))
    }
    past?.let { (next, count) ->
        AlertDialog(
            onDismissRequest = { past = null },
            title = { Text(t("splitExpenses.recurring.pastQuestion")) },
            text = { Text(strings.t("splitExpenses.recurring.pastDetail", "count" to count, "date" to shell.dateText(form.date))) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { past = null; send("skip") }) { Text(strings.t("splitExpenses.recurring.pastSkip", "date" to shell.dateText(next.toString()))) }
                    TextButton(onClick = { past = null; send("book") }) { Text(strings.t("splitExpenses.recurring.missedBook", "date" to shell.dateText(form.date))) }
                    TextButton(onClick = { past = null }) { Text(t("common.cancel")) }
                }
            },
        )
    }
    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(t("splitExpenses.recurring.deleteConfirm")) },
            text = { Text(t("splitExpenses.recurring.deleteConfirmDetail")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        store.deleteRecurring(existing.id)?.let { shell.notify(strings.t(it)) }
                        close()
                    }
                }) { Text(t("common.delete"), color = Yuvomi.colors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(t("common.cancel")) } },
        )
    }
}

/** `openGroupModal()`, as a screen (SPLIT-R4, R5): name, description, type, currency; editing adds members and the default split. */
@Composable
fun GroupEditorScreen(id: Int?, close: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val store = remember(shell.connection) { SplitStore.of(shell.container) } ?: return
    val s by store.state.collectAsState()
    val existing = id?.let { i -> s.data.groups.firstOrNull { it.id == i } }
    if ((id != null && existing == null) || shell.budgetReadOnly() || store.guest) {
        LaunchedEffect(Unit) { close() }
        return
    }
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var description by remember { mutableStateOf(existing?.description.orEmpty()) }
    var type by remember { mutableStateOf(existing?.type ?: "general") }
    var currency by remember { mutableStateOf(existing?.defaultCurrency ?: s.data.meta.defaultCurrency) }
    var method by remember { mutableStateOf(existing?.defaultSplitMethod?.takeIf { it != SplitRules.EXACT } ?: SplitRules.EQUAL) }
    var values by remember { mutableStateOf(SplitRules.defaultSplitValues(existing)) }
    var candidates by remember { mutableStateOf<List<MemberCandidate>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val key = remember { YuvomiClient.newIdempotencyKey() }
    LaunchedEffect(existing?.id) {
        if (existing != null) {
            candidates = try { store.candidates() } catch (e: ApiProblem) { emptyList() }
            selected = candidates.filter { it.inGroup }.map { it.key }.toSet()
        }
    }
    val people = s.people
    EditorScaffold(
        t(if (existing == null) "splitExpenses.addGroup" else "splitExpenses.editGroup"), close,
        t(if (existing == null) "common.add" else "common.save"), saving,
        save = {
            if (name.isBlank()) {
                nameError = strings.t("common.required")
                return@EditorScaffold
            }
            val showsDefaults = existing != null && people.size >= 2
            if (showsDefaults && !SplitRules.defaultsValid(method, values, strings.locale)) {
                scope.launch { shell.notify(strings.t("splitExpenses.defaultSplitInvalid")) }
                return@EditorScaffold
            }
            val add = candidates.filter { it.key in selected && !it.inGroup }
            val remove = candidates.filter { it.source == "user" && it.inGroup && it.key !in selected && it.groupRole != "owner" }.mapNotNull { it.userId }
            saving = true
            scope.launch {
                val err = store.saveGroup(existing, SplitFormRules.groupBody(name, description, type, currency, if (showsDefaults) method to values.filterKeys { k -> people.any { it.uid == k } } else null, strings.locale), key, add, remove)
                saving = false
                if (err == null) close() else shell.notify(strings.t(err))
            }
        },
    ) {
        Field(t("splitExpenses.name") + " *", name, nameError) { name = it.take(200); nameError = null }
        Field(t("splitExpenses.description"), description, singleLine = false) { description = it }
        ChoiceField(t("splitExpenses.type"), type, s.data.meta.groupTypes.map { it to strings.t(SplitRules.GROUP_TYPE_KEYS[it] ?: "splitExpenses.groupType.general") }) { type = it }
        ChoiceField(t("splitExpenses.currency"), currency, s.data.meta.currencies.ifEmpty { BudgetMoney.CURRENCY_CODES }.map { it to it }) { currency = it }
        if (existing != null && candidates.isNotEmpty()) {
            SectionTitle(t("splitExpenses.members"))
            candidates.forEach { c ->
                val on = c.key in selected
                val locked = c.groupRole == "owner"
                val label = c.displayName + (if (c.source == "contact") " · ${strings.t("nav.contacts")}" else "") + (if (c.groupRole == "guest") " · ${strings.t("splitExpenses.roleGuest")}" else "")
                Row(Modifier.fillMaxWidth().clickable(enabled = !locked) { selected = if (on) selected - c.key else selected + c.key }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(on, { selected = if (on) selected - c.key else selected + c.key }, enabled = !locked)
                    Text(label)
                }
            }
        }
        if (existing != null && people.size >= 2) {
            SectionTitle(t("splitExpenses.defaultSplit"))
            Hint(t("splitExpenses.defaultSplitHint"))
            ChoiceField(t("splitExpenses.splitMethod"), method, listOf(SplitRules.EQUAL, SplitRules.PERCENTAGE, SplitRules.SHARES).map { it to strings.t(SplitRules.methodKey(it)) }) { method = it }
            if (method != SplitRules.EQUAL) people.forEach { p ->
                Field("${p.displayName} ${strings.t("splitExpenses.splitValue")}", values[p.uid].orEmpty(), keyboard = KeyboardType.Decimal) { values = values + (p.uid to it) }
            }
            if (!SplitRules.defaultsValid(method, values, strings.locale)) Text(t("splitExpenses.defaultSplitInvalid"), color = Yuvomi.colors.danger, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** `openSettlementModal()`, as a screen (SPLIT-R15): who paid whom how much, proposed from the debts. */
@Composable
fun SettlementScreen(close: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val store = remember(shell.connection) { SplitStore.of(shell.container) } ?: return
    val s by store.state.collectAsState()
    val group = s.group
    if (group == null || shell.budgetReadOnly()) {
        LaunchedEffect(Unit) { close() }
        return
    }
    val me = shell.session.user.id
    val debts = s.data.balances.simplifiedDebts
    val proposed = remember { SplitFormRules.proposedDebt(debts, me) }
    val scope = rememberCoroutineScope()
    var payer by remember { mutableStateOf(proposed?.fromUserId ?: me) }
    var payee by remember { mutableStateOf(proposed?.toUserId ?: s.people.firstOrNull { it.uid != me }?.uid ?: me) }
    var currency by remember { mutableStateOf(proposed?.currency ?: group.defaultCurrency) }
    var amount by remember { mutableStateOf(proposed?.let { BudgetMoney.toInput(it.amount, it.currency, strings.locale) }.orEmpty()) }
    var notes by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val key = remember { YuvomiClient.newIdempotencyKey() }
    val people = s.people.map { it.uid to it.displayName }
    EditorScaffold(t("splitExpenses.registerPayment"), close, t("splitExpenses.registerPayment"), saving, save = {
        if (payer == payee) return@EditorScaffold
        val problem = BudgetMoney.problem(amount, currency, strings.locale, required = true, original = proposed?.amount?.toDoubleOrNull(), originalCurrency = proposed?.currency)
        if (problem != null) {
            error = BudgetMoney.problemText(strings, problem, currency)
            return@EditorScaffold
        }
        saving = true
        scope.launch {
            val err = store.settle(SplitFormRules.settlementBody(payer, payee, amount, currency, notes, strings.locale), key)
            saving = false
            if (err == null) close() else shell.notify(strings.t(err))
        }
    }) {
        ChoiceField(t("splitExpenses.payer"), payer, people) { p ->
            payer = p
            debts.firstOrNull { it.fromUserId == p }?.let { d ->
                payee = d.toUserId
                if (amount.isEmpty() || (proposed != null && amount == BudgetMoney.toInput(proposed.amount, proposed.currency, strings.locale))) {
                    amount = BudgetMoney.toInput(d.amount, d.currency, strings.locale)
                }
            }
        }
        ChoiceField(t("splitExpenses.payee"), payee, people) { payee = it }
        if (payer == payee) Text(t("splitExpenses.settlementSamePerson"), color = Yuvomi.colors.warning, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(t("splitExpenses.amount") + " *", amount, error, Modifier.weight(1f), KeyboardType.Decimal) { amount = it; error = null }
            ChoiceField(t("splitExpenses.currency"), currency, s.data.meta.currencies.ifEmpty { BudgetMoney.CURRENCY_CODES }.map { it to it }, Modifier.width(120.dp)) { currency = it }
        }
        Field(t("splitExpenses.notes"), notes, singleLine = false) { notes = it }
    }
}

/** `openGuestModal()`, as a screen (SPLIT-R6): a person outside the household with their own sign-in. */
@Composable
fun GuestScreen(close: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val store = remember(shell.connection) { SplitStore.of(shell.container) } ?: return
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var birth by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var saving by remember { mutableStateOf(false) }
    val key = remember { YuvomiClient.newIdempotencyKey() }
    EditorScaffold(t("splitExpenses.createGuest"), close, t("splitExpenses.createAndAddGuest"), saving, save = {
        errors = SplitFormRules.guestErrors(name, password, birth, strings)
        if (errors.isNotEmpty()) return@EditorScaffold
        saving = true
        scope.launch {
            val err = store.createGuest(SplitFormRules.guestBody(name, username, password, phone, email, birth), key)
            saving = false
            if (err == null) close() else shell.notify(strings.t(err))
        }
    }) {
        Field(t("splitExpenses.displayName") + " *", name, errors["name"]) { name = it.take(128) }
        Field(t("splitExpenses.usernameOptional"), username) { username = it.take(64) }
        Field(t("splitExpenses.temporaryPassword") + " *", password, errors["password"], keyboard = KeyboardType.Password) { password = it }
        Field(t("splitExpenses.phone"), phone, keyboard = KeyboardType.Phone) { phone = it }
        Field(t("splitExpenses.email"), email, keyboard = KeyboardType.Email) { email = it }
        DateField(t("splitExpenses.birthDate"), birth, error = errors["birth"]) { birth = it }
        Hint(t("splitExpenses.guestSyncHint"))
    }
}
