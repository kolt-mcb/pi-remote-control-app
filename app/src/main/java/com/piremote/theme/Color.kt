package com.piremote.theme

import androidx.compose.ui.graphics.Color

/**
 * Backward-compat shims.
 * Every value reads from ThemeManager.current so UI code still compiles
 * against the old top-level vals while the reactive system drives the real data.
 *
 * New code should prefer `val theme by ws.themeFlow.collectAsState()`
 * and use `theme.bg`, `theme.accent`, etc. directly.
 */

// ▸ Backgrounds
val bg: Color get() = ThemeManager.current.bg
val bgSecondary: Color get() = ThemeManager.current.bgSecondary
val bgTertiary: Color get() = ThemeManager.current.bgTertiary

// ▸ Borders
val border: Color get() = ThemeManager.current.border
val borderAccent: Color get() = ThemeManager.current.borderAccent
val borderMuted: Color get() = ThemeManager.current.borderMuted

// ▸ Text
val textPrimary: Color get() = ThemeManager.current.textPrimary
val textSecondary: Color get() = ThemeManager.current.textSecondary
val textMuted: Color get() = ThemeManager.current.textMuted

// ▸ Accents
val accent: Color get() = ThemeManager.current.accent
val success: Color get() = ThemeManager.current.success
val error: Color get() = ThemeManager.current.error
val warning: Color get() = ThemeManager.current.warning

// ▸ Messages / selection
val userBubbleBg: Color get() = ThemeManager.current.userBubbleBg
val userBubbleText: Color get() = ThemeManager.current.userBubbleText
val assistantText: Color get() = ThemeManager.current.assistantText
val selectedBg: Color get() = ThemeManager.current.selectedBg

// ▸ Tool boxes
val toolBorder: Color get() = ThemeManager.current.toolBorder
val toolPending: Color get() = ThemeManager.current.toolPending
val toolErrorBg: Color get() = ThemeManager.current.toolErrorBg
val toolSuccessBg: Color get() = ThemeManager.current.toolSuccessBg
val toolTitle: Color get() = ThemeManager.current.toolTitle

// ▸ Thinking
val thinkingColor: Color get() = ThemeManager.current.thinkingColor
val thinkingBorder: Color get() = ThemeManager.current.thinkingBorder
val thinkingLow: Color get() = ThemeManager.current.thinkingLow
val thinkingMedium: Color get() = ThemeManager.current.thinkingMedium
val thinkingHigh: Color get() = ThemeManager.current.thinkingHigh

// ▸ Syntax
val codeKeyword: Color get() = ThemeManager.current.codeKeyword
val codeString: Color get() = ThemeManager.current.codeString
val codeComment: Color get() = ThemeManager.current.codeComment
val codeFunction: Color get() = ThemeManager.current.codeFunction
val codeNumber: Color get() = ThemeManager.current.codeNumber
val codeType: Color get() = ThemeManager.current.codeType
val codeOperator: Color get() = ThemeManager.current.codeOperator
val codePunctuation: Color get() = ThemeManager.current.codePunctuation

// ▸ Markdown
val mdHeading: Color get() = ThemeManager.current.mdHeading
val mdLink: Color get() = ThemeManager.current.mdLink
val mdLinkUrl: Color get() = ThemeManager.current.mdLinkUrl
val mdCode: Color get() = ThemeManager.current.mdCode
val mdCodeBlock: Color get() = ThemeManager.current.mdCodeBlock
val mdCodeBlockBorder: Color get() = ThemeManager.current.mdCodeBlockBorder
val mdQuote: Color get() = ThemeManager.current.mdQuote
val mdQuoteBorder: Color get() = ThemeManager.current.mdQuoteBorder
val mdListBullet: Color get() = ThemeManager.current.mdListBullet

// ▸ Footer
val footerBg: Color get() = ThemeManager.current.footerBg
val footerText: Color get() = ThemeManager.current.footerText

// ▸ Legacy alias (used by a few places)
val errorColor: Color get() = error

// ▸ Code detection helpers (unchanged from original)
object CodeUtils {
    fun isCodeContent(content: String): Boolean {
        val patterns = setOf(
            "package ", "import ", "class ", "interface ", "fun ",
            "private ", "protected ", "public ",
            "abstract ", "override ", "sealed ", "data ",
            "val ", "var ", "companion ", "object ",
            "await ",
            ".kt:", ".java:", ".ts:", ".js:", ".xml:", "*.py",
            "//", "function ", "const ", "let ", "module",
            "file://", "src/main",
            "#if", "#else", "return"
        )
        return patterns.any { content.contains(it) }
    }
    fun countLines(content: String): Int = content.split("\n").size
}
