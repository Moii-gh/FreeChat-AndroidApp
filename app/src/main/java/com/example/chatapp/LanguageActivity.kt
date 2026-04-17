package com.example.chatapp

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LanguageActivity : AppCompatActivity() {

    private lateinit var languageContainer: LinearLayout
    private lateinit var tvTitle: TextView
    private lateinit var tvSelectLabel: TextView
    private var selectedLanguageCode: String = "ru"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_language)

        window.statusBarColor = Color.TRANSPARENT

        languageContainer = findViewById(R.id.languageContainer)
        tvTitle = findViewById(R.id.tvTitle)
        tvSelectLabel = findViewById(R.id.tvSelectLabel)

        // Load current language
        selectedLanguageCode = LocaleHelper.getSelectedLanguage(this)

        updateUiText()
        populateLanguages()

        // Back button
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        // Save button (checkmark)
        findViewById<View>(R.id.btnSave).setOnClickListener {
            LocaleHelper.setSelectedLanguage(this, selectedLanguageCode)
            updateUiText() // Update the title instantly
            
            // Return to settings and tell it to recreate/refresh
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun updateUiText() {
        tvTitle.text = LocaleHelper.getString(this, "button_language")
        tvSelectLabel.text = LocaleHelper.getString(this, "lable_select_language")
    }

    private fun populateLanguages() {
        languageContainer.removeAllViews()

        for ((code, name) in LocaleHelper.LANGUAGES) {
            val isSelected = code == selectedLanguageCode

            val button = TextView(this).apply {
                text = name
                textSize = 15f
                gravity = Gravity.CENTER
                
                // Colors based on selection state
                if (isSelected) {
                    setTextColor(Color.parseColor("#1C1C1E"))
                    // Light beige/pinkish background
                    val bg = GradientDrawable().apply {
                        cornerRadius = 28f.dpToPx()
                        setColor(Color.parseColor("#E6D7D7"))
                    }
                    background = bg
                } else {
                    setTextColor(Color.parseColor("#8E8E93"))
                    // Dark background with border
                    val bg = GradientDrawable().apply {
                        cornerRadius = 28f.dpToPx()
                        setColor(Color.parseColor("#252525"))
                        setStroke(1.dpToPxInt(), Color.parseColor("#333333"))
                    }
                    background = bg
                }

                // Layout params
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    56.dpToPxInt()
                )
                params.bottomMargin = 8.dpToPxInt()
                layoutParams = params

                isClickable = true
                isFocusable = true
                
                setOnClickListener {
                    selectedLanguageCode = code
                    populateLanguages() // Re-render buttons
                }
            }

            languageContainer.addView(button)
        }
    }

    // Extensions for DP to PX conversion
    private fun Float.dpToPx(): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            this,
            resources.displayMetrics
        )
    }

    private fun Int.dpToPxInt(): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            this.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
