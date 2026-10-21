package com.emux.core

import android.content.Context
import java.io.File

/**
 * v0.1.47 MULTI-DISTRO SUPPORT.
 *
 * Catalog of Linux distributions EmuX can install as the ACTIVE rootfs
 * (files/distros/<id>/). Alpine stays the default (~4 MB, apk); Debian and
 * Ubuntu join as proot-ready images — the SAME artifacts Termux's
 * proot-distro ships (github.com/termux/proot-distro releases), which are
 * purpose-built rootfs tarballs for unrooted Android proot use and come with
 * published SHA256 sums (pinned below; verified 2026-10 against the
 * distro-plugins of proot-distro v4.20.0).
 *
 * Architecture notes:
 *  - arch keys use FsLayout/BootstrapManager archName(): aarch64/armv7/x86_64/x86
 *  - proot-distro names: aarch64→aarch64, armv7→arm, x86→i686
 *  - Ubuntu noble has NO 32-bit x86 image (Canonical dropped i386) → empty list
 *    → BootstrapManager surfaces a clear "not available for this device" error.
 */

/** How the distro installs packages (BootstrapManager branches on this). */
enum class PkgManager { APK, APT, PACMAN, DNF }

/** One downloadable rootfs image. [sha256] is verified post-download when known. */
data class DistroImage(val url: String, val sha256: String? = null)

/** A Linux distribution installable into files/distros/<[id]>. */
data class DistroSpec(
    val id: String,
    /** Human label for UI ("Alpine 3.20.3", "Debian 12 (bookworm)", …). */
    val label: String,
    /** Short download-size hint for UI. */
    val sizeHint: String,
    val pkg: PkgManager,
    /** Ordered per-arch download candidates; empty list = unsupported arch. */
    val images: Map<String, List<DistroImage>>,
    /** Packages that must be present for the terminal (PTY + shell). */
    val shellPkgs: List<String>,
    /** Best-effort convenience packages (never fatal). */
    val extraPkgs: List<String>,
    /** X11 desktop stack (Settings → desktop components). */
    val desktopPkgs: List<String>,
) {
    fun candidates(arch: String): List<DistroImage> = images[arch].orEmpty()
}

object DistroCatalog {

    const val PREF_KEY = "distro"
    const val DEFAULT_ID = "alpine"

    // ---- Alpine (default) ---------------------------------------------------
    // Mirrors + versions live in BootstrapManager (ALPINE_VERSION / MIRRORS +
    // the Settings "mirror" base-URL normalization); the catalog only registers
    // the spec so every call site can treat distros uniformly.
    val ALPINE = DistroSpec(
        id = "alpine",
        label = "Alpine 3.20",
        sizeHint = "~4 MB download",
        pkg = PkgManager.APK,
        images = emptyMap(),   // built dynamically from BootstrapManager.MIRRORS
        shellPkgs = listOf("util-linux", "bash"),
        extraPkgs = listOf("sudo", "apt"),
        desktopPkgs = listOf(
            "xvfb", "xkbcomp", "xkeyboard-config", "openbox",
            "xterm", "xwd", "xhost", "netpbm"),
    )

    // ---- Debian 12 (bookworm) ----------------------------------------------
    // Primary: proot-distro release tarballs (SHA256-pinned, proot-ready:
    // merged /usr, fake-root paths, apt keyrings included).
    val DEBIAN = DistroSpec(
        id = "debian",
        label = "Debian 12 (bookworm)",
        sizeHint = "~40 MB download",
        pkg = PkgManager.APT,
        images = mapOf(
            "aarch64" to listOf(
                DistroImage(
                    "https://github.com/termux/proot-distro/releases/download/v4.17.3/debian-bookworm-aarch64-pd-v4.17.3.tar.xz",
                    "3a841a794ae5999b33e33b329582ed0379d4f54ca62c6ce5a8eb9cff5ef8900b"),
            ),
            "armv7" to listOf(
                DistroImage(
                    "https://github.com/termux/proot-distro/releases/download/v4.17.3/debian-bookworm-arm-pd-v4.17.3.tar.xz",
                    "85861ab139d4042302796cf46a93a9efbcb4808c06f7a1ae5fb71812f4564424"),
            ),
            "x86_64" to listOf(
                DistroImage(
                    "https://github.com/termux/proot-distro/releases/download/v4.17.3/debian-bookworm-x86_64-pd-v4.17.3.tar.xz",
                    "675e534333adcbf369e97abda3088927651e5d91612ae5727c52ff2284f4b8c8"),
            ),
            "x86" to listOf(
                DistroImage(
                    "https://github.com/termux/proot-distro/releases/download/v4.17.3/debian-bookworm-i686-pd-v4.17.3.tar.xz",
                    "1fb3a6b0ea679e3797b35984049abf22bfe3b6ab79e9bb98cdfc54994712e1e4"),
            ),
        ),
        // util-linux (script(1) = PTY) + bash are ALREADY in the image; the
        // install run only fires when the on-disk probe fails (offline-proof).
        shellPkgs = listOf("util-linux", "bash", "sudo"),
        // v0.1.53: coreutils joins the extras — the field device lost /usr/bin/tty
        // + /usr/bin/groups (half-installed coreutils after a killed dpkg
        // transaction / partial extraction) and nothing reinstalled them.
        extraPkgs = listOf("sudo", "netcat-openbsd", "ca-certificates", "wget", "coreutils"),
        desktopPkgs = listOf(
            "xvfb", "xkbcomp", "x11-xserver-utils", "x11-apps", "netpbm",
            "openbox", "xterm", "xfonts-base", "xkb-data", "netcat-openbsd"),
    )

