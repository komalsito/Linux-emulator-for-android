# EmuX release APKs (every shipped version, kept in-repo by user request)

`../emux-debug.apk` (repo root) is ALWAYS the newest build. This directory keeps
every versioned APK so a device can be re-flashed with any exact build even if
a branch or tag is lost. All are signed with the committed debug keystore
(`android-emux/debug-keystore/emux-debug.jks`, SHA-256
`5e3ef25492ec7e64802fd3e5a40beca04f1f86ae0472cbe44a0afe50aca9a1fa`), so they
upgrade/downgrade in place with `adb install -r` against each other.

| file | version | versionCode | verified | note |
|---|---|---|---|---|
| `emux-debug-v0.2.67-vc167.apk` | 0.2.67 | 167 | 51/0/0 | plane-agreement + continuous proof + dead-socket recycle |
| `emux-debug-v0.2.68-vc168.apk` | 0.2.68 | 168 | 51/0/0 | respawn capture on ANY death; STALE badge |
| `emux-debug-v0.2.69-vc169.apk` | 0.2.69 | 169 | 51/0/0 | wedge guard (no cap_display publication >20 s => kill+respawn) |
| `emux-debug-v0.2.70-vc170.apk` | 0.2.70 | 170 | 51/0/0 | LMK: capture/xwd never the sacrifice; no positive bias without root; cap.err painted on the STALE pane |
| `emux-debug-v0.2.71-vc171.apk` | 0.2.71 | 171 | 51/0/0 | NATIVE in-app capture (XGetImage/ZPixmap on a dedicated X connection, the x11vnc/x0vncserver model); guest xwd/netpbm/nc pipeline demoted to fallback; `src=` badge field |
| `emux-debug-v0.2.72-vc172.apk` | 0.2.72 | 172 | 51/0/0 | GetImage WIRE FIX (format byte in header, 6-unit request — v0.2.71's 28-byte request was BadLength-rejected on every grab); `nat=` failure reason on STALE pane; 25 s silence watchdog falls back to guest pipeline; wiretest harness in-repo |
| `emux-debug-v0.2.73-vc173.apk` | 0.2.73 | 173 | 51/0/0 | BANDED GetImage: probe measures pipe KB/s, refresh sweeps ~1.2 s bands painted progressively (field v0.2.72: full 3.5 MB reply timed out); timeout vs eof distinguished in `nat=` |
| `emux-debug-v0.2.74-vc174.apk` | 0.2.74 | 174 | 51/0/0 | depth-16 Xvfb (half the capture wire, RGB565 direct); X error CODES captured (`xerr=N`) + pipe KB/s and band rows painted on the STALE pane |
| `emux-debug-v0.2.75-vc175.apk` | 0.2.75 | 175 | 52/0/0 | FILESYSTEM-MAILBOX capture (emux-fbmail): guest xwd -> atomic rename -> host reads the file (no proot in the read path); native->fbmail->legacy ladder; full X error forensics (maj/min/res); ENV_VERSION 73 re-seeds the helper |
| `emux-debug-v0.2.76-vc176.apk` | 0.2.76 | 176 | 52/0/0 | MAILBOX READER: app polls /tmp/emux_frame.rgb (raw RGB565, already written by emux-capture every loop BEFORE the wedging nc push) + /tmp/emux_fb.xwd; emux-capture mail mode skips the push entirely; ENV_VERSION 74 |
| `emux-debug-v0.2.77-vc177.apk` | 0.2.77 | 177 | 52/0/0 | post-frame launcher exit with a LIVE server no longer floods the pane with the diag wall (judge by server probe, not launcher rc); bounded post-frame X respawn |
| `emux-debug-v0.2.78-vc178.apk` | 0.2.78 | 178 | 52/0/0 | CLICK SWALLOW: our own rc.xml bound the `Frame` context, so Openbox `XGrabButton`'d the parent of every app window and never replayed the press (motion/keys untouched) — rc.xml is now stamped, rewritten when the stamp differs and audited for grab contexts; in-app ButtonPress probe (`btn=`/`wm=` badge fields, `proof=motion-only` instead of a false `ok`); 2-stage escalation ladder (rewrite+`--reconfigure` → switch WM/none); host display pick by *connect* not bind; our own unverified Xvfb retired (the stray-on-`:0` report); ENV_VERSION 75 |
| `emux-debug-v0.2.79-vc179.apk` | 0.2.79 | 179 | 53/0/0 | LEFT-DRAG: a drag sent *motion only* and then released a button it had never pressed — a hover, not a drag, so nothing on the desktop could be dragged (right-drag worked only because long-press really presses button 3) — now `ButtonPress(1)` at the touch-down point, motions ≤30/s, `ButtonRelease(1)` on lift/cancel/second finger, with double-tap-and-**hold** as an alias (a quick double-tap is still a double click); capture-quality setting (1280 default / 960 / 720 / 540 long side) + Settings → *Restart desktop now*, native capture cap 3 → 10 fps, and a LIVE badge carrying geometry, measured KB/s and ms/refresh so the 0.5 fps field report is legible and fixable; `drag_test.sh` 7/7 (client wire sequence *and* a titlebar drag that moves the window exactly +150,+100); **two build gates fixed**: the LOWMEM binding javac lacked `jclasses` on its classpath, so `ActivityMainBinding` never reached the dex — a signed, `passed=52 failed=0` APK that would `NoClassDefFoundError` in `MainActivity.onCreate` — and `verify_apk.sh` could not see it because byte-greps cannot tell a *defined* class from a *referenced* one (new 53rd check parses dex `class_defs`) |
| `emux-debug-v0.2.80-vc180.apk` | 0.2.80 | 180 | 53/0/0 | STAIRCASE TERMINAL: `ptyWrapped` was an intent (set before the guest `script(1)` is known alive); on a ROM where the wrapper SIGSEGVs the shell fell back to raw pipes with the flag still true and every line staircased - the view now MEASURES the wire (a PTY with ONLCR can never emit a bare LF) and heals mid-stream, restoring ONLCR emulation + local echo; WARNING deduped to once per session. SLOPPY DESKTOP: the badge's "1.0 fps" counted partial band repaints while no full sweep ever completed (`-1KB/s -1ms/refresh`) - missed bands now halve the band so the sweep keeps moving, KB/s is EWMA'd from bands that land, fps counts COMPLETED refreshes and a stuck sweep prints `sweep y/H miss=N reason`; input proof re-probes on backoff and promotes agent->XTEST instead of a one-shot verdict at the worst moment; live server geometry is verified against the quality setting and mismatched sessions restart once (`GEOMETRY MISMATCH`); Settings predicts fps per option from the pipe your device measured |
| `emux-debug-v0.2.81-vc181.apk` | 0.2.81 | 181 | 55/0/0 | DAMAGE-DRIVEN CAPTURE: the loop polled the WHOLE screen per refresh, so every interaction waited behind megabytes of unchanged pixels - the laggy that survives every pacing fix. The server now reports what changed (DAMAGE Create on root + xDamageNotify events, geometry at offset 16, measured byte-by-byte) and only that rectangle crosses the pipe; idle desktop ships nothing; >60% damage and a 5 s healer still use the banded sweep; badge ends mode=damage/mode=sweep. Two wrong encodings died in the sandbox first (CARD16 QueryVersion = BadLength; ALL XFIXES region requests rejected by this Xvfb), and an unchecked sync() made failures look like success for one debugging round - damage_test.sh 7/7 is the record |

Rebuild any of them with the zero-Gradle pipeline:
`android-emux/tools/build-apk/no-gradle-build.sh` (see
`android-emux/docs/BUILDING_THE_APK.md`), then check with
`android-emux/tools/verify_apk.sh` — a build that does not pass it must not be
added here. The gate has grown as it caught things: 51 checks to v0.2.74, 52
from v0.2.75, **53 from v0.2.79** (the count in the table above is what that
APK was verified against). On this 1 GiB box the pipeline needs
`LOWMEM=1` and a bounded JVM heap — see
`android-emux/docs/DRAG_AND_CAPTURE_RATE_v0.2.79.md` §3 for the measurements.
