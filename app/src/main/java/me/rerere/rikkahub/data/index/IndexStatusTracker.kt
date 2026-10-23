package me.rerere.rikkahub.data.index

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 后台索引跑到哪了、上一次为什么失败。
 *
 * worker 在后台干活，界面上要是看不到进度和错误，用户就只剩「一堆数字不动」和「什么都没发生」；
 * 这里把状态集中成一个流，设置页订阅它就行，不需要轮询数据库。
 */
class IndexStatusTracker {
    data class Status(
        /** 当前是否在跑 */
        val running: Boolean = false,
        /** 本轮已处理的会话数 */
        val processed: Int = 0,
        /** 本轮待处理的会话数 */
        val total: Int = 0,
        /** 最近一次失败原因，成功跑完一轮后清空 */
        val lastError: String? = null,
        /** 最近一次失败的时间戳（毫秒） */
        val lastErrorAt: Long = 0L,
        /** 最近一次成功的时间戳（毫秒） */
        val lastSuccessAt: Long = 0L,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    fun start(total: Int) {
        _status.value = _status.value.copy(running = true, processed = 0, total = total)
    }

    fun progress(processed: Int, total: Int) {
        _status.value = _status.value.copy(running = true, processed = processed, total = total)
    }

    fun idle() {
        _status.value = _status.value.copy(running = false)
    }

    fun success() {
        _status.value = _status.value.copy(
            running = false,
            lastError = null,
            lastSuccessAt = System.currentTimeMillis(),
        )
    }

    /** 记下失败原因但不结束本轮：同一轮里别的会话还在继续 */
    fun noteError(message: String) {
        _status.value = _status.value.copy(
            lastError = message,
            lastErrorAt = System.currentTimeMillis(),
        )
    }
}
