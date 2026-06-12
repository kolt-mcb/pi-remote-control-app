# Plan: Move rendering to the host — app stays a dumb TTY

> **Status (June 2026): implemented.** All phases landed, with one design
> upgrade over this plan: instead of reimplementing markdown→ANSI in
> TypeScript, the host re-renders pi's own interactive components
> (AssistantMessageComponent / UserMessageComponent / ToolExecutionComponent)
> headless at the phone's width — exact terminal parity for free. The wire
> shape is the single `stream` field (+ `streamExpanded` for tool results)
> from Phase 3, skipping the intermediate per-type `ansiLines` step. Styled
> streaming ships as throttled `ansi_snapshot` re-renders rather than ANSI
> deltas. See PROTOCOL.md "Transport" for the final contract.

## Problem

The Android app is a TTY terminal, but `MessageNormalizer.toStream()` does
client-side rendering: markdown parsing, syntax highlighting, diff formatting,
gutter decoration, tool-header composition. This means:

- Every client must reimplement the host's presentation logic.
- Bugs live in the client renderer (the `highlightCodeToAnsi` bug we just fixed).
- `ansiLines` from the host is the *right* path — it's what a TTY should do —
  but it's only honored for `Custom` messages and some `ToolResult` messages.
  Everything else (Assistant, User, Thinking, most ToolResults) is rendered
  client-side from raw structured fields.

## Goal

The host renders every message to ANSI and sends it as `ansiLines[]`.
The Android app renders `ansiLines` verbatim through the TTY pipeline and
stops doing client-side markdown/syntax/diff formatting.

The TTY pipeline (`TtyStreamParser` → `parseAnsiLine` → `buildAnsiText`) is
already correct and stays untouched.

---

## Current state by message type

| Type         | What the host sends            | What the client does today                    |
|-------------|-------------------------------|----------------------------------------------|
| **User**    | `content` (plain text)         | Wraps, adds `> ` prefix, accent color        |
| **Assistant**| `content` (markdown)          | `markdownToAnsiLines()` — full md→ANSI conv. |
| **Thinking**| `content` (plain text)        | Adds `✻ Thinking…` header, wraps, dims      |
| **ToolResult**| `content`, `toolName`, `toolArgs`, `isError` | Composes `● ToolName(args)` header, renders edit diffs (`-`/`+`), wraps, highlights code, adds error badge |
| **ToolResult (with ansiLines)** | `ansiLines[]` | Passes through verbatim ✓ |
| **Custom**  | `ansiLines[]`                  | Passes through verbatim ✓ |
| **Streaming**| `text_delta` events           | Accumulates, converts to Assistant on `text_end` |

---

## Phase 1 — Host side (pi-remote-control extension, TypeScript)

The host extension (`extension.ts` or equivalent) needs to render each message
to ANSI *before* sending it. The phone's viewport width is already known
(`reportViewport` → `viewport` message).

### 1.1. Assistant messages — markdown → ANSI

At `message_end` (role: assistant), the host renders the final markdown
content to ANSI and attaches it as `ansiLines[]`.

**What the host needs:**
- A markdown → ANSI converter (same logic as `MarkdownToAnsi.kt` but in
  TypeScript). The Highlights library or a Node.js syntax highlighter for
  code blocks; simple regex for inline bold/italic/code/links.
- The phone's column width for wrapping (already reported via `viewport`).
- The host's syntax theme colors (already known — the host sends
  `theme_info` with `syntaxKeyword`, `syntaxString`, etc., so it has the
  palette).

**Wire format change:** `message_end` gains an optional `ansiLines[]`:
```json
{
  "type": "message_end",
  "agentId": "...",
  "message": {
    "role": "assistant",
    "content": "# Hello\n**bold**",
    "ansiLines": ["\u001b[38;2;...m│\u001b[0m # Hello\n...", ...]
  }
}
```

### 1.2. Tool results — compose the full display

At `tool_end`, the host renders the complete tool result display:
- Header line: `● ToolName(args) [error]` with styling
- For `edit`/`multiedit`: diff lines with `- old` (red) / `+ new` (green)
- For `write`: `+ file content` lines
- Code blocks: syntax-highlighted
- Plain text: word-wrapped

All sent as `ansiLines[]` on the `tool_end` event.

### 1.3. User messages — add `>` prefix

At `message_start` / `message_end` (role: user), the host wraps the content
with the `> ` prefix and accent color, sends as `ansiLines[]`.

### 1.4. Thinking messages — add header

