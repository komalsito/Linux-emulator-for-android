plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.emux"

    // v0.2.88 BUILD FIX: compileSdk was never specified in this repo's
    // build file, and AGP 8.5.2 hard-fails project configuration without it
    // ("compileSdkVersion is not specified", manual run #3). 34 is the
    // correct level: every dependency in use requires it (androidx.core
    // 1.13.x, appcompat 1.7.0, fragment 1.8.x) and AGP 8.5.x officially
    // supports compileSdk 34.
    compileSdk = 34

    // No NDK/C++ build here: EmuX runs a *pure-Android* terminal core
    // (com.termux:terminal-emulator + com.termux:view) — same engine Termux uses,
    // so PTY handling and rendering cost the same as Termux.
    defaultConfig {
        applicationId = "com.emux"
        minSdk = 24
        // v0.1.28 ARCHITECTURE FIX (the root cause behind "keyboard dead" and
        // "distro never runs"): Android 10+ denies execve()/mmap(PROT_EXEC) of
        // ANY file in app-private storage for apps targeting SDK 29+ (W^X —
        // SELinux untrusted_app domain; see developer.android.com/about/versions/
        // 10/behavior-changes-10 and termux-app issue #1072 — the exact reason
        // Termux ships targetSdk 28). With targetSdk 34 the whole Alpine rootfs
        // was inert ballast: every loader/busybox/apk/Xvfb exec died EACCES.
        // Targeting 28 restores exec of app-data files (untrusted_app_27
        // domain) on Android 7–15, making the musl-loader chain, apk, proot and
        // the X11 desktop actually runnable unrooted. Trade-off: not
        // distributable via Play Store — this app ships as a sideloaded APK
        // (adb install / GitHub Releases), same distribution model as Termux
        // on F-Droid.
        targetSdk = 28
        // vc216 (ENV_VERSION 96 -> 97): CENTRED DOCK RESTORED - "mate center bar" meant
        // the centred EmuX tint2 dock (v0.2.114), not MATE's full-width panel: the mate-panel
        // taskbar + layout seeding from vc215 are retired, the centred auto-hiding dock is
        // the taskbar again (bottom-edge poke reveals it), and a Settings launcher
        // (mate-control-center) joins the dock beside the jgmenu start menu - the
        // "settings menu from mate" the user missed.
        // vc215 (ENV_VERSION 95 -> 96): MATE-PANEL TASKBAR - the real mate-panel is the MATE
        // taskbar again: full-width always-visible bottom bar (menu bar, window list, tray,
        // clock; emux.layout, auto-hide off - field: "the normal taskbar (mate center bar)"),
        // with the tint2 taskbar as the verified fallback (mate-panel/window-list applets
        // missing or two quick deaths in a session -> tint2 bar). The tint2 config itself
        // becomes the full-width always-visible taskbar (autohide off, strut on: full-edge
        // bar = no wallpaper band under maximized windows).
        // vc214 (ENV_VERSION 94 -> 95): SLIM LEFT RAIL - a third tint2 panel joins the centred dock:
        //     emux-rail.tint2rc, a ~40px vertical rail on the left edge (clock at the top,
        //     one switcher section per desktop below: click an empty section to switch
        //     desktop, drag a task between sections to move its window; autohide with
        //     a 2px reveal strip, no strut - same contract as the dock). The slim native
        //     replacement for the wide vertical tint2 themes the field user installed,
        //     which had no taskbar and no start button (no way to open Settings). The
        //     tint2 stamp v114 re-seeds configs and reclaims a user-replaced tint2rc
        //     (backed up as .emux-bak/.pre-rice), so the taskbar + start menu return.
        // v0.2.94 (vc194): BROWSER ICON FIX (re-seed, ENV_VERSION 80 -> 81).
        // Field: firefox-esr installed via the Mozilla Team PPA (the only
        // way on Ubuntu noble arm64 - no ESR in Ubuntu, no arm64 tarballs
        // from Mozilla, snap impossible in proot), but the desktop browser
        // icon either did not exist or silently did nothing.
        //  1. emux-x11's launcher scan did not know `firefox-esr` - an
        //     ESR-only rootfs seeded no browser icon at all.
        //  2. Where a `firefox` (Ubuntu snap-stub) existed, the seeded
        //     Exec pointed at a stub that can never start in proot.
        //  3. Firefox in proot needs MOZ_DISABLE_CONTENT_SANDBOX=1 (its
        //     seccomp sandbox cannot work under proot); the seeded menu
        //     and launcher now both pass it.
        // The launcher is now named after the browser that runs (Firefox
        // ESR, real firefox-esr icon).
        // LATE ADDITION (same vc194 - INPUT ANTI-RACE): proofs that warp
        // the pointer or map the red probe window now wait for a
        // user-quiet window, and the rc.xml/WM ladder never escalates
        // mid-gesture. Field: rapid interaction made the button proof
        // fail spuriously (input then stayed broken; clicks sometimes
        // landed on the red probe window). DesktopService only;
        // ENV_VERSION stays 81.
        // v0.2.93 (vc193): ROUND-TRIP SERIALIZATION + REMOTE-ECHO PROBE.
        // Field (v0.2.92): proof stayed yellow, input felt SLOWER than the
        // previously fixed version, terminal typed double (2 glyphs visible,
        // 1 byte read by the shell).
        //  1. X11Client: requestReplyFull read packets until ITS sequence
        //     matched and queued everything else as events - two concurrent
        //     round-trips on ONE connection stole each other's replies. The
        //     capture-plane proof's QueryPointer raced the capture loop's
        //     ~10/s GetImage grabs on the native client and lost every time,
        //     so capInputOk was never set (proof=pending, input stuck on the
        //     per-event xdotool agent = the slowness). roundTripLock now
        //     serializes every socket read: one round-trip in flight, no
        //     cross-consumption. drain/collectDamage QUEUE non-damage packets
        //     instead of dropping them, so a probe's ButtonPress survives the
        //     capture drain.
        //  2. DesktopService: failed capture-plane proofs narrate themselves
        //     (read=... in the note history) and retry with exponential
        //     backoff (15 s .. 2 min) instead of every 15 s.
        //  3. VtTerminalView: the v0.2.80 bare-LF detector can flip
        //     noLineDiscipline on a REAL pty (local echo ON next to the tty's
        //     own echo = the double). New remote-echo probe: locally-echoed
        //     printable bytes are matched against incoming session bytes; two
        //     consecutive full matches prove the tty echoes, the matched copy
        //     is consumed and local echo turns off for the session.
        // No guest script change: ENV_VERSION stays 80, no re-seed needed.
        // v0.2.92 (vc192): CAPTURE-PLANE INPUT + RECONNECT VISIBILITY.
        // Field (18:15, v0.2.91): interaction worked for the first
        // seconds, then died - cursor moved (agent motion landed) but
        // no click reacted, while the badge read proof=pending
        // (yellow) forever: the control-plane reconnect kept failing
        // INVISIBLY, input rode the guest xdotool agent, and the
        // button-delivery proof + its rc.xml/WM-switch escalation
        // ladder (the proven click-swallow remedy) NEVER RAN - it only
        // ever ran over a control-plane connection. (1) X11Client now
        // reserves the reply sequence number and the write atomically
        // (a concurrent fake-input write could slip between
        // reservation and send and desync reply matching); (2) when
        // the control plane is down and frames flow, the app proves
        // XTEST over the CAPTURE client (the identity-guaranteed
        // server) and injects through it - and runs the button proof
        // + ladder there too; (3) every failed reconnect is counted
        // and named on the badge (rxfail=/ctrlErr=), proof=motion-ok
        // paints yellow (measuring) not red, and the INPUT line names
        // the capture plane when it carries input. No guest script
        // changed - ENV_VERSION stays 80; existing installs do not
        // re-seed.
        // v0.2.91 (vc191): SELF-AIMING PLANES. The v0.2.90 field round
        // showed the guest can be perfectly healthy (one Xvfb on :1,
        // openbox up, marker published) while the app sat on a dying :0
        // attempt forever: (1) a failed control-plane reconnect was
        // never retried, so input died for good; (2) the capture loop
        // only re-aimed when the 3 s rotation watcher moved displayNum;
        // (3) the 5 s display-mismatch check trusted /tmp/emux_cap_display,
        // hours stale with the legacy helper retired. Fixes: the native
        // capture loop re-reads /tmp/emux_display_num on EVERY reconnect;
        // the reconnect retries until connected; the 5 s watchdog re-aims
        // from the guest marker too (the loop proven to run - it ticks
        // the badge capage). With v0.2.90 identity guard above them,
        // every plane converges on the server the guest actually runs.
        // v0.2.90 (vc190): THE STALE-INPUT-SERVER FIX. Field (v0.2.89
        // tablet, force-stop + relaunch): the Xvfb attempt ladder leaked
        // live failed attempts on :0 (retire_own_xvfb "serving" keep-
        // checks preserve anything that listens), and the next session
        // input control plane connected to one of those zombies - green
        // badge, proof ok, fail=0, while every tap landed on a bare-root
        // server nobody streams; the real desktop on :1 never reacted
        // (pointer pinned at center, xdotool-verified). (1) emux-x11 now
        // retires a failed attempt unconditionally - a server the ladder
        // abandoned is a stray by definition; adoption before the ladder
        // is untouched. (2) X11Client exposes displayNum + resIdBase
        // (setup-reply resource-id-base, unique per server instance) and
        // DesktopService compares the input plane identity with the
        // capture plane every 5 s and inside every reconnect attempt -
        // a mismatch recycles the control plane onto the server the user
        // actually sees. ENV_VERSION 80 re-seeds the guest script.
        // v0.2.89 (vc189): (1) emux-x11 governor now self-heals a WM-less
        // desktop - it checks/installs openbox/xterm/x11-apps (was furniture-
        // only, single attempt) and relaunches a dead/never-started WM, so
        // windows regain decorations and tint2 lists tasks + a Terminal
        // launcher (field: fresh Ubuntu rootfs, HUAWEI MRDI-W09, no WM);
        // (2) the desktop helper key bar and its top-right "keys" toggle are
        // removed, and the terminal extra-keys row is removed app-wide
        // (field request; hardware-keyboard paths unchanged);
        // (3) verify_apk accepts aapt2's renamed minSdkVersion badging
        // field (the false FAIL on the v0.2.88 build).
        // ENV_VERSION 79 re-seeds the guest script on existing installs.
        // v0.2.88 (vc188): (1) emux-x11 furniture detection is argv-exact
        // now (running0) and tint2's launch is verified with a strike
        // fallback, so a first boot no longer eats its own taskbar to an
        // apt-install cmdline match; (2) DesktopView paints a "Linux desktop
        // is starting" card with the live diagnostic tail while frames
        // stream black (first-boot apt churn), dismissed by any touch.
        // ENV_VERSION 78 re-seeds the guest script on existing installs.
        // (3) Settings selector rows now carry explicit click listeners
        // opening the framework dialog directly - the v0.2.87
        // onDisplayPreferenceDialog override alone was still ripple-only on
        // the field ROM.
        // v0.2.87 (vc187): host-side fixes only - (1) TerminalService's manifest
        // foregroundServiceType now also declares dataSync, making the fallback
        // the FOREGROUND_SERVICE_DATA_SYNC permission has always promised legal
        // on API 29-33 (the v0.2.85 field log tail: "startForeground refused
        // type=1"); (2) Settings selectors open through a framework AlertDialog,
        // because the androidx DialogFragment + appcompat chain renders nothing
        // on the field HUAWEI ROM (click ripple, no selection UI). ENV_VERSION
        // stays 77: no guest script changed, so existing installs must not
        // re-seed.
        // v0.2.95 (vc195): TRACKPAD TWO-FINGER SCROLL FIX (DesktopView only;
        // ENV_VERSION stays 81: no guest script changed, no re-seed).
        // Field (v0.2.94, MatePad trackpad, scroll options on): a two-finger
        // scroll moved the pointer and SELECTED everything under it instead
        // of scrolling. Two stacked defects, both in the view:
        //  1. xButtonForBits defaulted "no button bits" to X button 1, so a
        //     trackpad DOWN with buttonState 0 (finger contact, not a click)
        //     pressed button 1 and the MOVE that followed drag-selected.
        //     A button-less DOWN now presses nothing; its MOVE is hover.
        //  2. isWheelInput routed ACTION_SCROLL through isMouseInput's v0.2.86
        //     touchscreen-device veto, so a trackpad whose InputDevice also
        //     reports SOURCE_TOUCHSCREEN had every scroll event dropped.
        //     Wheel events are now accepted on source alone (MOUSE or
        //     TOUCHPAD): a touchscreen two-finger drag never arrives as
        //     ACTION_SCROLL, so no finger gesture can be misrouted here.
        // A refused scroll is now noted once per session (source/tool/device)
        // instead of vanishing silently.
        // v0.2.96 (vc196): TRACKPAD CONTACT VOCABULARY + PROOF-GATE
        // LIVENESS (DesktopView/DesktopService; ENV_VERSION stays 81: no
        // guest script changed, no re-seed). Field (v0.2.95): two-finger
        // scroll did not scroll, inputs felt sloppy/slow. The cover
        // trackpad sends its gestures as BUTTONLESS mouse streams:
        //  1. The v0.2.95 contact fix left that stream no vocabulary - a
        //     tap did nothing, a two-finger scroll glided the pointer. The
        //     view now synthesizes trackpad semantics host-side: tap=click,
        //     tap-then-glide=drag, second pointer=scroll (44px/notch, the
        //     natural-scroll setting), two-finger tap=right click. Any
        //     ACTION_SCROLL is a wheel now (the ROM can tag the trackpad
        //     touchscreen-class), and mouse motion runs at 60/s not 30/s.
        //  2. The anti-race quiet gates were one-shot ABORTS: an active
        //     user aborted the capture proof (input rode the per-event
        //     xdotool agent - the slowness) and the button proof/ladder
        //     deferrals never re-ran at all. All gates now PARK until the
        //     first pause; the ladder re-confirms a raced failure in the
        //     quiet window before mutating the desktop.
        // v0.2.97 (vc197): SCROLL EVIDENCE ROUND + MERGED-STREAM SCROLL
        // (DesktopView only; ENV_VERSION stays 81). Field (v0.2.96): the
        // proof-gate liveness and speed fixes worked, but two-finger trackpad
        // scroll STILL does not scroll - the ROM delivers the gesture in a
        // shape no path recognises. Two-pronged:
        //  1. EVIDENCE: the pane note history logs ONE line per DISTINCT
        //     input event shape of the session (action, source, pointerCount,
        //     gesture-classifier verdict, button bits, device name) at every
        //     entry point - finger path, mouse path, hover, wheel - plus the
        //     input device inventory on the first frame. The next screenshot
        //     names exactly what the cover trackpad sends for each gesture.
        //  2. CANDIDATE FIX, guarded: many touchpad firmwares merge a
        //     two-finger swipe into ONE moving point (no second pointer, no
        //     ACTION_SCROLL - which is why v0.2.96's pair synthesis never
        //     armed). Android's gesture classifier tags such streams
        //     AMBIGUOUS_GESTURE - its documented two-finger-swipe hint
        //     (API 29+). That tagged delta is now the wheel, quantised into
        //     the same accumulators/burst as every other wheel path, on both
        //     the contact and the hover shape; a quick tagged lift is a
        //     right-click (two-finger tap).
                // v0.2.98 vc198: trackpad scroll synthesized from the held-button
        // hover stream; generic BUTTON_PRESS deferred; evidence key +btn
                // v0.2.99 vc199: ROUTING FIX - the pad's TOOL_TYPE_FINGER contact
        // stream is mouse-routed by DEVICE (no TOUCHSCREEN bit), not toolType;
        // the two-finger scroll reaches the v0.2.96 contact model at last
                // v0.2.100 vc200: device-first routing is TOOLTYPE-BLIND (v0.2.99's FINGER
        // guess was never verified by the log); toolType joins the evidence key;
        // POINTER_DOWN takes our own drag-lock press back for the wheel
        // v0.2.101 vc201: INSTALLED-DESKTOP SUPPORT. Field: 'sudo apt install
        // ubuntu-desktop' changed nothing - the desktop was hardcoded to the
        // seeded openbox+tint2+pcmanfm look. DesktopService now picks the WM
        // by what exists in the active rootfs (gnome-session > startxfce4 >
        // openbox); emux-x11 (ENV_VERSION 82 re-seed) launches the chosen
        // session with dbus-launch, skips the EmuX furniture when a full
        // session owns the desktop, and falls back to openbox if the session
        // cannot stand up under proot+Xvfb (GNOME Shell software-renders -
        // Xfce is the realistic choice). No input changes this round.
        // v0.2.102 vc202: THE SCROLL FIX + DESKTOP CHOICES.
        // Field log (vc200) proved the v0.2.99/v0.2.100 device-first rule
        // NEVER FIRED ONCE: its touchscreen veto was '(sources and
        // SOURCE_TOUCHSCREEN) == 0' - a partial-bit test; SOURCE_TOUCHSCREEN
        // is 0x1002 and every pointer device carries the 0x2 pointer-class
        // bit, so the test failed for the pad (sources 0x1002313, src=0x2002
        // SOURCE_MOUSE, tt=FINGER) and its two-finger scroll kept routing to
        // the finger machine as a drag. Both sites now demand the FULL mask
        // (supportsSource semantics) - pad contact/scroll reaches the v0.2.96
        // contact model at last. Sessions: emux-x11 exports
        // XDG_SESSION_TYPE=x11 (GNOME/mutter defaulted to Wayland and died on
        // the Xvfb - the vc201 openbox fallback), and detection grows
        // mate-session/startlxqt/lxsession (ENV_VERSION 83 re-seed).
        // v0.2.103 vc203: LADDER DEMOTION + EVIDENCE PRESERVATION. Field
        // (vc202, healthy session): the button-delivery probe reported its
        // KNOWN false-DEAD artifact, the escalation ladder's stage 2 killed a
        // healthy openbox, found no candidate WM (ubuntu-desktop ships
        // mutter, not twm/icewm/marco/...) and left the session WM-less -
        // wm=none, 40 MB/s full-frame repaint flood, "everything very
        // laggy". Stage 2+ now only LOGS (stage 1 reconfigure stays), and a
        // session WM that dies at launch preserves its stderr in
        // /tmp/wm_session.log before the openbox fallback truncates wm.log.
        // Field-CONFIRMED this round: the vc202 routing mask fix works - pad
        // contact events now log [mouse] and reach the v0.2.96 contact model.
        // v0.2.104 vc204: TERMINAL-DISPLAY HINT (ENV_VERSION 85 re-seed).
        // Field (vc203 screenshot): every X command from the Terminal tab
        // died with "Can't open display: (null)". Three stacked causes:
        //  1. sessionEnv pins DISPLAY=:0 host-side, but the desktop display
        //     ROTATES on stale-socket occupation (v0.2.5) - the terminal
        //     pointed at a display nothing served.
        //  2. the field Xvfb is TCP-ONLY (the -nolisten unix ladder) and
        //     this libX11 stack does NOT fall back to TCP for a bare ":N"
        //     (the guest script's own v0.2.13 note) - even a correct ":N"
        //     could not connect; the guest session code already exports
        //     127.0.0.1:$DN internally for exactly this reason.
        //  3. consequence: NO guest-side diagnostic (the xdotool wheel
        //     test, xdpyinfo, wmctrl, GUI app launches) could ever run.
        // emux-x11 now publishes the VERIFIED post-rotation display (unix
        // form when the socket exists, TCP form otherwise) to
        // /etc/profile.d/emux-display.sh at every desktop start; login
        // shells (bash -l -> /etc/profile -> profile.d) export it, and the
        // app-side DISPLAY=:0 stays only as the pre-desktop fallback.
        // v0.2.105 vc205: TOUCHPAD SCROLL + HARDLINK FIX (ENV_VERSION 86 re-seed).
        //  1. Android 14+ delivers a touchpad two-finger swipe as ONE fake
        //     finger classified CLASSIFICATION_TWO_FINGER_SWIPE (3); every
        //     wheel path keyed on AMBIGUOUS_GESTURE (1), so the swipe became a
        //     left-drag (text selection). DesktopView.onTouchEvent now routes
        //     classification 3 straight to the wheel (anchored at the cursor)
        //     and swallows 3-finger swipes / touchpad pinches.
        //  2. No proot launch used --link2symlink, so every package shipping a
        //     hardlink pair died in dpkg (field: perl-base 5.38.2 "error
        //     setting ownership of /usr/bin/perl5.38.2.dpkg-new: No such file
        //     or directory" while installing MATE). Android forbids hardlinks
        //     in app storage; the linkfix shim's link()->linkat() cannot create
        //     one either. All proot argv (app + guest scripts) now pass it.
        // v0.2.106 vc206: POINTER-MODE SWIPE SCROLL + INSTALLED DESKTOP +
        // ZERO-COPY CAPTURE (ENV_VERSION 87 re-seed).
        //  1. Scroll root cause: on Android <= 13 a pointer-mode touchpad
        //     (HUAWEI Glide Keyboard) reports the everyday two-finger scroll as
        //     PointerGesture SWIPE = ONE buttonless pointer moving from the
        //     cursor; only wide-apart fingers (FREEFORM) give POINTER_DOWN.
        //     That contact glided the pointer. It now drives the wheel,
        //     anchored at the cursor (setting trackpad_swipe_scroll, default on).
        //  2. Desktop: the persisted legacy "wm"=openbox always overrode the
        //     installed-desktop detection; new key desktop_session=auto picks
        //     MATE > Xfce > LXQt > LXDE > GNOME (GNOME last: it cannot run on
        //     Xvfb). MATE starts with NO_AT_BRIDGE, compositor + lock off.
        //  3. Capture: damage bands are read from the memory-mapped Xvfb
        //     -fbdir framebuffer instead of GetImage over the proot socket;
        //     the healer sweep cross-checks it and drops it on disagreement.
        //  4. Stale pixels: diffUpload compared narrow bands against the
        //     mirror with the wrong stride and could skip real changes.
        //  5. Status line: the errorText collector was unreachable after an
        //     endless StateFlow.collect, and each Desktop-tab tap leaked
        //     another collector.
        // v0.2.107 vc207: MATE THAT WORKS UNDER PROOT (ENV_VERSION 88 re-seed).
        // Field vc206 ps/wm.log: TWO mate-session trees (start_wm has four call
        // sites and never checked for a running WM), marco never running, and
        // caja crash-looping on /proc/self/mountinfo (EPERM under proot).
        // start_wm is idempotent; MATE runs with Openbox as its WM (the
        // click-safe rc.xml) and without caja in its required set; the
        // background comes from mate-settings-daemon. Terminal: app banners
        // are CRLF-normalised (the "staircase" self-test block).
        // v0.2.108 vc208: RESTART = FORCE STOP, CENTRED TASKBAR, HIGH-RES
        // (ENV_VERSION 89 re-seed).
        //  1. Every desktop process carries EMUX_DESKTOP_ID; stop/start kill
        //     every marked process of earlier sessions (MATE trees, session
        //     buses, double-forked daemons) - field: a stale desktop survived
        //     "Restart desktop" and only Force stop cured it.
        //  2. Centred taskbar: full-width tint2 tray bar (strut, tray, clock)
        //     + shrinking tint2 dock centred on top (start menu via jgmenu,
        //     pinned apps, icon-only windows); also MATE's panel.
        //  3. Resolution up to the device's own (Retina = native pixels, UI
        //     2x via Xft.dpi/GDK_SCALE/MATE scaling); healer sweeps read the
        //     validated mapped framebuffer, 1 in 8 still cross-checks the socket.
        // v0.2.109 vc209: TRUE COLOR + INPUT IDENTITY FIX (ENV_VERSION 90).
        //  1. 24-bit desktop (default; Settings > Desktop colors): Xvfb depth
        //     24, XRGB8888 end to end (grab, -fbdir map, mirror, ARGB_8888).
        //  2. The Huawei settings tier (CodePrefs) finally offers Retina.
        //  3. Server identity: res-id-base is per CLIENT, so the v0.2.90 guard
        //     rejected every healthy control plane -> input on the busy capture
        //     socket / slow agent. Now an atom token interned by the capture.
        //  4. Button probe (maps + clicks a window: closed open menus) runs once
        //     per server after 8 s idle instead of after every reconnect.
        //  5. Taskbar: full-width tinted bar replaced by a floating tray pill;
        //     dock opaque and reserves the bottom edge.
        // v0.2.110 vc210: DYNAMIC DOCK (ENV_VERSION 91 re-seed).
        // Field (vc209): "the taskbar leaves this bar of the background" -
        // the centred dock floated 4 px above the bottom edge at 94% width
        // with strut_policy=follow_size, so every maximized window stopped
        // ABOVE the dock band while the reserved edge showed the wallpaper
        // around the floating dock (4 px strip under it, side gaps) - a
        // permanent background bar under every window.
        // The dock and the tray pill are now flush with the bottom edge and
        // intellihide (tint2 autohide=2): visible on the bare desktop, no
        // strut (maximized windows run to the true screen edge), both bars
        // slide away when a window overlaps their rectangle and pop back
        // over it when the pointer touches the bottom edge (1 px stays
        // peeking - tint2 unhides on pointer-enter).
        // v0.2.111 vc211: UNIVERSAL CLIPBOARD + CLEAN DESKTOP (ENV_VERSION 92).
        //  1. Clipboard bridge: tablet <-> X CLIPBOARD/PRIMARY via tmp files
        //     and guest emux-clipd (xclip, installed in the background);
        //     tablet side pushes on window focus / clip change, pulls every 0.5 s.
        //  2. xterm: Xft font (scales at Retina), select = copy, Ctrl+Shift+C/V,
        //     right-click/long-press pastes. Terminal tab: Ctrl+Shift+C/V too.
        //  3. Diagnostic badge hidden by default (Settings > Show desktop
        //     diagnostics); still appears by itself when the capture goes stale.
        //  4. Dock/tray intellihide: hide after 1.5 s (was 0.4), show after a
        //     deliberate 0.3 s, reveal strip 2 px x UI scale.
        // v0.2.112 vc212: PERFORMANCE AUDIT (ENV_VERSION 93).
        //  Guest: running0 ran ~6 traced execs PER PROCESS (sed/cut/tr/head/
        //  grep) and the 5 s governor called it repeatedly for 20 min - with
        //  MATE's 60+ processes, thousands of proot-traced execs per tick. Now
        //  one ps + one awk. seed_desktop_config (dozen file rewrites) only
        //  when the installed set changes, not every tick. pidfile checks via
        //  builtins. Background apt (REST/governor/extras) once a day, nice 19,
        //  3 tries max (was 30 per desktop start). 45 s census = diagnostics
        //  only. Xvfb -nocursor (the pane draws the pointer). clipd lighter.
        //  App: TiledFrame - the pane is 256x128 tiles, so a small change
        //  re-uploads a few hundred KB to the GPU instead of the whole 13 MB
        //  Retina/24-bit bitmap. Healer full sweeps >= 5 s apart (were every
        //  ~1-2 s while the user worked). One pending UI post per frame burst.
        //  Per-event diagnostics only with the badge on. Bottom-edge reach:
        //  leaving the pane downwards parks the X pointer on the last row so
        //  the auto-hidden dock reveals.
        // v0.2.113 vc213: DOCK THAT SHOWS + CLIPBOARD ON THE X PROTOCOL
        // (ENV_VERSION 94). Verified on tint2 17 / Xvfb / xterm in a sandbox.
        //  1. Dock: one centred unit (start, apps, windows, tray, clock).
        //     tint2 17 has no intellihide (autohide=2 meant always-hidden) and
        //     the hidden strip is only dock-wide at bottom centre: the app now
        //     passes the pointer through that strip whenever it reaches the
        //     bottom edge anywhere (show 0 s, hide 1.5 s after leaving).
        //  2. The 2-strike tint2 fallback no longer retires the config forever
        //     (tint2 also dies when X is not answering yet); one-session
        //     fallback only, and the automatic keep marker is undone.
        //  3. Clipboard: X11Clipboard owns/reads X selections over its own
        //     connection (XFIXES change events, UTF8/STRING/TARGETS, INCR in)
        //     - copies cross in milliseconds; no xclip/emux-clipd polling.
        //     xterm: selecting no longer overwrites the tablet clipboard;
        //     Ctrl+Shift+C copies, Ctrl+Shift+V / right-click paste.
        // vc217 (ENV_VERSION 97 -> 98): CLIPBOARD RELIABILITY + SETTINGS MENU.
        // The clipboard bridge re-offers only when it is really serving the text
        // (stale-paste fix), pulls the Linux clipboard on focus regain + every 3s
        // while foreground (desktop -> tablet heals even if the XFIXES push never
        // fired), seeds 1.5s after start and logs every crossing to the status
        // line. emux-x11 pins a Settings submenu (prepend.csv) at the top of the
        // jgmenu start menu so the MATE settings are always visible.
        // vc218 (ENV_VERSION 98 -> 99): FIREFOX UPGRADE SELF-HEALING. The launcher
        // scan detects the official Mozilla ARM64 tarball at /opt/firefox first, the
        // openbox right-click menu re-seeds when the browser binary changes (field:
        // dead menu entries after apt removed firefox-esr), and the seeded
        // emux-firefox-upgrade helper installs/refreshes the newest release in one
        // command.
        // vc219: CAPTURE BATTERY + SCROLL-TEAR FIXES. The damage capture loop was
        // paced at 8 ms (up to ~125 paint attempts/s), presented every partial band
        // mid-grab (consecutive bands at different scroll offsets showed as torn /
        // cropped frames while scrolling Firefox), and had no visibility gate - it
        // painted at full speed while the app was backgrounded or the desktop pane was
        // hidden (Terminal tab). Now: one coherent present per tick at the 30 fps
        // ceiling, and zero paint while the desktop tab is hidden, the app is
        // backgrounded or the screen is off.
        // vc220 (ENV_VERSION 99 -> 100): TASKBAR FIREFOX FIX. Every seeded browser
        // launcher (dock icon, desktop icon, right-click menu) exec'd the binary
        // detected at the LAST desktop restart, so a browser changed between
        // restarts left the taskbar Firefox pointing at a dead or outdated binary
        // (field: taskbar Firefox crashed on every page, apps-menu Firefox worked).
        // A seeded emux-browser wrapper now resolves the browser at CLICK time
        // (/opt/firefox tarball first), stale browser launchers are removed when no
        // browser is installed, and the dock + right-click menu stamps reseed once.
        // vc221: CLOSE BUTTON + TERMINAL SELECTION FIX. The top bar gains a Close
        // (x) button that works like Android Settings > Force stop: desktop stop
        // sweep, every terminal session stopped, foreground service stopped, then
        // the process exits - no stale sessions on reopen. VtTerminalView's
        // selection overlay used `top` before it was declared, so it resolved to
        // View.top (0) and the blue highlight painted at the top row of the view
        // instead of on the selected text.
        // vc222 (ENV_VERSION 100 -> 101): OBCONF - the desktop WM is openbox, so
        // the MATE Appearance capplet (which edits marco's titlebar) could never
        // change the window buttons, and there was no working window-settings
        // entry at all. obconf now installs with the other extras and a Window
        // Manager entry is pinned in the Settings submenu and the openbox
        // right-click menu; MATE Appearance is relabelled Appearance (themes).
        // vc223: SINGLE-SNAPSHOT PRESENT. The damage tick still grabbed its
        // pending bands one by one, so during a fast scroll / window move the
        // content shifted between grabs and one presented frame mixed bands from
        // different offsets - the small self-correcting glitches the field still
        // saw on v0.2.121. The tick now grabs the union box of all pending bands
        // in one memcpy and diffs/uploads from that single instant.
        // vc224: 60 FPS INTERACTION CAP. The single-snapshot present fixed the
        // mixed-band glitches, but the tick still paced at 30 fps at all times,
        // so a fast Firefox scroll showed a frame up to 33 ms old and then
        // jumped ahead - "delayed, then crops to catch up". While the pointer is
        // down or a wheel burst landed within the last second the damage tick
        // now runs at up to 60 fps (half the latency, half the jump); at rest
        // the 30 fps ceiling and all battery gating are unchanged.
        // vc225: DISTRO BROWSER LAUNCHER FIX. The menu entry "Firefox Web
        // Browser" is the distro's own .desktop: its Exec launches Firefox
        // without MOZ_DISABLE_CONTENT_SANDBOX=1, which dies instantly under
        // proot, while the EmuX-seeded "Web Browser" wrapper works. emux-x11
        // now overrides distro Mozilla launchers with same-ID local copies
        // exec'ing the wrapper and sets the xdg default browser (text/html,
        // http, https) to it, so default HTML files and links open working.
        // vc226: SESSION MULTIPLICATION FIX. A shell death (the field SIGSEGV
        // on first pasted command) respawned the session THREE times: every
        // ensureSessionAttached call had added another eternal registry
        // collector (tab switches, bootstrap paths - never cancelled), each
        // stale one saw the emptied registry and called startSession, plus
        // the service's own death-watcher respawn. The attach collector is
        // now single (previous cancelled, as desktopStatusJob since v0.2.112),
        // the UI-side replacement spawn re-checks the registry after 800 ms,
        // and the service collapses identical start requests within 700 ms.
        // vc227: TERMINAL COLOR SETTINGS. The Terminal tab colors were hard
        // -coded constants (DEFAULT_FG / BG_COLOR / CURSOR_COLOR / PALETTE).
        // Settings now has a Terminal colors section in BOTH tiers: a scheme
        // preset (EmuX Dark, Dracula, Solarized Dark/Light, Nord, Gruvbox
        // Dark, Termux) plus 19 per-color hex overrides (foreground,
        // background, cursor, ANSI 0-15). VtTerminalView.applyTheme reloads
        // the theme and remaps every attached view's interned styles IN
        // PLACE (cells store style indices), so changes apply live.
        // vc228: LAST DUPLICATE-SESSION PATH. The attach collector's PTY
        // upgrade fired for respawned sessions still in STARTING (health
        // gate running): restart() -> start() spawned the child early, the
        // gate's own s.start() spawned a second one, one child later
        // published DEAD and the watcher respawned - one death, two
        // sessions. Upgrades now wait for RUNNING, and start() is
        // idempotent per generation as a second guard.
        // v0.2.134 vc234: RETINA DESKTOP FIXES (ENV_VERSION 103 re-seed).
        //  1. The left rail drew as a full-width bar across the middle of the
        //     screen: tint2 reads a VERTICAL panel_size as length-thickness, and
        //     the config said "40 100%". Now "100% 40" (x UI scale).
        //  2. Right-click menu opened at half the pointer position at Retina:
        //     pcmanfm forwards root clicks in GTK logical coordinates
        //     (GDK_SCALE=2 -> halved). The Root right-click now presses a key
        //     bound to ShowMenu, which Openbox places at the real pointer.
        //  3. Openbox and tint2 render at 96 DPI regardless of Xft.dpi: menu,
        //     titlebar and panel fonts now scale with EMUX_SCALE.
        //  Verified on Xvfb 2800x1594 + Openbox 3.6 + pcmanfm (GDK_SCALE=2) +
        //  tint2 17: menus open at the click point; rail hidden = 4px left strip.
        // v0.2.135 vc235: ANDROID->LINUX FILE DROP.
        //  1. ShareReceiverActivity: "Share -> EmuX" from any Android app
        //     (ACTION_SEND / ACTION_SEND_MULTIPLE / ACTION_VIEW content:) copies
        //     the stream into files/shared/inbox on the host.
        //  2. files/shared is bind-mounted into every proot session at
        //     /root/shared (emux-init.sh + emux-x11 host-mode re-exec), so
        //     dropped files appear at ~/shared inside the Linux env immediately.
        // v0.2.136 vc236: OPENBOX FONT FIX - rc.xml fonts back to a FIXED 10pt:
        // "10 x EMUX_SCALE" rendered ~27px titlebar/menu text at 96 DPI on the
        // raw Retina screen (field: pcmanfm titlebar + desktop root menu huge,
        // GTK apps fine). ENV_VERSION 105 re-seeds emux-x11.
        // v0.2.137 vc237: XTERM CLIPBOARD KEYMAP - plain Ctrl+C copies to the
        // CLIPBOARD selection (bridged to the Android clipboard) and Ctrl+V
        // pastes from it; right-click opens xterm's VT Options menu (Copy,
        // Paste...) instead of pasting immediately. Trade-off: with no
        // selection, Ctrl+C sends nothing (it no longer sends SIGINT - use
        // Ctrl+D or `q` to stop a process). ENV_VERSION 106 re-seeds emux-x11.
        // v0.2.138 vc238: EXPLICIT-DESKTOP-PICK VALIDATION (host + guest).
        // 1. DesktopService fails fast with the remedy on the pane when the
        //    pick (stored before validation existed, or after a distro
        //    switch) is not installed in the ACTIVE rootfs - previously the
        //    guest only had a WM-less fallback xterm to show for it.
        // 2. emux-x11 (ENV_VERSION 107 re-seed): start_wm never silently
        //    swaps an explicit pick for openbox; the visibility xterm
        //    prefers bash (dash printed the profile PS1 escapes literally).
        // 3. BootstrapManager seeds a dash-safe profile PS1 at bootstrap.
        // v0.2.139 vc239: XFCE DUPLICATE SESSIONS (field 21 Nov 2026).
        // 1. The governor WM watchdog checked running0 "startxfce4", but
        //    startxfce4 execs away into xfce4-session, so the check was
        //    always false and a whole xfce session was relaunched every
        //    5 s tick - fourteen xfce4-session trees in the field ps, each
        //    with its own xfconfd + xfce4-panel (slowdown, wallpaper
        //    flicker, Action Buttons deaths, dead inputs, battery drain
        //    after close). wm_running() now maps a WM name to the process
        //    that actually stays alive (startxfce4 -> xfce4-session, and
        //    the same wrapper mapping for lxqt/gnome/mate); the start_wm
        //    idempotency guard uses it too, and the visibility has-scan
        //    counts a live full session as a visible desktop.
        // 2. New kill_dup_xfce_sessions() (start_furniture top, before
        //    the full-session early return, + governor tick before any
        //    relaunch): keeps the oldest live xfce4-session, kills the
        //    rest, sweeps their orphaned xfwm4/xfce4-panel/xfdesktop by
        //    PPID and orphan xfconfd by session bus - so a field install
        //    heals itself on the first tick after the update.
        // 3. DesktopService killDesktopGuests() pattern sweep gains the
        //    xfce names (xfce4-session, xfce4-panel, xfdesktop, xfconfd,
        //    xfwm4, dbus-daemon, tumbler) so the app close can reap them.
        //    ENV_VERSION 108 re-seeds emux-x11 onto existing rootfs trees.
        // v0.2.140 vc240: TERMINAL PTY SIZE NEVER APPLIED (field 22 Nov 2026).
        // 1. `stty size` stayed 0 0 and COLUMNS=80 while /tmp/emuxsize
        //    correctly held "47 164": the emux-winsize watcher exited
        //    silently on EVERY session start, before ever entering its loop.
        //    Root cause: the guest dash inherits the HOST-style sessionEnv
        //    PATH (<linuxRoot>/usr/bin:...:/system/bin — host-absolute
        //    paths that do not exist inside proot), so no bare guest binary
        //    resolved — `tty`, `awk`, `stty`, `cat`, `sleep` all "not found"
        //    (field: "sh: 1: tty: not found"). The launcher's $(tty) failed
        //    so $1 arrived empty, the in-script `tty` fallback failed the
        //    same way, and the /proc/self/stat fallback failed because awk
        //    was not found either. emux-winsize now sets a sane GUEST PATH
        //    first, and its TTY resolution retries in the loop instead of
        //    exiting.
        // 2. emux-init.sh resolves the pts by ABSOLUTE path
        //    (/usr/bin/tty, /bin/tty fallback) and applies the size from
        //    /tmp/emuxsize directly at shell start (absolute-path stty on
        //    stdin, which IS the pts), so a shell starting with a
        //    missing/dead watcher still gets real geometry from the first
        //    prompt. ENV_VERSION 109 re-seeds emux-winsize + emux-init.sh
        //    onto existing rootfs trees.
        // v0.2.141 vc241: CRASH-RESPAWN DUPLICATE XFCE SESSIONS + DOUBLE
        // WHITE TERMINALS (field 23 Nov 2026). 1. Firefox crash -> a second
        // xfce session beside the dead one, dead input, zoomed out: the
        // DesktopService respawn reused the same EMUX_DESKTOP_ID, so
        // killDesktopGuests() pass 1 skipped the old tree (pass 2's age
        // filter skipped it too - it started during this visit) and pass 3
        // had no xfce names; the respawn now ROTATES the desktop id before
        // the sweep, so pass 1 reaps the old tree. 2. Self-heal rotation
        // deleted /tmp/emux_wm_launched and relaunched start_wm even while
        // the old session lived (the start_wm guard also required the
        // marker): the guard is now wm_running-only, kill_dup runs before
        // AND after start_wm on the self-heal path, and kill_dup keeps the
        // session on the LIVE display (display from /proc/PID/environ) and
        // kills a lone dead-display one - the oldest-first rule kept the
        // dead-display session after a rotation. 3. The governor exited
        // after 20 min (240 ticks), so a crash "after a bit" was
        // unguarded: it now runs the session lifetime (8640 ticks = 12 h)
        // and exits only when the launcher dies or no server AND no WM
        // remain for two ticks. 4. Two WHITE terminals at every xfce
        // start, the first unclosable under the top panel: apply_scale
        // never set xterm colours (xterm reads X resources, not the
        // GTK/xfce theme) - dark colours now merged, marker per-server so
        // a rotation re-applies; the immediate, REST-pass, self-heal and
        // main-tail forced-terminal sites all fired under a full session
        // (their guards only checked tint2/pcmanfm, which never run under
        // xfce) - they now skip full sessions (full_session_wm), the
        // immediate block touches /tmp/emux_xterm_launched so the
        // main-tail block cannot open a second one, and a legitimately
        // forced terminal gets -geometry 96x28+60+40 so its title bar
        // clears the top panel. ENV_VERSION 110 re-seeds emux-x11.
        // v0.2.142 vc242: FIREFOX-OPEN HARDENING (field 24 Nov 2026).
        // Opening Firefox killed the desktop EVERY time (zoom change,
        // black screen, dead/laggy desktop): Firefox's memory spike let
        // the Android low-memory killer take out Xvfb/xfce4-session (no
        // oom protection without root), the respawn adopted a server at
        // the wrong geometry and restarted twice (two black screens, a
        // zoom jump), the capture fell back from -fbdir to GetImage and
        // stayed there, and double-forked xfconfd/dbus-daemon survived
        // the respawn and fought the new session over xfconf. 1. LIGHTER
        // FIREFOX: emux-x11 seeds emux-firefox-light, which writes a
        // low-memory user.js into every Firefox profile at seed time and
        // at browser launch (dom.ipc.processCount 8->2,
        // dom.ipc.processCount.webIsolated 4->1,
        // browser.sessionstore.resume_from_crash=false so the restore
        // set stops growing after every crash,
        // browser.tabs.unloadOnLowMemory=true). 2. GEOMETRY MISMATCH:
        // the restart now waits for the wrong-geometry server's port to
        // go silent (bounded) before starting the desktop, so the new
        // launcher cannot re-adopt the dying server, and a restart
        // already in flight cannot be queued twice. 3. STALE DAEMONS:
        // killDesktopGuests() gains a dead-bus/age pass that reaps the
        // previous visit's xfconfd/dbus-daemon even when young, and
        // kill_dup_xfce_sessions() reaps orphaned session daemons whose
        // display has no live session. 4. FBDIR DROP: after a drop the
        // capture retries the map (bounded, 2 tries) instead of staying
        // on GetImage for the rest of the connection. ENV_VERSION 111
        // re-seeds emux-x11.
        versionCode = 242
        versionName = "0.2.142"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        // v0.1.47: COMMITTED debug keystore (ACTION_PLAN §6 pitfall: "if the
        // sandbox resets, the signature changes → devices need ONE adb
        // uninstall"). Every rebuild used to mint a fresh ephemeral
        // ~/.android/debug.keystore, so each release forced a reinstall on the
        // field device (wiping the rootfs). This is a DEBUG key, committed on
        // purpose — it grants nothing (sideloaded debug builds); keeping it in
        // the repo makes the APK signature stable across build environments.
        create("emuxDebug") {
            val ks = rootProject.file("debug-keystore/emux-debug.jks")
            if (ks.isFile) {
                storeFile = ks
                storePassword = "emuxdebug"
                keyAlias = "emux"
                keyPassword = "emuxdebug"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            // Fall back to the auto-generated debug key when the committed
            // keystore is absent (fresh clone without debug-keystore/).
            val ks = rootProject.file("debug-keystore/emux-debug.jks")
            // v0.2.88 BUILD FIX: this read `signingConfig.getByName(...)` - the
            // BuildType's own Property<SigningConfig?> receiver, which has no
            // getByName. The named config lives in the signingConfigs CONTAINER
            // (the outer android{} receiver is in scope inside buildTypes{}).
            // The typo is pre-existing, but the v0.2.88 comment additions
            // changed the file hash and forced a fresh Kotlin-DSL compile,
            // which surfaced it (manual run #2: script compilation error,
            // 1m31s, no APK).
            if (ks.isFile) signingConfig = signingConfigs.getByName("emuxDebug")
        }
    }

    packaging {
        jniLibs {
            // Keep .so files compressed-in-APK but mmap'd; do NOT extract → faster boot, less storage churn.
            useLegacyPackaging = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.work:work-runtime-ktx:2.9.1")   // battery-aware deferred bootstrap work
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // v0.1.47 multi-distro: pure-Java XZ/LZMA2 decoder (0BSD) — the Debian and
    // Ubuntu catalog images are .tar.xz, and host xz(1)/tar -J availability on
    // Android 7–15 ROMs is inconsistent. Host tooling is still tried first;
    // this is the guaranteed fallback inside extractTarXz().
    implementation("org.tukaani:xz:1.10")

    // ---- Terminal engine: EmuX in-house VT100/ANSI parser + damage-only renderer
    // (see com.emux.terminal.VtTerminalView). Same PTY strategy as Termux
    // (Android's app_process + Linux pseudo-terminal driver) — zero native code,
    // zero extra deps, Termux-grade power efficiency.

    // Optional: root helper binary (see docs/ROOT.md). Ships nothing privileged itself.
    // implementation(files("libs/magisk-su-bridge.aar"))
}

configurations.all {
    resolutionStrategy.force("org.robolectric:android-all:14-robolectric-10818077")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
}




