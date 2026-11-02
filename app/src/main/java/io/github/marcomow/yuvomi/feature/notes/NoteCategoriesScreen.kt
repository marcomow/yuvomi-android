package io.github.marcomow.yuvomi.feature.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.launch

/**
 * docs/spec/notes.md, "Manage categories" (NOTES-R23 to R26): the web app's category manager with
 * the notes configuration: personal categories always, the household's only for members who
 * may manage them; one add form with the type; rename, move up and down, and delete per row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteCategoriesScreen(back: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val store = remember(shell.connection) { NotesStore.of(shell.container) } ?: return
    val s by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<NoteCategory?>(null) }
    var deleting by remember { mutableStateOf<NoteCategory?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(store) { store.refreshCategories() }

    fun run(success: String?, action: suspend () -> String?) {
        if (busy) return
        busy = true
        scope.launch {
            val error = action()
            busy = false
            shell.notify(strings.t(error ?: success ?: return@launch))
        }
    }

    val groups = buildList {
        add(NoteCategory.SCOPE_PERSONAL to "noteCategories.personal")
        if (s.canManageHousehold) add(NoteCategory.SCOPE_HOUSEHOLD to "noteCategories.household")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("category.manageTitle")) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t("common.back")) } },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        ) {
            Text(
                t(if (s.canManageHousehold) "category.manageHint" else "noteCategories.personalManagementHint"),
                style = MaterialTheme.typography.bodyMedium,
                color = Yuvomi.colors.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            groups.forEach { (key, labelKey) ->
                val items = s.categories.filter { it.scope == key }
                Text(
                    t(labelKey),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
                if (items.isEmpty()) {
                    Text(
                        t("noteCategories.empty"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Yuvomi.colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items.forEachIndexed { index, c ->
                    CategoryRow(
                        c,
                        first = index == 0,
                        last = index == items.lastIndex,
                        onRename = { renaming = c },
                        onMove = { delta -> run(null) { store.moveCategory(c, delta) } },
                        onDelete = { deleting = c },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            AddForm(s.canManageHousehold, busy) { name, scopeKey ->
                if (busy) return@AddForm false
                busy = true
                val error = try {
                    store.createCategory(name, scopeKey)
                    store.load()
                    null
                } catch (e: ApiProblem) {
                    NoteErrors.key(e)
                }
                busy = false
                shell.notify(strings.t(error ?: "category.added"))
                error == null
            }
        }
    }

    renaming?.let { c ->
        RenameDialog(c, onDismiss = { renaming = null }) { name ->
            renaming = null
            if (name.isNotBlank() && name.trim() != c.name) run("category.renamed") { store.renameCategory(c, name) }
        }
    }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(t("category.deleteConfirm", "name" to c.name)) },
            text = { Text(t("noteCategories.deleteDetail")) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    run("category.deleted") { store.deleteCategory(c) }
                }) { Text(t("common.delete"), color = Yuvomi.colors.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(t("common.cancel")) } },
        )
    }
}

@Composable
private fun CategoryRow(
    c: NoteCategory,
    first: Boolean,
    last: Boolean,
    onRename: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(c.name) },
        leadingContent = { Icon(scopeIcon(c), scopeLabel(c), Modifier.size(20.dp), tint = Yuvomi.colors.textSecondary) },
        trailingContent = {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActionsNamed", "name" to c.name)) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(t("category.renameNamed", "name" to c.name)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = { menu = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(t("category.moveUpNamed", "name" to c.name)) },
                        leadingIcon = { Icon(Icons.Outlined.KeyboardArrowUp, null) },
                        enabled = !first,
                        onClick = { menu = false; onMove(-1) },
                    )
                    DropdownMenuItem(
                        text = { Text(t("category.moveDownNamed", "name" to c.name)) },
                        leadingIcon = { Icon(Icons.Outlined.KeyboardArrowDown, null) },
                        enabled = !last,
                        onClick = { menu = false; onMove(1) },
                    )
                    DropdownMenuItem(
                        text = { Text(t("category.deleteNamed", "name" to c.name), color = Yuvomi.colors.danger) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        },
        modifier = Modifier.clickable(onClickLabel = t("category.renameHint"), onClick = onRename),
    )
}

/** The unified add form (`_unifiedAddFormHtml`): name, the type when there is a choice, Add. */
@Composable
private fun AddForm(canManageHousehold: Boolean, busy: Boolean, add: suspend (String, String) -> Boolean) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var scopeKey by rememberSaveable { mutableStateOf(NoteCategory.SCOPE_PERSONAL) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(NotesRules.CATEGORY_NAME_MAX_LENGTH) },
            placeholder = { Text(t("category.addPlaceholder")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (canManageHousehold) {
            Text(t("noteCategories.scopeLabel"), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
            ScopeChoice(scopeKey) { scopeKey = it }
            Text(t("noteCategories.scopeHelp"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f))
            Button(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty()) scope.launch { if (add(trimmed, if (canManageHousehold) scopeKey else NoteCategory.SCOPE_PERSONAL)) name = "" }
                },
                enabled = !busy && name.isNotBlank(),
            ) { Text(t("common.add")) }
        }
    }
}

@Composable
private fun RenameDialog(c: NoteCategory, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by rememberSaveable(c.id) { mutableStateOf(c.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("category.renamePrompt")) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(NotesRules.CATEGORY_NAME_MAX_LENGTH) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(value) }, enabled = value.isNotBlank()) { Text(t("common.save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}
