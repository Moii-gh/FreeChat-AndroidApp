package com.example.chatapp.assistant

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import com.example.chatapp.LocaleHelper
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureManager(private val context: Context) {
    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var handlerThread: HandlerThread? = null

    fun captureOneShot(
        resultCode: Int,
        data: Intent,
        onResult: (Result<AssistantAttachment>) -> Unit
    ) {
        val delivered = AtomicBoolean(false)
        fun deliver(result: Result<AssistantAttachment>, stopProjection: Boolean = true) {
            if (!delivered.compareAndSet(false, true)) return
            cleanup(stopProjection = stopProjection)
            onResult(result)
        }

        val metrics = displayMetrics()
        val captureThread = HandlerThread("FreeChatScreenCapture").also { it.start() }
        handlerThread = captureThread
        val handler = Handler(captureThread.looper)
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = projectionManager.getMediaProjection(resultCode, data)
        val reader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            2
        )
        imageReader = reader
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                deliver(
                    Result.failure(IllegalStateException(LocaleHelper.getString(context, "digital_assistant_capture_failed"))),
                    stopProjection = false
                )
            }
        }, handler)

        reader.setOnImageAvailableListener({
            val result = runCatching {
                reader.acquireLatestImage().use { image ->
                    if (image == null) {
                        error(LocaleHelper.getString(context, "digital_assistant_capture_failed"))
                    }
                    val plane = image.planes.first()
                    val width = image.width
                    val height = image.height
                    val rowPadding = plane.rowStride - plane.pixelStride * width
                    val paddedWidth = width + rowPadding / plane.pixelStride
                    val bitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(plane.buffer)
                    val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    bitmap.recycle()
                    buildAttachment(cropped)
                }
            }
            deliver(result)
        }, handler)

        virtualDisplay = projection?.createVirtualDisplay(
            "FreeChatScreenCapture",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
        handler.postDelayed({
            deliver(
                Result.failure(IllegalStateException(LocaleHelper.getString(context, "digital_assistant_capture_failed")))
            )
        }, CAPTURE_TIMEOUT_MS)
    }

    private fun buildAttachment(bitmap: Bitmap): AssistantAttachment {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 86, output)
        bitmap.recycle()
        val bytes = output.toByteArray()
        val fileName = "screen_${System.currentTimeMillis()}.jpg"
        val tempDir = File(context.cacheDir, TEMP_DIR_NAME).apply { mkdirs() }
        val file = File(tempDir, fileName)
        file.writeBytes(bytes)
        return AssistantAttachment(
            mimeType = "image/jpeg",
            fileName = fileName,
            base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP),
            cacheFilePath = file.absolutePath
        )
    }

    private fun displayMetrics(): DisplayMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return DisplayMetrics().also { metrics ->
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                metrics.widthPixels = bounds.width()
                metrics.heightPixels = bounds.height()
                metrics.densityDpi = context.resources.displayMetrics.densityDpi
            } else {
                wm.defaultDisplay.getRealMetrics(metrics)
            }
        }
    }

    private fun cleanup(stopProjection: Boolean = true) {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        val activeProjection = projection
        projection = null
        if (stopProjection) {
            activeProjection?.stop()
        }
        handlerThread?.quitSafely()
        handlerThread = null
    }

    companion object {
        const val TEMP_DIR_NAME = "digital_assistant_temp"
        private const val CAPTURE_TIMEOUT_MS = 3_500L
    }
}
