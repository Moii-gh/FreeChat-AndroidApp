package com.example.chatapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.network.dto.ChatShareItemDto
import com.example.chatapp.util.setHapticClickListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SharedLinksAdapter(
    private val onCopyClick: (ChatShareItemDto) -> Unit,
    private val onDeleteClick: (ChatShareItemDto) -> Unit
) : RecyclerView.Adapter<SharedLinksAdapter.ViewHolder>() {

    private val items = mutableListOf<ChatShareItemDto>()
    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    fun submitList(newItems: List<ChatShareItemDto>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun removeItem(token: String) {
        val index = items.indexOfFirst { it.token == token }
        if (index != -1) {
            items.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_shared_link, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvLinkTitle: TextView = itemView.findViewById(R.id.tvLinkTitle)
        private val tvLinkDate: TextView = itemView.findViewById(R.id.tvLinkDate)
        private val btnCopy: FrameLayout = itemView.findViewById(R.id.btnCopy)
        private val btnDelete: FrameLayout = itemView.findViewById(R.id.btnDelete)

        fun bind(item: ChatShareItemDto) {
            tvLinkTitle.text = item.title.ifBlank { LocaleHelper.getString(itemView.context, "untitled_chat") }

            val formattedDate = try {
                item.createdAt?.let { raw ->
                    val parsers = listOf(
                        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
                        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                    )
                    var date: Date? = null
                    for (parser in parsers) {
                        try { date = parser.parse(raw); break } catch (_: Exception) {}
                    }
                    if (date != null) LocaleHelper.formatString(itemView.context, "shared_link_created_at", dateFormat.format(date)) else LocaleHelper.formatString(itemView.context, "shared_link_created_at", raw)
                } ?: LocaleHelper.getString(itemView.context, "shared_link_unknown_date")
            } catch (_: Exception) {
                LocaleHelper.getString(itemView.context, "shared_link_unknown_date")
            }
            tvLinkDate.text = formattedDate

            btnCopy.setHapticClickListener {
                onCopyClick(item)
            }

            btnDelete.setHapticClickListener {
                onDeleteClick(item)
            }

            if (item.token.isBlank()) {
                btnCopy.alpha = 0.3f
                btnCopy.isEnabled = false
            } else {
                btnCopy.alpha = 1.0f
                btnCopy.isEnabled = true
            }
        }
    }
}
