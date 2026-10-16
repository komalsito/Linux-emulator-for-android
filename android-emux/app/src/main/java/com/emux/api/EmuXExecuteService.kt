package com.emux.api

import android.app.IntentService
import android.content.Intent
import com.emux.terminal.TerminalService

/**
 * termux-api style entry point: other apps (Tasker, Termux:API clients, your own
 * scripts) can run commands inside EmuX's Linux env via
 *   am start-foreground-service -a com.emux.RUN_COMMAND --es command 'uname -a'
 * Guarded by the custom permission com.emux.RUN_COMMAND declared in the manifest.
 */
class EmuXExecuteService : IntentService("EmuXExec") {
    override fun onHandleIntent(intent: Intent?) {
        val command = intent?.getStringExtra("command") ?: return
        val resultFile = intent.getStringExtra("resultFile")
        val fullCmd = buildString {
            append(command)
            if (!command.contains(">")) resultFile?.let { append(" > '$it' 2>&1") }
        }
        TerminalService.startSession(this, "-l -c \"${fullCmd.replace("\"", "\\\"")}\"")
    }
}
