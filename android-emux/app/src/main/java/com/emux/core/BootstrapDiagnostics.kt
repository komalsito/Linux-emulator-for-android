package com.emux.core

import android.content.Context
import java.io.File

/**
 * v0.1.10: "bootstrap failed: NoClassDefFoundError" showed no missing class
 * because the message was taken from the *wrapper* exception only, and some
 * ROMs don't populate getCause() for linkage errors — the real detail lives in
 * getLocalizedMessage()/the stack trace. This helper extracts the actionable
 * part ("Failed resolution of: Lcom/foo/Bar;") and appends a short frame list
 * so the UI error text tells us exactly which class is missing.
 */
object BootstrapDiagnostics {

    fun describe(e: Throwable?): String {
        if (e == null) return "unknown"
        val chain = generateSequence(e) { it.cause }.take(6).toList()
        val sb = StringBuilder()
        for ((i, t) in chain.withIndex()) {
            if (i > 0) sb.append(" <- ")
            sb.append(t.javaClass.simpleName)
            val msg = linkageDetail(t) ?: t.message
            if (!msg.isNullOrEmpty()) sb.append(": ").append(msg.take(220))
        }
        // For linkage errors with no embedded detail, add the top frames —
        // they identify the call site that triggered resolution.
        if (chain.any { it is NoClassDefFoundError || it is ClassNotFoundException } &&
            sb.toString().length < e.toString().length + 40
        ) {
            val frames = e.stackTrace.take(4).joinToString(" | ") {
                "${it.className}.${it.methodName}:${it.lineNumber}"
            }
            if (frames.isNotEmpty()) sb.append(" @ ").append(frames)
        }
        return sb.toString()
    }

    private fun linkageDetail(t: Throwable): String? {
        val m = t.message ?: return null
        val idx = m.indexOf("Failed resolution of:")
        if (idx >= 0) return m.substring(idx).take(200)
        // v0.1.12: ART's LinkageError messages are frequently EMPTY on OEM ROMs
        // (e.g. Samsung/MIUI strip the "Failed resolution of:" detail), which is
        // why users kept seeing a bare "NoClassDefFoundError" with zero info.
        // Recover the missing class name from the stack frames instead — the
        // failing call site is always the top frame we ourselves compiled.
        if (t is NoClassDefFoundError || t is ClassNotFoundException || t is LinkageError) {
            if (!m.isNullOrBlank()) return m.take(200)
            val ours = t.stackTrace.firstOrNull { f ->
                f.className.startsWith("com.emux") && !f.className.contains("BootstrapDiagnostics")
            }
            if (ours != null) {
                return "missing class while running ${ours.methodName} in " +
                    ours.className.substringAfterLast('.') + ":" + ours.lineNumber +
                    " (ART gave no class name — see bootstrap-error.log)"
            }
        }
        return null
    }

    /** Writes the full trace to files/log/bootstrap-error.log (tail shown in Settings→About). */
    fun dumpFull(ctx: Context, e: Throwable?) {
        try {
            val f = File(ctx.filesDir, "log/bootstrap-error.log")
            f.parentFile?.mkdirs()
            val sw = java.io.StringWriter()
            e?.printStackTrace(java.io.PrintWriter(sw))
            f.writeText("${System.currentTimeMillis()}\n${BootstrapDiagnostics.describe(e)}\n$sw\n")
        } catch (_: Exception) { /* diagnostics must never throw */ }
    }
}
