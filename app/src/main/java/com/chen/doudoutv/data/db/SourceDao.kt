package com.chen.doudoutv.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.chen.doudoutv.data.model.SourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {

    @Query("SELECT * FROM sources ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources ORDER BY createdAt ASC")
    suspend fun getAll(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE enabled = 1 ORDER BY createdAt ASC")
    suspend fun getEnabled(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun get(id: Long): SourceEntity?

    @Insert
    suspend fun insert(source: SourceEntity): Long

    @Update
    suspend fun update(source: SourceEntity)

    @Query("UPDATE sources SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Delete
    suspend fun delete(source: SourceEntity)
}
