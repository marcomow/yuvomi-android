package io.github.marcomow.yuvomi.feature.rewards

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.marcomow.yuvomi.core.design.Card
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.OfflineBanner
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.AccountButton
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import java.time.Instant

/** Who may do what on the page (`isAdmin()`, `readOnly()`), offline counted as read-only. */
internal data class RewardsRights(val admin: Boolean, val canWrite: Boolean, val me: Int)

/** docs/spec/rewards.md: the tabs "Points" and "Rewards" (REW-R1 to R15). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RewardsScreen(vm: RewardsViewModel, host: RewardsHost, openHistory: () -> Unit, openTasks: () -> Unit) {
    val shell = LocalShell.current
    val s by vm.state.collectAsState()
    val rights = RewardsRights(host.isAdmin, !host.readOnly && !s.offline, s.bundle.overview.me ?: host.me)
    var overlay by remember { mutableStateOf<RewardsOverlay?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (System.currentTimeMillis() - vm.lastLoaded > 60_000) vm.refresh()
    }

    val onCatalog = s.tab == RewardsViewModel.TAB_CATALOG
    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text(t("rewards.title")) }, actions = { AccountButton() })
                PrimaryTabRow(selectedTabIndex = if (onCatalog) 1 else 0) {
                    Tab(selected = !onCatalog, onClick = { vm.setTab(RewardsViewModel.TAB_POINTS) }, text = { Text(t("rewards.tabPoints")) })
                    Tab(selected = onCatalog, onClick = { vm.setTab(RewardsViewModel.TAB_CATALOG) }, text = { Text(t("rewards.tabCatalog")) })
                }
            }
        },
        floatingActionButton = {
            // `updateRewardsFab`: parents add rewards on the catalogue, grant a bonus on the points.
            if (rights.admin && rights.canWrite && !s.loading) {
                if (onCatalog) {
                    ExtendedFloatingActionButton(
                        onClick = { overlay = RewardsOverlay.Editor(null) },
                        icon = { Icon(Icons.Outlined.Add, null) },
                        text = { Text(t("rewards.addReward")) },
                    )
                } else if (s.balances.isNotEmpty()) {
                    ExtendedFloatingActionButton(
                        onClick = { overlay = RewardsOverlay.Bonus },
                        icon = { Icon(Icons.Outlined.AutoAwesome, null) },
                        text = { Text(t("rewards.grantBonus")) },
                    )
                }
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                s.loading -> LoadingBox()
                s.errorKey != null -> ErrorBox("${t("rewards.loadError")}\n${t("common.loadErrorDescription")}", onRetry = { vm.refresh() })
                else -> PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { vm.refresh(pulled = true) }) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                        if (s.offline) {
                            item(key = "offline") { OfflineBanner(shell.formats.dateTime(Instant.ofEpochMilli(s.cachedAt!!).atZone(shell.time.zone))) }
                        }
                        if (onCatalog) {
                            catalogTab(s, rights) { overlay = it }
                        } else {
                            pointsTab(s, rights, vm, openHistory, setOverlay = { overlay = it }, runSetup = { action ->
                                when (action) {
                                    "participants" -> overlay = RewardsOverlay.Participants
                                    "tasks" -> openTasks()
                                    else -> vm.setTab(RewardsViewModel.TAB_CATALOG)
                                }
                            })
                        }
                    }
                }
            }
        }
    }

    overlay?.let { RewardsOverlayHost(it, vm, s, rights) { next -> overlay = next } }
}

/** The forms and sheets of the page, one at a time. */
internal sealed interface RewardsOverlay {
    data class Redeem(val memberId: Int?, val itemId: Int? = null) : RewardsOverlay
    data object NothingToRedeem : RewardsOverlay
    data object Bonus : RewardsOverlay
    data class Editor(val item: RewardItem?) : RewardsOverlay
    data object Participants : RewardsOverlay
    data class Member(val member: RewardBalance) : RewardsOverlay
    data class MoneyRequest(val kind: String, val account: MoneyAccount) : RewardsOverlay
    data class MoneyBook(val account: MoneyAccount) : RewardsOverlay
    data class MoneyPlan(val account: MoneyAccount) : RewardsOverlay
    data object MoneyOpen : RewardsOverlay
    data class MoneyClose(val account: MoneyAccount) : RewardsOverlay
    data class MoneyDetail(val account: MoneyAccount) : RewardsOverlay
}

