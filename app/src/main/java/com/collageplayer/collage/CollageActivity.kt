package com.collageplayer.collage

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.collageplayer.R
import com.collageplayer.data.Format
import com.collageplayer.data.MediaItem
import com.collageplayer.data.MediaRepository
import com.collageplayer.data.Prefs
import com.collageplayer.player.PlayerActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Collage mode: up to 3×3 videos at once, all picked at random from the
 * "Collage Mode" folder. Every panel is muted by default.
 */
class CollageActivity : AppCompatActivity(), CollagePanel.Listener {

    private lateinit var prefs: Prefs
    private lateinit var grid: CollageGrid
    private lateinit var topBar: View
    private lateinit var panelControls: View
    private lateinit var panelName: TextView
    private lateinit var panelTime: TextView
    private lateinit var panelDuration: TextView
    private lateinit var panelSeek: SeekBar
    private lateinit var panelVolume: Slider
    private lateinit var btnPanelPlay: MaterialButton
    private lateinit var btnPanelMute: MaterialButton
    private lateinit var btnPanelFit: MaterialButton
    private lateinit var btnPanelLoop: MaterialButton
    private lateinit var btnPlayAll: MaterialButton
    private lateinit var btnGridLayout: MaterialButton

    private val handler = Handler(Looper.getMainLooper())
    private val panels = ArrayList<CollagePanel>()
    private var library: List<MediaItem> = emptyList()
    private val bag = ArrayDeque<MediaItem>()
    private var selected: CollagePanel? = null
    private var allPaused = false
    private var resumeOnStart = false
    private var lagWarned = false
    private var budgetWarned = false
    private var seeking = false

