package io.github.marcomow.yuvomi.feature.rewards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.core.time.HouseholdTime
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** One overlay of the page at a time; [go] replaces it (null closes). */
@Composable
internal fun RewardsOverlayHost(o: RewardsOverlay, vm: RewardsViewModel, s: RewardsViewModel.UiState, rights: RewardsRights, go: (RewardsOverlay?) -> Unit) {
    val close = { go(null) }
    when (o) {
        is RewardsOverlay.Redeem -> RedeemDialog(o, vm, s, rights, close)
        RewardsOverlay.NothingToRedeem -> Notice(t("rewards.emptyCatalogMember"), close)
        RewardsOverlay.Bonus -> BonusDialog(vm, s, close)
        is RewardsOverlay.Editor -> RewardEditor(o.item, vm, close)
        RewardsOverlay.Participants -> ParticipantsSheet(vm, close)
        is RewardsOverlay.Member -> MemberSheet(o.member, vm, s, rights, go)
        is RewardsOverlay.MoneyRequest -> MoneyRequestDialog(o.kind, o.account, vm, close)
        is RewardsOverlay.MoneyBook -> MoneyBookDialog(o.account, vm, close)
        is RewardsOverlay.MoneyPlan -> MoneyPlanDialog(o.account, vm, close)
        RewardsOverlay.MoneyOpen -> MoneyOpenDialog(vm, s, close)
        is RewardsOverlay.MoneyClose -> Confirm(
            question = t("rewards.money.confirmCloseAccount", "name" to o.account.displayName),
            detail = t("rewards.money.closeAccountDetail"),
            confirm = t("rewards.money.closeAccount"),
            onDismiss = close,
        ) { close(); vm.closeAccount(o.account) }
        is RewardsOverlay.MoneyDetail -> MoneyDetailSheet(o.account, vm, s, close)
    }
}

@Composable
private fun Notice(text: String, close: () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = close) { Text(t("rewards.gotIt")) } },
    )
}

@Composable
internal fun Confirm(question: String, detail: String?, confirm: String, danger: Boolean = false, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(question) },
        text = detail?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = if (danger) Yuvomi.colors.danger else MaterialTheme.colorScheme.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}

/**
 * A form in a dialog: [submit] returns the sentence of a failure (shown in the form, which stays
 * open) or null (the form closes), like the web page's `form-error` line.
 */
@Composable
private fun FormDialog(
    title: String,
    confirm: String,
    close: () -> Unit,
    submit: suspend () -> String?,
    extraButton: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
                error?.let { Text(it, color = Yuvomi.colors.danger, style = MaterialTheme.typography.bodyMedium) }
                extraButton?.invoke()
            }
        },
        confirmButton = {
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val failure = submit()
                    busy = false
                    if (failure == null) close() else error = failure
                }
            }) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(confirm)
            }
        },
        dismissButton = { TextButton(onClick = close, enabled = !busy) { Text(t("common.cancel")) } },
    )
}

