package io.github.marcomow.yuvomi.feature.rewards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.OfflineBanner
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.AccountButton
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import java.time.Instant

/** docs/spec/rewards.md, REW-R13: `/rewards/history`, a level below "Points". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RewardsHistoryScreen(vm: RewardsHistoryViewModel, back: () -> Unit) {
    val shell = LocalShell.current
    val s by vm.state.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("rewards.tabLedger")) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t("rewards.tabPoints")) } },
                actions = { AccountButton() },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                s.loading -> LoadingBox()
                s.failed -> ErrorBox("${t("rewards.loadError")}\n${t("common.loadErrorDescription")}", onRetry = { vm.refresh() })
                else -> PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { vm.refresh(pulled = true) }) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                        if (s.cachedAt != null) {
                            item(key = "offline") { OfflineBanner(shell.formats.dateTime(Instant.ofEpochMilli(s.cachedAt!!).atZone(shell.time.zone))) }
                        }
                        // `personChipsHtml`: everyone, then one chip per participating member.
                        if (s.people.isNotEmpty()) {
                            item(key = "chips") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    item(key = "all") {
                                        FilterChip(selected = s.filter == null, onClick = { vm.setFilter(null) }, label = { Text(t("rewards.all")) })
                                    }
                                    items(s.people, key = { it.id }) { p ->
                                        FilterChip(
                                            selected = s.filter == p.id,
                                            onClick = { vm.setFilter(p.id) },
                                            label = { Text(p.displayName.substringBefore(' ')) },
                                            leadingIcon = { MemberAvatar(p.displayName, p.avatarColor, p.avatarData, size = 20.dp) },
                                        )
                                    }
                                }
                            }
                        }
                        if (s.entries.isEmpty()) {
                            item(key = "empty") { EmptyState(t("rewards.emptyLedgerTitle"), t("rewards.emptyLedgerBody")) }
                        } else {
                            items(s.entries, key = { it.id }) { LedgerRow(it) }
                        }
                    }
                }
            }
        }
    }
}
