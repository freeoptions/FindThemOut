package com.findthemout.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "image_fingerprint_cache")
data class ImageFingerprintCache(
    @PrimaryKey
    val path: String,
    val name: String,
    val size: Long,
    val modifiedAt: Long,
    val width: Int,
    val height: Int,
    val pHash: Long,
    val aHash: Long,
    val dHash: Long,
    val updatedAt: Long = System.currentTimeMillis(),
)
