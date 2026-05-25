package com.example.chatapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.data.AccountScopedSettings

class PersonalizationActivity : AppCompatActivity() {

    private lateinit var etInstructions: EditText
    private lateinit var launcherIconGroup: RadioGroup
    private lateinit var accountSettings: AccountScopedSettings
    private var isUpdatingLauncherIconSelection = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_personalization)

        window.statusBarColor = Color.TRANSPARENT

        etInstructions = findViewById(R.id.etInstructions)
        launcherIconGroup = findViewById(R.id.rgLauncherIcon)
        accountSettings = AccountScopedSettings(this)

        etInstructions.setText(accountSettings.getUserInstructions())
        animateInstructionsInputExpansion()
        
        // Обновляем тексты под текущую локаль.
        findViewById<TextView>(R.id.tvToolbarTitle)?.text = LocaleHelper.getString(this, "label_personalization")
        findViewById<TextView>(R.id.tvInstructionsLabel)?.text = LocaleHelper.getString(this, "label_personalization_instructions")
        setupLauncherIconSelector()

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

    private fun setupLauncherIconSelector() {
        findViewById<TextView>(R.id.tvLauncherIconLabel)?.text =
            LocaleHelper.getString(this, "label_launcher_icon")
        findViewById<RadioButton>(R.id.rbLauncherIconDefault)?.text =
            LocaleHelper.getString(this, "label_launcher_icon_default")
        findViewById<RadioButton>(R.id.rbLauncherIconTransparent)?.text =
            LocaleHelper.getString(this, "label_launcher_icon_transparent")

        setLauncherIconChecked(LauncherIconManager.getSelectedIcon(this))

        launcherIconGroup.setOnCheckedChangeListener { _, checkedId ->
            if (isUpdatingLauncherIconSelection) return@setOnCheckedChangeListener

            val icon = when (checkedId) {
                R.id.rbLauncherIconTransparent -> LauncherIconManager.LauncherIcon.TRANSPARENT
                else -> LauncherIconManager.LauncherIcon.DEFAULT
            }
            applyLauncherIcon(icon)
        }
    }

    private fun applyLauncherIcon(icon: LauncherIconManager.LauncherIcon) {
        val previousIcon = LauncherIconManager.getSelectedIcon(this)
        runCatching { LauncherIconManager.setSelectedIcon(this, icon) }
            .onSuccess { changed ->
                if (changed) {
                    Toast.makeText(
                        this,
                        LocaleHelper.getString(this, "toast_launcher_icon_updated"),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .onFailure {
                setLauncherIconChecked(previousIcon)
                Toast.makeText(
                    this,
                    LocaleHelper.getString(this, "toast_launcher_icon_update_failed"),
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    private fun setLauncherIconChecked(icon: LauncherIconManager.LauncherIcon) {
        val checkedId = when (icon) {
            LauncherIconManager.LauncherIcon.DEFAULT -> R.id.rbLauncherIconDefault
            LauncherIconManager.LauncherIcon.TRANSPARENT -> R.id.rbLauncherIconTransparent
        }

        isUpdatingLauncherIconSelection = true
        launcherIconGroup.check(checkedId)
        isUpdatingLauncherIconSelection = false
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
