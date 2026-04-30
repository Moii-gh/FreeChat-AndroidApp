package com.example.chatapp.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.example.chatapp.R

object LaunchLogoAnimator {

    private const val LOGO_SIZE_DP = 132
    private const val ENTER_DURATION_MS = 900L
    private const val EXIT_DURATION_MS = 260L
    private const val EXIT_DELAY_MS = 120L

    fun show(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        val overlay = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 1f
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val logo = ImageView(activity).apply {
            setImageResource(R.drawable.logo)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0f
            scaleX = 0.42f
            scaleY = 0.42f
            rotation = 0f
        }

        val logoSize = activity.dpToPx(LOGO_SIZE_DP)
        overlay.addView(
            logo,
            FrameLayout.LayoutParams(logoSize, logoSize, Gravity.CENTER)
        )

        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val enter = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(logo, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(logo, View.SCALE_X, 0.42f, 1.18f),
                ObjectAnimator.ofFloat(logo, View.SCALE_Y, 0.42f, 1.18f),
                ObjectAnimator.ofFloat(logo, View.ROTATION, 0f, 360f)
            )
            duration = ENTER_DURATION_MS
            interpolator = OvershootInterpolator(0.9f)
        }

        val exit = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(overlay, View.ALPHA, 1f, 0f),
                ObjectAnimator.ofFloat(logo, View.SCALE_X, 1.18f, 1.28f),
                ObjectAnimator.ofFloat(logo, View.SCALE_Y, 1.18f, 1.28f),
                ObjectAnimator.ofFloat(logo, View.ROTATION, 360f, 420f)
            )
            startDelay = EXIT_DELAY_MS
            duration = EXIT_DURATION_MS
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    root.removeView(overlay)
                }
            })
        }

        AnimatorSet().apply {
            playSequentially(enter, exit)
            start()
        }
    }

    private fun Activity.dpToPx(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
