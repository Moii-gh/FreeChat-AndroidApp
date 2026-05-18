package com.example.chatapp.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64InputStream
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.chatapp.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Утилиты для работы с файлами: получение имени, экспорт в DOCX,
 * операции с base64-изображениями (copy/share), открытие URI.
 */
object FileUtils {
    private const val MAX_CACHE_FILE_BYTES = 20 * 1024 * 1024
    private const val MAX_CLIPBOARD_TEXT_CHARS = 200_000
    private const val CACHE_SHARE_DIR = "shared_files"
    private const val GENERATED_IMAGE_DIR = "generated_images"
    private val imageHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    data class SavedImageFile(
        val uri: Uri,
        val fileName: String,
        val mimeType: String,
        val byteCount: Long
    )

    private data class ShareImageTarget(
        val uri: Uri,
        val mimeType: String
    )

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
            val cacheDir = File(context.cacheDir, CACHE_SHARE_DIR).apply { mkdirs() }
            val file = uniqueFile(cacheDir, safeFileName(fileName))
            writeBase64ToFile(base64Str, file, MAX_CACHE_FILE_BYTES)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            SafeLog.w("FileUtils", "Could not save base64 file to cache", e)
            null
        }
    }

    fun saveBase64FileToPersistentImage(
        context: Context,
        base64Str: String,
        fileName: String?,
        mimeType: String? = null
    ): SavedImageFile? {
        return try {
            val resolvedMimeType = mimeType?.takeIf { it.startsWith("image/", ignoreCase = true) }
                ?: imageMimeTypeFromName(fileName)
                ?: "image/png"
            if (estimatedDecodedSize(base64Str) > MAX_CACHE_FILE_BYTES) {
                return null
            }
            val picturesRoot = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: File(context.filesDir, Environment.DIRECTORY_PICTURES)
            val imageDir = File(picturesRoot, GENERATED_IMAGE_DIR).apply { mkdirs() }
            val resolvedFileName = imageFileName(
                rawName = fileName,
                mimeType = resolvedMimeType,
                prefix = "generated_image_${System.currentTimeMillis()}"
            )
            val file = uniqueFile(imageDir, resolvedFileName)
            val byteCount = writeBase64ToFile(base64Str, file, MAX_CACHE_FILE_BYTES)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            SavedImageFile(uri, file.name, resolvedMimeType, byteCount)
        } catch (e: Exception) {
            SafeLog.w("FileUtils", "Could not persist generated image", e)
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
        launchFileOperation(
            context = context,
            failureMessage = LocaleHelper.getString(context, "toast_open_attachment_error"),
            operation = {
                saveBase64FileToCache(context.applicationContext, base64Str, fileName)
            },
            onSuccess = { uri ->
                if (uri == null) {
                    Toast.makeText(context, LocaleHelper.getString(context, "toast_open_attachment_error"), Toast.LENGTH_SHORT).show()
                } else {
                    openUri(context, uri, mimeType)
                }
            }
        )
    }

    /** Копирует base64-изображение в буфер обмена через временный файл */
    fun copyImageBase64(context: Context, base64Str: String) {
        launchFileOperation(
            context = context,
            failureMessage = LocaleHelper.getString(context, "toast_image_copy_error"),
            operation = {
                saveBase64ToCache(context.applicationContext, base64Str)
            },
            onSuccess = { uri ->
                if (uri == null) {
                    Toast.makeText(context, LocaleHelper.getString(context, "toast_image_copy_error"), Toast.LENGTH_SHORT).show()
                } else {
                    copyImageUri(context, uri)
                }
            }
        )
    }

    fun copyImageUri(context: Context, uri: Uri) {
        runCatching {
            val safeUri = shareableUri(context, uri) ?: error("Image URI is not readable")
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newUri(context.contentResolver, "image", safeUri)
            clipboard.setPrimaryClip(clip)
        }.onSuccess {
            Toast.makeText(context, LocaleHelper.getString(context, "toast_image_copied"), Toast.LENGTH_SHORT).show()
        }.onFailure {
            SafeLog.w("FileUtils", "Could not copy image", it)
            Toast.makeText(context, LocaleHelper.getString(context, "toast_image_copy_error"), Toast.LENGTH_SHORT).show()
        }
    }

    /** Шарит base64-изображение через Intent.ACTION_SEND */
    fun shareImageBase64(context: Context, base64Str: String) {
        launchFileOperation(
            context = context,
            failureMessage = LocaleHelper.getString(context, "toast_share_error"),
            operation = {
                saveBase64ToCache(context.applicationContext, base64Str)
            },
            onSuccess = { uri ->
                if (uri == null) {
                    Toast.makeText(context, LocaleHelper.getString(context, "toast_share_error"), Toast.LENGTH_SHORT).show()
                } else {
                    shareImageUri(context, uri, "image/png")
                }
            }
        )
    }

    /** Шарит текст через Intent.ACTION_SEND */
    fun shareImageFromUrl(context: Context, imageUrl: String?) {
        if (imageUrl.isNullOrBlank()) {
            Toast.makeText(context, LocaleHelper.getString(context, "toast_share_error"), Toast.LENGTH_SHORT).show()
            return
        }

        launchFileOperation(
            context = context,
            failureMessage = LocaleHelper.getString(context, "toast_share_error"),
            operation = {
                prepareImageForShare(context.applicationContext, imageUrl)
            },
            onSuccess = { target ->
                if (target == null) {
                    Toast.makeText(context, LocaleHelper.getString(context, "toast_share_error"), Toast.LENGTH_SHORT).show()
                } else {
                    shareImageUri(context, target.uri, target.mimeType)
                }
            }
        )
    }

    fun shareImageUri(context: Context, uri: Uri, mimeType: String? = null) {
        val safeUri = shareableUri(context, uri)
        if (safeUri == null) {
            Toast.makeText(context, LocaleHelper.getString(context, "toast_share_error"), Toast.LENGTH_SHORT).show()
            return
        }
        val resolvedMimeType = mimeType?.takeIf { it.isNotBlank() }
            ?: runCatching { context.contentResolver.getType(safeUri) }.getOrNull()
            ?: imageMimeTypeFromName(safeUri.toString())
            ?: "image/png"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = resolvedMimeType
            putExtra(Intent.EXTRA_STREAM, safeUri)
            clipData = ClipData.newUri(context.contentResolver, "image", safeUri)
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
        if (text.length > MAX_CLIPBOARD_TEXT_CHARS) {
            Toast.makeText(
                context,
                LocaleHelper.getString(context, "toast_text_too_large_to_copy"),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("copied_text", text))
            Toast.makeText(context, LocaleHelper.getString(context, "toast_copied"), Toast.LENGTH_SHORT).show()
        } catch (error: RuntimeException) {
            SafeLog.w("FileUtils", "Could not copy text", error)
            Toast.makeText(
                context,
                LocaleHelper.getString(context, "toast_text_too_large_to_copy"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Открывает URI в системном приложении по умолчанию */
    fun openUri(context: Context, uri: Uri?, mimeType: String? = null) {
        if (uri == null) return
        try {
            val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
            val intent = if (scheme == "http" || scheme == "https") {
                Intent(Intent.ACTION_VIEW, uri)
            } else {
                val safeUri = shareableUri(context, uri) ?: error("File is not readable")
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        safeUri,
                        mimeType?.takeIf { it.isNotBlank() }
                            ?: context.contentResolver.getType(safeUri)
                            ?: imageMimeTypeFromName(safeUri.toString())
                            ?: "*/*"
                    )
                    clipData = ClipData.newUri(context.contentResolver, "attachment", safeUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            if (context !is android.app.Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                LocaleHelper.formatString(context, "toast_open_attachment_error_with_message", e.message.orEmpty()),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun saveImageFromUrl(context: Context, imageUrl: String?) {
        if (imageUrl.isNullOrBlank()) {
            Toast.makeText(context, LocaleHelper.getString(context, "toast_share_error"), Toast.LENGTH_SHORT).show()
            return
        }

        launchFileOperation(
            context = context,
            failureMessage = LocaleHelper.getString(context, "toast_share_error"),
            operation = {
                when {
                    imageUrl.startsWith("data:image", ignoreCase = true) -> {
                        val mimeType = imageUrl.substringAfter("data:", "")
                            .substringBefore(";")
                            .takeIf { it.startsWith("image/", ignoreCase = true) }
                            ?: "image/png"
                        saveBase64ImageToPictures(
                            context = context.applicationContext,
                            base64Str = imageUrl.substringAfter(",", ""),
                            fileName = imageFileName(null, mimeType, "freechat_${System.currentTimeMillis()}"),
                            mimeType = mimeType
                        )
                    }
                    imageUrl.startsWith("content://", ignoreCase = true) ||
                        imageUrl.startsWith("file://", ignoreCase = true) -> {
                        val uri = Uri.parse(imageUrl)
                        saveImageUriToPictures(
                            context = context.applicationContext,
                            uri = uri,
                            fileName = fileNameFromUri(context, uri),
                            mimeType = context.contentResolver.getType(shareableUri(context, uri) ?: uri)
                        )
                    }
                    else -> false
                }
            },
            onSuccess = { saved ->
                if (saved == true) {
                    Toast.makeText(context, LocaleHelper.getString(context, "button_save"), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, LocaleHelper.getString(context, "toast_share_error"), Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun prepareImageForShare(context: Context, imageUrl: String): ShareImageTarget? {
        return when {
            imageUrl.startsWith("data:image", ignoreCase = true) -> {
                val mimeType = imageUrl.substringAfter("data:", "")
                    .substringBefore(";")
                    .takeIf { it.startsWith("image/", ignoreCase = true) }
                    ?: "image/png"
                saveBase64FileToCache(
                    context = context,
                    base64Str = imageUrl.substringAfter(",", ""),
                    fileName = imageFileName(null, mimeType, "shared_image_${System.currentTimeMillis()}")
                )?.let { ShareImageTarget(it, mimeType) }
            }
            imageUrl.startsWith("content://", ignoreCase = true) ||
                imageUrl.startsWith("file://", ignoreCase = true) -> {
                val uri = Uri.parse(imageUrl)
                val safeUri = shareableUri(context, uri) ?: return null
                val mimeType = runCatching { context.contentResolver.getType(safeUri) }.getOrNull()
                    ?.takeIf { it.startsWith("image/", ignoreCase = true) }
                    ?: imageMimeTypeFromName(imageUrl)
                    ?: "image/png"
                ShareImageTarget(safeUri, mimeType)
            }
            imageUrl.startsWith("http://", ignoreCase = true) ||
                imageUrl.startsWith("https://", ignoreCase = true) -> {
                downloadImageToCache(context, imageUrl)
            }
            else -> null
        }
    }

    private fun downloadImageToCache(context: Context, imageUrl: String): ShareImageTarget? =
        runCatching {
            val request = Request.Builder()
                .url(imageUrl)
                .get()
                .build()
            imageHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                val contentLength = body.contentLength()
                if (contentLength > MAX_CACHE_FILE_BYTES) return@use null
                val mimeType = body.contentType()
                    ?.toString()
                    ?.substringBefore(";")
                    ?.lowercase(Locale.US)
                    ?.takeIf { it.startsWith("image/") }
                    ?: imageMimeTypeFromName(imageUrl)
                    ?: return@use null
                val cacheDir = File(context.cacheDir, CACHE_SHARE_DIR).apply { mkdirs() }
                val file = uniqueFile(
                    directory = cacheDir,
                    requestedName = imageFileName(
                        rawName = Uri.parse(imageUrl).lastPathSegment,
                        mimeType = mimeType,
                        prefix = "shared_image_${System.currentTimeMillis()}"
                    )
                )
                body.byteStream().use { input ->
                    writeStreamToFile(input, file, MAX_CACHE_FILE_BYTES)
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                ShareImageTarget(uri, mimeType)
            }
        }.onFailure {
            SafeLog.w("FileUtils", "Could not download image for sharing", it)
        }.getOrNull()

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
        val payload = base64.substringAfter(",", base64)
        val compactLength = payload.count { !it.isWhitespace() }
        return (compactLength * 3) / 4
    }

    private fun writeBase64ToFile(base64: String, file: File, maxBytes: Int): Long {
        val payload = base64.substringAfter(",", base64)
        var total = 0L
        Base64InputStream(
            ByteArrayInputStream(payload.toByteArray(Charsets.US_ASCII)),
            android.util.Base64.DEFAULT
        ).use { input ->
            FileOutputStream(file).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                    if (total > maxBytes) {
                        file.delete()
                        throw IllegalArgumentException("File is too large")
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
        if (total <= 0L) {
            file.delete()
            throw IllegalArgumentException("File is empty")
        }
        return total
    }

    private fun writeStreamToFile(input: java.io.InputStream, file: File, maxBytes: Int): Long {
        var total = 0L
        FileOutputStream(file).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > maxBytes) {
                    file.delete()
                    throw IllegalArgumentException("File is too large")
                }
                output.write(buffer, 0, read)
            }
        }
        if (total <= 0L) {
            file.delete()
            throw IllegalArgumentException("File is empty")
        }
        return total
    }

    private fun uniqueFile(directory: File, requestedName: String): File {
        val safeName = safeFileName(requestedName)
        val base = safeName.substringBeforeLast('.', safeName)
        val extension = safeName.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() && it != safeName }
            ?.let { ".$it" }
            .orEmpty()
        var candidate = File(directory, safeName)
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(directory, "${base}_$suffix$extension")
            suffix++
        }
        return candidate
    }

    private fun imageFileName(rawName: String?, mimeType: String, prefix: String): String {
        val extension = extensionForImageMimeType(mimeType)
        val baseName = rawName
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: prefix
        return "${safeFileName(baseName)}.$extension"
    }

    private fun extensionForImageMimeType(mimeType: String): String =
        when (mimeType.lowercase(Locale.US)) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "png"
        }

    private fun shareableUri(context: Context, uri: Uri): Uri? {
        val scheme = uri.scheme?.lowercase(Locale.US)
        return when (scheme) {
            "content" -> uri.takeIf { canOpenUri(context, it) }
            "file", null, "" -> {
                val path = if (scheme == "file") uri.path else uri.toString()
                val file = path?.let(::File)?.takeIf { it.exists() && it.isFile } ?: return null
                runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }.getOrNull()
            }
            else -> null
        }
    }

    private fun canOpenUri(context: Context, uri: Uri): Boolean =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { true } == true
        }.getOrDefault(false)

    private fun saveBase64ImageToPictures(
        context: Context,
        base64Str: String,
        fileName: String,
        mimeType: String
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }
        if (estimatedDecodedSize(base64Str) > MAX_CACHE_FILE_BYTES) {
            return false
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/FreeChat")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                Base64InputStream(
                    ByteArrayInputStream(base64Str.substringAfter(",", base64Str).toByteArray(Charsets.US_ASCII)),
                    android.util.Base64.DEFAULT
                ).use { input -> input.copyTo(output) }
            } ?: error("Output stream unavailable")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }.getOrElse {
            SafeLog.w("FileUtils", "Could not save image to gallery", it)
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    private fun saveImageUriToPictures(
        context: Context,
        uri: Uri,
        fileName: String?,
        mimeType: String?
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }
        val safeUri = shareableUri(context, uri) ?: return false
        val resolvedMimeType = mimeType?.takeIf { it.startsWith("image/", ignoreCase = true) }
            ?: context.contentResolver.getType(safeUri)
            ?: imageMimeTypeFromName(fileName)
            ?: "image/png"
        val resolvedFileName = imageFileName(
            rawName = fileName,
            mimeType = resolvedMimeType,
            prefix = "freechat_${System.currentTimeMillis()}"
        )
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, resolvedFileName)
            put(MediaStore.Images.Media.MIME_TYPE, resolvedMimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/FreeChat")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val targetUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return runCatching {
            resolver.openInputStream(safeUri)?.use { input ->
                resolver.openOutputStream(targetUri)?.use { output ->
                    input.copyTo(output)
                } ?: error("Output stream unavailable")
            } ?: error("Input stream unavailable")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(targetUri, values, null, null)
            true
        }.getOrElse {
            SafeLog.w("FileUtils", "Could not copy image to gallery", it)
            runCatching { resolver.delete(targetUri, null, null) }
            false
        }
    }

    private fun fileNameFromUri(context: Context, uri: Uri): String? =
        runCatching { getFileName(context, uri).takeIf { it.isNotBlank() } }.getOrNull()

    private fun <T> launchFileOperation(
        context: Context,
        failureMessage: String,
        operation: () -> T,
        onSuccess: (T) -> Unit
    ) {
        val scope = (context as? LifecycleOwner)?.lifecycleScope
            ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching(operation)
            }
            if (context is android.app.Activity && (context.isFinishing || context.isDestroyed)) {
                return@launch
            }
            result.onSuccess(onSuccess).onFailure {
                SafeLog.w("FileUtils", "File operation failed", it)
                Toast.makeText(context, failureMessage, Toast.LENGTH_SHORT).show()
            }
        }
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
