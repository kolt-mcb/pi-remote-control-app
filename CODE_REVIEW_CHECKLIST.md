# Code Review Checklist — Pi Remote Control

> Generated from code review on 2026-05-29

---

## ✅ Completed Items

| # | Item | Status |
|---|------|--------|
| 4 | `runBlocking` on main thread | ✅ Fixed - replaced with `viewModelScope.launch` |
| 5 | `PiWebSocket` scope never cancelled | ✅ Fixed - `disconnect()` now cancels the Job |
| 14 | `ChatMessageEntity` unnecessary auto-generated PK | ✅ Fixed - using composite PK `(url, msgId)` |
| 15 | Hardcoded default IP | ✅ Fixed - removed, now empty default |
| 18 | `loadUrlHistory` uses `runBlocking` | ✅ Fixed - changed to `suspend fun` |
| 6 | QR Scanner resource leak | ✅ Fixed - added `DisposableEffect` with `unbindAll()` |
| 7 | `SessionsScreen` refreshes on every recomposition | ✅ Fixed - key changed to `sessions.size` |
| 9 | `handleExtensionUI` null safety | ✅ Fixed - proper null checking |
| 17 | `UpdateChecker` GitHub API auth | ✅ Fixed - optional Bearer token support |
| 13 | ProGuard rules incomplete | ✅ Fixed - added keep rules for data classes |
| 11 | MainActivity cleanup | ✅ Fixed - added `onDestroy()` |

## 🔴 Critical / High Priority

### 1. `ChatDatabase` singleton race condition
- **File:** `app/src/main/java/com/piremote/db/ChatDatabase.kt`
- **Issue:** `@Volatile` + `synchronized(this)` double-checked locking pattern — `INSTANCE` can be observed as non-null while `build()` is still in progress in another thread.
- **Fix:** Replace with `by lazy` or let Room handle singleton internally:
  ```kotlin
  // Option A — by lazy
  private val INSTANCE: ChatDatabase by lazy { ... }
  // Option B — Room's built-in singleton (simplest)
  ```

### 2. Custom JSON parser edge cases
- **File:** `app/src/main/java/com/piremote/PiWebSocket.kt` (object `JP`, class `PS`)
- **Issues:**
  - No surrogate pair handling for emoji/non-BMP Unicode → potential crash on messages containing emoji
  - Accepts `+` prefix on numbers (e.g. `+123`) — invalid JSON
  - Accepts leading zeros (e.g. `01`) — invalid JSON
  - No handling of escaped control characters beyond the basic set
- **Fix:** Add test cases covering emoji, leading zeros, `+` prefix. Consider `kotlinx.serialization` for correctness (minimal APK impact).

