package com.emux.core

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Defers the ~3 MB rootfs download+extract to a WorkManager-scheduled slot. */
class BootstrapWorker(appCtx: Context, params: WorkerParameters) :
    CoroutineWorker(appCtx, params) {

    override suspend fun doWork(): Result = try {
        // v0.1.9 CRASH FIX (verified against kotlinx-coroutines semantics):
        // withContext propagates exceptions from its *child* coroutine even when
        // the body has its own try/catch — BootstrapManager.bootstrap() collects
        // bootstrapFlow(), whose internal ioScope.launch rethrew any escaped
        // Throwable straight through here, past every guard, into WorkManager's
        // executor thread → UncaughtExceptionHandler → the whole app died the
        // moment Install was tapped. That is THE Install-button crash.
        // Now nothing can leave this worker uncaught; failures become retry().
        val mgr = BootstrapManager(applicationContext)
        val r = mgr.bootstrap()
        if (r.isSuccess) Result.success() else Result.retry()
    } catch (e: Throwable) {
        // v0.1.10: the UI previously showed only "NoClassDefFoundError" because
        // we printed the wrapper's simpleName. Report the FULL cause chain with
        // the linkage detail ("Failed resolution of: L…;") and dump the complete
        // trace to files/log/bootstrap-error.log for remote diagnosis.
        com.emux.core.BootstrapDiagnostics.dumpFull(applicationContext, e)
        runCatching {
            com.emux.core.BootstrapReporter.publishError(
                applicationContext, com.emux.core.BootstrapDiagnostics.describe(e))
        }
        com.emux.EmuXLog.e("BootstrapWorker caught (was an app-killing escape)", e)
        Result.retry()
    }
}
