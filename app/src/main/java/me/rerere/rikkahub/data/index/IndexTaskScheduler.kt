package me.rerere.rikkahub.data.index

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 调度索引 worker。
 *
 * 只用一个唯一任务名：短时间内多次保存会话会不断入队，但真正干活的始终只有一条队列，
 * 避免每存一条消息就起一个 worker 抢配额。
 */
class IndexTaskScheduler(private val context: Context) {
    fun schedule(debounceSeconds: Long = DEFAULT_DEBOUNCE_SECONDS) {
        val request = OneTimeWorkRequestBuilder<ConversationIndexWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInitialDelay(debounceSeconds, TimeUnit.SECONDS)
            // 失败（网络抖动、接口 5xx）要自动退避重试，不能只等用户再点一次
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, MIN_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
        Log.d(TAG, "index work scheduled")
    }

    companion object {
        private const val TAG = "IndexTaskScheduler"
        const val WORK_NAME = "conversation-vector-index"
        private const val DEFAULT_DEBOUNCE_SECONDS = 15L
        private const val MIN_BACKOFF_SECONDS = 30L
    }
}
