package com.example.chatapp.assistant

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.chatapp.BuildConfig
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class DigitalAssistantHandoff(
    val draftText: String,
    val chatId: String?,
    val attachmentPath: String?,
    val attachmentMimeType: String?,
    val attachmentFileName: String?
) {
    fun attachmentUri(context: Context): Uri? {
        val path = attachmentPath ?: return null
        val file = File(path)
        if (!file.exists()) return null
        return FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file
        )
    }
}

class DigitalAssistantHandoffStore(private val context: Context) {
    private val dir: File = File(context.cacheDir, DIR_NAME)

    fun saveDraft(draftText: String, attachment: AssistantAttachment?): String {
        val token = UUID.randomUUID().toString()
        val tokenDir = File(dir, token).apply { mkdirs() }
        val copiedAttachment = attachment?.cacheFilePath?.let { sourcePath ->
            val source = File(sourcePath)
            if (source.exists()) {
                val target = File(tokenDir, attachment.fileName)
                source.copyTo(target, overwrite = true)
                target.absolutePath
            } else {
                null
            }
        }
        File(tokenDir, HANDOFF_FILE).writeText(
            JSONObject().apply {
                put("draftText", draftText)
                copiedAttachment?.let { put("attachmentPath", it) }
                attachment?.mimeType?.let { put("attachmentMimeType", it) }
                attachment?.fileName?.let { put("attachmentFileName", it) }
            }.toString(),
            Charsets.UTF_8
        )
        return token
    }

    fun saveChat(chatId: String): String {
        val token = UUID.randomUUID().toString()
        val tokenDir = File(dir, token).apply { mkdirs() }
        File(tokenDir, HANDOFF_FILE).writeText(
            JSONObject().apply {
                put("chatId", chatId)
            }.toString(),
            Charsets.UTF_8
        )
        return token
    }

    fun consume(token: String): DigitalAssistantHandoff? {
        val tokenDir = File(dir, token)
        val file = File(tokenDir, HANDOFF_FILE)
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            DigitalAssistantHandoff(
                draftText = json.optString("draftText", ""),
                chatId = json.optString("chatId").takeIf { it.isNotBlank() },
                attachmentPath = json.optString("attachmentPath").takeIf { it.isNotBlank() },
                attachmentMimeType = json.optString("attachmentMimeType").takeIf { it.isNotBlank() },
                attachmentFileName = json.optString("attachmentFileName").takeIf { it.isNotBlank() }
            )
        }.getOrNull().also {
            if (it?.chatId != null) {
                tokenDir.deleteRecursively()
            }
        }
    }

    companion object {
        const val EXTRA_HANDOFF_ID = "com.example.chatapp.assistant.EXTRA_HANDOFF_ID"
        private const val DIR_NAME = "digital_assistant_handoff"
        private const val HANDOFF_FILE = "handoff.json"
        private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L

        fun cleanupOldFiles(context: Context) {
            val now = System.currentTimeMillis()
            listOf(
                File(context.cacheDir, DIR_NAME),
                File(context.cacheDir, ScreenCaptureManager.TEMP_DIR_NAME)
            ).forEach { root ->
                root.listFiles()?.forEach { file ->
                    if (now - file.lastModified() > MAX_AGE_MS) {
                        file.deleteRecursively()
                    }
                }
            }
        }
    }
}

