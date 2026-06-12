# TTY Rendering Protocol

The phone app renders chat content as a terminal: every message is normalized
into a single ANSI+OSC stream, parsed by `com.piremote.tty.TtyStreamParser`
into typed blocks (styled text, inline images), and drawn as a continuous
monospace scrollback. This document is the contract for what the host
extension (or any program whose output reaches the app) may emit.

## Transport

Escape sequences ride inside JSON string fields, in precedence order:

- `stream` — the **primary channel**: one ANSI+OSC string holding the
  message's complete presentation, rendered by the host at the phone's
  reported width (`viewport` → `clientCols`). The app feeds it through the
  TTY parser verbatim — no client-side decoration at all. Tool results also
  carry `streamExpanded`, the expanded variant used when the user taps to
  expand (absent = same as `stream`).
- `ansiLines[]` — legacy per-line shape from older hosts (each entry is one
  line; the app joins with `\n` and adds a gutter). Ignored when `stream`
  is present.
- `content` — raw text fallback, rendered client-side as plain wrapped text
  when neither of the above is present.

`stream` appears on: `message_start`/`message_end` (user messages),
`message_update` `text_end` / `thinking_end` (final rendered block),
`message_update` `ansi_snapshot` (throttled re-render of the in-flight
block, so streaming text is styled), `tool_end`, and every `history` item.

Oversize images the host cannot embed in the stream (> 8 MiB base64) still
travel as a structured `images[]` array; the app appends them after the
stream. The host extension renders with pi's own interactive components
(AssistantMessageComponent, UserMessageComponent, ToolExecutionComponent),
so the phone shows exactly what the terminal shows.

The host re-sends the full `history` replay whenever the reported viewport
width changes, re-rendering the whole scrollback at the new width. Hosts in
peer mode receive the width via `route_viewport` forwarded by the host.

Because raw program output from the Pi flows through tool output and
`ansiLines`, standard terminal tools (`imgcat`, kitty `icat`, `chafa`,
matplotlib terminal backends) render inline images in the app with **zero
host-extension changes**.

## SGR styling (CSI ... m)

Supported codes:

| Codes | Meaning |
|---|---|
| `0` (or empty) | reset (does not close an open OSC 8 link) |
| `1` `2` `3` `4` | bold, dim, italic, underline |
| `30–37` / `40–47` | 16-color foreground / background |
| `90–97` / `100–107` | bright foreground / background |
| `38;5;N` / `48;5;N` | xterm 256-color |
| `38;2;R;G;B` / `48;2;R;G;B` | truecolor |
| `39` / `49` | default foreground / background |

All other CSI sequences (cursor movement, erase, scroll regions) are **parsed
and silently dropped** — the scrollback is append-only. Full-screen TUIs must
use render frames (`{type: "render", ...}`) instead, which keep their own
replace-mode surface.

## OSC 8 hyperlinks

```
ESC ] 8 ; params ; URI  (BEL | ESC \)   ...link text...   ESC ] 8 ; ;  (BEL | ESC \)
```

Link text gets a sub-line tap target that opens the URI in the phone browser.
`params` (e.g. `id=`) are accepted and ignored. SGR resets inside the link do
not close it; only `8;;` does.

## OSC 1337 inline images (primary image channel)

```
ESC ] 1337 ; File = inline=1 ; size=N ; mime=image/png ; width=W ; height=H ;
            preserveAspectRatio=0|1 : <base64>  (BEL | ESC \)
```

- `inline=1` is **required**; sequences without it are dropped.
- `width` / `height` accept `N` (terminal cells), `Npx`, `N%` (of viewport),
  or `auto` (default — natural size capped to the viewport).
- `mime` is a piremote extension (iTerm2 uses `name=`); when absent the type
  is sniffed from the payload magic bytes. PNG / JPEG / GIF / WebP.
- `preserveAspectRatio=0` stretches to the requested box; default preserves.
- The newline conventionally emitted after the sequence is swallowed (it does
  not produce a blank line).

## kitty graphics (APC)

```
ESC _ G key=val,key=val ; <base64 payload> ESC \
```

Supported subset:

- `a=T` (or `t`): transmit + display. Other actions (placement `a=p`,
  delete `a=d`, animation) are dropped.
- `f=100` (PNG) only. Raw `f=24/32` and zlib `o=z` payloads are dropped.
- Chunking: `m=1` on every chunk except the last (`m=0`), correlated by
  `i=<id>` (continuation chunks may omit `a`/`f`). An unterminated chunk
  train is discarded at end of stream.
- `c=<cols>` / `r=<rows>` set the display size in cells.

## Terminators

The app **accepts** BEL (`0x07`) and ST (`ESC \`) for OSC; kitty APC requires
ST. Emit ST — it is the spec-correct form and what `TtyEscapes` produces.

## Limits & error handling

- Image payloads: ≤ 8 MiB of base64 (~6 MB decoded). Decoded bitmaps are
  downsampled to ≤ 2048 px on the longest axis.
- Other OSC payloads: ≤ 4 KiB.
- Malformed, unterminated, or oversized sequences are **dropped whole**;
  surrounding text is preserved; the parser never throws.
- ESC characters inside user-typed content are stripped before rendering, so
  user input cannot inject sequences.

## Host migration path

Complete as of June 2026 — the host extension renders every message type to
a single `stream` field (with images embedded as OSC 1337), and the app
renders it verbatim. The structured fields (`content`, `ansiLines[]`,
`images[]`) remain as documented above for older hosts and as the raw-data
fallback when host-side rendering fails.
