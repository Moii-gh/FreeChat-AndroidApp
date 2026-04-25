package com.example.chatapp.ui

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
    private val messagesScrollView: ScrollView,
    private val popupMenuHelper: PopupMenuHelper,
    private val onRegenerate: (AssistantMessageWrapper) -> Unit,
    private val onUserMessageLongClick: (View, String, Int) -> Unit
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

    /**
     * Восстановленное сообщение из БД: определяет тип вложения,
     * проверяет доступность URI, при необходимости показывает заглушку.
     */
    fun renderRestoredUserMessage(content: String, imageUrl: String?, historyIndex: Int) {
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

        val baseText = if (content.contains("\n\n[")) content.split("\n\n[")[0] else content

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
    fun addAssistantMessage(text: String, animate: Boolean = true, isImageMode: Boolean = false): AssistantMessageWrapper {
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
            messagesScrollView
        )
        val shouldUseImageMode = isImageMode || AssistantMessageWrapper.containsImageReply(text)
        wrapper.isImageMode = shouldUseImageMode

        if (shouldUseImageMode && text.isEmpty()) {
            wrapper.showImageLoadingState()
        } else if (text.isNotEmpty()) {
            wrapper.updateContent(text, animate)
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
                            if (wrapper.isImageMode && wrapper.rawText.contains("base64,")) {
                                val b64 = wrapper.rawText.substringAfter("base64,").substringBefore(")")
                                FileUtils.copyImageBase64(context, b64)
                            } else {
                                wrapper.copyAction(wrapper.rawText)
                            }
                        }
                        1 -> {
                            Toast.makeText(context, LocaleHelper.getString(context, "toast_liked"), Toast.LENGTH_SHORT).show()
                            setColorFilter(Color.WHITE)
                            btnDislike?.animate()?.scaleX(0f)?.scaleY(0f)?.setDuration(150)?.withEndAction {
                                btnDislike?.visibility = View.GONE
                            }?.start()
                        }
                        2 -> {
                            Toast.makeText(context, LocaleHelper.getString(context, "toast_disliked"), Toast.LENGTH_SHORT).show()
                            setColorFilter(Color.WHITE)
                            btnLike?.animate()?.scaleX(0f)?.scaleY(0f)?.setDuration(150)?.withEndAction {
                                btnLike?.visibility = View.GONE
                            }?.start()
                        }
                        3 -> {
                            if (wrapper.isImageMode && wrapper.rawText.contains("base64,")) {
                                val b64 = wrapper.rawText.substringAfter("base64,").substringBefore(")")
                                FileUtils.shareImageBase64(context, b64)
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

        rootContainer.addView(
            btnRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(-paddingPx, 0, 0, 0)
            }
        )

        val thinkingText = LocaleHelper.getString(context, "ai_thinking")
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

        messagesScrollView.post {
            messagesScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }

        return wrapper
    }

    companion object {
        /**
         * Создаёт виджет блока кода с заголовком (язык) и кнопкой копирования.
         */
        fun createCodeBlockView(context: Context, codeContent: String, language: String): Pair<View, TextView> {
            val density = context.resources.displayMetrics.density
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(context, R.drawable.bg_code_block)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, (8 * density).toInt(), 0, (8 * density).toInt()) }
            }

            val header = android.widget.RelativeLayout(context).apply {
                background = ContextCompat.getDrawable(context, R.drawable.bg_code_header)
                setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            }

            val langText = TextView(context).apply {
                text = language.ifEmpty { "CODE" }.uppercase()
                setTextColor(Color.parseColor("#B3B3B3"))
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
                    setColorFilter(Color.parseColor("#B3B3B3"))
                    layoutParams = LinearLayout.LayoutParams((14 * density).toInt(), (14 * density).toInt()).apply {
                        marginEnd = (6 * density).toInt()
                    }
                }
                val copyText = TextView(context).apply {
                    text = LocaleHelper.getString(context, "code_copy")
                    setTextColor(Color.parseColor("#B3B3B3"))
                    textSize = 12f
                }

                addView(copyIcon)
                addView(copyText)

                setOnClickListener {
                    FileUtils.copyToClipboard(context, codeContent)
                    copyText.text = LocaleHelper.getString(context, "code_copied")
                    copyIcon.setColorFilter(Color.parseColor("#34C759"))
                    postDelayed({
                        copyText.text = LocaleHelper.getString(context, "code_copy")
                        copyIcon.setColorFilter(Color.parseColor("#B3B3B3"))
                    }, 2000)
                }
            }
            header.addView(copyLayout)
            container.addView(header)

            val codeText = TextView(context).apply {
                text = ""
                setTextColor(Color.parseColor("#E5E5EA"))
                textSize = 14f
                typeface = Typeface.MONOSPACE
                setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
                setTextIsSelectable(true)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            container.addView(codeText)
            return Pair(container, codeText)
        }
    }
}
