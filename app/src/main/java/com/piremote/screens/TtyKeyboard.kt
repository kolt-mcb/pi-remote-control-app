package com.piremote.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piremote.ChatViewModel
import com.piremote.theme.accent
import com.piremote.theme.bg
import com.piremote.theme.bgSecondary
import com.piremote.theme.textPrimary

/**
 * Raw key-sequence encoding for the tty keyboard — pure, no Compose, so it can be
 * unit-tested. The app is a "dumb TTY": these bytes are forwarded verbatim into
 * pi's real PTY via `mirror_input`, exactly like a terminal emulator would send
 * them, and pi echoes them back into the mirror.
 */
object TtyKeys {
    const val ESC = "\u001b"
    const val TAB = "\t"
    const val ENTER = "\r"
    const val DEL = "\u007f"
    const val UP = "\u001b[A"
    const val DOWN = "\u001b[B"
    const val RIGHT = "\u001b[C"
    const val LEFT = "\u001b[D"
    const val CTRL_C = "\u0003"
    const val CTRL_D = "\u0004"

    // Bracketed paste: pi's editor treats text between these markers as a literal
    // paste (multi-line safe — embedded newlines don't submit, large content
    // collapses), matching pi's own pasteToEditor. We wrap clipboard content in
    // them so a phone paste lands in pi's prompt exactly like a desktop paste.
    const val PASTE_START = "\u001b[200~"
    const val PASTE_END = "\u001b[201~"

    /** Wrap clipboard [text] for a bracketed paste into pi's prompt. Empty -> "". */
    fun bracketedPaste(text: String): String =
        if (text.isEmpty()) "" else PASTE_START + text.replace("\r\n", "\n") + PASTE_END

    /**
     * Encode typed [text] into raw terminal bytes. Sticky [ctrl]/[alt] are
     * one-shot: they apply to the FIRST character only (the caller clears them
     * after). Newlines become carriage returns (what a TTY expects for Enter).
     *
     * Ctrl maps a key to its control byte (Ctrl-A=0x01 … Ctrl-_=0x1f); Alt
     * prefixes ESC ("meta sends escape", the xterm convention).
     */
    fun encode(text: String, ctrl: Boolean, alt: Boolean): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder()
        text.forEachIndexed { i, c ->
            val ch = if (c == '\n') '\r' else c
            if (i == 0 && (ctrl || alt)) {
                if (alt) sb.append(ESC)
                sb.append(if (ctrl) ctrlByte(ch) else ch)
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** Control-key byte for a character: e.g. 'C' -> 0x03, '[' -> 0x1b (ESC). */
    private fun ctrlByte(c: Char): Char = (c.uppercaseChar().code and 0x1f).toChar()
}

/** Sticky (one-shot) modifier state shared between the key row and the capture
 *  field. Tap a modifier to arm it; it clears after the next character. */
@Stable
class StickyMods {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    fun clear() { ctrl = false; alt = false }
}

/**
 * The bottom input strip for the mirror: a row of special keys (Esc, Tab, Ctrl,
 * Alt, arrows, ^C, ^D) above the soft keyboard, plus a hidden capture field that
 * turns typed characters into raw PTY bytes. There is no separate chat box — you
 * type straight into pi's own prompt, which echoes inside the mirror.
 *
 * Every emitted byte string goes to [ChatViewModel.sendMirrorInput] with the
 * same [agentId] the mirror taps use, so typing and tapping target one session.
 */
@Composable
fun TtyKeyboardBar(vm: ChatViewModel, agentId: String, focusRequester: FocusRequester) {
    val mods = remember { StickyMods() }
    val send: (String) -> Unit = { if (it.isNotEmpty()) vm.sendMirrorInput(it, agentId) }

    // Image picker — images can't be typed as bytes, so they stay on the
    // semantic prompt path: pick one and it's sent to the selected session.
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val bytes = context.contentResolver.openInputStream(it)?.use { s -> s.readBytes() }
                if (bytes != null) {
                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    val mime = context.contentResolver.getType(it)
                        ?.takeIf { m -> m.startsWith("image/") } ?: "image/jpeg"
                    vm.addPendingImage("data:$mime;base64,$base64")
                    vm.sendPromptWithImages()
                }
            } catch (_: Exception) {}
        }
    }

    // Match the old input's root (fillMaxWidth + bg + imePadding) so the bar rides
    // just above the soft keyboard.
    Column(modifier = Modifier.fillMaxWidth().background(bg).imePadding()) {
        ModifierKeyRow(
            mods = mods,
            onBytes = send,
            onPickImage = {
                imagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onPaste = {
                val pasted = clipboard.getText()?.text
                if (!pasted.isNullOrEmpty()) send(TtyKeys.bracketedPaste(pasted))
            },
        )
        TtyInputCapture(mods = mods, focusRequester = focusRequester, onBytes = send)
    }
}

