package com.findthemout.app.data

data class AppExportBundle(
    val app: String,
    val exportedAt: String,
    val exportFolderPath: String?,
    val pinnedFolders: List<PinnedFolder>,
    val pendingDeletionEntries: List<PendingDeletionEntry>,
    val imageFingerprintCaches: List<ImageFingerprintCache>,
    val imagePairCaches: List<ImagePairCache>,
    val ignoredImageEntries: List<IgnoredImageEntry>,
)
