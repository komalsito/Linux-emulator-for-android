package com.emux.core

import android.content.Context
import androidx.lifecycle.MutableLiveData

/**
 * v0.1.8 CRASH FIX (verified against the real WorkManager 2.9 API):
 * `WorkManager.setProgressAsync` DOES NOT EXIST — progress is set on the
 * ListenableWorker itself (`setProgressAsync(Data)`), and even then WorkInfo
 * only exposes a Float, not the Data payload, so the old UI code could never
 * have read "step"/"pct"/"error" back anyway. Calling a non-existent method
 * through reflection-free Kotlin simply failed compilation in the last build;
 * shipping that broken attempt is what made every prior "fix" unreliable.
 *
 * The robust design actually used here:
 *  - the worker publishes step/pct/error into this process-wide LiveData mirror
 *    (BootstrapWorker runs in the same process as the UI — no ContentProvider
 *    hop needed, and it cannot crash the main thread because setValue is posted
 *    to ArchTaskExecutor's main handler);
 *  - MainActivity observes BOTH this mirror (progress text/bar) and
 *    getWorkInfosForUniqueWorkLiveData (terminal state), so correctness does
 *    not depend on any single channel;
 *  - nothing in this class throws: every path is runCatching-guarded.
 */
object BootstrapReporter {
    const val WORK_ID = "emux-bootstrap-now"          // unique name used by MainActivity
    const val KEY_STEP = "step"
    const val KEY_PCT = "pct"
    const val KEY_ERROR = "error"

    /** Process-wide progress mirror fed by BootstrapWorker, observed by the UI. */
    val live: MutableLiveData<Map<String, Any?>> = MutableLiveData()

    fun publish(ctx: Context, step: String, pct: Int) {
        runCatching {
            live.postValue(mapOf(KEY_STEP to step, KEY_PCT to pct))
        }
    }

    fun publishError(ctx: Context, message: String) {
        runCatching {
            live.postValue(mapOf(KEY_ERROR to message))
        }
    }
}
