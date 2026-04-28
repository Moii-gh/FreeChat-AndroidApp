package com.example.chatapp.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.chatapp.LocaleHelper
import com.example.chatapp.util.dpToPx
import com.example.chatapp.util.startPulse
import com.example.chatapp.util.stopPulse
import java.util.*

/**
 * Менеджер голосового ввода.
 * Инкапсулирует работу с SpeechRecognizer, waveform-визуализацию
 * и обработку lifecycle (cleanup в destroy).
 */
class SpeechRecognizerManager(
    private val context: Context,
    private val etInput: EditText,
    private val waveformContainer: LinearLayout
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var speechRecognizerIntent: Intent? = null
    private var isSpeechAvailable = false

    /**
     * Инициализирует SpeechRecognizer и настраивает waveform бары.
     */
    fun setup() {
        setupWaveformBars()
        setupRecognizer()
    }

    /** Создаёт 30 полосок waveform-визуализации */
    private fun setupWaveformBars() {
        for (i in 0 until 30) {
            val bar = View(context).apply {
                val size = if (i % 2 == 0) 12 else 8
                layoutParams = LinearLayout.LayoutParams(4.dpToPx(), size.dpToPx()).apply {
                    setMargins(1.dpToPx(), 0, 1.dpToPx(), 0)
                }
                background = ContextCompat.getDrawable(context, com.example.chatapp.R.drawable.waveform_bar_bg)
            }
            waveformContainer.addView(bar)
        }
    }

    /** Настройка SpeechRecognizer с обработкой ошибок и результатов */
    private fun setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            isSpeechAvailable = false
            speechRecognizer = null
            speechRecognizerIntent = null
            return
        }

        isSpeechAvailable = true
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                etInput.hint = LocaleHelper.getString(context, "speech_speak_now")
                toggleWaveform(true)
            }

            override fun onBeginningOfSpeech() {
                etInput.hint = LocaleHelper.getString(context, "speech_listening")
            }

            override fun onRmsChanged(rmsdB: Float) {
                updateWaveform(rmsdB)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                etInput.hint = LocaleHelper.getString(context, "main_panel_input")
                toggleWaveform(false)
            }

            override fun onError(error: Int) {
                etInput.hint = LocaleHelper.getString(context, "main_panel_input")
                toggleWaveform(false)
                val msg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> LocaleHelper.getString(context, "speech_error_audio")
                    SpeechRecognizer.ERROR_CLIENT -> LocaleHelper.getString(context, "speech_error_client")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> LocaleHelper.getString(context, "speech_error_permission")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> LocaleHelper.getString(context, "speech_error_network")
                    SpeechRecognizer.ERROR_NO_MATCH -> LocaleHelper.getString(context, "speech_error_no_match")
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> LocaleHelper.getString(context, "speech_error_busy")
                    SpeechRecognizer.ERROR_SERVER -> LocaleHelper.getString(context, "speech_error_server")
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> LocaleHelper.getString(context, "speech_error_timeout")
                    else -> LocaleHelper.getString(context, "speech_error_generic")
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }

            override fun onResults(results: Bundle?) {
                toggleWaveform(false)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val currentText = etInput.text.toString()
                    val space = if (currentText.isNotEmpty() && !currentText.endsWith(" ")) " " else ""
                    val newText = currentText + space + matches[0]
                    etInput.setText(newText)
                    etInput.setSelection(etInput.text.length)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    /**
     * Настраивает кнопку микрофона: нажал-удерживай = запись,
     * отпустил = остановка.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun setupMicButton(btnMic: ImageButton, activity: androidx.appcompat.app.AppCompatActivity) {
        btnMic.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    btnMic.animate().cancel()
                    btnMic.animate()
                        .scaleX(0.92f)
                        .scaleY(0.92f)
                        .setDuration(80L)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()

                    if (!isSpeechAvailable || speechRecognizer == null || speechRecognizerIntent == null) {
                        Toast.makeText(
                            context,
                            LocaleHelper.getString(context, "speech_error_generic"),
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setOnTouchListener true
                    }

                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED
                    ) {
                        androidx.core.app.ActivityCompat.requestPermissions(
                            activity, arrayOf(Manifest.permission.RECORD_AUDIO), 1
                        )
                    } else {
                        btnMic.setColorFilter(Color.WHITE)
                        btnMic.startPulse()
                        try {
                            speechRecognizer?.startListening(speechRecognizerIntent)
                        } catch (_: Exception) {
                            btnMic.clearColorFilter()
                            btnMic.stopPulse()
                            toggleWaveform(false)
                            Toast.makeText(
                                context,
                                LocaleHelper.getString(context, "speech_error_generic"),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    btnMic.animate().cancel()
                    btnMic.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(160L)
                        .setInterpolator(OvershootInterpolator(2.2f))
                        .start()
                    btnMic.clearColorFilter()
                    btnMic.stopPulse()
                    try {
                        speechRecognizer?.stopListening()
                    } catch (_: Exception) {
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** Переключение видимости waveform / поля ввода */
    private fun toggleWaveform(show: Boolean) {
        if (show) {
            etInput.visibility = View.GONE
            waveformContainer.visibility = View.VISIBLE
            waveformContainer.alpha = 0f
            waveformContainer.animate().alpha(1f).setDuration(200).start()
        } else {
            waveformContainer.animate().alpha(0f).setDuration(200).withEndAction {
                waveformContainer.visibility = View.GONE
                etInput.visibility = View.VISIBLE
                etInput.alpha = 0f
                etInput.animate().alpha(1f).setDuration(200).start()
            }.start()
        }
    }

    /** Обновление высоты waveform-баров по громкости голоса */
    private fun updateWaveform(rmsdB: Float) {
        val volume = (rmsdB + 2).coerceAtLeast(0f) / 12f
        for (i in 0 until waveformContainer.childCount) {
            val bar = waveformContainer.getChildAt(i)
            val randomFactor = (0.5 + Math.random() * 0.5).toFloat()
            val baseHeight = if (i % 2 == 0) 16 else 10
            val newHeight = (baseHeight + (volume * 24 * randomFactor)).toInt().dpToPx()
            val params = bar.layoutParams
            params.height = newHeight
            bar.layoutParams = params
        }
    }

    /** Cleanup — вызывать в onDestroy Activity */
    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
