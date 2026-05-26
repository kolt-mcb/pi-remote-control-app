package com.piremote.theme

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color

/** Dark vs Light – controls Material3 / window-insets defaults. */
enum class ColorScheme { DARK, LIGHT }

/**
 * Readonly palette consumed by the UI.
 * Every token in Color.kt is now a property of this class.
 *
 * Builder defaults are the "classic Pi Dark" palette.
 * Pass [ColorScheme.LIGHT] to use light-theme defaults.
 */
@Stable
data class PiRemoteTheme(
    val colorScheme: ColorScheme = ColorScheme.DARK,

    // Core backgrounds
    val bg: Color = Color(0xFF0D1117),
    val bgSecondary: Color = Color(0xFF161B22),
    val bgTertiary: Color = Color(0xFF21262D),
    val footerBg: Color = Color(0xFF010409),

    // Borders
    val border: Color = Color(0xFF30363D),
    val borderAccent: Color = Color(0xFF58A6FF),
    val borderMuted: Color = Color(0xFF21262D),

    // Text
    val textPrimary: Color = Color(0xFFF0F6FC),
    val textSecondary: Color = Color(0xFF8B949E),
    val textMuted: Color = Color(0xFF636C76),

    // Accents & states
    val accent: Color = Color(0xFF58A6FF),
    val success: Color = Color(0xFF3FB950),
    val error: Color = Color(0xFFF85149),
    val warning: Color = Color(0xFFD29922),

    // Messages
    val userBubbleBg: Color = Color(0xFF1C2333),
    val userBubbleText: Color = Color(0xFFF0F6FC),
    val assistantText: Color = Color(0xFFE6EDF3),
    val selectedBg: Color = Color(0xFF264F78),

    // Tool boxes
    val toolBorder: Color = Color(0xFF3FB950),
    val toolPending: Color = Color(0xFF2D333B),
    val toolErrorBg: Color = Color(0xFF2D1B1B),
    val toolSuccessBg: Color = Color(0xFF1B2D1B),
    val toolTitle: Color = Color(0xFF58A6FF),

    // Thinking
    val thinkingColor: Color = Color(0xFFD2A8FF),
    val thinkingBorder: Color = Color(0xFFD2A8FF),
    val thinkingLow: Color = Color(0xFF58A6FF),
    val thinkingMedium: Color = Color(0xFFD2A8FF),
    val thinkingHigh: Color = Color(0xFFFF7B72),

    // Syntax
    val codeKeyword: Color = Color(0xFFFF7B72),
    val codeString: Color = Color(0xFFA5D6FF),
    val codeComment: Color = Color(0xFF6E7681),
    val codeFunction: Color = Color(0xFFD2A8FF),
    val codeNumber: Color = Color(0xFF79C0FF),
    val codeType: Color = Color(0xFF7EE787),
    val codeOperator: Color = Color(0xFF79C0FF),
    val codePunctuation: Color = Color(0xFF636C76),

    // Markdown
    val mdHeading: Color = Color(0xFFF0C674),
    val mdLink: Color = Color(0xFF81A2BE),
    val mdLinkUrl: Color = Color(0xFF6E7681),
    val mdCode: Color = Color(0xFF58A6FF),
    val mdCodeBlock: Color = Color(0xFF7EE787),
    val mdCodeBlockBorder: Color = Color(0xFF6E7681),
    val mdQuote: Color = Color(0xFF8B949E),
    val mdQuoteBorder: Color = Color(0xFF6E7681),
    val mdListBullet: Color = Color(0xFF58A6FF),

    // Footer
    val footerText: Color = Color(0xFF636C76),
) {
    companion object {
        // ── Default dark palette (matches Pi db) ───────────────
        val defaultDark = PiRemoteTheme(
            colorScheme = ColorScheme.DARK,
            bg = Color(0xFF0D1117), bgSecondary = Color(0xFF161B22),
            bgTertiary = Color(0xFF21262D), footerBg = Color(0xFF010409),
            border = Color(0xFF30363D), borderAccent = Color(0xFF58A6FF),
            borderMuted = Color(0xFF21262D),
            textPrimary = Color(0xFFF0F6FC), textSecondary = Color(0xFF8B949E),
            textMuted = Color(0xFF636C76),
            accent = Color(0xFF58A6FF), success = Color(0xFF3FB950),
            error = Color(0xFFF85149), warning = Color(0xFFD29922),
            userBubbleBg = Color(0xFF1C2333), userBubbleText = Color(0xFFF0F6FC),
            assistantText = Color(0xFFE6EDF3), selectedBg = Color(0xFF264F78),
            toolBorder = Color(0xFF3FB950), toolPending = Color(0xFF2D333B),
            toolErrorBg = Color(0xFF2D1B1B), toolSuccessBg = Color(0xFF1B2D1B),
            toolTitle = Color(0xFF58A6FF),
            thinkingColor = Color(0xFFD2A8FF), thinkingBorder = Color(0xFFD2A8FF),
            thinkingLow = Color(0xFF58A6FF), thinkingMedium = Color(0xFFD2A8FF),
            thinkingHigh = Color(0xFFFF7B72),
            codeKeyword = Color(0xFFFF7B72), codeString = Color(0xFFA5D6FF),
            codeComment = Color(0xFF6E7681), codeFunction = Color(0xFFD2A8FF),
            codeNumber = Color(0xFF79C0FF), codeType = Color(0xFF7EE787),
            codeOperator = Color(0xFF79C0FF), codePunctuation = Color(0xFF636C76),
            mdHeading = Color(0xFFF0C674), mdLink = Color(0xFF81A2BE),
            mdLinkUrl = Color(0xFF6E7681), mdCode = Color(0xFF58A6FF),
            mdCodeBlock = Color(0xFF7EE787), mdCodeBlockBorder = Color(0xFF6E7681),
            mdQuote = Color(0xFF8B949E), mdQuoteBorder = Color(0xFF6E7681),
            mdListBullet = Color(0xFF58A6FF),
            footerText = Color(0xFF636C76),
        )

        // ── Default light palette (matches Pi light.json) ────────
        val defaultLight = PiRemoteTheme(
            colorScheme = ColorScheme.LIGHT,
            bg = Color(0xFFFFFFFF), bgSecondary = Color(0xFFF8F9FA),
            bgTertiary = Color(0xFFE9ECEF), footerBg = Color(0xFFE9ECEF),
            border = Color(0xFFDEE2E6), borderAccent = Color(0xFF547DA7),
            borderMuted = Color(0xFFB0B0B0),
            textPrimary = Color(0xFF1F2328), textSecondary = Color(0xFF6C6C6C),
            textMuted = Color(0xFF767676),
            accent = Color(0xFF5A8080), success = Color(0xFF588458),
            error = Color(0xFFAA5555), warning = Color(0xFF9A7326),
            userBubbleBg = Color(0xFFE8E8E8), userBubbleText = Color(0xFF1F2328),
            assistantText = Color(0xFF24292F), selectedBg = Color(0xFFD0D0E0),
            toolBorder = Color(0xFF588458), toolPending = Color(0xFFE8E8F0),
            toolErrorBg = Color(0xFFF0E8E8), toolSuccessBg = Color(0xFFE8F0E8),
            toolTitle = Color(0xFF1F2328),
            thinkingColor = Color(0xFF5A8080), thinkingBorder = Color(0xFF5A8080),
            thinkingLow = Color(0xFF547DA7), thinkingMedium = Color(0xFF5A8080),
            thinkingHigh = Color(0xFF875F87),
            codeKeyword = Color(0xFF0000FF), codeString = Color(0xFFA31515),
            codeComment = Color(0xFF008000), codeFunction = Color(0xFF795E26),
            codeNumber = Color(0xFF098658), codeType = Color(0xFF267F99),
            codeOperator = Color(0xFF000000), codePunctuation = Color(0xFF000000),
            mdHeading = Color(0xFF9A7326), mdLink = Color(0xFF547DA7),
            mdLinkUrl = Color(0xFF767676), mdCode = Color(0xFF5A8080),
            mdCodeBlock = Color(0xFF588458), mdCodeBlockBorder = Color(0xFF6C6C6C),
            mdQuote = Color(0xFF6C6C6C), mdQuoteBorder = Color(0xFF6C6C6C),
            mdListBullet = Color(0xFF588458),
            footerText = Color(0xFF767676),
        )
    }
}
