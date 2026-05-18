package com.piremote.theme

import android.app.Activity
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.core.view.WindowCompat

object ThemeManager {
    var isDark: Boolean = true
}

private val DarkColorScheme = darkColorScheme(
    primary = accent,
    surface = bg,
    background = bg,
    surfaceVariant = bgSecondary,
    onSurface = textPrimary,
    onSurfaceVariant = textSecondary,
    outline = border,
    error = errorColor,
)

private val LightColorScheme = lightColorScheme(
    primary = accent,
    surface = Color(0xFFF8F9FA),
    background = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9ECEF),
    onSurface = Color(0xFF212529),
    onSurfaceVariant = Color(0xFF6C757D),
    outline = Color(0xFFDEE2E6),
    error = errorColor,
)

@Composable
fun PiRemoteTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val darkMode = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val useDark = darkMode && ThemeManager.isDark || darkMode
    val scheme = if (darkMode) DarkColorScheme else LightColorScheme
    MaterialTheme(colorScheme = scheme, content = content)
    SideEffect {
        val window = (ctx as? Activity)?.window ?: return@SideEffect
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.Transparent.toArgb()
        window.navigationBarColor = Color.Transparent.toArgb()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !darkMode
    }
}
