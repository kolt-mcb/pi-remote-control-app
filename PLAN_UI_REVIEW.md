# PLAN: UI review fixes

Source: UI design review of the Compose interface (2026-07-19). Goal: fix
accessibility, legibility, and layout issues in the chrome around the mirror,
remove dead UI left from the mirror-first refactor, and de-duplicate the
phone/tablet screens. The mirror surface itself (`MirrorScreen.kt`) needs no
changes.

## Shared conventions (all phases)

- **Touch targets**: every interactive element must have a hit area ≥ 44dp
  tall (48dp preferred). Keep the compact *drawn* look — expand the hit area,
  not necessarily the visuals: prefer `Modifier.minimumInteractiveComponentSize()`
  (Material3) on small chips/buttons; where that visibly breaks a dense row,
  use larger padding instead. Order matters: size/hit-area modifiers go before
  `clickable` padding tricks — verify each control actually measures ≥ 44dp.
- **Semantics**: pass `role = Role.Button` (and a meaningful `onClickLabel`)
  to `clickable`/`combinedClickable` on interactive `Box`/`Row`/`Text`
  elements. Decorative status dots (a colored `Box` with `CircleShape`) that
  sit next to a text label need no contentDescription.
- **Color-blind support**: status words get a glyph prefix so state is not
  color-only: `◉ busy` / `● active` / `○ idle` (keep existing colors).
- **Legibility floor**: no text below 10.sp. Raise every 8.sp and 9.sp to
  10.sp. Remove alpha-stacking on muted text: replace every
  `textMuted.copy(alpha = …)` with plain `textMuted`.
- **Style**: keep the terminal aesthetic (square corners, piMono, box-drawing
  borders). KeyCap's 4dp rounding is intentional keyboard chrome — keep it.
- Do NOT reformat unrelated code, do NOT run git commands, do NOT commit.
  Leave changes in the working tree. There are pre-existing uncommitted
  modifications — preserve them.
- Build check command: `./gradlew :app:compileDebugKotlin` (run only when your
  phase says to — parallel phases must not run gradle).

## Phase 1 — Dead code removal (single agent, whole codebase)

The mirror-first refactor left an unused legacy rendering path. Remove:

1. **Salvage first**: `screens/TtyScrollback.kt` contains two LIVE functions —
   `rememberTtyMetrics` (+ its `TtyMetrics` data class, used by
   `Screens.kt:1144`) and `ImageViewerDialog` + its private helper `saveImage`
   (used by `MirrorScreen.kt`). Move them into a new file
   `screens/TtyViewer.kt` (same package), then delete `TtyScrollback.kt`
   entirely (kills `TtyScrollback`, `TtyTextItem`, `buildTtyText`,
   `AnsiStyle.toSpanStyle`, `TtyImageItem`, `TtyBrailleFallback`).
2. Delete `screens/ScrollbackRenderer.kt` (only caller was `TtyScrollback`).
3. In `Screens.kt` delete unreferenced composables: `TurnSummaryPanel`,
   `PiTurnToolChip`, `PiWorkingStatus`, `PiFooter`, `PiBlinkBlock`,
   `PiStartupHeader`, `piSpinnerFrames`.
4. In `UIExt.kt` delete `PiWidgetPanel`, `PiStatusBarLine`, and the
   backwards-compat aliases `WidgetPanel` / `StatusBarLine` (nothing calls
   any of them).
5. Slim `ChatScreen` (`Screens.kt`): remove the dead `LazyListState` block
   (`ls`, `scrollKey`, and the auto-scroll `LaunchedEffect` near lines
   1057-1064 — `ls` is never attached to any list) and remove now-unused
   parameters: `url`, `input`, `messages`, `assist`, `commands`, `statuses`,
   `widgets`, `turnSummary`. Keep: `vm`, `status`, `busy`, `sessions`,
   `selectedSession`, `compacting`, `notifyBanners`, `uiTitle`, `clientCount`,
   `showSessionSelector`. Update BOTH call sites (`MainActivity.kt`,
   `TabletLayout.kt`) and drop any params those two only forwarded to
   ChatScreen (e.g. `turnSummary`, `widgets`, `statuses`, `commands` in
   `TabletLayout` — check `MainActivity` collect sites and remove collects
   that become unused). `compacting` is currently unused inside ChatScreen
   too — remove it as well if nothing else in ChatScreen uses it.
6. Remove imports that become unused in every touched file.
7. Run `./gradlew :app:compileDebugKotlin`; fix any errors you introduced.

## Phase 2 — Parallel per-file fixes (one agent per batch; edit ONLY your files; no gradle)

### 2A — `screens/Screens.kt`

- Touch targets (per conventions): `PiTerminalChip`, `PiMenuItem`, the
  `[Disconnect]` box in `PiHeader`, `Retry` box in `ConnectScreen`,
  `[+ New session]` box, category filter tabs in `SessionsScreen`,
  `SavedSessionRow`, the version chip in `UpdateAffordance`.
- Semantics + glyphs per conventions (SessionCard status words, PiHeader,
  PiSessionSelector).
- Legibility floor per conventions (many 9.sp / 10.sp / alpha-stacked spots).
- `UpdateAffordance` discoverability: render the version text as a bordered
  chip (like `PiTerminalChip`) labeled `v$currentCode · $currentName · updates`,
  full `textMuted` (no alpha), so it reads as tappable.
