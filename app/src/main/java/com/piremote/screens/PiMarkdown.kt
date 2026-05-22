package com.piremote.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piremote.theme.*
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxTheme

// Hand-rolled markdown renderer — pi's terminal renders markdown with theme
// colors (mdHeading/mdCode/mdQuote/...), so we mirror that look in the app.
// Intentionally minimal: handles the patterns pi actually emits, no tables.

// ── Inline tokenizer ──────────────────────────────────────────────────

/**
 * Parse inline markdown — **bold**, __bold__, *italic*, _italic_, `code`,
 * and [text](url) — into a Compose AnnotatedString.
 *
 * Strategy: find the earliest match across all patterns, emit the prefix as
 * unstyled, emit the styled match, recurse on the tail. Recursion lets nested
 * patterns work (e.g. `**bold _italic_**`). Caps recursion depth to dodge
 * pathological inputs.
 */
fun parseInlineMd(text: String, baseColor: Color, depth: Int = 0): AnnotatedString {
    if (depth > 4 || text.isEmpty()) {
        return AnnotatedString(text, SpanStyle(color = baseColor))
    }

    // (regex, spanStyle factory, captured-group index for visible text, optional second group for url)
    data class Match(val start: Int, val end: Int, val style: SpanStyle, val inner: String, val trail: String? = null, val trailStyle: SpanStyle? = null)

    val patterns = listOf<Pair<Regex, (MatchResult) -> Match>>(
        // Bold with ** ... **  (non-greedy, no nested **)
        Regex("""\*\*([^*\n]+?)\*\*""") to { m ->
            Match(m.range.first, m.range.last + 1,
                SpanStyle(color = baseColor, fontWeight = FontWeight.Bold), m.groupValues[1])
        },
        // Bold with __ ... __
        Regex("""__([^_\n]+?)__""") to { m ->
            Match(m.range.first, m.range.last + 1,
                SpanStyle(color = baseColor, fontWeight = FontWeight.Bold), m.groupValues[1])
        },
        // Italic with * ... *  (single-star, must not be **)
        Regex("""(?<!\*)\*(?!\*)([^*\n]+?)\*(?!\*)""") to { m ->
            Match(m.range.first, m.range.last + 1,
                SpanStyle(color = baseColor, fontStyle = FontStyle.Italic), m.groupValues[1])
        },
        // Italic with _ ... _  (must not be __)
        Regex("""(?<!_)_(?!_)([^_\n]+?)_(?!_)""") to { m ->
            Match(m.range.first, m.range.last + 1,
                SpanStyle(color = baseColor, fontStyle = FontStyle.Italic), m.groupValues[1])
        },
        // Inline code `...` — uses mdCode color, no background to keep terminal feel.
        Regex("""`([^`\n]+?)`""") to { m ->
            Match(m.range.first, m.range.last + 1,
                SpanStyle(color = mdCode, fontWeight = FontWeight.Medium), m.groupValues[1])
        },
        // Link [text](url)
        Regex("""\[([^\]\n]+?)\]\(([^)\n]+?)\)""") to { m ->
            Match(m.range.first, m.range.last + 1,
                SpanStyle(color = mdLink, textDecoration = TextDecoration.Underline), m.groupValues[1],
                trail = " (${m.groupValues[2]})",
                trailStyle = SpanStyle(color = mdLinkUrl, fontSize = 11.sp))
        }
    )

    val firstMatch = patterns.mapNotNull { (re, fac) ->
        re.find(text)?.let(fac)
    }.minByOrNull { it.start }

    return buildAnnotatedString {
        if (firstMatch == null) {
            withStyleSpan(SpanStyle(color = baseColor)) { append(text) }
            return@buildAnnotatedString
        }
        if (firstMatch.start > 0) {
            withStyleSpan(SpanStyle(color = baseColor)) { append(text.substring(0, firstMatch.start)) }
        }
        withStyleSpan(firstMatch.style) { append(firstMatch.inner) }
        firstMatch.trail?.let { t -> withStyleSpan(firstMatch.trailStyle ?: SpanStyle(color = baseColor)) { append(t) } }
        if (firstMatch.end < text.length) {
            append(parseInlineMd(text.substring(firstMatch.end), baseColor, depth + 1))
        }
    }
}

