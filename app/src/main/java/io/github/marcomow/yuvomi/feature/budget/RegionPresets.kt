package io.github.marcomow.yuvomi.feature.budget

/**
 * Upstream's region presets (`public/settings/region-presets.js`). The household stores no region
 * of its own for formatting: currency, date and time format pick the number locale
 * (`numberLocaleFor`), which `router.js` mirrors into `yuvomi-number-locale` and
 * `getFormatLocale()` reads for every amount. The table is upstream's, in upstream's order.
 */
object RegionPresets {
    data class Preset(val code: String, val currency: String, val dateFormat: String, val timeFormat: String)

    private fun r(code: String, currency: String, dateFormat: String, timeFormat: String) = Preset(code, currency, dateFormat, timeFormat)

    val presets: List<Preset> = listOf(
        r("de-DE", "EUR", "dmy", "24h"), r("de-AT", "EUR", "dmy", "24h"), r("de-CH", "CHF", "dmy", "24h"),
        r("en-US", "USD", "mdy", "12h"), r("en-GB", "GBP", "dmy_slash", "24h"), r("en-CA", "CAD", "ymd", "12h"),
        r("en-AU", "AUD", "dmy_slash", "12h"), r("en-NZ", "NZD", "dmy_slash", "12h"), r("en-PH", "PHP", "mdy", "12h"),
        r("en-SG", "SGD", "dmy_slash", "12h"), r("fil-PH", "PHP", "mdy", "12h"), r("es-ES", "EUR", "dmy_slash", "24h"),
        r("es-CL", "CLP", "dmy_slash", "24h"), r("es-MX", "MXN", "dmy_slash", "12h"), r("es-AR", "ARS", "dmy_slash", "12h"),
        r("es-BO", "BOB", "dmy_slash", "12h"), r("es-CO", "COP", "dmy_slash", "12h"), r("es-CR", "CRC", "dmy_slash", "12h"),
        r("es-CU", "CUP", "dmy_slash", "12h"), r("es-DO", "DOP", "dmy_slash", "12h"), r("es-EC", "USD", "dmy_slash", "12h"),
        r("es-GT", "GTQ", "dmy_slash", "12h"), r("es-HN", "HNL", "dmy_slash", "12h"), r("es-NI", "NIO", "dmy_slash", "12h"),
        r("es-PA", "PAB", "mdy", "12h"), r("es-PE", "PEN", "dmy_slash", "12h"), r("es-PY", "PYG", "dmy_slash", "12h"),
        r("es-SV", "USD", "dmy_slash", "12h"), r("es-UY", "UYU", "dmy_slash", "12h"), r("es-VE", "VES", "dmy_slash", "12h"),
        r("fr-CA", "CAD", "ymd", "24h"), r("fr-HT", "HTG", "dmy_slash", "24h"), r("nl-SR", "SRD", "dmy_slash", "24h"),
        r("en-BZ", "BZD", "dmy_slash", "24h"), r("en-JM", "JMD", "dmy_slash", "12h"), r("en-TT", "TTD", "dmy_slash", "12h"),
        r("en-BS", "BSD", "dmy_slash", "12h"), r("en-BB", "BBD", "dmy_slash", "12h"), r("en-GY", "GYD", "dmy_slash", "12h"),
        r("en-AG", "XCD", "dmy_slash", "12h"), r("en-DM", "XCD", "dmy_slash", "12h"), r("en-GD", "XCD", "dmy_slash", "12h"),
        r("en-KN", "XCD", "dmy_slash", "12h"), r("en-LC", "XCD", "dmy_slash", "12h"), r("en-VC", "XCD", "dmy_slash", "12h"),
        r("fr-FR", "EUR", "dmy_slash", "24h"), r("it-IT", "EUR", "dmy_slash", "24h"), r("sv-SE", "SEK", "ymd", "24h"),
        r("nb-NO", "NOK", "dmy", "24h"), r("pl-PL", "PLN", "dmy", "24h"), r("cs-CZ", "CZK", "dmy", "24h"),
        r("uk-UA", "UAH", "dmy", "24h"), r("ru-RU", "RUB", "dmy", "24h"), r("be-BY", "BYN", "dmy", "24h"),
        r("tr-TR", "TRY", "dmy", "24h"), r("zh-CN", "CNY", "ymd", "24h"), r("zh-Hant-TW", "TWD", "ymd_slash", "12h"),
        r("zh-Hant-HK", "HKD", "dmy_slash", "12h"), r("zh-Hant-MO", "MOP", "dmy_slash", "12h"), r("ja-JP", "JPY", "ymd", "24h"),
        r("hi-IN", "INR", "dmy_slash", "12h"), r("pt-PT", "EUR", "dmy_slash", "24h"), r("pt-BR", "BRL", "dmy_slash", "24h"),
        r("nl-NL", "EUR", "dmy_dash", "24h"), r("ar-AE", "AED", "dmy_slash", "12h"), r("ar-SA", "SAR", "dmy_slash", "12h"),
        r("ko-KR", "KRW", "ymd", "12h"), r("id-ID", "IDR", "dmy", "24h"), r("ms-MY", "MYR", "dmy_slash", "12h"),
        r("he-IL", "ILS", "dmy", "24h"), r("fa-IR", "IRR", "ymd", "24h"), r("el-GR", "EUR", "dmy_slash", "12h"),
        r("hu-HU", "HUF", "ymd_dot", "24h"), r("vi-VN", "VND", "dmy_slash", "24h"),
    )

    /** `LEGACY_REGION_TRIPLES`: the earlier triple of a region whose preset changed (#1847). */
    private val legacy: Map<String, Preset> = mapOf("nl-NL" to r("nl-NL", "EUR", "dmy", "24h"))

    private val byCode = presets.associateBy { it.code }

    private fun Preset.matches(currency: String?, dateFormat: String?, timeFormat: String?) =
        this.currency == currency && this.dateFormat == dateFormat && this.timeFormat == timeFormat

    /** `detectRegion`: the first preset with exactly these three values, or null (`custom`). */
    fun detect(currency: String?, dateFormat: String?, timeFormat: String?): String? =
        presets.firstOrNull { it.matches(currency, dateFormat, timeFormat) }?.code

    /** `resolveRegion`: a stored region wins while its preset (or legacy triple) still fits. */
    fun resolve(region: String?, currency: String?, dateFormat: String?, timeFormat: String?): String? {
        if (region != null &&
            (byCode[region]?.matches(currency, dateFormat, timeFormat) == true || legacy[region]?.matches(currency, dateFormat, timeFormat) == true)
        ) {
            return region
        }
        return detect(currency, dateFormat, timeFormat)
    }

    /** `numberLocaleFor`: the BCP-47 tag money follows, or null to format in the UI language. */
    fun numberLocaleFor(region: String?, currency: String?, dateFormat: String?, timeFormat: String?): String? =
        resolve(region, currency, dateFormat, timeFormat)?.takeIf { it in byCode }
}