/** A labelled choice from a short list (the web page's `<select>`). */
@Composable
private fun <T> Choice(label: String, options: List<T>, selected: T?, text: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selected?.let(text).orEmpty(), Modifier.weight(1f))
                Icon(Icons.Outlined.ArrowDropDown, null)
            }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(text(option)) }, onClick = { open = false; onSelect(option) })
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, negative: Boolean = false, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        Text(
            value,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            color = if (negative) Yuvomi.colors.danger else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TwoWay(first: String, second: String, firstSelected: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(selected = firstSelected, onClick = { onChange(true) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(first) }
        SegmentedButton(selected = !firstSelected, onClick = { onChange(false) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(second) }
    }
}

// ── REW-R8: redeem ──

@Composable
private fun RedeemDialog(o: RewardsOverlay.Redeem, vm: RewardsViewModel, s: RewardsViewModel.UiState, rights: RewardsRights, close: () -> Unit) {
    val strings = LocalStrings.current
    val members = s.balances
    val items = vm.redeemable()
    var memberId by remember { mutableStateOf(vm.defaultMember(o.memberId)) }
    var itemId by remember { mutableStateOf(o.itemId?.takeIf { id -> items.any { it.id == id } } ?: items.firstOrNull()?.id) }
    var note by remember { mutableStateOf("") }
    val item = items.firstOrNull { it.id == itemId }
    FormDialog(
        title = t(if (rights.admin) "rewards.redeem" else "rewards.request"),
        confirm = t(if (rights.admin) "rewards.confirmRedeem" else "rewards.requestAction"),
        close = close,
        submit = { vm.redeem(memberId ?: return@FormDialog strings.t("common.errorGeneric"), itemId ?: return@FormDialog strings.t("rewards.errorRewardGone"), note) },
    ) {
        if (rights.admin && members.size > 1) {
            Choice(t("rewards.member"), members, members.firstOrNull { it.id == memberId }, { "${it.displayName} · ${RewardsRules.pointsLabel(it.balance, strings)}" }) { memberId = it.id }
        }
        Choice(t("rewards.reward"), items, item, { "${it.icon?.let { i -> "$i " }.orEmpty()}${it.name} - ${RewardsRules.pointsLabel(it.cost, strings)}" }) { itemId = it.id }
        val balance = memberId?.let(s::balanceOf) ?: 0
        val cost = item?.cost ?: 0
        SummaryRow(t("rewards.balance"), RewardsRules.points(balance, strings))
        SummaryRow(t("rewards.cost"), "−" + RewardsRules.points(cost, strings))
        HorizontalDivider()
        SummaryRow(t("rewards.remaining"), RewardsRules.points(balance - cost, strings), negative = balance - cost < 0, bold = true)
        OutlinedTextField(
            note, { note = it.take(500) }, Modifier.fillMaxWidth(),
            label = { Text(t("rewards.noteOptional")) }, placeholder = { Text(t("rewards.notePlaceholder")) }, singleLine = true,
        )
    }
}

// ── REW-R10: bonus ──

@Composable
private fun BonusDialog(vm: RewardsViewModel, s: RewardsViewModel.UiState, close: () -> Unit) {
    val strings = LocalStrings.current
    val members = s.balances
    if (members.isEmpty()) {
        Notice(t("rewards.emptyOverviewAdmin"), close)
        return
    }
    var memberId by remember { mutableStateOf(members.first().id) }
    var credit by remember { mutableStateOf(true) }
    var points by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    FormDialog(
        title = t("rewards.grantBonus"),
        confirm = t(if (credit) "rewards.bonusCredit" else "rewards.bonusDebit"),
        close = close,
        submit = { vm.bonus(memberId, credit, points, reason) },
    ) {
        Choice(t("rewards.member"), members, members.firstOrNull { it.id == memberId }, { "${it.displayName} · ${RewardsRules.pointsLabel(it.balance, strings)}" }) { memberId = it.id }
        TwoWay(t("rewards.bonusKindCredit"), t("rewards.bonusKindDebit"), credit) { credit = it }
        OutlinedTextField(
            points, { points = it.filter(Char::isDigit).take(7) }, Modifier.fillMaxWidth(),
            label = { Text("${t("rewards.bonusPoints")} *") }, placeholder = { Text("10") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            reason, { reason = it.take(200) }, Modifier.fillMaxWidth(),
            label = { Text(t("rewards.reasonOptional")) }, placeholder = { Text(t("rewards.reasonPlaceholder")) }, singleLine = true,
        )
    }
}

// ── REW-R14, R15: a reward ──

@Composable
private fun RewardEditor(item: RewardItem?, vm: RewardsViewModel, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var form by remember { mutableStateOf(item?.let(RewardsViewModel.RewardForm::of) ?: RewardsViewModel.RewardForm()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = t(if (item != null) "rewards.editReward" else "rewards.addReward"),
        confirm = t(if (item != null) "common.save" else "common.add"),
        close = close,
        submit = { vm.saveReward(item, form) },
        extraButton = if (item == null) null else ({
            deleteError?.let { Text(it, color = Yuvomi.colors.danger) }
            TextButton(onClick = { confirmDelete = true }) { Text(t("common.delete"), color = Yuvomi.colors.danger) }
        }),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                form.icon, { form = form.copy(icon = it.take(4)) }, Modifier.width(88.dp),
                label = { Text(t("rewards.iconLabel")) }, placeholder = { Text("🎁") }, singleLine = true,
            )
            OutlinedTextField(
                form.name, { form = form.copy(name = it.take(120)) }, Modifier.weight(1f),
                label = { Text("${t("rewards.nameLabel")} *") }, placeholder = { Text(t("rewards.namePlaceholder")) }, singleLine = true,
            )
        }
        OutlinedTextField(
            form.cost, { form = form.copy(cost = it.filter(Char::isDigit).take(7)) }, Modifier.fillMaxWidth(),
            label = { Text("${t("rewards.costLabel")} *") }, placeholder = { Text("100") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            form.quantity, { form = form.copy(quantity = it.filter(Char::isDigit).take(7)) }, Modifier.fillMaxWidth(),
            label = { Text(t("rewards.quantityLabel")) }, supportingText = { Text(t("rewards.quantityHint")) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            form.description, { form = form.copy(description = it.take(500)) }, Modifier.fillMaxWidth(),
            label = { Text(t("rewards.descLabel")) }, placeholder = { Text(t("rewards.descPlaceholder")) }, minLines = 2,
        )
        if (item != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("rewards.activeLabel"), Modifier.weight(1f))
                Switch(form.active, { form = form.copy(active = it) })
            }
        }
    }
    if (confirmDelete && item != null) {
        Confirm(
            question = t("rewards.confirmDeleteReward", "reward" to item.name),
            detail = t("rewards.confirmDeleteRewardDetail"),
            confirm = t("common.delete"),
            danger = true,
            onDismiss = { confirmDelete = false },
        ) {
            confirmDelete = false
            scope.launch {
                val error = vm.deleteReward(item)
                if (error == null) close() else deleteError = error
            }
        }
    }
}

// ── REW-R12: participants (every switch saves at once) ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParticipantsSheet(vm: RewardsViewModel, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val members = remember { mutableStateListOf<Participant>() }
    var loadError by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        vm.participants().onSuccess { members.addAll(it) }.onFailure { loadError = it.message }
        loading = false
    }
    loadError?.let {
        Notice(it, close)
        return
    }
    val done = {
        close()
        vm.refresh()
    }
    ModalBottomSheet(onDismissRequest = done, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text(t("rewards.manageParticipants"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Text(t("rewards.participantsIntro"), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = Yuvomi.colors.textSecondary)
            if (loading) CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
            members.forEachIndexed { index, m ->
                ListItem(
                    leadingContent = { MemberAvatar(m.displayName, m.avatarColor, m.avatarData, size = 36.dp) },
                    headlineContent = { Text(m.displayName) },
                    trailingContent = {
                        Switch(m.enabled, { on ->
                            members[index] = m.copy(enabled = on)
                            scope.launch {
                                vm.setParticipant(m.id, on)?.let { failure ->
                                    members[index] = m.copy(enabled = !on)
                                    error = failure
                                }
                            }
                        })
                    },
                )
            }
            error?.let { Text(it, Modifier.padding(horizontal = 24.dp), color = Yuvomi.colors.danger) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Spacer(Modifier.weight(1f))
                Button(onClick = done) { Text(t("rewards.done")) }
            }
        }
    }
}

// ── REW-R11: a member's points and last entries ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemberSheet(member: RewardBalance, vm: RewardsViewModel, s: RewardsViewModel.UiState, rights: RewardsRights, go: (RewardsOverlay?) -> Unit) {
    val strings = LocalStrings.current
    var entries by remember { mutableStateOf<List<LedgerEntry>?>(null) }
    LaunchedEffect(member.id) { entries = vm.memberLedger(member.id) }
    val hint = RewardsRules.hint(member.balance, s.bundle.overview.catalog, strings)
    val canRedeem = rights.canWrite && (rights.admin || member.id == rights.me)
    ModalBottomSheet(onDismissRequest = { go(null) }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp).navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                MemberAvatar(member.displayName, member.avatarColor, member.avatarData, size = 52.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(member.displayName, style = MaterialTheme.typography.titleLarge)
                    Text(RewardsRules.pointsLabel(member.balance, strings), style = MaterialTheme.typography.titleMedium)
                    hint?.let { Text(it.label, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary) }
                }
            }
            Spacer(Modifier.size(8.dp))
            when (val list = entries) {
                null -> CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
                else -> if (list.isEmpty()) {
                    Text(t("rewards.emptyLedgerBody"), Modifier.padding(24.dp), color = Yuvomi.colors.textSecondary)
                } else {
                    list.forEach { LedgerRow(it, showName = false) }
                }
            }
            if (canRedeem) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { go(null) }) { Text(t("common.close")) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { go(redeemOverlay(vm, member.id)) }) { Text(t(if (rights.admin) "rewards.redeem" else "rewards.request")) }
                }
            }
        }
    }
}

