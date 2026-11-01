package io.github.marcomow.yuvomi.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** The Yuvomi colours Material 3 has no role for. */
@Immutable
data class YuvomiColors(
    val isDark: Boolean,
    val tokens: Tokens,
    val danger: Color,
    val dangerContainer: Color,
    val success: Color,
    val warning: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val border: Color,
) {
    /** A module's family tone (`--module-<key>`), e.g. `tasks`, `calendar`. */
    fun module(key: String): Color = tokens.color("module-$key", isDark)
}

val LocalYuvomiColors = staticCompositionLocalOf<YuvomiColors> { error("YuvomiTheme missing") }

/** Material 3 structure, Yuvomi skin, dynamic colour off (ADR 0002). */
@Composable
fun YuvomiTheme(tokens: Tokens, dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = { name: String -> tokens.color(name, dark) }
    val accent = c("color-accent")
    val onAccent = if (accent.luminance() > 0.45f) c("color-text-primary").let { if (dark) Color(0xFF1D1B17) else it } else Color.White
    val base: ColorScheme = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = c("color-accent-subtle"),
        onPrimaryContainer = c("color-text-primary"),
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = c("color-accent-light"),
        onSecondaryContainer = c("color-text-primary"),
        tertiary = c("color-success"),
        background = c("color-bg"),
        onBackground = c("color-text-primary"),
        surface = c("color-bg"),
        onSurface = c("color-text-primary"),
        surfaceVariant = c("color-surface-3"),
        onSurfaceVariant = c("color-text-secondary"),
        surfaceContainerLowest = c("color-surface-2"),
        surfaceContainerLow = c("color-surface"),
        surfaceContainer = c("color-surface"),
        surfaceContainerHigh = c("color-surface-elevated"),
        surfaceContainerHighest = c("color-surface-3"),
        outline = c("color-border"),
        outlineVariant = c("color-border-subtle"),
        error = c("color-danger"),
        onError = Color.White,
        errorContainer = c("color-danger-light"),
        onErrorContainer = c("color-text-primary"),
    )
    val extra = YuvomiColors(
        isDark = dark,
        tokens = tokens,
        danger = c("color-danger"),
        dangerContainer = c("color-danger-light"),
        success = c("color-success"),
        warning = c("color-warning"),
        textSecondary = c("color-text-secondary"),
        textTertiary = c("color-text-tertiary"),
        border = c("color-border"),
    )
    CompositionLocalProvider(LocalYuvomiColors provides extra) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

object Yuvomi {
    val colors: YuvomiColors @Composable get() = LocalYuvomiColors.current
}
