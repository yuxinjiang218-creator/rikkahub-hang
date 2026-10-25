package me.rerere.rikkahub.data.index

import android.content.Context
import android.util.Log
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import java.io.File

/**
 * 向量缓存的打包。
 *
 * 缓存库平时放在 noBackupFilesDir（不进系统备份），但它对用户是有价值的数据：
 * 里面是已经花过钱的嵌入结果，丢了就得重新调接口、重新付费。
 * 所以应用自己的备份包里带上它，恢复后不用重建。
 *
 * 只负责打包：恢复走 [me.rerere.rikkahub.data.sync.PendingRestore] 的通用文件搬运。
 * 这里不加载向量扩展——扩展是连接级的，为了打包去挂原生库没有意义，
 * 还会把原生库的可用性问题带进备份流程（某些定制系统上加载不了就整个备份都做不成）。
 */
internal object VectorBackup {
    /** 压缩包里的目录名，和 database/ 平级 */
    const val ARCHIVE_DIRECTORY = "vector"

    /** 相对 [ARCHIVE_DIRECTORY] 的条目名 */
    const val ARCHIVE_DATABASE = "$ARCHIVE_DIRECTORY/${SQLiteConfiguration.VECTOR_DATABASE_NAME}"

    private const val TAG = "VectorBackup"

    /**
     * 把向量缓存整理成一份可独立打开的单文件副本。
     *
     * WAL 里可能还有没落盘的向量，直接拷主文件会丢最新一批，
     * 所以和主库一样用 `VACUUM INTO` 做一致性快照。
     *
     * 缓存不存在（从没建过索引）或快照失败时返回 null，调用方跳过，备份照常进行：
     * 少一份缓存只是恢复后要重新索引，不该让整个备份失败。
     */
    fun createSnapshot(context: Context, destination: File): File? {
        val source = SQLiteConfiguration.vectorDatabaseFile(context)
        if (!source.isFile || source.length() == 0L) {
            Log.i(TAG, "没有向量缓存，本次备份不含索引")
            return null
        }
        destination.parentFile?.mkdirs()
        var database: SQLiteDatabase? = null
        return try {
            database = SQLiteDatabase.openDatabase(
                SQLiteDatabaseConfiguration(source.path, SQLiteDatabase.OPEN_READWRITE),
                null,
                null
            )
            database.execSQL("VACUUM main INTO ?", arrayOf(destination.absolutePath))
            destination
        } catch (error: Throwable) {
            Log.w(TAG, "向量缓存打包失败，本次备份不含索引", error)
            destination.delete()
            null
        } finally {
            runCatching { database?.close() }
        }
    }
}
