package com.example.chatapp

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.data.AccountScopedSettings

class AiModeActivity : AppCompatActivity() {

    private lateinit var modesContainer: LinearLayout
    private var selectedMode: String = "auto"
    private lateinit var accountSettings: AccountScopedSettings

    private var modes = listOf<ModeItem>()

    data class ModeItem(val id: String, val title: String, val desc: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_mode)

        window.statusBarColor = Color.TRANSPARENT

        modesContainer = findViewById(R.id.modesContainer)
        accountSettings = AccountScopedSettings(this)

        selectedMode = accountSettings.getAiMode()

        modes = listOf(
            ModeItem(
                id = "plus",
                title = LocaleHelper.getString(this, "ai_mode_plus_title"),
                desc = LocaleHelper.getString(this, "ai_mode_plus_desc")
            ),
            ModeItem(
                id = "free",
                title = LocaleHelper.getString(this, "ai_mode_free_title"),
                desc = LocaleHelper.getString(this, "ai_mode_free_desc")
            ),
            ModeItem(
                id = "auto",
                title = LocaleHelper.getString(this, "ai_mode_auto_title"),
                desc = LocaleHelper.getString(this, "ai_mode_auto_desc")
            )
        )

        populateModes()

        // Translate Title
        findViewById<TextView>(R.id.tvToolbarTitle)?.text = LocaleHelper.getString(this, "lable_apps")

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
    }

    private fun populateModes() {
        modesContainer.removeAllViews()

        for (mode in modes) {
            val isSelected = mode.id == selectedMode

            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(20.dpToPxInt(), 16.dpToPxInt(), 20.dpToPxInt(), 16.dpToPxInt())

                val layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                layoutParams.bottomMargin = 12.dpToPxInt()
                this.layoutParams = layoutParams

                if (isSelected) {
                    val bg = GradientDrawable().apply {
                        cornerRadius = 24f.dpToPx()
                        setColor(Color.parseColor("#E6D7D7"))
                    }
                    background = bg
                } else {
                    val bg = GradientDrawable().apply {
                        cornerRadius = 24f.dpToPx()
                        setColor(Color.parseColor("#1C1C1E"))
                    }
                    background = bg
                }

                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedMode = mode.id
                    accountSettings.saveAiMode(mode.id)
                    populateModes() // Re-render visually instantly
                }
            }

            val titleView = TextView(this).apply {
                text = mode.title
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(if (isSelected) Color.parseColor("#1C1C1E") else Color.WHITE)
            }

            val descView = TextView(this).apply {
                text = mode.desc
                textSize = 13f
                setPadding(0, 4.dpToPxInt(), 0, 0)
                setTextColor(if (isSelected) Color.parseColor("#555555") else Color.parseColor("#8E8E93"))
            }

            itemLayout.addView(titleView)
            itemLayout.addView(descView)

            modesContainer.addView(itemLayout)
        }
    }

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
