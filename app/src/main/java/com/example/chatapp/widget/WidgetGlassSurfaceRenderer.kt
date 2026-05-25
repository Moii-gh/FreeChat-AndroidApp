package com.example.chatapp.widget

import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

internal object WidgetGlassSurfaceRenderer {
    private const val MAX_SURFACE_SIDE_PX = 520
    private const val MAX_BACKDROP_SOURCE_SIDE_PX = 900

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
        val radius = dp(context, state.cornerRadiusDp.toFloat())
            .coerceAtMost(min(rect.width(), rect.height()) / 2f)
        val clipPath = Path().apply {
            addRoundRect(rect, radius, radius, Path.Direction.CW)
        }
        val effectiveStyle = WidgetStyleResources.effectiveStyle(context, state)
        val palette = wallpaperPalette(context, state.matchWallpaperColors)

        canvas.save()
        canvas.clipPath(clipPath)
        drawBackdrop(context, canvas, rect, state, effectiveStyle, palette)
        when (effectiveStyle) {
            WidgetStyle.LiquidGlass -> drawLiquidGlass(canvas, rect, state, palette)
            WidgetStyle.DarkMatte -> drawDarkMatte(canvas, rect, state)
            WidgetStyle.Solid -> drawSolid(canvas, rect, context.isNightMode())
            WidgetStyle.AdaptiveSystem -> Unit
        }
        canvas.restore()

