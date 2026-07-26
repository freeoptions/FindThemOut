package com.findthemout.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object AppExportManager {
    private const val PREFS_NAME = "findthemout_prefs"
    private const val KEY_EXPORT_FOLDER_PATH = "export_folder_path"
    private const val APP_NAME = "FindThemOut"

    fun getExportFolderPath(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_EXPORT_FOLDER_PATH, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun setExportFolderPath(context: Context, path: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_EXPORT_FOLDER_PATH, path)
            .apply()
    }

    fun buildExportFileName(): String {
        val now = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH_mm_ss")
        return "${APP_NAME}_exportConfig_${now.format(formatter)}.json"
    }

    fun exportToFile(
        context: Context,
        targetDirectory: String,
        bundle: AppExportBundle,
    ): File {
        val directory = File(targetDirectory)
        directory.mkdirs()
        val file = File(directory, buildExportFileName())
        file.writeText(bundle.toJson().toString(2), Charsets.UTF_8)
        return file
    }

    fun importFromFile(file: File): AppExportBundle {
        val payload = JSONObject(file.readText(Charsets.UTF_8))
        return payload.toBundle()
    }

    private fun AppExportBundle.toJson(): JSONObject {
        return JSONObject().apply {
            put("app", app)
            put("exportedAt", exportedAt)
            put("exportFolderPath", exportFolderPath)
            put("pinnedFolders", JSONArray().apply {
                pinnedFolders.forEach { folder ->
                    put(
                        JSONObject().apply {
                            put("path", folder.path)
                            put("name", folder.name)
                            put("enabled", folder.enabled)
                            put("position", folder.position)
                            put("createdAt", folder.createdAt)
                        },
                    )
                }
            })
            put("pendingDeletionEntries", JSONArray().apply {
                pendingDeletionEntries.forEach { entry ->
                    put(
                        JSONObject().apply {
                            put("path", entry.path)
                            put("name", entry.name)
                            put("size", entry.size)
                            put("addedAt", entry.addedAt)
                        },
                    )
                }
            })
            put("imageFingerprintCaches", JSONArray().apply {
                imageFingerprintCaches.forEach { cache ->
                    put(
                        JSONObject().apply {
                            put("path", cache.path)
                            put("name", cache.name)
                            put("size", cache.size)
                            put("modifiedAt", cache.modifiedAt)
                            put("width", cache.width)
                            put("height", cache.height)
                            put("pHash", cache.pHash)
                            put("aHash", cache.aHash)
                            put("dHash", cache.dHash)
                            put("updatedAt", cache.updatedAt)
                        },
                    )
                }
            })
            put("imagePairCaches", JSONArray().apply {
                imagePairCaches.forEach { cache ->
                    put(
                        JSONObject().apply {
                            put("leftPath", cache.leftPath)
                            put("rightPath", cache.rightPath)
                            put("leftSize", cache.leftSize)
                            put("rightSize", cache.rightSize)
                            put("leftModifiedAt", cache.leftModifiedAt)
                            put("rightModifiedAt", cache.rightModifiedAt)
                            put("score", cache.score)
                            put("isMatch", cache.isMatch)
                            put("updatedAt", cache.updatedAt)
                        },
                    )
                }
            })
            put("ignoredImageEntries", JSONArray().apply {
                ignoredImageEntries.forEach { entry ->
                    put(
                        JSONObject().apply {
                            put("path", entry.path)
                            put("name", entry.name)
                            put("addedAt", entry.addedAt)
                        },
                    )
                }
            })
        }
    }

    private fun JSONObject.toBundle(): AppExportBundle {
        return AppExportBundle(
            app = optString("app", APP_NAME),
            exportedAt = optString("exportedAt"),
            exportFolderPath = optString("exportFolderPath").ifBlank { null },
            pinnedFolders = optJSONArray("pinnedFolders").toPinnedFolders(),
            pendingDeletionEntries = optJSONArray("pendingDeletionEntries").toPendingDeletionEntries(),
            imageFingerprintCaches = optJSONArray("imageFingerprintCaches").toImageFingerprintCaches(),
            imagePairCaches = optJSONArray("imagePairCaches").toImagePairCaches(),
            ignoredImageEntries = optJSONArray("ignoredImageEntries").toIgnoredImageEntries(),
        )
    }

    private fun JSONArray?.toPinnedFolders(): List<PinnedFolder> {
        if (this == null) return emptyList()
        val deduped = linkedMapOf<String, PinnedFolder>()
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val path = item.optString("path").trim()
            if (path.isBlank()) continue
            deduped[path] = PinnedFolder(
                path = path,
                name = item.optString("name").ifBlank { File(path).name },
                enabled = item.optBoolean("enabled", true),
                position = item.optInt("position", deduped.size),
                createdAt = item.optLong("createdAt", System.currentTimeMillis()),
            )
        }
        return deduped.values.sortedBy { it.position }
    }

    private fun JSONArray?.toPendingDeletionEntries(): List<PendingDeletionEntry> {
        if (this == null) return emptyList()
        val deduped = linkedMapOf<String, PendingDeletionEntry>()
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val path = item.optString("path").trim()
            if (path.isBlank()) continue
            deduped[path] = PendingDeletionEntry(
                path = path,
                name = item.optString("name").ifBlank { File(path).name },
                size = item.optLong("size", 0L),
                addedAt = item.optLong("addedAt", System.currentTimeMillis()),
            )
        }
        return deduped.values.toList()
    }

    private fun JSONArray?.toImageFingerprintCaches(): List<ImageFingerprintCache> {
        if (this == null) return emptyList()
        val deduped = linkedMapOf<String, ImageFingerprintCache>()
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val path = item.optString("path").trim()
            if (path.isBlank()) continue
            deduped[path] = ImageFingerprintCache(
                path = path,
                name = item.optString("name").ifBlank { File(path).name },
                size = item.optLong("size", 0L),
                modifiedAt = item.optLong("modifiedAt", 0L),
                width = item.optInt("width", 0),
                height = item.optInt("height", 0),
                pHash = item.optLong("pHash", 0L),
                aHash = item.optLong("aHash", 0L),
                dHash = item.optLong("dHash", 0L),
                updatedAt = item.optLong("updatedAt", System.currentTimeMillis()),
            )
        }
        return deduped.values.toList()
    }

    private fun JSONArray?.toImagePairCaches(): List<ImagePairCache> {
        if (this == null) return emptyList()
        val deduped = linkedMapOf<String, ImagePairCache>()
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val leftPath = item.optString("leftPath").trim()
            val rightPath = item.optString("rightPath").trim()
            if (leftPath.isBlank() || rightPath.isBlank()) continue
            val key = "$leftPath|$rightPath"
            deduped[key] = ImagePairCache(
                leftPath = leftPath,
                rightPath = rightPath,
                leftSize = item.optLong("leftSize", 0L),
                rightSize = item.optLong("rightSize", 0L),
                leftModifiedAt = item.optLong("leftModifiedAt", 0L),
                rightModifiedAt = item.optLong("rightModifiedAt", 0L),
                score = item.optInt("score", 0),
                isMatch = item.optBoolean("isMatch", false),
                updatedAt = item.optLong("updatedAt", System.currentTimeMillis()),
            )
        }
        return deduped.values.toList()
    }

    private fun JSONArray?.toIgnoredImageEntries(): List<IgnoredImageEntry> {
        if (this == null) return emptyList()
        val deduped = linkedMapOf<String, IgnoredImageEntry>()
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val path = item.optString("path").trim()
            if (path.isBlank()) continue
            deduped[path] = IgnoredImageEntry(
                path = path,
                name = item.optString("name").ifBlank { File(path).name },
                addedAt = item.optLong("addedAt", System.currentTimeMillis()),
            )
        }
        return deduped.values.toList()
    }
}
