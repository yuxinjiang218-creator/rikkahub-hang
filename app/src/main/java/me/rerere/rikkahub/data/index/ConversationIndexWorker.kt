package me.rerere.rikkahub.data.index

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * 后台建立/维护向量索引。
 *
 * 约束里有网络要求（要调 embedding 接口），一轮处理不完就继续下一轮，直到队列空。
 */
class ConversationIndexWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {
    private val indexer: ConversationIndexer by inject()
    private val statusTracker: IndexStatusTracker by inject()

    override suspend fun doWork(): Result {
        // running 由这里统一开始和收尾：中途分批、跨批都不落，
        // 抽屉里那条才不会一闪一闪；异常退出去也一定收尾，不会永远转圈
        var finished = false
        return try {
            indexer.ensureFtsReady()
            // 不看轮数看时间：轮数封顶会让大批历史“假装做完”，然后半天没人管
            val deadline = System.currentTimeMillis() + WORK_BUDGET_MS
            var deferred = false
            while (!isStopped) {
                val result = indexer.processPending()
                deferred = deferred || result.deferred
                if (!result.hasMore) break
                if (System.currentTimeMillis() >= deadline) {
                    Log.i(TAG, "本次时间用完，剩余任务交给下一次调度")
                    statusTracker.finishWithError()
                    finished = true
                    return Result.retry()
                }
            }
            if (deferred) {
                // 有会话没做成（网络或接口问题），交给退避重试，别等用户再点“立即重试”
                statusTracker.finishWithError()
                finished = true
                Result.retry()
            } else {
                statusTracker.finishSuccess()
                finished = true
                Result.success()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Log.w(TAG, "索引任务失败，稍后重试: ${error.message}")
            statusTracker.noteError("索引任务异常：${error.message ?: error::class.simpleName}")
            statusTracker.finishWithError()
            finished = true
            Result.retry()
        } finally {
            if (!finished) statusTracker.finishWithError()
        }
    }

    companion object {
        private const val TAG = "ConversationIndexWorker"

        /** 一次 worker 最多干这么久，剩下的靠 retry 续上，而不是直接宣布完成 */
        private const val WORK_BUDGET_MS = 8 * 60 * 1000L
    }
}
