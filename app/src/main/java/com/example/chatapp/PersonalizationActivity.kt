package com.example.chatapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.data.AccountScopedSettings

class PersonalizationActivity : AppCompatActivity() {

    private lateinit var etInstructions: EditText
    private lateinit var accountSettings: AccountScopedSettings

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_personalization)

        window.statusBarColor = Color.TRANSPARENT

        etInstructions = findViewById(R.id.etInstructions)
        accountSettings = AccountScopedSettings(this)

        etInstructions.setText(accountSettings.getUserInstructions())
        animateInstructionsInputExpansion()
        
        // Обновляем тексты под текущую локаль.
        findViewById<android.widget.TextView>(R.id.tvToolbarTitle)?.text = LocaleHelper.getString(this, "label_personalization")
        findViewById<android.widget.TextView>(R.id.tvInstructionsLabel)?.text = LocaleHelper.getString(this, "label_personalization_instructions")

        // Кнопка назад.
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        // Кнопка сохранения.
        findViewById<View>(R.id.btnSave).setOnClickListener {
            val text = etInstructions.text.toString().trim()
            accountSettings.saveUserInstructions(text)
            Toast.makeText(this, LocaleHelper.getString(this, "toast_instructions_saved"), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun animateInstructionsInputExpansion() {
        etInstructions.post {
            val params = etInstructions.layoutParams as LinearLayout.LayoutParams
            val expandedHeight = etInstructions.height
            val collapsedHeight = 92.dp

            if (expandedHeight <= collapsedHeight) return@post

            params.weight = 0f
            params.height = collapsedHeight
            etInstructions.layoutParams = params

            ValueAnimator.ofInt(collapsedHeight, expandedHeight).apply {
                duration = 520L
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { animator ->
                    params.height = animator.animatedValue as Int
                    etInstructions.layoutParams = params
                }
                start()
            }
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
