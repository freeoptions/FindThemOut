package com.findthemout.app.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.findthemout.app.data.ImageFingerprintCache
import com.findthemout.app.data.ImageFingerprintCacheDao
import com.findthemout.app.data.ImagePairCache
import com.findthemout.app.data.ImagePairCacheDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos

private const val FINGERPRINT_WORKER_BATCH_SIZE = 12
private const val HASH_SIZE = 8
private const val DHASH_WIDTH = 9
private const val PHASH_SIZE = 16
private const val PHASH_LOW_FREQUENCY_SIZE = 8
private const val PHASH_DISTANCE_THRESHOLD = 10
private const val COMBINED_DISTANCE_THRESHOLD = 24
private const val MAX_CANDIDATES_PER_IMAGE = 24
private const val MIN_GROUP_SIZE = 2

private val supportedImageExtensions = setOf(
    "jpg",
    "jpeg",
    "png",
    "webp",
    "bmp",
    "gif",
    "heic",
    "heif",
)

object SimilarImageScanner {
    suspend fun scanSelectedFolders(
        folderPaths: List<String>,
        ignoredPaths: Set<String>,
        cacheDao: ImageFingerprintCacheDao,
        pairCacheDao: ImagePairCacheDao,
        onProgress: (ScanProgressSnapshot) -> Unit,
    ): ScanOutput {
        val startedAt = System.currentTimeMillis()
        val visitedDirectories = IntCounter()
        val visitedFiles = IntCounter()

        val imageFiles = withContext(Dispatchers.IO) {
            collectImageFiles(folderPaths, visitedDirectories, visitedFiles, onProgress)
        }.filterNot { ignoredPaths.contains(it.absolutePath) }

        val fingerprintLoadResult = loadFingerprints(imageFiles, cacheDao, onProgress)
        val groups = withContext(Dispatchers.Default) {
            groupFingerprints(
                fingerprints = fingerprintLoadResult.fingerprints,
                changedPaths = fingerprintLoadResult.changedPaths,
                pairCacheDao = pairCacheDao,
                onProgress = onProgress,
            )
        }

        onProgress(
            ScanProgressSnapshot(
                stage = "done",
                message = "扫描完成",
                current = 1,
                total = 1,
            ),
        )

        return ScanOutput(
            groups = groups,
            summary = ScanSummary(
                scannedFolders = folderPaths.size,
                visitedDirectories = visitedDirectories.value,
                visitedFiles = visitedFiles.value,
                candidateImages = imageFiles.size,
                groupedImages = groups.sumOf { it.items.size },
                groupCount = groups.size,
                elapsedMillis = System.currentTimeMillis() - startedAt,
            ),
        )
    }

    private suspend fun loadFingerprints(
        imageFiles: List<File>,
        cacheDao: ImageFingerprintCacheDao,
        onProgress: (ScanProgressSnapshot) -> Unit,
    ): FingerprintLoadResult = withContext(Dispatchers.IO) {
        val cachedMap = cacheDao.getAll().associateBy { it.path }
        val readyFingerprints = arrayOfNulls<ImageFingerprint>(imageFiles.size)
        val rebuildTargets = mutableListOf<Pair<Int, File>>()
        val changedPaths = linkedSetOf<String>()
        var processedCount = 0

        imageFiles.forEachIndexed { index, file ->
            val cached = cachedMap[file.absolutePath]
            if (
                cached != null &&
                cached.size == file.length() &&
                cached.modifiedAt == file.lastModified()
            ) {
                readyFingerprints[index] = cached.toFingerprint()
                processedCount += 1
                onProgress(
                    ScanProgressSnapshot(
                        stage = "fingerprint",
                        message = "正在复用缓存指纹 ${processedCount}/${imageFiles.size}",
                        current = processedCount,
                        total = imageFiles.size,
                    ),
                )
            } else {
                rebuildTargets += index to file
                changedPaths += file.absolutePath
            }
        }

        rebuildTargets.chunked(FINGERPRINT_WORKER_BATCH_SIZE).forEach { chunk ->
            val rebuiltEntries = withContext(Dispatchers.Default) {
                chunk.map { (index, file) ->
                    Triple(index, file, buildFingerprint(file))
                }
            }

            val upsertEntries = mutableListOf<ImageFingerprintCache>()
            rebuiltEntries.forEach { (index, file, rebuilt) ->
                if (rebuilt == null) {
                    cacheDao.deleteByPath(file.absolutePath)
                } else {
                    readyFingerprints[index] = rebuilt
                    upsertEntries += rebuilt.toCacheEntry()
                }
                processedCount += 1
                onProgress(
                    ScanProgressSnapshot(
                        stage = "fingerprint",
                        message = "正在生成新指纹 ${processedCount}/${imageFiles.size}",
                        current = processedCount,
                        total = imageFiles.size,
                    ),
                )
            }

            if (upsertEntries.isNotEmpty()) {
                cacheDao.upsertAll(upsertEntries)
            }
        }

        FingerprintLoadResult(
            fingerprints = readyFingerprints.filterNotNull(),
            changedPaths = changedPaths,
        )
    }

