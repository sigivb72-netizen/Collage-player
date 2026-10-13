package com.collageplayer.collage

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.collageplayer.R
import com.collageplayer.data.MediaItem
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import androidx.media3.common.MediaItem as M3Item

/**
 * One cell of the collage: a TextureView driven by its own lightweight ExoPlayer.
 *
 * Anti-lag choices made here:
 *  - hardware (MediaCodec) decoding, falling back to another decoder if the HW one is busy
 *  - the audio track is fully *disabled* while muted, so muted panels don't decode audio at all
 *  - tiny buffers (local files don't need more), so 9 players don't eat RAM
 *  - no display refresh-rate switching, no audio focus fights
 *  - pan/zoom/fit are a matrix on the TextureView: free, no re-decoding
 */
@SuppressLint("ViewConstructor")
class CollagePanel(context: Context, val listener: Listener) : FrameLayout(context) {

    interface Listener {
        fun onPanelTap(panel: CollagePanel)
        fun onPanelEnded(panel: CollagePanel)
        fun onPanelError(panel: CollagePanel)
        fun onPanelLag(panel: CollagePanel)
        fun onPanelStateChanged(panel: CollagePanel)
    }

    enum class Fit(val label: String) { FIT("Fit"), FILL("Fill"), STRETCH("Stretch"), ORIGINAL("Original") }

    private val texture = TextureView(context)
    private val border = View(context)
    private val nameLabel = TextView(context)
    private val lagBadge = TextView(context)
    private val muteBadge = TextView(context)
    private val handler = Handler(Looper.getMainLooper())

    var player: ExoPlayer? = null
        private set
    var item: MediaItem? = null
        private set

