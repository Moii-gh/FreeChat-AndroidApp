package com.example.chatapp.notifications

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.LanguageManager
import com.example.chatapp.LocaleHelper
import com.example.chatapp.R
import com.example.chatapp.util.setHapticClickListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SmartNotificationsSpamActivity : AppCompatActivity() {

    private lateinit var settingsStore: SmartNotificationsSettingsStore
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutEmptyState: View
    private lateinit var rvSpamList: RecyclerView
    private lateinit var tvToolbarTitle: TextView
    private lateinit var tvEmptyState: TextView
    private lateinit var btnClearAll: ImageView

    private lateinit var spamAdapter: SpamAdapter

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smart_notifications_spam)

        window.statusBarColor = Color.TRANSPARENT

        settingsStore = SmartNotificationsSettingsStore(this)

        progressBar = findViewById(R.id.progressBar)
        layoutEmptyState = findViewById(R.id.layoutEmptyState)
        rvSpamList = findViewById(R.id.rvSpamList)
        tvToolbarTitle = findViewById(R.id.tvToolbarTitle)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        btnClearAll = findViewById(R.id.btnClearAll)

        findViewById<View>(R.id.btnBack).setHapticClickListener { finish() }

        btnClearAll.setHapticClickListener {
            showClearAllConfirmationDialog()
        }

        setupRecyclerView()
        applyTranslations()
        loadSpamNotifications()
    }

    private fun applyTranslations() {
        tvToolbarTitle.text = LocaleHelper.getString(this, "smart_notifications_spam_title")
        tvEmptyState.text = LocaleHelper.getString(this, "smart_notifications_spam_empty_state")
    }

    private fun setupRecyclerView() {
        rvSpamList.layoutManager = LinearLayoutManager(this)
        spamAdapter = SpamAdapter(
            onDeleteClicked = { itemId ->
                settingsStore.removeSpamNotification(itemId)
                loadSpamNotifications()
            },
            onRestoreClicked = { item ->
                restoreNotification(item)
            }
        )
        rvSpamList.adapter = spamAdapter
    }

    private fun loadSpamNotifications() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val displayItems = withContext(Dispatchers.IO) {
                val storeItems = settingsStore.spamNotifications
                val pm = packageManager
                val timeFormat = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                val dateFormat = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT)

                storeItems.map { item ->
                    val appLabel = runCatching {
                        val appInfo = pm.getApplicationInfo(item.packageName, 0)
                        pm.getApplicationLabel(appInfo).toString()
                    }.getOrDefault(item.packageName)

                    val appIcon = runCatching {
                        val appInfo = pm.getApplicationInfo(item.packageName, 0)
                        pm.getApplicationIcon(appInfo)
                    }.getOrNull()

                    val dateStr = runCatching {
                        val date = java.util.Date(item.timestamp)
                        val today = java.util.Calendar.getInstance()
                        val itemCal = java.util.Calendar.getInstance().apply { timeInMillis = item.timestamp }
                        if (today.get(java.util.Calendar.YEAR) == itemCal.get(java.util.Calendar.YEAR) &&
                            today.get(java.util.Calendar.DAY_OF_YEAR) == itemCal.get(java.util.Calendar.DAY_OF_YEAR)) {
                            timeFormat.format(date)
                        } else {
                            "${dateFormat.format(date)} ${timeFormat.format(date)}"
                        }
                    }.getOrDefault("")

                    DisplaySpamItem(
                        id = item.id,
                        packageName = item.packageName,
                        appLabel = appLabel,
                        appIcon = appIcon,
                        title = item.title,
                        text = item.text,
                        formattedTime = dateStr
                    )
                }
            }

            progressBar.visibility = View.GONE
            spamAdapter.submitList(displayItems)
            updateUiStates(displayItems.isEmpty())
        }
    }

    private fun updateUiStates(isEmpty: Boolean) {
        if (isEmpty) {
            layoutEmptyState.visibility = View.VISIBLE
            rvSpamList.visibility = View.GONE
            btnClearAll.visibility = View.GONE
        } else {
            layoutEmptyState.visibility = View.GONE
            rvSpamList.visibility = View.VISIBLE
            btnClearAll.visibility = View.VISIBLE
        }
    }

    private fun showClearAllConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle(LocaleHelper.getString(this, "button_delete") + "?")
            .setMessage(LocaleHelper.getString(this, "shared_link_delete_message"))
            .setPositiveButton(LocaleHelper.getString(this, "button_ok")) { _, _ ->
                settingsStore.clearAllSpam()
                loadSpamNotifications()
            }
            .setNegativeButton(LocaleHelper.getString(this, "button_cancel"), null)
            .show()
    }

    private fun restoreNotification(item: DisplaySpamItem) {
        val context = applicationContext
        val notificationManager = androidx.core.app.NotificationManagerCompat.from(context)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channel = android.app.NotificationChannel(
                "smart_notifications_channel",
                LocaleHelper.getString(context, "smart_notifications_title"),
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(item.packageName)
        val pendingIntent = if (launchIntent != null) {
            android.app.PendingIntent.getActivity(
                context,
                System.currentTimeMillis().toInt(),
                launchIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            null
        }

        val restoredTitle = "${item.appLabel}: ${item.title}"

        val builder = androidx.core.app.NotificationCompat.Builder(context, "smart_notifications_channel")
            .setSmallIcon(R.drawable.ic_freechat_notification)
            .setContentTitle(restoredTitle)
            .setContentText(item.text)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(item.text))
            .setAutoCancel(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)

        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent)
        }

        item.appIcon?.let { icon ->
            val bitmap = drawableToBitmap(icon)
            if (bitmap != null) {
                builder.setLargeIcon(bitmap)
            }
        }

        runCatching {
            notificationManager.notify(item.id.hashCode(), builder.build())
            settingsStore.removeSpamNotification(item.id)
            loadSpamNotifications()
            Toast.makeText(
                this,
                LocaleHelper.getString(this, "smart_notifications_restored_toast"),
                Toast.LENGTH_SHORT
            ).show()
        }.onFailure { e ->
            com.example.chatapp.util.SafeLog.w("SpamActivity", "Failed to restore notification", e)
        }
    }

    private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable): android.graphics.Bitmap? {
        if (drawable is android.graphics.drawable.BitmapDrawable) {
            return drawable.bitmap
        }
        return runCatching {
            val bitmap = android.graphics.Bitmap.createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1),
                android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        }.getOrNull()
    }

    inner class SpamAdapter(
        private val onDeleteClicked: (String) -> Unit,
        private val onRestoreClicked: (DisplaySpamItem) -> Unit
    ) : RecyclerView.Adapter<SpamAdapter.ViewHolder>() {

        private val items = mutableListOf<DisplaySpamItem>()

        fun submitList(newList: List<DisplaySpamItem>) {
            items.clear()
            items.addAll(newList)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_spam_notification, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivAppIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
            private val tvAppLabel: TextView = itemView.findViewById(R.id.tvAppLabel)
            private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
            private val tvSpamTitle: TextView = itemView.findViewById(R.id.tvSpamTitle)
            private val tvSpamText: TextView = itemView.findViewById(R.id.tvSpamText)
            private val btnRestoreSpam: View = itemView.findViewById(R.id.btnRestoreSpam)
            private val btnDeleteSpam: View = itemView.findViewById(R.id.btnDeleteSpam)

            fun bind(item: DisplaySpamItem) {
                tvAppLabel.text = item.appLabel
                tvTimestamp.text = item.formattedTime

                if (item.appIcon != null) {
                    ivAppIcon.setImageDrawable(item.appIcon)
                } else {
                    ivAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                }

                if (item.title.isNotBlank()) {
                    tvSpamTitle.text = item.title
                    tvSpamTitle.visibility = View.VISIBLE
                } else {
                    tvSpamTitle.visibility = View.GONE
                }

                if (item.text.isNotBlank()) {
                    tvSpamText.text = item.text
                    tvSpamText.visibility = View.VISIBLE
                } else {
                    tvSpamText.visibility = View.GONE
                }

                btnRestoreSpam.setHapticClickListener {
                    onRestoreClicked(item)
                }

                btnDeleteSpam.setHapticClickListener {
                    onDeleteClicked(item.id)
                }
            }
        }
    }

    data class DisplaySpamItem(
        val id: String,
        val packageName: String,
        val appLabel: String,
        val appIcon: Drawable?,
        val title: String,
        val text: String,
        val formattedTime: String
    )
}
