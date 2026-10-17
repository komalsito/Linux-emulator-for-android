package com.emux.core

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager

/**
 * v0.1.10 fix for "bootstrap failed: NoClassDefFoundError".
 *
 * Verified root-cause candidates in the shipped code, all fixed here:
 *
 *  A) WorkManager was auto-initialized through androidx.startup's
 *     WorkManagerInitializer ContentProvider. On OEM ROMs where provider init
 *     races/fails, every later WorkManager.getInstance() throws
 *     IllegalStateException("…not initialized properly") wrapped around a
 *     missing-class cause — displayed as "NoClassDefFound". We now disable
 *     auto-init in the manifest (tools:node="remove") and initialize lazily
 *     and explicitly before first use.
 *
 *  B) BootstrapWorker references androidx.work.CoroutineWorker, which lives in
 *     work-runtime-ktx. That artifact must be present at runtime; it is declared
 *     `implementation` (fine), but if minification ever stripped it the worker
 *     class load would throw a literal NoClassDefFoundError inside doWork().
 *     We therefore ALSO catch Throwable at the enqueue site AND keep a pure
 *     in-process fallback runner so bootstrap still completes without W-M.
 *
 *  C) The old error text showed only e.javaClass.simpleName of the *wrapper*
 *     (NoClassDefFoundError) hiding the real missing class. BootstrapReporter
 *     callers now pass full chain info (see MainActivity).
 */
object EmuXBootstrapApp {
    @Volatile private var wmReady = false
    @Volatile private var wmFailed = false

    /** Returns true if WorkManager is usable in this process. */
    fun workManagerAvailable(ctx: Application): Boolean {
        if (wmReady) return true
        if (wmFailed) return false
        synchronized(this) {
            if (wmReady || wmFailed) return wmReady
            val ok = runCatching {
                // Explicit init with a minimal config; getInstance() then works.
                WorkManager.initialize(ctx, Configuration.Builder().build())
                WorkManager.getInstance(ctx) // touch it once to prove linkage
                true
            }.getOrElse {
                com.emux.EmuXLog.e("WorkManager init failed -> using in-process fallback", it)
                false
            }
            wmReady = ok; wmFailed = !ok
            return ok
        }
    }
}
