package com.example.chatapp

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AiModeActivity : AppCompatActivity() {

    private lateinit var modesContainer: LinearLayout

    data class ModeItem(val title: String, val desc: String)

    private val modes by lazy {
        listOf(
            ModeItem(
                title = "Server-side routing",
                desc = "The backend now chooses the model by subscription and request type. The client no longer switches providers itself."
            ),
            ModeItem(
                title = "Task modes stay available",
                desc = "Chat, study, shopping, search and image generation still work, but they only describe the task. Final model selection happens on the server."
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_mode)

        window.statusBarColor = Color.TRANSPARENT

        modesContainer = findViewById(R.id.modesContainer)
        populateModes()

        findViewById<TextView>(R.id.tvToolbarTitle)?.text = LocaleHelper.getString(this, "lable_apps")
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
    }

    private fun populateModes() {
        modesContainer.removeAllViews()

        for (mode in modes) {
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(20.dpToPxInt(), 16.dpToPxInt(), 20.dpToPxInt(), 16.dpToPxInt())

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 12.dpToPxInt()
                }

                background = GradientDrawable().apply {
                    cornerRadius = 24f.dpToPx()
                    setColor(Color.parseColor("#1C1C1E"))
                }
            }

            val titleView = TextView(this).apply {
                text = mode.title
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
            }

            val descView = TextView(this).apply {
                text = mode.desc
                textSize = 13f
                setPadding(0, 4.dpToPxInt(), 0, 0)
                setTextColor(Color.parseColor("#8E8E93"))
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
