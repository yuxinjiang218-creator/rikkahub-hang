package me.rerere.rikkahub.data.index

import android.content.Context
import android.util.Log

/**
 * 进程内唯一的向量索引实例。
 *
 * 扩展的索引配置是连接级的，多开连接只会互相干扰，所以整个进程共用一条连接、内部串行。
 */
class VectorIndexProvider(private val context: Context) {
    private var store: VectorIndex? = null

    fun get(): VectorIndex = synchronized(this) {
        store ?: SqliteVectorStore.open(context).also { store = it }
    }

    /** 缓存库坏了或者要重建时调用，下次取用会重新打开 */
    fun reset() = synchronized(this) {
        runCatching { store?.close() }
        store = null
    }

    fun close() = synchronized(this) {
        runCatching { store?.close() }
        store = null
    }

    fun statsOrNull(): VectorIndexStats? = runCatching { get().stats() }
        .onFailure { Log.w("VectorIndexProvider", "读取索引状态失败: ${it.message}") }
        .getOrNull()
}
