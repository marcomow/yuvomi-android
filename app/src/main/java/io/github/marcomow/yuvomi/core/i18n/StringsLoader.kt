package io.github.marcomow.yuvomi.core.i18n

import android.content.res.AssetManager
import android.icu.text.PluralRules
import android.icu.util.ULocale

/**
 * Reads `assets/i18n/<locale>.json` (upstream's strings) and the app's own strings:
 * `assets/i18n-android/<locale>.json` plus `assets/i18n-android/<module>/<locale>.json`
 * for each native module (ADR 0011, ADR 0015).
 */
object StringsLoader {

    fun load(assets: AssetManager, locale: String): Strings {
        fun read(path: String): Map<String, String> =
            runCatching { assets.open(path).bufferedReader().use { Strings.flatten(it.readText()) } }.getOrDefault(emptyMap())

        val moduleDirs = runCatching { assets.list("i18n-android")?.filterNot { it.endsWith(".json") } }.getOrNull().orEmpty().sorted()
        fun android(locale: String): Map<String, String> =
            moduleDirs.fold(read("i18n-android/$locale.json")) { acc, dir -> acc + read("i18n-android/$dir/$locale.json") }

        val english = read("i18n/en.json") + android("en")
        val own = if (locale == "en") english else read("i18n/$locale.json") + android(locale)
        val rules = PluralRules.forLocale(ULocale.forLanguageTag(locale))
        return Strings(locale, own, english) { count -> rules.select(count) }
    }
}
