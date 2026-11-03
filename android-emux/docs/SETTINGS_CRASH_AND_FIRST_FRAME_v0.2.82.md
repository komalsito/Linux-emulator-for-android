# v0.2.82 — the fallback that crashed, the blank first five seconds, and the re-proof that gave up

Three fixes from one field report (HUAWEI MRDI-W09, API 31, v0.2.81): a crash
stack, "tried moving it, clicking other things, it didnt worked", and the
badge history that explained both.

## 1. Settings crashed on the tier that exists to prevent crashes

```
java.lang.NullPointerException: Attempt to invoke virtual method
'android.content.SharedPreferences androidx.preference.PreferenceManager.getSharedPreferences()'
on a null object reference
    at com.emux.SettingsActivity$CodePrefs.onCreatePreferences(SettingsActivity.kt:271)
```

Settings has three tiers: XML preferences, a programmatic `CodePrefs` screen,
and a plain-widget last resort. The device never reached the XML tier — it
threw first (the known Material3/preferenceTheme attribute-resolution class of
failure on some ROMs, which is exactly what the fallbacks exist for) — and then
the *fallback* killed the process.

Line 271 read `preferenceManager.sharedPreferences` for the mirror field. On
this androidx version `getPreferenceManager()` is **null** while
`onCreatePreferences` runs: the manager is not there yet, and the NPE names the
virtual `getSharedPreferences()` on a null `PreferenceManager`. The fragment
state path made it uncatchable in practice — the failing `onCreatePreferences`
ran during `dispatchActivityCreated` from `onStart`, outside the transaction
try/catch that guards the tier switch — so the "absolute last resort" screen
never got its turn.

Fix: the static `PreferenceManager.getDefaultSharedPreferences(ctx)` — same
file, no receiver to be null. The fallback tier is now actually fall-back-able.

## 2. Damage mode showed nothing for its first ~5 seconds

v0.2.81's damage loop only painted when something *changed after subscribing*
(or at the healer tick, every ~150 ticks). On connect that meant a blank pane —
or worse, the previous session's photograph — for up to five seconds, which on
a slow device reads exactly like "the desktop is dead". The loop now starts one
tick below the healer, so the first iteration is a full banded sweep: the pane
is correct immediately, and damage events take over from there.

## 3. The input re-proof gave up after four minutes

The field session that reported unresponsive clicks was on the guest xdotool
agent for its whole life (a process exec per event, on a rootfs whose own
binaries segfault — see the v0.2.80 report). v0.2.80's re-proof tried six times
over four minutes and then logged once and stopped. If the original
`proof=ignored` was a boot-time race — desktop stack still settling, WM startup
grab on the pointer — the recoverable window can be longer than that. The
re-proof is now indefinite: 3 s, 10 s, 20 s, 40 s, then every minute for as long
as the session lives and the agent is still carrying input. The moment the
server honours XTEST the transport promotes and the agent retires.

## Regression

`verify_apk.sh` 55/0, `drag_test.sh` 7/7, `ladder_test.sh` 46/0,
`damage_test.sh` 7/7. No guest changes; `ENV_VERSION` stays 75.
