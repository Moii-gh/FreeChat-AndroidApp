package com.example.chatapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.network.dto.ChatShareItemDto
import com.example.chatapp.util.setHapticClickListener
import kotlinx.coroutines.launch

class SharedLinksActivity : AppCompatActivity() {

    private lateinit var chatRepository: ChatRepository
    private lateinit var adapter: SharedLinksAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyStateContainer: LinearLayout

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shared_links)

        window.statusBarColor = Color.TRANSPARENT
        chatRepository = ChatRepository(this)

        progressBar = findViewById(R.id.progressBar)
        emptyStateContainer = findViewById(R.id.emptyStateContainer)

        findViewById<ImageView>(R.id.btnBack).setHapticClickListener {
            finish()
        }

        setupRecyclerView()
        loadLinks()
    }

    private fun setupRecyclerView() {
        adapter = SharedLinksAdapter(
            onCopyClick = { item -> copyToClipboard(item) },
            onDeleteClick = { item -> showDeleteConfirmation(item) }
        )
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun loadLinks() {
        progressBar.visibility = View.VISIBLE
        emptyStateContainer.visibility = View.GONE

        lifecycleScope.launch {
            val result = chatRepository.getMySharedLinks()
            progressBar.visibility = View.GONE

            result.onSuccess { links ->
                if (links.isEmpty()) {
                    emptyStateContainer.visibility = View.VISIBLE
                } else {
                    adapter.submitList(links)
                }
            }.onFailure {
                Toast.makeText(this@SharedLinksActivity, LocaleHelper.getString(this@SharedLinksActivity, "shared_links_load_error"), Toast.LENGTH_SHORT).show()
                emptyStateContainer.visibility = View.VISIBLE
            }
        }
    }

    private fun copyToClipboard(item: ChatShareItemDto) {
        if (item.token.isBlank()) {
            Toast.makeText(this, LocaleHelper.getString(this, "shared_link_expired_copy_error"), Toast.LENGTH_SHORT).show()
            return
        }
        // Use the HTTP landing page URL — it works in any messenger/browser
        // and automatically redirects to freechat:// to open the app
        val url = "${BuildConfig.CHAT_SHARE_PUBLIC_BASE_URL.removeSuffix("/")}/share/${item.token}"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("FreeChat Share", url)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, LocaleHelper.getString(this, "shared_link_copied"), Toast.LENGTH_SHORT).show()
    }

    private fun showDeleteConfirmation(item: ChatShareItemDto) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val dialogView = layoutInflater.inflate(R.layout.dialog_delete_shared_link, null)
        val card = dialogView.findViewById<View>(R.id.deleteDialogCard)
        val title = dialogView.findViewById<TextView>(R.id.tvDeleteDialogTitle)
        val message = dialogView.findViewById<TextView>(R.id.tvDeleteDialogMessage)
        val cancelButton = dialogView.findViewById<TextView>(R.id.btnDeleteDialogCancel)
        val confirmButton = dialogView.findViewById<TextView>(R.id.btnDeleteDialogConfirm)

        title.text = LocaleHelper.getString(this, "shared_link_delete_title")
        message.text = LocaleHelper.getString(this, "shared_link_delete_message")
        cancelButton.text = LocaleHelper.getString(this, "button_cancel")
        confirmButton.text = LocaleHelper.getString(this, "button_delete")

        card.alpha = 0f
        card.scaleX = 0.94f
        card.scaleY = 0.94f

        cancelButton.setHapticClickListener {
            dialog.dismiss()
        }
        confirmButton.setHapticClickListener {
            dialog.dismiss()
            deleteLink(item)
        }

        dialog.setContentView(dialogView)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setDimAmount(0.58f)
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            card.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180L)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .start()
        }
        dialog.show()
    }

    private fun deleteLink(item: ChatShareItemDto) {
        lifecycleScope.launch {
            if (item.token.isBlank()) {
                Toast.makeText(this@SharedLinksActivity, LocaleHelper.getString(this@SharedLinksActivity, "shared_link_delete_old_error"), Toast.LENGTH_SHORT).show()
                return@launch
            }

            val result = chatRepository.revokeShareLinkByToken(item.token)
            result.onSuccess {
                adapter.removeItem(item.token)
                if (adapter.itemCount == 0) {
                    emptyStateContainer.visibility = View.VISIBLE
                }
                Toast.makeText(this@SharedLinksActivity, LocaleHelper.getString(this@SharedLinksActivity, "shared_link_deleted"), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@SharedLinksActivity, LocaleHelper.getString(this@SharedLinksActivity, "shared_link_delete_error"), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
