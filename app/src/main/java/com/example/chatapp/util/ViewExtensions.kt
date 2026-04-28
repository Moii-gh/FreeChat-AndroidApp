package com.example.chatapp.util

import android.content.res.Resources
import android.view.MotionEvent
import android.view.View
import android.view.animation.Animation
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.ScaleAnimation

/**
 * Extension-функции для View, используемые по всему приложению.
 * Вынесены из MainActivity для переиспользования и чистоты кода.
 */

/** Конвертация dp в px на основе плотности экрана */
fun Int.dpToPx(): Int = (this * Resources.getSystem().displayMetrics.density).toInt()

/** Анимация «пружинистого» нажатия кнопки (масштаб 0.8 → 1.0 с overshoot) */
fun View.bounce() {
    this.animate().scaleX(0.8f).scaleY(0.8f).setDuration(100).withEndAction {
        this.animate().scaleX(1f).scaleY(1f).setDuration(100)
            .setInterpolator(OvershootInterpolator()).start()
    }.start()
}

/** Анимация появления элемента: плавный сдвиг снизу + fade in */
fun View.slideAndFadeIn() {
    this.alpha = 0f
    this.translationY = 50f
    this.animate()
        .alpha(1f)
        .translationY(0f)
        .setDuration(400)
        .setInterpolator(DecelerateInterpolator(2f))
        .start()
}

/** Запуск бесконечной пульсирующей анимации масштаба (для кнопки микрофона) */
fun View.startPulse() {
    val pulse = ScaleAnimation(
        1f, 1.2f, 1f, 1.2f,
        Animation.RELATIVE_TO_SELF, 0.5f,
        Animation.RELATIVE_TO_SELF, 0.5f
    ).apply {
        duration = 600
        repeatMode = Animation.REVERSE
        repeatCount = Animation.INFINITE
    }
    this.startAnimation(pulse)
}

/** Остановка пульсирующей анимации */
fun View.stopPulse() {
    this.clearAnimation()
}

fun View.setPressAnimation(
    pressedScale: Float = 0.94f,
    pressedTranslationDp: Float = 1.5f
) {
    val pressedOffset = pressedTranslationDp * resources.displayMetrics.density

    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.animate().cancel()
                view.animate()
                    .scaleX(pressedScale)
                    .scaleY(pressedScale)
                    .translationY(pressedOffset)
                    .setDuration(70L)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                view.animate().cancel()
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(150L)
                    .setInterpolator(OvershootInterpolator(2f))
                    .start()
            }
        }
        false
    }
}

fun View.setHapticClickListener(action: (View) -> Unit) {
    setPressAnimation()
    this.setOnClickListener {
        it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
        action(it)
    }
}

open class OnSwipeTouchListener(context: android.content.Context) : View.OnTouchListener {
    private val gestureDetector = android.view.GestureDetector(context, GestureListener())

    override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event)
    }

    private inner class GestureListener : android.view.GestureDetector.SimpleOnGestureListener() {
        private val swipeThreshold = 100
        private val swipeVelocityThreshold = 100

        override fun onDown(e: android.view.MotionEvent): Boolean {
            return false
        }

        override fun onFling(
            e1: android.view.MotionEvent?,
            e2: android.view.MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (e1 == null) return false
            try {
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x
                if (kotlin.math.abs(diffX) > kotlin.math.abs(diffY)) {
                    if (kotlin.math.abs(diffX) > swipeThreshold && kotlin.math.abs(velocityX) > swipeVelocityThreshold) {
                        if (diffX > 0) {
                            onSwipeRight()
                        } else {
                            onSwipeLeft()
                        }
                        return true
                    }
                }
            } catch (exception: Exception) {
                exception.printStackTrace()
            }
            return false
        }
    }

    open fun onSwipeRight() {}
    open fun onSwipeLeft() {}
}
