package io.github.marcomow.yuvomi.feature.subscriptions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.launch

private data class MetaRow(val kind: String, val id: Int, val label: String, val storedName: String, val color: String?, val usage: Int)

/** docs/spec/subscriptions.md, the manager (SUBS-R25 to R28): `openMetadataModal()` as a screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionMetaScreen(back: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val store = remember(shell.connection) { SubscriptionsStore.of(shell.container) } ?: return
    val s by store.state.collectAsState()
    val scope = rememberCoroutineScope { kotlinx.coroutines.Dispatchers.Main.immediate }
    var editing by remember { mutableStateOf<MetaRow?>(null) }
    var deleting by remember { mutableStateOf<MetaRow?>(null) }
    var newCategory by remember { mutableStateOf("") }
    var newColor by remember { mutableStateOf(SubscriptionRules.DEFAULT_COLOR) }
    var newMethod by remember { mutableStateOf("") }
    LaunchedEffect(store) { if (!s.loaded) store.refresh() }
    LaunchedEffect(Unit) { if (shell.budgetReadOnly()) back() }

    fun run(saved: String? = null, block: suspend () -> String?) = scope.launch {
        val key = block()
        shell.notify(strings.t(key ?: saved ?: return@launch))
    }

    val categories = s.meta.categories.map { MetaRow(SubscriptionsStore.KIND_CATEGORIES, it.id, SubscriptionRules.categoryLabel(strings, it), it.name, it.color, it.usageCount) }
    val methods = s.meta.paymentMethods.map { MetaRow(SubscriptionsStore.KIND_METHODS, it.id, SubscriptionRules.methodLabel(strings, it), it.name, null, it.usageCount) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("subscriptions.manageMetadata")) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t("common.back")) } },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            SectionTitle(t("subscriptions.categoriesTitle"))
            categories.forEachIndexed { i, row ->
                MetaRowView(row, i, categories.size, edit = { editing = row }, delete = { deleting = row }) { delta -> run { store.move(row.kind, row.id, delta) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newCategory, { newCategory = it }, placeholder = { Text(t("subscriptions.newCategoryPlaceholder")) }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(enabled = newCategory.isNotBlank(), onClick = {
                    val name = newCategory
                    newCategory = ""
                    run("subscriptions.metaSavedToast") { store.createCategory(name, newColor) }
                }) { Text(t("common.add")) }
            }
            ColorRow(newColor) { newColor = it }

            SectionTitle(t("subscriptions.paymentMethodsTitle"))
            methods.forEachIndexed { i, row ->
                MetaRowView(row, i, methods.size, edit = { editing = row }, delete = { deleting = row }) { delta -> run { store.move(row.kind, row.id, delta) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newMethod, { newMethod = it }, placeholder = { Text(t("subscriptions.newPaymentMethodPlaceholder")) }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(enabled = newMethod.isNotBlank(), onClick = {
                    val name = newMethod
                    newMethod = ""
                    run("subscriptions.metaSavedToast") { store.createMethod(name) }
                }) { Text(t("common.add")) }
            }
        }
    }

    editing?.let { row ->
        var name by remember(row) { mutableStateOf(row.label) }
        var color by remember(row) { mutableStateOf(row.color ?: SubscriptionRules.DEFAULT_COLOR) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(t(if (row.kind == SubscriptionsStore.KIND_CATEGORIES) "subscriptions.editCategory" else "subscriptions.editPaymentMethod")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(100) }, singleLine = true)
                    if (row.kind == SubscriptionsStore.KIND_CATEGORIES) ColorRow(color) { color = it }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    editing = null
                    val sent = SubscriptionRules.renamedName(name, row.label, row.storedName)
                    run("subscriptions.metaSavedToast") {
                        if (row.kind == SubscriptionsStore.KIND_CATEGORIES) store.updateCategory(row.id, sent, color) else store.updateMethod(row.id, sent)
                    }
                }) { Text(t("common.save")) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(t("common.cancel")) } },
        )
    }

    deleting?.let { row ->
        val isCat = row.kind == SubscriptionsStore.KIND_CATEGORIES
        val warning = if (row.usage > 0) strings.t("subscriptions.metaInUseWarning", "count" to row.usage) + " " else ""
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(strings.t(if (isCat) "subscriptions.deleteCategoryConfirm" else "subscriptions.deletePaymentMethodConfirm", "name" to row.label)) },
            text = { Text(warning + strings.t(if (isCat) "subscriptions.deleteCategoryConfirmDetail" else "subscriptions.deletePaymentMethodConfirmDetail")) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    run("subscriptions.metaDeletedToast") { if (isCat) store.deleteCategory(row.id) else store.deleteMethod(row.id) }
                }) { Text(t("common.delete"), color = Yuvomi.colors.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(t("common.cancel")) } },
        )
    }
}

@Composable
private fun MetaRowView(row: MetaRow, index: Int, total: Int, edit: () -> Unit, delete: () -> Unit, move: (Int) -> Unit) {
    val strings = LocalStrings.current
    val isCat = row.kind == SubscriptionsStore.KIND_CATEGORIES
    Row(Modifier.fillMaxWidth().clickable(onClick = edit).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (isCat) Box(Modifier.size(14.dp).background(parseHex(row.color, Yuvomi.colors.textSecondary), CircleShape))
        else Icon(Icons.Outlined.CreditCard, null, Modifier.size(18.dp), tint = Yuvomi.colors.textSecondary)
        Spacer(Modifier.width(12.dp))
        Text(row.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        var menu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, strings.t("common.moreActionsNamed", "name" to row.label)) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(strings.t(if (isCat) "subscriptions.editCategoryNamed" else "subscriptions.editPaymentMethodNamed", "name" to row.label)) }, onClick = { menu = false; edit() })
                if (total > 1) {
                    DropdownMenuItem(text = { Text(strings.t("subscriptions.moveUpNamed", "name" to row.label)) }, enabled = index > 0, onClick = { menu = false; move(-1) })
                    DropdownMenuItem(text = { Text(strings.t("subscriptions.moveDownNamed", "name" to row.label)) }, enabled = index < total - 1, onClick = { menu = false; move(1) })
                }
                DropdownMenuItem(
                    text = { Text(strings.t(if (isCat) "subscriptions.deleteCategoryNamed" else "subscriptions.deletePaymentMethodNamed", "name" to row.label), color = Yuvomi.colors.danger) },
                    onClick = { menu = false; delete() },
                )
            }
        }
    }
}
