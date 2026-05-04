package com.example.chatapp.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.chatapp.LocaleHelper
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Утилиты для работы с файлами: получение имени, экспорт в DOCX,
 * операции с base64-изображениями (copy/share), открытие URI.
 */
object FileUtils {
    private const val MAX_CACHE_FILE_BYTES = 20 * 1024 * 1024
    private const val CACHE_SHARE_DIR = "shared_files"

    /** Извлекает имя файла из content:// URI через ContentResolver */
    fun getFileName(context: Context, uri: Uri): String {
        var name = LocaleHelper.getString(context, "toast_error")
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) name = cursor.getString(idx)
                }
            }
        } catch (e: SecurityException) {
            SafeLog.w("FileUtils", "No permission to read file name", e)
            uri.path?.let { path ->
                val fallback = path.substringAfterLast('/', "")
                name = if (fallback.isNotBlank() && !fallback.startsWith("document")) {
                    fallback
                } else {
                    LocaleHelper.getString(context, "toast_error")
                }
            }
        } catch (e: Exception) {
            SafeLog.w("FileUtils", "Could not read file name", e)
        }
        return name
    }

    /** Сохраняет base64-строку как PNG в кэш и возвращает FileProvider URI для шаринга */
    fun saveBase64ToCache(context: Context, base64Str: String): Uri? {
        return saveBase64FileToCache(context, base64Str, "shared_image_${System.currentTimeMillis()}.png")
    }

    fun saveBase64FileToCache(context: Context, base64Str: String, fileName: String?): Uri? {
        return try {
            if (estimatedDecodedSize(base64Str) > MAX_CACHE_FILE_BYTES) {
                return null
            }
            val bytes = android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT)
            if (bytes.size > MAX_CACHE_FILE_BYTES) {
                return null
            }
            val cacheDir = File(context.cacheDir, CACHE_SHARE_DIR).apply { mkdirs() }
            val file = File(cacheDir, safeFileName(fileName))
            file.writeBytes(bytes)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            SafeLog.w("FileUtils", "Could not save base64 file to cache", e)
            null
        }
    }

    fun localImageUriToBase64(
        context: Context,
        uriString: String?,
        fallbackMimeType: String? = null
    ): Pair<String, String>? {
        val uri = uriString
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "content" && scheme != "file") {
            return null
        }

        val mimeType = fallbackMimeType
            ?.takeIf { it.startsWith("image/", ignoreCase = true) }
            ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
                ?.takeIf { it.startsWith("image/", ignoreCase = true) }
            ?: imageMimeTypeFromName(uriString)
            ?: return null

        return runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytesLimited(MAX_CACHE_FILE_BYTES)
            } ?: return null
            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            base64 to mimeType
        }.getOrNull()
    }

    fun localImageUriToDataUrl(
        context: Context,
        uriString: String?,
        fallbackMimeType: String? = null
    ): String? {
        val (base64, mimeType) = localImageUriToBase64(context, uriString, fallbackMimeType)
            ?: return null
        return "data:$mimeType;base64,$base64"
    }

    fun openBase64File(context: Context, base64Str: String, fileName: String?, mimeType: String?) {
        val uri = saveBase64FileToCache(context, base64Str, fileName)
        if (uri == null) {
            Toast.makeText(context, LocaleHelper.getString(context, "toast_open_attachment_error"), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, mimeType?.takeIf { it.isNotBlank() } ?: "*/*")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                LocaleHelper.formatString(context, "toast_open_attachment_error_with_message", e.message.orEmpty()),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** Копирует base64-изображение в буфер обмена через временный файл */
    fun copyImageBase64(context: Context, base64Str: String) {
        val uri = saveBase64ToCache(context, base64Str)
        if (uri != null) {
            copyImageUri(context, uri)
        } else {
            Toast.makeText(context, LocaleHelper.getString(context, "toast_image_copy_error"), Toast.LENGTH_SHORT).show()
        }
    }

    fun copyImageUri(context: Context, uri: Uri) {
        runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newUri(context.contentResolver, "image", uri)
            clipboard.setPrimaryClip(clip)
        }.onSuccess {
            Toast.makeText(context, LocaleHelper.getString(context, "toast_image_copied"), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, LocaleHelper.getString(context, "toast_image_copy_error"), Toast.LENGTH_SHORT).show()
        }
    }

    /** Шарит base64-изображение через Intent.ACTION_SEND */
    fun shareImageBase64(context: Context, base64Str: String) {
        val uri = saveBase64ToCache(context, base64Str)
        if (uri != null) {
            shareImageUri(context, uri, "image/png")
        } else {
            Toast.makeText(context, LocaleHelper.getString(context, "toast_share_error"), Toast.LENGTH_SHORT).show()
        }
    }

    /** Шарит текст через Intent.ACTION_SEND */
    fun shareImageUri(context: Context, uri: Uri, mimeType: String? = null) {
        val resolvedMimeType = mimeType?.takeIf { it.isNotBlank() }
            ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: "image/png"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = resolvedMimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, LocaleHelper.getString(context, "share_image")).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        runCatching {
            context.startActivity(chooser)
        }.onFailure {
            Toast.makeText(context, LocaleHelper.getString(context, "toast_share_error"), Toast.LENGTH_SHORT).show()
        }
    }

    fun shareText(context: Context, text: String) {
        val chooser = Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            },
            LocaleHelper.getString(context, "share")
        ).apply {
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        runCatching {
            context.startActivity(chooser)
        }.onFailure {
            Toast.makeText(context, LocaleHelper.getString(context, "toast_share_error"), Toast.LENGTH_SHORT).show()
        }
    }

    /** Копирует текст в системный буфер обмена */
    fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("copied_text", text))
        Toast.makeText(context, LocaleHelper.getString(context, "toast_copied"), Toast.LENGTH_SHORT).show()
    }

    /** Открывает URI в системном приложении по умолчанию */
    fun openUri(context: Context, uri: Uri?) {
        if (uri == null) return
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                LocaleHelper.formatString(context, "toast_open_attachment_error_with_message", e.message.orEmpty()),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun safeFileName(fileName: String?): String {
        val fallback = "attachment_${System.currentTimeMillis()}"
        val raw = fileName?.takeIf { it.isNotBlank() } ?: fallback
        return raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120).ifBlank { fallback }
    }

    private fun imageMimeTypeFromName(value: String?): String? {
        return when (value?.substringBefore('?')?.substringAfterLast('.', "")?.lowercase(Locale.US)) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> null
        }
    }

    private fun estimatedDecodedSize(base64: String): Int {
        val compactLength = base64.count { !it.isWhitespace() }
        return (compactLength * 3) / 4
    }

    private fun java.io.InputStream.readBytesLimited(maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) {
                throw IllegalArgumentException("File is too large")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    /**
     * Экспорт истории чата в формат DOCX (Open XML).
     * Создает zip-архив с минимальной DOCX-структурой.
     */
    fun exportChatToDocx(context: Context, chatHistory: List<JSONObject>) {
        if (chatHistory.isEmpty()) {
            Toast.makeText(context, LocaleHelper.getString(context, "toast_chat_empty"), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val fileName = "ChatHistory_${System.currentTimeMillis()}.docx"
            val file = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS), fileName)
            val fos = java.io.FileOutputStream(file)
            val zos = java.util.zip.ZipOutputStream(fos)

            // [Content_Types].xml
            val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""
            zos.putNextEntry(java.util.zip.ZipEntry("[Content_Types].xml"))
            zos.write(contentTypes.toByteArray())
            zos.closeEntry()

            // _rels/.rels
            val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""
            zos.putNextEntry(java.util.zip.ZipEntry("_rels/.rels"))
            zos.write(rels.toByteArray())
            zos.closeEntry()

            // word/document.xml
            val sb = StringBuilder()
            sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
""")

            for (msg in chatHistory) {
                val isUser = msg.optString("role") == "user"
                val textContent = msg.optString("content", "")
                val sender = if (isUser) {
                    LocaleHelper.getString(context, "export_sender_you")
                } else {
                    LocaleHelper.getString(context, "export_sender_bot")
                }
                val text = textContent
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")

                sb.append("    <w:p><w:r><w:rPr><w:b/></w:rPr><w:t>${sender}:</w:t></w:r></w:p>\n")

                for (line in text.split("\n")) {
                    if (line.isNotEmpty()) {
                        sb.append("    <w:p><w:r><w:t xml:space=\"preserve\">${line}</w:t></w:r></w:p>\n")
                    }
                }
                sb.append("    <w:p><w:r><w:t></w:t></w:r></w:p>\n")
            }

            sb.append("  </w:body>\n</w:document>")

            zos.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
            zos.write(sb.toString().toByteArray())
            zos.closeEntry()

            zos.close()
            fos.close()

            Toast.makeText(
                context,
                LocaleHelper.formatString(context, "toast_doc_saved_with_path", file.absolutePath),
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            SafeLog.w("FileUtils", "DOCX export failed", e)
            Toast.makeText(context, LocaleHelper.getString(context, "toast_doc_error"), Toast.LENGTH_SHORT).show()
        }
    }
}
