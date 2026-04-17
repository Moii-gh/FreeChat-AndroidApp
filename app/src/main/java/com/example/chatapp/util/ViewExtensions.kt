package com.example.chatapp.util

import android.content.res.Resources
import android.view.View
import android.view.animation.Animation
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