/** `openRedeemModal`'s first check: with nothing redeemable it only says so. */
internal fun redeemOverlay(vm: RewardsViewModel, memberId: Int?, itemId: Int? = null): RewardsOverlay =
    if (vm.redeemable().isEmpty()) RewardsOverlay.NothingToRedeem else RewardsOverlay.Redeem(memberId, itemId)

@Composable
private fun redeemVerb(admin: Boolean) = t(if (admin) "rewards.redeem" else "rewards.request")

// ── Points ──

private fun LazyListScope.pointsTab(
    s: RewardsViewModel.UiState,
    rights: RewardsRights,
    vm: RewardsViewModel,
    openHistory: () -> Unit,
    setOverlay: (RewardsOverlay) -> Unit,
    runSetup: (String) -> Unit,
) {
    val list = s.balances
    val catalog = s.bundle.overview.catalog
    // REW-R3: a member who collects (not a parent) sees their own card first.
    val own = if (!rights.admin) list.firstOrNull { it.id == rights.me } else null
    val others = if (own != null) list.filter { it.id != rights.me } else list
    if (own != null && list.isNotEmpty()) item(key = "own") { OwnCard(own, catalog, rights, vm, setOverlay) }
    if (list.isNotEmpty() && rights.admin && rights.canWrite) {
        val steps = RewardsRules.setupSteps(s.bundle.overview.setup)
        if (steps.isNotEmpty()) item(key = "setup") { SetupCard(steps, runSetup) }
    }
    pendingPanel(s, rights, vm)
    moneySection(s, rights, vm, setOverlay)
    if (list.isEmpty()) {
        item(key = "empty") {
            EmptyState(
                t("rewards.emptyOverviewTitle"),
                t(if (rights.admin) "rewards.emptyOverviewAdmin" else "rewards.emptyOverviewMember"),
                action = if (rights.admin && rights.canWrite) t("rewards.manageParticipants") else null,
                onAction = { setOverlay(RewardsOverlay.Participants) },
            )
        }
    } else if (others.isNotEmpty()) {
        item(key = "standings-title") {
            SectionTitle(t("rewards.standings")) {
                if (rights.admin && rights.canWrite) {
                    TextButton(onClick = { setOverlay(RewardsOverlay.Participants) }) {
                        Icon(Icons.Outlined.Group, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t("rewards.manageParticipants"))
                    }
                }
            }
        }
        items(others, key = { "standing-${it.id}" }) { member -> StandingRow(member, catalog, rights, vm, setOverlay) }
    }
    val recent = s.bundle.recentLedger
    if (!recent.isNullOrEmpty()) {
        // The section title is the way into the history (`rw-section__more`).
        item(key = "recent-title") {
            TextButton(onClick = openHistory, modifier = Modifier.padding(start = 4.dp, top = 12.dp)) {
                Text(t("rewards.tabLedger"), style = MaterialTheme.typography.titleSmall)
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp))
            }
        }
        items(recent, key = { "recent-${it.id}" }) { LedgerRow(it) }
    }
}

@Composable
private fun CountUp(value: Int) {
    val strings = LocalStrings.current
    val shown by animateIntAsState(value, animationSpec = tween(600), label = "points")
    Text(RewardsRules.points(shown, strings), fontWeight = FontWeight.SemiBold)
}

