package com.piremote.db

import androidx.room.*

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE url = :serverUrl ORDER BY seq ASC")
    suspend fun getAllByServerUrl(serverUrl: String): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(msg: ChatMessageEntity)

    // Wipe persisted history for a server when its session is replaced (/new,
    // /resume), so a later reconnect doesn't re-inject the stale conversation.
    @Query("DELETE FROM chat_messages WHERE url = :serverUrl")
    suspend fun deleteByServerUrl(serverUrl: String)
}
