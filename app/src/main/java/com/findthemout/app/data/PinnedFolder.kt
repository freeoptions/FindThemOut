package com.findthemout.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pinned_folders")
data class PinnedFolder(
    @PrimaryKey
    val path: String,
    val name: String,
    val enabled: Boolean = true,
    val position: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