@Composable
private fun OwnCard(member: RewardBalance, catalog: List<RewardItem>, rights: RewardsRights, vm: RewardsViewModel, setOverlay: (RewardsOverlay) -> Unit) {
    val strings = LocalStrings.current
    val hint = RewardsRules.hint(member.balance, catalog, strings)
    val goal = if (member.balance >= 0) RewardsRules.nextGoal(member.balance, catalog) else null
    val target = (goal as? RewardsRules.Goal.Toward)?.target
    SectionTitle(t("rewards.yourPoints"))
    Card(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                val shown by animateIntAsState(member.balance, animationSpec = tween(600), label = "own")
                Text(RewardsRules.points(shown, strings), fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text(t("rewards.pointsUnit"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
            }
            if (target != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(target.icon ?: "🎁")
                    Spacer(Modifier.width(8.dp))
                    Text(target.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text(RewardsRules.pointsLabel(target.cost, strings), style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary)
                }
            }
            ProgressLine(hint)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { setOverlay(RewardsOverlay.Member(member)) }) { Text(t("rewards.ownLedger")) }
                Spacer(Modifier.weight(1f))
                if (rights.canWrite && RewardsRules.canAffordAny(member.balance, catalog)) {
                    Button(onClick = { setOverlay(redeemOverlay(vm, member.id)) }) {
                        Icon(Icons.Outlined.CardGiftcard, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(redeemVerb(rights.admin))
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupCard(steps: List<RewardsRules.SetupStep>, run: (String) -> Unit) {
    SectionTitle(t("rewards.setupTitle"))
    Card(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.padding(vertical = 4.dp)) {
            steps.forEach { step ->
                ListItem(
                    leadingContent = {
                        Icon(
                            if (step.done) Icons.Outlined.CheckCircle else Icons.Outlined.Circle, null,
                            tint = if (step.done) Yuvomi.colors.success else Yuvomi.colors.textTertiary,
                        )
                    },
                    headlineContent = { Text(t(step.labelKey)) },
                    trailingContent = if (step.done) null else ({ TextButton(onClick = { run(step.action) }) { Text(t("rewards.setupGo")) } }),
                )
            }
        }
    }
}

@Composable
private fun StandingRow(member: RewardBalance, catalog: List<RewardItem>, rights: RewardsRights, vm: RewardsViewModel, setOverlay: (RewardsOverlay) -> Unit) {
    val strings = LocalStrings.current
    val hint = RewardsRules.hint(member.balance, catalog, strings)
    val canRedeem = rights.canWrite && (rights.admin || member.id == rights.me)
    val affordable = rights.admin || RewardsRules.canAffordAny(member.balance, catalog)
    ListItem(
        modifier = Modifier.clickable { setOverlay(RewardsOverlay.Member(member)) },
        leadingContent = { MemberAvatar(member.displayName, member.avatarColor, member.avatarData, size = 40.dp) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(member.displayName, Modifier.weight(1f))
                CountUp(member.balance)
                Text(" ${strings.t("rewards.pointsUnit")}", style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            }
        },
        supportingContent = { Box(Modifier.padding(top = 4.dp)) { ProgressLine(hint) } },
        trailingContent = if (!canRedeem) null else ({
            OutlinedButton(onClick = { setOverlay(redeemOverlay(vm, member.id)) }, enabled = affordable) { Text(redeemVerb(rights.admin)) }
        }),
    )
}

private fun LazyListScope.pendingPanel(s: RewardsViewModel.UiState, rights: RewardsRights, vm: RewardsViewModel) {
    val requests = s.bundle.redemptions
    if (requests.isEmpty()) return
    item(key = "pending-title") {
        SectionTitle(t(if (rights.admin) "rewards.pendingApprovals" else "rewards.yourPending"), count = requests.size)
    }
    items(requests, key = { "pending-${it.id}" }) { r -> PendingRow(r, s, rights, vm) }
}

@Composable
private fun PendingRow(r: Redemption, s: RewardsViewModel.UiState, rights: RewardsRights, vm: RewardsViewModel) {
    val strings = LocalStrings.current
    val amount = if (r.isMoney) vm.money.format(r.cost, vm.money.ctx(r.currency, r.minorUnit, s.bundle.money)) else RewardsRules.pointsLabel(r.cost, strings)
    val meta = listOfNotNull(r.userName?.takeIf { rights.admin }, amount, r.note?.let { "„$it“" }).joinToString(" · ")
    // REW-R7: parents read the balance next to the request (#1607, #1623).
    val extra = when {
        !rights.admin -> null
        r.isMoney -> r.userBalance?.let { "${strings.t("rewards.money.title")}: ${vm.money.format(it, vm.money.ctx(r.currency, r.minorUnit, s.bundle.money))}" }
        else -> (r.userBalance ?: s.balanceOf(r.userId).toLong()).takeIf { it < 0 }
            ?.let { strings.t("rewards.pendingBalanceBelowZero", "points" to RewardsRules.points(it, strings)) }
    }
    val busy = r.id in s.deciding
    ListItem(
        leadingContent = { MemberAvatar(r.userName.orEmpty(), r.userColor, r.userAvatar, size = 32.dp) },
        headlineContent = { Text(RewardsRules.requestTitle(r, strings)) },
        supportingContent = {
            Column {
                Text(meta)
                if (extra != null) Text(extra, color = Yuvomi.colors.warning)
            }
        },
        trailingContent = if (!rights.canWrite) null else ({
            if (rights.admin) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { vm.decide(r, "fulfill") }, enabled = !busy) { Text(t("rewards.approve")) }
                    var open by remember { mutableStateOf(false) }
                    Box {
                        val name = RewardsRules.requestTitle(r, strings).let { if (r.isMoney) it else r.rewardName }
                        IconButton(onClick = { open = true }, enabled = !busy) {
                            Icon(Icons.Outlined.MoreVert, strings.t("common.moreActionsNamed", "name" to name))
                        }
                        DropdownMenu(open, onDismissRequest = { open = false }) {
                            DropdownMenuItem(text = { Text(t("rewards.reject")) }, onClick = { open = false; vm.decide(r, "reject") })
                        }
                    }
                }
            } else {
                OutlinedButton(onClick = { vm.decide(r, "cancel") }, enabled = !busy) { Text(t("common.cancel")) }
            }
        }),
    )
}

private fun LazyListScope.moneySection(s: RewardsViewModel.UiState, rights: RewardsRights, vm: RewardsViewModel, setOverlay: (RewardsOverlay) -> Unit) {
    val money = s.bundle.money ?: return
    val manage = rights.admin && rights.canWrite
    val canOpen = manage && RewardsRules.moneyCandidates(money).isNotEmpty()
    if (money.accounts.isEmpty() && !canOpen) return
    item(key = "money-title") {
        SectionTitle(t("rewards.money.title")) {
            if (canOpen) {
                TextButton(onClick = { setOverlay(RewardsOverlay.MoneyOpen) }) {
                    Icon(Icons.Outlined.Savings, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(t("rewards.money.setUp"))
                }
            }
        }
    }
    if (money.accounts.isEmpty()) {
        item(key = "money-empty") {
            Text(t("rewards.money.empty"), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Yuvomi.colors.textSecondary)
        }
    }
    items(money.accounts, key = { "money-${it.id}-${it.former}" }) { account -> MoneyRow(account, s, rights, vm, setOverlay) }
}

@Composable
private fun MoneyRow(account: MoneyAccount, s: RewardsViewModel.UiState, rights: RewardsRights, vm: RewardsViewModel, setOverlay: (RewardsOverlay) -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val manage = rights.admin && rights.canWrite
    val mine = account.id == rights.me
    val line = planLine(account.plan, vm.money, s.bundle.money, strings) { d ->
        io.github.marcomow.yuvomi.core.time.HouseholdTime.date(d)?.let { shell.formats.date(it, shell.time.today()) } ?: d
    }
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable { setOverlay(RewardsOverlay.MoneyDetail(account)) },
        leadingContent = { MemberAvatar(account.displayName, account.avatarColor, account.avatarData, size = 40.dp) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(account.displayName)
                if (account.former) Tag(t("settings.memberFormerBadge"))
            }
        },
        supportingContent = {
            Column {
                Text(vm.money.format(account.balanceMinor, vm.ctx(account)), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(line, color = if (account.plan != null && !account.plan.paused) MaterialTheme.colorScheme.onSurfaceVariant else Yuvomi.colors.textTertiary)
            }
        },
        trailingContent = when {
            manage && account.former -> ({ OutlinedButton(onClick = { setOverlay(RewardsOverlay.MoneyBook(account)) }) { Text(t("rewards.money.debit")) } })
            manage -> ({
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { setOverlay(RewardsOverlay.MoneyBook(account)) }) { Text(t("rewards.money.book")) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, strings.t("common.moreActionsNamed", "name" to account.displayName)) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(t("rewards.money.planTitle")) }, onClick = { menu = false; setOverlay(RewardsOverlay.MoneyPlan(account)) })
                            if (account.closable) {
                                DropdownMenuItem(
                                    text = { Text(t("rewards.money.closeAccount"), color = Yuvomi.colors.danger) },
                                    onClick = { menu = false; setOverlay(RewardsOverlay.MoneyClose(account)) },
                                )
                            }
                        }
                    }
                }
            })
            mine && !account.former && rights.canWrite -> ({
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { setOverlay(RewardsOverlay.MoneyRequest("withdrawal", account)) }, enabled = account.balanceMinor > 0) {
                        Text(t("rewards.money.withdraw"))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, strings.t("common.moreActionsNamed", "name" to account.displayName)) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(t("rewards.money.deposit")) }, onClick = { menu = false; setOverlay(RewardsOverlay.MoneyRequest("deposit", account)) })
                        }
                    }
                }
            })
            else -> null
        },
    )
}

