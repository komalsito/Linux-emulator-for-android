package com.emux

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.emux.core.BootstrapReporter
import com.emux.core.FsLayout
import com.emux.core.RootManager
import com.emux.databinding.ActivityMainBinding
import com.emux.terminal.ExtraKeysView
import com.emux.terminal.TerminalService
import com.emux.terminal.VtTerminalView
import com.emux.x11.DesktopService
import com.emux.x11.DesktopView
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Single-Activity UI: terminal ⇄ desktop tabs, bootstrap progress, permission
 * onboarding (all optional), and lifecycle hooks that drive the power model.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val root by lazy { RootManager(this) }
    private val desktop by lazy { DesktopService.get(this, root) }   // v0.1.62 singleton

    private var termView: VtTerminalView? = null

    /**
     * v0.1.31: sessions already upgraded from raw pipes to a real PTY in-place
     * (guards against repeat restarts if registry emissions race the flag).
     */
    private val ptyUpgradedSessions = mutableSetOf<Int>()
    private val ptyGuestUpgraded = mutableSetOf<Int>()   // v0.1.55 guest-PTY upgrade gate

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* fine if denied */ }

    /**
     * v0.1.50 LIVE DISTRO SWITCH (field complaint: "only switches when the
     * app is closed and reopened"). SharedPreferences listeners are held by
     * WEAK reference, so this must be a field, not a lambda passed inline.
     * Selecting an already-installed distro re-stamps the marker, restarts
     * sessions bound to the old rootfs and hides the Install pane immediately;
     * selecting a distro that is not on disk shows the Install pane at once.
     */
    private val distroPrefListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == com.emux.core.DistroCatalog.PREF_KEY) {
                b.root.post {
                    try {
                        if (com.emux.core.FsLayout.ensureMarkerForExistingRootfs(this)) {
                            restartStaleDistroSessions()
                            hideBootstrapUi()
                            ensureSessionAttached()
                        } else {
                            showBootstrapUiOnly()
                        }
                    } catch (e: Throwable) {
                        com.emux.EmuXLog.e("distro pref change handling failed", e)
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // v0.1.3 CRASH FIX: wrap the ENTIRE startup path. Any exception thrown
        // here (missing resource, OEM view-inflation quirk, corrupt prefs…) used
        // to propagate straight out of onCreate → Android shows "EmuX keeps
        // stopping" and, because it happens on every launch, the app became
        // permanently unopenable. Now we degrade to a plain error screen that
        // still tells the user what happened and lets them retry/clear data.
        try {
            doOnCreate(savedInstanceState)
        } catch (e: Throwable) {
            com.emux.EmuXLog.init(this)
            com.emux.EmuXLog.e("MainActivity.onCreate failed", e)
            try {
                setContentView(android.widget.ScrollView(this).apply {
                    addView(android.widget.TextView(this@MainActivity).apply {
                        text = "EmuX failed to start:\n\n${e.javaClass.name}: ${e.message}\n\n" +
                            "You can try: Settings (in launcher) → EmuX → Storage → Clear Data,\n" +
                            "or reinstall the APK. Log tail:\n\n" + com.emux.EmuXLog.tail(40)
                        setTextIsSelectable(true)
                        setPadding(48, 96, 48, 48)
                        setTextColor(0xFFE8E8E8.toInt())
                        setBackgroundColor(0xFF101216.toInt())
                    })
                })
            } catch (_: Throwable) { /* nothing more we can do */ }
        }
    }

    private fun doOnCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        wireTabs()
        wireExtraKeys()
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(distroPrefListener)
        requestNotificationIfNeeded()
        observeSessions()
        // v0.1.3: session attach + root detection are deferred until AFTER the
        // first frame is drawn. Two reasons:
        //  a) TerminalService.startSession() from inside onCreate hit Android 12+
        //     background-FGS restrictions (see TerminalService fix);
        //  b) hasWindowFocus() is false during onCreate, so deferring guarantees
        //     the "app is visible" exemption applies when we do start services.
        postSafely(Runnable {
            if (!com.emux.core.FsLayout.isBootstrapped(this)) {
                // v0.1.27 PERSISTENCE FIX #3: the old code showed the Install UI
                // whenever isBootstrapped() was false — even when a COMPLETE rootfs
                // sat on disk with only the .env_version marker lost (backup
                // restore / OEM storage manager). The user then tapped Install and
                // watched the whole ~3 MB download+extract re-run "every time I
                // close and reopen the app". Re-stamp first; only a genuinely
                // missing/partial rootfs shows the banner.
                val restamped = runCatching {
                    com.emux.core.FsLayout.ensureMarkerForExistingRootfs(this)
                }.getOrDefault(false)
                if (restamped) {
                    com.emux.EmuXLog.i("rootfs intact, marker re-stamped at startup — skipping reinstall")
                    ensureSessionAttached()
                } else {
                    showBootstrapUiOnly()
                }
            } else {
                ensureSessionAttached()
            }
        })
        maybeAskBatteryExemption()

        // v0.1.53 FIELD FIX: one-shot background dpkg/apt repair for APT
        // distros. The field device ended up with a permanently broken
        // package manager (killed apt run + proot link(2) translation bug ->
        // "dpkg was interrupted" / "status-old: Permission denied"), and
        // bootstrap never re-runs on an already-stamped rootfs, so nothing
        // ever healed it: no sudo, no coreutils repair, no Xvfb -> black
        // desktop. Daemon thread; no-ops on healthy systems (3 File stats).
        runCatching { com.emux.core.BootstrapManager(applicationContext).startupRepairAsync() }

        // v0.1.9 CRASH FIX: click listeners run on the main looper OUTSIDE every
        // enclosing try/catch — any Throwable escaping startBootstrapNow (OEM ROM
        // quirks in WorkManager, missing provider, ...) was an instant force-close
        // exactly when Install was tapped. Belt-and-suspenders with the worker fix.
        b.btnInstall.setOnClickListener {
            try {
                // v0.1.47: an explicit tap is the user's confirmation to install
                // the SELECTED distro — including the switch case (another
                // distro already installed). See BootstrapManager's switch guard.
                com.emux.core.DistroCatalog.markSwitchConfirmed(this)
                startBootstrapNow(showUi = true)
            } catch (e: Throwable) {
                com.emux.EmuXLog.e("install click failed", e)
                runCatching {
                    b.bootstrapBar.visibility = android.view.View.GONE
                    b.bootstrapLabel.visibility = android.view.View.VISIBLE
                    b.bootstrapLabel.text = getString(
                        R.string.bootstrap_failed_reason,
                        "${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }

        lifecycleScope.launch {
            // v0.1.8: root.detect() must NEVER escape this coroutine — an
            // uncaught Throwable in a lifecycleScope launch goes straight to the
            // thread's UncaughtExceptionHandler and kills the process ("app keeps
            // stopping" right after opening the app / tapping Install).
            try { root.detect() } catch (e: Throwable) {
                com.emux.EmuXLog.e("root detect failed", e)
            }
            runCatching {
                // v0.1.30: show the running version in the top bar so the field
                // can confirm which build is actually installed (tap-installs of
                // a stale APK were suspected in the HarmonyOS report).
                val ver = runCatching { com.emux.EmuXLog.appVersion(this@MainActivity) }.getOrDefault("?")
                b.statusRoot.text = "v$ver · " + when (root.mode) {
                    com.emux.core.RootMode.NONE -> getString(R.string.root_none)
                    else -> getString(R.string.root_available, root.mode.name)
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(distroPrefListener)
        }
        super.onDestroy()
    }

    /**
     * v0.1.8 CRASH FIX: View.post bodies run on the main looper OUTSIDE every
     * enclosing try/catch (MainActivity.onCreate's doOnCreate guard had already
     * returned by then). Any throw inside it — corrupt prefs file, missing view,
     * OEM ROM quirk — was an instant uncaught crash. Now wrapped, logged, and
     * degraded gracefully.
     */
    private fun postSafely(r: Runnable) {
        b.root.post {
            try { r.run() } catch (e: Throwable) {
                com.emux.EmuXLog.e("post-frame task failed", e)
                runCatching {
                    b.bootstrapLabel.visibility = android.view.View.VISIBLE
                    b.bootstrapLabel.text = "Startup error: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
        }
    }

    /**
     * v0.1.28 FIX (the literal "wants to install every time I open the app"
     * symptom): hide the ENTIRE bootstrap pane on success. The old code hid
     * only the inner children (bar/label/button) while the pane itself —
     * with its static "Install the Linux environment (Alpine minirootfs,
     * ~3 MB)? Runs once…" prompt — stayed visible on every launch, even
     * fully-bootstrapped ones. The layout now defaults the pane to GONE and
     * only showBootstrapUiOnly() reveals it.
     */
    private fun hideBootstrapUi() {
        runCatching {
            b.bootstrapBar.visibility = android.view.View.GONE
            b.bootstrapLabel.visibility = android.view.View.GONE
            b.btnInstall.visibility = android.view.View.GONE
            b.bootstrapPane.visibility = android.view.View.GONE
        }
        refocusTerminal()
    }

    /**
     * v0.1.35 BUGFIX ("keyboard disappears after a short time"): every bootstrap
     * banner update changes pane visibility in this window, which on several OEM
     * IMEs dismisses the soft keyboard even though the terminal keeps focus.
     * Re-assert (forced) after each terminal bootstrap result so the user never
     * ends up staring at a shell with no input method.
     */
    private fun refocusTerminal() {
        runCatching { termView?.post { termView?.showKeyboard(true) } }
        // v0.1.45: a session born before helpers existed upgrades in-place now.
        runCatching { termView?.session?.upgradeToGuestPtyIfAvailable() }
    }

    /** Bootstrap banner without auto-starting anything (fresh install path). */
    private fun showBootstrapUiOnly() {
        b.bootstrapPane.visibility = android.view.View.VISIBLE
        b.btnInstall.visibility = android.view.View.VISIBLE
        // v0.1.47 MULTI-DISTRO: the pane names the SELECTED distro; when other
        // distros are already installed it explains the switch (they stay on
        // disk — installing one never deletes another).
        runCatching {
            val spec = com.emux.core.DistroCatalog.requested(this)
            val installed = com.emux.core.FsLayout.installedDistroIds(this)
                .map { com.emux.core.DistroCatalog.byId(it).label }
            if (installed.isEmpty()) {
                b.bootstrapPrompt.text =
                    getString(R.string.bootstrap_prompt_fresh, spec.label, spec.sizeHint)
                b.btnInstall.text = getString(R.string.bootstrap_install)
            } else {
                b.bootstrapPrompt.text = getString(
                    R.string.bootstrap_prompt_switch, spec.label, spec.sizeHint,
                    installed.joinToString(", "))
                b.btnInstall.text = getString(R.string.bootstrap_switch_install)
            }
        }
    }

    /**
     * v0.1.47: after a successful install/switch, sessions still running from
     * a DIFFERENT rootfs are restarted in-place (same TerminalSession object +
     * scrollback) so the new distro takes effect without a force-stop. A
     * running desktop is stopped; the next Desktop tab visit starts it fresh
     * against the active distro.
     */
    private fun restartStaleDistroSessions() {
        runCatching {
            val current = com.emux.core.FsLayout.linuxRoot(this).absolutePath
            var restarted = 0
            com.emux.terminal.TerminalService.sessions().value.forEach { s ->
                val lr = s.launchedRootfs
                if (lr != null && lr != current) { s.restart(); restarted++ }
            }
            if (restarted > 0 && desktop.state.value != com.emux.x11.DesktopService.State.IDLE) {
                desktop.stop()
            }
        }
    }

    private fun wireTabs() {
        b.tabTerm.setOnClickListener { showTerminal() }
        b.tabX11.setOnClickListener { showDesktop() }
        b.tabSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        showTerminal()
    }

    private fun showTerminal() {
        b.paneDesktop.visibility = android.view.View.GONE
        b.termContainer.visibility = android.view.View.VISIBLE
        b.tabTerm.isSelected = true; b.tabX11.isSelected = false
        ensureSessionAttached()
        // v0.1.25 BUGFIX: returning from the desktop tab left the terminal
        // unfocused (the desktop pane had eaten focus), so the IME connection
        // was never re-created and typing did nothing until a tap. Force
        // focus + keyboard on every switch back to the terminal.
        b.termContainer.findViewWithTag<VtTerminalView>("term")?.let { t ->
            t.requestFocus(); t.showKeyboard()
        }
    }

    private fun showDesktop() {
        b.termContainer.visibility = android.view.View.GONE
        b.paneDesktop.visibility = android.view.View.VISIBLE
        b.tabTerm.isSelected = false; b.tabX11.isSelected = true
        // v0.1.25 DIAGNOSTIC: paint the view once immediately. If this red
        // background does not appear, the problem is layout/z-order/size —
        // NOT the X pipeline. If it appears then turns dark, frames are
        // simply never arriving (check the status line error text).
        b.desktopView.debugFlashRed()
        // v0.1.54: a FAILED desktop (State.ERROR) must retry when the user comes
        // back to the tab - previously only IDLE started, so one bad attempt
        // (e.g. the apt-lock race with the startup repair pass) left the pane
        // showing a stale error forever ("desktop never launches").
        if (desktop.state.value == DesktopService.State.IDLE ||
            desktop.state.value == DesktopService.State.ERROR) {
            b.desktopStatus.text = getString(R.string.x_starting)
            // v0.1.28: set the UI callback BEFORE startDesktop — startDesktop
            // owns the single frame reader now (the old duplicate
            // beginFrameLoop() here raced with the internal one and lost).
            desktop.frameCallback = { runOnUiThread { b.desktopView.onNewFrame(desktop.frameId) } }
            desktop.startDesktop(wmCommand = prefsWm())
        } else {
            desktop.frameCallback = { runOnUiThread { b.desktopView.onNewFrame(desktop.frameId) } }
        }
        b.desktopView.desktop = desktop
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                desktop.state.collect { s ->
                    b.desktopStatus.text = when (s) {
                        DesktopService.State.IDLE -> ""
                        DesktopService.State.STARTING_X -> getString(R.string.x_starting)
                        DesktopService.State.CONNECTED ->
                            getString(R.string.x_connected, desktop.x11?.screenWidth ?: 0, desktop.x11?.screenHeight ?: 0) +
                                // v0.1.41 BUGFIX: the no-frames / input-disabled
                                // diagnostics live in errorText, but this
                                // collector only rendered it in ERROR state —
                                // the desktop status never showed WHY frames
                                // were missing ("waiting for X frames" forever).
                                (desktop.errorText.value?.let { "\n" + it } ?: "")
                        DesktopService.State.ERROR ->
                            // v0.1.55: show the LAST FEW failures, not just the
                            // newest - the pane used to overwrite each error, so
                            // a rotating failure sequence could never be caught
                            // in one screenshot.
                            getString(R.string.x_error, desktop.errorHistoryText())
                    }
                }
            }
        }
    }

    private fun prefsWm(): String =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .getString("wm", "openbox") ?: "openbox"

    /** Attach a live TerminalService session to the view, creating one if needed. */
    private fun ensureSessionAttached() {
        val view = termView ?: VtTerminalView(this).also {
            b.termContainer.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1))
            termView = it
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TerminalService.sessions().collect { list ->
                    val s = list.firstOrNull { it.id < 9000 && it.state.value != com.emux.terminal.TerminalSession.State.DEAD }
                        ?: run {
                            // v0.1.18 BUGFIX #3 ("keyboard dead, nothing typed, no
                            // new text"): the old filter was `id < 9000` ONLY. When
                            // the first session's child process died (denied su,
                            // missing launcher, …), its DEAD TerminalSession object
                            // stayed in the registry forever, so this collect block
                            // kept re-attaching the view to that corpse and NEVER
                            // started a fresh session. Every keystroke went to the
                            // dead process's closed stream (write() swallowed the
                            // exception) → literally zero bytes reached any shell:
                            // "cannot type", "no new text inside the interface".
                            // Now dead sessions are skipped and a replacement is
                            // spawned; pumpOutput prints the exit notice instead.
                            if (list.any { it.id < 9000 && it.state.value == com.emux.terminal.TerminalSession.State.DEAD }) {
                                view.feed("\n[emux] previous shell exited — starting a new one…\n".toByteArray())
                            }
                            TerminalService.startSession(this@MainActivity, "bash -l")
                            return@collect
                        }
                    // v0.1.31 BUGFIX (the persistent "terminal does not let me
                    // type" after a successful Install): sessions created while
                    // bootstrap was still running have NO PTY wrapper (script(1)
                    // not installed yet) and run on raw pipes — no echo, no
                    // prompt flush. Nothing ever upgraded them, so the keyboard
                    // stayed dead until an app force-stop even though the apk
                    // extras arrived minutes later. TtyWrap.setDistroCandidates()
                    // re-arms the probe when the components appear; if a PTY
                    // engine exists NOW and this session lacks it, restart the
                    // child in-place (same session object + scrollback).
                    if (!s.ptyWrapped) {
                        // Re-arm the wrapper probe first: sessions born before
                        // bootstrap cached a "no wrapper" verdict; setDistro-
                        // Candidates resets it now that script(1) may exist.
                        com.emux.terminal.TtyWrap.setDistroCandidates(this@MainActivity)
                        if (com.emux.terminal.TtyWrap.available() && ptyUpgradedSessions.add(s.id)) {
                            view.feed("\n[emux] PTY engine available now — restarting this shell with a real PTY…\n".toByteArray())
                            s.restart()
                        } else if (!com.emux.terminal.TtyWrap.available() &&
                            s.canGuestPty() && ptyGuestUpgraded.add(s.id)) {
                            // v0.1.55 FIELD FIX: on ROMs whose seccomp kills the
                            // host-side loader exec, TtyWrap.available() is
                            // ALWAYS false - the old upgrade path never fired
                            // and the shell stayed on raw pipes forever
                            // (pty=false, no echo, staircased output) even
                            // though the guest PTY engine (script inside proot)
                            // was available. Upgrade through it explicitly.
                            view.feed("\n[emux] guest PTY (script inside proot) available — restarting this shell with a real PTY…\n".toByteArray())
                            s.upgradeToGuestPtyIfAvailable()
                        }
                    }
                    // v0.1.55: raw-pipe sessions need ONLCR emulation in the
                    // renderer or every line staircases (no tty driver adds CR)
                    view.onlcr = !s.ptyWrapped
                    if (view.session !== s) {
                        view.session = s
                        watchLiveness(view, s)
                        // v0.1.41: unconditional attach marker. If THIS never
                        // appears on a field screenshot, the pump/render path
                        // itself is broken (not the child stream).
                        view.feed(("[emux] attached #" + s.id +
                            " pty=" + s.ptyWrapped +
                            if (s.ptyWrapped) "\r\n" else " (" + s.ptyReason + ")\r\n").toByteArray())
                    }
                    // v0.1.45 CRITICAL: the scrollback pump used to live inside
                    // pumpOutput's repeatOnLifecycle block, which is CANCELLED on
                    // pause and never re-created on resume when view.session===s
                    // — after returning to the app, session bytes never reached
                    // the view again (only direct feeds showed): "worked the
                    // first time, closed and came back = black". Re-arm the pump
                    // on EVERY collect pass (cancelling the stale one).
                    pumpJob?.cancel()
                    pumpJob = lifecycleScope.launch {
                        for (chunk in s.scrollback) view.feed(chunk)
                    }
                    // v0.1.25 DIAGNOSTIC: surface PTY availability in the
                    // terminal itself so users can report exactly which
                    // layer is broken without adb/logcat access.
                    val tty = com.emux.terminal.TtyWrap
                    // v0.1.37: guest-side PTY (script(1) inside proot) counts
                    // as a real PTY even though no host wrapper probes OK.
                    if (!tty.available() && !s.ptyWrapped) {
                        view.feed("\n[emux] WARNING: no PTY wrapper found on this ROM - typing will NOT echo and commands may hang. Install busybox or root the device.\n".toByteArray())
                    } else if (!tty.hasCtty && !s.ptyWrapped) {
                        view.feed("\n[emux] NOTE: PTY relay active but WITHOUT controlling terminal (toybox script). Ctrl-Z / background jobs may misbehave; normal typing works.\n".toByteArray())
                    }
                }
            }
        }
    }

    /**
     * v0.1.19 BUGFIX J: "Bootstrapped" + a RUNNING session is NOT proof bytes
     * flow. If the launcher exec'd something that dies silently, or stdio stays
     * buffered behind a pipe, the user sees a dead black screen and blames the
     * keyboard. Give every fresh session 12 s to produce its first output byte;
     * otherwise print an explicit diagnostic on-screen (and force one respawn
     * attempt through the normal death path by destroying the wedged child).
     */
    private fun watchLiveness(view: VtTerminalView, s: com.emux.terminal.TerminalSession) {
        lifecycleScope.launch {
            kotlinx.coroutines.withTimeoutOrNull(12_000) {
                while (currentCoroutineContext().isActive && !s.outputSeen.value) {
                    kotlinx.coroutines.delay(250)
                }
            }
            if (!s.outputSeen.value && s.ptyWrapped &&
                s.state.value == com.emux.terminal.TerminalSession.State.RUNNING) {
                val (head, tail) = synchronized(s.streamHead) {
                    s.streamHead.toString() to s.streamTail.toString()
                }
                view.feed(
                    ("\r\n[emux] dbg stream: " + head.take(250) +
                        "\r\n[emux] dbg tail: " + tail.take(250) +
                        "\r\n[emux] dbg view: " + view.dbgState() +
                        "\r\n[emux] shell produced no output for 12s — restarting it with diagnostics…\r\n").toByteArray()
                )
                // Destroying the child drives state→DEAD (exitedCleanly=false),
                // which the service's watcher turns into exactly one bounded
                // respawn — instead of an eternal silent zombie.
                runCatching { s.debugKillForDiagnostics() }
            } else if (!s.ptyWrapped && !s.outputSeen.value) {
                // v0.1.35 BUGFIX ("keyboard disappears after a short time"):
                // in DEGRADED mode (no PTY engine — bootstrap apk still failing)
                // an interactive shell over raw pipes may print NOTHING until
                // the first command runs (toybox sh writes no prompt to a pipe).
                // The 12 s watchdog therefore killed perfectly healthy idle
                // shells: respawn churn swapped the session under the user and
                // the IME connection died with each swap ("keyboard vanishes,
                // comes back when I type/tap again"). Only PTY-backed sessions
                // guarantee a prompt, so only they get the output watchdog.
            } else if (s.outputSeen.value) {
                // v0.1.22 DIAGNOSTIC BANNER: the user reported "i can write on
                // the terminal" but commands never ran / desktop stayed black.
                // Output flowing only proves the PTY relay works — whether the
                // shell has a CONTROLLING terminal (job control, foreground
                // commands completing) depends on which wrapper we resolved.
                // Print the exact engine once per session so device feedback
                // tells us precisely which layer to fix next.
                val tty = com.emux.terminal.TtyWrap
                val line = if (!tty.available() && !s.ptyWrapped) {
                    "[emux] WARNING: no PTY wrapper (script/busybox) found on this ROM — running with raw pipes; interactive shells may not work. Install Termux busybox or root.\n"
                } else if (!tty.hasCtty && !s.ptyWrapped) {
                    "[emux] PTY via toybox script (no controlling terminal): typing echoes, but Ctrl-Z and some job-control use cases are limited. Commands should still run.\n"
                } else {
                    ""
                }
                if (line.isNotEmpty()) view.feed(line.toByteArray())
                // v0.1.24 DIAGNOSTIC PROBE: every prior fix round was blind —
                // we could not tell whether the distro shell actually starts,
                // whether the musl loader execs, or which layer fails. Run one
                // harmless self-test through the SAME launcher chain and print
                // the verdict, so device feedback pinpoints the broken layer
                // instead of costing another guess-and-rebuild cycle.
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val probe = runCatching {
                        val env = com.emux.core.FsLayout.sessionEnv(this@MainActivity)
                        // v0.1.24: the probe script is shipped as an asset and
                        // copied to files/bin by bootstrap; read its body in
                        // Kotlin (plain file read — always allowed even on
                        // noexec mounts) and run it through Android sh -c,
                        // exactly like the session launcher does.
                        val probeFile = java.io.File(filesDir, "bin/emux-init-probe.sh")
                        val probeBody = runCatching { if (probeFile.isFile) probeFile.readText() else null }.getOrNull()
                            ?: return@runCatching ""
                        val pb = ProcessBuilder("/system/bin/sh", "-c", probeBody)
                        pb.environment().clear(); pb.environment().putAll(env)
                        pb.redirectErrorStream(true)
                        val p = pb.start()
                        val out = p.inputStream.bufferedReader().use { it.readText() }
                        val wd = Thread { try { Thread.sleep(8_000); p.destroy() } catch (_: Exception) {} }.apply { isDaemon = true }
                        wd.start(); p.waitFor(); wd.interrupt()
                        out
                    }.getOrDefault("[emux] probe failed to run\n")
                    val summary = buildString {
                        append("\n[emux] --- environment self-test ---\n")
                        if (probe.contains("PROOT_OK")) append("[emux]   proot (unrooted distro engine): OK\n")
                        else if (probe.contains("PROBE_PROOT_MISSING")) append("[emux]   proot: MISSING — tap Install to re-seed native bridge\n")
                        else append("[emux]   proot: FAILED\n")
                        if (probe.contains("SCRIPT_OK")) append("[emux]   PTY wrapper (util-linux script): OK\n")
                        else append("[emux]   PTY wrapper: MISSING — keyboard will not echo; tap Install\n")
                        if (probe.contains("BASH_OK")) append("[emux]   bash: OK\n")
                        else append("[emux]   bash: missing (busybox sh fallback)\n")
                        if (probe.contains("XVFB_OK")) append("[emux]   Xvfb (desktop): OK\n")
                        else append("[emux]   Xvfb: missing — desktop unavailable; enable desktop components in Settings and re-Install\n")
                        if (probe.contains("LOADER_OK")) append("[emux]   musl loader + busybox: OK\n")
                        else append("[emux]   musl loader/busybox: FAILED -> " + probe.lineSequence().filter { it.isNotBlank() && !it.startsWith("EMUXPROBE") }.take(3).joinToString(" | ") + "\n")
                        // v0.1.47: apk (Alpine) OR apt (Debian/Ubuntu) is the
                        // distro package manager — report whichever family applies.
                        when {
                            probe.contains("APK_OK") -> append("[emux]   package manager (apk): OK\n")
                            probe.contains("APT_OK") -> append("[emux]   package manager (apt): OK\n")
                            probe.contains("PROBE_NOPM") -> append("[emux]   package manager: NOT FOUND in rootfs\n")
                            else -> append("[emux]   package manager: cannot run\n")
                        }
                        // v0.1.30: raw platform-layer markers (SELinux domain,
                        // host exec permission, proot ptrace) — decisive for
                        // OEM-hardened ROMs (HarmonyOS field report).
                        probe.lineSequence()
                            .filter { it.startsWith("DOMAIN=") || it.startsWith("HOSTEXEC") ||
                                it.startsWith("PROOT_PTRACE") || it.startsWith("ROOTFS=") }
                            .forEach { append("[emux]   ").append(it).append("\n") }
                        append("[emux] ------------------------------\n")
                    }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { view.feed(summary.toByteArray()) }
                }
            }
        }
    }

    /** v0.1.45: current scrollback pump (see ensureSessionAttached). */
    private var pumpJob: kotlinx.coroutines.Job? = null

    private fun pumpOutput(view: VtTerminalView, s: com.emux.terminal.TerminalSession) {
        // v0.1.40 FIELD DIAGNOSTIC: one-shot dump of the child's raw stream head
        // (escaped) + renderer cursor/grid state, so a single screenshot shows
        // exactly what the session sent and where the cursor ended up when the
        // screen looks wrong.
        lifecycleScope.launch {
            try {
                var waited = 0
                while (currentCoroutineContext().isActive && !s.outputSeen.value && waited < 30_000) {
                    kotlinx.coroutines.delay(250); waited += 250
                }
                if (s.outputSeen.value) {
                    kotlinx.coroutines.delay(1500)
                    val (head, tail) = synchronized(s.streamHead) {
                        s.streamHead.toString() to s.streamTail.toString()
                    }
                    view.feed(("\r\n[emux] dbg stream: " + head.take(250) +
                        "\r\n[emux] dbg tail: " + tail.take(250) +
                        "\r\n[emux] dbg view: " + view.dbgState() + "\r\n").toByteArray())
                }
            } catch (_: Exception) { }
        }
        // NOTE: previously this collected `scrollback` — a Channel that *closes* when
        // the session dies. On a dead/closed channel `for (chunk in ch)` just falls
        // through immediately, so nothing was ever fed to the terminal and the screen
        // stayed blank ("nothing happens"). Now we keep pumping until the session
        // transitions to DEAD, and print an explicit notice on death/exit.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    if (s.state.value == com.emux.terminal.TerminalSession.State.DEAD) {
                        view.feed("[emux] session #${s.id} already exited (code ${s.exitCode}) — starting a new shell…\n".toByteArray())
                    } else {
                        s.state.collect { st ->
                            if (st == com.emux.terminal.TerminalSession.State.DEAD) {
                                // v0.1.18: show the real exit code + whether it was
                                // clean, instead of the old generic ENTER hint. The
                                // service auto-respawns abnormal deaths; a clean
                                // `exit` just ends the session visibly.
                                val why = if (s.exitedCleanly) "exited cleanly"
                                    else "exited abnormally (code ${s.exitCode})"
                                view.feed("\n[emux] process $why — ${if (s.exitedCleanly) "press ENTER or tap Install for a new shell." else "restarting…" }\n".toByteArray())
                            }
                        }
                    }
                }
            }
        }
    }

    private fun wireExtraKeys() {
        // v0.1.54: pgup/pgdn page the renderer scrollback locally.
        b.extraKeys.localScrollSink = { pagesUp ->
            runOnUiThread {
                b.termContainer.findViewWithTag<com.emux.terminal.VtTerminalView>("term")
                    ?.scrollPage(pagesUp)
            }
        }
        b.extraKeys.currentSessionSink = { bytes -> termView?.session?.write(bytes) }
    }

    private fun observeSessions() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TerminalService.sessions().collect { list ->
                    b.statusSessions.text = resources.getQuantityString(
                        R.plurals.sessions_count, list.size, list.size
                    )
                }
            }
        }
    }

    /** Kick the distro install (user pressed Install). */
    private fun startBootstrapNow(showUi: Boolean) {
        // v0.1.11 CRASH FIX ("bootstrap failed: NoClassDefFoundError"): every
        // androidx.work call in this file was STATIC — on ROMs where the work
        // runtime fails class resolution, enqueue/observe threw straight past
        // runCatching-less code into the looper and the UI printed the wrapper
        // name only. All W-M access now goes through BootstrapScheduling (fully
        // guarded reflection); if it reports unavailable we run the identical
        // bootstrap in-process via BootstrapRunner. Either way Install works.
        val wmOk = com.emux.core.BootstrapScheduling.ensureWork(this)
        if (wmOk) {
            com.emux.core.BootstrapScheduling.cancel(this, EmuXApplication.BOOTSTRAP_WORK)
        }
        val enqueued = wmOk && com.emux.core.BootstrapScheduling.enqueue(
            this, "emux-bootstrap-now", replace = true)
        if (!enqueued) {
            com.emux.EmuXLog.i("WorkManager unavailable/enqueue failed -> in-process bootstrap")
            com.emux.core.BootstrapRunner.run(applicationContext) { r ->
                // Render the terminal result into the same UI the W-M observer uses,
                // so fallback mode is indistinguishable to the user (except it works).
                runCatching {
                    b.bootstrapBar.isIndeterminate = false
                    if (r.isSuccess) {
                        restartStaleDistroSessions()
                        ensureSessionAttached()
                        hideBootstrapUi()
                    } else {
                        b.bootstrapLabel.text = getString(
                            R.string.bootstrap_failed_reason,
                            com.emux.core.BootstrapDiagnostics.describe(r.exceptionOrNull()))
                        b.bootstrapBar.visibility = android.view.View.GONE
                        b.btnInstall.visibility = android.view.View.VISIBLE
                        refocusTerminal()
                    }
                }
            }
        }
        if (showUi) watchBootstrapProgress()
        // v0.1.12: safety net for linkage errors that kill the W-M job outside
        // any code we control (worker superclass resolution). If after a grace
        // period the rootfs still isn't there, run it in-process instead.
        ensureBootstrapCompletes()
    }

    /**
     * v0.1.12: last-resort safety net. If WorkManager reported available but the
     * job nevertheless dies with a linkage error (e.g. BootstrapWorker's
     * CoroutineWorker superclass fails to resolve at instantiation time — which
     * happens OUTSIDE every try/catch we control), nothing else would ever run
     * the bootstrap. This re-checks after a grace period and, when the rootfs is
     * still missing, transparently runs the zero-dependency in-process path.
     */
    private fun ensureBootstrapCompletes() {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            kotlinx.coroutines.delay(8_000L)
            try {
                if (com.emux.core.FsLayout.isBootstrapped(applicationContext)) return@launch
                // v0.1.27: rootfs intact + marker lost → re-stamp, never re-download.
                if (runCatching { com.emux.core.FsLayout.ensureMarkerForExistingRootfs(applicationContext) }
                        .getOrDefault(false)) {
                    com.emux.EmuXLog.i("8s safety net: marker re-stamped for intact rootfs")
                    return@launch
                }
                if (com.emux.core.BootstrapRunner.isRunning()) return@launch
                com.emux.EmuXLog.i("bootstrap not completed via W-M -> in-process retry")
                com.emux.core.BootstrapRunner.run(applicationContext) { r ->
                    runCatching {
                        b.bootstrapBar.isIndeterminate = false
                        if (r.isSuccess) {
                            restartStaleDistroSessions()
                            ensureSessionAttached()
                            hideBootstrapUi()
                        } else {
                            b.bootstrapLabel.text = getString(
                                R.string.bootstrap_failed_reason,
                                com.emux.core.BootstrapDiagnostics.describe(r.exceptionOrNull()))
                            b.bootstrapBar.visibility = android.view.View.GONE
                            b.btnInstall.visibility = android.view.View.VISIBLE
                        }
                    }
                }
            } catch (e: Throwable) {
                com.emux.EmuXLog.e("ensureBootstrapCompletes failed", e)
            }
        }
    }

    /** Last-resort path when WorkManager itself can't load/run on this device. */
    private fun startInProcessFallback() {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val mgr = com.emux.core.BootstrapManager(applicationContext)
            val r = runCatching { mgr.bootstrap() }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                b.bootstrapBar.isIndeterminate = false
                if (r.getOrNull()?.isSuccess == true) {
                    restartStaleDistroSessions()
                    ensureSessionAttached()
                    hideBootstrapUi()
                } else {
                    b.bootstrapLabel.text = getString(
                        R.string.bootstrap_failed_reason,
                        com.emux.core.BootstrapDiagnostics.describe(
                            r.exceptionOrNull() ?: RuntimeException("in-process fallback failed")))
                    b.bootstrapBar.visibility = android.view.View.GONE
                    b.btnInstall.visibility = android.view.View.VISIBLE
                    refocusTerminal()
                }
            }
        }
    }

    /** Observe the BootstrapWorker's progress; render bar + real errors. */
    private fun watchBootstrapProgress() {
        b.bootstrapBar.visibility = android.view.View.VISIBLE
        b.bootstrapBar.isIndeterminate = true
        b.btnInstall.visibility = android.view.View.GONE
        b.bootstrapLabel.visibility = android.view.View.VISIBLE
        b.bootstrapLabel.text = getString(R.string.bootstrap_starting)
        lifecycleScope.launch {
            try {
                // (a) step/pct/error mirror: BootstrapReporter publishes into a
                // static LiveData fed by the worker (survives activity recreation
                // because the worker re-publishes on restart).
                BootstrapReporter.live.observe(this@MainActivity) { data ->
                    val step = (data[BootstrapReporter.KEY_STEP] as? String) ?: ""
                    val pct = (data[BootstrapReporter.KEY_PCT] as? Int) ?: -1
                    val err = data[BootstrapReporter.KEY_ERROR] as? String
                    if (err != null) {
                        b.bootstrapLabel.text =
                            getString(R.string.bootstrap_failed_reason, err)
                        b.bootstrapLabel.visibility = android.view.View.VISIBLE
                        b.bootstrapBar.visibility = android.view.View.GONE
                        b.btnInstall.visibility = android.view.View.VISIBLE
                        refocusTerminal()
                        refocusTerminal()
                    } else if (step.isNotEmpty()) {
                        b.bootstrapLabel.text = step
                        b.bootstrapLabel.visibility = android.view.View.VISIBLE
                        // v0.1.40 BUGFIX: worker-driven successes only arrive as
                        // progress frames here; the bootstrap pane then stayed
                        // visible forever ("Bootstrapped" + full bar over a
                        // working terminal). Hide it and attach the session.
                        if (step == "Bootstrapped" || pct == 100) {
                            restartStaleDistroSessions()
                            hideBootstrapUi()
                            ensureSessionAttached()
                            // v0.1.57 FIELD FIX ("after Install I must close and
                            // reopen the app"): sessions born before bootstrap
                            // finished stay on raw pipes with the pre-install
                            // env until something restarts them. The collector
                            // below only re-runs when the session LIST changes,
                            // which a finished bootstrap does not trigger - so
                            // upgrade every live session to the guest PTY now.
                            runCatching {
                                for (s in com.emux.terminal.TerminalService.sessions().value) {
                                    if (s.id < 9000 && !s.ptyWrapped) {
                                        s.upgradeToGuestPtyIfAvailable()
                                    }
                                }
                            }
                            refocusTerminal()
                        }
                        b.bootstrapBar.isIndeterminate = pct < 0
                        if (pct >= 0) b.bootstrapBar.progress = pct
                    }
                }
                // (b) terminal state comes from WorkInfo itself — v0.1.11: only
                // when androidx.work actually linked; otherwise the in-process
                // BootstrapRunner result drives the UI via BootstrapReporter.
                if (com.emux.core.BootstrapScheduling.workAvailable) {
                    com.emux.core.BootstrapScheduling.progressLiveData(
                        this@MainActivity, "emux-bootstrap-now"
                    )?.observe(this@MainActivity) { raw ->
                        val infos = raw as? List<*> ?: return@observe
                        val info = infos.firstOrNull() ?: return@observe
                        val state = info.javaClass.getMethod("getState").invoke(info).toString()
                        when (state) {
                            "SUCCEEDED" -> {
                                hideBootstrapUi()
                                ensureSessionAttached()
                            }
                            "FAILED", "CANCELLED", "BLOCKED" -> {
                                b.bootstrapBar.visibility = android.view.View.GONE
                                if (b.bootstrapLabel.text.isNullOrEmpty() ||
                                    b.bootstrapLabel.text == getString(R.string.bootstrap_starting)) {
                                    b.bootstrapLabel.text =
                                        getString(R.string.bootstrap_failed_reason, "worker $state")
                                }
                                b.bootstrapLabel.visibility = android.view.View.VISIBLE
                                b.btnInstall.visibility = android.view.View.VISIBLE
                                refocusTerminal()
                            }
                            else -> { /* ENQUEUED/RUNNING — keep showing the bar */ }
                        }
                    }
                }
            } catch (e: Throwable) {
                com.emux.EmuXLog.e("progress observer failed", e)
                b.bootstrapBar.visibility = android.view.View.GONE
                b.btnInstall.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun ensureWorkManager() {
        // v0.1.10: route through EmuXBootstrapApp so the availability flag is set;
        // MainActivity uses this to decide whether WorkManager observation is even
        // possible on this device (some OEM ROMs fail androidx.work linkage).
        com.emux.core.EmuXBootstrapApp.workManagerAvailable(application)
    }

    // ------------------------------------------------------- permissions glue
    private fun requestNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** One-tap battery optimization exemption — the single biggest Termux-parity win. */
    private fun maybeAskBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                @Suppress("BatteryLife")
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) { /* OEM without the screen: harmless */ }
            }
        }
    }

    // -------------------------------------------------------- power lifecycle
    override fun onResume() {
        super.onResume()
        desktop.foreground = true
        b.termContainer.findViewWithTag<VtTerminalView>("term")?.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        desktop.foreground = false   // drops frame rate to idle instantly
    }

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            desktop.screenOn = i?.action != Intent.ACTION_SCREEN_OFF
        }
    }

    override fun onStart() {
        super.onStart()
        // v0.1.3 CRASH FIX: on Android 14+ (targetSdk 34) registering a receiver
        // for SCREEN_ON/SCREEN_OFF requires the RECEIVER_NOT_EXPORTED flag —
        // without it, registerReceiver throws SecurityException here and this ran
        // on EVERY launch → instant "app keeps stopping" crash loop.
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                    screenReceiver,
                    android.content.IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
                    },
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                registerReceiver(
                    screenReceiver,
                    android.content.IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
                    }
                )
            }
        } catch (e: Throwable) {
            com.emux.EmuXLog.e("screen receiver registration failed (non-fatal)", e)
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(screenReceiver) }
        super.onStop()
    }
}
