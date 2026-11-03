/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.opentv.R
import app.opentv.core.AppSettings
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Where a recording's bytes go — and how to read the size back and delete it — hidden behind one
 * small surface so the engine, library and playback don't care whether a recording lives on the
 * box's internal storage, on a NAS over SMB or explicitly local FTP, or on a plugged-in USB / external drive.
 *
 * ## Three shapes of locator
 * A recording's `filePath` (its stored locator) is one of:
 *  - an absolute filesystem path — an INTERNAL recording, played back via a `file://` URI;
 *  - an `smb://host/share/…` locator — a NAS recording, read back over SMB;
 *  - a `content://…` document URI — a USB recording written through the Storage Access Framework;
 *  - an `ftp://private-ip:port/…` locator — a cleartext LAN recording, never a media source.
 *
 * USB is special because SAF only assigns a document's `content://` URI when the file is actually
 * created. So a USB recording is planned with a [USB_PLACEHOLDER_PREFIX]`filename` marker; when the
 * capture opens its sink the document is created, and the sink reports the real `content://` URI
 * back (via [Sink.resolvedLocator]) for the caller to persist onto the row.
 */
object RecordingStorage {

    /** Marker locator for a USB recording that hasn't been created (and given a URI) yet. */
    private const val USB_PLACEHOLDER_PREFIX = "usb://"

    /** An opened destination the capture writes to, plus how to close it. */
    class Sink(
        val output: OutputStream,
        /**
         * The real locator the bytes are going to when it differs from the planned one — set only
         * for USB, where the `content://` document URI is known only once the file is created. The
         * capture persists this onto the recording row so playback and delete can find the file.
         * Null for INTERNAL and SMB, whose planned locator is already the final one.
         */
        val resolvedLocator: String? = null,
        private val closer: () -> Unit,
    ) {
        fun close() = closer()
    }

    fun smbConfig(settings: AppSettings): SmbConfig = SmbConfig(
        host = settings.smbHost.value,
        share = settings.smbShare.value,
        folder = settings.smbFolder.value,
        username = settings.smbUser.value,
        password = settings.smbPassword.value,
    )

    fun ftpConfig(settings: AppSettings): FtpConfig = FtpConfig(
        host = settings.ftpHost.value,
        port = settings.ftpPort.value,
        folder = settings.ftpFolder.value,
        username = settings.ftpUser.value,
        password = settings.ftpPassword.value,
        plaintextAcknowledged = settings.ftpPlaintextAcknowledged.value,
    )

    /** Internal recordings dir on the device's own (app-private) external storage — no permission needed. */
    fun internalDir(context: Context): File =
        File(context.getExternalFilesDir(null), "recordings").apply { mkdirs() }

    /**
     * The locator a new recording named [filename] would be stored at, given the current target.
     * Computed up front so the DB row knows where its file lives before capture starts.
     */
    fun plannedLocator(context: Context, settings: AppSettings, filename: String): String =
        when (settings.recordingTarget.value) {
            AppSettings.RecordingTarget.SMB ->
                SmbClient.locator(smbConfig(settings), filename)
            AppSettings.RecordingTarget.FTP ->
                FtpClient.locator(ftpConfig(settings), filename)
            AppSettings.RecordingTarget.USB ->
                // The real content:// URI isn't known until the SAF document is created at capture
                // time; carry the intended filename in a placeholder until then.
                USB_PLACEHOLDER_PREFIX + filename
            AppSettings.RecordingTarget.INTERNAL ->
                File(internalDir(context), filename).absolutePath
        }

    /** True for a `content://` locator — a USB recording created through SAF. */
    fun isContent(locator: String): Boolean = locator.startsWith("content://")

    /** True for the pre-creation USB marker — a planned USB recording with no document yet. */
    fun isUsbPlaceholder(locator: String): Boolean = locator.startsWith(USB_PLACEHOLDER_PREFIX)

