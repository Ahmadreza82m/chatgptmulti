package com.lumo.studio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.lumo.studio.model.Accent
import com.lumo.studio.model.ThemeMode

class LumoColors(
    val bg: Color,
    val bg2: Color,
    val fg: Color,
    val fg2: Color,
    val fg3: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val line2: Color,
    val g1: Color,
    val g2: Color,
    val gold: Color,
    val ok: Color,
    val warn: Color,
    val bad: Color,
    val blob1: Color,
    val blob2: Color,
    val blob3: Color,
    val dark: Boolean,
)

val LocalLumo = staticCompositionLocalOf<LumoColors> { error("LumoTheme is missing") }

private fun palette(dark: Boolean, accent: Accent): LumoColors {
    val (g1, g2) = when (accent) {
        Accent.AURORA -> Color(0xFF8B6CFF) to Color(0xFF2FD6C6)
        Accent.CHAMPAGNE -> Color(0xFFF3DFB2) to Color(0xFFC79D5A)
        Accent.ROSE -> Color(0xFFFF8AB4) to Color(0xFF8F6BFF)
    }
    return if (dark) {
        LumoColors(
            bg = Color(0xFF07070C), bg2 = Color(0xFF0E0E17),
            fg = Color(0xFFF4F1EA), fg2 = Color(0xA8F4F1EA), fg3 = Color(0x66F4F1EA),
            surface = Color(0x0EFFFFFF), surface2 = Color(0x1AFFFFFF),
            line = Color(0x17FFFFFF), line2 = Color(0x29FFFFFF),
            g1 = g1, g2 = g2,
            gold = Color(0xFFECD6A6), ok = Color(0xFF6FE3B0), warn = Color(0xFFFFCF7A), bad = Color(0xFFFF8F9C),
            blob1 = Color(0x8C7C5CFF), blob2 = Color(0x6127D3C3), blob3 = Color(0x4DFF7AA8),
            dark = true,
        )
    } else {
        LumoColors(
            bg = Color(0xFFECEEF5), bg2 = Color(0xFFF7F8FC),
            fg = Color(0xFF12131B), fg2 = Color(0xA312131B), fg3 = Color(0x6B12131B),
            surface = Color(0xA8FFFFFF), surface2 = Color(0xE6FFFFFF),
            line = Color(0x1712131B), line2 = Color(0x2912131B),
            g1 = g1, g2 = g2,
            gold = Color(0xFFA07C3A), ok = Color(0xFF14935E), warn = Color(0xFFB97A0C), bad = Color(0xFFCC3D55),
            blob1 = Color(0x4D7C5CFF), blob2 = Color(0x4228B9A8), blob3 = Color(0x38FF7AA8),
            dark = false,
        )
    }
}

@Composable
fun LumoTheme(mode: ThemeMode, accent: Accent, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val c = palette(dark, accent)
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.g1,
            onPrimary = Color(0xFF0A0A12),
            secondary = c.g2,
            background = c.bg,
            onBackground = c.fg,
            surface = c.bg2,
            onSurface = c.fg,
            surfaceVariant = c.surface2,
            onSurfaceVariant = c.fg2,
            outline = c.line2,
            error = c.bad,
        )
    } else {
        lightColorScheme(
            primary = c.g1,
            onPrimary = Color.White,
            secondary = c.g2,
            background = c.bg,
            onBackground = c.fg,
            surface = c.bg2,
            onSurface = c.fg,
            surfaceVariant = c.surface2,
            onSurfaceVariant = c.fg2,
            outline = c.line2,
            error = c.bad,
        )
    }
    CompositionLocalProvider(LocalLumo provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
