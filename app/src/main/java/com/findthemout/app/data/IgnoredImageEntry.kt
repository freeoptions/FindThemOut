package com.findthemout.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ignored_image_entries")
data class IgnoredImageEntry(
    @PrimaryKey
    val path: String,
    val name: String,
    val addedAt: Long = System.currentTimeMillis(),
)
