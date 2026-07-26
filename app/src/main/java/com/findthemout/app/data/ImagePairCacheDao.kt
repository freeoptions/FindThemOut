package com.findthemout.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ImagePairCacheDao {
    @Query("SELECT * FROM image_pair_cache")
    suspend fun getAll(): List<ImagePairCache>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ImagePairCache)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<ImagePairCache>)

    @Query("DELETE FROM image_pair_cache WHERE leftPath = :path OR rightPath = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM image_pair_cache")
    suspend fun deleteAll()
}