    var fit = Fit.FIT
        set(v) { field = v; applyTransform() }
    var muted = true
        private set
    var volume = 1f
        private set
    var loop = true
        set(v) { field = v; player?.repeatMode = if (v) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
    var lagMonitor = true
    var consecutiveErrors = 0

    private var videoW = 0f
    private var videoH = 0f
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private val matrix = Matrix()

    init {
        setBackgroundColor(Color.BLACK)
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        nameLabel.apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(6), dp(2), dp(6), dp(2))
            maxLines = 1
            visibility = GONE
        }
        addView(nameLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))

        lagBadge.apply {
            text = "⚠ lag"
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setBackgroundColor(0xFFFFC107.toInt())
            setPadding(dp(4), dp(1), dp(4), dp(1))
            visibility = GONE
        }
        addView(lagBadge, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
            .apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })

        muteBadge.apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(4), dp(1), dp(4), dp(1))
            visibility = GONE
        }
        addView(muteBadge, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
            .apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })

        border.setBackgroundResource(R.drawable.bg_panel_selected)
        border.visibility = GONE
        addView(border, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        setupGestures()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    var selectedPanel = false
        set(v) {
            field = v
            border.visibility = if (v) VISIBLE else GONE
            nameLabel.visibility = if (v) VISIBLE else GONE
        }

    // ---------------------------------------------------------------- player

    @OptIn(UnstableApi::class)
    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1500, 4000, 300, 800)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val selector = DefaultTrackSelector(context)
        val p = ExoPlayer.Builder(context, renderers)
            .setLoadControl(load)
            .setTrackSelector(selector)
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
            .build()
        p.setVideoTextureView(texture)
        p.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        p.volume = volume
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoW = videoSize.width * videoSize.pixelWidthHeightRatio
                    videoH = videoSize.height.toFloat()
                    applyTransform()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) consecutiveErrors = 0
                if (state == Player.STATE_ENDED) listener.onPanelEnded(this@CollagePanel)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = listener.onPanelStateChanged(this@CollagePanel)

            override fun onPlayerError(error: PlaybackException) {
                consecutiveErrors++
                listener.onPanelError(this@CollagePanel)
            }
        })
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                // ExoPlayer reports in batches of ~50 dropped frames. 50 in under 10s = visible stutter.
                if (lagMonitor && droppedFrames >= 20 && elapsedMs in 1..10_000) {
                    lagBadge.visibility = VISIBLE
                    handler.removeCallbacks(hideLag)
                    handler.postDelayed(hideLag, 5000)
                    listener.onPanelLag(this@CollagePanel)
                }
            }
        })
        applyMuteToPlayer(p)
        player = p
        return p
    }

    private val hideLag = Runnable { lagBadge.visibility = GONE }

    fun play(newItem: MediaItem, startMs: Long, playWhenReady: Boolean = true) {
        item = newItem
        nameLabel.text = newItem.title
        videoW = 0f; videoH = 0f
        resetView()
        val p = ensurePlayer()
        p.setMediaItem(M3Item.fromUri(newItem.uri), startMs)
        p.prepare()
        p.playWhenReady = playWhenReady
    }

    val isPlaying: Boolean get() = player?.isPlaying == true || player?.playWhenReady == true

    fun setPlaying(play: Boolean) {
        player?.playWhenReady = play
        listener.onPanelStateChanged(this)
    }

    fun togglePlay() = setPlaying(!(player?.playWhenReady ?: false))

    fun setMuted(m: Boolean) {
        muted = m
        player?.let { applyMuteToPlayer(it) }
        updateMuteBadge()
    }

    fun setVolumeLevel(v: Float) {
        volume = v.coerceIn(0f, 1f)
        player?.volume = volume
        if (volume > 0f && muted) setMuted(false) else updateMuteBadge()
        if (volume == 0f && !muted) setMuted(true)
    }

    private fun updateMuteBadge() {
        if (muted) {
            muteBadge.visibility = GONE
        } else {
            muteBadge.text = "🔊 ${(volume * 100).toInt()}%"
            muteBadge.visibility = VISIBLE
        }
    }

    /** Muted = audio track disabled entirely: zero audio decoding cost. */
    private fun applyMuteToPlayer(p: ExoPlayer) {
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, muted)
            .build()
        p.volume = if (muted) 0f else volume
    }

    fun seekToFraction(f: Float) {
        val p = player ?: return
        val d = p.duration
        if (d > 0) p.seekTo((d * f).toLong())
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    // ---------------------------------------------------------------- view transform

    fun resetView() {
        zoom = 1f; panX = 0f; panY = 0f
        applyTransform()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyTransform()
    }

    /**
     * The TextureView stretches the decoded frame over the whole panel; this matrix
     * re-shapes it into the right aspect ratio, applies the fit mode, zoom and pan.
     */
    private fun applyTransform() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val vw = if (videoW > 0) videoW else w
        val vh = if (videoH > 0) videoH else h
        val (cw, ch) = when (fit) {
            Fit.FIT -> min(w / vw, h / vh).let { vw * it to vh * it }
            Fit.FILL -> max(w / vw, h / vh).let { vw * it to vh * it }
            Fit.STRETCH -> w to h
            Fit.ORIGINAL -> vw to vh
        }
        val dw = cw * zoom
        val dh = ch * zoom
        // keep at least a sliver of the video on screen
        val maxPanX = (dw + w) / 2 - dp(48)
        val maxPanY = (dh + h) / 2 - dp(48)
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)
        matrix.setScale(dw / w, dh / h)
        matrix.postTranslate((w - dw) / 2 + panX, (h - dh) / 2 + panY)
        texture.setTransform(matrix)
        texture.invalidate()
    }

    // ---------------------------------------------------------------- gestures

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val tap = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                listener.onPanelTap(this@CollagePanel)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                togglePlay()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (e2.pointerCount > 1) return false
                panX -= dx
                panY -= dy
                applyTransform()
                return true
            }
        })
        tap.setIsLongpressEnabled(false) // a slow finger-up must still count as a tap
        val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val newZoom = (zoom * d.scaleFactor).coerceIn(0.5f, 8f)
                val k = newZoom / zoom
                // zoom around the fingers
                val fx = d.focusX - width / 2f
                val fy = d.focusY - height / 2f
                panX = fx + (panX - fx) * k
                panY = fy + (panY - fy) * k
                zoom = newZoom
                applyTransform()
                return true
            }
        })
        setOnTouchListener { _, ev ->
            parent?.requestDisallowInterceptTouchEvent(true)
            pinch.onTouchEvent(ev)
            if (!pinch.isInProgress) tap.onTouchEvent(ev)
            true
        }
    }

    val isZoomedOrPanned: Boolean get() = zoom != 1f || abs(panX) > 1 || abs(panY) > 1
}