    /**
     * Open the destination named by [locator] for writing. For a USB placeholder this creates the
     * SAF document and reports its real `content://` URI through [Sink.resolvedLocator]. The
     * capture must persist that URI before its first write; the placeholder lookup below is the
     * crash-recovery fallback for the smaller window between createFile and that persistence.
     * Throws with a human-readable message when the USB drive / folder grant is missing, rather
     * than crashing the capture.
     */
    fun openSink(context: Context, settings: AppSettings, locator: String): Sink = when {
        SmbClient.isSmb(locator) -> {
            val handle = SmbClient.openForWrite(smbConfig(settings), locator, overwrite = false)
            Sink(handle.output) { handle.close() }
        }
        isFtp(locator) -> {
            val handle = FtpClient.openForWrite(ftpConfig(settings), locator)
            Sink(handle.output, resolvedLocator = locator) { handle.close() }
        }
        isUsbPlaceholder(locator) ->
            openUsbSink(context, settings, locator.removePrefix(USB_PLACEHOLDER_PREFIX))
        isContent(locator) -> {
            // A USB recording whose document already exists (e.g. re-opened): write into it.
            val uri = Uri.parse(locator)
            val out = context.contentResolver.openOutputStream(uri, "w")
                ?: throw IOException(context.getString(R.string.rec_error_usb_unavailable))
            Sink(out, resolvedLocator = locator) { closeOutput(out) }
        }
        else -> {
            val file = File(locator)
            file.parentFile?.mkdirs()
            if (!file.createNewFile()) throw IOException("A recording with this file name already exists.")
            val out = try {
                FileOutputStream(file)
            } catch (error: Exception) {
                if (file.exists() && file.length() == 0L) runCatching { file.delete() }
                throw error
            }
            Sink(out) { closeOutput(out) }
        }
    }

