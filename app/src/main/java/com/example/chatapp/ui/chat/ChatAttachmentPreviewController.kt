package com.example.chatapp.ui.chat

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.example.chatapp.AttachmentPayload
import com.example.chatapp.LocaleHelper
import com.example.chatapp.databinding.ActivityMainBinding
import com.example.chatapp.util.FileUtils
import com.example.chatapp.util.SafeImageLoader
import com.example.chatapp.viewmodel.ChatViewModel
import java.io.File

internal class ChatAttachmentPreviewController(
    private val context: Context,
    private val binding: ActivityMainBinding,
    private val chatViewModel: ChatViewModel,
    private val onPreviewShown: () -> Unit,
    private val onPreviewCleared: () -> Unit,
    private val onPreviewChanged: () -> Unit
) {
    var currentPreviewUri: Uri? = null
        private set
    var retainedEditingAttachment: AttachmentPayload? = null
    var assistantHandoffAttachmentPath: String? = null

    val hasAttachment: Boolean
        get() = currentPreviewUri != null || retainedEditingAttachment != null

    fun showFilePreview(fileUri: Uri) {
        retainedEditingAttachment = null
        currentPreviewUri = fileUri
        chatViewModel.selectedFileUri = fileUri
        val mimeType = context.contentResolver.getType(fileUri).orEmpty()

        binding.previewContainer.isVisible = true
        if (mimeType.startsWith("image/")) {
            binding.previewImage.isVisible = true
            binding.previewFileContainer.isGone = true
            val size = binding.previewImage.width.takeIf { it > 0 } ?: (72 * context.resources.displayMetrics.density).toInt()
            SafeImageLoader.loadUri(binding.previewImage, fileUri, size, size)
        } else {
            binding.previewImage.isGone = true
            binding.previewFileContainer.isVisible = true
            binding.previewFileName.text = FileUtils.getFileName(context, fileUri)
        }
        onPreviewShown()
    }

    fun clearPreview() {
        currentPreviewUri = null
        retainedEditingAttachment = null
        chatViewModel.selectedFileUri = null
        binding.previewContainer.isGone = true
        binding.previewImage.setImageDrawable(null)
        binding.previewImage.isGone = true
        binding.previewFileContainer.isGone = true
        assistantHandoffAttachmentPath?.let { path ->
            runCatching { File(path).delete() }
        }
        assistantHandoffAttachmentPath = null
        onPreviewCleared()
    }

    fun showRetainedAttachmentPreview(payload: AttachmentPayload?) {
        retainedEditingAttachment = payload
        if (payload == null) return

        binding.previewContainer.isVisible = true
        if (payload.mimeType.startsWith("image/", ignoreCase = true)) {
            binding.previewImage.isVisible = true
            binding.previewFileContainer.isGone = true
            val imageSet = payload.base64Data?.let { base64 ->
                val size = binding.previewImage.width.takeIf { it > 0 } ?: (72 * context.resources.displayMetrics.density).toInt()
                SafeImageLoader.loadBase64Image(
                    imageView = binding.previewImage,
                    base64Data = base64,
                    fileName = payload.fileName ?: "preview_image.png",
                    widthPx = size,
                    heightPx = size
                )
                true
            } == true

            if (!imageSet && payload.fileUri.isNotBlank()) {
                val size = binding.previewImage.width.takeIf { it > 0 } ?: (72 * context.resources.displayMetrics.density).toInt()
                runCatching { Uri.parse(payload.fileUri) }.getOrNull()?.let {
                    SafeImageLoader.loadUri(binding.previewImage, it, size, size)
                }
            }
        } else {
            binding.previewImage.isGone = true
            binding.previewImage.setImageDrawable(null)
            binding.previewFileContainer.isVisible = true
            binding.previewFileName.text = payload.fileName?.takeIf { it.isNotBlank() }
                ?: LocaleHelper.getString(context, "label_file_analysis")
        }
        onPreviewChanged()
    }
}