        drawBorder(canvas, rect, radius, state, effectiveStyle)
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
            height = (rawHeight * scale).roundToInt().coerceAtLeast(72)
        )
    }

    private fun drawBackdrop(
        context: Context,
        canvas: Canvas,
        rect: RectF,
        state: FreeChatAttachmentWidgetStateStore.State,
        style: WidgetStyle,
        palette: WallpaperPalette
    ) {
        val backdrop = loadBackdrop(
            context = context,
            uriString = state.backgroundImageUri,
            width = rect.width().roundToInt(),
            height = rect.height().roundToInt(),
            blurIntensity = state.blurIntensity
        )
        if (backdrop != null) {
            canvas.drawBitmap(backdrop, 0f, 0f, null)
            backdrop.recycle()
            return
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val topColor: Int
        val bottomColor: Int
        when (style) {
            WidgetStyle.LiquidGlass -> {
                topColor = blendColors(withAlpha(palette.secondary, 178), 0xFFDCEBFF.toInt(), 0.20f)
                bottomColor = blendColors(withAlpha(palette.primary, 118), 0xFF0B1020.toInt(), 0.58f)
            }
            WidgetStyle.DarkMatte -> {
                topColor = 0xFF292B31.toInt()
                bottomColor = 0xFF111217.toInt()
            }
            WidgetStyle.Solid -> {
                topColor = if (context.isNightMode()) 0xFFE7EBF3.toInt() else 0xFFF7F9FE.toInt()
                bottomColor = if (context.isNightMode()) 0xFFD8DDE8.toInt() else 0xFFECEFF7.toInt()
            }
            WidgetStyle.AdaptiveSystem -> {
                topColor = 0xFFF4F7FD.toInt()
                bottomColor = 0xFFE9EEF8.toInt()
            }
        }
        paint.shader = LinearGradient(
            0f,
            0f,
            rect.width(),
            rect.height(),
            topColor,
            bottomColor,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
        paint.shader = null
    }

    private fun drawLiquidGlass(
        canvas: Canvas,
        rect: RectF,
        state: FreeChatAttachmentWidgetStateStore.State,
        palette: WallpaperPalette
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val depth = state.glassDepth / 100f
        val glow = state.borderGlow / 100f
        val blur = state.blurIntensity / 100f

        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            rect.height(),
            intArrayOf(
                Color.argb((62 + 34 * blur).roundToInt(), 255, 255, 255),
                Color.argb((24 + 18 * blur).roundToInt(), 255, 255, 255),
                Color.argb((22 + 58 * depth).roundToInt(), 7, 12, 26)
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
            blendColors(withAlpha(palette.secondary, (86 + 90 * glow).roundToInt()), Color.WHITE, 0.35f),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
        paint.shader = null

        paint.shader = RadialGradient(
            rect.width() * 0.86f,
            rect.height() * 0.82f,
            max(rect.width(), rect.height()) * 0.72f,
            withAlpha(palette.primary, (42 + 50 * depth).roundToInt()),
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
                Color.argb((42 + 58 * glow).roundToInt(), 255, 255, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, reflectionPhase(state), 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
        paint.shader = null

        drawLiquidReflection(canvas, rect, state)
        drawInnerCaustics(canvas, rect, state, palette)
        if (state.noiseTexture) {
            drawNoise(canvas, rect, state)
        }
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
        val phase = reflectionPhase(state)
        val stripWidth = rect.width() * 0.24f
        val startX = -stripWidth + phase * (rect.width() + stripWidth * 2f)
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
        state: FreeChatAttachmentWidgetStateStore.State,
        palette: WallpaperPalette
    ) {
        val glow = state.borderGlow / 100f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = max(1.4f, rect.height() * 0.012f)
            color = blendColors(withAlpha(palette.secondary, (52 + 56 * glow).roundToInt()), Color.WHITE, 0.24f)
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
        paint.color = Color.argb((22 + 30 * glow).roundToInt(), 255, 255, 255)
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
        val count = (rect.width() * rect.height() / 760f).roundToInt().coerceIn(90, 520)
        repeat(count) {
            val alpha = random.nextInt(5, 17)
            val bright = random.nextBoolean()
            paint.color = if (bright) {
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

    private fun drawDarkMatte(
        canvas: Canvas,
        rect: RectF,
        state: FreeChatAttachmentWidgetStateStore.State
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            rect.height(),
            0xEE2A2B31.toInt(),
            0xF0141519.toInt(),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
        paint.shader = null
        paint.color = Color.argb((18 + state.glassDepth * 0.24f).roundToInt(), 255, 255, 255)
        canvas.drawRect(0f, 0f, rect.width(), rect.height() * 0.26f, paint)
    }

    private fun drawSolid(canvas: Canvas, rect: RectF, nightMode: Boolean) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            rect.height(),
            if (nightMode) 0xFFF6F8FD.toInt() else 0xFFFFFFFF.toInt(),
            if (nightMode) 0xFFE3E8F2.toInt() else 0xFFF1F4FA.toInt(),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
        paint.shader = null
    }

    private fun drawBorder(
        canvas: Canvas,
        rect: RectF,
        radius: Float,
        state: FreeChatAttachmentWidgetStateStore.State,
        style: WidgetStyle
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = max(1f, rect.height() * 0.012f)
        }
        val strokeInset = paint.strokeWidth / 2f
        val strokeRect = RectF(rect).apply { inset(strokeInset, strokeInset) }
        val alpha = when (style) {
            WidgetStyle.LiquidGlass -> (72 + state.borderGlow * 1.15f).roundToInt().coerceAtMost(210)
            WidgetStyle.DarkMatte -> 46
            WidgetStyle.Solid -> 118
            WidgetStyle.AdaptiveSystem -> 80
        }
        paint.color = if (style == WidgetStyle.Solid) {
            Color.argb(alpha, 188, 196, 210)
        } else {
            Color.argb(alpha, 255, 255, 255)
        }
        canvas.drawRoundRect(strokeRect, radius - strokeInset, radius - strokeInset, paint)
        if (style == WidgetStyle.LiquidGlass) {
            paint.strokeWidth = 1f
            paint.color = Color.argb(82, 255, 255, 255)
            canvas.drawLine(radius * 0.62f, 1.5f, rect.width() - radius * 0.62f, 1.5f, paint)
        }
    }

    private fun loadBackdrop(
        context: Context,
        uriString: String?,
        width: Int,
        height: Int,
        blurIntensity: Int
    ): Bitmap? {
        val uri = uriString?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val source = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            } ?: return@runCatching null
            val cropped = source.centerCrop(width, height)
            if (cropped != source) source.recycle()
            cropped.frostedResizeBlur(blurIntensity)
        }.getOrNull()
    }

    private fun Bitmap.centerCrop(targetWidth: Int, targetHeight: Int): Bitmap {
        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val scale = max(targetWidth / width.toFloat(), targetHeight / height.toFloat())
        val scaledWidth = width * scale
        val scaledHeight = height * scale
        val left = (targetWidth - scaledWidth) / 2f
        val top = (targetHeight - scaledHeight) / 2f
        val destination = RectF(left, top, left + scaledWidth, top + scaledHeight)
        Canvas(output).drawBitmap(this, null, destination, Paint(Paint.ANTI_ALIAS_FLAG))
        return output
    }

    private fun Bitmap.frostedResizeBlur(intensity: Int): Bitmap {
        if (intensity <= 0) return this
        val scale = (0.82f - intensity.coerceIn(0, 100) / 142f).coerceIn(0.16f, 0.82f)
        val smallWidth = (width * scale).roundToInt().coerceAtLeast(12)
        val smallHeight = (height * scale).roundToInt().coerceAtLeast(12)
        val small = Bitmap.createScaledBitmap(this, smallWidth, smallHeight, true)
        val blurred = Bitmap.createScaledBitmap(small, width, height, true)
        if (small != this) small.recycle()
        if (blurred != this) recycle()
        return blurred
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sampleSize = 1
        val largestSide = max(width, height)
        while (largestSide / sampleSize > MAX_BACKDROP_SOURCE_SIDE_PX) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun wallpaperPalette(context: Context, matchWallpaperColors: Boolean): WallpaperPalette {
        if (!matchWallpaperColors || Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            return defaultPalette(context)
        }
        return runCatching {
            val colors = WallpaperManager.getInstance(context)
                .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            val primary = colors?.primaryColor?.toArgb() ?: return@runCatching defaultPalette(context)
            val secondary = colors.secondaryColor?.toArgb() ?: lighten(primary, 0.22f)
            WallpaperPalette(
                primary = primary,
                secondary = secondary
            )
        }.getOrElse {
            defaultPalette(context)
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
        if (!state.dynamicReflections) return 0.34f
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

    private fun lighten(color: Int, amount: Float): Int {
        return blendColors(color, Color.WHITE, amount)
    }

    private data class SurfaceDimensions(
        val width: Int,
        val height: Int
    )

    private data class WallpaperPalette(
        val primary: Int,
        val secondary: Int
    )
}
