package com.example.chatapp.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.view.isGone
import androidx.core.view.isVisible

object ChatUiAnimationHelper {
    fun installPressAnimation(
        touchSource: View,
        target: View = touchSource,
        pressedScale: Float = 0.92f,
        pressedOffset: Float = 0f,
        pressedTranslationZ: Float = 0f,
        onPressStart: () -> Unit = {},
        onPressEnd: () -> Unit = {}
    ) {
        touchSource.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onPressStart()
                    target.animate().cancel()
                    target.animate()
                        .scaleX(pressedScale)
                        .scaleY(pressedScale)
                        .translationY(pressedOffset)
                        .translationZ(pressedTranslationZ)
                        .setDuration(80L)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    onPressEnd()
                    target.animate().cancel()
                    target.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationY(0f)
                        .translationZ(0f)
                        .setDuration(160L)
                        .setInterpolator(OvershootInterpolator(2.2f))
                        .start()
                }
            }
            false
        }
    }

    fun resetTopActionTransforms(vararg views: View) {
        views.forEach { view ->
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            view.translationX = 0f
        }
    }

    fun animateTopActionsSplit(
        topRightMain: View,
        topRightChat: View,
        btnNewChat: View,
        btnMore: View,
        durationMs: Long,
        density: Float,
        onAnimatorReady: (AnimatorSet) -> Unit,
        onFinished: () -> Unit
    ) {
        topRightMain.isVisible = true
        topRightChat.isVisible = true

        topRightChat.alpha = 0f
        topRightChat.scaleX = 0.48f
        topRightChat.scaleY = 1f
        btnNewChat.alpha = 0f
        btnNewChat.translationX = 18f * density
        btnNewChat.scaleX = 0.9f
        btnNewChat.scaleY = 0.9f
        btnMore.alpha = 0f
        btnMore.translationX = -8f * density
        btnMore.scaleX = 0.9f
        btnMore.scaleY = 0.9f

        topRightChat.post {
            topRightMain.pivotX = topRightMain.width.toFloat()
            topRightMain.pivotY = topRightMain.height / 2f
            topRightChat.pivotX = topRightChat.width.toFloat()
            topRightChat.pivotY = topRightChat.height / 2f

            val expandedScale = if (topRightMain.width > 0) {
                topRightChat.width.toFloat() / topRightMain.width.toFloat()
            } else {
                2.1f
            }

            val stretch = ObjectAnimator.ofFloat(topRightMain, View.SCALE_X, 1f, expandedScale).apply {
                duration = 105L
                interpolator = AccelerateDecelerateInterpolator()
            }

            val reveal = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(topRightMain, View.ALPHA, 1f, 0f),
                    ObjectAnimator.ofFloat(topRightChat, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(topRightChat, View.SCALE_X, 0.48f, 1f),
                    ObjectAnimator.ofFloat(btnNewChat, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(btnNewChat, View.TRANSLATION_X, 18f * density, 0f),
                    ObjectAnimator.ofFloat(btnNewChat, View.SCALE_X, 0.9f, 1f),
                    ObjectAnimator.ofFloat(btnNewChat, View.SCALE_Y, 0.9f, 1f),
                    ObjectAnimator.ofFloat(btnMore, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(btnMore, View.TRANSLATION_X, -8f * density, 0f),
                    ObjectAnimator.ofFloat(btnMore, View.SCALE_X, 0.9f, 1f),
                    ObjectAnimator.ofFloat(btnMore, View.SCALE_Y, 0.9f, 1f)
                )
                duration = durationMs - stretch.duration
                interpolator = OvershootInterpolator(1.15f)
            }

            val animator = AnimatorSet().apply {
                playSequentially(stretch, reveal)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        topRightMain.isGone = true
                        resetTopActionTransforms(topRightMain, topRightChat, btnNewChat, btnMore)
                        topRightChat.isVisible = true
                        onFinished()
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        topRightMain.isGone = true
                        resetTopActionTransforms(topRightMain, topRightChat, btnNewChat, btnMore)
                        topRightChat.isVisible = true
                        onFinished()
                    }
                })
            }
            onAnimatorReady(animator)
            animator.start()
        }
    }
}
