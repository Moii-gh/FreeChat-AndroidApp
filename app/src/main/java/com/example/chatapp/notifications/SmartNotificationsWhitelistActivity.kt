package com.example.chatapp.notifications

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.LanguageManager
import com.example.chatapp.LocaleHelper
import com.example.chatapp.R
import com.example.chatapp.util.setHapticClickListener
import com.google.android.material.checkbox.MaterialCheckBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SmartNotificationsWhitelistActivity : AppCompatActivity() {

    private lateinit var settingsStore: SmartNotificationsSettingsStore
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmptyState: TextView
    private lateinit var selectedAppsHeader: View
    private lateinit var rvSelectedApps: RecyclerView
    private lateinit var layoutCollapsedHeader: View
    private lateinit var tvSelectedCountPill: TextView
    private lateinit var rvAppsList: RecyclerView
    private lateinit var etSearch: EditText

    private val selectionOrder = mutableListOf<String>()
    private var allApps: List<AppItem> = emptyList()
    private var filteredApps: List<AppItem> = emptyList()

    private lateinit var whitelistAdapter: WhitelistAppsAdapter
    private lateinit var selectedAdapter: SelectedAppsAdapter
    private var isHeaderCollapsed = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smart_notifications_whitelist)

        window.statusBarColor = Color.TRANSPARENT

        settingsStore = SmartNotificationsSettingsStore(this)

        progressBar = findViewById(R.id.progressBar)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        selectedAppsHeader = findViewById(R.id.selectedAppsHeader)
        rvSelectedApps = findViewById(R.id.rvSelectedApps)
        layoutCollapsedHeader = findViewById(R.id.layoutCollapsedHeader)
        tvSelectedCountPill = findViewById(R.id.tvSelectedCountPill)
        rvAppsList = findViewById(R.id.rvAppsList)
        etSearch = findViewById(R.id.etSearch)

        findViewById<View>(R.id.btnBack).setHapticClickListener { finish() }

        setupRecyclerViews()
        setupSearch()
        loadApplications()
        applyTranslations()
    }

    private fun applyTranslations() {
        findViewById<TextView>(R.id.tvToolbarTitle)?.text =
            LocaleHelper.getString(this, "smart_notifications_whitelist_title")
        etSearch.hint = LocaleHelper.getString(this, "smart_notifications_whitelist_search_hint")
        tvEmptyState.text = LocaleHelper.getString(this, "smart_notifications_whitelist_empty")
    }

    private fun setupRecyclerViews() {
        rvAppsList.layoutManager = LinearLayoutManager(this)
        whitelistAdapter = WhitelistAppsAdapter(
            onItemClicked = { item ->
                item.isWhitelisted = !item.isWhitelisted
                saveWhitelistState(item)
                whitelistAdapter.notifyDataSetChanged()
                updateHeaderState()
            }
        )
        rvAppsList.adapter = whitelistAdapter

        rvSelectedApps.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        selectedAdapter = SelectedAppsAdapter()
        rvSelectedApps.adapter = selectedAdapter

        // Add snapping helper to allow beautiful snapping swiping/page flipping gesture horizontally
        val snapHelper = androidx.recyclerview.widget.LinearSnapHelper()
        snapHelper.attachToRecyclerView(rvSelectedApps)

        // Scroll listener to collapse/expand selected apps top bar
        rvAppsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
                val firstVisible = layoutManager?.findFirstCompletelyVisibleItemPosition() ?: 0

                if (firstVisible == 0) {
                    // Force expanded when at the absolute top of the list
                    setHeaderCollapsed(false)
                } else if (dy > 0) {
                    // Scrolling down - collapse
                    setHeaderCollapsed(true)
                } else if (dy < 0) {
                    // Scrolling up - expand
                    setHeaderCollapsed(false)
                }
            }
        })
    }

    private fun setHeaderCollapsed(collapsed: Boolean) {
        if (isHeaderCollapsed == collapsed) return
        isHeaderCollapsed = collapsed

        if (collapsed) {
            rvSelectedApps.visibility = View.GONE
            layoutCollapsedHeader.visibility = View.VISIBLE
            selectedAppsHeader.setBackgroundResource(R.drawable.bg_outer_pill)
        } else {
            rvSelectedApps.visibility = View.VISIBLE
            layoutCollapsedHeader.visibility = View.GONE
            selectedAppsHeader.setBackgroundResource(R.drawable.bg_assistant_card)
        }
    }

    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun loadApplications() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val resolvedApps = withContext(Dispatchers.IO) {
                val pm = packageManager
                val packages = pm.getInstalledPackages(0)
                val whitelistedSet = settingsStore.whitelist
                val currentPkgName = packageName

                packages.mapNotNull { packageInfo ->
                    val appInfo = packageInfo.applicationInfo ?: return@mapNotNull null
                    if (appInfo.packageName == currentPkgName) return@mapNotNull null

                    val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    if (isSystem) return@mapNotNull null

                    val label = pm.getApplicationLabel(appInfo).toString()
                    val icon = try {
                        pm.getApplicationIcon(appInfo)
                    } catch (_: Exception) {
                        null
                    }

                    AppItem(
                        packageName = appInfo.packageName,
                        label = label,
                        icon = icon,
                        isWhitelisted = whitelistedSet.contains(appInfo.packageName)
                    )
                }.sortedBy { it.label.lowercase() }
            }

            allApps = resolvedApps

            // Populate the selectionOrder list to preserve initial whitelisted apps
            selectionOrder.clear()
            selectionOrder.addAll(allApps.filter { it.isWhitelisted }.map { it.packageName })

            filterApps(etSearch.text.toString())
            progressBar.visibility = View.GONE
            updateHeaderState()
        }
    }

    private fun filterApps(query: String) {
        filteredApps = if (query.isBlank()) {
            allApps
        } else {
            allApps.filter {
                it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
            }
        }
        whitelistAdapter.submitList(filteredApps)
        tvEmptyState.visibility = if (filteredApps.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun saveWhitelistState(item: AppItem) {
        val currentSet = settingsStore.whitelist.toMutableSet()
        if (item.isWhitelisted) {
            currentSet.add(item.packageName)
            if (!selectionOrder.contains(item.packageName)) {
                selectionOrder.add(item.packageName)
            }
        } else {
            currentSet.remove(item.packageName)
            selectionOrder.remove(item.packageName)
        }
        settingsStore.whitelist = currentSet
    }

    private fun updateHeaderState() {
        val whitelisted = allApps.filter { it.isWhitelisted }
        if (whitelisted.isEmpty()) {
            selectedAppsHeader.visibility = View.GONE
        } else {
            selectedAppsHeader.visibility = View.VISIBLE
            selectedAdapter.submitList(whitelisted)

            // Sync background with current collapse state
            if (isHeaderCollapsed) {
                rvSelectedApps.visibility = View.GONE
                layoutCollapsedHeader.visibility = View.VISIBLE
                selectedAppsHeader.setBackgroundResource(R.drawable.bg_outer_pill)
            } else {
                rvSelectedApps.visibility = View.VISIBLE
                layoutCollapsedHeader.visibility = View.GONE
                selectedAppsHeader.setBackgroundResource(R.drawable.bg_assistant_card)
            }

            // Update collapsed count pill text
            tvSelectedCountPill.text = LocaleHelper.formatString(
                this,
                "smart_notifications_whitelist_selected",
                whitelisted.size
            )

            // Update overlapping recent icons on the right (up to 7 icons)
            val recentPackages = selectionOrder.takeLast(7).reversed()
            val recentApps = recentPackages.mapNotNull { pkg -> allApps.find { it.packageName == pkg } }

            val cards = listOf(
                findViewById<View>(R.id.cardRecent1),
                findViewById<View>(R.id.cardRecent2),
                findViewById<View>(R.id.cardRecent3),
                findViewById<View>(R.id.cardRecent4),
                findViewById<View>(R.id.cardRecent5),
                findViewById<View>(R.id.cardRecent6),
                findViewById<View>(R.id.cardRecent7)
            )
            val imageViews = listOf(
                findViewById<ImageView>(R.id.ivRecent1),
                findViewById<ImageView>(R.id.ivRecent2),
                findViewById<ImageView>(R.id.ivRecent3),
                findViewById<ImageView>(R.id.ivRecent4),
                findViewById<ImageView>(R.id.ivRecent5),
                findViewById<ImageView>(R.id.ivRecent6),
                findViewById<ImageView>(R.id.ivRecent7)
            )

            for (i in 0 until 7) {
                if (i < recentApps.size) {
                    val app = recentApps[i]
                    cards[i].visibility = View.VISIBLE
                    if (app.icon != null) {
                        imageViews[i].setImageDrawable(app.icon)
                    } else {
                        imageViews[i].setImageResource(android.R.drawable.sym_def_app_icon)
                    }
                } else {
                    cards[i].visibility = View.GONE
                }
            }
        }
    }

    // Horizontal Recycler Adapter for selected apps icons
    inner class SelectedAppsAdapter : RecyclerView.Adapter<SelectedAppsAdapter.ViewHolder>() {
        private val items = mutableListOf<AppItem>()

        fun submitList(newList: List<AppItem>) {
            items.clear()
            items.addAll(newList)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_selected_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivIcon: ImageView = itemView.findViewById(R.id.ivSelectedAppIcon)
            private val tvLabel: TextView = itemView.findViewById(R.id.tvSelectedAppLabel)

            fun bind(item: AppItem) {
                tvLabel.text = item.label
                if (item.icon != null) {
                    ivIcon.setImageDrawable(item.icon)
                } else {
                    ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                }

                itemView.setHapticClickListener {
                    // Tap on top icon toggles selection off
                    item.isWhitelisted = false
                    saveWhitelistState(item)
                    whitelistAdapter.notifyDataSetChanged()
                    updateHeaderState()
                }
            }
        }
    }

    // Vertical Recycler Adapter for all packages
    inner class WhitelistAppsAdapter(
        private val onItemClicked: (AppItem) -> Unit
    ) : RecyclerView.Adapter<WhitelistAppsAdapter.ViewHolder>() {

        private val items = mutableListOf<AppItem>()

        fun submitList(newList: List<AppItem>) {
            items.clear()
            items.addAll(newList)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_whitelist_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val cbSelected: MaterialCheckBox = itemView.findViewById(R.id.cbAppSelected)
            private val ivIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
            private val tvLabel: TextView = itemView.findViewById(R.id.tvAppLabel)
            private val tvPackage: TextView = itemView.findViewById(R.id.tvAppPackage)

            fun bind(item: AppItem) {
                tvLabel.text = item.label
                tvPackage.text = item.packageName

                if (item.icon != null) {
                    ivIcon.setImageDrawable(item.icon)
                } else {
                    ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                }

                cbSelected.isChecked = item.isWhitelisted

                itemView.setHapticClickListener {
                    onItemClicked(item)
                }
            }
        }
    }

    data class AppItem(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
        var isWhitelisted: Boolean
    )
}
