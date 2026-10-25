package me.rerere.ai.agent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import me.rerere.ai.util.HttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

class RetryTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `io failures are retried with doubling delays`() = runTest {
        var calls = 0
        val attempts = mutableListOf<RetryAttempt>()

        val result = withRetry(RetryPolicy.NETWORK, onRetry = { attempts += it }) {
            calls++
            if (calls < 3) throw IOException("timeout")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(listOf(1, 2), attempts.map { it.retry })
        assertEquals(listOf(1.seconds, 2.seconds), attempts.map { it.delay })
        assertEquals(3_000L, currentTime)
    }

    @Test
    fun `gives up after max retries`() = runTest {
        var calls = 0

        val error = runCatching {
            withRetry(RetryPolicy.NETWORK) {
                calls++
                throw IOException("offline")
            }
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(4, calls)
    }

    @Test
    fun `other errors are thrown without retrying`() = runTest {
        var calls = 0

        val error = runCatching {
            withRetry(RetryPolicy.NETWORK) {
                calls++
                throw IllegalStateException("bad request")
            }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(1, calls)
    }

    @Test
    fun `io failure caused by cancellation is not retried`() = runTest {
        var calls = 0

        val job = launch {
            withRetry(RetryPolicy(maxRetries = 3, initialDelay = Duration.ZERO)) {
                calls++
                // 停止生成时，底层连接会以 IOException("Canceled") 结束请求
                currentCoroutineContext().cancel()
                throw IOException("Canceled")
            }
        }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(1, calls)
    }

    @Test
    fun `rate limit and server errors are retried`() = runTest {
        listOf(408, 429, 500, 502, 503, 504).forEach { code ->
            var calls = 0
            val result = withRetry(RetryPolicy(maxRetries = 2, initialDelay = ZERO)) {
                calls++
                if (calls == 1) throw HttpException("boom", code = code)
                "ok"
            }
            assertEquals("code=$code 应该重试", "ok", result)
            assertEquals("code=$code", 2, calls)
        }
    }

    @Test
    fun `client errors are not retried`() = runTest {
        // 这几个重发结果不会变，重试只是让用户白等
        listOf(400, 401, 403, 404, 422).forEach { code ->
            var calls = 0
            val error = runCatching {
                withRetry(RetryPolicy(maxRetries = 3, initialDelay = ZERO)) {
                    calls++
                    throw HttpException("boom", code = code)
                }
            }.exceptionOrNull()
            assertTrue("code=$code", error is HttpException)
            assertEquals("code=$code 不该重试", 1, calls)
        }
    }

    @Test
    fun `errors without a status code fall back to the error type`() = runTest {
        // 流式响应的错误事件通常没有数字状态码，只有类型字符串
        var calls = 0
        val result = withRetry(RetryPolicy(maxRetries = 2, initialDelay = ZERO)) {
            calls++
            if (calls == 1) throw HttpException("upstream busy", type = "rate_limit_error")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, calls)
    }

    @Test
    fun `unknown errors are never retried`() = runTest {
        // 未知错误里混着余额不足、key 失效、内容被拒，乱重试没用还可能被记成滥用
        var calls = 0
        val error = runCatching {
            withRetry(RetryPolicy(maxRetries = 3, initialDelay = ZERO)) {
                calls++
                throw HttpException("quota exceeded", type = "insufficient_quota")
            }
        }.exceptionOrNull()
        assertTrue(error is HttpException)
        assertEquals(1, calls)
    }
}
