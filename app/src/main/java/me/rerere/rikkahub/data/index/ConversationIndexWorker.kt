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

    override suspend fun doWork(): Result {
        return try {
            indexer.ensureFtsReady()
            var round = 0
            while (!isStopped && round < MAX_ROUNDS) {
                val more = indexer.processPending()
                round++
                if (!more) break
            }
            Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Log.w(TAG, "索引任务失败，稍后重试: ${error.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ConversationIndexWorker"
        private const val MAX_ROUNDS = 20
    }
}
