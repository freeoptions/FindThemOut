package com.findthemout.app.data

import androidx.room.Entity

@Entity(
    tableName = "image_pair_cache",
    primaryKeys = ["leftPath", "rightPath"],
)
data class ImagePairCache(
    val leftPath: String,
    val rightPath: String,
    val leftSize: Long,
    val rightSize: Long,
    val leftModifiedAt: Long,
    val rightModifiedAt: Long,
    val score: Int,
    val isMatch: Boolean,
    val updatedAt: Long = System.currentTimeMillis(),
)
