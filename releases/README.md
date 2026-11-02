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

Rebuild any of them with the zero-Gradle pipeline:
`android-emux/tools/build-apk/no-gradle-build.sh` (see
`android-emux/docs/BUILDING_THE_APK.md`), then check with
`android-emux/tools/verify_apk.sh` — a build that does not pass 51/0/0 must not
be added here.