    private fun collectImageFiles(
        folderPaths: List<String>,
        visitedDirectories: IntCounter,
        visitedFiles: IntCounter,
        onProgress: (ScanProgressSnapshot) -> Unit,
    ): List<File> {
        val results = linkedMapOf<String, File>()
        folderPaths.forEachIndexed { index, rawPath ->
            val root = File(rawPath)
            onProgress(
                ScanProgressSnapshot(
                    stage = "collect",
                    message = "正在读取文件夹 ${index + 1}/${folderPaths.size}: ${root.absolutePath}",
                    current = index + 1,
                    total = folderPaths.size,
                ),
            )
            if (root.exists() && root.isDirectory) {
                collectFilesRecursively(root, results, visitedDirectories, visitedFiles)
            }
        }
        return results.values.toList()
    }

    private fun collectFilesRecursively(
        directory: File,
        results: MutableMap<String, File>,
        visitedDirectories: IntCounter,
        visitedFiles: IntCounter,
    ) {
        visitedDirectories.increment()
        val children = try {
            directory.listFiles()
        } catch (_: SecurityException) {
            null
        } ?: return

        children.forEach { child ->
            if (child.isDirectory) {
                if (!child.name.startsWith(".")) {
                    collectFilesRecursively(child, results, visitedDirectories, visitedFiles)
                }
            } else if (child.isFile) {
                visitedFiles.increment()
                if (
                    child.length() > 0L &&
                    !child.name.startsWith(".") &&
                    !child.name.startsWith(".trashed-", ignoreCase = true) &&
                    child.extension.lowercase(Locale.ROOT) in supportedImageExtensions
                ) {
                    results.putIfAbsent(child.absolutePath, child)
                }
            }
        }
    }