    // ---- Ubuntu 24.04 LTS (noble) ------------------------------------------
    // Primary: proot-distro release tarballs (SHA256-pinned).
    // Fallback: Canonical cloud-images minimal root tarballs (no published
    // per-file sha at a stable URL → verified by xz magic only), reachable
    // where GitHub release downloads are blocked/slow (mainland-China field
    // reports). armhf cloud images do not exist for noble; the pd tarball
    // covers armv7.
    val UBUNTU = DistroSpec(
        id = "ubuntu",
        label = "Ubuntu 24.04 LTS",
        sizeHint = "~61 MB download",
        pkg = PkgManager.APT,
        images = mapOf(
            "aarch64" to listOf(
                DistroImage(
                    "https://github.com/termux/proot-distro/releases/download/v4.18.0/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz",
                    "91acaa786b8e2fbba56a9fd0f8a1188cee482b5c7baeed707b29ddaa9a294daa"),
                DistroImage(
                    "https://cloud-images.ubuntu.com/minimal/releases/noble/release/ubuntu-24.04-minimal-cloudimg-arm64-root.tar.xz"),
            ),
            "armv7" to listOf(
                DistroImage(
                    "https://github.com/termux/proot-distro/releases/download/v4.18.0/ubuntu-noble-arm-pd-v4.18.0.tar.xz",
                    "2afb7e1ff17983fa2cf4c57edeea6be427ffb0359d8628b24a147b4c8aa276d5"),
            ),
            "x86_64" to listOf(
                DistroImage(
                    "https://github.com/termux/proot-distro/releases/download/v4.18.0/ubuntu-noble-x86_64-pd-v4.18.0.tar.xz",
                    "3a769bce23985effb504140d43b6ddd73dac9e261d1932894afa31de81e45414"),
                DistroImage(
                    "https://cloud-images.ubuntu.com/minimal/releases/noble/release/ubuntu-24.04-minimal-cloudimg-amd64-root.tar.xz"),
            ),
            // x86 (32-bit): no noble image exists anywhere → unsupported.
        ),
        shellPkgs = listOf("util-linux", "bash", "sudo"),
        // v0.1.53: coreutils joins the extras — the field device lost /usr/bin/tty
        // + /usr/bin/groups (half-installed coreutils after a killed dpkg
        // transaction / partial extraction) and nothing reinstalled them.
        extraPkgs = listOf("sudo", "netcat-openbsd", "ca-certificates", "wget", "coreutils"),
        desktopPkgs = listOf(
            "xvfb", "xkbcomp", "x11-xserver-utils", "x11-apps", "netpbm",
            "openbox", "xterm", "xfonts-base", "xkb-data", "netcat-openbsd"),
    )

    // ---- Arch Linux (ARM) ---------------------------------------------------
    // Rolling-release favorite; the proot-distro image is a FULL base install
    // (~138 MB compressed on aarch64 — the honest size hint matters here).
    // pacman repositories (ALARM for arm/aarch64) come preconfigured in the
    // image; bash + util-linux are preinstalled so the terminal works offline.
    val ARCH = DistroSpec(
        id = "arch",
        label = "Arch Linux ARM",
        sizeHint = "~138 MB download",
        pkg = PkgManager.PACMAN,
        images = mapOf(
            "aarch64" to listOf(
                DistroImage(
                    "https://github.com/termux/proot-distro/releases/download/v4.17.3/archlinux-aarch64-pd-v4.17.3.tar.xz",
                    "dc56b998ffa2663209417396c8d70caf87c8052acf41e9a2c6daf24cbd181533"),
            ),
            "armv7" to listOf(
                DistroImage(
                    "https://github.com/termux/proot-distro/releases/download/v4.17.3/archlinux-arm-pd-v4.17.3.tar.xz",
                    "4b698018ded0656e17c0867b97b53cc32be5906c0f37e02ab499c65d5f12d439"),
            ),
            "x86" to listOf(
                DistroImage(
                    "https://github.com/termux/proot-distro/releases/download/v4.17.3/archlinux-i686-pd-v4.17.3.tar.xz",
                    "787d43c21fae2c6efe843a324ff2875fc654fe8475020deb8678c224967f29af"),
            ),
            "x86_64" to listOf(
                DistroImage(
                    "https://github.com/termux/proot-distro/releases/download/v4.17.3/archlinux-x86_64-pd-v4.17.3.tar.xz",
                    "75e069c3f59f4806848972dfd6a2d390b0328ca3f4486db140eb21d1d376b35b"),
            ),
        ),
        shellPkgs = listOf("util-linux", "bash", "sudo"),
        extraPkgs = listOf("sudo", "openbsd-netcat", "wget", "ca-certificates"),
        desktopPkgs = listOf(
            "xorg-server-xvfb", "xorg-xkbcomp", "xkeyboard-config", "xorg-xhost",
            "xorg-xwd", "netpbm", "openbox", "xterm", "xorg-fonts-misc",
            "openbsd-netcat"),
    )

