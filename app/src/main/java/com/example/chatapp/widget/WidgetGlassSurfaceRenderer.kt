package com.example.chatapp.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

internal object WidgetGlassSurfaceRenderer {
    private const val MAX_SURFACE_SIDE_PX = 520
    private const val LIQUID_BLUR = 0.76f
    private const val LIQUID_GLOW = 0.78f
    private const val LIQUID_DEPTH = 0.82f

    fun render(
        context: Context,
        state: FreeChatAttachmentWidgetStateStore.State,
        size: WidgetSize,
        layoutName: String
    ): Bitmap {
        val dimensions = surfaceDimensions(context, size, layoutName)
        val bitmap = Bitmap.createBitmap(
            dimensions.width,
            dimensions.height,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        val rect = RectF(0f, 0f, dimensions.width.toFloat(), dimensions.height.toFloat())
        val radius = (dp(context, state.cornerRadiusDp.toFloat()) * dimensions.scale)
            .coerceAtMost(min(rect.width(), rect.height()) / 2f)
        val clipPath = Path().apply {
            addRoundRect(rect, radius, radius, Path.Direction.CW)
        }
        val effectiveStyle = WidgetStyleResources.effectiveStyle(context, state)
        val palette = defaultPalette(context)

        canvas.save()
        canvas.clipPath(clipPath)
        when (effectiveStyle) {
            WidgetStyle.LiquidGlass -> drawLiquidGlass(canvas, rect, state, palette)
            WidgetStyle.Dark -> drawDark(canvas, rect)
            WidgetStyle.Adaptive -> drawAdaptive(canvas, rect, context)
        }
        canvas.restore()

        drawBorder(canvas, rect, radius, effectiveStyle)
        return bitmap
    }

    private fun surfaceDimensions(
        context: Context,
        size: WidgetSize,
        layoutName: String
    ): SurfaceDimensions {
        val density = context.resources.displayMetrics.density
        val fallbackWidthDp = if (layoutName.contains("Tiny")) 88 else 320
        val fallbackHeightDp = if (layoutName.contains("one", ignoreCase = true)) 76 else 150
        val widthDp = size.minWidth.takeIf { it > 0 } ?: fallbackWidthDp
        val heightDp = size.minHeight.takeIf { it > 0 } ?: fallbackHeightDp
        val rawWidth = (widthDp * density).roundToInt().coerceAtLeast(96)
        val rawHeight = (heightDp * density).roundToInt().coerceAtLeast(72)
        val scale = min(
            MAX_SURFACE_SIDE_PX.toFloat() / rawWidth,
            MAX_SURFACE_SIDE_PX.toFloat() / rawHeight
        ).coerceAtMost(1f)
        return SurfaceDimensions(
            width = (rawWidth * scale).roundToInt().coerceAtLeast(96),
            height = (rawHeight * scale).roundToInt().coerceAtLeast(72),
            scale = scale
        )
    }

    private fun drawLiquidGlass(
        canvas: Canvas,
        rect: RectF,
        state: FreeChatAttachmentWidgetStateStore.State,
        palette: WallpaperPalette
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            rect.height(),
            intArrayOf(
                Color.argb((28 + 22 * LIQUID_BLUR).roundToInt(), 255, 255, 255),
                Color.argb((14 + 12 * LIQUID_BLUR).roundToInt(), 255, 255, 255),
                Color.argb((8 + 12 * LIQUID_DEPTH).roundToInt(), 255, 255, 255)
            ),
            floatArrayOf(0f, 0.44f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
        paint.shader = null

        paint.shader = RadialGradient(
            rect.width() * 0.14f,
            rect.height() * 0.06f,
            max(rect.width(), rect.height()) * 0.76f,
            blendColors(
                withAlpha(palette.secondary, (26 + 34 * LIQUID_GLOW).roundToInt()),
                Color.WHITE,
                0.35f
            ),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
        paint.shader = null

        paint.shader = RadialGradient(
            rect.width() * 0.86f,
            rect.height() * 0.82f,
            max(rect.width(), rect.height()) * 0.72f,
            withAlpha(palette.primary, (14 + 22 * LIQUID_DEPTH).roundToInt()),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
        paint.shader = null

        paint.shader = LinearGradient(
            0f,
            0f,
            rect.width(),
            0f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb((42 + 58 * LIQUID_GLOW).roundToInt(), 255, 255, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, reflectionPhase(state), 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
        paint.shader = null

        drawLiquidReflection(canvas, rect, state)
        drawInnerCaustics(canvas, rect, palette)
        drawNoise(canvas, rect, state)
    }

    private fun drawLiquidReflection(
        canvas: Canvas,
        rect: RectF,
        state: FreeChatAttachmentWidgetStateStore.State
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                rect.width() * 0.24f,
                0f,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb(62, 255, 255, 255),
                    Color.argb(18, 255, 255, 255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.34f, 0.56f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        val stripWidth = rect.width() * 0.24f
        val startX = -stripWidth + reflectionPhase(state) * (rect.width() + stripWidth * 2f)
        canvas.save()
        canvas.rotate(-18f, rect.centerX(), rect.centerY())
        canvas.drawRoundRect(
            RectF(startX, -rect.height() * 0.35f, startX + stripWidth, rect.height() * 1.35f),
            stripWidth / 2f,
            stripWidth / 2f,
            paint
        )
        canvas.restore()
        paint.shader = null
    }

    private fun drawInnerCaustics(
        canvas: Canvas,
        rect: RectF,
        palette: WallpaperPalette
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1.4f, rect.height() * 0.012f)
            color = blendColors(
                withAlpha(palette.secondary, (52 + 56 * LIQUID_GLOW).roundToInt()),
                Color.WHITE,
                0.24f
            )
        }
        canvas.drawOval(
            RectF(
                rect.width() * 0.58f,
                rect.height() * -0.16f,
                rect.width() * 1.13f,
                rect.height() * 0.58f
            ),
            paint
        )
        paint.color = Color.argb((22 + 30 * LIQUID_GLOW).roundToInt(), 255, 255, 255)
        canvas.drawArc(
            RectF(
                rect.width() * -0.20f,
                rect.height() * 0.46f,
                rect.width() * 0.68f,
                rect.height() * 1.34f
            ),
            204f,
            78f,
            false,
            paint
        )
    }

    private fun drawNoise(
        canvas: Canvas,
        rect: RectF,
        state: FreeChatAttachmentWidgetStateStore.State
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val random = Random(state.appWidgetId * 1103515245 + rect.width().roundToInt())
        val count = (rect.width() * rect.height() / 880f).roundToInt().coerceIn(72, 440)
        repeat(count) {
            val alpha = random.nextInt(4, 13)
            paint.color = if (random.nextBoolean()) {
                Color.argb(alpha, 255, 255, 255)
            } else {
                Color.argb(alpha / 2, 0, 0, 0)
            }
            canvas.drawPoint(
                random.nextFloat() * rect.width(),
                random.nextFloat() * rect.height(),
                paint
            )
        }
    }

    private fun drawDark(canvas: Canvas, rect: RectF) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                0f,
                rect.height(),
                0xEE222226.toInt(),
                0xF0171719.toInt(),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(rect, paint)
        paint.shader = null
    }

    private fun drawBorder(
        canvas: Canvas,
        rect: RectF,
        radius: Float,
        style: WidgetStyle
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = max(1f, rect.height() * 0.012f)
        }
        val strokeInset = paint.strokeWidth / 2f
        val strokeRect = RectF(rect).apply { inset(strokeInset, strokeInset) }
        val alpha = when (style) {
            WidgetStyle.LiquidGlass -> (72 + LIQUID_GLOW * 115f).roundToInt().coerceAtMost(210)
            WidgetStyle.Dark -> 46
            WidgetStyle.Adaptive -> 80
        }
        paint.color = Color.argb(alpha, 255, 255, 255)
        canvas.drawRoundRect(strokeRect, radius - strokeInset, radius - strokeInset, paint)
        if (style == WidgetStyle.LiquidGlass) {
            paint.strokeWidth = 1f
            paint.color = Color.argb(82, 255, 255, 255)
            canvas.drawLine(radius * 0.62f, 1.5f, rect.width() - radius * 0.62f, 1.5f, paint)
        }
    }

    private fun defaultPalette(context: Context): WallpaperPalette {
        return if (context.isNightMode()) {
            WallpaperPalette(primary = 0xFF5E80FF.toInt(), secondary = 0xFF70D8FF.toInt())
        } else {
            WallpaperPalette(primary = 0xFF7BA8FF.toInt(), secondary = 0xFF9CE8FF.toInt())
        }
    }

    private fun reflectionPhase(state: FreeChatAttachmentWidgetStateStore.State): Float {
        val tick = (System.currentTimeMillis() / 2200L + state.appWidgetId) % 9L
        return 0.12f + tick / 8f * 0.76f
    }

    private fun Context.isNightMode(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun dp(context: Context, value: Float): Float {
        return value * context.resources.displayMetrics.density
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun blendColors(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t).roundToInt(),
            (Color.red(from) + (Color.red(to) - Color.red(from)) * t).roundToInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * t).roundToInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).roundToInt()
        )
    }

    private fun drawAdaptive(canvas: Canvas, rect: RectF, context: Context) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val isDark = context.isNightMode()
        val accentColor = WidgetStyleResources.getSystemAccentColor(context)
        val colors = WidgetStyleResources.resolveAdaptiveColors(accentColor, isDark)

        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(colors.panelBg, hsl)
        val h = hsl[0]
        val s = hsl[1]
        val l = hsl[2]

        // Vertical depth gradient (premium "аккуратная глубина")
        val topLightness = (l + 0.03f).coerceAtMost(1.0f)
        val bottomLightness = (l - 0.03f).coerceAtLeast(0.0f)

        val topColor = ColorUtils.HSLToColor(floatArrayOf(h, s, topLightness))
        val bottomColor = ColorUtils.HSLToColor(floatArrayOf(h, s, bottomLightness))

        val alpha = Color.alpha(colors.panelBg)
        val finalTopColor = (topColor and 0x00FFFFFF) or (alpha shl 24)
        val finalBottomColor = (bottomColor and 0x00FFFFFF) or (alpha shl 24)

        paint.shader = LinearGradient(
            0f, 0f, 0f, rect.height(),
            finalTopColor, finalBottomColor,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
        paint.shader = null
    }

    private data class SurfaceDimensions(
        val width: Int,
        val height: Int,
        val scale: Float
    )

    private data class WallpaperPalette(
        val primary: Int,
        val secondary: Int
    )
}