    private suspend fun groupFingerprints(
        fingerprints: List<ImageFingerprint>,
        changedPaths: Set<String>,
        pairCacheDao: ImagePairCacheDao,
        onProgress: (ScanProgressSnapshot) -> Unit,
    ): List<SimilarImageGroup> {
        if (fingerprints.size < 2) {
            return emptyList()
        }

        val pairCacheMap = withContext(Dispatchers.IO) {
            pairCacheDao.getAll().associateBy { orderedPathPair(it.leftPath, it.rightPath) }
        }

        val pHashTree = BkTree()
        val dHashTree = BkTree()
        val aHashTree = BkTree()
        fingerprints.forEachIndexed { index, fingerprint ->
            pHashTree.add(index, fingerprint.pHash)
            dHashTree.add(index, fingerprint.dHash)
            aHashTree.add(index, fingerprint.aHash)
        }

        val unionFind = UnionFind(fingerprints.size)
        val matchedPairs = mutableMapOf<Pair<Int, Int>, Int>()
        val updatedPairEntries = mutableListOf<ImagePairCache>()
        val fingerprintsByPath = fingerprints.associateBy { it.path }
        val fingerprintIndexesByPath = fingerprints.mapIndexed { index, fingerprint -> fingerprint.path to index }.toMap()

        pairCacheMap.values.forEach { entry ->
            val left = fingerprintsByPath[entry.leftPath] ?: return@forEach
            val right = fingerprintsByPath[entry.rightPath] ?: return@forEach
            if (!isPairCacheFresh(entry, left, right)) {
                return@forEach
            }
            if (changedPaths.contains(left.path) || changedPaths.contains(right.path)) {
                return@forEach
            }

            val leftIndex = fingerprintIndexesByPath[left.path] ?: return@forEach
            val rightIndex = fingerprintIndexesByPath[right.path] ?: return@forEach
            if (leftIndex < 0 || rightIndex < 0) {
                return@forEach
            }

            if (entry.isMatch) {
                val pair = orderedPair(leftIndex, rightIndex)
                matchedPairs[pair] = entry.score
                unionFind.union(leftIndex, rightIndex)
            }
        }

        val workIndexes = if (changedPaths.isEmpty()) {
            emptyList()
        } else {
            fingerprints.indices.filter { changedPaths.contains(fingerprints[it].path) }
        }

        workIndexes.forEachIndexed { progressIndex, index ->
            val fingerprint = fingerprints[index]
            onProgress(
                ScanProgressSnapshot(
                    stage = "match",
                    message = "正在匹配候选图片 ${progressIndex + 1}/${workIndexes.size}",
                    current = progressIndex + 1,
                    total = workIndexes.size,
                ),
            )

            val rawCandidates = linkedMapOf<Int, Triple<Int, Int, Int>>()
            pHashTree.query(fingerprint.pHash, PHASH_DISTANCE_THRESHOLD).forEach { (candidateIndex, distance) ->
                val existing = rawCandidates[candidateIndex]
                rawCandidates[candidateIndex] = Triple(distance, existing?.second ?: Int.MAX_VALUE, existing?.third ?: Int.MAX_VALUE)
            }
            dHashTree.query(fingerprint.dHash, PHASH_DISTANCE_THRESHOLD).forEach { (candidateIndex, distance) ->
                val existing = rawCandidates[candidateIndex]
                rawCandidates[candidateIndex] = Triple(existing?.first ?: Int.MAX_VALUE, distance, existing?.third ?: Int.MAX_VALUE)
            }
            aHashTree.query(fingerprint.aHash, PHASH_DISTANCE_THRESHOLD).forEach { (candidateIndex, distance) ->
                val existing = rawCandidates[candidateIndex]
                rawCandidates[candidateIndex] = Triple(existing?.first ?: Int.MAX_VALUE, existing?.second ?: Int.MAX_VALUE, distance)
            }

            val candidates = rawCandidates
                .mapNotNull { (candidateIndex, distances) ->
                    if (candidateIndex == index) {
                        return@mapNotNull null
                    }
                    val other = fingerprints[candidateIndex]
                    if (!isAspectRatioClose(fingerprint, other)) {
                        return@mapNotNull null
                    }

                    val cacheKey = orderedPathPair(fingerprint.path, other.path)
                    val cachedPair = pairCacheMap[cacheKey]
                    if (
                        cachedPair != null &&
                        isPairCacheFresh(cachedPair, fingerprint, other) &&
                        !changedPaths.contains(other.path)
                    ) {
                        val pair = orderedPair(index, candidateIndex)
                        if (cachedPair.isMatch) {
                            matchedPairs[pair] = cachedPair.score
                            unionFind.union(index, candidateIndex)
                        }
                        return@mapNotNull null
                    }

                    val combinedDistance = combinedDistance(fingerprint, other)
                    if (combinedDistance > COMBINED_DISTANCE_THRESHOLD) {
                        val cacheEntry = buildPairCacheEntry(fingerprint, other, score = 0, isMatch = false)
                        updatedPairEntries += cacheEntry
                        return@mapNotNull null
                    }

                    CandidateMatch(
                        index = candidateIndex,
                        rawDistance = minOf(distances.first, distances.second, distances.third),
                        combinedDistance = combinedDistance,
                    )
                }
                .sortedWith(compareBy<CandidateMatch> { it.combinedDistance }.thenBy { it.rawDistance })
                .take(MAX_CANDIDATES_PER_IMAGE)

            candidates.forEach { candidate ->
                val other = fingerprints[candidate.index]
                val pair = orderedPair(index, candidate.index)
                val score = buildScore(candidate.combinedDistance, fingerprint, other)
                val isMatch = candidate.combinedDistance <= COMBINED_DISTANCE_THRESHOLD
                updatedPairEntries += buildPairCacheEntry(fingerprint, other, score, isMatch)
                if (isMatch) {
                    matchedPairs[pair] = score
                    unionFind.union(index, candidate.index)
                }
            }
        }

        if (updatedPairEntries.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                pairCacheDao.upsertAll(updatedPairEntries.distinctBy { orderedPathPair(it.leftPath, it.rightPath) })
            }
        }

        val grouped = linkedMapOf<Int, MutableList<Int>>()
        fingerprints.indices.forEach { index ->
            val root = unionFind.find(index)
            grouped.getOrPut(root) { mutableListOf() }.add(index)
        }