- `ConnectScreen` fixes:
  - Make the content Column scrollable (`verticalScroll(rememberScrollState())`);
    replace `Spacer(Modifier.weight(1f))` with a fixed spacer (weight doesn't
    work in a scrollable column).
  - Disable the Connect button when the URL field is blank (dim border/text
    to `borderMuted`/`textMuted`, `clickable(enabled = …)`).
  - Menu option 3 "Recent connections": when `urlHistory` is empty, render it
    disabled with a `(none)` suffix instead of silently doing nothing.
- `SessionsScreen` layout: the active-session `LazyColumn` (~line 568) is
  unbounded inside a non-scrollable Column and starves the Saved-sessions box.
  Give it `Modifier.weight(1f, fill = false).heightIn(max = 280.dp)` so the
  saved list and nav hints always remain reachable.

### 2B — `screens/TabletLayout.kt`

- Touch targets: `[Disconnect]`, `Browse`, `[+ New]`, category tabs,
  `SidebarSessionItem`, `SidebarSavedSessionItem`.
- Legibility floor: this file bottoms out at 8.sp — raise all 8/9.sp to 10.sp;
  remove `.copy(alpha = …)` on muted text.
- Semantics + status glyphs per conventions.
- Rename the tablet category tab label "Done" back to "Completed" (parity with
  phone; will be centralized in Phase 3).

### 2C — `screens/TabletConnectScreen.kt`

- Same Connect-panel fixes as 2A (scrollable right column, disabled Connect
  on blank URL, option-3 `(none)` state) — keep the code shape close to 2A's
  so Phase 3 can merge them easily.
- Touch targets ("Tap to reopen", Retry, Connect, chips) and legibility floor.
- Semantics per conventions.

### 2D — `screens/TtyKeyboard.kt`

- `KeyCap`: grow to ≥ 44dp tall hit area (raise vertical padding / add
  `minimumInteractiveComponentSize`) — these are the most-used controls.
  Keep the 4dp rounding.
- Pin `paste` and `📎` at the right edge OUTSIDE the horizontal scroll region
  (Row { scrollable Row(weight 1f) { keys… }; KeyCap(paste); KeyCap(📎) }) so
  they're always visible on narrow phones.
- Give `📎` a real contentDescription/onClickLabel ("attach image");
  `role = Role.Button` on all keycaps.

### 2E — `screens/UIExt.kt` + `screens/FileDownloadDialog.kt`

- `SelectDialog` `[✕]` close: ≥ 44dp hit area + role/onClickLabel. Same for
  YES/NO halves (already tall — verify), option rows, and `InputDialog`'s
  `[OK]`/`[CANCEL]` boxes.
- Legibility floor in both dialogs.
- `FileDownloadDialog`: style text with `piMono` + theme colors to match every
  other dialog (title 14.sp bold accent, body 12.sp, buttons styled like
  `InputDialog`'s).

### 2F — `MainActivity.kt`

- Tablet back-press currently disconnects instantly (`BackHandler` →
  `vm.disconnect()`). Add a confirm `AlertDialog` ("Disconnect from host?",
  piMono-styled like `SessionCard`'s close dialog) driven by a local
  `showDisconnectConfirm` state; back press sets it true, confirm
  disconnects, dismiss cancels.

## Phase 3 — Phone/tablet dedup (single agent, after Phase 2 builds green)

Extract shared components into `screens/ConnectPanel.kt` (new file):

1. `ConnectPanel(...)` — the header + Options menu + URL input + recents +
   Connect button + status block + Quick Start box, currently duplicated
   (~120 lines) between `ConnectScreen` and `TabletConnectScreen`. Parameterize
   only what differs (subtitle "phone"/"tablet", scan-option callback).
   Rewrite both screens to use it.
2. Unify the saved-session row: keep the richer phone version
   (`SavedSessionRow` — status badge + last-assistant-message preview) and use
   it in the tablet sidebar too (drop `SidebarSavedSessionItem`), with a
   `compact: Boolean` param if the sidebar needs tighter spacing.
3. Unify the category filter tab row (phone `SessionsScreen` vs
   `TabletSidebar`) into one composable with identical labels/counts.
4. Visual parity is the acceptance bar: both form factors keep their current
   layout structure; only the duplicated internals move.
5. Run `./gradlew :app:compileDebugKotlin`; fix errors you introduced.

## Phase 4 — Verification checklist (read-only verifiers)

- **build**: `./gradlew :app:compileDebugKotlin` passes.
- **dead-code**: no references to any deleted symbol; `TtyScrollback.kt` /
  `ScrollbackRenderer.kt` gone; `ImageViewerDialog` + `rememberTtyMetrics`
  still resolve; ChatScreen signature slimmed at both call sites.
- **targets**: every `clickable`/`combinedClickable` in the screens package
  sits on an element measuring ≥ 44dp in its tap dimension (or has
  `minimumInteractiveComponentSize`).
- **legibility**: no `fontSize` below 10.sp anywhere in `screens/`; no
  `textMuted.copy(alpha` remains.
- **semantics**: interactive boxes carry `role = Role.Button`; status words
  carry glyphs.
- **dedup**: no duplicated connect-panel bodies; one saved-session row
  composable; tab labels identical across form factors.
