package com.collageplayer.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import com.collageplayer.data.MediaItem
import java.util.concurrent.Executors

/** Tiny thumbnail loader: MediaStore thumbnails, background pool, memory cache. */
object Thumbnails {
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<Long, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }

    fun load(context: Context, item: MediaItem, into: ImageView, placeholder: Int) {
        val key = if (item.isAudio) -item.id else item.id
        into.tag = key
        cache.get(key)?.let { into.setImageBitmap(it); return }
        into.setImageResource(placeholder)
        if (item.isAudio && Build.VERSION.SDK_INT < 29) return
        val app = context.applicationContext
        pool.execute {
            val bmp = try {
                if (Build.VERSION.SDK_INT >= 29) {
                    app.contentResolver.loadThumbnail(item.uri, Size(320, 180), null)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Video.Thumbnails.getThumbnail(
                        app.contentResolver, item.id,
                        MediaStore.Video.Thumbnails.MINI_KIND, null
                    )
                }
            } catch (_: Throwable) {
                null
            }
            if (bmp != null) {
                cache.put(key, bmp)
                main.post { if (into.tag == key) into.setImageBitmap(bmp) }
            }
        }
    }
}
