package com.example.chatapp.ui.chat

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.example.chatapp.AttachmentPayload
import com.example.chatapp.ChatAttachmentHelper
import com.example.chatapp.LocaleHelper
import com.example.chatapp.PendingAttachment
import com.example.chatapp.R
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
    var retainedEditingAttachment: AttachmentPayload? = null
    var assistantHandoffAttachmentPath: String? = null

    val currentPreviewUri: Uri?
        get() = chatViewModel.pendingInputAttachments.firstOrNull()?.uri

    val currentAttachments: List<PendingAttachment>
        get() = chatViewModel.pendingInputAttachments.toList()

    val hasAttachment: Boolean
        get() = chatViewModel.pendingInputAttachments.isNotEmpty() || retainedEditingAttachment != null

    fun showFilePreview(fileUri: Uri) {
        retainedEditingAttachment = null
        addAttachments(listOf(pendingAttachmentFromUri(fileUri)))
    }

    fun addAttachments(attachments: List<PendingAttachment>) {
        if (attachments.isEmpty()) return
        val wasEmpty = !hasAttachment
        retainedEditingAttachment = null
        chatViewModel.addPendingInputAttachments(attachments)
        render()
        if (wasEmpty) onPreviewShown() else onPreviewChanged()
    }

    fun renderCurrentAttachments() {
        render()
        if (hasAttachment) onPreviewChanged()
    }

    fun clearPreview() {
        val hadAttachment = hasAttachment
        chatViewModel.clearPendingInputAttachments()
        retainedEditingAttachment = null
        binding.previewItemsContainer.removeAllViews()
        binding.previewContainer.isGone = true
        assistantHandoffAttachmentPath?.let { path ->
            runCatching { File(path).delete() }
        }
        assistantHandoffAttachmentPath = null
        if (hadAttachment) onPreviewCleared()
    }

    fun showRetainedAttachmentPreview(payload: AttachmentPayload?) {
        chatViewModel.clearPendingInputAttachments()
        retainedEditingAttachment = payload
        render()
        if (payload == null) {
            onPreviewCleared()
        } else {
            onPreviewChanged()
        }
    }

    private fun render() {
        binding.previewItemsContainer.removeAllViews()

        val attachments = chatViewModel.pendingInputAttachments
        when {
            attachments.isNotEmpty() -> {
                binding.previewContainer.isVisible = true
                attachments.forEachIndexed { index, attachment ->
                    binding.previewItemsContainer.addView(
                        createPendingAttachmentView(attachment, index),
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            marginEnd = dp(8)
                        }
                    )
                }
            }
            retainedEditingAttachment != null -> {
                binding.previewContainer.isVisible = true
                binding.previewItemsContainer.addView(
                    createRetainedAttachmentView(retainedEditingAttachment!!),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            else -> {
                binding.previewContainer.isGone = true
            }
        }
    }

    private fun createPendingAttachmentView(attachment: PendingAttachment, index: Int): View {
        val label = attachment.displayName
            ?.takeIf { it.isNotBlank() }
            ?: FileUtils.getFileName(context, attachment.uri)
        return createAttachmentCard(
            uri = attachment.uri,
            base64Data = null,
            mimeType = attachment.mimeType,
            fileName = label,
            onRemove = {
                chatViewModel.removePendingInputAttachment(index)
                render()
                if (hasAttachment) onPreviewChanged() else onPreviewCleared()
            }
        )
    }

    private fun createRetainedAttachmentView(payload: AttachmentPayload): View {
        return createAttachmentCard(
            uri = payload.fileUri.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() },
            base64Data = payload.base64Data,
            mimeType = payload.mimeType,
            fileName = payload.fileName?.takeIf { it.isNotBlank() }
                ?: LocaleHelper.getString(context, "label_file_analysis"),
            onRemove = {
                retainedEditingAttachment = null
                render()
                onPreviewCleared()
            }
        )
    }

    private fun createAttachmentCard(
        uri: Uri?,
        base64Data: String?,
        mimeType: String,
        fileName: String,
        onRemove: () -> Unit
    ): View {
        val isImage = mimeType.startsWith("image/", ignoreCase = true)
        val width = if (isImage) dp(80) else dp(184)
        val height = dp(80)

        val root = FrameLayout(context).apply {
            setBackgroundResource(com.example.chatapp.R.drawable.preview_background)
            clipToOutline = true
            isClickable = false
            isFocusable = false
        }

        if (isImage) {
            val imageView = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            root.addView(
                imageView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            val size = dp(80)
            when {
                !base64Data.isNullOrBlank() -> SafeImageLoader.loadBase64Image(
                    imageView = imageView,
                    base64Data = base64Data,
                    fileName = fileName,
                    widthPx = size,
                    heightPx = size
                )
                uri != null -> SafeImageLoader.loadUri(imageView, uri, size, size)
                else -> imageView.setImageResource(R.drawable.ic_image)
            }
        } else {
            val fileRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(30), dp(10))
            }
            val icon = ImageView(context).apply {
                setImageResource(R.drawable.ic_file_new)
                setColorFilter(Color.parseColor("#D1D1D6"))
            }
            fileRow.addView(
                icon,
                LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    marginEnd = dp(10)
                }
            )
            val textColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            val nameView = TextView(context).apply {
                text = fileName
                setTextColor(Color.WHITE)
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            val typeView = TextView(context).apply {
                text = mimeType.substringAfterLast('/').uppercase()
                setTextColor(Color.parseColor("#8E8E93"))
                textSize = 10f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            textColumn.addView(nameView)
            textColumn.addView(typeView)
            fileRow.addView(
                textColumn,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            root.addView(
                fileRow,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        val removeButton = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            setBackgroundResource(R.drawable.circle_button_bg)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            isClickable = true
            isFocusable = true
            contentDescription = LocaleHelper.getString(context, "content_desc_remove_attachment")
            setOnClickListener { onRemove() }
        }
        root.addView(
            removeButton,
            FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(5), dp(5), 0)
            }
        )

        return root.apply {
            layoutParams = LinearLayout.LayoutParams(width, height)
        }
    }

    private fun pendingAttachmentFromUri(uri: Uri): PendingAttachment {
        val fileName = FileUtils.getFileName(context, uri).takeIf { it.isNotBlank() }
        val mimeType = context.contentResolver.getType(uri)
            ?.takeIf { it.isNotBlank() }
            ?: ChatAttachmentHelper.resolveMimeTypeFromName(fileName)
        val sizeBytes = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (index == -1 || cursor.isNull(index)) null else cursor.getLong(index)
                }
        }.getOrNull()

        return PendingAttachment(
            uri = uri,
            displayName = fileName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            sourceUri = uri.toString(),
            isLocalCopy = false
        )
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