    // ---- Fedora 40 ----------------------------------------------------------
    // dnf-based; the pd image is compact (~30 MB) with repos preconfigured.
    val FEDORA = DistroSpec(
        id = "fedora",
        label = "Fedora 40",
        sizeHint = "~30 MB download",
        pkg = PkgManager.DNF,
        images = mapOf(
            "aarch64" to listOf(
                DistroImage(
                    "https://github.com/termux/proot-distro/releases/download/v4.17.3/fedora-aarch64-pd-v4.17.3.tar.xz",
                    "5f51f3f2da790732fc6b720eefe5ec44841cf8edb21dfa342005257c4665bb8c"),
            ),
            "x86_64" to listOf(
                DistroImage(
                    "https://github.com/termux/proot-distro/releases/download/v4.17.3/fedora-x86_64-pd-v4.17.3.tar.xz",
                    "857bd545ea1f3a4ad8fba907cabd8059abbd04abf29f5d543f473f7e051a3eb1"),
            ),
        ),
        shellPkgs = listOf("util-linux", "bash", "sudo"),
        extraPkgs = listOf("sudo", "nmap-ncat", "wget", "ca-certificates"),
        desktopPkgs = listOf(
            "xorg-x11-server-Xvfb", "xkbcomp", "xorg-x11-server-utils",
            "xorg-x11-apps", "netpbm", "openbox", "xterm", "nmap-ncat",
            "xorg-x11-fonts-misc"),
    )

    val ALL: List<DistroSpec> = listOf(ALPINE, DEBIAN, UBUNTU, ARCH, FEDORA)

    fun byId(id: String?): DistroSpec = ALL.firstOrNull { it.id == id } ?: ALPINE

    /** The distro the user selected in Settings (defaults to Alpine). */
    fun requested(ctx: Context): DistroSpec = byId(
        runCatching {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                .getString(PREF_KEY, DEFAULT_ID)
        }.getOrNull()
    )

    /**
     * SWITCH GUARD: true when the user explicitly tapped Install for the
     * CURRENTLY SELECTED distro. BootstrapManager refuses to run a fresh
     * download of a selected-but-uninstalled distro while ANOTHER distro is
     * installed unless this is set — an auto-scheduled WorkManager pass (or a
     * stale enqueued job surviving an app update) must never pull 40-60 MB and
     * swap the environment behind the user's back. Id-scoped, so every new
     * switch needs a fresh confirmation.
     */
    fun switchConfirmed(ctx: Context): Boolean = runCatching {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString("distro_switch_ok_for", null) == requested(ctx).id
    }.getOrDefault(false)

    fun markSwitchConfirmed(ctx: Context) {
        runCatching {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit().putString("distro_switch_ok_for", requested(ctx).id).apply()
        }
    }

    // ---- apt mirror tables (rotation fallbacks; see BootstrapManager) --------

    /** Debian archive mirrors (base, no trailing slash): default first. */
    val DEBIAN_MIRRORS = listOf(
        "http://deb.debian.org/debian",
        "https://mirrors.tuna.tsinghua.edu.cn/debian",
        "https://mirrors.aliyun.com/debian",
        "https://mirrors.ustc.edu.cn/debian",
    )
    val DEBIAN_SECURITY_MIRRORS = listOf(
        "http://deb.debian.org/debian-security",
        "https://mirrors.tuna.tsinghua.edu.cn/debian-security",
        "https://mirrors.aliyun.com/debian-security",
        "https://mirrors.ustc.edu.cn/debian-security",
    )

    /** Ubuntu archive mirrors, per port flavor (arm64/armhf → ubuntu-ports). */
    val UBUNTU_MIRRORS = listOf(
        "http://archive.ubuntu.com/ubuntu",
        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu",
        "https://mirrors.aliyun.com/ubuntu",
        "https://mirrors.ustc.edu.cn/ubuntu",
    )
    val UBUNTU_PORTS_MIRRORS = listOf(
        "http://ports.ubuntu.com/ubuntu-ports",
        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports",
        "https://mirrors.aliyun.com/ubuntu-ports",
        "https://mirrors.ustc.edu.cn/ubuntu-ports",
    )

    /** Read ID / VERSION_CODENAME out of the rootfs os-release (best effort). */
    fun osReleaseField(rootfs: File, key: String): String? = runCatching {
        val f = File(rootfs, "etc/os-release")
        (if (f.isFile) f else File(rootfs, "usr/lib/os-release")).takeIf { it.isFile }
            ?.readLines()?.firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')?.trim('"')?.trim('\'')
    }.getOrNull()
}
