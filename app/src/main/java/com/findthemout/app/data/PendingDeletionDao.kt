package com.findthemout.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingDeletionDao {
    @Query("SELECT * FROM pending_deletion_entries ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<PendingDeletionEntry>>

    @Query("SELECT * FROM pending_deletion_entries ORDER BY addedAt DESC")
    suspend fun getAll(): List<PendingDeletionEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: PendingDeletionEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<PendingDeletionEntry>)

    @Query("DELETE FROM pending_deletion_entries WHERE path = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM pending_deletion_entries WHERE path IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    @Query("SELECT COUNT(*) FROM pending_deletion_entries")
    suspend fun getCount(): Int

    @Query("DELETE FROM pending_deletion_entries")
    suspend fun deleteAll()
}
