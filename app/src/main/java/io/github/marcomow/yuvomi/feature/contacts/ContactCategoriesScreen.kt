package io.github.marcomow.yuvomi.feature.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t

/** `category-manager.js` labels its tones in this order. */
private val COLOR_LABELS = listOf(
    "category.colorGreen", "category.colorOcher", "category.colorViolet", "category.colorTeal",
    "category.colorOrange", "category.colorRed", "category.colorGrey",
)

/** docs/spec/contacts.md, CONT-R16: add, rename, recolour, reorder and delete categories. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ContactCategoriesScreen(vm: ContactCategoriesViewModel, back: () -> Unit) {
    val strings = LocalStrings.current
    val s by vm.state.collectAsState()
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<ContactCategory?>(null) }
    var deleting by remember { mutableStateOf<ContactCategory?>(null) }
    var coloring by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("contacts.manageCategories")) },
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
                        Text(t("category.manageHint"), style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary, modifier = Modifier.padding(16.dp))
                    }
                    itemsIndexed(s.categories, key = { _, c -> c.key }) { i, c ->
                        val name = ContactsRules.categoryLabel(c, strings)
                        Column {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (s.colors.isNotEmpty()) {
                                    val tone = categoryTone(c.color)
                                    Box(
                                        Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(tone ?: MaterialTheme.colorScheme.surfaceVariant)
                                            .clickable { coloring = if (coloring == c.key) null else c.key }
                                            .semantics { contentDescription = strings.t("category.colorPick") },
                                    )
                                    Spacer(Modifier.width(12.dp))
                                }
                                Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).clickable { renaming = c })
                                var menu by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActionsNamed", "name" to name)) }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(text = { Text(t("category.renameNamed", "name" to name)) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; renaming = c })
                                        DropdownMenuItem(
                                            text = { Text(t("category.moveUpNamed", "name" to name)) },
                                            leadingIcon = { Icon(Icons.Outlined.KeyboardArrowUp, null) },
                                            enabled = i > 0,
                                            onClick = { menu = false; vm.move(c.key, -1) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(t("category.moveDownNamed", "name" to name)) },
                                            leadingIcon = { Icon(Icons.Outlined.KeyboardArrowDown, null) },
                                            enabled = i < s.categories.lastIndex,
                                            onClick = { menu = false; vm.move(c.key, 1) },
                                        )
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text(t("category.deleteNamed", "name" to name), color = Yuvomi.colors.danger) },
                                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger) },
                                            onClick = { menu = false; deleting = c },
                                        )
                                    }
                                }
                            }
                            if (coloring == c.key) {
                                FlowRow(Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Swatch(null, strings.t("category.colorNone"), c.color == null) { coloring = null; vm.recolor(c.key, null) }
                                    s.colors.forEachIndexed { j, color ->
                                        Swatch(color, strings.t(COLOR_LABELS.getOrElse(j) { "category.colorPick" }), c.color == color) { coloring = null; vm.recolor(c.key, color) }
                                    }
                                }
                            }
                        }
                    }
                    item(key = "add") {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newName,
                                onValueChange = { newName = it.take(100) },
                                placeholder = { Text(t("category.addPlaceholder")) },
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

    renaming?.let { c ->
        var value by remember(c.key) { mutableStateOf(ContactsRules.categoryLabel(c, strings)) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(t("category.renamePrompt")) },
            text = { OutlinedTextField(value = value, onValueChange = { value = it.take(100) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { renaming = null; vm.rename(c.key, value) }, enabled = value.isNotBlank()) { Text(t("common.save")) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(t("common.cancel")) } },
        )
    }

    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(t("category.deleteConfirm", "name" to ContactsRules.categoryLabel(c, strings))) },
            text = { Text(t("category.deleteConfirmDetail")) },
            confirmButton = { TextButton(onClick = { deleting = null; vm.delete(c.key) }) { Text(t("common.delete"), color = Yuvomi.colors.danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(t("common.cancel")) } },
        )
    }
}

@Composable
private fun Swatch(css: String?, label: String, selected: Boolean, onClick: () -> Unit) {
    val tone = categoryTone(css)
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(tone ?: MaterialTheme.colorScheme.surfaceVariant)
            .border(if (selected) 2.dp else 0.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(18.dp))
    }
}
