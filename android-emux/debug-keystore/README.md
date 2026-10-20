# Committed debug keystore

`emux-debug.jks` is the **debug** signing key for EmuX, committed on purpose
(see `docs/ACTION_PLAN.md` §6 — "if the sandbox resets, the signature changes →
devices need ONE `adb uninstall com.emux`").

- alias: `emux` · storepass/keypass: `emuxdebug` · RSA 2048, valid ~30 years
- Used by `app/build.gradle.kts` → `signingConfigs.emuxDebug` for the `debug`
  build type only. It grants nothing: debug builds are sideloaded, and anyone
  can already re-sign an APK they possess.
- Effect: every build — from any machine/CI/sandbox — produces the SAME
  signature, so `adb install -r emux-debug.apk` upgrades in place instead of
  failing with INSTALL_FAILED_UPDATE_INCOMPATIBLE.

NOTE: APKs built BEFORE v0.1.47 were signed with ephemeral per-machine debug
keys. Upgrading from any of those to a v0.1.47+ build requires ONE
`adb uninstall com.emux` (this also wipes the installed rootfs; the app
re-bootstraps on next launch). After that, signatures are stable forever.