    /**
     * Create the recording's file on the granted USB tree and open it for writing. The tree URI
     * carries a persisted read/write permission, so the created document stays readable for
     * playback across restarts with no storage permission.
     */
    private fun openUsbSink(context: Context, settings: AppSettings, filename: String): Sink {
        val treeUriString = settings.usbTreeUri.value
            ?: throw IOException(context.getString(R.string.rec_error_usb_no_folder))
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriString))
        if (tree == null || !tree.canWrite()) {
            throw IOException(context.getString(R.string.rec_error_usb_unavailable))
        }
        val doc = tree.createFile(mimeForFilename(filename), filename)
            ?: throw IOException(context.getString(R.string.rec_error_usb_unavailable))
        val out = try {
            context.contentResolver.openOutputStream(doc.uri)
        } catch (error: Exception) {
            runCatching { doc.delete() }
            throw error
        } ?: run {
            runCatching { doc.delete() }
            throw IOException(context.getString(R.string.rec_error_usb_unavailable))
        }
        return Sink(out, resolvedLocator = doc.uri.toString()) { closeOutput(out) }
    }

    /** Recordings are MPEG-TS `.ts` or a remuxed `.mp4`; anything else is a generic video type. */
    private fun mimeForFilename(filename: String): String = when {
        filename.endsWith(".ts", ignoreCase = true) -> "video/mp2t"
        filename.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
        else -> "video/*"
    }

    /**
     * Copy a recording's bytes from wherever it lives into [out]. Used by the MP4 pass, which
     * needs a local file that Media3 can read; SMB has no URI Media3 understands, so it streams.
     */
    fun copyTo(context: Context, settings: AppSettings, locator: String, out: OutputStream) {
        when {
            SmbClient.isSmb(locator) -> SmbClient.copyTo(smbConfig(settings), locator, out)
            isFtp(locator) -> FtpClient.copyTo(ftpConfig(settings), locator, out)
            isContent(locator) -> {
                val input = context.contentResolver.openInputStream(Uri.parse(locator))
                    ?: throw IOException(context.getString(R.string.rec_error_usb_unavailable))
                input.use { it.copyTo(out) }
            }
            isUsbPlaceholder(locator) -> Unit
            else -> File(locator).inputStream().use { it.copyTo(out) }
        }
    }


    /**
     * Delete the physical recording and return only a verifiable success. Exceptions deliberately
     * escape so callers can keep the database row and surface the failure instead of forgetting a
     * remote/USB file behind a successful-looking UI action.
     */
    fun delete(context: Context, settings: AppSettings, locator: String): Boolean = when {
        SmbClient.isSmb(locator) -> SmbClient.delete(smbConfig(settings), locator)
        isFtp(locator) -> FtpClient.delete(ftpConfig(settings), locator)
        isContent(locator) -> DocumentFile.fromSingleUri(context, Uri.parse(locator))?.let { document ->
            document.delete() && !document.exists()
        } == true
        isUsbPlaceholder(locator) -> deleteUsbPlaceholder(context, settings, locator)
        else -> File(locator).delete()
    }

    /**
     * Return a current physical size. A negative value means the provider could not report a
     * reliable size (for example a detached USB tree or an unreachable NAS).
     */
    fun sizeOf(context: Context, settings: AppSettings, locator: String): Long = when {
        SmbClient.isSmb(locator) -> runCatching {
            SmbClient.sizeOf(smbConfig(settings), locator)
        }.getOrDefault(-1L)
        isFtp(locator) -> runCatching {
            FtpClient.sizeOf(ftpConfig(settings), locator)
        }.getOrDefault(-1L)
        isContent(locator) -> runCatching {
            DocumentFile.fromSingleUri(context, Uri.parse(locator))?.length() ?: -1L
        }.getOrDefault(-1L)
        isUsbPlaceholder(locator) -> runCatching {
            findUsbPlaceholder(context, settings, locator)?.length() ?: -1L
        }.getOrDefault(-1L)
        else -> File(locator).length()
    }

    /**
     * Local-only compatibility overload. Callers that need USB/SMB sizes must provide context and
     * settings through [sizeOf] above so the provider can be queried rather than guessed.
     */
    fun sizeOf(locator: String): Long =
        if (SmbClient.isSmb(locator) || isFtp(locator) || isContent(locator) || isUsbPlaceholder(locator)) -1L
        else File(locator).length()

    /** Recover a remotely staged FTP recording, returning verified bytes or -1 when not recoverable. */
    fun recoverInterrupted(context: Context, settings: AppSettings, locator: String): Long =
        if (isFtp(locator)) FtpClient.recoverInterrupted(ftpConfig(settings), locator) else -1L

    /** Resolve the SAF document created just before a crash, before its content:// URI was persisted. */
    fun recoverUsbPlaceholderLocator(context: Context, settings: AppSettings, locator: String): String? =
        if (isUsbPlaceholder(locator)) {
            findUsbPlaceholder(context, settings, locator)?.uri?.toString()
        } else {
            null
        }

    /** Only local files and SMB expose a readable growing file; FTP publishes after upload completion. */
    fun supportsGrowingPlayback(locator: String): Boolean =
        !isContent(locator) && !isUsbPlaceholder(locator) && !isFtp(locator)

    fun isFtp(locator: String): Boolean = locator.startsWith("ftp://", ignoreCase = true)

    private fun deleteUsbPlaceholder(context: Context, settings: AppSettings, locator: String): Boolean {
        val treeUri = settings.usbTreeUri.value ?: return false
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return false
        val filename = usbPlaceholderFilename(locator) ?: return false
        val document = tree.findFile(filename) ?: return true
        if (!document.delete()) return false
        return tree.findFile(filename) == null
    }

    /** Resolve a pre-create locator to the document left behind by a crash, if any. */
    private fun findUsbPlaceholder(
        context: Context,
        settings: AppSettings,
        locator: String,
    ): DocumentFile? {
        val filename = usbPlaceholderFilename(locator) ?: return null
        val treeUri = settings.usbTreeUri.value ?: return null
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return null
        return tree.findFile(filename)
    }

    private fun closeOutput(output: OutputStream) {
        var failure: Throwable? = null
        runCatching { output.flush() }.onFailure { failure = it }
        runCatching { output.close() }.onFailure { closeError ->
            val earlier = failure
            if (earlier == null) failure = closeError else earlier.addSuppressed(closeError)
        }
        failure?.let { error ->
            if (error is IOException) throw error
            throw IOException("Recording destination could not be closed.", error)
        }
    }

    /** Filename lookup key for the crash window after SAF createFile but before DB persistence. */
    internal fun usbPlaceholderFilename(locator: String): String? {
        if (!isUsbPlaceholder(locator)) return null
        val filename = locator.removePrefix(USB_PLACEHOLDER_PREFIX)
        return filename.takeIf { it.isNotBlank() && !it.contains('/') && !it.contains('\\') }
    }

    /** Filesystem-safe and collision-resistant recording filename. */
    fun fileNameFor(
        channelName: String,
        title: String,
        startMillis: Long,
        extension: String = "ts",
    ): String {
        val stamp = android.text.format.DateFormat.format("yyyy-MM-dd HHmm", startMillis)
        val base = (if (title.isBlank() || title == channelName) channelName else "$channelName - $title")
        val safe = base.replace(Regex("[^A-Za-z0-9 _.-]"), "_").take(80).trim()
        val suffix = java.util.UUID.randomUUID().toString().take(8)
        return "$safe - $stamp ($suffix).$extension"
    }
}
