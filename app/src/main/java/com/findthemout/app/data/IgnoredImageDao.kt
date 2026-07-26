package com.findthemout.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface IgnoredImageDao {
    @Query("SELECT * FROM ignored_image_entries ORDER BY addedAt DESC")
    suspend fun getAll(): List<IgnoredImageEntry>

    @Query("SELECT path FROM ignored_image_entries")
    suspend fun getAllPaths(): List<String>

    @Query("SELECT COUNT(*) FROM ignored_image_entries")
    suspend fun getCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: IgnoredImageEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<IgnoredImageEntry>)

    @Query("DELETE FROM ignored_image_entries")
    suspend fun deleteAll()
}
