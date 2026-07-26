package com.findthemout.app.data

import android.content.Context
import androidx.room.Room
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PinnedFolder::class, ImageFingerprintCache::class, PendingDeletionEntry::class, ImagePairCache::class, IgnoredImageEntry::class],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pinnedFolderDao(): PinnedFolderDao
    abstract fun imageFingerprintCacheDao(): ImageFingerprintCacheDao
    abstract fun pendingDeletionDao(): PendingDeletionDao
    abstract fun imagePairCacheDao(): ImagePairCacheDao
    abstract fun ignoredImageDao(): IgnoredImageDao

    companion object {
        const val DATABASE_NAME: String = "find_them_out.db"

        private val migration1To2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS image_fingerprint_cache (
                        path TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        size INTEGER NOT NULL,
                        modifiedAt INTEGER NOT NULL,
                        width INTEGER NOT NULL,
                        height INTEGER NOT NULL,
                        pHash INTEGER NOT NULL,
                        aHash INTEGER NOT NULL,
                        dHash INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        private val migration2To3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_deletion_entries (
                        path TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        size INTEGER NOT NULL,
                        addedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        private val migration3To4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS image_pair_cache (
                        leftPath TEXT NOT NULL,
                        rightPath TEXT NOT NULL,
                        leftSize INTEGER NOT NULL,
                        rightSize INTEGER NOT NULL,
                        leftModifiedAt INTEGER NOT NULL,
                        rightModifiedAt INTEGER NOT NULL,
                        score INTEGER NOT NULL,
                        isMatch INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(leftPath, rightPath)
                    )
                    """.trimIndent(),
                )
            }
        }

        private val migration4To5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ignored_image_entries (
                        path TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        addedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME,
                ).addMigrations(
                    migration1To2,
                    migration2To3,
                    migration3To4,
                    migration4To5,
                ).build().also { created ->
                    instance = created
                }
            }
        }
    }
}
