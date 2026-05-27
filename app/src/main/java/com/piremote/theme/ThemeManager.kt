package com.piremote.theme

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Reactive theme holder consumed by the Compose UI.
 *
 * ▸ Starts with the built-in "pi-dark" palette.
 * ▸ Accepts overrides:
 *   – local user pref (pick built-in)
 *   – remote server (theme_info broadcast from extension)
 *
 * Compose usage:
 *   val theme by ws.themeFlow.collectAsState()
 *   Box(modifier = Modifier.background(theme.bg)) { … }
 */
object ThemeManager {

    private val _flow = MutableStateFlow(PiRemoteTheme.defaultDark)
    val flow: StateFlow<PiRemoteTheme> get() = _flow

    var current: PiRemoteTheme
        get() = _flow.value
        set(value) { _flow.value = value }

    fun applyRemote(t: PiRemoteTheme) { current = t }
}
