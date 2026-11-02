package io.github.marcomow.yuvomi.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.Card
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t

/** A heading above a group, like upstream's `settings-section__title`. */
@Composable
fun GroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 6.dp),
    )
}

/** A hint line under or above a group (`form-hint settings-group__footer`). */
@Composable
fun Hint(text: String, color: Color = Yuvomi.colors.textSecondary, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = modifier.padding(horizontal = 4.dp, vertical = 6.dp))
}

/** `row-carrier settings-group`: rows on one card, divided. */
@Composable
fun SettingsGroup(title: String? = null, footer: String? = null, content: @Composable () -> Unit) {
    Column {
        if (title != null) GroupTitle(title)
        Card { Column { content() } }
        if (footer != null) Hint(footer)
    }
}

@Composable
fun RowDivider() = HorizontalDivider(Modifier.padding(start = 16.dp), color = Yuvomi.colors.border)

private val rowColors @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

/** `settingSwitchRowHtml`. */
@Composable
fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, hint: String? = null, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = hint?.let { { Text(it, color = Yuvomi.colors.textSecondary) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = rowColors,
        modifier = Modifier.clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) },
    )
}

/** A label with its value; tapping it runs [onClick] when given. */
@Composable
fun ValueRow(label: String, value: String?, hint: String? = null, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = if (value != null || hint != null) ({
            Column {
                if (value != null) Text(value, color = if (onClick != null && enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            }
        }) else null,
        colors = rowColors,
        modifier = if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier,
    )
}

/** A row that does something: a button in upstream's card footers. */
@Composable
fun ActionRow(label: String, hint: String? = null, danger: Boolean = false, enabled: Boolean = true, icon: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label, color = if (danger) Yuvomi.colors.danger else MaterialTheme.colorScheme.primary) },
        supportingContent = hint?.let { { Text(it, color = Yuvomi.colors.textSecondary) } },
        leadingContent = icon,
        colors = rowColors,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

/** A choice from a list, shown as its current label; tap opens [ChoiceDialog]. */
@Composable
fun ChoiceRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String?,
    enabled: Boolean = true,
    hint: String? = null,
    searchable: Boolean = false,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ValueRow(label, options.firstOrNull { it.first == selected }?.second ?: selected, hint, enabled) { open = true }
    if (open) {
        ChoiceDialog(label, options, selected, searchable, onDismiss = { open = false }) {
            open = false
            if (it != selected) onSelect(it)
        }
    }
}

@Composable
fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String?,
    searchable: Boolean = false,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (searchable) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
                val shown = if (query.isBlank()) options else options.filter { (k, v) -> v.contains(query.trim(), true) || k.contains(query.trim(), true) }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(shown, key = { it.first }) { (value, text) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onSelect(value) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = value == selected, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(text)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}

/** A section that stays in the web app (ADR 0006): named, with the way there. */
@Composable
fun WebSectionCard(section: SettingsRegistry.Section, openWeb: (String) -> Unit) {
    SettingsGroup {
        ListItem(
            headlineContent = { Text(t(section.labelKey)) },
            supportingContent = { Text(t(section.descriptionKey), color = Yuvomi.colors.textSecondary) },
            trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, t("android.browser.opensInBrowser"), Modifier.size(20.dp)) },
            colors = rowColors,
            modifier = Modifier.clickable(onClickLabel = t("android.browser.open")) { openWeb(SettingsRegistry.webPath(section)) },
        )
    }
}

/** A text field with label and inline error, upstream's `formRowHtml`. */
@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    error: String? = null,
    hint: String? = null,
    enabled: Boolean = true,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = (error ?: hint)?.let { { Text(it) } },
        enabled = enabled,
        singleLine = singleLine,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Padding inside a group for forms. */
@Composable
fun GroupBody(content: @Composable () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** The right sentence for an app-only fallback: upstream's key when shipped, else ours (see SET-R40). */
@Composable
fun tOr(key: String, fallback: String, vararg args: Pair<String, Any?>): String {
    val strings = LocalStrings.current
    return if (strings.has(key)) strings.t(key, args.toMap()) else strings.t(fallback, args.toMap())
}

/** A busy marker to the right of a title. */
@Composable
fun Busy(on: Boolean) {
    if (on) Box(Modifier.padding(8.dp)) { androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
}
