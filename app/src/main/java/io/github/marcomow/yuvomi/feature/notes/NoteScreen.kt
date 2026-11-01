package io.github.marcomow.yuvomi.feature.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.OfflineBanner
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * docs/spec/notes.md, one note read (NOTES-R11, R12, R14 to R16): the web app's read view, which
 * is where a tap on a card leads. Edit, pin and delete for whoever may change it; checklist
 * boxes for whoever may tick; a sign of why for whoever may do neither.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteScreen(noteId: Int, back: () -> Unit, edit: (Int) -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val store = remember(shell.connection) { NotesStore.of(shell.container) } ?: return
    val s by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val viewer = shell.noteViewer(s.offline)
    val note = s.note(noteId)

    // Opened from elsewhere (the overview, a link): the board may not be loaded yet.
    LaunchedEffect(store, noteId) { if (!s.loaded) store.refresh() }

    fun report(key: String?) {
        if (key != null) scope.launch { shell.notify(strings.t(key)) }
    }

    val mayChange = note != null && viewer.mayChange(note)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(note?.title?.trim()?.takeIf { it.isNotEmpty() } ?: t("notes.viewNote")) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t("common.back")) } },
                actions = {
                    if (note != null && mayChange) {
                        IconButton(onClick = { edit(note.id) }) { Icon(Icons.Outlined.Edit, t("common.edit")) }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActions")) }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(t(if (note.pinned) "notes.unpinAction" else "notes.pinAction")) },
                                    leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                                    onClick = { menu = false; scope.launch { report(store.togglePin(note)) } },
                                )
                                DropdownMenuItem(
                                    text = { Text(t("common.delete"), color = Yuvomi.colors.danger) },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger) },
                                    onClick = {
                                        menu = false
                                        back()
                                        store.scheduleDelete(note, strings) { m, a -> shell.notify(m, a) }
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                note == null && (s.loading || s.refreshing || !s.loaded && !s.offline) -> LoadingBox()
                note == null -> EmptyState(t("notes.errorNoteGone"))
                else -> {
                    if (s.offline) OfflineBanner(shell.formats.dateTime(Instant.ofEpochMilli(s.cachedAt!!).atZone(shell.time.zone)))
                    NoteBody(note, viewer, shell.notesReadOnly()) { line, checked -> scope.launch { report(store.check(note.id, line, checked)) } }
                }
            }
        }
    }
}

@Composable
private fun NoteBody(note: Note, viewer: NoteViewer, moduleReadOnly: Boolean, onCheck: (Int, Boolean) -> Unit) {
    val surface = MaterialTheme.colorScheme.surfaceContainer
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // A foreign note without the right to change it: the reason, instead of a missing Edit.
        if (!moduleReadOnly && !viewer.mayChange(note) && !viewer.readOnly && !note.creatorName.isNullOrEmpty()) {
            Text(
                t("common.foreignReadOnlyHint", "name" to note.creatorName),
                style = MaterialTheme.typography.bodySmall,
                color = Yuvomi.colors.textSecondary,
            )
        }
        CategoryBadges(note.categories)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = NoteColors.tint(note.color, surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                if (note.content.isBlank()) {
                    Text(t("notes.readEmpty"), color = Yuvomi.colors.textSecondary)
                } else {
                    NoteMarkdownView(
                        note.content,
                        onCheck = if (viewer.mayCheck(note)) ({ line, checked, _ -> onCheck(line, checked) }) else null,
                    )
                }
            }
        }
        CreatorLine(note)
    }
}
