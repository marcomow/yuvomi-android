package io.github.marcomow.yuvomi.core.i18n

import android.content.res.AssetManager
import android.icu.text.PluralRules
import android.icu.util.ULocale

/** Reads `assets/i18n/<locale>.json` and `assets/i18n-android/<locale>.json` (ADR 0011). */
object StringsLoader {

    fun load(assets: AssetManager, locale: String): Strings {
        fun read(path: String): Map<String, String> =
            runCatching { assets.open(path).bufferedReader().use { Strings.flatten(it.readText()) } }.getOrDefault(emptyMap())

        val english = read("i18n/en.json") + read("i18n-android/en.json")
        val own = if (locale == "en") english else read("i18n/$locale.json") + read("i18n-android/$locale.json")
        val rules = PluralRules.forLocale(ULocale.forLanguageTag(locale))
        return Strings(locale, own, english) { count -> rules.select(count) }
    }
}
