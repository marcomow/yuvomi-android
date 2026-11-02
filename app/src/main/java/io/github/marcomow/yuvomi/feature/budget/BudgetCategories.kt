package io.github.marcomow.yuvomi.feature.budget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The category manager (`public/components/category-manager.js` with basePath
 * `/budget/categories`): two groups, expense and income; add, rename, move and delete categories
 * and their subcategories. Every change tells the budget to reload its categories (`onChanged`).
 */
class BudgetCategoriesViewModel(
    private val container: AppContainer,
    private val strings: Strings,
    private val notify: suspend (String) -> Unit,
    private val changed: () -> Unit,
) : ViewModel() {
    data class UiState(val categories: List<BudgetCategory> = emptyList(), val loading: Boolean = true, val error: Boolean = false, val busy: Boolean = false)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val api: BudgetApi? get() = container.session.connection?.client?.let(::BudgetApi)

    init {
        load()
    }

    fun load() {
        val api = api ?: return
        viewModelScope.launch {
            try {
                _state.update { it.copy(categories = api.categories(strings.locale), loading = false, error = false) }
            } catch (e: ApiProblem) {
                _state.update { it.copy(loading = false, error = it.categories.isEmpty()) }
                notify(BudgetText.categoryError(e, strings))
            }
        }
    }

    private fun run(success: String?, block: suspend (BudgetApi) -> Unit) {
        val api = api ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block(api)
                _state.update { it.copy(categories = api.categories(strings.locale)) }
                changed()
                if (success != null) notify(strings.t(success))
            } catch (e: ApiProblem) {
                notify(BudgetText.categoryError(e, strings))
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun add(type: String, name: String) = run("category.added") { it.createCategory(name, type, YuvomiClient.newIdempotencyKey()) }

    fun rename(key: String, name: String) = run("category.renamed") { it.renameCategory(key, name) }

    fun delete(key: String) = run("category.deleted") { it.deleteCategory(key) }

    /** `_move`: one step up or down within the group, sent as the group's whole order. */
    fun move(type: String, key: String, delta: Int) {
        val group = group(type)
        val i = group.indexOfFirst { it.key == key }
        val j = i + delta
        if (i < 0 || j !in group.indices) return
        val order = group.map { it.key }.toMutableList().apply { add(j, removeAt(i)) }
        run(null) { it.reorderCategories(type, order) }
    }

    fun addSub(category: String, name: String) = run("category.added") { it.createSubcategory(category, name, YuvomiClient.newIdempotencyKey()) }

    fun renameSub(category: String, key: String, name: String) = run("category.renamed") { it.renameSubcategory(category, key, name) }

    fun deleteSub(category: String, key: String) = run("category.deleted") { it.deleteSubcategory(category, key) }

    fun moveSub(category: String, key: String, delta: Int) {
        val subs = _state.value.categories.firstOrNull { it.key == category }?.subcategories ?: return
        val i = subs.indexOfFirst { it.key == key }
        val j = i + delta
        if (i < 0 || j !in subs.indices) return
        val order = subs.map { it.key }.toMutableList().apply { add(j, removeAt(i)) }
        run(null) { it.reorderSubcategories(category, order) }
    }

    fun group(type: String) = _state.value.categories.filter { (it.type ?: "expense") == type }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetCategoriesScreen(changed: () -> Unit, close: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val vm: BudgetCategoriesViewModel = viewModel(key = "budget-categories") {
        BudgetCategoriesViewModel(shell.container, strings, { shell.notify(it) }, changed)
    }
    val s by vm.state.collectAsState()
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    var renaming by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var deleting by remember { mutableStateOf<Pair<String, String?>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("budget.manageCategories")) },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Outlined.Close, t("common.close")) } },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().imePadding()) {
            when {
                s.loading -> LoadingBox()
                s.error -> ErrorBox(t("budget.metaLoadError"), onRetry = vm::load)
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
                    item(key = "hint") {
                        Text(t("category.manageHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary, modifier = Modifier.padding(16.dp))
                    }
                    listOf("expense" to "budget.expenses", "income" to "budget.income").forEach { (type, label) ->
                        item(key = "title-$type") { SectionTitle(t(label)) }
                        val group = s.categories.filter { (it.type ?: "expense") == type }
                        group.forEachIndexed { i, c ->
                            val name = BudgetText.category(c, strings)
                            item(key = "cat-${c.key}") {
                                ManagerRow(
                                    name = name,
                                    first = i == 0,
                                    last = i == group.lastIndex,
                                    open = c.key in expanded,
                                    onToggle = { expanded = if (c.key in expanded) expanded - c.key else expanded + c.key },
                                    onRename = { renaming = c.key to null },
                                    onUp = { vm.move(type, c.key, -1) },
                                    onDown = { vm.move(type, c.key, 1) },
                                    onDelete = { deleting = c.key to null },
                                )
                            }
                            if (c.key in expanded) {
                                c.subcategories.forEachIndexed { j, sub ->
                                    item(key = "sub-${c.key}-${sub.key}") {
                                        ManagerRow(
                                            name = BudgetText.subcategory(sub, strings),
                                            first = j == 0,
                                            last = j == c.subcategories.lastIndex,
                                            indent = true,
                                            onRename = { renaming = c.key to sub.key },
                                            onUp = { vm.moveSub(c.key, sub.key, -1) },
                                            onDown = { vm.moveSub(c.key, sub.key, 1) },
                                            onDelete = { deleting = c.key to sub.key },
                                        )
                                    }
                                }
                                item(key = "sub-add-${c.key}") { AddRow(t("category.addSubPlaceholder"), indent = true) { vm.addSub(c.key, it) } }
                            }
                        }
                        item(key = "add-$type") { AddRow(t("budget.addCategory")) { vm.add(type, it) } }
                        item(key = "div-$type") { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                    }
                }
            }
        }
    }

    renaming?.let { (cat, sub) ->
        val current = if (sub == null) s.categories.firstOrNull { it.key == cat }?.let { BudgetText.category(it, strings) }
        else s.categories.firstOrNull { it.key == cat }?.subcategories?.firstOrNull { it.key == sub }?.let { BudgetText.subcategory(it, strings) }
        NameDialog(t("category.renamePrompt"), t("category.renamePrompt"), initial = current.orEmpty(), onDismiss = { renaming = null }) { name ->
            renaming = null
            if (name.isNotBlank() && name != current) if (sub == null) vm.rename(cat, name) else vm.renameSub(cat, sub, name)
        }
    }
    deleting?.let { (cat, sub) ->
        val category = s.categories.firstOrNull { it.key == cat }
        val name = if (sub == null) category?.let { BudgetText.category(it, strings) } else category?.subcategories?.firstOrNull { it.key == sub }?.let { BudgetText.subcategory(it, strings) }
        ConfirmDialog(
            title = if (sub == null) t("category.deleteConfirm", "name" to name.orEmpty()) else t("category.deleteSubConfirm", "name" to name.orEmpty()),
            detail = t(if (sub == null) "budget.categoryDeleteConfirmDetail" else "budget.subcategoryDeleteConfirmDetail"),
            confirm = t("common.delete"),
            onDismiss = { deleting = null },
        ) {
            deleting = null
            if (sub == null) vm.delete(cat) else vm.deleteSub(cat, sub)
        }
    }
}

