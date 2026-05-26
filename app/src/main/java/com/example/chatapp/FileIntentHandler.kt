package com.example.chatapp

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URLConnection
import java.util.Locale

class FileIntentHandler(private val context: Context) {

    data class Result(
        val attachments: List<PendingAttachment>,
        val prefillText: String?,
        val errors: List<String>
    )

    private val appContext = context.applicationContext

    suspend fun handle(intent: Intent?): Result = withContext(Dispatchers.IO) {
        if (intent == null || !isFileIntent(intent)) {
            return@withContext Result(emptyList(), null, emptyList())
        }

        val action = intent.action
        val allowImages = action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE
        val uris = collectUris(intent, action)
        val errors = mutableListOf<String>()
        val attachments = mutableListOf<PendingAttachment>()

        uris.forEach { uri ->
            runCatching {
                prepareAttachment(intent, uri, allowImages)
            }.onSuccess { attachment ->
                attachments.add(attachment)
            }.onFailure { error ->
                errors.add(userFacingError(error))
            }
        }

        val prefillText = if (attachments.isEmpty()) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        } else {
            null
        }

        Result(
            attachments = attachments,
            prefillText = prefillText,
            errors = errors.distinct()
        )
    }

    private fun collectUris(intent: Intent?, action: String?): List<Uri> {
        if (intent == null) return emptyList()

        val result = linkedSetOf<Uri>()
        if (action == Intent.ACTION_VIEW) {
            intent.data?.let(result::add)
        }

        if (action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(result::add)
        }

        if (action == Intent.ACTION_SEND_MULTIPLE) {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                ?.forEach(result::add)
        }

        intent.clipData?.let { clipData ->
            for (index in 0 until clipData.itemCount) {
                clipData.getItemAt(index).uri?.let(result::add)
            }
        }

        return result.filter { it != Uri.EMPTY }
    }

    private fun prepareAttachment(
        intent: Intent,
        sourceUri: Uri,
        allowImages: Boolean
    ): PendingAttachment {
        if (sourceUri.scheme?.lowercase(Locale.US) != "content") {
            throw IllegalArgumentException(LocaleHelper.getString(context, "attachment_read_error"))
        }

        maybeTakePersistableReadPermission(intent, sourceUri)

        val metadata = readMetadata(sourceUri)
        val displayName = metadata.displayName
        val mimeType = resolveMimeType(sourceUri, displayName, intent.type)
        if (!isSupportedMimeType(mimeType, displayName, allowImages)) {
            throw IllegalArgumentException(
                LocaleHelper.getString(context, "attachment_unsupported_type")
            )
        }

        val isImage = mimeType.startsWith("image/", ignoreCase = true)
        val maxBytes = if (isImage) MAX_INCOMING_IMAGE_BYTES else ChatAttachmentHelper.MAX_ATTACHMENT_BYTES.toLong()
        metadata.sizeBytes?.let { declaredSize ->
            if (declaredSize > maxBytes) {
                throw IllegalArgumentException(LocaleHelper.getString(context, "attachment_too_large"))
            }
        }

        val targetName = normalizedFileName(displayName, mimeType)
        val targetFile = uniqueFile(incomingCacheDir(), targetName)
        val copiedBytes = copyUriToFile(sourceUri, targetFile, maxBytes)
        val localUri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            targetFile
        )

        return PendingAttachment(
            uri = localUri,
            displayName = displayName?.takeIf { it.isNotBlank() } ?: targetFile.name,
            mimeType = mimeType,
            sizeBytes = copiedBytes,
            sourceUri = sourceUri.toString(),
            isLocalCopy = true
        )
    }

    private fun maybeTakePersistableReadPermission(intent: Intent, uri: Uri) {
        val hasReadGrant = intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        val hasPersistableGrant = intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
        if (!hasReadGrant || !hasPersistableGrant) return

        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun readMetadata(uri: Uri): UriMetadata {
        return runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use UriMetadata(null, null)
                val name = cursor.stringOrNull(OpenableColumns.DISPLAY_NAME)
                val size = cursor.longOrNull(OpenableColumns.SIZE)
                UriMetadata(name, size)
            }
        }.getOrNull() ?: UriMetadata(null, null)
    }

    private fun resolveMimeType(uri: Uri, displayName: String?, intentType: String?): String {
        val resolverType = runCatching { appContext.contentResolver.getType(uri) }.getOrNull()
        return resolverType?.takeIf { it.isSpecificMimeType() }
            ?: intentType?.takeIf { it.isSpecificMimeType() }
            ?: URLConnection.guessContentTypeFromName(displayName.orEmpty())
            ?: mimeTypeFromExtension(displayName)
            ?: "application/octet-stream"
    }

    private fun isSupportedMimeType(mimeType: String, displayName: String?, allowImages: Boolean): Boolean {
        if (allowImages && mimeType.startsWith("image/", ignoreCase = true)) {
            return true
        }

        val normalizedMime = mimeType.lowercase(Locale.US)
        if (normalizedMime in SUPPORTED_DOCUMENT_MIME_TYPES) {
            return true
        }

        val extension = displayName
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.US)
            .orEmpty()
        return extension in SUPPORTED_DOCUMENT_EXTENSIONS ||
            (allowImages && extension in IMAGE_EXTENSIONS)
    }

    private fun copyUriToFile(sourceUri: Uri, targetFile: File, maxBytes: Long): Long {
        var totalBytes = 0L
        appContext.contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    totalBytes += read
                    if (totalBytes > maxBytes) {
                        targetFile.delete()
                        throw IllegalArgumentException(LocaleHelper.getString(context, "attachment_too_large"))
                    }
                    output.write(buffer, 0, read)
                }
            }
        } ?: throw IllegalArgumentException(LocaleHelper.getString(context, "attachment_read_error"))

        if (totalBytes <= 0L) {
            targetFile.delete()
            throw IllegalArgumentException(LocaleHelper.getString(context, "attachment_read_error"))
        }
        return totalBytes
    }

    private fun incomingCacheDir(): File =
        File(appContext.cacheDir, INCOMING_ATTACHMENT_DIR).apply { mkdirs() }

    private fun normalizedFileName(displayName: String?, mimeType: String): String {
        val fallback = "incoming_attachment_${System.currentTimeMillis()}"
        val safeName = safeFileName(displayName?.takeIf { it.isNotBlank() } ?: fallback)
        if (safeName.substringAfterLast('.', "").takeIf { it != safeName }.isNullOrBlank()) {
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            if (!extension.isNullOrBlank()) return "$safeName.$extension"
        }
        return safeName
    }

    private fun safeFileName(fileName: String): String {
        return fileName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(120)
            .ifBlank { "incoming_attachment_${System.currentTimeMillis()}" }
    }

    private fun uniqueFile(directory: File, requestedName: String): File {
        val base = requestedName.substringBeforeLast('.', requestedName)
        val extension = requestedName.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() && it != requestedName }
            ?.let { ".$it" }
            .orEmpty()
        var candidate = File(directory, requestedName)
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(directory, "${base}_$suffix$extension")
            suffix++
        }
        return candidate
    }

    private fun userFacingError(error: Throwable): String =
        error.message
            ?.takeIf { it.isNotBlank() && it.length <= 160 }
            ?: LocaleHelper.getString(context, "attachment_read_error")

    private fun String.isSpecificMimeType(): Boolean =
        isNotBlank() &&
            this != "*/*" &&
            !endsWith("/*") &&
            !equals("application/octet-stream", ignoreCase = true)

    private fun mimeTypeFromExtension(displayName: String?): String? {
        val extension = displayName
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.US)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return when (extension) {
            "csv" -> "text/csv"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        }
    }

    private fun android.database.Cursor.stringOrNull(columnName: String): String? {
        val index = getColumnIndex(columnName)
        return if (index == -1 || isNull(index)) null else getString(index)
    }

    private fun android.database.Cursor.longOrNull(columnName: String): Long? {
        val index = getColumnIndex(columnName)
        return if (index == -1 || isNull(index)) null else getLong(index)
    }

    private data class UriMetadata(
        val displayName: String?,
        val sizeBytes: Long?
    )

    companion object {
        private const val INCOMING_ATTACHMENT_DIR = "incoming_attachments"
        private const val MAX_INCOMING_IMAGE_BYTES = 25L * 1024L * 1024L

        val SUPPORTED_DOCUMENT_MIME_TYPES = setOf(
            "application/pdf",
            "text/plain",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/csv",
            "application/csv"
        )

        private val SUPPORTED_DOCUMENT_EXTENSIONS = setOf(
            "pdf",
            "txt",
            "doc",
            "docx",
            "xls",
            "xlsx",
            "csv"
        )

        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif")

        fun isFileIntent(intent: Intent?): Boolean {
            val action = intent?.action ?: return false
            return when (action) {
                Intent.ACTION_VIEW -> {
                    intent.data?.scheme?.equals("content", ignoreCase = true) == true ||
                        intent.type?.let { mimeType ->
                            mimeType in SUPPORTED_DOCUMENT_MIME_TYPES
                        } == true
                }
                Intent.ACTION_SEND,
                Intent.ACTION_SEND_MULTIPLE -> true
                else -> false
            }
        }

        fun consume(intent: Intent?) {
            intent ?: return
            intent.setAction(null)
            intent.setDataAndType(null, null)
            intent.clipData = null
            intent.removeExtra(Intent.EXTRA_STREAM)
            intent.removeExtra(Intent.EXTRA_TEXT)
        }

    }
}
