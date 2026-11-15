package com.emux

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import com.emux.core.FsLayout
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/**
 * v0.2.135 ANDROID->LINUX FILE DROP.
 *
 * The receiving end of the Android share sheet: any app that "Shares" content
 * to EmuX (or opens a content: URI with us) lands its bytes in the host drop
 * box, files/shared/inbox (FsLayout.shared()). emux-init.sh and emux-x11
 * bind-mount files/shared into every proot session at /root/shared, so the
 * file is immediately at ~/shared/inbox inside the Linux environment - no
 * storage permission, no adb, no downloads-folder detour.
 *
 * Handles:
 *   - ACTION_SEND          (EXTRA_STREAM file, or EXTRA_TEXT snippet)
 *   - ACTION_SEND_MULTIPLE (EXTRA_STREAM ArrayList)
 *   - ACTION_VIEW          (content: URI opened "with" EmuX)
 *
 * Translucent + excludeFromRecents: the share completes with a Toast and the
 * activity finishes; the user never sees a UI. Copies run on a worker thread
 * so a large video does not ANR the sending app's process transition.
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent
        if (intent == null) {
            finish()
            return
        }
        val uris = collectUris(intent)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (uris.isEmpty() && text.isNullOrEmpty()) {
            toast("EmuX: nothing to share")
            finish()
            return
        }
        val inbox = File(FsLayout.shared(this), "inbox").apply { mkdirs() }
        val n = uris.size
        thread(name = "emux-share-drop") {
            var ok = 0
            var err = 0
            for (uri in uris) {
                if (dropUri(uri, inbox)) ok++ else err++
            }
            if (!text.isNullOrEmpty() && uris.isEmpty()) {
                // Shared text with no file: save it as a timestamped .txt.
                val name = "shared-text-${System.currentTimeMillis() / 1000}.txt"
                if (writeAtomic(name) { it.write(text.toByteArray()) }) ok++ else err++
            }
            val msg = when {
                err == 0 && n > 0 -> "EmuX: $ok file(s) -> ~/shared/inbox"
                err == 0 -> "EmuX: text saved to ~/shared/inbox"
                ok == 0 -> "EmuX: share failed ($err)"
                else -> "EmuX: $ok saved, $err failed -> ~/shared/inbox"
            }
            toast(msg)
            runOnUiThread { finish() }
        }
    }

    /** Gather every shareable URI out of the intent, across all three actions. */
    private fun collectUris(intent: Intent): List<Uri> {
        val uris = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND ->
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE ->
                @Suppress("DEPRECATION")
                (intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.addAll(it) }
            Intent.ACTION_VIEW -> intent.data?.let { uris.add(it) }
        }
        return uris
    }

    /**
     * Copy one content: URI into the inbox. Name resolution order: the
     * provider's DISPLAY_NAME (real file name), else the URI's last segment,
     * else a timestamp - always sanitised and de-duplicated (foo.txt ->
     * foo-2.txt) so a re-share never clobbers the first copy.
     */
    private fun dropUri(uri: Uri, inbox: File): Boolean {
        return try {
            val base = sanitize(queryName(uri) ?: uri.lastPathSegment ?: "shared-file")
            val target = uniqueName(inbox, base)
            contentResolver.openInputStream(uri)?.use { input ->
                writeAtomic(target.name) { out -> input.copyTo(out) }
            } ?: false
        } catch (e: Exception) {
            EmuXLog.e("share-drop: failed $uri: ${e.message}")
            false
        }
    }

    /**
     * Write via a .part temp file + rename, so a killed transfer never leaves
     * a truncated file that LOOKS complete inside the guest.
     */
    private fun writeAtomic(name: String, write: (java.io.OutputStream) -> Unit): Boolean {
        val tmp = File(inboxDir(), "$name.part")
        return try {
            tmp.outputStream().use(write)
            val done = File(inboxDir(), name)
            if (!tmp.renameTo(done)) {
                // cross-filesystem rename should not happen inside files/, but
                // fall back to a stream copy rather than losing the file
                done.outputStream().use { o -> tmp.inputStream().use { i -> i.copyTo(o) } }
                tmp.delete()
            }
            true
        } catch (e: IOException) {
            EmuXLog.e("share-drop: write failed $name: ${e.message}")
            tmp.delete()
            false
        }
    }

    private fun inboxDir(): File = File(FsLayout.shared(this), "inbox")

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (_: Exception) { null }

    /** Strip path separators/control chars; keep it short and filesystem-safe. */
    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[/\\\\\\u0000-\\u001f]"), "_").trim()
        return if (cleaned.isEmpty()) "shared-file" else cleaned.take(120)
    }

    /** foo.txt -> foo-2.txt -> foo-3.txt ... */
    private fun uniqueName(dir: File, base: String): File {
        var f = File(dir, base)
        if (!f.exists()) return f
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val ext = if (dot > 0) base.substring(dot) else ""
        var i = 2
        while (f.exists()) { f = File(dir, "$stem-$i$ext"); i++ }
        return f
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
