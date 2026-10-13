package com.collageplayer.data

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import androidx.media3.common.MediaItem as M3Item

/**
 * Everything that touches the "Collage Mode" folder (Movies/Collage Mode):
 * copying videos in (optionally re-encoding them to a collage-friendly size),
 * and deleting them.
 */
object CollageStore {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    interface ImportCallback {
        fun onProgress(index: Int, total: Int, name: String, percent: Int, optimizing: Boolean)
        fun onFinished(copied: Int, failed: Int)
    }

    class ImportJob internal constructor() {
        @Volatile internal var cancelled = false
        internal var transformer: Transformer? = null
        fun cancel() {
            cancelled = true
            main.post { transformer?.cancel() }
        }
    }

    /** Copies [uris] into the collage folder, one after another. Must be called on the main thread. */
    fun import(
        context: Context,
        uris: List<Uri>,
        optimize: Boolean,
        targetShortSide: Int,
        cb: ImportCallback,
    ): ImportJob {
        val app = context.applicationContext
        val job = ImportJob()
        var copied = 0
        var failed = 0

        fun step(i: Int) {
            if (i >= uris.size || job.cancelled) {
                cb.onFinished(copied, failed)
                return
            }
            val src = uris[i]
            io.execute {
                val name = displayName(app, src) ?: "video_${System.currentTimeMillis()}.mp4"
                val shortSide = if (optimize) shortSide(app, src) else 0
                main.post {
                    val done: (Boolean) -> Unit = { ok ->
                        if (ok) copied++ else failed++
                        step(i + 1)
                    }
                    if (optimize && shortSide > targetShortSide * 1.1f) {
                        cb.onProgress(i, uris.size, name, 0, true)
                        transcode(app, src, name, targetShortSide, job, { p ->
                            cb.onProgress(i, uris.size, name, p, true)
                        }) { ok ->
                            if (ok || job.cancelled) done(ok)
                            else plainCopy(app, src, name, done) // encoder failed: keep the original
                        }
                    } else {
                        cb.onProgress(i, uris.size, name, 0, false)
                        plainCopy(app, src, name, done)
                    }
                }
            }
        }
        step(0)
        return job
    }

    private fun plainCopy(app: Context, src: Uri, name: String, done: (Boolean) -> Unit) {
        io.execute {
            val ok = try {
                val mime = app.contentResolver.getType(src) ?: "video/mp4"
                app.contentResolver.openInputStream(src)?.use { write(app, name, mime, it) } != null
            } catch (t: Throwable) {
                false
            }
            main.post { done(ok) }
        }
    }

    @OptIn(UnstableApi::class)
    private fun transcode(
        app: Context, src: Uri, name: String, shortSide: Int, job: ImportJob,
        progress: (Int) -> Unit, done: (Boolean) -> Unit,
    ) {
        val out = File(app.cacheDir, "collage_transcode.mp4").apply { delete() }
        val outName = name.substringBeforeLast('.') + "_collage.mp4"
        val holder = ProgressHolder()
        var finished = false

        val transformer = Transformer.Builder(app)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finished = true
                    job.transformer = null
                    io.execute {
                        val ok = try {
                            out.inputStream().use { write(app, outName, "video/mp4", it) } != null
                        } catch (t: Throwable) {
                            false
                        }
                        out.delete()
                        main.post { done(ok) }
                    }
                }

                override fun onError(
                    composition: Composition, exportResult: ExportResult, exportException: ExportException,
                ) {
                    finished = true
                    job.transformer = null
                    out.delete()
                    done(false)
                }
            })
            .build()
        job.transformer = transformer

        val edited = EditedMediaItem.Builder(M3Item.fromUri(src))
            .setEffects(Effects(emptyList(), listOf(Presentation.createForShortSide(shortSide))))
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(edited).build())
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
        try {
            transformer.start(composition, out.absolutePath)
        } catch (t: Throwable) {
            job.transformer = null
            done(false)
            return
        }
        val poll = object : Runnable {
            override fun run() {
                if (finished) return
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    progress(holder.progress)
                }
                main.postDelayed(this, 400)
            }
        }
        main.post(poll)
    }

    private fun shortSide(app: Context, uri: Uri): Int = try {
        MediaMetadataRetriever().run {
            setDataSource(app, uri)
            val w = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            release()
            minOf(w, h)
        }
    } catch (_: Throwable) {
        0
    }

    fun displayName(app: Context, uri: Uri): String? = try {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Throwable) {
        null
    }

    /** Writes a stream as a new video inside the collage folder. Returns its uri. */
    private fun write(app: Context, name: String, mime: String, input: InputStream): Uri? {
        val safeMime = if (mime.startsWith("video/")) mime else "video/mp4"
        return if (Build.VERSION.SDK_INT >= 29) {
            val cr = app.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, safeMime)
                put(MediaStore.Video.Media.RELATIVE_PATH, MediaRepository.COLLAGE_RELATIVE_PATH.trimEnd('/'))
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: return null
            try {
                cr.openOutputStream(uri)?.use { input.copyTo(it, 1 shl 16) } ?: throw IllegalStateException()
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                cr.update(uri, values, null, null)
                uri
            } catch (t: Throwable) {
                cr.delete(uri, null, null)
                null
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                MediaRepository.COLLAGE_FOLDER)
            dir.mkdirs()
            var f = File(dir, name)
            var n = 1
            while (f.exists()) {
                f = File(dir, name.substringBeforeLast('.') + " ($n)." + name.substringAfterLast('.', "mp4"))
                n++
            }
            f.outputStream().use { input.copyTo(it, 1 shl 16) }
            MediaScannerConnection.scanFile(app, arrayOf(f.absolutePath), arrayOf(safeMime), null)
            Uri.fromFile(f)
        }
    }

    /**
     * Deletes items from the collage folder. Returns an IntentSender when the system must ask the
     * user for confirmation (files not created by this install), else null.
     */
    fun delete(context: Context, uris: List<Uri>): android.content.IntentSender? {
        val cr = context.contentResolver
        if (Build.VERSION.SDK_INT >= 30) {
            return MediaStore.createDeleteRequest(cr, uris).intentSender
        }
        for (u in uris) {
            try {
                cr.delete(u, null, null)
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException) {
                    return e.userAction.actionIntent.intentSender
                }
            }
        }
        return null
    }
}
