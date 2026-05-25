package com.piremote.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
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
