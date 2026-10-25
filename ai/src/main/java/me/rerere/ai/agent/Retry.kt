package me.rerere.ai.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import me.rerere.ai.util.HttpException
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 请求模型失败时的重试策略：最多重试 [maxRetries] 次，等待时间从 [initialDelay] 开始每次翻倍。
 *
 * 默认重试两类：
 * - [IOException]：连不上、超时、读到一半断开这类网络故障；
 * - [HttpException.retryable]：429 限流和 5xx 服务端抖动。这类以前被漏掉了，
 *   因为状态码被拼进了 message 字符串，重试层根本看不到。
 *
 * 鉴权错、参数错、内容过滤这些重发结果不会变，重试只会让用户白等。
 */
data class RetryPolicy(
    val maxRetries: Int,
    val initialDelay: Duration = 1.seconds,
    val retryOn: (Throwable) -> Boolean = { it.isRetryableFailure() },
) {
    companion object {
        val NONE = RetryPolicy(maxRetries = 0)

        /** 网络自动重试：最多 3 次，间隔 1s、2s、4s */
        val NETWORK = RetryPolicy(maxRetries = 3)
    }
}

/** 这个异常重发一次有没有可能变好 */
internal fun Throwable.isRetryableFailure(): Boolean = when (this) {
    is IOException -> true
    is HttpException -> retryable
    else -> false
}

/** 第 [retry] 次重试（共 [maxRetries] 次）将在 [delay] 后开始，[cause] 是上一次失败的原因 */
data class RetryAttempt(
    val cause: Throwable,
    val retry: Int,
    val maxRetries: Int,
    val delay: Duration,
)

/**
 * 按 [policy] 执行 [block]：失败且允许重试时先调用 [onRetry]，等待后再执行一次。
 */
suspend fun <T> withRetry(
    policy: RetryPolicy,
    onRetry: suspend (RetryAttempt) -> Unit = {},
    block: suspend () -> T,
): T {
    var retries = 0
    while (true) {
        try {
            return block()
        } catch (error: Throwable) {
            // 用户主动停止生成时，底层连接也可能以 IOException("canceled") 收尾；
            // 先检查协程状态，确保取消不会被当作网络波动重新拉起。
            currentCoroutineContext().ensureActive()
            if (error is CancellationException || retries >= policy.maxRetries || !policy.retryOn(error)) {
                throw error
            }
            retries++
            val backoff = policy.initialDelay * (1 shl (retries - 1))
            onRetry(RetryAttempt(error, retries, policy.maxRetries, backoff))
            delay(backoff)
        }
    }
}