// ── REW-R17 to R22: pocket money ──

@Composable
private fun AmountField(value: String, onChange: (String) -> Unit, ctx: RewardsMoney.Ctx, money: RewardsMoney) {
    OutlinedTextField(
        value, { onChange(it.take(20)) }, Modifier.fillMaxWidth(),
        label = { Text("${t("rewards.money.amount")} (${ctx.currency}) *") },
        placeholder = { Text(money.placeholder(ctx)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

@Composable
private fun MoneyRequestDialog(kind: String, account: MoneyAccount, vm: RewardsViewModel, close: () -> Unit) {
    val ctx = vm.ctx(account)
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    FormDialog(
        title = t(if (kind == "withdrawal") "rewards.money.withdraw" else "rewards.money.deposit"),
        confirm = t("rewards.requestAction"),
        close = close,
        submit = { vm.requestMoney(kind, account, amount, note) },
    ) {
        SummaryRow(t("rewards.money.title"), vm.money.format(account.balanceMinor, ctx))
        AmountField(amount, { amount = it }, ctx, vm.money)
        OutlinedTextField(note, { note = it.take(500) }, Modifier.fillMaxWidth(), label = { Text(t("rewards.noteOptional")) }, singleLine = true)
        Text(t("rewards.money.requestHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
    }
}

@Composable
private fun MoneyBookDialog(account: MoneyAccount, vm: RewardsViewModel, close: () -> Unit) {
    val ctx = vm.ctx(account)
    var credit by remember { mutableStateOf(!account.former) }
    var amount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    FormDialog(
        title = "${t(if (account.former) "rewards.money.debit" else "rewards.money.book")} · ${account.displayName}",
        confirm = t("common.save"),
        close = close,
        submit = { vm.book(account, credit, amount, reason) },
    ) {
        SummaryRow(t("rewards.money.title"), vm.money.format(account.balanceMinor, ctx))
        // A former account is only paid out: no credit offered (the server would refuse it).
        if (!account.former) TwoWay(t("rewards.money.credit"), t("rewards.money.debit"), credit) { credit = it }
        AmountField(amount, { amount = it }, ctx, vm.money)
        OutlinedTextField(reason, { reason = it.take(200) }, Modifier.fillMaxWidth(), label = { Text(t("rewards.reasonOptional")) }, singleLine = true)
    }
}

@Composable
private fun MoneyPlanDialog(account: MoneyAccount, vm: RewardsViewModel, close: () -> Unit) {
    val strings = LocalStrings.current
    val locale = Locale.forLanguageTag(strings.locale)
    var form by remember { mutableStateOf(vm.planForm(account)) }
    var confirmRemove by remember { mutableStateOf(false) }
    FormDialog(
        title = "${t("rewards.money.planTitle")} · ${account.displayName}",
        confirm = t("common.save"),
        close = close,
        submit = { vm.savePlan(account, form) },
        extraButton = if (account.plan == null) null else ({
            TextButton(onClick = { confirmRemove = true }) { Text(t("rewards.money.removePlan")) }
        }),
    ) {
        AmountField(form.amount, { form = form.copy(amount = it) }, vm.planCtx(account), vm.money)
        Choice(t("rewards.money.frequency"), listOf(true, false), form.weekly, { strings.t(if (it) "rewards.money.weekly" else "rewards.money.monthly") }) {
            form = form.copy(weekly = it)
        }
        if (form.weekly) {
            Choice(t("rewards.money.weekday"), (1..7).toList(), form.weekday, { DayOfWeek.of(it).getDisplayName(TextStyle.FULL, locale) }) {
                form = form.copy(weekday = it)
            }
        } else {
            Choice(t("rewards.money.dayOfMonth"), (1..31).toList(), form.monthDay, { it.toString() }) { form = form.copy(monthDay = it) }
            Text(t("rewards.money.dayOfMonthHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("rewards.money.pausePlan"), Modifier.weight(1f))
            Switch(form.paused, { form = form.copy(paused = it) })
        }
    }
    if (confirmRemove) {
        Confirm(
            question = t("rewards.money.confirmRemovePlan", "name" to account.displayName),
            detail = t("rewards.money.removePlanDetail"),
            confirm = t("rewards.money.removePlan"),
            onDismiss = { confirmRemove = false },
        ) {
            confirmRemove = false
            close()
            vm.removePlan(account)
        }
    }
}

@Composable
private fun MoneyOpenDialog(vm: RewardsViewModel, s: RewardsViewModel.UiState, close: () -> Unit) {
    val candidates = RewardsRules.moneyCandidates(s.bundle.money)
    var memberId by remember { mutableStateOf(candidates.firstOrNull()?.id) }
    if (candidates.isEmpty()) {
        LaunchedEffect(Unit) { close() }
        return
    }
    FormDialog(
        title = t("rewards.money.setUp"),
        confirm = t("rewards.money.openAccount"),
        close = close,
        submit = { vm.openAccount(memberId ?: candidates.first().id) },
    ) {
        Choice(t("rewards.member"), candidates, candidates.firstOrNull { it.id == memberId }, { it.displayName }) { memberId = it.id }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoneyDetailSheet(account: MoneyAccount, vm: RewardsViewModel, s: RewardsViewModel.UiState, close: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    var entries by remember { mutableStateOf<List<MoneyEntry>?>(null) }
    LaunchedEffect(account.id) { entries = vm.moneyLedger(account.id) }
    val line = planLine(account.plan, vm.money, s.bundle.money, strings) { d ->
        HouseholdTime.date(d)?.let { shell.formats.date(it, shell.time.today()) } ?: d
    }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp).navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                MemberAvatar(account.displayName, account.avatarColor, account.avatarData, size = 52.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("${t("rewards.money.title")} · ${account.displayName}", style = MaterialTheme.typography.titleMedium)
                    Text(vm.money.format(account.balanceMinor, vm.ctx(account)), style = MaterialTheme.typography.titleLarge)
                    Text(line, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }
            }
            Spacer(Modifier.size(8.dp))
            when (val list = entries) {
                null -> CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
                else -> if (list.isEmpty()) {
                    Text(t("rewards.emptyLedgerTitle"), Modifier.padding(24.dp), color = Yuvomi.colors.textSecondary)
                } else {
                    list.forEach { MoneyEntryRow(it, vm.money, s.bundle.money) }
                }
            }
        }
    }
}
