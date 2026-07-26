package com.findthemout.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_deletion_entries")
data class PendingDeletionEntry(
    @PrimaryKey
    val path: String,
    val name: String,
    val size: Long,
    val addedAt: Long = System.currentTimeMillis(),
)
