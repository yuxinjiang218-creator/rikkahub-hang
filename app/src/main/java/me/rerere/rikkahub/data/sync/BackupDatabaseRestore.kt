package me.rerere.rikkahub.data.sync

import android.content.Context
import android.util.Log
import androidx.room.Room
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.migrations.Migration_11_12
import me.rerere.rikkahub.data.db.migrations.Migration_13_14
import me.rerere.rikkahub.data.db.migrations.Migration_14_15
import me.rerere.rikkahub.data.db.migrations.Migration_15_16
import me.rerere.rikkahub.data.db.migrations.Migration_6_7
import me.rerere.rikkahub.data.repository.BackupDatabaseMergeResult
import me.rerere.rikkahub.data.repository.ConversationRepository
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

private const val TAG = "BackupDatabaseRestore"

internal class BackupDatabaseExtractor(
    private val context: Context,
    private val conversationRepository: ConversationRepository,
) {
    private val tempDir = File(context.cacheDir, "backup_restore_db_${System.nanoTime()}").apply {
        mkdirs()
    }
    private val databaseFile = File(tempDir, "rikka_hub")

    fun copyDatabaseEntry(input: InputStream, entryName: String) {
        val targetFile = when (entryName) {
            "rikka_hub.db" -> databaseFile
            "rikka_hub-wal" -> File(tempDir, "rikka_hub-wal")
            "rikka_hub-shm" -> File(tempDir, "rikka_hub-shm")
            else -> return
        }
        targetFile.parentFile?.mkdirs()
        FileOutputStream(targetFile).use { output ->
            input.copyTo(output)
        }
        Log.i(TAG, "copyDatabaseEntry: extracted $entryName (${targetFile.length()} bytes)")
    }

    suspend fun mergeIfPresent(): BackupDatabaseMergeResult? {
        if (!databaseFile.exists()) {
            cleanup()
            return null
        }

        val backupDatabase = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            databaseFile.absolutePath,
        )
            .addMigrations(Migration_6_7, Migration_11_12, Migration_13_14, Migration_14_15, Migration_15_16)
            .build()

        return try {
            conversationRepository.mergeFromBackupDatabase(backupDatabase).also { result ->
                Log.i(TAG, "mergeIfPresent: $result")
            }
        } finally {
            backupDatabase.close()
            cleanup()
        }
    }

    fun cleanup() {
        if (tempDir.exists()) {
            tempDir.deleteRecursively()
        }
    }
}
