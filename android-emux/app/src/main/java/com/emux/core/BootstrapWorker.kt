package com.emux.core

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Defers the ~3 MB rootfs download+extract to a WorkManager-scheduled slot. */
class BootstrapWorker(appCtx: Context, params: WorkerParameters) :
    CoroutineWorker(appCtx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val mgr = BootstrapManager(applicationContext)
        val r = mgr.bootstrap()
        if (r.isSuccess) Result.success() else Result.retry()
    }
}