### 3. `PiService` Android 14+ quota loop
- **File:** `app/src/main/java/com/piremote/PiService.kt`
- **Issue:** When FGS quota expires (6h/24h for `dataSync`), `startForeground` throws → service stops → WS auto-reconnects → `PiService.start()` called again → crash loop.
- **Fix:** Add a reconnect backoff counter that caps at the FGS quota. After N quota failures, fall back to not using a foreground service (connection dies on app background, but at least it doesn't crash).

### 4. `ChatViewModel.connect()` uses `runBlocking` on main thread
- **File:** `app/src/main/java/com/piremote/ChatViewModel.kt`
- **Issue:**
  ```kotlin
  runBlocking {
      val prefs = _ctx.dataStore.data.first()
      // ...
  }
  ```
  Blocks the Compose composition thread during `connect()`.
- **Fix:** Use `connectionScope.launch {}` or `withContext(Dispatchers.IO) {}` for DataStore reads/writes inside `connect()`.

---

## 🟡 Medium Priority

### 5. `PiWebSocket` scope never cancelled
- **File:** `app/src/main/java/com/piremote/PiWebSocket.kt`
- **Issue:**
  ```kotlin
  private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  ```
  `disconnect()` closes the socket but never calls `scope.cancel()`. Reconnection `scope.launch { delay(...); reconnect() }` blocks linger.
- **Fix:** Add `scope.cancel()` to `disconnect()` and a `close()` method called from `MainActivity.onDestroy()`.

### 6. QR Scanner resource leak
- **File:** `app/src/main/java/com/piremote/scan/QrScanner.kt`
- **Issue:** `AndroidView` binds camera to lifecycle but doesn't call `CameraProvider.unbindAll()` when the scanner is dismissed (user taps back).
- **Fix:** Store `ProcessCameraProvider` reference and call `unbindAll()` in a `DisposableEffect(Unit) { onDispose { ... } }`.

### 7. `SessionsScreen` refreshes saved sessions on every recomposition
- **File:** `app/src/main/java/com/piremote/screens/Screens.kt` (line ~430)
- **Issue:**
  ```kotlin
  LaunchedEffect(Unit) { vm.refreshSavedSessions() }
  ```
  Fires on every recomposition if the screen content is recomposed but not re-entered.
- **Fix:** Track entered state with `remember { mutableStateOf(false) }` and only call once per screen entry.

### 8. `parseAnsiLine` ignores DCS/OSC sequences
- **File:** `app/src/main/java/com/piremote/screens/TerminalView.kt`
- **Issue:** Only handles SGR (`\e[...m`). DCS (`\eP...`), OSC (`\e]...`) sequences from Pi extensions appear as garbled text in the rendered terminal.
- **Fix:** Either skip unrecognized escape sequences or handle common ones (cursor movement, clear-screen).

### 9. `handleExtensionUI` notify has no null-safety on both fields
- **File:** `app/src/main/java/com/piremote/PiWebSocket.kt`
- **Issue:**
  ```kotlin
  val msg = Js.gets(j, "message") ?: Js.gets(j, "content") ?: "Notification"
  ```
  If both fields exist but are non-string types, `Js.gets` returns `null` for both → falls back to "Notification" silently.
- **Fix:** Log a warning or prefer whichever field is actually set by checking `j["message"]` before `Js.gets`.

### 10. `UiExt.kt` — `NotifyBanner` and `PiWidgetPanel` use deprecated Surface
- **File:** `app/src/main/java/com/piremote/screens/UIExt.kt`
- **Issue:** Uses `Surface(color = ..., tonalElevation = 0.dp)` — in Compose Material3 this is deprecated in favor of `Box` with `background` modifier for flat surfaces.
- **Fix:** Replace with `Box(modifier = Modifier.background(...))`.

---

## 🟢 Low / Style / DX

### 11. Massive `MainActivity` composable
- **File:** `app/src/main/java/com/piremote/MainActivity.kt`
- **Issue:** 30+ `collectAsState` calls all in `onCreate`, screen switching logic inline. Hard to preview, hard to test.
- **Fix:** Extract into a dedicated `@Composable` (e.g. `AppContent(vm, ws)`) with a state data class.

### 12. No `@Preview` annotations
- **Files:** All `*.kt` files under `screens/`, `theme/`, etc.
- **Fix:** Add `@Preview(showBackground = true)` to key composables (`ConnectScreen`, `PiMarkdown`, `TerminalView`, `ThemeManager`).

### 13. ProGuard rules likely incomplete
- **File:** `app/proguard-rules.pro`
- **Issue:** Custom JSON parser (`JP.write`) uses reflection-like string access (`j["args"] as? Map<*, *>`). Obfuscation may break field name resolution if not configured.
- **Fix:** Add `@keep` rules for all data classes used in JSON deserialization.

### 14. `ChatMessageEntity` has unnecessary auto-generated PK
- **File:** `app/src/main/java/com/piremote/db/ChatMessageEntity.kt`
- **Issue:**
  ```kotlin
  @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
  // plus
  indices = [Index(value = ["url", "msgId"], unique = true)]
  ```
  `rowId` is generated but the semantic key is `(url, msgId)`. Room will use `rowId` for identity while you never reference it.
- **Fix:** Make `(url, msgId)` the composite primary key:
  ```kotlin
  @PrimaryKey(autoGenerate = false)
  val msgId: String,
  val url: String,
  ```

### 15. Hardcoded default IP in VM
- **File:** `app/src/main/java/com/piremote/ChatViewModel.kt`
- **Issue:** `"ws://192.168.1.100:8765"` is a specific IP. Users on different subnets will connect to a dead host.
- **Fix:** Leave blank and show a clear "Enter server URL" placeholder, or use a well-known default like `"ws://0.0.0.0:8765"` (which is wrong) — better yet, don't default; let the user enter it.

### 16. Missing `@OptIn(ExperimentalForegroundApi)` for FGS
- **File:** `app/src/main/java/com/piremote/PiService.kt`
- **Issue:** `FOREGROUND_SERVICE_DATA_SYNC` is declared but the `@RequiresPermission` / `@RequiresFeature` annotations may not be properly handled.
- **Fix:** Verify all FGS permissions are guarded with proper API-level checks.

### 17. `UpdateChecker` does not authenticate with GitHub API
- **File:** `app/src/main/java/com/piremote/UpdateChecker.kt`
- **Issue:** No `Authorization` header. Rate-limited to 60 req/hour unauthenticated.
- **Fix:** Add `GITHUB_TOKEN` (read-only, stored in DataStore) for 5000 req/hour. Or accept the rate limit since updates are infrequent.

### 18. `ChatViewModel` uses `runBlocking` in `loadUrlHistory()` too
- **File:** `app/src/main/java/com/piremote/ChatViewModel.kt`
- **Issue:**
  ```kotlin
  fun loadUrlHistory() {
      try { runBlocking { ... } } catch (_: Throwable) {}
  }
  ```
  Same issue as #4 — blocks on main thread. This is called during `LaunchedEffect(Unit)` so it's already in a coroutine scope.
- **Fix:** Make it `suspend fun` and call it from within `LaunchedEffect`.

---

## ✅ Things Done Well (Keep These)

| # | Item | Why It's Good |
|---|---|---|
| 1 | Per-agent `AgentState` isolation | Prevents multi-peer state collision; clean `flatMapLatest` derivation |
| 2 | Certificate pinning by SHA-256 | End-to-end encrypted WSS without CA trust — perfect for LAN-first |
| 3 | Streaming protocol with dedup | Text, thinking, tool calls properly sequenced; no duplicate bubbles |
| 4 | Turn-end persistence (not per-token) | Saves massive I/O; `REPLACE` on `(url, msgId)` ensures upsert |
| 5 | Foreground service with graceful degradation | Connection survives app background; handles Android 14 quota failures |
| 6 | Viewport reporting to host | Extension components render at phone width — no horizontal scroll |
| 7 | In-app APK updater with FileProvider | Safe, no root, no sideload required |
| 8 | Theme mirroring from host | Phone UI matches terminal aesthetic automatically |
| 9 | `spawn_peer` instead of `ctx.newSession()` | Avoids extension runtime invalidation — smart workaround |
| 10 | Custom lightweight JSON parser | No dependency for simple wire protocol; keeps APK small |
| 11 | Terminal-themed UI (ASCII borders, monospace) | Matches pi's aesthetic; cohesive branding |
| 12 | Turn summary bar | Compact tool-usage recap after each agent turn |
| 13 | Working status line with elapsed time | "Working... (5s · tap to interrupt)" — great UX |
| 14 | Saved sessions browser | Tap-to-resume via `pi --session <path>` peer spawn |
| 15 | Extension UI protocol (dialogs, widgets, notify) | Handles full extension protocol spec |
