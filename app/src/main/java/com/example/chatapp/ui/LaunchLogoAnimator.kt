package com.example.chatapp.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.os.Bundle
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

    private var hasPlayedForCurrentActivitySession = false
    private var liveActivityCount = 0
    private var lifecycleCallbacksRegistered = false

    fun registerLifecycleCallbacks(application: Application) {
        if (lifecycleCallbacksRegistered) return
        lifecycleCallbacksRegistered = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                liveActivityCount += 1
            }

            override fun onActivityDestroyed(activity: Activity) {
                liveActivityCount = (liveActivityCount - 1).coerceAtLeast(0)
                if (liveActivityCount == 0 && !activity.isChangingConfigurations) {
                    hasPlayedForCurrentActivitySession = false
                }
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }

    fun shouldPlayOnActivityCreate(savedInstanceState: Bundle?): Boolean {
        if (hasPlayedForCurrentActivitySession) {
            return false
        }
        hasPlayedForCurrentActivitySession = true
        return savedInstanceState == null
    }

    fun show(activity: Activity, onFinished: (() -> Unit)? = null) {
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
                    onFinished?.invoke()
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
