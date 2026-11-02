package io.github.marcomow.yuvomi.feature.inventory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.t

/** docs/spec/inventory.md, INV-R19 and R20: categories, or places with sub-places. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryManagerScreen(vm: InventoryManagerViewModel, back: () -> Unit) {
    val s by vm.state.collectAsState()
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<InventoryManagerViewModel.Row?>(null) }
    var deleting by remember { mutableStateOf<InventoryManagerViewModel.Row?>(null) }
    var addingSub by remember { mutableStateOf<InventoryManagerViewModel.Row?>(null) }
    val title = t(if (vm.locations) "inventory.manageLocations" else "inventory.manageCategories")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t("common.back")) } },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                s.loading -> LoadingBox()
                s.error != null -> ErrorBox(s.error!!, onRetry = vm::load)
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "hint") {
                        Text(
                            t(if (vm.locations) "inventory.manageLocationsHint" else "inventory.manageCategoriesHint"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Yuvomi.colors.textSecondary,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    s.rows.forEachIndexed { i, row ->
                        item(key = "r-${row.key}") {
                            ManagerRow(vm, row, i, s.rows.size, indent = false, onRename = { renaming = row }, onDelete = { deleting = row }, onAddSub = if (vm.locations) ({ addingSub = row }) else null)
                        }
                        row.children.forEachIndexed { j, child ->
                            item(key = "c-${child.key}") {
                                ManagerRow(vm, child, j, row.children.size, indent = true, onRename = { renaming = child }, onDelete = { deleting = child }, onAddSub = null)
                            }
                        }
                    }
                    item(key = "add") {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newName,
                                onValueChange = { newName = it.take(100) },
                                placeholder = { Text(t(if (vm.locations) "inventory.addLocation" else "inventory.addCategory")) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { vm.add(newName); newName = "" }, enabled = newName.isNotBlank() && !s.busy) { Text(t("common.add")) }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { row ->
        NameDialog(t("category.renamePrompt"), row.label, t("common.save"), onDismiss = { renaming = null }) { vm.rename(row, it); renaming = null }
    }
    addingSub?.let { row ->
        NameDialog(t("category.addSubPlaceholder"), "", t("common.add"), onDismiss = { addingSub = null }) { vm.add(it, row); addingSub = null }
    }
    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(t(if (row.parentId != null) "category.deleteSubConfirm" else "category.deleteConfirm", "name" to row.label)) },
            text = { Text(t(if (vm.locations) "inventory.locationDeleteConfirmDetail" else "inventory.categoryDeleteConfirmDetail")) },
            confirmButton = { TextButton(onClick = { deleting = null; vm.delete(row) }) { Text(t("common.delete"), color = Yuvomi.colors.danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(t("common.cancel")) } },
        )
    }
}

@Composable
private fun ManagerRow(
    vm: InventoryManagerViewModel,
    row: InventoryManagerViewModel.Row,
    index: Int,
    count: Int,
    indent: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onAddSub: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (indent) 40.dp else 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.label, style = if (indent) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).clickable(onClick = onRename))
        var menu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActionsNamed", "name" to row.label)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(t("category.renameNamed", "name" to row.label)) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; onRename() })
                if (onAddSub != null) {
                    DropdownMenuItem(text = { Text(t("category.addSubPlaceholder")) }, leadingIcon = { Icon(Icons.Outlined.Add, null) }, onClick = { menu = false; onAddSub() })
                }
                DropdownMenuItem(text = { Text(t("category.moveUpNamed", "name" to row.label)) }, leadingIcon = { Icon(Icons.Outlined.KeyboardArrowUp, null) }, enabled = index > 0, onClick = { menu = false; vm.move(row, -1) })
                DropdownMenuItem(text = { Text(t("category.moveDownNamed", "name" to row.label)) }, leadingIcon = { Icon(Icons.Outlined.KeyboardArrowDown, null) }, enabled = index < count - 1, onClick = { menu = false; vm.move(row, 1) })
                if (vm.deletable(row)) {
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(t(if (row.parentId != null) "category.deleteSubNamed" else "category.deleteNamed", "name" to row.label), color = Yuvomi.colors.danger) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it.take(100) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}
