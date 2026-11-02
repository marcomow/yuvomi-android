package io.github.marcomow.yuvomi.feature.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.Tokens
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.launch

/** docs/spec/notes.md, the editor for a new note ([noteId] null) or an existing one (NOTES-R17 to R22). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NoteEditorScreen(noteId: Int?, close: () -> Unit, deleted: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val store = remember(shell.connection) { NotesStore.of(shell.container) } ?: return
    val board by store.state.collectAsState()
    val vm = viewModel(key = "note-editor-$noteId") { NoteEditorViewModel(store, noteId) }
    val s by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val viewer = shell.noteViewer(offline = false)
    val solo = shell.session.soloHousehold
    val f = s.form
    var confirmDiscard by remember { mutableStateOf(false) }
    var detailsOpen by rememberSaveable(s.ready) { mutableStateOf(s.original.detailsOpen) }
    var reading by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(s.messageKey) {
        s.messageKey?.let {
            vm.messageShown()
            shell.notify(strings.t(it))
        }
    }
    LaunchedEffect(s.outcome) {
        when (s.outcome) {
            NoteEditorViewModel.Outcome.CREATED -> { close(); shell.notify(strings.t("notes.createdToast")) }
            NoteEditorViewModel.Outcome.SAVED -> { close(); shell.notify(strings.t("notes.savedToast")) }
            NoteEditorViewModel.Outcome.GONE -> close()
            null -> Unit
        }
    }

    fun leave() = if (s.dirty) confirmDiscard = true else close()
    BackHandler(enabled = s.dirty && s.outcome == null) { confirmDiscard = true }

    val note = s.note
    val sendVisibility = viewer.mayChangeVisibility(note)
    val sendLock = viewer.mayChangeLock(note)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(if (noteId == null) "notes.newNote" else "notes.editNote")) },
                navigationIcon = { IconButton(onClick = ::leave) { Icon(Icons.Outlined.Close, t("common.cancel")) } },
                actions = {
                    if (note != null && viewer.mayChange(note)) {
                        IconButton(onClick = {
                            deleted()
                            store.scheduleDelete(note, strings) { m, a -> shell.notify(m, a) }
                        }) { Icon(Icons.Outlined.Delete, t("common.delete")) }
                    }
                    TextButton(onClick = { if (f.content.isBlank()) reading = false; vm.save(sendVisibility, sendLock) }, enabled = s.ready && !s.saving && !s.creatingCategory) {
                        Text(t(if (noteId == null) "common.add" else "common.save"))
                    }
                },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        if (!s.ready) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Read | Edit (#507): the read tab shows the text as it will look, unsaved changes included.
            ModeSwitch(reading) { reading = it }
            if (reading) {
                ReadTab(f, board.categories, live = note != null && f.content == note.content && viewer.mayCheck(note)) { line, checked ->
                    scope.launch { vm.check(line, checked)?.let { shell.notify(strings.t(it)) } }
                }
            } else {
                OutlinedTextField(
                    value = f.title,
                    onValueChange = { v -> vm.edit { it.copy(title = v) } },
                    placeholder = { Text(t("notes.titleLabel")) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = strings.t("notes.titleLabel") },
                )
                FormatToolbar { format ->
                    vm.format(
                        format,
                        MarkdownFormat.Placeholders(
                            strings.t("markdown.placeholderText"),
                            strings.t("markdown.placeholderCode"),
                            strings.t("markdown.placeholderLinkText"),
                            strings.t("markdown.placeholderUrl"),
                        ),
                    )
                }
                OutlinedTextField(
                    value = s.content,
                    onValueChange = vm::setContent,
                    placeholder = { Text(t("notes.contentPlaceholder")) },
                    supportingText = {
                        Text(if (s.contentMissing) t("common.contentRequired") else t("notes.contentMarkdownHint"))
                    },
                    isError = s.contentMissing,
                    minLines = 8,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = strings.t("notes.contentLabel") },
                )
            }
            if (reading) return@Column

            CategoryEditor(s, board.categories, board.canManageHousehold, vm) {
                scope.launch { vm.createCategory(board.canManageHousehold)?.let { shell.notify(strings.t(it)) } }
            }

            // "More details" (advancedSection): colour, pin, visibility, lock.
            Row(
                Modifier.fillMaxWidth().clickable { detailsOpen = !detailsOpen }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(t("modal.moreDetails"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (detailsOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (detailsOpen) {
                Text(t("notes.colorLabel"), style = MaterialTheme.typography.labelLarge, color = Yuvomi.colors.textSecondary)
                ColorPicker(f.color) { c -> vm.edit { it.copy(color = c) } }
                SwitchRow(t("notes.pinnedLabel"), null, f.pinned) { v -> vm.edit { it.copy(pinned = v) } }
                // Hidden, not removed, in a household of one: the stored value is still sent (`renderAccessFields`).
                if (sendVisibility && !solo) {
                    Text(t("common.visibility.label"), style = MaterialTheme.typography.labelLarge, color = Yuvomi.colors.textSecondary)
                    listOf(Note.VISIBILITY_ALL to "common.visibility.all", Note.VISIBILITY_PRIVATE to "common.visibility.private").forEach { (value, key) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(f.visibility == value, role = Role.RadioButton) { vm.edit { it.copy(visibility = value) } },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = f.visibility == value, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(t(key))
                        }
                    }
                    Text(t("common.visibility.hint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }
                if (sendLock && !solo) {
                    SwitchRow(t("notes.lockedToggle"), t("notes.lockedHint"), f.locked) { v -> vm.edit { it.copy(locked = v) } }
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(t("modal.unsavedChanges")) },
            text = { Text(t("modal.unsavedChangesDetail")) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; close() }) { Text(t("modal.discardChanges"), color = Yuvomi.colors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(t("common.cancel")) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSwitch(reading: Boolean, onChange: (Boolean) -> Unit) {
    val label = t("notes.modeSwitchLabel")
    val options = listOf(true to "notes.modeRead", false to "notes.modeEdit")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().semantics { contentDescription = label }) {
        options.forEachIndexed { i, (value, key) ->
            SegmentedButton(
                selected = reading == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                label = { Text(t(key)) },
            )
        }
    }
}

/** The read tab: chosen categories, then the text on the note's colour. */
@Composable
private fun ReadTab(f: NoteForm, categories: List<NoteCategory>, live: Boolean, onCheck: (Int, Boolean) -> Unit) {
    CategoryBadges(f.categoryIds.mapNotNull { id -> categories.firstOrNull { it.id == id } })
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = NoteColors.tint(f.color, MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (f.content.isBlank()) {
                Text(t("notes.readEmpty"), color = Yuvomi.colors.textSecondary)
            } else {
                NoteMarkdownView(f.content, onCheck = if (live) ({ line, checked, _ -> onCheck(line, checked) }) else null)
            }
        }
    }
}

