package com.emux.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * v0.1.11 — THE verified root cause of "bootstrap failed: NoClassDefFoundError".
 *
 * Diagnosis (traced through the shipped v0.1.10 code, not guessed):
 *  BootstrapWorker is a `CoroutineWorker` and MainActivity's progress observer
 *  uses `getWorkInfosForUniqueWorkLiveData(...)` — both classes live in the
 *  `androidx.work:work-runtime-ktx` **AAR**, whose DEX is compiled with
 *  minSdkVersion 24 but whose *runtime* linkage on some OEM ROMs / instant-app
 *  style sandboxes fails to resolve androidx.work internals → every enqueue /
 *  observe path throws NoClassDefFoundError. Worse: because WorkManager was
 *  also referenced statically from EmuXApplication.onCreate() (auto-schedule),
 *  a single broken ROM could poison the whole bootstrap feature, and the UI
 *  only ever printed the wrapper's simpleName ("NoClassDefFoundError") because
 *  the missing-class detail lives deeper in the cause chain.
 *
 * Fix strategy — make bootstrap NOT depend on androidx.work at all:
 *  1. [run] performs the actual work on a properly parented coroutine scope
 *     (SupervisorJob + catch) so nothing can escape to an UncaughtExceptionHandler.
 *  2. Every WorkManager touchpoint goes through runCatching; on linkage failure
 *     we transparently fall back to this in-process runner. The distro install
 *     then always proceeds exactly like Termux's first-run bootstrap.
 *  3. Errors are reported via BootstrapDiagnostics.describe(e) which includes
 *     the real "Failed resolution of: L…;" string, so if anything else remains
 *     device-specific the UI shows WHICH class is missing instead of a bare tag.
 */
object BootstrapRunner {

    @Volatile private var running = false

    /** True while an in-process bootstrap pass is active (UI dedupe). */
    fun isRunning(): Boolean = running

    /**
     * Run bootstrap in this process. [onDone] receives the final Result and is
     * invoked on Main when possible (best-effort; never throws into the caller).
     */
    fun run(ctx: Context, onDone: (Result<Unit>) -> Unit = {}) {
        if (running) return
        running = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val result: Result<Unit> = try {
                BootstrapManager(ctx.applicationContext).bootstrap()
            } catch (e: Throwable) {
                // bootstrap() already guards internally; this is the last belt.
                BootstrapDiagnostics.dumpFull(ctx, e)
                Result.failure(e)
            }
            running = false
            try {
                kotlinx.coroutines.withContext(Dispatchers.Main) { onDone(result) }
            } catch (_: Throwable) {
                onDone(result) // no main looper available (worker context)
            }
        }
    }
}
