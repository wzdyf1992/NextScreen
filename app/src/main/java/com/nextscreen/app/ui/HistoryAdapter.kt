package com.nextscreen.app.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nextscreen.app.R
import com.nextscreen.app.data.HistoryStore

class HistoryAdapter(private val onClick: (HistoryStore.Item) -> Unit) :
    RecyclerView.Adapter<HistoryAdapter.VH>() {

    private var items: List<HistoryStore.Item> = emptyList()

    fun submit(list: List<HistoryStore.Item>) {
        items = list
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val it = items[position]
        h.title.text = it.title
        val kind = when (it.kind) {
            "AUDIO" -> "音频"
            "IMAGE" -> "图片"
            else -> "视频"
        }
        h.icon.setImageResource(
            when (it.kind) {
                "AUDIO" -> R.drawable.ic_music
                "IMAGE" -> R.drawable.ic_image
                else -> R.drawable.ic_movie
            }
        )
        val time = DateUtils.getRelativeTimeSpanString(it.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        h.subtitle.text = "$kind · $time · 点击重新播放"
        h.itemView.setOnClickListener { _ -> onClick(it) }
    }
}
