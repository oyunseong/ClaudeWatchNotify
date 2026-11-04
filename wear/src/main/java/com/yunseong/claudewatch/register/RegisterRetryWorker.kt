package com.yunseong.claudewatch.register

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 토큰 등록이 실패하면 네트워크가 연결될 때 다시 시도한다. (docs/DESIGN.md 8.5)
 * 설치 직후나 onNewToken 순간에 워치가 오프라인이면, 앱을 다시 열 때까지 등록이 안 되던 문제를 막는다.
 */
class RegisterRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val registrar = TokenRegistrar.get(applicationContext)
        registrar.ensureRegistered(scheduleRetry = false)
        return if (registrar.status.value.isRegistered) Result.success()
        else if (runAttemptCount < MAX_ATTEMPTS) Result.retry()
        else Result.failure()
    }

    companion object {
        private const val NAME = "register-retry"
        private const val MAX_ATTEMPTS = 8

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<RegisterRetryWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
