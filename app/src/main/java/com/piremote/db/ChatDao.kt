package com.piremote.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE url = :serverUrl ORDER BY seq ASC")
    fun getByUrl(serverUrl: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE url = :serverUrl ORDER BY seq ASC")
    suspend fun getAllByServerUrl(serverUrl: String): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(msg: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<ChatMessageEntity>)

    @Delete
    suspend fun delete(msg: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE url = :serverUrl")
    suspend fun clearByServerUrl(serverUrl: String)

    @Query("SELECT COUNT(*) FROM chat_messages")
    suspend fun totalMessages(): Int
}
