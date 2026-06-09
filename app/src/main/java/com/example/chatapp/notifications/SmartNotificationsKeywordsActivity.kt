package com.example.chatapp.notifications

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.LanguageManager
import com.example.chatapp.LocaleHelper
import com.example.chatapp.R
import com.example.chatapp.util.setHapticClickListener
import android.widget.LinearLayout

class SmartNotificationsKeywordsActivity : AppCompatActivity() {

    private lateinit var settingsStore: SmartNotificationsSettingsStore
    private lateinit var etNewKeyword: EditText
    private lateinit var btnAddKeyword: View
    private lateinit var rvKeywords: RecyclerView
    private lateinit var tvEmptyState: TextView
    private lateinit var tvToolbarTitle: TextView

    private lateinit var keywordsAdapter: KeywordsAdapter
    private var isVipMode = false
    private val keywordsList = mutableListOf<String>()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smart_notifications_keywords)

        window.statusBarColor = Color.TRANSPARENT

        settingsStore = SmartNotificationsSettingsStore(this)
        isVipMode = intent.getStringExtra("mode") == "vip"

        etNewKeyword = findViewById(R.id.etNewKeyword)
        btnAddKeyword = findViewById(R.id.btnAddKeyword)
        rvKeywords = findViewById(R.id.rvKeywords)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        tvToolbarTitle = findViewById(R.id.tvToolbarTitle)

        findViewById<View>(R.id.btnBack).setHapticClickListener { finish() }

        setupRecyclerView()
        setupInput()
        applyTranslations()
        loadKeywords()
    }

    private fun applyTranslations() {
        val titleKey = if (isVipMode) "smart_notifications_keywords_title_vip" else "smart_notifications_keywords_title_spam"
        tvToolbarTitle.text = LocaleHelper.getString(this, titleKey)
        etNewKeyword.hint = LocaleHelper.getString(this, "smart_notifications_keywords_add_hint")
        tvEmptyState.text = LocaleHelper.getString(this, "smart_notifications_keywords_empty")
    }

    private fun setupRecyclerView() {
        rvKeywords.layoutManager = LinearLayoutManager(this)
        keywordsAdapter = KeywordsAdapter { word ->
            removeKeyword(word)
        }
        rvKeywords.adapter = keywordsAdapter
    }

    private fun setupInput() {
        btnAddKeyword.setHapticClickListener {
            addNewKeyword()
        }

        etNewKeyword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                addNewKeyword()
                true
            } else {
                false
            }
        }
    }

    private fun loadKeywords() {
        keywordsList.clear()
        val loaded = if (isVipMode) settingsStore.vipWords else settingsStore.spamWords
        keywordsList.addAll(loaded.sorted())
        keywordsAdapter.submitList(keywordsList)
        updateUiState()
    }

    private fun addNewKeyword() {
        val word = etNewKeyword.text.toString().trim()
        if (word.isEmpty()) {
            return
        }
        if (keywordsList.contains(word)) {
            etNewKeyword.text = null
            return
        }

        keywordsList.add(word)
        saveKeywords()
        etNewKeyword.text = null
        loadKeywords()
    }

    private fun removeKeyword(word: String) {
        keywordsList.remove(word)
        saveKeywords()
        loadKeywords()
    }

    private fun saveKeywords() {
        val set = keywordsList.toSet()
        if (isVipMode) {
            settingsStore.vipWords = set
        } else {
            settingsStore.spamWords = set
        }
    }

    private fun updateUiState() {
        if (keywordsList.isEmpty()) {
            tvEmptyState.visibility = View.VISIBLE
            rvKeywords.visibility = View.GONE
        } else {
            tvEmptyState.visibility = View.GONE
            rvKeywords.visibility = View.VISIBLE
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun roundedDrawable(fill: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius.toFloat()
    }

    inner class KeywordsAdapter(
        private val onDeleteClicked: (String) -> Unit
    ) : RecyclerView.Adapter<KeywordsAdapter.ViewHolder>() {
        private val items = mutableListOf<String>()

        fun submitList(newList: List<String>) {
            items.clear()
            items.addAll(newList)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val context = parent.context
            val itemContainer = LinearLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
                val marginVertical = 6.dp()
                val params = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, marginVertical, 0, marginVertical)
                }
                layoutParams = params
                background = roundedDrawable(
                    Color.parseColor("#1C1C1E"),
                    12.dp()
                )
            }

            val tvWord = TextView(context).apply {
                id = View.generateViewId()
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setTextColor(Color.WHITE)
                textSize = 15f
            }
            itemContainer.addView(tvWord)

            val btnDelete = ImageView(context).apply {
                id = View.generateViewId()
                layoutParams = LinearLayout.LayoutParams(28.dp(), 28.dp())
                setImageResource(R.drawable.ic_assistant_close)
                setColorFilter(Color.parseColor("#8E8E93"))
                background = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless)).use {
                    it.getDrawable(0)
                }
                isClickable = true
                isFocusable = true
                setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
            }
            itemContainer.addView(btnDelete)

            return ViewHolder(itemContainer, tvWord, btnDelete)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(
            itemView: View,
            private val tvWord: TextView,
            private val btnDelete: ImageView
        ) : RecyclerView.ViewHolder(itemView) {
            fun bind(word: String) {
                tvWord.text = word
                btnDelete.setHapticClickListener {
                    onDeleteClicked(word)
                }
            }
        }
    }
}