private inline fun androidx.compose.ui.text.AnnotatedString.Builder.withStyleSpan(
    style: SpanStyle, block: androidx.compose.ui.text.AnnotatedString.Builder.() -> Unit
) {
    val idx = pushStyle(style)
    try { block() } finally { pop(idx) }
}

// ── Block renderer ────────────────────────────────────────────────────

/**
 * Render markdown text as a column of Compose composables, mirroring pi's
 * theme colors for headers, code, quotes, lists, and inline marks.
 *
 * Block coverage: fenced ```code blocks```, ATX headers (# ## ###),
 * blockquotes (>), unordered (- *) and ordered (1.) lists, plus
 * paragraphs with inline formatting. Blank lines render as small spacers.
 */
@Composable
fun PiMarkdown(
    text: String,
    baseColor: Color = assistantText,
    baseSize: TextUnit = 13.sp,
    modifier: Modifier = Modifier
) {
    val lines = remember(text) { text.split("\n") }
    Column(modifier = modifier) {
        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            // Fenced code block — gather lines until the closing ```.
            if (line.trimStart().startsWith("```")) {
                val lang = line.trimStart().removePrefix("```").trim().ifBlank { null }
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    codeLines.add(lines[i]); i++
                }
                i++ // skip the closing fence
                MdCodeBlock(codeLines, lang)
                continue
            }

            // Header — # / ## / ### / ...
            val headerMatch = Regex("""^(#{1,6})\s+(.*)$""").find(line)
            if (headerMatch != null) {
                val level = headerMatch.groupValues[1].length
                val body = headerMatch.groupValues[2]
                val size = when (level) { 1 -> 16.sp; 2 -> 15.sp; 3 -> 14.sp; else -> 13.sp }
                Text(
                    parseInlineMd(body, mdHeading),
                    fontFamily = piMono, fontSize = size, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
                i++; continue
            }

            // Blockquote — > text   (single line; pi rarely emits multi-line quotes)
            if (line.startsWith("> ") || line == ">") {
                val body = line.removePrefix(">").trimStart()
                Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
                    Box(modifier = Modifier.width(2.dp).heightIn(min = 16.dp).background(mdQuoteBorder))
                    Spacer(Modifier.width(6.dp))
                    Text(parseInlineMd(body, mdQuote), fontFamily = piMono, fontSize = baseSize, fontStyle = FontStyle.Italic)
                }
                i++; continue
            }

            // List item — -, *, or numbered.
            val ulMatch = Regex("""^(\s*)[-*]\s+(.*)$""").find(line)
            val olMatch = Regex("""^(\s*)(\d+)\.\s+(.*)$""").find(line)
            if (ulMatch != null) {
                val indent = ulMatch.groupValues[1].length
                MdListItem(bullet = "•", body = ulMatch.groupValues[2], indent = indent, baseColor = baseColor, baseSize = baseSize)
                i++; continue
            }
            if (olMatch != null) {
                val indent = olMatch.groupValues[1].length
                MdListItem(bullet = "${olMatch.groupValues[2]}.", body = olMatch.groupValues[3], indent = indent, baseColor = baseColor, baseSize = baseSize)
                i++; continue
            }

            // Blank line → small spacer.
            if (line.isBlank()) {
                Spacer(Modifier.height(4.dp))
                i++; continue
            }

            // Plain paragraph line with inline formatting.
            Text(parseInlineMd(line, baseColor), fontFamily = piMono, fontSize = baseSize)
            i++
        }
    }
}

