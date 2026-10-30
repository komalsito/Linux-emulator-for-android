package com.emux

import android.content.Context
import java.io.File

/**
 * v0.2.62 CRASH VISIBILITY.
 *
 * The problem this solves is specific and was hit in the field: the app died on
 * EVERY launch with the system "EmuX keeps stopping" dialog, and the user had no
 * adb, so the stack trace was unreachable. MainActivity.onCreate already wraps
 * its whole startup path in catch(Throwable) and degrades to an on-screen error
 * (v0.1.3), and EmuXApplication.onCreate guards each of its statements (v0.1.6),
 * postSafely guards View.post bodies (v0.1.8) — so the throws that produce the
 * SYSTEM dialog are exactly the ones that escape all of those:
 *
 *   - a ContentProvider (androidx.startup runs EmojiCompat / ProcessLifecycle /
 *     ProfileInstaller initializers here, before any Activity exists),
 *   - a Runnable posted to the main looper after onCreate returned,
 *   - an uncaught Throwable inside a coroutine (goes straight to the thread's
 *     handler — see the v0.1.8 note in MainActivity),
 *   - a View callback such as onDraw, which no Activity-level guard can reach,
 *   - class initialization (ExceptionInInitializerError) of anything touched by
 *     the two statements in Application.onCreate that run before its guards.
 *
 * A Thread.setDefaultUncaughtExceptionHandler installed as the first statement of
 * Application.onCreate covers all of those, because it is process-wide and is in
 * place before any provider or Activity runs. It writes the report to disk and
 * then delegates to the previous handler, so the crash still crashes — this never
 * swallows a failure, it only makes it readable afterwards.
 *
 * The next launch surfaces it: MainActivity shows the report on screen with a
 * copy button and a way to continue, so a field device with no adb and no root
 * can still tell us what killed it.
 */
object CrashReport {

    private const val NAME = "last_crash.txt"

    /** Freshness window: a report older than this is stale, not the current bug. */
    private const val MAX_AGE_MS = 30L * 60 * 1000

    @Volatile private var installed = false

    /** Both locations, because only the external one is reachable without adb. */
    private fun targets(ctx: Context): List<File> = listOfNotNull(
        runCatching { File(ctx.filesDir, NAME) }.getOrNull(),
        runCatching { ctx.getExternalFilesDir(null)?.let { File(it, NAME) } }.getOrNull()
    )

    /**
     * Install the process-wide reporter. Idempotent, and itself fully guarded:
     * this is the first thing Application.onCreate does, so a failure here must
     * not be the reason the app dies.
     */
    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val previous = runCatching { Thread.getDefaultUncaughtExceptionHandler() }.getOrNull()
        runCatching {
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching { write(app, thread, error) }
                // Never swallow: hand back to the platform so the process still
                // dies and the system still reports it. A hidden crash is worse
                // than a visible one.
                if (previous != null) previous.uncaughtException(thread, error)
            }
        }
    }

    private fun write(ctx: Context, thread: Thread, error: Throwable) {
        val sb = StringBuilder(4096)
        sb.append("EmuX crash report\n")
        sb.append("app version : ")
          .append(runCatching { EmuXLog.appVersion(ctx) }.getOrDefault("?")).append('\n')
        sb.append("env version : ")
          .append(runCatching { com.emux.core.FsLayout.ENV_VERSION.toString() }.getOrDefault("?")).append('\n')
        sb.append("android     : API ").append(android.os.Build.VERSION.SDK_INT)
          .append(" / ").append(android.os.Build.MANUFACTURER).append(' ')
          .append(android.os.Build.MODEL).append('\n')
        sb.append("abi         : ")
          .append(runCatching { android.os.Build.SUPPORTED_ABIS.joinToString(",") }.getOrDefault("?")).append('\n')
        sb.append("thread      : ").append(thread.name).append('\n')
        sb.append("when        : ").append(java.util.Date()).append('\n')
        sb.append('\n')
        val sw = java.io.StringWriter()
        runCatching { error.printStackTrace(java.io.PrintWriter(sw)) }
        sb.append(sw.toString().ifBlank { "${error.javaClass.name}: ${error.message}" })
        sb.append("\n--- EmuXLog tail (what happened just before) ---\n")
        sb.append(runCatching { EmuXLog.tail(80) }.getOrDefault("(log unavailable)"))
        val text = sb.toString()
        for (f in targets(ctx)) {
            runCatching { f.parentFile?.mkdirs(); f.writeText(text) }
        }
        runCatching { EmuXLog.e("uncaught on ${thread.name}", error) }
    }

    /**
     * The report from the previous process, if it is recent enough to be about
     * the crash the user is reporting. Does NOT delete it — call [clear] once the
     * user has dismissed it, so a second look is still possible.
     */
    fun consumeIfFresh(ctx: Context): String? {
        val f = runCatching { File(ctx.filesDir, NAME) }.getOrNull() ?: return null
        return runCatching {
            if (!f.isFile) return null
            if (System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS) { clear(ctx); return null }
            val t = f.readText()
            if (t.isBlank()) null else t
        }.getOrNull()
    }

    fun clear(ctx: Context) {
        for (f in targets(ctx)) runCatching { if (f.exists()) f.delete() }
    }

    /** Absolute paths, so the report can tell a user where to look without adb. */
    fun locations(ctx: Context): String = targets(ctx).joinToString("\n") { it.absolutePath }
}
