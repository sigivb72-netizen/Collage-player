package com.collageplayer.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val title: String,
    val durationMs: Long,
    val size: Long,
    val dateAdded: Long,
    val width: Int,
    val height: Int,
    val folder: String,
    val isAudio: Boolean = false,
)

data class Folder(val name: String, val items: List<MediaItem>)

object MediaRepository {

    const val COLLAGE_FOLDER = "Collage Mode"
    /** Relative path (Android 10+) of the collage folder inside shared storage. */
    val COLLAGE_RELATIVE_PATH = "Movies/$COLLAGE_FOLDER/"

    private fun videoCollection(): Uri =
        if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    private fun audioCollection(): Uri =
        if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    fun loadVideos(context: Context): List<MediaItem> =
        queryVideos(context, null, null)

    @Suppress("DEPRECATION")
    fun loadCollageVideos(context: Context): List<MediaItem> {
        return if (Build.VERSION.SDK_INT >= 29) {
            queryVideos(
                context,
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("$COLLAGE_RELATIVE_PATH%")
            )
        } else {
            queryVideos(
                context,
                "${MediaStore.Video.Media.DATA} LIKE ?",
                arrayOf("%/Movies/$COLLAGE_FOLDER/%")
            )
        }
    }

    private fun queryVideos(context: Context, sel: String?, args: Array<String>?): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )
        val coll = videoCollection()
        try {
            context.contentResolver.query(coll, proj, sel, args,
                "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(proj[0])
                val iName = c.getColumnIndexOrThrow(proj[1])
                val iDur = c.getColumnIndexOrThrow(proj[2])
                val iSize = c.getColumnIndexOrThrow(proj[3])
                val iDate = c.getColumnIndexOrThrow(proj[4])
                val iW = c.getColumnIndexOrThrow(proj[5])
                val iH = c.getColumnIndexOrThrow(proj[6])
                val iB = c.getColumnIndexOrThrow(proj[7])
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    out += MediaItem(
                        id = id,
                        uri = ContentUris.withAppendedId(coll, id),
                        title = c.getString(iName) ?: "video",
                        durationMs = c.getLong(iDur),
                        size = c.getLong(iSize),
                        dateAdded = c.getLong(iDate),
                        width = c.getInt(iW),
                        height = c.getInt(iH),
                        folder = c.getString(iB) ?: "Unknown",
                    )
                }
            }
        } catch (_: SecurityException) {
        }
        return out
    }

    fun loadAudio(context: Context): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.ARTIST,
        )
        val coll = audioCollection()
        try {
            context.contentResolver.query(coll, proj, null, null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out += MediaItem(
                        id = id,
                        uri = ContentUris.withAppendedId(coll, id),
                        title = c.getString(1) ?: "audio",
                        durationMs = c.getLong(2),
                        size = c.getLong(3),
                        dateAdded = c.getLong(4),
                        width = 0, height = 0,
                        folder = c.getString(5) ?: "",
                        isAudio = true,
                    )
                }
            }
        } catch (_: SecurityException) {
        }
        return out
    }

    fun groupFolders(items: List<MediaItem>): List<Folder> =
        items.groupBy { it.folder }
            .map { (k, v) -> Folder(k, v) }
            .sortedBy { it.name.lowercase() }

    fun sort(items: List<MediaItem>, order: Int): List<MediaItem> = when (order) {
        1 -> items.sortedBy { it.title.lowercase() }
        2 -> items.sortedByDescending { it.size }
        3 -> items.sortedByDescending { it.durationMs }
        else -> items.sortedByDescending { it.dateAdded }
    }

    val SORT_NAMES = arrayOf("Date added", "Name", "Size", "Duration")
}