private val FORMAT_ICONS: Map<MarkdownFormat.Format, ImageVector> = mapOf(
    MarkdownFormat.Format.BOLD to Icons.Outlined.FormatBold,
    MarkdownFormat.Format.ITALIC to Icons.Outlined.FormatItalic,
    MarkdownFormat.Format.UNDERLINE to Icons.Outlined.FormatUnderlined,
    MarkdownFormat.Format.STRIKETHROUGH to Icons.Outlined.FormatStrikethrough,
    MarkdownFormat.Format.HEADING to Icons.Outlined.Title,
    MarkdownFormat.Format.LIST to Icons.AutoMirrored.Outlined.FormatListBulleted,
    MarkdownFormat.Format.ORDERED_LIST to Icons.Outlined.FormatListNumbered,
    MarkdownFormat.Format.CHECKLIST to Icons.Outlined.Checklist,
    MarkdownFormat.Format.LINK to Icons.Outlined.Link,
    MarkdownFormat.Format.CODE to Icons.Outlined.Code,
    MarkdownFormat.Format.QUOTE to Icons.Outlined.FormatQuote,
    MarkdownFormat.Format.DIVIDER to Icons.Outlined.HorizontalRule,
)

/** NOTES-R17: one scrolling row, like the web toolbar. */
@Composable
private fun FormatToolbar(onFormat: (MarkdownFormat.Format) -> Unit) {
    val label = t("markdown.toolbarLabel")
    Row(Modifier.horizontalScroll(rememberScrollState()).semantics { contentDescription = label }) {
        MarkdownFormat.Format.entries.forEach { format ->
            IconButton(onClick = { onFormat(format) }) { Icon(FORMAT_ICONS.getValue(format), t(format.labelKey)) }
        }
    }
}

