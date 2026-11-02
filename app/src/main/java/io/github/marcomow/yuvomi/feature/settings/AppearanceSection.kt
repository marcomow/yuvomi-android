package io.github.marcomow.yuvomi.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.marcomow.yuvomi.core.i18n.LocalePicker
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.languageName
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * docs/spec/settings.md, "Appearance and region" (SET-R20 to R25): `personal-appearance.js`.
 * The display language is this device's, shared with the account sheet; everything else is
 * the household's and goes through `PUT /preferences`.
 */
@Composable
fun AppearanceSection(ctx: SectionContext) {
    val slots by ctx.vm.slots.collectAsState()
    val prefs = slots[SettingsSheetViewModel.PREFERENCES]?.value as? JsonObject
    val ui = Locale.forLanguageTag(ctx.strings.locale)
    val chosen by ctx.shell.container.settings.language.collectAsState(initial = null)
    fun hintWith(hint: String, current: String?) = if (current != null) ctx.strings.t("settings.hintWithCurrent", "value" to current, "hint" to hint) else hint

    // Theme: the app follows the device's light or dark setting, as the web app's "System".
    SettingsGroup(title = t("settings.languageTitle"), footer = if (ctx.isAdmin) null else t("settings.dataLanguageAdminOnly")) {
        val languages = listOf("" to t("android.account.automatic")) + LocalePicker.SUPPORTED.map { it to languageName(it) }.sortedBy { it.second.lowercase() }
        ChoiceRow(t("settings.localeLabel"), languages, chosen ?: "", hint = t("android.settings.languageDevice")) { code ->
            ctx.scope.launch { ctx.shell.container.settings.setLanguage(code.ifEmpty { null }) }
        }
        if (ctx.isAdmin && prefs != null) {
            RowDivider()
            val selected = prefs.str("language")
            val auto = prefs.str("language_auto") ?: "en"
            val options = listOf("" to t("settings.dataLanguageAuto")) + LocalePicker.SUPPORTED.map { it to languageName(it) }
            ChoiceRow(
                t("settings.dataLanguageLabel"), options, selected ?: "", enabled = ctx.editable,
                hint = hintWith(t("settings.dataLanguageHint"), if (selected == null) languageName(auto) else null),
            ) { code ->
                ctx.save("settings.dataLanguageSaved", buildJsonObject { if (code.isEmpty()) put("language", JsonNull) else put("language", code) })
            }
        }
    }

    if (prefs == null) {
        val slot = slots[SettingsSheetViewModel.PREFERENCES]
        if (slot?.errorKey != null) SettingsGroup(footer = t(slot.errorKey)) { ActionRow(t("settings.retry")) { ctx.vm.loadPreferences(force = true) } }
        else Busy(true)
        return
    }

    val currency = prefs.str("currency")
    val dateFormat = prefs.str("date_format")
    val timeFormat = prefs.str("time_format")
    val region = RegionRules.resolve(prefs.str("region"), currency, dateFormat, timeFormat)
    var custom by remember(region) { mutableStateOf(region == RegionRules.CUSTOM) }
    val zone = prefs.str("timezone")
    val effective = prefs.str("timezone_effective") ?: "UTC"

    if (ctx.isAdmin) {
        SettingsGroup(title = t("settings.regionTitle")) {
            val regions = RegionRules.PRESETS.keys.map { it to RegionRules.label(it, ui) }.sortedBy { it.second.lowercase(ui) } +
                (RegionRules.CUSTOM to t("settings.regionCustom"))
            ChoiceRow(
                t("settings.regionLabel"), regions, if (custom) RegionRules.CUSTOM else region, enabled = ctx.editable, searchable = true,
                hint = t("settings.regionHint"),
            ) { code ->
                if (code == RegionRules.CUSTOM) {
                    custom = true
                    return@ChoiceRow
                }
                val preset = RegionRules.PRESETS[code] ?: return@ChoiceRow
                ctx.save("settings.regionSaved", buildJsonObject {
                    put("currency", preset.currency)
                    put("date_format", preset.dateFormat)
                    put("time_format", preset.timeFormat)
                    put("region", code)
                })
            }
            RowDivider()
            ChoiceRow(
                t("settings.currencyLabel"), RegionRules.CURRENCIES.map { it to RegionRules.currencyLabel(it, ui) }, currency,
                enabled = ctx.editable, searchable = true, hint = t("settings.currencyHint"),
            ) { code ->
                // The region is not written: it stays while the new triple still derives it, else it reads as custom (#934).
                ctx.save("settings.currencySaved", buildJsonObject { put("currency", code) })
            }
            RowDivider()
            ChoiceRow(
                t("settings.timezoneLabel"),
                listOf("" to t("settings.timezoneAuto")) + RegionRules.zones().map { it to it.replace('_', ' ') },
                zone ?: "", enabled = ctx.editable, searchable = true,
                hint = hintWith(t("settings.timezoneHint"), if (zone == null) effective else null),
            ) { value ->
                ctx.save("settings.timezoneSaved", buildJsonObject { if (value.isEmpty()) put("timezone", JsonNull) else put("timezone", value) })
            }
        }
    } else {
        SettingsGroup(title = t("settings.regionTitle"), footer = t("settings.regionAdminOnly")) {
            ValueRow(t("settings.timezoneTitle"), zone?.replace('_', ' ') ?: t("settings.timezoneAuto"), hintWith(t("settings.timezoneAdminOnly"), if (zone == null) effective else null))
        }
    }

    if (!ctx.isAdmin || custom) {
        SettingsGroup(footer = t("settings.formatsHouseholdHint")) {
            ChoiceRow(t("settings.dateFormatLabel"), RegionRules.DATE_FORMATS, dateFormat, enabled = ctx.editable) {
                ctx.save("settings.dateFormatSavedToast", buildJsonObject { put("date_format", it) })
            }
            RowDivider()
            ChoiceRow(t("settings.timeFormatLabel"), listOf("24h" to "24 " + t("settings.timeFormatHours"), "12h" to "AM/PM"), timeFormat, enabled = ctx.editable) {
                ctx.save("settings.timeFormatSavedToast", buildJsonObject { put("time_format", it) })
            }
        }
    }
}
