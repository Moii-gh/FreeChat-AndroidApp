package com.example.chatapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.example.chatapp.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

data class AttachmentPayload(
    val fileUri: String,
    val mimeType: String,
    val fileName: String?,
    val base64Data: String?,
    val attachmentContext: String?
)

/**
 * Готовит вложение для отправки в чат: читает URI, определяет MIME и извлекает текстовый контекст.
 * В Activity остается только координация UI, а вся работа с файлами держится здесь.
 */
class ChatAttachmentHelper(private val context: Context) {
    private data class PreparedBytes(
        val bytes: ByteArray,
        val mimeType: String,
        val fileName: String?
    )

    private val appContext = context.applicationContext

    suspend fun buildAttachmentPayload(uri: Uri?): AttachmentPayload? = withContext(Dispatchers.IO) {
        if (uri == null) return@withContext null

        val fileName = FileUtils.getFileName(context, uri)
            .takeIf { it.isNotBlank() }
        val mimeType = resolveMimeType(uri, fileName)
        val isImage = mimeType.startsWith("image/", ignoreCase = true)
        val prepared = if (isImage) {
            prepareImageAttachment(uri, mimeType, fileName)
        } else {
            PreparedBytes(readAttachmentBytes(uri), mimeType, fileName)
        }

        val extractedText = if (!isImage) {
            extractTextFromFile(prepared.bytes, prepared.mimeType, prepared.fileName)
        } else {
            null
        }

        val attachmentContext = if (extractedText != null) {
            buildAttachmentContext(prepared.fileName, prepared.mimeType, extractedText)
        } else {
            null
        }

        val base64Data = Base64.encodeToString(prepared.bytes, Base64.NO_WRAP)

        return@withContext AttachmentPayload(
            fileUri = uri.toString(),
            mimeType = prepared.mimeType,
            fileName = prepared.fileName,
            base64Data = base64Data,
            attachmentContext = attachmentContext
        )
    }

    private fun buildAttachmentContext(
        fileName: String?,
        mimeType: String,
        extractedText: String
    ): String {
        val preview = extractedText.trim().take(MAX_ATTACHMENT_CONTEXT_CHARS)
        return buildString {
            append(LocaleHelper.getString(context, "attachment_context_file_summary"))
            if (!fileName.isNullOrBlank()) append(": ").append(fileName)
            append("\n")
                .append(LocaleHelper.getString(context, "attachment_context_mime"))
                .append(": ")
                .append(mimeType)
            append("\n")
                .append(LocaleHelper.getString(context, "attachment_context_preview"))
                .append(":\n")
                .append(preview)
            if (extractedText.length > preview.length) {
                append("\n\n").append(LocaleHelper.getString(context, "attachment_context_truncated"))
            }
        }
    }

    /**
     * Извлекает текстовое содержимое файла.
     * Поддерживает текстовые файлы, DOCX и эвристику для неизвестных форматов.
     */
    private fun extractTextFromFile(bytes: ByteArray, mimeType: String, fileName: String?): String? {
        val extension = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()

        // DOCX является ZIP-архивом с XML внутри.
        if (isDocxFile(mimeType, extension)) {
            val docxText = extractDocxText(bytes)
            if (!docxText.isNullOrBlank()) return truncateText(docxText)
        }

        if (isTextLikeAttachment(mimeType, fileName)) {
            return decodeAttachmentText(bytes)
        }

        val heuristicText = tryDecodeAsText(bytes)
        if (heuristicText != null) return truncateText(heuristicText)

        return null
    }

