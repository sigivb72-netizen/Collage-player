package com.collageplayer.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.collageplayer.R
import com.collageplayer.data.CollageStore
import com.collageplayer.data.Format
import com.collageplayer.data.Prefs
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Full-featured player built on libVLC — the same engine as VLC for Android,
 * so it plays the same formats (mkv, avi, flv, wmv, ts, hevc, vp9, av1, dts, ac3, …)
 * plus network streams.
 */
class PlayerActivity : AppCompatActivity(), MediaPlayer.EventListener {

    companion object {
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_TITLES = "titles"
        private const val EXTRA_INDEX = "index"

        fun start(context: Context, uris: List<Uri>, titles: List<String>, index: Int) {
            context.startActivity(Intent(context, PlayerActivity::class.java)
                .putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris))
                .putStringArrayListExtra(EXTRA_TITLES, ArrayList(titles))
                .putExtra(EXTRA_INDEX, index))
        }

        private val SCALES = listOf(
            MediaPlayer.ScaleType.SURFACE_BEST_FIT to "Best fit",
            MediaPlayer.ScaleType.SURFACE_FIT_SCREEN to "Fit screen",
            MediaPlayer.ScaleType.SURFACE_FILL to "Fill",
            MediaPlayer.ScaleType.SURFACE_16_9 to "16:9",
            MediaPlayer.ScaleType.SURFACE_4_3 to "4:3",
            MediaPlayer.ScaleType.SURFACE_16_10 to "16:10",
            MediaPlayer.ScaleType.SURFACE_221_1 to "2.21:1",
            MediaPlayer.ScaleType.SURFACE_235_1 to "2.35:1",
            MediaPlayer.ScaleType.SURFACE_ORIGINAL to "Original",
        )
        private val SPEEDS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)
    }

    private enum class Repeat { OFF, ONE, ALL }

    private lateinit var prefs: Prefs
    private lateinit var libVLC: LibVLC
    private lateinit var player: MediaPlayer
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var audioManager: AudioManager

    private lateinit var controls: View
    private lateinit var centerInfo: TextView
    private lateinit var buffering: ProgressBar
    private lateinit var seek: SeekBar
    private lateinit var timeCur: TextView
    private lateinit var timeTotal: TextView
    private lateinit var btnPlay: MaterialButton
    private lateinit var btnSpeed: MaterialButton
    private lateinit var btnRepeat: MaterialButton
    private lateinit var btnUnlock: View
    private lateinit var titleView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var playlist: List<Uri> = emptyList()
    private var titles: List<String> = emptyList()
    private var index = 0
    private var pfd: ParcelFileDescriptor? = null

    private var scaleIndex = 0
    private var repeat = Repeat.OFF
    private var locked = false
    private var userSeeking = false
    private var wasPlayingBeforeStop = true
    private var abA = -1L
    private var abB = -1L
    private var sleepAt = 0L
    private var orientationMode = 0 // 0 sensor, 1 landscape, 2 portrait
    private var equalizer: MediaPlayer.Equalizer? = null

    private val hideControls = Runnable { setControlsVisible(false) }
    private val hideCenter = Runnable { centerInfo.visibility = View.GONE }
    private val sleepRunnable = Runnable {
        player.pause()
        Toast.makeText(this, "Sleep timer: paused", Toast.LENGTH_SHORT).show()
        finish()
    }

    private val pickSubtitle =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::loadSubtitle) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        prefs = Prefs(this)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        bindViews()
        readIntent(intent)

        val opts = arrayListOf(
            if (prefs.audioTimeStretch) "--audio-time-stretch" else "--no-audio-time-stretch",
            "--freetype-rel-fontsize=${prefs.subtitleSize}",
            "--http-reconnect",
            "--network-caching=1500",
        )
        if (prefs.skipLoopFilter) opts += "--avcodec-skiploopfilter=4"
        libVLC = LibVLC(this, opts)
        player = MediaPlayer(libVLC)
        player.setEventListener(this)
        if (prefs.defaultSpeedLock) player.rate = prefs.lastSpeed

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (locked) toast("Screen locked — tap the lock to unlock") else finish()
            }
        })

        playIndex(index)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        savePosition()
        readIntent(intent)
        playIndex(index)
    }

    private fun readIntent(i: Intent) {
        @Suppress("DEPRECATION")
        val uris: ArrayList<Uri>? = i.getParcelableArrayListExtra(EXTRA_URIS)
        if (!uris.isNullOrEmpty()) {
            playlist = uris
            titles = i.getStringArrayListExtra(EXTRA_TITLES) ?: uris.map { it.lastPathSegment ?: "" }
            index = i.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, uris.size - 1)
        } else {
            val data = i.data ?: run { finish(); return }
            playlist = listOf(data)
            titles = listOf(CollageStore.displayName(this, data) ?: data.lastPathSegment ?: data.toString())
            index = 0
        }
    }

    // ------------------------------------------------------------------ views

    private fun bindViews() {
        videoLayout = findViewById(R.id.videoLayout)
        controls = findViewById(R.id.controls)
        centerInfo = findViewById(R.id.centerInfo)
        buffering = findViewById(R.id.buffering)
        seek = findViewById(R.id.seek)
        timeCur = findViewById(R.id.timeCur)
        timeTotal = findViewById(R.id.timeTotal)
        btnPlay = findViewById(R.id.btnPlay)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnRepeat = findViewById(R.id.btnRepeat)
        btnUnlock = findViewById(R.id.btnUnlock)
        titleView = findViewById(R.id.title)

        ViewCompat.setOnApplyWindowInsetsListener(controls) { v, insets ->
            val i = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnPlay.setOnClickListener { togglePlay() }
        findViewById<View>(R.id.btnRew).setOnClickListener { seekBy(-prefs.doubleTapSeekSec * 1000L) }
        findViewById<View>(R.id.btnFf).setOnClickListener { seekBy(prefs.doubleTapSeekSec * 1000L) }
        findViewById<View>(R.id.btnPrev).setOnClickListener { previous() }
        findViewById<View>(R.id.btnNext).setOnClickListener { next(userAction = true) }
        findViewById<View>(R.id.btnAudio).setOnClickListener { showAudioDialog() }
        findViewById<View>(R.id.btnSubs).setOnClickListener { showSubtitleDialog() }
        findViewById<View>(R.id.btnMore).setOnClickListener { showMoreDialog() }
        findViewById<View>(R.id.btnAspect).setOnClickListener { cycleScale() }
        findViewById<View>(R.id.btnRotate).setOnClickListener { cycleOrientation() }
        findViewById<View>(R.id.btnLock).setOnClickListener { setLocked(true) }
        btnUnlock.setOnClickListener { setLocked(false) }
        btnRepeat.setOnClickListener { cycleRepeat() }
        btnSpeed.setOnClickListener { showSpeedDialog() }
        val pip = findViewById<View>(R.id.btnPip)
        if (Build.VERSION.SDK_INT >= 26 &&
            packageManager.hasSystemFeature("android.software.picture_in_picture")) {
            pip.setOnClickListener { enterPip() }
        } else pip.visibility = View.GONE
        updateRepeatButton()

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    val t = (player.length * p / 1000f).toLong()
                    timeCur.text = Format.time(t)
                    showCenter(Format.time(t) + " / " + Format.time(player.length))
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                userSeeking = true
                handler.removeCallbacks(hideControls)
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                player.setPosition(sb.progress / 1000f, false)
                scheduleHide()
            }
        })

        setupGestures(findViewById(R.id.gestureArea))
    }

    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun setControlsVisible(visible: Boolean) {
        handler.removeCallbacks(hideControls)
        if (locked) {
            controls.visibility = View.GONE
            btnUnlock.visibility = if (visible) View.VISIBLE else View.GONE
            if (visible) handler.postDelayed({ btnUnlock.visibility = View.GONE }, 3000)
            return
        }
        controls.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) scheduleHide() else hideSystemBars()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, 4000)
    }

    private fun showCenter(text: String) {
        centerInfo.text = text
        centerInfo.visibility = View.VISIBLE
        handler.removeCallbacks(hideCenter)
        handler.postDelayed(hideCenter, 900)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ playback

    private fun playIndex(i: Int) {
        if (playlist.isEmpty()) return
        index = i.coerceIn(0, playlist.size - 1)
        val uri = playlist[index]
        titleView.text = titles.getOrNull(index) ?: uri.lastPathSegment
        abA = -1; abB = -1

        val media = try {
            pfd?.close()
            pfd = null
            if (uri.scheme == "content") {
                val fd = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("cannot open")
                pfd = fd
                Media(libVLC, fd.fileDescriptor)
            } else {
                Media(libVLC, uri)
            }
        } catch (t: Throwable) {
            toast("Cannot open: ${t.message}")
            return
        }
        when (prefs.hwDecoding) {
            "on" -> media.setHWDecoderEnabled(true, true)
            "off" -> media.setHWDecoderEnabled(false, false)
            else -> media.setHWDecoderEnabled(true, false)
        }
        if (prefs.resumePlayback) {
            val pos = prefs.getPosition(uri.toString())
            if (pos > 5000) {
                media.addOption(":start-time=${pos / 1000.0}")
                showCenter("Resuming at ${Format.time(pos)}")
            }
        }
        player.media = media
        media.release()
        player.play()
        equalizer?.let { player.setEqualizer(it) }
        setControlsVisible(true)
    }

    private fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
        scheduleHide()
    }

    private fun seekBy(ms: Long) {
        if (!player.isSeekable) return
        val t = (player.time + ms).coerceIn(0, maxOf(0, player.length))
        player.setTime(t, false)
        showCenter((if (ms > 0) "+" else "−") + "${abs(ms) / 1000}s  ${Format.time(t)}")
    }

    private fun next(userAction: Boolean) {
        when {
            index < playlist.size - 1 -> { savePosition(); playIndex(index + 1) }
            repeat == Repeat.ALL || userAction && playlist.size > 1 -> { savePosition(); playIndex(0) }
            else -> finish()
        }
    }

    private fun previous() {
        if (player.time > 5000 || index == 0) player.setTime(0, false)
        else { savePosition(); playIndex(index - 1) }
    }

    private fun savePosition() {
        if (!this::player.isInitialized || playlist.isEmpty()) return
        val key = playlist[index].toString()
        val len = player.length
        val t = player.time
        if (len > 0 && t > 5000 && t < len - 5000) prefs.savePosition(key, t)
        else prefs.clearPosition(key)
    }

    override fun onEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                btnPlay.setIconResource(R.drawable.ic_pause)
                buffering.visibility = View.GONE
                updatePipParams()
            }
            MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped ->
                btnPlay.setIconResource(R.drawable.ic_play)
            MediaPlayer.Event.Buffering ->
                buffering.visibility = if (event.buffering < 100f) View.VISIBLE else View.GONE
            MediaPlayer.Event.LengthChanged -> timeTotal.text = Format.time(event.lengthChanged)
            MediaPlayer.Event.TimeChanged -> {
                val t = event.timeChanged
                if (abB > 0 && t >= abB) player.setTime(abA, false)
                if (!userSeeking) {
                    timeCur.text = Format.time(t)
                    val len = player.length
                    if (len > 0) seek.progress = (t * 1000 / len).toInt()
                }
            }
            MediaPlayer.Event.EndReached -> {
                prefs.clearPosition(playlist[index].toString())
                if (repeat == Repeat.ONE) handler.post { playIndex(index) }
                else handler.post { next(userAction = false) }
            }
            MediaPlayer.Event.EncounteredError -> {
                toast("Playback error")
                handler.post { if (playlist.size > 1) next(userAction = false) }
            }
            MediaPlayer.Event.Vout -> if (event.voutCount > 0) updatePipParams()
        }
    }

    // ------------------------------------------------------------------ gestures

    private fun setupGestures(area: View) {
        var mode = 0 // 0 none, 1 seek, 2 volume, 3 brightness
        var seekStartTime = 0L
        var seekTarget = 0L
        var volAcc = 0f
        var brightAcc = 0f
        var scaleAcc = 1f

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                mode = 0
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                setControlsVisible(if (locked) true else controls.visibility != View.VISIBLE)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                val w = area.width
                when {
                    e.x < w / 3f -> seekBy(-prefs.doubleTapSeekSec * 1000L)
                    e.x > w * 2 / 3f -> seekBy(prefs.doubleTapSeekSec * 1000L)
                    else -> togglePlay()
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (locked || !prefs.gestures || e1 == null) return false
                if (mode == 0) {
                    val tx = abs(e2.x - e1.x)
                    val ty = abs(e2.y - e1.y)
                    mode = when {
                        tx > ty -> if (player.isSeekable) 1 else -1
                        e1.x < area.width / 2f -> 3
                        else -> 2
                    }
                    seekStartTime = player.time
                    volAcc = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                    brightAcc = window.attributes.screenBrightness.let { if (it < 0) 0.5f else it }
                }
                when (mode) {
                    1 -> {
                        val delta = ((e2.x - e1.x) / area.width * 90_000).toLong() // full swipe = 90s
                        seekTarget = (seekStartTime + delta).coerceIn(0, maxOf(0, player.length))
                        showCenter((if (delta >= 0) "+" else "−") + Format.time(abs(delta)) +
                            "\n" + Format.time(seekTarget))
                    }
                    2 -> {
                        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        volAcc = (volAcc + dy / area.height * max * 1.5f).coerceIn(0f, max.toFloat())
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volAcc.roundToInt(), 0)
                        showCenter("Volume ${(volAcc / max * 100).roundToInt()}%")
                    }
                    3 -> {
                        brightAcc = (brightAcc + dy / area.height * 1.5f).coerceIn(0.01f, 1f)
                        window.attributes = window.attributes.apply { screenBrightness = brightAcc }
                        showCenter("Brightness ${(brightAcc * 100).roundToInt()}%")
                    }
                }
                return true
            }
        })
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                scaleAcc = 1f
                return !locked
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                scaleAcc *= d.scaleFactor
                return true
            }

            override fun onScaleEnd(d: ScaleGestureDetector) {
                if (scaleAcc > 1.1f) setScale(SCALES.indexOfFirst { it.first == MediaPlayer.ScaleType.SURFACE_FILL })
                else if (scaleAcc < 0.9f) setScale(0)
            }
        })
        area.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            if (!scaleDetector.isInProgress) detector.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP && mode == 1) {
                player.setTime(seekTarget, false)
                mode = 0
            }
            true
        }
    }

    // ------------------------------------------------------------------ buttons

    private fun setScale(i: Int) {
        scaleIndex = i.coerceIn(0, SCALES.size - 1)
        player.videoScale = SCALES[scaleIndex].first
        showCenter(SCALES[scaleIndex].second)
    }

    private fun cycleScale() {
        setScale((scaleIndex + 1) % SCALES.size)
        scheduleHide()
    }

    private fun cycleOrientation() {
        orientationMode = (orientationMode + 1) % 3
        requestedOrientation = when (orientationMode) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
        showCenter(arrayOf("Auto-rotate", "Landscape", "Portrait")[orientationMode])
    }

    private fun setLocked(lock: Boolean) {
        locked = lock
        if (lock) {
            controls.visibility = View.GONE
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            showCenter("Locked")
            setControlsVisible(true)
        } else {
            btnUnlock.visibility = View.GONE
            orientationMode -= 1
            cycleOrientation()
            setControlsVisible(true)
        }
    }

    private fun cycleRepeat() {
        repeat = Repeat.values()[(repeat.ordinal + 1) % 3]
        updateRepeatButton()
        showCenter(when (repeat) {
            Repeat.OFF -> "Repeat off"
            Repeat.ONE -> "Repeat this video"
            Repeat.ALL -> "Repeat all"
        })
        scheduleHide()
    }

    private fun updateRepeatButton() {
        btnRepeat.alpha = if (repeat == Repeat.OFF) 0.45f else 1f
        btnRepeat.text = if (repeat == Repeat.ONE) "1" else ""
    }

    private fun setSpeed(s: Float) {
        player.rate = s
        prefs.lastSpeed = s
        btnSpeed.text = String.format(Locale.US, "%.2fx", s)
    }

    private fun showSpeedDialog() {
        val names = SPEEDS.map { String.format(Locale.US, "%.2fx", it) }.toTypedArray()
        val cur = SPEEDS.indexOfFirst { abs(it - player.rate) < 0.01f }
        MaterialAlertDialogBuilder(this)
            .setTitle("Playback speed")
            .setSingleChoiceItems(names, cur) { d, w -> setSpeed(SPEEDS[w]); d.dismiss() }
            .setNeutralButton("Custom…") { _, _ ->
                sliderDialog("Playback speed", 0.25f, 4f, 0.05f, player.rate, { String.format(Locale.US, "%.2fx", it) }) { setSpeed(it) }
            }
            .show()
    }

    private fun showAudioDialog() {
        val tracks = player.audioTracks ?: emptyArray()
        if (tracks.isEmpty()) { toast("No audio tracks"); return }
        val cur = tracks.indexOfFirst { it.id == player.audioTrack }
        MaterialAlertDialogBuilder(this)
            .setTitle("Audio track")
            .setSingleChoiceItems(tracks.map { it.name }.toTypedArray(), cur) { d, w ->
                player.setAudioTrack(tracks[w].id); d.dismiss()
            }
            .setNeutralButton("Audio delay…") { _, _ -> delayDialog(true) }
            .show()
    }

    private fun showSubtitleDialog() {
        val tracks = player.spuTracks ?: emptyArray()
        val names = tracks.map { it.name }.toMutableList()
        val cur = tracks.indexOfFirst { it.id == player.spuTrack }
        val b = MaterialAlertDialogBuilder(this).setTitle("Subtitles")
        if (names.isEmpty()) b.setMessage("No subtitle tracks in this video.")
        else b.setSingleChoiceItems(names.toTypedArray(), cur) { d, w -> player.setSpuTrack(tracks[w].id); d.dismiss() }
        b.setPositiveButton("Load file…") { _, _ -> pickSubtitle.launch(arrayOf("*/*")) }
        b.setNeutralButton("Delay…") { _, _ -> delayDialog(false) }
        b.show()
    }

    /** libVLC can't open content:// slaves, so copy the subtitle into the cache first. */
    private fun loadSubtitle(uri: Uri) {
        try {
            val name = CollageStore.displayName(this, uri) ?: "sub.srt"
            val f = File(cacheDir, "subs/$name").apply { parentFile?.mkdirs() }
            contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
            if (player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(f), true)) toast("Subtitle loaded")
            else toast("Could not load subtitle")
        } catch (t: Throwable) {
            toast("Could not load subtitle")
        }
    }

    private fun delayDialog(audio: Boolean) {
        val curMs = (if (audio) player.audioDelay else player.spuDelay) / 1000f
        sliderDialog(if (audio) "Audio delay" else "Subtitle delay", -5000f, 5000f, 50f, curMs,
            { "${it.toInt()} ms" }) {
            val us = (it * 1000).toLong()
            if (audio) player.setAudioDelay(us) else player.setSpuDelay(us)
        }
    }

    private fun sliderDialog(
        title: String, from: Float, to: Float, step: Float, value: Float,
        label: (Float) -> String, onSet: (Float) -> Unit,
    ) {
        val text = TextView(this).apply { textSize = 18f; setPadding(0, 0, 0, 8) }
        val slider = Slider(this).apply {
            valueFrom = from; valueTo = to; stepSize = step
            this.value = ((value / step).roundToInt() * step).coerceIn(from, to)
            setLabelFormatter { label(it) }
            addOnChangeListener { _, v, _ -> text.text = label(v); onSet(v) }
        }
        text.text = label(slider.value)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(text); addView(slider)
        }
        MaterialAlertDialogBuilder(this).setTitle(title).setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton("Reset") { _, _ -> onSet(if (from < 0) 0f else 1f) }
            .show()
    }

    private fun showMoreDialog() {
        val items = mutableListOf("Equalizer", "Sleep timer", "A-B repeat", "Jump to time", "Video info")
        val chapters = player.getChapters(-1)
        if (!chapters.isNullOrEmpty()) items.add(0, "Chapters")
        val uri = playlist.getOrNull(index)
        if (uri?.scheme == "content") items += "Copy to Collage Mode folder"
        if (playlist.size > 1) items += "Playlist"
        MaterialAlertDialogBuilder(this)
            .setItems(items.toTypedArray()) { _, w ->
                when (items[w]) {
                    "Chapters" -> MaterialAlertDialogBuilder(this).setTitle("Chapters")
                        .setItems(chapters!!.mapIndexed { i, c -> c.name ?: "Chapter ${i + 1}" }.toTypedArray()) { _, c ->
                            player.chapter = c
                        }.show()
                    "Equalizer" -> showEqualizer()
                    "Sleep timer" -> showSleepTimer()
                    "A-B repeat" -> abRepeat()
                    "Jump to time" -> jumpToTime()
                    "Video info" -> showInfo()
                    "Playlist" -> MaterialAlertDialogBuilder(this).setTitle("Playlist")
                        .setSingleChoiceItems(titles.toTypedArray(), index) { d, i ->
                            savePosition(); playIndex(i); d.dismiss()
                        }.show()
                    "Copy to Collage Mode folder" -> CollageStore.import(this, listOf(uri!!), false, 720,
                        object : CollageStore.ImportCallback {
                            override fun onProgress(index: Int, total: Int, name: String, percent: Int, optimizing: Boolean) {}
                            override fun onFinished(copied: Int, failed: Int) =
                                toast(if (copied > 0) "Copied to Collage Mode" else "Copy failed")
                        })
                }
            }.show()
    }

    private fun showEqualizer() {
        val n = MediaPlayer.Equalizer.getPresetCount()
        val names = arrayOf("Off") + Array(n) { MediaPlayer.Equalizer.getPresetName(it) }
        MaterialAlertDialogBuilder(this).setTitle("Equalizer")
            .setItems(names) { _, w ->
                equalizer = if (w == 0) null else MediaPlayer.Equalizer.createFromPreset(w - 1)
                player.setEqualizer(equalizer)
                toast("Equalizer: ${names[w]}")
            }.show()
    }

    private fun showSleepTimer() {
        val mins = intArrayOf(0, 5, 10, 15, 30, 45, 60, 90, 120)
        val names = mins.map { if (it == 0) "Off" else "$it minutes" }.toTypedArray()
        val remaining = if (sleepAt > 0) " (${(sleepAt - System.currentTimeMillis()) / 60000 + 1} min left)" else ""
        MaterialAlertDialogBuilder(this).setTitle("Sleep timer$remaining")
            .setItems(names) { _, w ->
                handler.removeCallbacks(sleepRunnable)
                sleepAt = 0
                if (mins[w] > 0) {
                    sleepAt = System.currentTimeMillis() + mins[w] * 60_000L
                    handler.postDelayed(sleepRunnable, mins[w] * 60_000L)
                    toast("Stopping in ${mins[w]} minutes")
                }
            }.show()
    }

    private fun abRepeat() {
        when {
            abA < 0 -> { abA = player.time; toast("Point A set at ${Format.time(abA)} — open again to set B") }
            abB < 0 -> {
                val t = player.time
                if (t <= abA) { toast("B must be after A"); return }
                abB = t
                toast("Looping ${Format.time(abA)} → ${Format.time(abB)}")
            }
            else -> { abA = -1; abB = -1; toast("A-B repeat off") }
        }
    }

    private fun jumpToTime() {
        val input = EditText(this).apply { hint = "mm:ss or hh:mm:ss"; setSingleLine() }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        MaterialAlertDialogBuilder(this).setTitle("Jump to time").setView(box)
            .setPositiveButton("Go") { _, _ ->
                val parts = input.text.toString().split(":").mapNotNull { it.trim().toLongOrNull() }
                val sec = parts.fold(0L) { acc, p -> acc * 60 + p }
                if (parts.isNotEmpty()) player.setTime(sec * 1000, false)
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showInfo() {
        val sb = StringBuilder()
        sb.append(titles.getOrNull(index)).append("\n\n")
        player.currentVideoTrack?.let { v ->
            sb.append("Video: ${v.codec}  ${v.width}×${v.height}")
            if (v.frameRateDen > 0) sb.append(String.format(Locale.US, "  %.2f fps", v.frameRateNum.toFloat() / v.frameRateDen))
            sb.append("\n")
        }
        sb.append("Audio tracks: ${maxOf(0, player.audioTracksCount - 1)}\n")
        sb.append("Subtitle tracks: ${maxOf(0, player.spuTracksCount - 1)}\n")
        sb.append("Length: ${Format.time(player.length)}\n")
        sb.append("Decoding: ${prefs.hwDecoding}\n")
        sb.append("Engine: libVLC ${LibVLC.version()}")
        MaterialAlertDialogBuilder(this).setTitle("Info").setMessage(sb).setPositiveButton(android.R.string.ok, null).show()
    }

    // ------------------------------------------------------------------ PiP

    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val v = player.currentVideoTrack
        var ratio = if (v != null && v.width > 0 && v.height > 0) {
            val sar = if (v.sarDen > 0 && v.sarNum > 0) v.sarNum.toFloat() / v.sarDen else 1f
            Rational((v.width * sar).toInt(), v.height)
        } else Rational(16, 9)
        // Android rejects ratios outside ~[1/2.39, 2.39]
        if (ratio.toFloat() > 2.39f) ratio = Rational(239, 100)
        if (ratio.toFloat() < 1 / 2.39f) ratio = Rational(100, 239)
        val b = PictureInPictureParams.Builder().setAspectRatio(ratio)
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(prefs.autoPip && player.isPlaying)
        return b.build()
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= 26) try { pipParams()?.let { setPictureInPictureParams(it) } } catch (_: Throwable) {}
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26) try {
            pipParams()?.let { enterPictureInPictureMode(it) }
        } catch (_: Throwable) {
            toast("Picture-in-picture not available")
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (prefs.autoPip && player.isPlaying && Build.VERSION.SDK_INT in 26..30) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        if (isInPip) {
            handler.removeCallbacks(hideControls)
            controls.visibility = View.GONE
            centerInfo.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onStart() {
        super.onStart()
        player.attachViews(videoLayout, null, true, false)
        player.videoScale = SCALES[scaleIndex].first
        if (wasPlayingBeforeStop && player.hasMedia()) player.play()
    }

    override fun onStop() {
        super.onStop()
        wasPlayingBeforeStop = player.isPlaying
        savePosition()
        player.pause()
        player.detachViews()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player.setEventListener(null)
        player.stop()
        player.release()
        libVLC.release()
        pfd?.close()
    }
}
