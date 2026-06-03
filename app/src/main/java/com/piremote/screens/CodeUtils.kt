package com.piremote.screens

/** Patterns that strongly suggest code content. Defined once — not per call. */
private val CODE_PATTERNS = setOf(
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

/** Check if content looks like source code (for inline formatting decisions). */
fun isCodeContent(content: String): Boolean = CODE_PATTERNS.any { content.contains(it) }

/** Count lines without allocating a list — O(n), zero allocation. */
fun countLines(content: String): Int = content.count { it == '\n' } + 1