@Composable
private fun ManagerRow(
    name: String,
    first: Boolean,
    last: Boolean,
    indent: Boolean = false,
    open: Boolean? = null,
    onToggle: (() -> Unit)? = null,
    onRename: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onRename).padding(start = if (indent) 40.dp else 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = if (indent) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (onToggle != null && open != null) {
            IconButton(onClick = onToggle) { Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, t("budget.subcategoryLabel")) }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActionsNamed", "name" to name)) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(t("category.renameNamed", "name" to name)) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(t("category.moveUpNamed", "name" to name)) }, enabled = !first, onClick = { menu = false; onUp() })
                DropdownMenuItem(text = { Text(t("category.moveDownNamed", "name" to name)) }, enabled = !last, onClick = { menu = false; onDown() })
                DropdownMenuItem(
                    text = { Text(t(if (indent) "category.deleteSubNamed" else "category.deleteNamed", "name" to name), color = Yuvomi.colors.danger) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun AddRow(placeholder: String, indent: Boolean = false, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(start = if (indent) 40.dp else 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(60) },
            placeholder = { Text(placeholder) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        IconButton(enabled = text.isNotBlank(), onClick = { onAdd(text.trim()); text = "" }) { Icon(Icons.Outlined.Add, t("common.add")) }
    }
}

