package com.example.chatapp.ui

import android.animation.ValueAnimator
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.core.text.util.LinkifyCompat
import coil.load
import com.example.chatapp.browser.BrowserUrlSanitizer
import com.example.chatapp.LocaleHelper
import com.example.chatapp.R
import com.example.chatapp.util.SafeLog
import com.example.chatapp.util.SyntaxHighlighter
import com.example.chatapp.util.dpToPx
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import java.util.regex.Pattern

/**
 * Обёртка для блока ответа ассистента.
 * Управляет контентом (текст / код / изображение), shimmer-анимациями,
 * рендерингом Markdown через Markwon.
 */
class AssistantMessageWrapper(
    val container: LinearLayout,
    val contentArea: LinearLayout,
    val copyAction: (String) -> Unit,
    val shareAction: (String) -> Unit,
    private val context: android.content.Context,
    private val onOpenLink: (String) -> Unit
) {
    companion object {
        val LINK_COLOR: Int = Color.rgb(10, 132, 255)
        private val IMAGE_MARKDOWN_PATTERN = Pattern.compile("!\\[.*?\\]\\((.*?)\\)")

        fun extractImageUrl(text: String): String? {
            val matcher = IMAGE_MARKDOWN_PATTERN.matcher(text)
            return if (matcher.find()) matcher.group(1)?.trim() else null
        }

        fun containsImageReply(text: String): Boolean {
            val imageUrl = extractImageUrl(text) ?: return false
            return isRenderableImageUrl(imageUrl)
        }

        fun isRenderableImageUrl(imageUrl: String?): Boolean {
            if (imageUrl.isNullOrBlank()) {
                return false
            }

            return imageUrl.startsWith("http://", ignoreCase = true) ||
                imageUrl.startsWith("https://", ignoreCase = true) ||
                imageUrl.startsWith("data:image", ignoreCase = true) ||
                imageUrl.startsWith("content://", ignoreCase = true) ||
                imageUrl.startsWith("file://", ignoreCase = true)
        }

        fun isLocalImageUrl(imageUrl: String?): Boolean {
            if (imageUrl.isNullOrBlank()) {
                return false
            }

            return imageUrl.startsWith("content://", ignoreCase = true) ||
                imageUrl.startsWith("file://", ignoreCase = true)
        }
    }

    var rawText: String = ""
    var btnRow: View? = null
    var messageSyncId: String? = null
    var reaction: String? = null

    var isImageMode = false
    var imageContainer: FrameLayout? = null
    var shimmerBg: View? = null
    var imageStatusText: TextView? = null
    var imageIcon: ImageView? = null
    var imageViewResult: ImageView? = null
    var currentImageUrl: String? = null
    private var sourcesButtonContainer: LinearLayout? = null

    private var pulseAnimator: ValueAnimator? = null
    private var textShimmerAnimator: ValueAnimator? = null
    private val markwon = Markwon.builder(context)
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: io.noties.markwon.core.MarkwonTheme.Builder) {
                builder.linkColor(LINK_COLOR)
                    .isLinkUnderlined(true)
            }

            override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                builder.linkResolver { _, link -> openSourceUrl(link) }
            }
        })
        .build()
    private var lastRenderedText: String? = null
    private var lastRenderedWasFinal = false
    private var streamingTextView: TextView? = null

    private data class SourceLink(
        val title: String,
        val url: String
    )

    private data class ParsedReply(
        val content: String,
        val sources: List<SourceLink>
    )

    /** Показывает шиммер-заглушку при генерации изображения */
    fun showImageLoadingState() {
        contentArea.removeAllViews()
        sourcesButtonContainer = null
        val density = context.resources.displayMetrics.density

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (8 * density).toInt())
        }

        imageIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_image)
            setColorFilter(Color.parseColor("#8E8E93"))
            layoutParams = LinearLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt()).apply {
                marginEnd = (8 * density).toInt()
            }
        }

        imageStatusText = TextView(context).apply {
            text = LocaleHelper.getString(context, "image_creating")
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 14f
        }

        header.addView(imageIcon)
        header.addView(imageStatusText)

        imageContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                (300 * density).toInt(), (300 * density).toInt()
            ).apply {
                topMargin = (4 * density).toInt()
                bottomMargin = (8 * density).toInt()
            }
            background = ContextCompat.getDrawable(context, R.drawable.preview_background)
            clipToOutline = true
        }

        shimmerBg = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            background = ContextCompat.getDrawable(context, R.drawable.bg_shimmer_image)
            alpha = 0.5f
        }
        imageContainer?.addView(shimmerBg)

        imageViewResult = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        imageContainer?.addView(imageViewResult)

        pulseAnimator = ValueAnimator.ofFloat(0.5f, 1f).apply {
            duration = 1000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim -> shimmerBg?.alpha = anim.animatedValue as Float }
            start()
        }

        contentArea.addView(header)
        imageContainer?.let { contentArea.addView(it) }
    }

    /** Применяет текстовый shimmer-эффект к "Думаю..." */
    private fun applyTextShimmer(textView: TextView) {
        textShimmerAnimator?.cancel()
        val paint = textView.paint
        val text = textView.text.toString()
        if (text.isEmpty()) return

        val width = paint.measureText(text)
        if (width <= 0f) return

        val shader = LinearGradient(
            0f, 0f, width / 2f, 0f,
            intArrayOf(Color.parseColor("#8E8E93"), Color.WHITE, Color.parseColor("#8E8E93")),
            null, Shader.TileMode.MIRROR
        )
        paint.shader = shader

        val matrix = Matrix()
        textShimmerAnimator = ValueAnimator.ofFloat(0f, width).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                if (textView.paint.shader != null) {
                    matrix.setTranslate(anim.animatedValue as Float, 0f)
                    shader.setLocalMatrix(matrix)
                    textView.invalidate()
                }
            }
            start()
        }
    }

    /** Останавливает все аниматоры (вызывать при пересоздании контента) */
    fun cancelAnimators() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        textShimmerAnimator?.cancel()
        textShimmerAnimator = null
    }

    private fun ensureImageContainer() {
        if (imageContainer == null) {
            val density = context.resources.displayMetrics.density
            imageContainer = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (300 * density).toInt(), (300 * density).toInt()
                ).apply {
                    topMargin = (8 * density).toInt()
                    bottomMargin = (8 * density).toInt()
                }
                background = ContextCompat.getDrawable(context, R.drawable.preview_background)
                clipToOutline = true
            }

            shimmerBg = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                background = ContextCompat.getDrawable(context, R.drawable.bg_shimmer_image)
                alpha = 0.5f
            }
            imageContainer?.addView(shimmerBg)

            imageViewResult = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.CENTER_CROP
                visibility = View.GONE
            }
            imageContainer?.addView(imageViewResult)

            contentArea.addView(imageContainer)
        }
    }

    private fun ensureImageLoadingState(statusText: String? = null) {
        if (imageContainer == null) {
            showImageLoadingState()
        }

        imageIcon?.setImageResource(R.drawable.ic_image)
        imageIcon?.setColorFilter(Color.parseColor("#8E8E93"))
        shimmerBg?.visibility = View.VISIBLE
        imageViewResult?.visibility = View.GONE
        imageStatusText?.text = if (statusText.isNullOrBlank()) {
            LocaleHelper.getString(context, "image_creating")
        } else {
            statusText
        }
    }

    private fun parseReplySources(reply: String): ParsedReply {
        val normalized = reply.replace("\r\n", "\n")
        val lines = normalized.split("\n")
        val headerIndex = lines.indexOfLast {
            it.trim().equals("Источники:", ignoreCase = true)
        }
        if (headerIndex < 0) {
            return ParsedReply(reply, emptyList())
        }

        val sources = lines.drop(headerIndex + 1)
            .mapNotNull { parseSourceLine(it) }
            .distinctBy { it.url }

        if (sources.isEmpty()) {
            return ParsedReply(reply, emptyList())
        }

        val content = lines.take(headerIndex)
            .joinToString("\n")
            .trimEnd()
        return ParsedReply(content, sources)
    }

    private fun parseSourceLine(line: String): SourceLink? {
        val trimmed = line.trim()
        val separatorIndex = trimmed.indexOf(". ")
        if (separatorIndex <= 0 || trimmed.take(separatorIndex).any { !it.isDigit() }) {
            return null
        }

        val markdownLink = trimmed.substring(separatorIndex + 2).trim()
        if (!markdownLink.startsWith("[")) {
            return null
        }

        val titleEnd = markdownLink.lastIndexOf("](")
        if (titleEnd <= 0 || !markdownLink.endsWith(")")) {
            return null
        }

        val title = markdownLink.substring(1, titleEnd)
            .replace("\\[", "[")
            .replace("\\]", "]")
            .trim()
        val url = markdownLink.substring(titleEnd + 2, markdownLink.length - 1).trim()
        if (!url.startsWith("http", ignoreCase = true)) {
            return null
        }

        return SourceLink(
            title = title.ifBlank { url },
            url = url
        )
    }

    private fun removeSourcesButton() {
        sourcesButtonContainer?.let { contentArea.removeView(it) }
        sourcesButtonContainer = null
    }

    private fun renderSourcesButton(sources: List<SourceLink>) {
        if (sources.isEmpty()) {
            return
        }

        val button = TextView(context).apply {
            text = LocaleHelper.formatString(context, "sources_count", sources.size)
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 13f
            gravity = Gravity.CENTER
            minHeight = 34.dpToPx()
            setPadding(14.dpToPx(), 7.dpToPx(), 14.dpToPx(), 7.dpToPx())
            background = roundedDrawable(
                fillColor = Color.parseColor("#2C2C2E"),
                cornerRadiusPx = 999.dpToPx(),
                strokeColor = Color.parseColor("#3A3A3C"),
                strokeWidthPx = 1.dpToPx()
            )
            isClickable = true
            isFocusable = true
            val icon = ContextCompat.getDrawable(context, R.drawable.ic_link)?.mutate()
            icon?.setTint(Color.parseColor("#CCCCCC"))
            setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
            compoundDrawablePadding = 6.dpToPx()
            setOnClickListener { showSourcesSheet(sources) }
        }

        sourcesButtonContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 6.dpToPx()
                bottomMargin = 4.dpToPx()
            }
            addView(button, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        contentArea.addView(sourcesButtonContainer)
    }

    private fun showSourcesSheet(sources: List<SourceLink>) {
        val dialog = BottomSheetDialog(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx(), 12.dpToPx(), 24.dpToPx(), 24.dpToPx())
            background = ContextCompat.getDrawable(context, R.drawable.rounded_dialog_bg)
        }

        val handle = View(context).apply {
            background = roundedDrawable(
                fillColor = Color.parseColor("#4A4A4C"),
                cornerRadiusPx = 999.dpToPx()
            )
            layoutParams = LinearLayout.LayoutParams(42.dpToPx(), 4.dpToPx()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 18.dpToPx()
            }
        }

        val title = TextView(context).apply {
            text = LocaleHelper.getString(context, "sources_title")
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        sources.forEach { source ->
            list.addView(createSourceItem(source) {
                dialog.dismiss()
                openSourceUrl(source.url)
            })
        }

        val scroll = ScrollView(context).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 14.dpToPx()
            }
        }

        val collapseButton = TextView(context).apply {
            text = LocaleHelper.getString(context, "button_collapse")
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(20.dpToPx(), 10.dpToPx(), 20.dpToPx(), 10.dpToPx())
            background = ContextCompat.getDrawable(context, R.drawable.btn_edit_profile_outline)
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 18.dpToPx()
            }
        }

        root.addView(handle)
        root.addView(title)
        root.addView(scroll)
        root.addView(collapseButton)

        dialog.setContentView(root)
        dialog.setOnShowListener { dialogInterface ->
            val sheetDialog = dialogInterface as BottomSheetDialog
            val bottomSheet = sheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.setBackgroundColor(Color.TRANSPARENT)
        }
        dialog.show()
    }

    private fun createSourceItem(source: SourceLink, onClick: () -> Unit): View {
        val item = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dpToPx(), 12.dpToPx(), 14.dpToPx(), 12.dpToPx())
            background = roundedDrawable(
                fillColor = Color.parseColor("#2C2C2E"),
                cornerRadiusPx = 12.dpToPx(),
                strokeColor = Color.parseColor("#3A3A3C"),
                strokeWidthPx = 1.dpToPx()
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8.dpToPx()
            }
        }

        val title = TextView(context).apply {
            text = source.title
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val url = TextView(context).apply {
            text = source.url
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 5.dpToPx(), 0, 0)
        }

        item.addView(title)
        item.addView(url)
        return item
    }

    private fun openSourceUrl(url: String) {
        val safeUrl = BrowserUrlSanitizer.normalize(url)
        if (safeUrl == null) {
            Toast.makeText(context, LocaleHelper.getString(context, "toast_open_link_error"), Toast.LENGTH_SHORT).show()
            return
        }
        onOpenLink(safeUrl)
    }

    private fun roundedDrawable(
        fillColor: Int,
        cornerRadiusPx: Int,
        strokeColor: Int? = null,
        strokeWidthPx: Int = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = cornerRadiusPx.toFloat()
            if (strokeColor != null && strokeWidthPx > 0) {
                setStroke(strokeWidthPx, strokeColor)
            }
        }
    }

    private fun renderStreamingText(reply: String) {
        textShimmerAnimator?.cancel()
        textShimmerAnimator = null
        removeSourcesButton()
        btnRow?.visibility = View.GONE
        btnRow?.animate()?.cancel()
        btnRow?.alpha = 1f

        imageContainer?.let {
            if (it.parent == contentArea) contentArea.removeView(it)
        }
        imageContainer = null
        currentImageUrl = null

        val textView = if (streamingTextView?.parent == contentArea) {
            streamingTextView!!
        } else {
            contentArea.removeAllViews()
            TextView(context).apply {
                tag = "streaming_text"
                setTextColor(ContextCompat.getColor(context, android.R.color.white))
                textSize = 16f
                setLineSpacing(0f, 1.2f)
                setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
                setTextIsSelectable(false)
            }.also { tv ->
                streamingTextView = tv
                contentArea.addView(tv)
            }
        }

        if (textView.text.toString() != reply) {
            textView.text = reply
        }
        lastRenderedText = reply
        lastRenderedWasFinal = false
    }

    /**
     * Главный метод обновления контента.
     * Парсит текст на чанки: обычный текст (Markwon) и блоки кода (SyntaxHighlighter).
     * Использует инкрементальное обновление View для плавности.
     */
    fun updateContent(reply: String, animate: Boolean = true, isFinal: Boolean = false) {
        if (reply == lastRenderedText && isFinal == lastRenderedWasFinal) {
            rawText = reply
            return
        }
        rawText = reply
        val thinkingText = LocaleHelper.getString(context, "ai_thinking")
        val isStatusMessage = reply == thinkingText || 
                reply == "Поиск в сети..." || 
                reply == "Анализ файла..." ||
                reply == "Анализ файлов..." ||
                reply == "Генерация изображения..." || 
                reply == "Редактирование изображения..." ||
                reply.startsWith("Использование инструмента")

        // Обработка статусов (пока ИИ "думает" или выполняет инструмент)
        if (isStatusMessage) {
            removeSourcesButton()
            btnRow?.visibility = View.GONE
            
            // Если в контейнере уже есть сообщения, не заменяем их статусом,
            // просто ждём когда придут реальные данные.
            if (contentArea.childCount == 0) {
                val textView = TextView(context).apply {
                    setTextColor(Color.parseColor("#8E8E93"))
                    textSize = 16f
                    setLineSpacing(0f, 1.2f)
                    setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
                }
                contentArea.addView(textView)
            }
            
            val firstView = contentArea.getChildAt(0)
            if (firstView is TextView && firstView.tag != "content") {
                if (firstView.text != reply) {
                    firstView.text = reply
                    applyTextShimmer(firstView)
                }
            }
            lastRenderedText = reply
            lastRenderedWasFinal = false
            streamingTextView = null
            return
        }

        // Показываем кнопки только когда ответ полностью готов с плавной анимацией
        if (!isFinal) {
            renderStreamingText(reply)
            return
        }

        if (btnRow?.visibility != View.VISIBLE) {
            btnRow?.alpha = 0f
            btnRow?.visibility = View.VISIBLE
            btnRow?.animate()
                ?.alpha(1f)
                ?.setDuration(300L)
                ?.setInterpolator(android.view.animation.DecelerateInterpolator())
                ?.start()
        }
        
        textShimmerAnimator?.cancel()
        textShimmerAnimator = null
        removeSourcesButton()
        
        val parsedReply = parseReplySources(reply)
        val displayReply = parsedReply.content

        // Очищаем статусную строку, если она была первой и мы переходим к контенту
        if (contentArea.childCount > 0) {
            val firstView = contentArea.getChildAt(0) as? TextView
            if (firstView != null && firstView.tag != "content") {
                contentArea.removeViewAt(0)
            }
        }

        // Если есть картинка
        val imageUrl = extractImageUrl(displayReply)?.takeIf { isRenderableImageUrl(it) }
        val hasImage = imageUrl != null
        val cleanReply = if (hasImage) {
            displayReply.replace(Regex("!\\[.*?\\]\\(.*?\\)"), "").trim()
        } else {
            displayReply
        }

        val chunks = MarkdownTableRenderer.splitIntoChunks(cleanReply)

        // Инкрементальное обновление contentArea
        // Мы пытаемся переиспользовать существующие View, чтобы избежать мерцания.
        
        // 1. Синхронизируем количество View с количеством чанков (без учёта imageContainer)
        val currentImageContainer = imageContainer
        val childrenCountWithoutImage = if (currentImageContainer != null && currentImageContainer.parent == contentArea) {
            contentArea.childCount - 1
        } else {
            contentArea.childCount
        }

        // 2. Обновляем существующие или добавляем новые
        chunks.forEachIndexed { index, chunk ->
            val existingView = if (index < childrenCountWithoutImage) contentArea.getChildAt(index) else null
            
            when (chunk) {
                is MarkdownTableRenderer.Chunk.Text -> {
                    val tv = if (existingView is TextView && existingView.tag == "content_text") {
                        existingView
                    } else {
                        if (existingView != null) contentArea.removeViewAt(index)
                        TextView(context).apply {
                            tag = "content_text"
                            setTextColor(ContextCompat.getColor(context, android.R.color.white))
                            textSize = 16f
                            setLineSpacing(0f, 1.2f)
                            setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
                            configureLinkableTextView()
                        }.also { contentArea.addView(it, index) }
                    }
                    // Обновляем текст только если он изменился (для плавности)
                    if (tv.text.toString() != chunk.content) {
                        renderMarkdownWithAutoLinks(tv, chunk.content)
                    }
                }
                is MarkdownTableRenderer.Chunk.Code -> {
                    val container = if (existingView != null && existingView.tag == "content_code") {
                        existingView
                    } else {
                        if (existingView != null) contentArea.removeViewAt(index)
                        val (codeContainer, codeTv) = ChatMessageRenderer.createCodeBlockView(
                            context, chunk.content, chunk.language
                        )
                        codeContainer.tag = "content_code"
                        codeContainer.setTag(R.id.code_text_view, codeTv) // Используем ID для хранения ссылки
                        contentArea.addView(codeContainer, index)
                        codeContainer
                    }
                    val codeTv = container.getTag(R.id.code_text_view) as? TextView
                    if (codeTv != null && codeTv.text.toString() != chunk.content) {
                        codeTv.text = SyntaxHighlighter.highlight(chunk.content, chunk.language)
                    }
                }
                is MarkdownTableRenderer.Chunk.Table -> {
                    // Таблицы сложнее обновлять инкрементально, поэтому если таблица изменилась — пересоздаём View.
                    // Но в стриминге таблицы обычно приходят целиком в конце чанка.
                    val tableContainer = if (existingView != null && existingView.tag == "content_table" && existingView.getTag(R.id.table_raw_content) == chunk.raw) {
                        existingView
                    } else {
                        if (existingView != null) contentArea.removeViewAt(index)
                        MarkdownTableRenderer.createTableView(context, chunk.parsed, chunk.raw).apply {
                            tag = "content_table"
                            setTag(R.id.table_raw_content, chunk.raw)
                        }.also { contentArea.addView(it, index) }
                    }
                }
            }
        }

        // 3. Удаляем лишние View (если текст сократился, что редко для стримингового ответа)
        val newChildrenCountWithoutImage = chunks.size
        while (contentArea.childCount > newChildrenCountWithoutImage) {
            val viewToRemove = contentArea.getChildAt(newChildrenCountWithoutImage)
            if (viewToRemove == imageContainer) break // Не удаляем контейнер картинки здесь
            contentArea.removeViewAt(newChildrenCountWithoutImage)
        }

        // Обработка изображения
        if (imageUrl != null) {
            ensureImageContainer()
            
            pulseAnimator?.cancel()
            pulseAnimator = null
            shimmerBg?.visibility = View.GONE
            imageViewResult?.visibility = View.VISIBLE

            if (imageUrl.startsWith("data:image")) {
                if (currentImageUrl != imageUrl) {
                    currentImageUrl = imageUrl
                    try {
                        val base64Data = imageUrl.substringAfter(",")
                        val imageBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        imageViewResult?.setImageBitmap(bitmap)
                    } catch (e: Exception) {
                        SafeLog.w("AssistantMessageWrapper", "Could not decode inline image", e)
                    }
                }
            } else if (isLocalImageUrl(imageUrl)) {
                if (currentImageUrl != imageUrl) {
                    currentImageUrl = imageUrl
                    runCatching {
                        imageViewResult?.setImageURI(Uri.parse(imageUrl))
                    }.onFailure {
                        imageViewResult?.load(Uri.parse(imageUrl)) { crossfade(true) }
                    }
                }
            } else {
                if (currentImageUrl != imageUrl) {
                    currentImageUrl = imageUrl
                    imageViewResult?.load(imageUrl) { crossfade(true) }
                }
            }
            
            // Перемещаем imageContainer в самый конец, если он еще не там
            val lastIdx = contentArea.childCount - 1
            if (contentArea.getChildAt(lastIdx) != imageContainer) {
                contentArea.removeView(imageContainer)
                contentArea.addView(imageContainer)
            }
        } else {
            // Если картинки больше нет в тексте, убираем контейнер
            imageContainer?.let {
                if (it.parent == contentArea) contentArea.removeView(it)
            }
            imageContainer = null
            currentImageUrl = null
        }

        // Источники
        if (isFinal) {
            renderSourcesButton(parsedReply.sources)
        } else {
            removeSourcesButton()
        }

        lastRenderedText = reply
        lastRenderedWasFinal = true
        streamingTextView = null
    }

    private fun TextView.configureLinkableTextView() {
        SelectableTextSupport.configure(
            textView = this,
            linkColor = LINK_COLOR,
            openLinksOnTap = true
        )
        setLinkTextColor(LINK_COLOR)
        setHorizontallyScrolling(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NORMAL
        }
    }

    private fun renderMarkdownWithAutoLinks(textView: TextView, markdown: String) {
        markwon.setMarkdown(textView, markdown)
        val spannable = SpannableStringBuilder(textView.text)
        LinkifyCompat.addLinks(spannable, Linkify.WEB_URLS)

        spannable.getSpans(0, spannable.length, URLSpan::class.java).forEach { span ->
            val start = spannable.getSpanStart(span)
            val end = spannable.getSpanEnd(span)
            val flags = spannable.getSpanFlags(span)
            val safeUrl = BrowserUrlSanitizer.normalize(span.url)
            spannable.removeSpan(span)
            if (start >= 0 && end > start && safeUrl != null) {
                spannable.setSpan(InternalBrowserUrlSpan(safeUrl, onOpenLink), start, end, flags)
            }
        }

        textView.text = spannable
    }

    private class InternalBrowserUrlSpan(
        private val url: String,
        private val opener: (String) -> Unit
    ) : URLSpan(url) {
        override fun onClick(widget: View) {
            opener(url)
        }

        override fun updateDrawState(ds: TextPaint) {
            ds.color = LINK_COLOR
            ds.isUnderlineText = true
        }
    }
}