    private val hideTopBar = Runnable { if (selected == null) setTopBarVisible(false) }
    private val deselectLater = Runnable { select(null) }
    private val ticker = object : Runnable {
        override fun run() {
            updatePanelProgress()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_collage)
        prefs = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        grid = findViewById(R.id.grid)
        topBar = findViewById(R.id.topBar)
        panelControls = findViewById(R.id.panelControls)
        panelName = findViewById(R.id.panelName)
        panelTime = findViewById(R.id.panelTime)
        panelDuration = findViewById(R.id.panelDuration)
        panelSeek = findViewById(R.id.panelSeek)
        panelVolume = findViewById(R.id.panelVolume)
        btnPanelPlay = findViewById(R.id.btnPanelPlay)
        btnPanelMute = findViewById(R.id.btnPanelMute)
        btnPanelFit = findViewById(R.id.btnPanelFit)
        btnPanelLoop = findViewById(R.id.btnPanelLoop)
        btnPlayAll = findViewById(R.id.btnPlayAll)
        btnGridLayout = findViewById(R.id.btnGridLayout)

        for (v in listOf(topBar, panelControls)) {
            ViewCompat.setOnApplyWindowInsetsListener(v) { view, insets ->
                val i = insets.getInsetsIgnoringVisibility(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                if (view === topBar) view.setPadding(i.left + dp(4), i.top + dp(4), i.right + dp(4), dp(20))
                else view.setPadding(i.left + dp(8), dp(6), i.right + dp(8), i.bottom + dp(6))
                insets
            }
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnGridLayout.setOnClickListener {
            CollageLayouts.showPicker(this, prefs.collageRows, prefs.collageCols) { r, c ->
                prefs.collageRows = r
                prefs.collageCols = c
                applyLayout()
            }
        }
        findViewById<View>(R.id.btnShuffleAll).setOnClickListener { shuffleAll() }
        btnPlayAll.setOnClickListener { setAllPlaying(allPaused) }
        findViewById<View>(R.id.btnMuteAll).setOnClickListener {
            panels.forEach { it.setMuted(true) }
            refreshPanelControls()
            toast("All panels muted")
        }

        setupPanelControls()

        grid.setOnClickListener { select(null); setTopBarVisible(topBar.visibility != View.VISIBLE) }

        Executors.newSingleThreadExecutor().execute {
            val items = MediaRepository.loadCollageVideos(this)
            runOnUiThread {
                findViewById<View>(R.id.loading).visibility = View.GONE
                if (items.isEmpty()) {
                    toast("The Collage Mode folder is empty")
                    finish()
                    return@runOnUiThread
                }
                library = items
                applyLayout()
                setTopBarVisible(true)
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    // ---------------------------------------------------------------- layout & shuffle

    /** Grows/shrinks the panel list to rows×cols, keeping videos already playing. */
    private fun applyLayout() {
        val rows = prefs.collageRows
        val cols = prefs.collageCols
        val n = rows * cols
        btnGridLayout.text = CollageLayouts.label(rows, cols)
        findViewById<TextView>(R.id.collageTitle).text = "${library.size} in folder"
        select(null)

        while (panels.size > n) {
            val p = panels.removeAt(panels.size - 1)
            p.release()
            grid.removeView(p)
        }
        grid.rows = rows
        grid.cols = cols
        val stagger = if (prefs.collageStagger) 150L else 0L
        var k = 0
        while (panels.size < n) {
            val p = CollagePanel(this, this)
            p.loop = !prefs.collageAutoNext
            p.lagMonitor = prefs.collageLagMonitor
            panels += p
            grid.addView(p)
            val item = nextFromBag()
            // Staggered start: codecs initialise one after another instead of 9 at once.
            handler.postDelayed({ if (p in panels) p.play(item, startPos(item), !allPaused) }, stagger * k++)
        }
        checkBudget(n)
    }

    private fun startPos(item: MediaItem): Long =
        if (prefs.collageRandomStart && item.durationMs > 10_000)
            Random.nextLong(0, (item.durationMs * 0.8).toLong()) else 0L

    /** Shuffle-bag: every video is used once before any repeats, and on-screen duplicates are avoided. */
    private fun nextFromBag(avoid: MediaItem? = null): MediaItem {
        val onScreen = panels.mapNotNull { it.item }.toSet()
        if (bag.isEmpty()) bag.addAll(library.shuffled())
        val pick = bag.firstOrNull { it !in onScreen && it != avoid }
            ?: library.filter { it !in onScreen && it != avoid }.randomOrNull()
            ?: library.filter { it != avoid }.randomOrNull()
            ?: library.first()
        bag.remove(pick)
        return pick
    }

    /** New random video in every panel, preferring ones that weren't just on screen. */
    private fun shuffleAll() {
        val old = panels.mapNotNull { it.item }.toSet()
        val pool = library.filter { it !in old }.shuffled() + library.filter { it in old }.shuffled()
        panels.forEachIndexed { i, p ->
            val item = pool.getOrElse(i) { library.random() }
            handler.postDelayed({ if (p in panels) p.play(item, startPos(item), !allPaused) },
                if (prefs.collageStagger) 100L * i else 0L)
        }
        bag.clear()
        bag.addAll(pool.drop(panels.size))
        refreshPanelControls()
    }

    private fun shufflePanel(p: CollagePanel) {
        val item = nextFromBag(avoid = p.item)
        p.play(item, startPos(item), true)
        refreshPanelControls()
    }

    private fun checkBudget(n: Int) {
        if (budgetWarned) return
        val hw = DecoderBudget.maxHardwareInstances()
        val low = DecoderBudget.isLowRam(this)
        if ((hw in 1 until n) || (low && n > 4)) {
            budgetWarned = true
            MaterialAlertDialogBuilder(this)
                .setTitle("Heads up")
                .setMessage(
                    (if (hw in 1 until n) "This phone reports $hw hardware video decoders; " +
                        "extra panels fall back to software decoding.\n\n" else "This is a low-memory phone.\n\n") +
                        "For smooth playback use videos added with \"Optimize\" (720p or 480p) " +
                        "or pick a smaller layout.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    // ---------------------------------------------------------------- panel callbacks

    override fun onPanelTap(panel: CollagePanel) {
        if (selected === panel) select(null) else select(panel)
    }

    override fun onPanelEnded(panel: CollagePanel) {
        if (!panel.loop) handler.post { shufflePanel(panel) }
    }

    override fun onPanelError(panel: CollagePanel) {
        val name = panel.item?.title ?: ""
        if (panel.consecutiveErrors <= 3 && library.size > 1) {
            toast("Can't play $name — picking another")
            handler.postDelayed({ shufflePanel(panel) }, 300)
        } else {
            toast("Can't play $name")
        }
    }

    override fun onPanelLag(panel: CollagePanel) {
        if (lagWarned) return
        lagWarned = true
        toast("Your phone is struggling. Try optimized (720p) videos or a smaller grid.")
    }

    override fun onPanelStateChanged(panel: CollagePanel) {
        if (panel === selected) refreshPanelControls()
    }

    // ---------------------------------------------------------------- selection UI

    private fun select(p: CollagePanel?) {
        selected?.selectedPanel = false
        selected = p
        p?.selectedPanel = true
        handler.removeCallbacks(deselectLater)
        if (p == null) {
            panelControls.visibility = View.GONE
            handler.removeCallbacks(ticker)
            scheduleTopBarHide()
        } else {
            panelControls.visibility = View.VISIBLE
            setTopBarVisible(true)
            refreshPanelControls()
            handler.removeCallbacks(ticker)
            handler.post(ticker)
            touchSelection()
        }
    }

    /** Keeps the controls open while the user interacts; closes them after 8s idle. */
    private fun touchSelection() {
        handler.removeCallbacks(deselectLater)
        handler.postDelayed(deselectLater, 8000)
    }

    private fun setTopBarVisible(v: Boolean) {
        topBar.visibility = if (v) View.VISIBLE else View.GONE
        if (v) scheduleTopBarHide() else hideSystemBars()
    }

    private fun scheduleTopBarHide() {
        handler.removeCallbacks(hideTopBar)
        handler.postDelayed(hideTopBar, 4000)
    }

    private fun setupPanelControls() {
        fun onSel(block: (CollagePanel) -> Unit) = View.OnClickListener {
            selected?.let { block(it); refreshPanelControls(); touchSelection() }
        }
        findViewById<View>(R.id.btnPanelClose).setOnClickListener { select(null) }
        btnPanelPlay.setOnClickListener(onSel { it.togglePlay() })
        findViewById<View>(R.id.btnPanelShuffle).setOnClickListener(onSel { shufflePanel(it) })
        findViewById<View>(R.id.btnPanelChange).setOnClickListener(onSel { chooseVideo(it) })
        btnPanelFit.setOnClickListener(onSel {
            it.fit = CollagePanel.Fit.values()[(it.fit.ordinal + 1) % CollagePanel.Fit.values().size]
        })
        findViewById<View>(R.id.btnPanelReset).setOnClickListener(onSel { it.resetView() })
        btnPanelLoop.setOnClickListener(onSel {
            it.loop = !it.loop
            toast(if (it.loop) "Loop this video" else "Shuffle to another video when it ends")
        })
        findViewById<View>(R.id.btnPanelFull).setOnClickListener(onSel { p ->
            val item = p.item ?: return@onSel
            p.setPlaying(false)
            PlayerActivity.start(this, library.map { it.uri }, library.map { it.title },
                library.indexOf(item).coerceAtLeast(0))
        })
        btnPanelMute.setOnClickListener(onSel { p ->
            p.setMuted(!p.muted)
            if (!p.muted && p.volume == 0f) p.setVolumeLevel(1f)
        })
        panelVolume.addOnChangeListener { _, value, fromUser ->
            if (fromUser) selected?.let {
                it.setVolumeLevel(value / 100f)
                btnPanelMute.setIconResource(if (it.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
                touchSelection()
            }
        }
        panelSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val d = selected?.player?.duration ?: 0
                    if (d > 0) panelTime.text = Format.time(d * progress / 1000)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) { seeking = true; handler.removeCallbacks(deselectLater) }
            override fun onStopTrackingTouch(sb: SeekBar) {
                seeking = false
                selected?.seekToFraction(sb.progress / 1000f)
                touchSelection()
            }
        })
    }

    private fun chooseVideo(p: CollagePanel) {
        val names = arrayOf("🔀  Random") + library.map { it.title }.toTypedArray()
        val cur = library.indexOf(p.item).let { if (it >= 0) it + 1 else -1 }
        handler.removeCallbacks(deselectLater)
        MaterialAlertDialogBuilder(this)
            .setTitle("Play in this panel")
            .setSingleChoiceItems(names, cur) { d, w ->
                if (w == 0) shufflePanel(p)
                else library[w - 1].let { p.play(it, 0, true) }
                refreshPanelControls()
                touchSelection()
                d.dismiss()
            }
            .setOnCancelListener { touchSelection() }
            .show()
    }

    private fun refreshPanelControls() {
        val p = selected ?: return
        panelName.text = p.item?.title ?: ""
        btnPanelPlay.setIconResource(if (p.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        btnPanelMute.setIconResource(if (p.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
        panelVolume.value = if (p.muted) 0f else (p.volume * 100).toInt().toFloat()
        btnPanelFit.text = p.fit.label
        btnPanelLoop.alpha = if (p.loop) 1f else 0.5f
        updatePanelProgress()
    }

    private fun updatePanelProgress() {
        val p = selected?.player ?: return
        if (seeking) return
        val d = p.duration
        val t = p.currentPosition
        panelTime.text = Format.time(t)
        panelDuration.text = if (d > 0) Format.time(d) else "--:--"
        if (d > 0) panelSeek.progress = (t * 1000 / d).toInt()
    }

    private fun setAllPlaying(play: Boolean) {
        allPaused = !play
        panels.forEach { it.setPlaying(play) }
        btnPlayAll.setIconResource(if (play) R.drawable.ic_pause else R.drawable.ic_play)
        refreshPanelControls()
        scheduleTopBarHide()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onStart() {
        super.onStart()
        if (resumeOnStart) {
            setAllPlaying(true)
            resumeOnStart = false
        }
    }

    override fun onStop() {
        super.onStop()
        if (!allPaused && panels.isNotEmpty()) {
            panels.forEach { it.setPlaying(false) }
            resumeOnStart = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        panels.forEach { it.release() }
        panels.clear()
    }
}
