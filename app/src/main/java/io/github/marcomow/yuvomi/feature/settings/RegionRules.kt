package io.github.marcomow.yuvomi.feature.settings

import java.time.ZoneId
import java.util.Currency
import java.util.Locale

/**
 * Region, currency, date and time formats (`region-presets.js`, `currency-codes.js`,
 * `personal-appearance.js`), pure (SET-R20 to R24).
 */
object RegionRules {
    data class Preset(val currency: String, val dateFormat: String, val timeFormat: String)

    const val CUSTOM = "custom"

    val PRESETS: Map<String, Preset> = linkedMapOf(
        "de-DE" to Preset("EUR", "dmy", "24h"), "de-AT" to Preset("EUR", "dmy", "24h"), "de-CH" to Preset("CHF", "dmy", "24h"),
        "en-US" to Preset("USD", "mdy", "12h"), "en-GB" to Preset("GBP", "dmy_slash", "24h"), "en-CA" to Preset("CAD", "ymd", "12h"),
        "en-AU" to Preset("AUD", "dmy_slash", "12h"), "en-NZ" to Preset("NZD", "dmy_slash", "12h"), "en-PH" to Preset("PHP", "mdy", "12h"),
        "en-SG" to Preset("SGD", "dmy_slash", "12h"), "fil-PH" to Preset("PHP", "mdy", "12h"), "es-ES" to Preset("EUR", "dmy_slash", "24h"),
        "es-CL" to Preset("CLP", "dmy_slash", "24h"), "es-MX" to Preset("MXN", "dmy_slash", "12h"), "es-AR" to Preset("ARS", "dmy_slash", "12h"),
        "es-BO" to Preset("BOB", "dmy_slash", "12h"), "es-CO" to Preset("COP", "dmy_slash", "12h"), "es-CR" to Preset("CRC", "dmy_slash", "12h"),
        "es-CU" to Preset("CUP", "dmy_slash", "12h"), "es-DO" to Preset("DOP", "dmy_slash", "12h"), "es-EC" to Preset("USD", "dmy_slash", "12h"),
        "es-GT" to Preset("GTQ", "dmy_slash", "12h"), "es-HN" to Preset("HNL", "dmy_slash", "12h"), "es-NI" to Preset("NIO", "dmy_slash", "12h"),
        "es-PA" to Preset("PAB", "mdy", "12h"), "es-PE" to Preset("PEN", "dmy_slash", "12h"), "es-PY" to Preset("PYG", "dmy_slash", "12h"),
        "es-SV" to Preset("USD", "dmy_slash", "12h"), "es-UY" to Preset("UYU", "dmy_slash", "12h"), "es-VE" to Preset("VES", "dmy_slash", "12h"),
        "fr-CA" to Preset("CAD", "ymd", "24h"), "fr-HT" to Preset("HTG", "dmy_slash", "24h"), "nl-SR" to Preset("SRD", "dmy_slash", "24h"),
        "en-BZ" to Preset("BZD", "dmy_slash", "24h"), "en-JM" to Preset("JMD", "dmy_slash", "12h"), "en-TT" to Preset("TTD", "dmy_slash", "12h"),
        "en-BS" to Preset("BSD", "dmy_slash", "12h"), "en-BB" to Preset("BBD", "dmy_slash", "12h"), "en-GY" to Preset("GYD", "dmy_slash", "12h"),
        "en-AG" to Preset("XCD", "dmy_slash", "12h"), "en-DM" to Preset("XCD", "dmy_slash", "12h"), "en-GD" to Preset("XCD", "dmy_slash", "12h"),
        "en-KN" to Preset("XCD", "dmy_slash", "12h"), "en-LC" to Preset("XCD", "dmy_slash", "12h"), "en-VC" to Preset("XCD", "dmy_slash", "12h"),
        "fr-FR" to Preset("EUR", "dmy_slash", "24h"), "it-IT" to Preset("EUR", "dmy_slash", "24h"), "sv-SE" to Preset("SEK", "ymd", "24h"),
        "nb-NO" to Preset("NOK", "dmy", "24h"), "pl-PL" to Preset("PLN", "dmy", "24h"), "cs-CZ" to Preset("CZK", "dmy", "24h"),
        "uk-UA" to Preset("UAH", "dmy", "24h"), "ru-RU" to Preset("RUB", "dmy", "24h"), "be-BY" to Preset("BYN", "dmy", "24h"),
        "tr-TR" to Preset("TRY", "dmy", "24h"), "zh-CN" to Preset("CNY", "ymd", "24h"), "zh-Hant-TW" to Preset("TWD", "ymd_slash", "12h"),
        "zh-Hant-HK" to Preset("HKD", "dmy_slash", "12h"), "zh-Hant-MO" to Preset("MOP", "dmy_slash", "12h"), "ja-JP" to Preset("JPY", "ymd", "24h"),
        "hi-IN" to Preset("INR", "dmy_slash", "12h"), "pt-PT" to Preset("EUR", "dmy_slash", "24h"), "pt-BR" to Preset("BRL", "dmy_slash", "24h"),
        "nl-NL" to Preset("EUR", "dmy_dash", "24h"), "ar-AE" to Preset("AED", "dmy_slash", "12h"), "ar-SA" to Preset("SAR", "dmy_slash", "12h"),
        "ko-KR" to Preset("KRW", "ymd", "12h"), "id-ID" to Preset("IDR", "dmy", "24h"), "ms-MY" to Preset("MYR", "dmy_slash", "12h"),
        "he-IL" to Preset("ILS", "dmy", "24h"), "fa-IR" to Preset("IRR", "ymd", "24h"), "el-GR" to Preset("EUR", "dmy_slash", "12h"),
        "hu-HU" to Preset("HUF", "ymd_dot", "24h"), "vi-VN" to Preset("VND", "dmy_slash", "24h"),
    )

