package io.github.marcomow.yuvomi.core.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalStrings = staticCompositionLocalOf<Strings> { error("Strings not provided") }

/** `t("tasks.title")`, `t("tasks.doneByToast", "name" to "Leo")` in composables. */
@Composable
fun t(key: String, vararg args: Pair<String, Any?>): String = LocalStrings.current.t(key, args.toMap())