/** NOTES-R22: chosen categories as chips, a search that suggests and can create. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditor(
    s: NoteEditorViewModel.UiState,
    categories: List<NoteCategory>,
    canManageHousehold: Boolean,
    vm: NoteEditorViewModel,
    create: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(t("noteCategories.categories"), style = MaterialTheme.typography.labelLarge, color = Yuvomi.colors.textSecondary)
        val selected = s.form.categoryIds.mapNotNull { id -> categories.firstOrNull { it.id == id } }
        if (selected.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                selected.forEach { c ->
                    InputChip(
                        selected = true,
                        onClick = { vm.removeCategory(c.id) },
                        label = { Text(c.name) },
                        leadingIcon = { Icon(scopeIcon(c), scopeLabel(c), Modifier.size(16.dp)) },
                        trailingIcon = {
                            Icon(Icons.Outlined.Close, t("noteCategories.removeAction", "name" to c.name), Modifier.size(InputChipDefaults.AvatarSize))
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = s.categoryQuery,
            onValueChange = vm::setCategoryQuery,
            placeholder = { Text(t("noteCategories.searchPlaceholder")) },
            singleLine = true,
            isError = s.categoryNameMissing,
            supportingText = if (s.categoryNameMissing) ({ Text(t("common.required")) }) else null,
            modifier = Modifier.fillMaxWidth(),
        )
        val suggestions = NotesRules.suggestions(categories, s.form.categoryIds, s.categoryQuery)
        if (suggestions.isNotEmpty()) {
            val label = t("noteCategories.searchResultsLabel")
            FlowRow(
                Modifier.semantics { contentDescription = label },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                suggestions.forEach { c ->
                    SuggestionChip(
                        onClick = { vm.selectCategory(c) },
                        label = { Text(c.name) },
                        icon = { Icon(scopeIcon(c), scopeLabel(c), Modifier.size(16.dp)) },
                    )
                }
            }
        }
        val scope = if (canManageHousehold) s.creationScope else NoteCategory.SCOPE_PERSONAL
        val canCreate = NotesRules.canCreate(categories, s.categoryQuery, scope)
        if (canManageHousehold) {
            Text(t("noteCategories.scopeLabel"), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
            ScopeChoice(s.creationScope, vm::setCreationScope)
            Text(t("noteCategories.scopeHelp"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        }
        if (canCreate) {
            OutlinedButton(onClick = create, enabled = !s.creatingCategory && !s.saving) {
                Text(t("noteCategories.createAction", "name" to s.categoryQuery.trim()))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScopeChoice(selected: String, onSelect: (String) -> Unit) {
    val options = listOf(NoteCategory.SCOPE_PERSONAL to "noteCategories.personal", NoteCategory.SCOPE_HOUSEHOLD to "noteCategories.household")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, key) ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                label = { Text(t(key)) },
            )
        }
    }
}

/** The web's colour picker without "custom": the eight pastels, plus a stored colour outside them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPicker(selected: String, onSelect: (String) -> Unit) {
    val options = NoteColors.palette.map { it.first to t(it.second) }.let { list ->
        if (NoteColors.isPalette(selected)) list else list + (selected to selected)
    }
    val label = t("notes.colorLabel")
    FlowRow(Modifier.semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { (hex, name) ->
            val color = Tokens.parseColor(hex) ?: MaterialTheme.colorScheme.surface
            val on = hex.equals(selected, ignoreCase = true)
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(if (on) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, Yuvomi.colors.border), CircleShape)
                    .selectable(on, role = Role.RadioButton) { onSelect(hex) }
                    .semantics { contentDescription = name },
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