    /** `LEGACY_REGION_TRIPLES`: a region saved before its preset changed still counts as itself. */
    private val LEGACY = mapOf("nl-NL" to Preset("EUR", "dmy", "24h"))

    /** `CURRENCY_CODES`, in their dropdown order. */
    val CURRENCIES = listOf(
        "AED", "ARS", "AUD", "BBD", "BOB", "BRL", "BSD", "BYN", "BZD", "CAD", "CHF", "CLP",
        "CNY", "COP", "CRC", "CUP", "CZK", "DKK", "DOP", "EUR", "GBP", "GTQ", "GYD",
        "HKD", "HNL", "HTG", "HUF", "IDR", "ILS", "INR", "IRR", "JMD", "JPY", "KRW", "KZT", "MOP",
        "MXN", "MYR", "NIO", "NOK", "NZD", "PAB", "PEN", "PHP", "PLN", "PYG", "RUB", "SAR",
        "SEK", "SGD", "SRD", "TRY", "TTD", "TWD", "UAH", "USD", "UYU", "VES", "VND", "XCD", "ZAR",
    )

    /** `DATE_FORMATS`, in their dropdown order. */
    val DATE_FORMATS = listOf(
        "mdy" to "MM/DD/YYYY", "dmy" to "DD.MM.YYYY", "dmy_slash" to "DD/MM/YYYY", "dmy_dash" to "DD-MM-YYYY",
        "ymd" to "YYYY-MM-DD", "mdy_dot" to "MM.DD.YYYY", "ymd_dot" to "YYYY.MM.DD", "ymd_slash" to "YYYY/MM/DD",
    )

    /** `detectRegion`: the first preset with this exact triple, else custom. */
    fun detect(currency: String?, dateFormat: String?, timeFormat: String?): String =
        PRESETS.entries.firstOrNull { (_, p) -> p.currency == currency && p.dateFormat == dateFormat && p.timeFormat == timeFormat }?.key ?: CUSTOM

    /** `resolveRegion`: the saved region while its triple still matches, else the detected one. */
    fun resolve(region: String?, currency: String?, dateFormat: String?, timeFormat: String?): String {
        fun matches(p: Preset?) = p != null && p.currency == currency && p.dateFormat == dateFormat && p.timeFormat == timeFormat
        if (region != null && (matches(PRESETS[region]) || matches(LEGACY[region]))) return region
        return detect(currency, dateFormat, timeFormat)
    }

    private fun country(code: String) = code.substringAfterLast('-')

    /** `regionKeepsLanguage`. */
    fun keepsLanguage(code: String): Boolean {
        val c = country(code)
        return c == "CH" || PRESETS.keys.count { country(it) == c } > 1
    }

    /** `regionLabel`: the country, with the language where the country alone is ambiguous. */
    fun label(code: String, ui: Locale): String {
        val region = Locale("", country(code)).getDisplayCountry(ui).ifEmpty { code }
        if (!keepsLanguage(code)) return region
        val language = Locale(code.substringBefore('-')).getDisplayLanguage(ui)
        return if (language.isNotEmpty()) "$region ($language)" else region
    }

    /** `currencyLongLabel`: "AED - United Arab Emirates Dirham". */
    fun currencyLabel(code: String, ui: Locale): String =
        runCatching { Currency.getInstance(code).getDisplayName(ui) }.getOrNull()?.takeIf { it != code }?.let { "$code - $it" } ?: code

    /** `timeZoneList`: UTC first, then every region zone, sorted. */
    fun zones(): List<String> = listOf("UTC") + ZoneId.getAvailableZoneIds()
        .filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") && it.first().isUpperCase() }
        .sorted()
}
