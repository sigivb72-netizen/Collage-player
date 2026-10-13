package com.collageplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.collageplayer.R
import com.collageplayer.data.Folder
import com.collageplayer.data.Format
import com.collageplayer.data.MediaItem

/** Rows are either media items or folders. */
sealed class Row {
    data class Media(val item: MediaItem) : Row()
    data class Dir(val folder: Folder) : Row()
}

class MediaAdapter(
    private val onClick: (Row) -> Unit,
    private val onLongClick: (Row) -> Unit,
) : RecyclerView.Adapter<MediaAdapter.VH>() {

    var rows: List<Row> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView = v.findViewById(R.id.subtitle)
        val badge: TextView = v.findViewById(R.id.badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false))

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val row = rows[position]
        h.itemView.setOnClickListener { onClick(row) }
        h.itemView.setOnLongClickListener { onLongClick(row); true }
        when (row) {
            is Row.Media -> {
                val it = row.item
                h.title.text = it.title
                val res = if (it.width > 0) "${it.width}×${it.height} · " else ""
                h.sub.text = res + Format.size(it.size)
                h.badge.text = Format.time(it.durationMs)
                h.badge.visibility = if (it.durationMs > 0) View.VISIBLE else View.GONE
                h.thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                Thumbnails.load(h.itemView.context, it, h.thumb,
                    if (it.isAudio) R.drawable.ic_music else R.drawable.ic_video_library)
            }
            is Row.Dir -> {
                h.thumb.tag = null
                h.thumb.scaleType = ImageView.ScaleType.CENTER_INSIDE
                h.thumb.setImageResource(R.drawable.ic_folder)
                h.title.text = row.folder.name
                val n = row.folder.items.size
                h.sub.text = if (n == 1) "1 video" else "$n videos"
                h.badge.visibility = View.GONE
            }
        }
    }
}
