package com.example.chatapp.ui

import android.animation.ValueAnimator
import android.graphics.*
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.core.content.ContextCompat
import coil.load
import com.example.chatapp.LocaleHelper
import com.example.chatapp.R
import com.example.chatapp.util.SyntaxHighlighter
import com.example.chatapp.util.dpToPx
import java.util.regex.Pattern

/**
 * Обёртка для блока ответа ассистента.
 * Управляет контентом (текст / код / изображение), shimmer-анимациями,
 * рендерингом Markdown через Markwon.
 *
 * Ранее был inner class в MainActivity, теперь — самостоятельный класс
 * с явной зависимостью от Context.
 */
class AssistantMessageWrapper(
    val container: LinearLayout,
    val contentArea: LinearLayout,
    val copyAction: (String) -> Unit,
    val shareAction: (String) -> Unit,
    private val context: android.content.Context,
    private val scrollView: ScrollView
) {
    companion object {
        private val IMAGE_MARKDOWN_PATTERN = Pattern.compile("!\\[.*?\\]\\((.*?)\\)")

        fun extractImageUrl(text: String): String? {
            val matcher = IMAGE_MARKDOWN_PATTERN.matcher(text)
            return if (matcher.find()) matcher.group(1)?.trim() else null
        }

        fun containsImageReply(text: String): Boolean {
            val imageUrl = extractImageUrl(text) ?: return false
            return imageUrl.startsWith("http") || imageUrl.startsWith("data:image")
        }
    }

    var rawText: String = ""
    var btnRow: View? = null

    var isImageMode = false
    var imageContainer: FrameLayout? = null
    var shimmerBg: View? = null
    var imageStatusText: TextView? = null
    var imageIcon: ImageView? = null
    var imageViewResult: ImageView? = null
    var currentImageUrl: String? = null

    private var pulseAnimator: ValueAnimator? = null
    private var textShimmerAnimator: ValueAnimator? = null

    /** Показывает шиммер-заглушку при генерации изображения */
    fun showImageLoadingState() {
        contentArea.removeAllViews()
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
                matrix.setTranslate(anim.animatedValue as Float, 0f)
                shader.setLocalMatrix(matrix)
                textView.invalidate()
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

    /**
     * Главный метод обновления контента.
     * Парсит текст на чанки: обычный текст (Markwon) и блоки кода (SyntaxHighlighter).
     */
    fun updateContent(reply: String, animate: Boolean = true) {
        rawText = reply
        val thinkingText = LocaleHelper.getString(context, "ai_thinking")
        val isStatusMessage = reply == thinkingText || 
                reply == "Поиск в сети..." || 
                reply == "Генерация изображения..." || 
                reply.startsWith("Использование инструмента")

        // Обработка статусов
        if (isStatusMessage) {
            btnRow?.visibility = View.GONE
            if (contentArea.childCount == 0) {
                val textView = TextView(context).apply {
                    setTextColor(Color.parseColor("#8E8E93"))
                    textSize = 16f
                    setLineSpacing(0f, 1.2f)
                    setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
                }
                contentArea.addView(textView)
            }
            val tv = contentArea.getChildAt(0) as? TextView
            if (tv != null && tv.text != reply) {
                tv.text = reply
                applyTextShimmer(tv)
            }
            return
        }

        btnRow?.visibility = View.VISIBLE
        textShimmerAnimator?.cancel()
        textShimmerAnimator = null

        // Удаляем статусную строку, если она была первой
        if (contentArea.childCount > 0) {
            val tv = contentArea.getChildAt(0) as? TextView
            if (tv?.paint?.shader != null || tv?.currentTextColor != ContextCompat.getColor(context, android.R.color.white)) {
                tv?.paint?.shader = null
                tv?.setTextColor(ContextCompat.getColor(context, android.R.color.white))
                tv?.invalidate()
            }
        }

        // Если есть картинка
        val imageUrl = extractImageUrl(reply)
        val hasImage = imageUrl?.startsWith("http") == true || imageUrl?.startsWith("data:image") == true
        val cleanReply = if (hasImage) {
            // Убираем маркдаун картинки из текста, чтобы не дублировать
            reply.replace(Regex("!\\[.*?\\]\\(.*?\\)"), "").trim()
        } else {
            reply
        }

        // Парсинг текста + кода: разбиваем по ``` разделителю
        val parts = cleanReply.split("```")
        val markwon = io.noties.markwon.Markwon.create(context)

        // Сколько блоков текста и кода мы собираемся показать
        val textPartsCount = if (cleanReply.isEmpty()) 0 else parts.size

        for (i in parts.indices) {
            val part = parts[i]
            val isCode = (i % 2 != 0)

            // Создаём view если не хватает
            if (i >= contentArea.childCount) {
                if (isCode) {
                    val (codeContainer, codeTv) = ChatMessageRenderer.createCodeBlockView(context, "", "CODE")
                    codeContainer.tag = codeTv
                    contentArea.addView(codeContainer)
                } else {
                    val tv = TextView(context).apply {
                        setTextColor(ContextCompat.getColor(context, android.R.color.white))
                        textSize = 16f
                        setLineSpacing(0f, 1.2f)
                        setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
                        setTextIsSelectable(true)
                        movementMethod = android.text.method.LinkMovementMethod.getInstance()
                    }
                    contentArea.addView(tv)
                }
            }

            // Обновляем view
            val view = contentArea.getChildAt(i)
            if (isCode) {
                val codeTv = view.tag as? TextView
                if (codeTv != null) {
                    val newlineIdx = part.indexOf('\n')
                    var lang = "CODE"
                    var fullCode = part
                    if (newlineIdx != -1) {
                        lang = part.substring(0, newlineIdx).trim()
                        fullCode = part.substring(newlineIdx + 1)
                    }
                    if (lang.isEmpty()) lang = "CODE"
                    codeTv.text = SyntaxHighlighter.highlight(fullCode)
                }
            } else {
                val tv = view as? TextView
                if (tv != null) {
                    if (part.trim().isNotEmpty()) {
                        markwon.setMarkdown(tv, part.trim())
                    } else {
                        tv.text = ""
                    }
                }
            }
        }

        // Удаляем лишние view, учитывая imageContainer если он добавлен в конец
        val expectedViews = textPartsCount + if (hasImage) 1 else 0
        while (contentArea.childCount > expectedViews) {
            val view = contentArea.getChildAt(contentArea.childCount - 1)
            if (view != imageContainer) {
                contentArea.removeView(view)
            } else if (!hasImage) {
                contentArea.removeView(view)
                imageContainer = null
            } else {
                break
            }
        }

        if (hasImage && imageUrl != null) {
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
                    } catch (e: Exception) { e.printStackTrace() }
                }
            } else {
                if (currentImageUrl != imageUrl) {
                    currentImageUrl = imageUrl
                    imageViewResult?.load(imageUrl) { crossfade(true) }
                }
            }
            
            // Перемещаем imageContainer в конец
            contentArea.removeView(imageContainer)
            contentArea.addView(imageContainer)
        }

        // Умный автоскролл: не дёргаем, если пользователь листает вверх
        val tolerance = 400
        val currentScrollY = scrollView.scrollY
        val scrollChild = scrollView.getChildAt(0) ?: return
        val maxScrollY = scrollChild.measuredHeight - scrollView.measuredHeight
        if (maxScrollY - currentScrollY <= tolerance) {
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