At `thinking_end`, the host adds the `✻ Thinking…` header and dim styling,
sends as `ansiLines[]` on a `message_end` with `role: thinking`.

### 1.5. Streaming text — stays as-is for now

`text_delta` is incremental and can't be pre-rendered to complete ANSI lines.
The host continues sending raw text deltas. On `text_end`, the host sends the
final rendered `ansiLines[]` so the Streaming→Assistant transition shows the
fully rendered output.

**Future option:** Send ANSI-formatted deltas (`ansi_delta`) so streaming
text carries formatting. Requires the client to accumulate ANSI segments
incrementally. Low priority.

---

## Phase 2 — Android app side

### 2.1. Honor `ansiLines` for all message types

In `MessageNormalizer.toStream()`, when `ansiLines` is present and
non-empty, pass it through verbatim for **every** message type (User,
Assistant, ToolResult, Thinking). Currently only done for Custom and some
ToolResults.

**File: `MessageNormalizer.kt`**
```kotlin
fun toStream(msg: ChatMessage, cols: Int, expanded: Boolean): String {
    // If the host sent rendered ANSI, use it for any message type.
    msg.ansiLines?.takeIf { it.isNotEmpty() }?.let { lines ->
        return lines.joinToString("\n", postfix = "\n") { gutter(it) }
    }
    return when (msg.type) {
        // ... existing fallbacks for older hosts ...
    }
}
```

### 2.2. Remove `MarkdownToAnsi.kt` — delete entirely

This file contains:
- `markdownToAnsiLines()` — markdown → ANSI converter
- `inlineToAnsi()` — inline bold/italic/code/link → ANSI
- `highlightCodeToAnsi()` — syntax highlighting via Highlights library
- `resolveLanguage()` — language alias mapping
- `piAnsiSyntaxTheme` — syntax theme colors
- Helper regexes, ANSI escape builders

**Replace:** Remove the dependency on `dev.snipme.highlights` from
`build.gradle.kts` (the Highlights library).

**Keep:** `ansiGutterLine()` — move to `TtyEscapes.kt` or `MessageNormalizer.kt`.

### 2.3. Simplify `MessageNormalizer.kt`

Remove the client-side rendering logic:
- `userStream()` — delete (host renders `> ` prefix)
- `assistantStream()` — delete (host renders markdown)
- `toolStream()` — delete the diff rendering, code highlighting, content
  wrapping. Keep only the fallback for hosts without `ansiLines`.
- `thinkingStream()` — delete (host renders thinking header)
- `summaryLine()` — delete
- `imageStream()` — **keep** (structured `images[]` → OSC 1337 is fine,
  or the host can do this too in a future phase)
- `wrapText()` — delete
- `escapeContent()` — delete

The file becomes a thin adapter:
```kotlin
object MessageNormalizer {
    fun toStream(msg: ChatMessage, cols: Int, expanded: Boolean): String {
        msg.ansiLines?.takeIf { it.isNotEmpty() }?.let {
            return it.joinToString("\n", postfix = "\n") { gutter(it) }
        }
        // Fallback for older hosts or message types without ansiLines:
        return when (msg.type) {
            MessageToolType.User -> simpleUserFallback(msg, cols)
            MessageToolType.Assistant -> simpleAssistantFallback(msg, cols)
            MessageToolType.ToolResult -> simpleToolFallback(msg, cols, expanded)
            MessageToolType.Thinking -> simpleThinkingFallback(msg, cols, expanded)
            MessageToolType.Streaming -> simpleStreamingFallback(msg, cols)
            MessageToolType.Custom -> customStream(msg)
        }
    }
}
```

### 2.4. Remove unused imports and theme colors

- Remove the syntax theme colors from `PiRemoteTheme` data class and
  `parseRemoteTheme()` in `PiWebSocket.kt`:
  `codeKeyword`, `codeString`, `codeNumber`, `codeComment`, `codeType`,
  `codeFunction`, `codePunctuation`, `codeOperator`
- Remove the markdown colors: `mdHeading`, `mdLink`, `mdLinkUrl`, `mdCode`,
  `mdCodeBlock`, `mdCodeBlockBorder`, `mdQuote`, `mdQuoteBorder`, `mdListBullet`

Keep: `accent`, `success`, `error`, `thinkingBorder`, `textMuted`, `borderMuted`,
`toolBorder`, `textPrimary`, `textSecondary` — used by the UI chrome
(boxes, headers, footer, status bar, dialogs).

### 2.5. Remove `CodeUtils.kt`

