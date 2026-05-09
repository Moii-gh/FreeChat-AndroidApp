package com.example.chatapp.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import com.example.chatapp.LocaleHelper
import com.example.chatapp.R
import com.example.chatapp.util.FileUtils
import com.example.chatapp.util.bounce
import com.example.chatapp.util.dpToPx
import com.example.chatapp.util.slideAndFadeIn

/**
 * Рендерер сообщений чата.
 * Создаёт View для пользовательских и ассистентских сообщений,
 * включая вложения (изображения, файлы), блоки кода, кнопки действий.
 */
class ChatMessageRenderer(
    private val context: Context,
    private val messagesContainer: LinearLayout,
    private val popupMenuHelper: PopupMenuHelper,
    private val onRegenerate: (AssistantMessageWrapper) -> Unit,
    private val onUserMessageLongClick: (View, String, Int) -> Unit,
    private val onAssistantContentChanged: () -> Unit,
    private val onAssistantReactionChanged: (String, String?) -> Unit,
    private val onOpenLink: (String) -> Unit
) {

    // ──────── Сообщения пользователя ────────

    /** Добавляет текстовое сообщение пользователя */
    fun addUserMessage(message: String, historyIndex: Int) {
        val tv = TextView(context).apply {
            text = message
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            textSize = 16f
            setBackgroundResource(R.drawable.bg_user_message)
            setPadding(32, 20, 32, 20)
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.75).toInt()
        }
        attachUserMessageLongClick(tv, message, historyIndex)
        messagesContainer.addView(
            tv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
                setMargins(0, 0, 0, 24)
            }
        )
        tv.slideAndFadeIn()
    }

    /** Добавляет сообщение пользователя с картинкой */
    fun addUserMessageWithImage(message: String, imageUri: Uri, historyIndex: Int) {
        val density = context.resources.displayMetrics.density
        val sizePx = (80 * density).toInt()
        val imageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            setBackgroundResource(R.drawable.preview_background)
            try {
                setImageURI(imageUri)
            } catch (e: Exception) {
                Toast.makeText(context, LocaleHelper.getString(context, "toast_error"), Toast.LENGTH_SHORT).show()
            }
        }
        attachUserMessageLongClick(imageView, message, historyIndex)
        messagesContainer.addView(
            imageView,
            LinearLayout.LayoutParams(sizePx, sizePx).apply {
                gravity = Gravity.END
                setMargins(64, 0, 0, (4 * density).toInt())
            }
        )
        imageView.slideAndFadeIn()
        imageView.setOnClickListener { FileUtils.openUri(context, imageUri) }

        if (message.isNotEmpty()) {
            addUserMessage(message, historyIndex)
        }
    }

    fun addUserMessageWithImageData(
        message: String,
        base64Data: String,
        mimeType: String?,
        fileName: String?,
        historyIndex: Int
    ) {
        val density = context.resources.displayMetrics.density
        val sizePx = (80 * density).toInt()
        val imageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            setBackgroundResource(R.drawable.preview_background)
            runCatching {
                val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                setImageBitmap(bitmap)
            }.onFailure {
                setImageResource(R.drawable.ic_image)
                setColorFilter(Color.WHITE)
            }
        }
        attachUserMessageLongClick(imageView, message, historyIndex)
        messagesContainer.addView(
            imageView,
            LinearLayout.LayoutParams(sizePx, sizePx).apply {
                gravity = Gravity.END
                setMargins(64, 0, 0, (4 * density).toInt())
            }
        )
        imageView.slideAndFadeIn()
        imageView.setOnClickListener {
            FileUtils.openBase64File(context, base64Data, fileName ?: "image.png", mimeType ?: "image/png")
        }

        if (message.isNotEmpty()) {
            addUserMessage(message, historyIndex)
        }
    }

    /** Добавляет сообщение пользователя с файлом */
    fun addUserMessageWithFile(message: String, fileUri: Uri, historyIndex: Int) {
        val density = context.resources.displayMetrics.density
        val fileContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_user_message)
            val pV = (12 * density).toInt()
            val pH = (16 * density).toInt()
            setPadding(pH, pV, pH, pV)
        }
        val fileIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_file_new)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt()).apply {
                marginEnd = (12 * density).toInt()
            }
        }
        val tvFileName = TextView(context).apply {
            text = FileUtils.getFileName(context, fileUri)
            setTextColor(Color.WHITE)
            textSize = 15f
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val caretTv = TextView(context).apply {
            text = "›"
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 24f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (12 * density).toInt()
            }
            setPadding(0, 0, 0, (4 * density).toInt())
        }
        fileContainer.addView(fileIcon)
        fileContainer.addView(tvFileName)
        fileContainer.addView(caretTv)
        attachUserMessageLongClick(fileContainer, message, historyIndex)
        messagesContainer.addView(
            fileContainer,
            LinearLayout.LayoutParams((260 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
                setMargins(64, 0, 0, (4 * density).toInt())
            }
        )
        fileContainer.slideAndFadeIn()
        fileContainer.setOnClickListener { FileUtils.openUri(context, fileUri) }

        if (message.isNotEmpty()) {
            addUserMessage(message, historyIndex)
        }
    }

    fun addUserMessageWithFileData(
        message: String,
        base64Data: String,
        mimeType: String?,
        fileName: String?,
        historyIndex: Int
    ) {
        val density = context.resources.displayMetrics.density
        val fileContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_user_message)
            val pV = (12 * density).toInt()
            val pH = (16 * density).toInt()
            setPadding(pH, pV, pH, pV)
        }
        val fileIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_file_new)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt()).apply {
                marginEnd = (12 * density).toInt()
            }
        }
        val tvFileName = TextView(context).apply {
            text = fileName?.takeIf { it.isNotBlank() } ?: LocaleHelper.getString(context, "label_file_analysis")
            setTextColor(Color.WHITE)
            textSize = 15f
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val caretTv = TextView(context).apply {
            text = "вЂє"
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 24f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (12 * density).toInt()
            }
            setPadding(0, 0, 0, (4 * density).toInt())
        }
        fileContainer.addView(fileIcon)
        fileContainer.addView(tvFileName)
        fileContainer.addView(caretTv)
        attachUserMessageLongClick(fileContainer, message, historyIndex)
        messagesContainer.addView(
            fileContainer,
            LinearLayout.LayoutParams((260 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
                setMargins(64, 0, 0, (4 * density).toInt())
            }
        )
        fileContainer.slideAndFadeIn()
        fileContainer.setOnClickListener {
            FileUtils.openBase64File(context, base64Data, fileName, mimeType)
        }

        if (message.isNotEmpty()) {
            addUserMessage(message, historyIndex)
        }
    }

    /**
     * Восстановленное сообщение из БД: определяет тип вложения,
     * проверяет доступность URI, при необходимости показывает заглушку.
     */
    fun renderRestoredUserMessage(
        content: String,
        imageUrl: String?,
        attachmentData: String?,
        attachmentMimeType: String?,
        attachmentFileName: String?,
        historyIndex: Int
    ) {
        val baseText = if (content.contains("\n\n[")) content.split("\n\n[")[0] else content
        val storedAttachmentData = attachmentData?.takeIf { it.isNotBlank() }
            ?: imageUrl?.takeIf { it.startsWith("data:", ignoreCase = true) }?.substringAfter(",", "")

        if (storedAttachmentData != null) {
            val resolvedMimeType = attachmentMimeType
                ?: imageUrl?.takeIf { it.startsWith("data:", ignoreCase = true) }
                    ?.substringAfter("data:")
                    ?.substringBefore(";")
            if (resolvedMimeType?.startsWith("image/", ignoreCase = true) == true) {
                addUserMessageWithImageData(baseText, storedAttachmentData, resolvedMimeType, attachmentFileName, historyIndex)
            } else {
                addUserMessageWithFileData(baseText, storedAttachmentData, resolvedMimeType, attachmentFileName, historyIndex)
            }
            return
        }

        var isUriReadable = false
        var isImageUri = false
        var safeUri: Uri? = null

        if (imageUrl != null && imageUrl.startsWith("content://")) {
            safeUri = Uri.parse(imageUrl)
            try {
                (context as? android.app.Activity)?.contentResolver?.openInputStream(safeUri)?.close()
                isUriReadable = true
                val mimeType = (context as? android.app.Activity)?.contentResolver?.getType(safeUri)
                isImageUri = mimeType?.startsWith("image/") == true
            } catch (e: Exception) {
                isUriReadable = false
            }
        }

        if (isUriReadable && safeUri != null) {
            if (isImageUri) {
                addUserMessageWithImage(baseText, safeUri, historyIndex)
            } else {
                addUserMessageWithFile(baseText, safeUri, historyIndex)
            }
        } else if (imageUrl != null) {
            val isPhoto = content.contains("[Извлеченный текст из фото]") || content.contains("[Extracted text from photo]") || imageUrl.contains("image")
            val title = LocaleHelper.getString(context, "label_file_analysis")
            val icon = if (isPhoto) R.drawable.ic_camera else R.drawable.ic_file_new
            addUserMessageSimulatedAttachment(baseText, title, icon, imageUrl, historyIndex)
        } else {
            addUserMessage(content, historyIndex)
        }
    }

    /** Заглушка вложения (URI мёртв, но мы знаем что файл был) */
    private fun addUserMessageSimulatedAttachment(
        message: String,
        title: String,
        iconRes: Int,
        imageUrl: String?,
        historyIndex: Int
    ) {
        val density = context.resources.displayMetrics.density
        val fileContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_user_message)
            val pV = (12 * density).toInt()
            val pH = (16 * density).toInt()
            setPadding(pH, pV, pH, pV)
        }
        val fileIcon = ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt()).apply {
                marginEnd = (12 * density).toInt()
            }
        }
        val tvFileName = TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 15f
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        fileContainer.addView(fileIcon)
        fileContainer.addView(tvFileName)
        if (imageUrl != null) fileContainer.tag = imageUrl
        attachUserMessageLongClick(fileContainer, message, historyIndex)

        messagesContainer.addView(
            fileContainer,
            LinearLayout.LayoutParams((200 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
                setMargins(64, 0, 0, (4 * density).toInt())
            }
        )
        fileContainer.slideAndFadeIn()

        val uriStr = fileContainer.tag as? String
        if (uriStr != null) {
            fileContainer.setOnClickListener { FileUtils.openUri(context, Uri.parse(uriStr)) }
        }

        if (message.isNotEmpty() && message != LocaleHelper.getString(context, "attachment_empty_text")) {
            addUserMessage(message, historyIndex)
        }
    }

    private fun attachUserMessageLongClick(view: View, message: String, historyIndex: Int) {
        view.setTag(R.id.user_message_history_index, historyIndex)
        view.isLongClickable = true
        view.setOnLongClickListener {
            onUserMessageLongClick(view, message, historyIndex)
            true
        }
    }

    // ──────── Сообщения ассистента ────────

    /**
     * Создаёт блок ответа ассистента с кнопками действий (copy, like, dislike, share, more).
     * Возвращает wrapper для дальнейшего обновления через streaming.
     */
    fun addAssistantMessage(
        text: String,
        animate: Boolean = true,
        isImageMode: Boolean = false,
        messageSyncId: String? = null,
        reaction: String? = null
    ): AssistantMessageWrapper {
        val rootContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val contentArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        rootContainer.addView(contentArea)

        val wrapper = AssistantMessageWrapper(
            rootContainer, contentArea,
            { FileUtils.copyToClipboard(context, it) },
            { FileUtils.shareText(context, it) },
            context,
            onOpenLink
        ).apply {
            this.messageSyncId = messageSyncId
            this.reaction = normalizeReaction(reaction)
        }
        val shouldUseImageMode = isImageMode || AssistantMessageWrapper.containsImageReply(text)
        wrapper.isImageMode = shouldUseImageMode
        val thinkingText = LocaleHelper.getString(context, "ai_thinking")

        if (shouldUseImageMode && text.isEmpty()) {
            wrapper.showImageLoadingState()
        } else if (text.isNotEmpty()) {
            wrapper.updateContent(text, animate, isFinal = text != thinkingText)
        }

        // Кнопки действий
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icons = intArrayOf(
            R.drawable.ic_copy,
            R.drawable.ic_thumb_up,
            R.drawable.ic_thumb_down,
            R.drawable.ic_share,
            R.drawable.ic_more_vertical
        )
        val density = context.resources.displayMetrics.density
        val iconSizePx = (14 * density).toInt()
        val paddingPx = (4 * density).toInt()
        val touchAreaPx = iconSizePx + paddingPx * 2
        val iconMarginPx = (12 * density).toInt()

        var btnLike: ImageButton? = null
        var btnDislike: ImageButton? = null
        var currentReaction = wrapper.reaction

        icons.forEachIndexed { index, icon ->
            val btn = ImageButton(context).apply {
                setImageResource(icon)
                setBackgroundResource(android.R.color.transparent)
                setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
                setColorFilter(Color.parseColor("#B3B3B3"))
                scaleType = ImageView.ScaleType.FIT_CENTER

                if (index == 1) btnLike = this
                if (index == 2) btnDislike = this

                setOnClickListener {
                    this.bounce()
                    when (index) {
                        0 -> {
                            val imageUrl = AssistantMessageWrapper.extractImageUrl(wrapper.rawText)
                            if (wrapper.isImageMode && imageUrl?.startsWith("data:image", ignoreCase = true) == true) {
                                val b64 = imageUrl.substringAfter("base64,")
                                FileUtils.copyImageBase64(context, b64)
                            } else if (wrapper.isImageMode && AssistantMessageWrapper.isLocalImageUrl(imageUrl)) {
                                FileUtils.copyImageUri(context, Uri.parse(imageUrl))
                            } else {
                                wrapper.copyAction(wrapper.rawText)
                            }
                        }
                        1 -> {
                            currentReaction = if (currentReaction == REACTION_LIKE) null else REACTION_LIKE
                            wrapper.reaction = currentReaction
                            updateReactionButtons(
                                btnLike,
                                btnDislike,
                                currentReaction,
                                animate = true,
                                visibleWidth = touchAreaPx,
                                visibleMarginEnd = iconMarginPx
                            )
                            wrapper.messageSyncId?.let { syncId ->
                                onAssistantReactionChanged(syncId, currentReaction)
                            }
                        }
                        2 -> {
                            currentReaction = if (currentReaction == REACTION_DISLIKE) null else REACTION_DISLIKE
                            wrapper.reaction = currentReaction
                            updateReactionButtons(
                                btnLike,
                                btnDislike,
                                currentReaction,
                                animate = true,
                                visibleWidth = touchAreaPx,
                                visibleMarginEnd = iconMarginPx
                            )
                            wrapper.messageSyncId?.let { syncId ->
                                onAssistantReactionChanged(syncId, currentReaction)
                            }
                        }
                        3 -> {
                            val imageUrl = AssistantMessageWrapper.extractImageUrl(wrapper.rawText)
                            if (wrapper.isImageMode && imageUrl?.startsWith("data:image", ignoreCase = true) == true) {
                                val b64 = imageUrl.substringAfter("base64,")
                                FileUtils.shareImageBase64(context, b64)
                            } else if (wrapper.isImageMode && AssistantMessageWrapper.isLocalImageUrl(imageUrl)) {
                                FileUtils.shareImageUri(context, Uri.parse(imageUrl))
                            } else {
                                wrapper.shareAction(wrapper.rawText)
                            }
                        }
                        4 -> popupMenuHelper.showAssistantMessageOptionsMenu(this@apply, wrapper)
                    }
                }
            }
            btnRow.addView(
                btn,
                LinearLayout.LayoutParams(touchAreaPx, touchAreaPx).apply {
                    setMargins(0, 0, iconMarginPx, 0)
                }
            )
        }
        updateReactionButtons(
            btnLike,
            btnDislike,
            currentReaction,
            animate = false,
            visibleWidth = touchAreaPx,
            visibleMarginEnd = iconMarginPx
        )

        rootContainer.addView(
            btnRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(-paddingPx, 0, 0, 0)
            }
        )

        btnRow.visibility = if (text.isNotEmpty() && text != thinkingText) View.VISIBLE else View.GONE
        wrapper.btnRow = btnRow

        messagesContainer.addView(
            rootContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.START
                setMargins(0, 0, 0, 32)
            }
        )

        rootContainer.slideAndFadeIn()
        onAssistantContentChanged()

        return wrapper
    }

    private fun updateReactionButtons(
        likeButton: ImageButton?,
        dislikeButton: ImageButton?,
        reaction: String?,
        animate: Boolean,
        visibleWidth: Int,
        visibleMarginEnd: Int
    ) {
        setReactionButtonState(
            button = likeButton,
            isActive = reaction == REACTION_LIKE,
            isVisibleChoice = reaction == null || reaction == REACTION_LIKE,
            animate = animate,
            visibleWidth = visibleWidth,
            visibleMarginEnd = visibleMarginEnd
        )
        setReactionButtonState(
            button = dislikeButton,
            isActive = reaction == REACTION_DISLIKE,
            isVisibleChoice = reaction == null || reaction == REACTION_DISLIKE,
            animate = animate,
            visibleWidth = visibleWidth,
            visibleMarginEnd = visibleMarginEnd
        )
    }

    private fun setReactionButtonState(
        button: ImageButton?,
        isActive: Boolean,
        isVisibleChoice: Boolean,
        animate: Boolean,
        visibleWidth: Int,
        visibleMarginEnd: Int
    ) {
        button ?: return
        val targetColor = Color.parseColor(if (isActive) "#FFFFFF" else "#B3B3B3")
        val targetAlpha = when {
            !isVisibleChoice -> 0f
            isActive -> 1f
            else -> 0.86f
        }
        val targetScale = if (isVisibleChoice) 1f else 0.72f
        val targetWidth = if (isVisibleChoice) visibleWidth else 0
        val targetMarginEnd = if (isVisibleChoice) visibleMarginEnd else 0
        button.animate().cancel()
        button.isSelected = isActive
        button.isEnabled = isVisibleChoice
        button.isClickable = isVisibleChoice
        if (!animate) {
            button.setColorFilter(targetColor)
            button.alpha = targetAlpha
            button.scaleX = targetScale
            button.scaleY = targetScale
            (button.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                params.width = targetWidth
                params.marginEnd = targetMarginEnd
                button.layoutParams = params
            }
            button.visibility = if (isVisibleChoice) View.VISIBLE else View.GONE
            button.tag = targetColor
            return
        }

        if (isVisibleChoice) {
            button.visibility = View.VISIBLE
        }

        val currentColor = (button.tag as? Int) ?: Color.parseColor("#B3B3B3")
        ValueAnimator.ofArgb(currentColor, targetColor).apply {
            duration = 140L
            addUpdateListener {
                val color = it.animatedValue as Int
                button.setColorFilter(color)
                button.tag = color
            }
            start()
        }
        (button.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            val startWidth = params.width
            val startMarginEnd = params.marginEnd
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 140L
                addUpdateListener {
                    val fraction = it.animatedFraction
                    params.width = (startWidth + (targetWidth - startWidth) * fraction).toInt()
                    params.marginEnd = (startMarginEnd + (targetMarginEnd - startMarginEnd) * fraction).toInt()
                    button.layoutParams = params
                }
                start()
            }
        }
        button.animate()
            .alpha(targetAlpha)
            .scaleX(targetScale)
            .scaleY(targetScale)
            .setDuration(140L)
            .withEndAction {
                if (!isVisibleChoice) {
                    button.visibility = View.GONE
                }
            }
            .start()
    }

    private fun normalizeReaction(reaction: String?): String? =
        when (reaction) {
            REACTION_LIKE, REACTION_DISLIKE -> reaction
            else -> null
        }

    companion object {
        const val REACTION_LIKE = "like"
        const val REACTION_DISLIKE = "dislike"

        /**
         * Создаёт виджет блока кода с заголовком (язык) и кнопкой копирования.
         */
        fun createCodeBlockView(context: Context, codeContent: String, language: String): Pair<View, TextView> {
            val density = context.resources.displayMetrics.density
            val displayLanguage = normalizeCodeLanguageLabel(language)
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(context, R.drawable.bg_code_block)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, (8 * density).toInt(), 0, (8 * density).toInt()) }
            }

            val header = android.widget.RelativeLayout(context).apply {
                background = ContextCompat.getDrawable(context, R.drawable.bg_code_header)
                setPadding((14 * density).toInt(), (9 * density).toInt(), (12 * density).toInt(), (9 * density).toInt())
            }

            val langText = TextView(context).apply {
                text = displayLanguage
                setTextColor(Color.parseColor("#C9D1D9"))
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                layoutParams = android.widget.RelativeLayout.LayoutParams(
                    android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    addRule(android.widget.RelativeLayout.ALIGN_PARENT_START)
                    addRule(android.widget.RelativeLayout.CENTER_VERTICAL)
                }
            }
            header.addView(langText)

            val copyLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = android.widget.RelativeLayout.LayoutParams(
                    android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    addRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
                    addRule(android.widget.RelativeLayout.CENTER_VERTICAL)
                }

                val copyIcon = ImageView(context).apply {
                    setImageResource(R.drawable.ic_copy)
                    setColorFilter(Color.parseColor("#C9D1D9"))
                    layoutParams = LinearLayout.LayoutParams((14 * density).toInt(), (14 * density).toInt()).apply {
                        marginEnd = (6 * density).toInt()
                    }
                }
                val copyText = TextView(context).apply {
                    text = LocaleHelper.getString(context, "code_copy")
                    setTextColor(Color.parseColor("#C9D1D9"))
                    textSize = 12f
                }

                addView(copyIcon)
                addView(copyText)

                setOnClickListener {
                    FileUtils.copyToClipboard(context, codeContent)
                    copyText.text = LocaleHelper.getString(context, "code_copied")
                    copyIcon.setColorFilter(Color.parseColor("#FFFFFF"))
                    postDelayed({
                        copyText.text = LocaleHelper.getString(context, "code_copy")
                        copyIcon.setColorFilter(Color.parseColor("#C9D1D9"))
                    }, 2000)
                }
            }
            header.addView(copyLayout)
            container.addView(header)

            val codeText = TextView(context).apply {
                text = codeContent
                setTextColor(Color.parseColor("#D6DEEB"))
                textSize = 14f
                typeface = Typeface.MONOSPACE
                includeFontPadding = false
                setLineSpacing((2 * density), 1.0f)
                setHorizontallyScrolling(true)
                setPadding((14 * density).toInt(), (13 * density).toInt(), (14 * density).toInt(), (13 * density).toInt())
                SelectableTextSupport.configure(this)
                minWidth = (context.resources.displayMetrics.widthPixels - (48 * density).toInt()).coerceAtLeast(0)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val horizontalScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = true
                isFillViewport = true
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                addView(codeText)
            }

            container.addView(horizontalScroll)
            return Pair(container, codeText)
        }

        private fun normalizeCodeLanguageLabel(language: String): String {
            return when (language.trim().lowercase()) {
                "kt", "kts", "kotlin" -> "Kotlin"
                "java" -> "Java"
                "js", "jsx", "javascript" -> "JavaScript"
                "ts", "tsx", "typescript" -> "TypeScript"
                "py", "python" -> "Python"
                "html", "xhtml" -> "HTML"
                "css", "scss", "sass" -> "CSS"
                "xml", "svg", "xaml" -> "XML"
                "json", "jsonc" -> "JSON"
                "sql" -> "SQL"
                "sh", "shell", "bash", "zsh", "powershell", "ps1" -> "Bash"
                "" -> "Code"
                else -> language.trim().replaceFirstChar { it.uppercase() }
            }
        }
    }
}