        return grouped.values
            .filter { it.size >= MIN_GROUP_SIZE }
            .mapIndexed { groupIndex, memberIndexes ->
                val sortedMembers = memberIndexes
                    .map { memberIndex -> memberIndex to fingerprints[memberIndex] }
                    .sortedWith(
                        compareByDescending<Pair<Int, ImageFingerprint>> { it.second.size }
                            .thenBy { it.second.name.length }
                            .thenByDescending { it.second.width * it.second.height }
                            .thenBy { it.second.path.lowercase(Locale.getDefault()) },
                    )
                val representativeIndex = sortedMembers.first().first
                val items = sortedMembers.mapIndexed { itemIndex, (originalIndex, fingerprint) ->
                    SimilarImageItem(
                        path = fingerprint.path,
                        name = fingerprint.name,
                        width = fingerprint.width,
                        height = fingerprint.height,
                        size = fingerprint.size,
                        modifiedAt = fingerprint.modifiedAt,
                        score = if (itemIndex == 0) 99 else matchedPairs[orderedPair(representativeIndex, originalIndex)] ?: 72,
                    )
                }
                SimilarImageGroup(
                    id = "group-${groupIndex + 1}",
                    items = items,
                )
            }
            .sortedWith(
                compareByDescending<SimilarImageGroup> { it.items.size }
                    .thenByDescending { it.totalSize },
            )
    }

    private fun buildFingerprint(file: File): ImageFingerprint? {
        val decodedBitmap = decodeSampledBitmap(file) ?: return null
        val bitmap = decodedBitmap.bitmap

        val orientedBitmap = if (decodedBitmap.exifOrientationApplied) {
            bitmap
        } else {
            applyExifOrientation(file, bitmap)
        }
        val outputWidth = orientedBitmap.width
        val outputHeight = orientedBitmap.height
        val pHashBitmap = Bitmap.createScaledBitmap(orientedBitmap, PHASH_SIZE, PHASH_SIZE, true)
        val aHashBitmap = Bitmap.createScaledBitmap(orientedBitmap, HASH_SIZE, HASH_SIZE, true)
        val dHashBitmap = Bitmap.createScaledBitmap(orientedBitmap, DHASH_WIDTH, HASH_SIZE, true)

        val fingerprint = ImageFingerprint(
            path = file.absolutePath,
            name = file.name,
            size = file.length(),
            width = outputWidth,
            height = outputHeight,
            modifiedAt = file.lastModified(),
            pHash = computePerceptualHash(pHashBitmap),
            aHash = computeAverageHash(aHashBitmap),
            dHash = computeDifferenceHash(dHashBitmap),
        )

        if (orientedBitmap !== bitmap) {
            bitmap.recycle()
        }
        pHashBitmap.recycle()
        aHashBitmap.recycle()
        dHashBitmap.recycle()
        orientedBitmap.recycle()

        return fingerprint
    }

    private fun decodeSampledBitmap(file: File): DecodedBitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && file.isHeifFile()) {
            decodeWithImageDecoder(file)?.let { bitmap ->
                return DecodedBitmap(bitmap = bitmap, exifOrientationApplied = true)
            }
        }

        return decodeWithBitmapFactory(file)?.let { bitmap ->
            DecodedBitmap(bitmap = bitmap, exifOrientationApplied = false)
        }
    }

    /**
     * ImageDecoder is the platform decoder for HEIF/HEIC on Android 9+ and
     * respects the file's EXIF orientation while decoding.
     */
    private fun decodeWithImageDecoder(file: File): Bitmap? {
        return try {
            val source = ImageDecoder.createSource(file)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.setTargetSampleSize(
                    calculateInSampleSize(
                        info.size.width,
                        info.size.height,
                        PHASH_SIZE,
                        PHASH_SIZE,
                    ),
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeWithBitmapFactory(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }

        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, PHASH_SIZE, PHASH_SIZE)
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    }

    private fun File.isHeifFile(): Boolean {
        val normalizedExtension = extension.lowercase(Locale.ROOT)
        return normalizedExtension == "heic" || normalizedExtension == "heif"
    }

    private data class DecodedBitmap(
        val bitmap: Bitmap,
        val exifOrientationApplied: Boolean,
    )

    private fun applyExifOrientation(file: File, bitmap: Bitmap): Bitmap {
        val exif = try {
            ExifInterface(file.absolutePath)
        } catch (_: Exception) {
            return bitmap
        }

        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun computeAverageHash(bitmap: Bitmap): Long {
        val values = bitmap.toGrayValues()
        val average = values.average()
        var hash = 0L
        values.forEachIndexed { index, value ->
            if (value >= average) {
                hash = hash or (1L shl index)
            }
        }
        return hash
    }

    private fun computeDifferenceHash(bitmap: Bitmap): Long {
        val values = bitmap.toGrayValues()
        var hash = 0L
        for (row in 0 until HASH_SIZE) {
            for (column in 0 until HASH_SIZE) {
                val left = values[row * DHASH_WIDTH + column]
                val right = values[row * DHASH_WIDTH + column + 1]
                val bitIndex = row * HASH_SIZE + column
                if (left > right) {
                    hash = hash or (1L shl bitIndex)
                }
            }
        }
        return hash
    }

    private fun computePerceptualHash(bitmap: Bitmap): Long {
        val values = bitmap.toGrayValues()
        val dct = Array(PHASH_SIZE) { DoubleArray(PHASH_SIZE) }
        for (u in 0 until PHASH_SIZE) {
            for (v in 0 until PHASH_SIZE) {
                var sum = 0.0
                for (x in 0 until PHASH_SIZE) {
                    for (y in 0 until PHASH_SIZE) {
                        val pixel = values[x * PHASH_SIZE + y]
                        sum += pixel * cosineTable[x][u] * cosineTable[y][v]
                    }
                }
                val cu = if (u == 0) 1.0 / Math.sqrt(2.0) else 1.0
                val cv = if (v == 0) 1.0 / Math.sqrt(2.0) else 1.0
                dct[u][v] = 0.25 * cu * cv * sum
            }
        }

        val lowFrequencies = mutableListOf<Double>()
        for (x in 0 until PHASH_LOW_FREQUENCY_SIZE) {
            for (y in 0 until PHASH_LOW_FREQUENCY_SIZE) {
                if (x != 0 || y != 0) {
                    lowFrequencies += dct[x][y]
                }
            }
        }

        val median = lowFrequencies.sorted()[lowFrequencies.size / 2]
        var hash = 0L
        lowFrequencies.forEachIndexed { index, value ->
            if (value >= median) {
                hash = hash or (1L shl index)
            }
        }
        return hash
    }

    private fun Bitmap.toGrayValues(): DoubleArray {
        val width = this.width
        val height = this.height
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return DoubleArray(pixels.size) { index ->
            val pixel = pixels[index]
            val red = pixel shr 16 and 0xFF
            val green = pixel shr 8 and 0xFF
            val blue = pixel and 0xFF
            red * 0.299 + green * 0.587 + blue * 0.114
        }
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int,
    ): Int {
        var sampleSize = 1
        var halfWidth = width / 2
        var halfHeight = height / 2
        while ((halfWidth / sampleSize) >= reqWidth && (halfHeight / sampleSize) >= reqHeight) {
            sampleSize *= 2
            halfWidth = maxOf(1, halfWidth)
            halfHeight = maxOf(1, halfHeight)
        }
        return maxOf(1, sampleSize)
    }

    private fun isAspectRatioClose(left: ImageFingerprint, right: ImageFingerprint): Boolean {
        if (left.height <= 0 || right.height <= 0) {
            return false
        }
        return abs(left.aspectRatio - right.aspectRatio) <= 0.05
    }

    private fun combinedDistance(left: ImageFingerprint, right: ImageFingerprint): Int {
        return bitDistance(left.pHash, right.pHash) +
            bitDistance(left.aHash, right.aHash) +
            bitDistance(left.dHash, right.dHash)
    }

    private fun buildScore(
        combinedDistance: Int,
        left: ImageFingerprint,
        right: ImageFingerprint,
    ): Int {
        val resolutionBonus = if (left.width != right.width || left.height != right.height) 4 else 0
        return (98 - combinedDistance * 2 + resolutionBonus).coerceIn(45, 99)
    }

    private fun bitDistance(left: Long, right: Long): Int {
        return java.lang.Long.bitCount(left xor right)
    }
}

private data class FingerprintLoadResult(
    val fingerprints: List<ImageFingerprint>,
    val changedPaths: Set<String>,
)

private class IntCounter {
    var value: Int = 0
        private set

    fun increment() {
        value += 1
    }
}

private data class ImageFingerprint(
    val path: String,
    val name: String,
    val size: Long,
    val width: Int,
    val height: Int,
    val modifiedAt: Long,
    val pHash: Long,
    val aHash: Long,
    val dHash: Long,
) {
    val aspectRatio: Double
        get() = width.toDouble() / height.coerceAtLeast(1).toDouble()
}

private class UnionFind(size: Int) {
    private val parent = IntArray(size) { it }
    private val rank = IntArray(size)

    fun find(value: Int): Int {
        if (parent[value] != value) {
            parent[value] = find(parent[value])
        }
        return parent[value]
    }

    fun union(left: Int, right: Int) {
        val leftRoot = find(left)
        val rightRoot = find(right)
        if (leftRoot == rightRoot) {
            return
        }
        when {
            rank[leftRoot] < rank[rightRoot] -> parent[leftRoot] = rightRoot
            rank[leftRoot] > rank[rightRoot] -> parent[rightRoot] = leftRoot
            else -> {
                parent[rightRoot] = leftRoot
                rank[leftRoot] += 1
            }
        }
    }
}

private class BkTree {
    private var root: BkTreeNode? = null

    fun add(index: Int, value: Long) {
        if (root == null) {
            root = BkTreeNode(index, value)
            return
        }
        var node = root!!
        while (true) {
            val distance = java.lang.Long.bitCount(value xor node.value)
            val next = node.children[distance]
            if (next == null) {
                node.children[distance] = BkTreeNode(index, value)
                return
            }
            node = next
        }
    }

    fun query(value: Long, maxDistance: Int): List<Pair<Int, Int>> {
        val currentRoot = root ?: return emptyList()
        val results = mutableListOf<Pair<Int, Int>>()
        val stack = ArrayDeque<BkTreeNode>()
        stack.add(currentRoot)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val distance = java.lang.Long.bitCount(value xor node.value)
            if (distance <= maxDistance) {
                results += node.index to distance
            }
            val minDistance = distance - maxDistance
            val maxAllowedDistance = distance + maxDistance
            node.children.forEach { (childDistance, child) ->
                if (childDistance in minDistance..maxAllowedDistance) {
                    stack.add(child)
                }
            }
        }
        return results
    }
}