    private fun isDocxFile(mimeType: String, extension: String): Boolean {
        return extension == "docx" ||
            mimeType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ignoreCase = true)
    }

    private fun extractDocxText(bytes: ByteArray): String? {
        return runCatching {
            val zipInput = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes))
            var entry = zipInput.nextEntry
            var documentXml: String? = null
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    documentXml = zipInput.bufferedReader(Charsets.UTF_8).readText()
                    break
                }
                zipInput.closeEntry()
                entry = zipInput.nextEntry
            }
            zipInput.close()

            if (documentXml == null) return@runCatching null

            val withBreaks = documentXml
                .replace(Regex("<w:p[\\s>]"), "\n")
                .replace(Regex("<w:br[^>]*>"), "\n")
                .replace(Regex("<w:tab[^>]*>"), "\t")

            withBreaks.replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
                .ifBlank { null }
        }.getOrNull()
    }

    private fun tryDecodeAsText(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val sampleSize = minOf(bytes.size, 8192)
        val sample = bytes.copyOf(sampleSize)
        val text = sample.toString(Charsets.UTF_8)

        var printable = 0
        var nonPrintable = 0
        for (ch in text) {
            if (ch.isLetterOrDigit() || ch.isWhitespace() || ch in "!@#\$%^&*()_+-=[]{}|;':\",./<>?`~\\") {
                printable++
            } else if (ch.code < 32 && ch != '\n' && ch != '\r' && ch != '\t') {
                nonPrintable++
            }
        }

        val total = printable + nonPrintable
        if (total == 0) return null
        val ratio = nonPrintable.toFloat() / total
        if (ratio > 0.05f) return null

        return decodeAttachmentText(bytes)
    }

    private fun resolveMimeType(uri: Uri, fileName: String?): String {
        return appContext.contentResolver.getType(uri)
            ?.takeIf { it.isNotBlank() }
            ?: fileName?.let { java.net.URLConnection.guessContentTypeFromName(it) }
            ?: "application/octet-stream"
    }

    private fun readAttachmentBytes(uri: Uri): ByteArray {
        val declaredSize = queryAttachmentSize(uri)
        if (declaredSize != null && declaredSize > MAX_ATTACHMENT_BYTES) {
            throw IllegalArgumentException(LocaleHelper.getString(context, "attachment_too_large"))
        }

        val output = java.io.ByteArrayOutputStream()
        appContext.contentResolver.openInputStream(uri)?.use { inputStream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalBytes = 0
            while (true) {
                val read = inputStream.read(buffer)
                if (read == -1) break
                totalBytes += read
                if (totalBytes > MAX_ATTACHMENT_BYTES) {
                    throw IllegalArgumentException(LocaleHelper.getString(context, "attachment_too_large"))
                }
                output.write(buffer, 0, read)
            }
        } ?: throw IllegalArgumentException(LocaleHelper.getString(context, "attachment_read_error"))

        return output.toByteArray()
    }

    private fun queryAttachmentSize(uri: Uri): Long? {
        return runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index == -1 || cursor.isNull(index)) null else cursor.getLong(index)
            }
        }.getOrNull()
    }

    private fun prepareImageAttachment(uri: Uri, mimeType: String, fileName: String?): PreparedBytes {
        val bounds = decodeImageBounds(uri)
        val declaredSize = queryAttachmentSize(uri)
        if (
            declaredSize != null &&
            declaredSize in 1L..MAX_ORIGINAL_IMAGE_BYTES.toLong() &&
            bounds != null &&
            bounds.first <= MAX_IMAGE_DIMENSION &&
            bounds.second <= MAX_IMAGE_DIMENSION
        ) {
            return PreparedBytes(readAttachmentBytes(uri), mimeType, fileName)
        }

        val sampleSize = bounds?.let { calculateInSampleSize(it.first, it.second, MAX_IMAGE_DIMENSION) } ?: 1
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        val bitmap = try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
        } catch (error: OutOfMemoryError) {
            throw IllegalArgumentException(LocaleHelper.getString(context, "attachment_too_large"))
        } ?: return PreparedBytes(readAttachmentBytes(uri), mimeType, fileName)

        return try {
            val compressed = compressBitmapForUpload(bitmap)
            val normalizedName = fileName
                ?.substringBeforeLast('.', fileName)
                ?.takeIf { it.isNotBlank() }
                ?.let { "$it.jpg" }
                ?: "image_${System.currentTimeMillis()}.jpg"
            PreparedBytes(compressed, "image/jpeg", normalizedName)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeImageBounds(uri: Uri): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
        }
        val width = options.outWidth
        val height = options.outHeight
        return if (width > 0 && height > 0) width to height else null
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        var sampledWidth = width
        var sampledHeight = height
        while (sampledWidth / 2 >= maxDimension || sampledHeight / 2 >= maxDimension) {
            sampledWidth /= 2
            sampledHeight /= 2
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun compressBitmapForUpload(bitmap: Bitmap): ByteArray {
        var quality = IMAGE_JPEG_QUALITY
        var lastBytes: ByteArray
        do {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            lastBytes = output.toByteArray()
            quality -= 10
        } while (lastBytes.size > MAX_ATTACHMENT_BYTES && quality >= MIN_IMAGE_JPEG_QUALITY)

        if (lastBytes.size > MAX_ATTACHMENT_BYTES) {
            throw IllegalArgumentException(LocaleHelper.getString(context, "attachment_too_large"))
        }
        return lastBytes
    }

    private fun isTextLikeAttachment(mimeType: String, fileName: String?): Boolean {
        if (mimeType.startsWith("text/", ignoreCase = true)) return true
        val normalizedMime = mimeType.lowercase()
        if (normalizedMime in TEXT_LIKE_MIME_TYPES) {
            return true
        }

        val extension = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return extension in TEXT_LIKE_EXTENSIONS
    }

    private fun decodeAttachmentText(bytes: ByteArray): String {
        val text = bytes.toString(Charsets.UTF_8)
            .replace("\u0000", "")
            .trim()
        return truncateText(text)
    }

    private fun truncateText(text: String): String {
        return if (text.length > MAX_EXTRACTED_TEXT_CHARS) {
            text.take(MAX_EXTRACTED_TEXT_CHARS) + "\n\n" +
                LocaleHelper.getString(context, "attachment_text_truncated")
        } else {
            text
        }
    }

    companion object {
        fun resolveMimeTypeFromName(fileName: String?): String {
            return fileName?.let { java.net.URLConnection.guessContentTypeFromName(it) }
                ?: "application/octet-stream"
        }

        const val MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024
        const val MAX_ORIGINAL_IMAGE_BYTES = 1536 * 1024
        const val MAX_IMAGE_DIMENSION = 1536
        const val IMAGE_JPEG_QUALITY = 86
        const val MIN_IMAGE_JPEG_QUALITY = 66
        const val MAX_EXTRACTED_TEXT_CHARS = 120_000
        const val MAX_ATTACHMENT_CONTEXT_CHARS = 4_000

        val TEXT_LIKE_MIME_TYPES = setOf(
            "application/json",
            "application/xml",
            "application/javascript",
            "application/x-javascript",
            "application/typescript",
            "application/csv",
            "application/sql",
            "application/rtf",
            "application/yaml",
            "application/x-yaml",
            "application/x-sh",
            "application/x-httpd-php",
            "application/graphql",
            "application/ld+json",
            "application/x-latex",
            "application/x-tex",
            "application/toml",
            "application/x-toml",
            "application/x-properties"
        )

        val TEXT_LIKE_EXTENSIONS = setOf(
            "txt", "md", "markdown", "csv", "tsv", "log", "rtf",
            "json", "xml", "html", "htm", "css", "js", "jsx", "ts", "tsx",
            "svg", "graphql", "gql",
            "kt", "kts", "java", "gradle", "groovy", "scala",
            "py", "rb", "php", "pl", "pm", "lua", "r",
            "c", "cpp", "h", "hpp", "cs", "swift", "go", "rs", "dart",
            "sh", "bash", "zsh", "bat", "cmd", "ps1", "psm1",
            "env", "ini", "cfg", "conf", "properties", "toml",
            "yaml", "yml", "dockerfile",
            "sql", "proto", "graphql",
            "tex", "latex", "rst", "adoc", "org",
            "diff", "patch", "gitignore", "editorconfig",
            "makefile", "cmake", "tf", "tfvars", "hcl"
        )
    }
}
