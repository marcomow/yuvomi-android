package io.github.marcomow.yuvomi.feature.contacts

import android.content.Context
import android.content.Intent
import android.icu.text.DateFormat
import android.icu.util.TimeZone
import android.icu.util.ULocale
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.design.initials
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date

/** A category's curated tone (`var(--color-success)`, `var(--module-budget)`) as a colour, or null. */
@Composable
fun categoryTone(css: String?): Color? {
    val name = css?.let { Regex("var\\(--([a-z0-9-]+)\\)").find(it)?.groupValues?.get(1) } ?: return null
    val colors = Yuvomi.colors
    return colors.tokens.color(name, colors.isDark)
}

/** `contactAvatar`: a member in their own colour and picture, everyone else as initials on a quiet disc. */
@Composable
fun ContactAvatar(c: Contact, size: Dp = 40.dp) {
    if (c.isMember) {
        MemberAvatar(c.familyDisplayName ?: c.name, c.familyAvatarColor ?: "#8E8E93", c.familyAvatarData, size = size)
        return
    }
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials(c.name.ifBlank { "?" }),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.38f).sp,
            maxLines = 1,
        )
    }
}

/** `birthDateText`: the full date, or day and month written out when the year is unknown. */
fun birthDateText(date: String?, yearUnknown: Boolean, locale: String): String {
    val d = date?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: return date.orEmpty()
    val format = DateFormat.getInstanceForSkeleton(if (yearUnknown) "MMMMd" else "yMMMd", ULocale.forLanguageTag(locale))
    format.timeZone = TimeZone.getTimeZone("UTC")
    return format.format(Date.from(d.atStartOfDay(ZoneOffset.UTC).toInstant()))
}

/** A month's name in the UI language, for the yearless birthday picker. */
fun monthName(month: Int, locale: String): String {
    val format = DateFormat.getInstanceForSkeleton("MMMM", ULocale.forLanguageTag(locale))
    format.timeZone = TimeZone.getTimeZone("UTC")
    return format.format(Date.from(LocalDate.of(ContactsRules.YEAR_UNKNOWN_PLACEHOLDER, month, 1).atStartOfDay(ZoneOffset.UTC).toInstant()))
}

/** The web app's export: the server's vCard, here through the system's "save as" (CONT-R15). */
@Composable
fun rememberVCardSaver(fetch: suspend (Int) -> ByteArray?): (Contact) -> Unit {
    val context = LocalContext.current
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val pending = remember { arrayOfNulls<Contact>(1) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/vcard")) { uri: Uri? ->
        val contact = pending[0] ?: return@rememberLauncherForActivityResult
        pending[0] = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = fetch(contact.id) ?: return@launch
            val ok = runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null }.getOrDefault(false)
            shell.notify(strings.t(if (ok) "android.contacts.exported" else "common.errorGeneric"))
        }
    }
    return { contact ->
        pending[0] = contact
        runCatching { saver.launch(vcardFileName(contact.name)) }.onFailure {
            pending[0] = null
            scope.launch { shell.notify(strings.t("android.contacts.noApp")) }
        }
    }
}

/** The server's file name: everything outside `[a-zA-Z0-9-_ ]` becomes `_`. */
fun vcardFileName(name: String): String = name.replace(Regex("[^a-zA-Z0-9-_ ]"), "_").ifBlank { "contact" } + ".vcf"

/** Reads a picked `.vcf` as text; null when it cannot be read. */
fun readText(context: Context, uri: Uri): String? =
    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()

/** Starts [intents] in order, or says that no app can do it. */
suspend fun startOrSay(context: Context, notify: suspend (String) -> Unit, noApp: String, vararg intents: Intent) {
    if (!ContactIntents.start(context, *intents)) notify(noApp)
}