private data class BkTreeNode(
    val index: Int,
    val value: Long,
    val children: MutableMap<Int, BkTreeNode> = mutableMapOf(),
)

private data class CandidateMatch(
    val index: Int,
    val rawDistance: Int,
    val combinedDistance: Int,
)

private fun ImageFingerprintCache.toFingerprint(): ImageFingerprint {
    return ImageFingerprint(
        path = path,
        name = name,
        size = size,
        width = width,
        height = height,
        modifiedAt = modifiedAt,
        pHash = pHash,
        aHash = aHash,
        dHash = dHash,
    )
}

private fun ImageFingerprint.toCacheEntry(): ImageFingerprintCache {
    return ImageFingerprintCache(
        path = path,
        name = name,
        size = size,
        modifiedAt = modifiedAt,
        width = width,
        height = height,
        pHash = pHash,
        aHash = aHash,
        dHash = dHash,
    )
}

private fun buildPairCacheEntry(
    left: ImageFingerprint,
    right: ImageFingerprint,
    score: Int,
    isMatch: Boolean,
): ImagePairCache {
    val (leftPath, rightPath) = orderedPathPair(left.path, right.path)
    val leftFingerprint = if (leftPath == left.path) left else right
    val rightFingerprint = if (rightPath == right.path) right else left
    return ImagePairCache(
        leftPath = leftPath,
        rightPath = rightPath,
        leftSize = leftFingerprint.size,
        rightSize = rightFingerprint.size,
        leftModifiedAt = leftFingerprint.modifiedAt,
        rightModifiedAt = rightFingerprint.modifiedAt,
        score = score,
        isMatch = isMatch,
    )
}

private fun isPairCacheFresh(
    entry: ImagePairCache,
    left: ImageFingerprint,
    right: ImageFingerprint,
): Boolean {
    val leftFingerprint = if (entry.leftPath == left.path) left else right
    val rightFingerprint = if (entry.rightPath == right.path) right else left
    return entry.leftSize == leftFingerprint.size &&
        entry.rightSize == rightFingerprint.size &&
        entry.leftModifiedAt == leftFingerprint.modifiedAt &&
        entry.rightModifiedAt == rightFingerprint.modifiedAt
}

private fun orderedPair(left: Int, right: Int): Pair<Int, Int> {
    return if (left <= right) left to right else right to left
}

private fun orderedPathPair(left: String, right: String): Pair<String, String> {
    return if (left <= right) left to right else right to left
}

private val cosineTable = Array(PHASH_SIZE) { x ->
    DoubleArray(PHASH_SIZE) { u ->
        cos(((2 * x + 1) * u * Math.PI) / (2.0 * PHASH_SIZE))
    }
}