/** Scrollable row of special keys. Ctrl/Alt arm sticky modifiers (highlighted
 *  while armed); the rest emit their escape sequences immediately. */
@Composable
private fun ModifierKeyRow(
    mods: StickyMods,
    onBytes: (String) -> Unit,
    onPickImage: () -> Unit,
    onPaste: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyCap("esc") { onBytes(TtyKeys.ESC) }
        KeyCap("tab") { onBytes(TtyKeys.TAB) }
        KeyCap("ctrl", active = mods.ctrl) { mods.ctrl = !mods.ctrl }
        KeyCap("alt", active = mods.alt) { mods.alt = !mods.alt }
        KeyCap("↑") { onBytes(TtyKeys.UP) }
        KeyCap("↓") { onBytes(TtyKeys.DOWN) }
        KeyCap("←") { onBytes(TtyKeys.LEFT) }
        KeyCap("→") { onBytes(TtyKeys.RIGHT) }
        KeyCap("^C") { onBytes(TtyKeys.CTRL_C); mods.clear() }
        KeyCap("^D") { onBytes(TtyKeys.CTRL_D); mods.clear() }
        KeyCap("paste") { onPaste() }
        KeyCap("📎") { onPickImage() }
    }
}

@Composable
private fun KeyCap(label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) accent else bgSecondary)
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) bg else textPrimary,
            fontFamily = piMono,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Invisible keystroke capture. A [BasicTextField] holds a fixed "sacrificial"
 * baseline buffer with the cursor pinned at its end; on every change we diff
 * against the baseline to recover what the IME did — appended chars are typed
 * input, a shorter value is a backspace — then reset to the baseline so the
 * field never grows and no composing region lingers. This is the standard way to
 * get reliable per-character input from Android soft keyboards (which talk via
 * InputConnection, not KeyEvents). Typed text is rendered transparent; the real
 * cursor and echo live in pi's prompt inside the mirror.
 */
@Composable
private fun TtyInputCapture(
    mods: StickyMods,
    focusRequester: FocusRequester,
    onBytes: (String) -> Unit,
) {
    val base = "        " // 8 spaces — room for a few rapid backspaces per event
    var field by remember { mutableStateOf(TextFieldValue(base, TextRange(base.length))) }

    // Invisible (1.dp, fully transparent): this field exists only to hold the IME
    // connection. There is no on-screen input box — the terminal itself is the
    // input. Tapping the mirror focuses this field (MirrorSurface.onRequestKeyboard),
    // and pi echoes typed characters into its own prompt inside the mirror.
    BasicTextField(
        value = field,
        onValueChange = { nv ->
            val t = nv.text
            when {
                // Grew from the baseline → the suffix is what was typed.
                t.length > base.length && t.startsWith(base) -> {
                    onBytes(TtyKeys.encode(t.substring(base.length), mods.ctrl, mods.alt))
                    mods.clear()
                }
                // Shorter → one or more backspaces ate into the baseline.
                t.length < base.length -> {
                    onBytes(TtyKeys.DEL.repeat(base.length - t.length))
                }
                // Grew but the IME rewrote the buffer (composing/replace);
                // best-effort: treat the extra tail as typed input.
                t.length > base.length -> {
                    onBytes(TtyKeys.encode(t.takeLast(t.length - base.length), mods.ctrl, mods.alt))
                    mods.clear()
                }
            }
            // Always snap back to the baseline with the cursor at the end.
            field = TextFieldValue(base, TextRange(base.length))
        },
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .focusRequester(focusRequester),
        textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
        cursorBrush = SolidColor(Color.Transparent),
        // No autocorrect/suggestions/capitalization: composing text would
        // corrupt the diff. ImeAction.None keeps Enter as a newline ('\n'),
        // which encode() turns into CR.
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.None,
        ),
        singleLine = false,
        maxLines = 1,
    )
}
