package com.findthemout.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PinnedFolderDao {
    @Query("SELECT * FROM pinned_folders ORDER BY position ASC, createdAt ASC")
    fun observeFolders(): Flow<List<PinnedFolder>>

    @Query("SELECT * FROM pinned_folders ORDER BY position ASC, createdAt ASC")
    suspend fun getFolders(): List<PinnedFolder>

    @Query("SELECT * FROM pinned_folders WHERE path = :path LIMIT 1")
    suspend fun getFolderByPath(path: String): PinnedFolder?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolder(folder: PinnedFolder)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolders(folders: List<PinnedFolder>)

    @Query("DELETE FROM pinned_folders WHERE path = :path")
    suspend fun deleteFolder(path: String)

    @Query("UPDATE pinned_folders SET enabled = :enabled WHERE path = :path")
    suspend fun setFolderEnabled(path: String, enabled: Boolean)

    @Query("UPDATE pinned_folders SET enabled = :enabled")
    suspend fun setAllEnabled(enabled: Boolean)

    @Query("DELETE FROM pinned_folders")
    suspend fun deleteAll()
}
