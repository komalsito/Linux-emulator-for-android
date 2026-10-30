package com.emux.core
import java.io.File
/** Stub mirroring the REAL signatures at BootstrapManager.kt:28,160,194,1234,
 *  1251,2159,2172,2182,2253,2264,2280,2367,2479. Pass 1 of compile_all.sh uses
 *  this; pass 2 compiles the real file and deletes these .class files first. */
class BootstrapManager(private val ctx: android.content.Context) {
    internal fun stageHelperAssets(): Map<String, ByteArray> = emptyMap()
    internal fun installHelperScripts(dest: File, staged: Map<String, ByteArray>, report: (String, Int) -> Unit) {}
    internal fun installCaptureConv(dest: File) {}
    internal fun installWallpaper(dest: File) {}
    internal fun installLinkFixShim(dest: File) {}
    internal fun aptJournalDirty(dest: File): Boolean = false
    internal fun dpkgHalfConfigured(dest: File): Boolean = false
    internal fun dpkgLibcBroken(dest: File): Boolean = false
    fun desktopPreRepair(): String? = null
    fun installDesktopComponents(): String? = null
    companion object {
        internal fun reapOrphanGuests(log: (String) -> Unit = {}) {}
        @Volatile var progressNote: ((String) -> Unit)? = null
    }
}
