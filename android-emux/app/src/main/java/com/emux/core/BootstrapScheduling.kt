package com.emux.core

import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * v0.1.11 — reflective WorkManager bridge.
 *
 * WHY: every shipped release so far referenced `androidx.work` classes
 * statically (imports, class literals like BootstrapWorker::class.java inside
 * OneTimeWorkRequestBuilder<...>). On devices where the work-runtime linkage
 * fails late (OEM ROM quirks, broken ART profile, direct-boot), merely calling
 * into those code paths throws NoClassDefFoundError — which is exactly the
 * "bootstrap failed: NoClassDefFound" the user sees, with the wrapper's
 * simpleName hiding the missing class. The only way to make bootstrap robust
 * across ALL devices is to never let a androidx.work linkage failure escape:
 * everything here is wrapped in runCatching at each step and any Throwable
 * (including Error subclasses) downgrades us to [BootstrapRunner], a pure
 * in-process path that needs zero extra libraries.
 */
object BootstrapScheduling {

    /** true once we know androidx.work links on this device. */
    @Volatile var workAvailable: Boolean = false
        private set

    @Volatile private var probed = false

    /** Probe + lazy explicit init of WorkManager. Never throws. */
    fun ensureWork(ctx: Context): Boolean {
        if (probed) return workAvailable
        synchronized(this) {
            if (probed) return workAvailable
            val ok = runCatching {
                // v0.1.12 THE REAL NoClassDefFoundError FIX: BootstrapWorker
                // statically extends androidx.work.CoroutineWorker. WorkManager
                // instantiates worker classes reflectively *before* any code of
                // ours runs inside them — so if ART cannot resolve that
                // superclass, the job dies with a bare NoClassDefFoundError and
                // none of our try/catch guards ever execute. That is why every
                // previous "guarded" release still failed identically on the
                // user's device. We now PROVE assignability here, eagerly and
                // inside this catch block; if it fails we report W-M unavailable
                // and MainActivity transparently runs the identical bootstrap
                // through the zero-dependency BootstrapRunner instead.
                val cw = Class.forName("androidx.work.CoroutineWorker")
                val bw = Class.forName("com.emux.core.BootstrapWorker")
                require(cw.isAssignableFrom(bw)) {
                    "BootstrapWorker not loadable against androidx.work on this ROM"
                }
                val cfgCls = Class.forName("androidx.work.Configuration\$Builder")
                val builder = cfgCls.getDeclaredConstructor().newInstance()
                val buildM = cfgCls.getMethod("build")
                val cfg = buildM.invoke(builder)
                val wmCls = Class.forName("androidx.work.WorkManager")
                val initM = wmCls.getMethod("initialize", Context::class.java, Class.forName("androidx.work.Configuration"))
                try { initM.invoke(null, ctx.applicationContext, cfg) } catch (_: Exception) { /* already init'd */ }
                val getM = wmCls.getMethod("getInstance", Context::class.java)
                getM.invoke(null, ctx.applicationContext)  // touch → proves linkage
                true
            }.getOrElse {
                com.emux.EmuXLog.e("androidx.work unavailable -> in-process bootstrap mode", it)
                false
            }
            workAvailable = ok; probed = true
            return ok
        }
    }

    /** Auto-schedule from Application.onCreate (battery-friendly constraints). */
    fun scheduleAuto(ctx: Context) {
        if (!ensureWork(ctx)) {
            // Fresh install & no WorkManager: don't auto-download at app start
            // (that would burn battery outside a managed slot); the Install
            // button path in MainActivity still runs BootstrapRunner directly.
            return
        }
        runCatching { enqueue(ctx, "emux-bootstrap", replace = false) }
            .onFailure {
                com.emux.EmuXLog.e("scheduleAuto failed", it)
            }
    }

    /** Enqueue BootstrapWorker uniquely. Returns true if actually enqueued. */
    fun enqueue(ctx: Context, uniqueName: String, replace: Boolean): Boolean {
        if (!ensureWork(ctx)) return false
        return runCatching {
            val wmCls = Class.forName("androidx.work.WorkManager")
            val wm = wmCls.getMethod("getInstance", Context::class.java).invoke(null, ctx.applicationContext)

            // OneTimeWorkRequest.Builder via reflection (generics erased anyway).
            val otwrCls = Class.forName("androidx.work.OneTimeWorkRequest\$Builder")
            val workerCls = Class.forName("com.emux.core.BootstrapWorker")
            val reqBuilder = otwrCls.getDeclaredConstructor(Class::class.java).newInstance(workerCls)

            // Constraints: network connected.
            val cCls = Class.forName("androidx.work.Constraints\$Builder")
            val cb = cCls.getDeclaredConstructor().newInstance()
            val netEnum = Class.forName("androidx.work.NetworkType")
                .enumConstants().first { (it as Enum<*>).name == "CONNECTED" }
            val setNet = cCls.getMethod("setRequiredNetworkType", Class.forName("androidx.work.NetworkType"))
            setNet.invoke(cb, netEnum)
            val cons = cCls.getMethod("build").invoke(cb)
            otwrCls.getMethod("setConstraints", Class.forName("androidx.work.Constraints"))
                .invoke(reqBuilder, cons)

            // Exponential backoff 30s so a dead mirror can't hammer.
            otwrCls.getMethod(
                "setBackoffCriteria",
                Class.forName("androidx.work.BackoffPolicy"),
                Long::class.javaPrimitiveType, TimeUnit::class.java
            ).invoke(
                reqBuilder,
                Class.forName("androidx.work.BackoffPolicy").enumConstants()
                    .first { (it as Enum<*>).name == "EXPONENTIAL" },
                30L, TimeUnit.SECONDS
            )

            val req = otwrCls.getMethod("build").invoke(reqBuilder)

            val policy = Class.forName("androidx.work.ExistingWorkPolicy")
                .enumConstants().first {
                    (it as Enum<*>).name == if (replace) "REPLACE" else "KEEP"
                }
            wmCls.getMethod(
                "enqueueUniqueWork", String::class.java,
                Class.forName("androidx.work.ExistingWorkPolicy"),
                Class.forName("androidx.work.WorkRequest")
            ).invoke(wm, uniqueName, policy, req)
            true
        }.getOrElse {
            com.emux.EmuXLog.e("W-M enqueue failed -> falling back in-process", it)
            false
        }
    }

    /** Cancel a unique job; safe if WorkManager is absent. */
    fun cancel(ctx: Context, uniqueName: String) {
        if (!workAvailable) return
        runCatching {
            val wmCls = Class.forName("androidx.work.WorkManager")
            val wm = wmCls.getMethod("getInstance", Context::class.java).invoke(null, ctx.applicationContext)
            wmCls.getMethod("cancelUniqueWork", String::class.java).invoke(wm, uniqueName)
        }
    }

    /** LiveData<List<WorkInfo>> for a unique name, or null when unavailable. */
    @Suppress("UNCHECKED_CAST")
    fun progressLiveData(ctx: Context, uniqueName: String): androidx.lifecycle.LiveData<Any>? {
        if (!ensureWork(ctx)) return null
        return runCatching {
            val wmCls = Class.forName("androidx.work.WorkManager")
            val wm = wmCls.getMethod("getInstance", Context::class.java).invoke(null, ctx.applicationContext)
            val ld = wmCls.getMethod(
                "getWorkInfosForUniqueWorkLiveData", String::class.java
            ).invoke(wm, uniqueName)
            ld as androidx.lifecycle.LiveData<Any>
        }.getOrNull()
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> Class<T>.enumConstants(): List<T> =
    (enumConstants as? Array<T>)?.toList() ?: emptyList()
