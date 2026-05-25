package com.piremote.db

import androidx.room.*

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE url = :serverUrl ORDER BY seq ASC")
    suspend fun getAllByServerUrl(serverUrl: String): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(msg: ChatMessageEntity)
}
