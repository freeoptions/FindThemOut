package com.findthemout.app.scan

data class ScanProgressSnapshot(
    val stage: String,
    val message: String,
    val current: Int = 0,
    val total: Int = 0,
)

data class SimilarImageItem(
    val path: String,
    val name: String,
    val width: Int,
    val height: Int,
    val size: Long,
    val modifiedAt: Long,
    val score: Int,
) {
    val resolutionText: String
        get() = "${width} x ${height}"
}

data class SimilarImageGroup(
    val id: String,
    val items: List<SimilarImageItem>,
) {
    val representative: SimilarImageItem
        get() = items.first()

    val totalSize: Long
        get() = items.sumOf { it.size }
}

data class ScanSummary(
    val scannedFolders: Int,
    val visitedDirectories: Int,
    val visitedFiles: Int,
    val candidateImages: Int,
    val groupedImages: Int,
    val groupCount: Int,
    val elapsedMillis: Long,
)

data class ScanOutput(
    val groups: List<SimilarImageGroup>,
    val summary: ScanSummary,
)
