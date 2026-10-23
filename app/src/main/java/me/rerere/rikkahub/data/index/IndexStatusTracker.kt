package me.rerere.rikkahub.data.index

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 后台索引跑到哪了、上一次为什么失败。
 *
 * worker 在后台干活，界面上要是看不到进度和错误，用户就只剩「一堆数字不动」和「什么都没发生」。
 * 这里是一份纯内存状态：worker 推、界面订阅，不查库、不轮询。
 *
 * 关键约定：一次 worker 执行 = 一个会话周期。[start] 时定下总数，之后只增分子；
 * 结束（成功或失败）才落 [running]，中途不会假结束，抽屉里那条也就不会一闪一闪。
 */
class IndexStatusTracker {
    data class Status(
        /** 整段执行期间为 true，收工才落 */
        val running: Boolean = false,
        /** 本次执行已处理的会话数，只增不减 */
        val processed: Int = 0,
        /** 本次执行开始时的待处理会话数，固定不变 */
        val total: Int = 0,
        /** 最近一次失败原因，成功跑完一次后清空 */
        val lastError: String? = null,
        /** 最近一次失败的时间戳（毫秒） */
        val lastErrorAt: Long = 0L,
        /** 最近一次成功的时间戳（毫秒） */
        val lastSuccessAt: Long = 0L,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** 开始一次执行；已经在跑就不重置，避免分批时进度被打回 0 */
    fun startIfNeeded(total: Int) {
        if (_status.value.running) return
        _status.value = _status.value.copy(running = true, processed = 0, total = total)
    }

    /** 处理完一个会话 */
    fun advance() {
        _status.value = _status.value.copy(processed = _status.value.processed + 1)
    }

    fun finishSuccess() {
        _status.value = _status.value.copy(
            running = false,
            lastError = null,
            lastSuccessAt = System.currentTimeMillis(),
        )
    }

    /** 收工但有会话没做成：保留 [noteError] 记下的原因 */
    fun finishWithError() {
        _status.value = _status.value.copy(running = false)
    }

    /** 记下失败原因但不结束执行：同一轮里别的会话还在继续 */
    fun noteError(message: String) {
        _status.value = _status.value.copy(
            lastError = message,
            lastErrorAt = System.currentTimeMillis(),
        )
    }
}