// ── Catalogue ──

private fun LazyListScope.catalogTab(s: RewardsViewModel.UiState, rights: RewardsRights, setOverlay: (RewardsOverlay) -> Unit) {
    val items = s.bundle.catalog
    if (items.isEmpty()) {
        item(key = "catalog-empty") {
            EmptyState(
                t("rewards.emptyCatalogTitle"),
                t(if (rights.admin) "rewards.emptyCatalogAdmin" else "rewards.emptyCatalogMember"),
                action = if (rights.admin && rights.canWrite) t("rewards.addReward") else null,
                onAction = { setOverlay(RewardsOverlay.Editor(null)) },
            )
        }
        return
    }
    item(key = "catalog-top") { Spacer(Modifier.size(8.dp)) }
    items(items, key = { "reward-${it.id}" }) { item -> RewardCard(item, s, rights, setOverlay) }
}

@Composable
private fun RewardCard(item: RewardItem, s: RewardsViewModel.UiState, rights: RewardsRights, setOverlay: (RewardsOverlay) -> Unit) {
    val strings = LocalStrings.current
    val inactive = !item.isActive
    val soldOut = item.remaining == 0
    val mine = s.balances.firstOrNull { it.id == rights.me }
    // `affordabilityFor`: parents redeem for anyone; a member needs to collect and to have enough.
    val short = if (!rights.admin && mine != null) (item.cost - mine.balance).coerceAtLeast(0) else null
    val canRedeem = rights.canWrite && !inactive && !soldOut && (rights.admin || (mine != null && short == 0))
    Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Text(item.icon ?: "🎁", fontSize = 28.sp, modifier = Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                    if (inactive) Tag(t("rewards.inactive"))
                    if (soldOut) Tag(t("rewards.soldOut"))
                }
                item.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary) }
                if ((item.remaining ?: 0) > 0) {
                    Text(t("rewards.unitsLeft", "n" to RewardsRules.points(item.remaining!!, strings)), style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(RewardsRules.pointsLabel(item.cost, strings), style = MaterialTheme.typography.labelLarge, color = rewardsTone(), modifier = Modifier.weight(1f))
                    if (rights.admin && rights.canWrite) {
                        IconButton(onClick = { setOverlay(RewardsOverlay.Editor(item)) }) {
                            Icon(Icons.Outlined.Edit, strings.t("common.editNamed", "name" to item.name))
                        }
                    }
                    if (canRedeem) {
                        OutlinedButton(onClick = { setOverlay(RewardsOverlay.Redeem(null, item.id)) }) { Text(redeemVerb(rights.admin)) }
                    } else if (short != null && short > 0) {
                        Text(t("rewards.pointsShort", "points" to RewardsRules.points(short, strings)), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                    }
                }
            }
        }
    }
}
