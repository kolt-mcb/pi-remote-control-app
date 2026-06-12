package com.piremote.screens

import com.piremote.ChatMessage
import com.piremote.MessageToolType
import com.piremote.theme.PiRemoteTheme
import com.piremote.tty.MessageNormalizer
import com.piremote.tty.TtyBlock
import com.piremote.tty.TtyStreamParser
import com.piremote.tty.wrapStyledLines

/** What tapping a scrollback entry does. */
sealed interface TapAction {
    data class ToggleExpand(val msgId: String) : TapAction
    data class OpenImage(val base64: String, val mimeType: String) : TapAction
}

/** One LazyColumn item: a single render block of a message, with tap metadata. */
data class ScrollbackEntry(
    val key: String,
    val msgId: String,
    val block: TtyBlock,
    val tap: TapAction? = null,
    val copyText: String? = null,
)

/**
 * Message → entries renderer with an LRU cache, so only changed messages
 * re-run normalize+parse. The streaming tail message misses on every delta
 * (its contentHash changes); everything else is a cache hit.
 */
class ScrollbackRenderer {

    /** A Text block longer than this is split so no single LazyColumn item gets huge. */
    private val maxBlockLines = 200

    private data class Key(
        val msgId: String,
        val contentHash: Int,
        val cols: Int,
        val expanded: Boolean,
        val theme: PiRemoteTheme,
    )

    private val cache = object : LinkedHashMap<Key, List<ScrollbackEntry>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, List<ScrollbackEntry>>) =
            size > 512
    }

    fun entries(msg: ChatMessage, cols: Int, expanded: Boolean, theme: PiRemoteTheme): List<ScrollbackEntry> {
        val key = Key(msg.id, contentHash(msg), cols, expanded, theme)
        cache[key]?.let { return it }
        val blocks = TtyStreamParser.parse(MessageNormalizer.toStream(msg, cols, expanded))
        val entries = toEntries(msg, blocks, cols)
        cache[key] = entries
        return entries
    }

    private fun contentHash(msg: ChatMessage): Int {
        var h = msg.content.hashCode()
        h = 31 * h + msg.toolArgs.hashCode()
        h = 31 * h + (msg.stream?.hashCode() ?: 0)
        h = 31 * h + (msg.streamExpanded?.hashCode() ?: 0)
        h = 31 * h + (msg.ansiLines?.hashCode() ?: 0)
        h = 31 * h + msg.images.fold(0) { acc, img -> 31 * acc + img.data.hashCode() }
        h = 31 * h + msg.isError.hashCode()
        h = 31 * h + msg.toolName.hashCode()
        h = 31 * h + msg.type.ordinal
        return h
    }

    private fun toEntries(msg: ChatMessage, blocks: List<TtyBlock>, cols: Int): List<ScrollbackEntry> {
        // Tool results and thinking blocks expand/collapse on tap and copy on
        // long-press — the whole message body is the tap target, like the old
        // bubble UI.
        val expandable = msg.type == MessageToolType.ToolResult || msg.type == MessageToolType.Thinking
        val copy = msg.content.takeIf { expandable && it.isNotBlank() }

        val out = mutableListOf<ScrollbackEntry>()
        var ordinal = 0
        fun add(block: TtyBlock, tap: TapAction?, copyText: String?) {
            out.add(ScrollbackEntry("${msg.id}#$ordinal", msg.id, block, tap, copyText))
            ordinal++
        }

        blocks.forEach { block ->
            when (block) {
                is TtyBlock.Image ->
                    add(block, TapAction.OpenImage(block.base64, block.mimeType), null)
                is TtyBlock.Text -> {
                    val tap = if (expandable) TapAction.ToggleExpand(msg.id) else null
                    // Hard-wrap to the measured viewport — Text renders with
                    // softWrap=false, so anything wider would clip.
                    wrapStyledLines(block.lines, cols).chunked(maxBlockLines).forEach { chunk ->
                        add(TtyBlock.Text(chunk), tap, copy)
                    }
                }
            }
        }
        return out
    }
}
