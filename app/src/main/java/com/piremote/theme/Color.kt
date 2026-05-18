package com.piremote.theme

import androidx.compose.ui.graphics.Color

val bg = Color(0xFF0D1117)
val bgSecondary = Color(0xFF161B22)
val bgTertiary = Color(0xFF21262D)
val border = Color(0xFF30363D)

val textPrimary = Color(0xFFF0F6FC)
val textSecondary = Color(0xFF8B949E)
val textMuted = Color(0xFF636C76)

val accent = Color(0xFF58A6FF)
val userBubble = Color(0xFF1F6FEB)
val assistantText = Color(0xFFE6EDF3)
val toolBg = Color(0xFF0D1117)
val toolBorder = Color(0xFF3FB950)
val errorColor = Color(0xFFF85149)
val thinkingColor = Color(0xFFD2A8FF)

val codeKeyword = Color(0xFFFF7B72)
val codeString = Color(0xFFA5D6FF)
val codeComment = Color(0xFF6E7681)
val codeFunction = Color(0xFFD2A8FF)
val codeNumber = Color(0xFF79C0FF)
val codeType = Color(0xFF7EE787)

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

    fun extractFilePath(content: String): String {
        // Try: "file:///home/user/path/file.kt" → "/home/user/path/file.kt"
        val m1 = Regex("file://([\\w./\\-]+)/(\\.[a-zA-Z0-9./\\-]+(?:\\.(?:kt|java|ts|js|xml|json|py|sh)))").find(content)
        if (m1 != null) return "/${m1.groupValues[1]}${m1.groupValues[2]}"
        // Try: "C:\path\file.kt"
        val m2 = Regex("([a-zA-Z]:[/\\\\][\\w./\\-]+)\\.(kt|java|ts|js|xml|json|py|sh)").find(content)
        if (m2 != null) return "${m2.groupValues[1]}.${m2.groupValues[2]}"
        // Try: "home/user/path/file.kt"
        val m3 = Regex("(home|src|android)[\\w./\\-]+\\.(kt|java|ts|js|xml|json|py|sh)").find(content)
        if (m3 != null) return m3.groupValues[0]
        return ""
    }

    fun countLines(content: String): Int = content.split("\n").size
}
