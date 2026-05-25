package com.piremote.theme

import androidx.compose.ui.graphics.Color

// ── Pi Terminal Theme Colors ──────────────────────────────────────────
// Dark theme (default) — matches pi dark.json

val bg          = Color(0xFF0D1117)       // Terminal background
val bgSecondary = Color(0xFF161B22)       // Panel backgrounds
val bgTertiary  = Color(0xFF21262D)       // Input/deep backgrounds

val border       = Color(0xFF30363D)      // Normal borders (├─┤─┴)
val borderAccent = Color(0xFF58A6FF)      // Highlighted borders (selected panels)
val borderMuted  = Color(0xFF21262D)      // Subtle borders (editor border idle)

val textPrimary  = Color(0xFFF0F6FC)      // Default text
val textSecondary = Color(0xFF8B949E)     // Muted text
val textMuted    = Color(0xFF636C76)      // Dim text

// Core accents
val accent        = Color(0xFF58A6FF)     // Primary accent (blue)
val success       = Color(0xFF3FB950)     // Success / idle
val error         = Color(0xFFF85149)     // Error
val errorColor    = error                  // Alias for backwards compat
val warning       = Color(0xFFD29922)     // Warning / amber

// Message styling
val userBubbleBg  = Color(0xFF1C2333)     // User message subtle bg
val userBubbleText = textPrimary
val assistantText = Color(0xFFE6EDF3)

// Tool boxes
val toolBorder    = Color(0xFF3FB950)     // Tool success border
val toolPending   = Color(0xFF2D333B)     // Tool pending bg
val toolErrorBg   = Color(0xFF2D1B1B)     // Tool error bg
val toolSuccessBg = Color(0xFF1B2D1B)     // Tool success bg
val toolTitle     = accent                // Tool title text

// Thinking colors (match thinking level borders)
val thinkingColor      = Color(0xFFD2A8FF)  // Thinking text / border
val thinkingBorder     = Color(0xFFD2A8FF)  // Thinking level border (low)
val thinkingLow        = Color(0xFF58A6FF)  // Low thinking border (blue)
val thinkingMedium     = Color(0xFFD2A8FF)  // Medium thinking border (purple)
val thinkingHigh       = Color(0xFFFF7B72)  // High thinking border (red-orange)

// Syntax highlighting
val codeKeyword   = Color(0xFFFF7B72)     // Keywords
val codeString    = Color(0xFFA5D6FF)     // Strings
val codeComment   = Color(0xFF6E7681)     // Comments
val codeFunction  = Color(0xFFD2A8FF)     // Functions
val codeNumber    = Color(0xFF79C0FF)     // Numbers
val codeType      = Color(0xFF7EE787)     // Types
val codeOperator  = Color(0xFF79C0FF)     // Operators
val codePunctuation = textMuted           // Punctuation

// Footer bar
val footerBg     = Color(0xFF010409)      // Footer background (near black)
val footerText   = textMuted              // Footer text color

// Markdown — values mirror pi's dark.json (mdHeading/mdCode/mdQuote/etc.)
val mdHeading        = Color(0xFFF0C674)  // warm gold for # ## ### lines
val mdLink           = Color(0xFF81A2BE)  // blue-ish link text
val mdLinkUrl        = Color(0xFF6E7681)  // dim gray for the (url) part
val mdCode           = accent             // inline `code` — accent blue
val mdCodeBlock      = Color(0xFF7EE787)  // fenced code block body — green
val mdCodeBlockBorder = Color(0xFF6E7681) // fenced code block left rule — gray
val mdQuote          = Color(0xFF8B949E)  // blockquote text — gray
val mdQuoteBorder    = Color(0xFF6E7681)  // blockquote left rule — gray
val mdListBullet     = accent             // - / * / 1. bullets — accent blue

// Md-like selections
val selectedBg   = Color(0xFF264F78)      // Selected line bg (menu items)

object CodeUtils {
    fun isCodeContent(content: String): Boolean {
        val patterns = setOf(
            "package ", "import ", "class ", "interface ", "fun ",
            "private ", "protected ", "public ",
            "abstract ", "override ", "sealed ", "data ",
            "val ", "var ", "companion ", "object ",
            "asynchronous ", "await ", "typical ", "Expandable ",
            ".kt:", ".java:", ".ts:", ".js:", ".xml:", "*.py",
            "//", "function ", "const ", "let ", "module",
            "file://", "src/main", "page ",
            " ", "#if", "#else", "return"
        )
        return patterns.any { content.contains(it) }
    }

    fun countLines(content: String): Int = content.split("\n").size
}