`isCodeContent()` and `countLines()` are only used by `MessageNormalizer`
to decide when to highlight code. Delete the file.

### 2.6. Update unit tests

- **Delete:** All tests that depend on client-side rendering:
  - `MessageNormalizerTest.assistantMarkdownRendersStyledText`
  - `MessageNormalizerTest.markdownLinkBecomesOsc8`
  - `MessageNormalizerTest.editToolExpandedShowsDiffColors`
  - `MessageNormalizerTest.editToolCollapsedIsSummaryOnly`
  - `MessageNormalizerTest.thinkingCollapsedIsSingleLine`
  - `MessageNormalizerTest.thinkingExpandedShowsBody`
  - `MessageNormalizerTest.toolErrorBadgeInHeader`
  - `MessageNormalizerTest.wrapTextRespectsWidthAndNewlines`
- **Keep:** Tests that verify TTY pipeline correctness:
  - `MessageNormalizerTest.userMessageWithTwoImages`
  - `MessageNormalizerTest.hostAnsiLinesPassThrough`
  - `MessageNormalizerTest.hostOscImageInsideAnsiLinesPassesThrough`
  - `MessageNormalizerTest.userContentCannotInjectEscapes`
  - All `TtyStreamParserTest` tests
  - All `WrapTest` tests
- **Add:** Tests for the new fallback behavior:
  - "ansiLines on Assistant message passes through"
  - "ansiLines on ToolResult passes through"
  - "ansiLines on User message passes through"
  - "ansiLines on Thinking message passes through"
  - "Missing ansiLines falls back to simple plain-text rendering"

### 2.7. Build.gradle.kts — remove Highlights dependency

```kotlin
// Remove:
implementation("dev.snipme.highlights:highlights:VERSION")
```

---

## Phase 3 — Polish (optional)

### 3.1. Host sends images as OSC 1337 inside `ansiLines`

Instead of the `images[]` array, the host embeds OSC 1337 sequences directly
in `ansiLines`. The client's `TtyStreamParser` already handles this correctly.
The client-side `imageStream()` desugaring goes away.

### 3.2. Single `stream` field per message

As documented in PROTOCOL.md: replace `content` + `ansiLines[]` with a single
`stream` field. The app feeds it through `TtyStreamParser.parse()` directly,
bypassing `MessageNormalizer` entirely.

### 3.3. ANSI-formatted streaming deltas

Host sends `ansi_delta` (pre-rendered ANSI fragments) instead of raw text
deltas. Client accumulates them into a single ANSI buffer. On `text_end`,
the buffer becomes the final `ansiLines`.

---

## Files affected

**Delete:**
- `app/src/main/java/com/piremote/screens/MarkdownToAnsi.kt`
- `app/src/main/java/com/piremote/screens/CodeUtils.kt`

**Simplify:**
- `app/src/main/java/com/piremote/tty/MessageNormalizer.kt` (from ~170 lines to ~40)

**Modify (remove theme color fields):**
- `app/src/main/java/com/piremote/theme/Theme.kt` (PiRemoteTheme data class)
- `app/src/main/java/com/piremote/theme/Color.kt` (shim getters)
- `app/src/main/java/com/piremote/PiWebSocket.kt` (`parseRemoteTheme()`)

**Modify (update tests):**
- `app/src/test/java/com/piremote/tty/MessageNormalizerTest.kt`

**Modify (remove dependency):**
- `app/build.gradle.kts` (Highlights library)

**Unchanged (the TTY pipeline stays as-is):**
- `TtyStreamParser.kt` — ANSI parser ✓
- `Sgr.kt` — SGR color model ✓
- `TerminalView.kt` — `buildAnsiText()` ✓
- `TtyScrollback.kt` — scrollback renderer ✓
- `TtyBlock.kt` — block model ✓
- `Wrap.kt` — viewport wrapping ✓
- `TtyEscapes.kt` — escape builders ✓
- `UIExt.kt` — extension UI dialogs (use `parseAnsiLine` already) ✓

---

## Order of operations

1. **Host:** Implement ANSI rendering for all message types. Ship the host
   extension update. At this point, `ansiLines` is present on all messages
   from new hosts, but the client still has its old rendering as fallback.

2. **Client (this PR):** Implement Phase 2 — honor `ansiLines` universally,
   simplify `MessageNormalizer`, delete client-side renderers. The fallback
   paths handle old hosts.

3. **Client (later):** Remove fallback paths when old hosts are no longer
   supported.

4. **Both (later, optional):** Phase 3 — images in `ansiLines`, single
   `stream` field, ANSI deltas.
