package io.github.marcomow.yuvomi.feature.rewards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Redeem
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.feature.shell.LocalShell

/** The module's tone (ADR 0002). */
@Composable
internal fun rewardsTone(): Color = Yuvomi.colors.module("rewards")

@Composable
internal fun SectionTitle(text: String, count: Int? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = Yuvomi.colors.textSecondary)
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** The bar toward the next reward (`rw-progress`), read as its sentence. */
@Composable
internal fun ProgressLine(hint: RewardsRules.Hint?) {
    val strings = LocalStrings.current
    if (hint == null) {
        Text(strings.t("rewards.noRewardsYet"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(
            progress = { hint.pct.coerceIn(0, 100) / 100f },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${strings.t("rewards.progressLabel")}: ${hint.label}" },
            color = rewardsTone(),
        )
        Text(hint.label, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
    }
}

/** A small label like "inactive" or "Sold out" (`rw-tag`). */
@Composable
internal fun Tag(text: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall)
    }
}

private fun ledgerIcon(type: String): ImageVector = when (type) {
    "earn" -> Icons.Outlined.CheckCircle
    "bonus" -> Icons.Outlined.AutoAwesome
    "redeem" -> Icons.Outlined.Redeem
    "adjust" -> Icons.Outlined.Tune
    "reversal" -> Icons.AutoMirrored.Outlined.Undo
    else -> Icons.Outlined.Circle
}

@Composable
private fun EntryIcon(icon: ImageVector, positive: Boolean) {
    val tint = if (positive) Yuvomi.colors.success else Yuvomi.colors.textSecondary
    Box(Modifier.size(32.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun DeltaText(text: String, positive: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = if (positive) Yuvomi.colors.success else MaterialTheme.colorScheme.onSurface,
    )
}

/** `ledgerRowHtml`: sign | reason, person · date | points. [showName] off in a member's own list. */
@Composable
internal fun LedgerRow(entry: LedgerEntry, showName: Boolean = true) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val date = shell.time.parse(entry.createdAt)?.toLocalDate()?.let { shell.formats.date(it, shell.time.today()) }.orEmpty()
    ListItem(
        leadingContent = { EntryIcon(ledgerIcon(entry.type), entry.delta > 0) },
        headlineContent = { Text(RewardsRules.ledgerReason(entry, strings)) },
        supportingContent = {
            Text(listOfNotNull(entry.userName?.takeIf { showName }, date.ifEmpty { null }, RewardsRules.decayNote(entry, strings)).joinToString(" · "))
        },
        trailingContent = {
            // REW-R26: a reduced earning shows its full value struck through, said as "instead of 5".
            val base = RewardsRules.decayBase(entry)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (base != null) {
                    val insteadOf = strings.t("rewards.ledgerLateDecayInsteadOf", "count" to base)
                    Text(
                        RewardsRules.points(base, strings),
                        style = MaterialTheme.typography.bodySmall,
                        textDecoration = TextDecoration.LineThrough,
                        color = Yuvomi.colors.textSecondary,
                        modifier = Modifier.padding(end = 6.dp).semantics { contentDescription = insteadOf },
                    )
                }
                DeltaText(RewardsRules.delta(entry.delta, strings), entry.delta > 0)
            }
        },
    )
}

/** `moneyLedgerRowHtml`: what the entry was, its date (a plan's credit names its date), the amount. */
@Composable
internal fun MoneyEntryRow(entry: MoneyEntry, money: RewardsMoney, household: MoneyState?) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val kind = RewardsRules.moneyRowKind(entry)
    val whenText = (if (kind == "allowance") entry.allowanceDate else entry.createdAt)
        ?.let { shell.time.parse(it)?.toLocalDate() }?.let { shell.formats.date(it, shell.time.today()) }.orEmpty()
    val ctx = money.ctx(entry.currency, entry.minorUnit, household)
    val positive = entry.delta > 0
    ListItem(
        leadingContent = { EntryIcon(if (kind == "allowance") Icons.Outlined.CalendarMonth else Icons.Outlined.Payments, positive) },
        headlineContent = { Text(RewardsRules.moneyRowLabel(kind, strings)) },
        supportingContent = { Text(listOfNotNull(whenText.ifEmpty { null }, entry.reason).joinToString(" · ")) },
        trailingContent = { DeltaText((if (positive) "+" else "−") + money.format(kotlin.math.abs(entry.delta), ctx), positive) },
    )
}

/** `moneyPlanLine`: the plan in one sentence, or that there is none. */
internal fun planLine(plan: MoneyPlan?, money: RewardsMoney, household: MoneyState?, strings: io.github.marcomow.yuvomi.core.i18n.Strings, date: (String) -> String): String {
    if (plan == null) return strings.t("rewards.money.noPlan")
    val amount = money.format(plan.amountMinor, money.ctx(plan.currency, plan.minorUnit, household))
    val rhythm = strings.t(if (plan.frequency == "weekly") "rewards.money.planWeekly" else "rewards.money.planMonthly", "amount" to amount)
    if (plan.paused) return "$rhythm · ${strings.t("rewards.money.planPaused")}"
    return "$rhythm · ${strings.t("rewards.money.nextCredit", "date" to plan.nextRunDate?.let(date).orEmpty())}"
}
