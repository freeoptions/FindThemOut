package com.findthemout.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ImageFingerprintCacheDao {
    @Query("SELECT * FROM image_fingerprint_cache")
    suspend fun getAll(): List<ImageFingerprintCache>

    @Query("SELECT COUNT(*) FROM image_fingerprint_cache")
    suspend fun getCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ImageFingerprintCache)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<ImageFingerprintCache>)

    @Query("DELETE FROM image_fingerprint_cache WHERE path = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM image_fingerprint_cache WHERE path IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    @Query("DELETE FROM image_fingerprint_cache")
    suspend fun deleteAll()
}
