package com.piremote.theme

import android.app.Activity
import android.content.res.Configuration
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.core.view.WindowCompat

/** CompositionLocal that surfaces the current [PiRemoteTheme] to any composable. */
val LocalPiTheme = compositionLocalOf { PiRemoteTheme.defaultDark }

@Composable
fun PiRemoteTheme(content: @Composable () -> Unit) {
    val piTheme by ThemeManager.flow.collectAsState()
    val ctx = LocalContext.current

    // Derive Material3 colorScheme from our palette
    val darkMode = piTheme.colorScheme == ColorScheme.DARK
    val scheme = if (darkMode) {
        darkColorScheme(
            primary = piTheme.accent,
            surface = piTheme.bg,
            background = piTheme.bg,
            surfaceVariant = piTheme.bgSecondary,
            onSurface = piTheme.textPrimary,
            onSurfaceVariant = piTheme.textSecondary,
            outline = piTheme.border,
            error = piTheme.error,
        )
    } else {
        lightColorScheme(
            primary = piTheme.accent,
            surface = piTheme.bg,
            background = piTheme.bg,
            surfaceVariant = piTheme.bgSecondary,
            onSurface = piTheme.textPrimary,
            onSurfaceVariant = piTheme.textSecondary,
            outline = piTheme.border,
            error = piTheme.error,
        )
    }

    CompositionLocalProvider(LocalPiTheme provides piTheme) {
        MaterialTheme(colorScheme = scheme, content = content)
    }

    SideEffect {
        val window = (ctx as? Activity)?.window ?: return@SideEffect
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.Transparent.toArgb()
        window.navigationBarColor = Color.Transparent.toArgb()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !darkMode
    }
}
