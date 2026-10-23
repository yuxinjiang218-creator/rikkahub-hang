package me.rerere.rikkahub.data.db

import android.content.Context
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import java.io.File

/** Use the same SQLite extensions for the live database and backup validation. */
internal object SQLiteConfiguration {
    const val DATABASE_NAME = "rikka_hub"

    /** 向量索引缓存的库文件名，存放在 noBackupFilesDir 下，不进备份 */
    const val VECTOR_DATABASE_NAME = "chat_vectors.db"

    /** 向量扩展的入口函数名，加载时必须显式指定 */
    const val VECTOR_EXTENSION_ENTRY = "sqlite3_vector_init"

    fun configure(context: Context, configuration: SQLiteDatabaseConfiguration): SQLiteDatabaseConfiguration {
        configuration.customExtensions.add(
            SQLiteCustomExtension(context.applicationInfo.nativeLibraryDir + "/libsimple", null)
        )
        return configuration
    }

    fun openHelperFactory(context: Context) = RequerySQLiteOpenHelperFactory(
        listOf(RequerySQLiteOpenHelperFactory.ConfigurationOptions { configure(context, it) })
    )

    fun vectorDatabaseFile(context: Context): File =
        File(File(context.noBackupFilesDir, "vector"), VECTOR_DATABASE_NAME)

    /** 向量库只挂向量扩展，不需要分词器 */
    fun configureVector(
        context: Context,
        configuration: SQLiteDatabaseConfiguration
    ): SQLiteDatabaseConfiguration {
        configuration.customExtensions.add(
            SQLiteCustomExtension(resolveVectorLibrary(context), VECTOR_EXTENSION_ENTRY)
        )
        return configuration
    }

    /**
     * 依赖包里的原生库名是 `vector.so`，打包到 APK 后可能被改成 `libvector.so`。
     *
     * 两个候选都探一下，谁存在用谁；都不存在就直接报错，避免运行到一半才发现扩展没加载。
     */
    private fun resolveVectorLibrary(context: Context): String {
        val dir = context.applicationInfo.nativeLibraryDir
        val candidates = listOf("$dir/libvector", "$dir/vector")
        return candidates.firstOrNull { File("$it.so").isFile }
            ?: error("找不到向量扩展原生库，候选路径: $candidates")
    }
}
