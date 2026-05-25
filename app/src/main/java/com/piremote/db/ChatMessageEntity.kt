package com.piremote.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Unique (url, msgId) so @Insert(REPLACE) upserts in place — a message keeps
// its msgId as it transitions Streaming → Assistant/ToolResult, so the final
// content overwrites the earlier row instead of being dropped or duplicated.
@Entity(
    tableName = "chat_messages",
    indices = [Index(value = ["url", "msgId"], unique = true)]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val msgId: String,
    val url: String,
    val seq: Int,
    val type: String,          // User, Assistant, ToolResult, Thinking
    val toolCallId: String = "",
    val toolName: String = "",
    val content: String = "",
    val isError: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