@Composable
private fun MdListItem(bullet: String, body: String, indent: Int, baseColor: Color, baseSize: TextUnit) {
    Row(
        modifier = Modifier.padding(start = (indent * 2).dp),
        verticalAlignment = androidx.compose.ui.Alignment.Top
    ) {
        Text(bullet, color = mdListBullet, fontFamily = piMono, fontSize = baseSize, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Text(parseInlineMd(body, baseColor), fontFamily = piMono, fontSize = baseSize, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MdCodeBlock(lines: List<String>, lang: String?) {
    val code = lines.joinToString("\n")
    val annotated = remember(code, lang) { highlightCode(code, lang) }
    Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = androidx.compose.ui.Alignment.Top) {
        // Left rule — matches pi's quoteBorder-style accent for code blocks.
        Box(modifier = Modifier.width(2.dp).fillMaxHeight().background(mdCodeBlockBorder))
        Spacer(Modifier.width(6.dp))
        Text(annotated, fontFamily = piMono, fontSize = 12.sp)
    }
}

// Tokenize a fenced code block using `dev.snipme:highlights` (same hljs grammar
// lineage pi-tui uses) and emit a Compose AnnotatedString colored by our palette.
// If the fence has no language tag or the tag is unknown, falls back to the
// previous single-color rendering.
private fun highlightCode(code: String, lang: String?): AnnotatedString {
    val language = resolveLanguage(lang)
    if (language == null) {
        return AnnotatedString(code, SpanStyle(color = mdCodeBlock))
    }
    val highlights = Highlights.Builder()
        .code(code)
        .language(language)
        .theme(piSyntaxTheme)
        .build()
        .getHighlights()
    return buildAnnotatedString {
        withStyleSpan(SpanStyle(color = textPrimary)) { append(code) }
        for (h in highlights) {
            val s = h.location.start.coerceIn(0, code.length)
            val e = h.location.end.coerceIn(s, code.length)
            if (s == e) continue
            when (h) {
                is ColorHighlight -> addStyle(SpanStyle(color = rgbToColor(h.rgb)), s, e)
                is BoldHighlight  -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), s, e)
            }
        }
    }
}

// Map common fence aliases (kt, py, sh, …) to Highlights' SyntaxLanguage enum.
// Returns null when there's no usable language tag so callers can skip
// highlighting entirely rather than render `DEFAULT` (which strips nothing).
private fun resolveLanguage(lang: String?): SyntaxLanguage? {
    if (lang.isNullOrBlank()) return null
    return when (lang.lowercase().trim()) {
        "kt", "kotlin"                              -> SyntaxLanguage.KOTLIN
        "js", "javascript", "jsx", "mjs", "cjs"     -> SyntaxLanguage.JAVASCRIPT
        "ts", "typescript", "tsx"                   -> SyntaxLanguage.TYPESCRIPT
        "py", "python"                              -> SyntaxLanguage.PYTHON
        "sh", "bash", "zsh", "shell"                -> SyntaxLanguage.SHELL
        "rb", "ruby"                                -> SyntaxLanguage.RUBY
        "rs", "rust"                                -> SyntaxLanguage.RUST
        "cpp", "c++", "cxx", "cc", "hpp"            -> SyntaxLanguage.CPP
        "cs", "csharp", "c#"                        -> SyntaxLanguage.CSHARP
        "go", "golang"                              -> SyntaxLanguage.GO
        "java"                                      -> SyntaxLanguage.JAVA
        "swift"                                     -> SyntaxLanguage.SWIFT
        "php"                                       -> SyntaxLanguage.PHP
        "dart"                                      -> SyntaxLanguage.DART
        "perl", "pl"                                -> SyntaxLanguage.PERL
        "coffee", "coffeescript"                    -> SyntaxLanguage.COFFEESCRIPT
        "c", "h"                                    -> SyntaxLanguage.C
        else                                        -> null
    }
}

private fun Color.toRgb(): Int = toArgb() and 0xFFFFFF
private fun rgbToColor(rgb: Int): Color =
    Color(red = (rgb shr 16) and 0xFF, green = (rgb shr 8) and 0xFF, blue = rgb and 0xFF)

// Highlights echoes these ints back unchanged in ColorHighlight.rgb, so the
// round-trip to a Compose Color is lossless.
private val piSyntaxTheme = SyntaxTheme(
    key              = "piremote",
    code             = textPrimary.toRgb(),
    keyword          = codeKeyword.toRgb(),
    string           = codeString.toRgb(),
    literal          = codeNumber.toRgb(),
    comment          = codeComment.toRgb(),
    metadata         = codeType.toRgb(),       // @annotations, decorators
    multilineComment = codeComment.toRgb(),
    punctuation      = codePunctuation.toRgb(),
    mark             = codeFunction.toRgb(),   // function-call style marks
)

// `remember` import without pulling all of runtime into this file.
@Composable
private fun <T> remember(key: Any?, calc: () -> T): T = androidx.compose.runtime.remember(key) { calc() }
@Composable
private fun <T> remember(k1: Any?, k2: Any?, calc: () -> T): T = androidx.compose.runtime.remember(k1, k2) { calc() }
