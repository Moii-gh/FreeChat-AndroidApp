package com.example.chatapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
                Toast.makeText(this@SharedLinksActivity, "Ошибка загрузки ссылок", Toast.LENGTH_SHORT).show()
                emptyStateContainer.visibility = View.VISIBLE
            }
        }
    }

    private fun copyToClipboard(item: ChatShareItemDto) {
        if (item.token.isBlank()) {
            Toast.makeText(this, "Ссылка устарела и не может быть скопирована", Toast.LENGTH_SHORT).show()
            return
        }
        // Use the HTTP landing page URL — it works in any messenger/browser
        // and automatically redirects to freechat:// to open the app
        val url = "${BuildConfig.APP_API_BASE_URL.removeSuffix("/api/v1")}/share/${item.token}"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("FreeChat Share", url)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Ссылка скопирована ✓", Toast.LENGTH_SHORT).show()
    }

    private fun showDeleteConfirmation(item: ChatShareItemDto) {
        AlertDialog.Builder(this)
            .setTitle("Удаление ссылки")
            .setMessage("Вы уверены, что хотите удалить эту ссылку? Люди, у которых она есть, потеряют доступ к копии чата.")
            .setPositiveButton("Удалить") { _, _ ->
                deleteLink(item)
            }
            .setNegativeButton(LocaleHelper.getString(this, "button_cancel"), null)
            .show()
    }

    private fun deleteLink(item: ChatShareItemDto) {
        lifecycleScope.launch {
            if (item.token.isBlank()) {
                Toast.makeText(this@SharedLinksActivity, "Невозможно удалить старую ссылку", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val result = chatRepository.revokeShareLinkByToken(item.token)
            result.onSuccess {
                adapter.removeItem(item.token)
                if (adapter.itemCount == 0) {
                    emptyStateContainer.visibility = View.VISIBLE
                }
                Toast.makeText(this@SharedLinksActivity, "Ссылка удалена", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@SharedLinksActivity, "Ошибка удаления ссылки", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
