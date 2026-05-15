package com.example.chatapp.util

import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.ImageView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil.load
import com.example.chatapp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

object SafeImageLoader {
    fun loadUri(
        imageView: ImageView,
        uri: Uri,
        widthPx: Int,
        heightPx: Int,
        centerCrop: Boolean = true
    ) {
        imageView.clearColorFilter()
        imageView.load(uri) {
            crossfade(false)
            if (widthPx > 0 && heightPx > 0) size(widthPx, heightPx)
            if (centerCrop) {
                scale(coil.size.Scale.FILL)
            } else {
                scale(coil.size.Scale.FIT)
            }
            placeholder(R.drawable.bg_shimmer_image)
            error(R.drawable.ic_image)
        }
    }

    fun loadModel(
        imageView: ImageView,
        model: Any,
        widthPx: Int,
        heightPx: Int,
        centerCrop: Boolean = true
    ) {
        imageView.clearColorFilter()
        imageView.load(model) {
            crossfade(true)
            if (widthPx > 0 && heightPx > 0) size(widthPx, heightPx)
            if (centerCrop) {
                scale(coil.size.Scale.FILL)
            } else {
                scale(coil.size.Scale.FIT)
            }
            placeholder(R.drawable.bg_shimmer_image)
            error(R.drawable.ic_image)
        }
    }

    fun loadBase64Image(
        imageView: ImageView,
        base64Data: String,
        fileName: String?,
        widthPx: Int,
        heightPx: Int,
        centerCrop: Boolean = true
    ) {
        val token = UUID.randomUUID().toString()
        imageView.setTag(R.id.safe_image_load_token, token)
        imageView.setImageResource(R.drawable.ic_image)
        imageView.setColorFilter(Color.WHITE)

        val context = imageView.context.applicationContext
        val lifecycleScope = imageView.findViewTreeLifecycleOwner()?.lifecycleScope
        val fallbackJob = if (lifecycleScope == null) SupervisorJob() else null
        val scope = lifecycleScope ?: CoroutineScope(requireNotNull(fallbackJob) + Dispatchers.Main.immediate)
        val detachListener = fallbackJob?.let { job ->
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit

                override fun onViewDetachedFromWindow(view: View) {
                    if (imageView.getTag(R.id.safe_image_load_token) == token) {
                        job.cancel()
                    }
                    view.removeOnAttachStateChangeListener(this)
                }
            }
        }
        detachListener?.let(imageView::addOnAttachStateChangeListener)
        scope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    FileUtils.saveBase64FileToCache(context, base64Data, fileName)
                }
                if (imageView.getTag(R.id.safe_image_load_token) != token || !imageView.isAttachedToWindow) {
                    return@launch
                }
                if (uri != null) {
                    loadUri(imageView, uri, widthPx, heightPx, centerCrop)
                } else {
                    imageView.setImageResource(R.drawable.ic_image)
                    imageView.setColorFilter(Color.WHITE)
                }
            } finally {
                detachListener?.let(imageView::removeOnAttachStateChangeListener)
            }
        }
    }
}
