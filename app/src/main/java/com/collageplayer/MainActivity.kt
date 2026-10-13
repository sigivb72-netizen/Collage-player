package com.collageplayer

import android.Manifest
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.collageplayer.collage.CollageActivity
import com.collageplayer.collage.CollageLayouts
import com.collageplayer.data.CollageStore
import com.collageplayer.data.Folder
import com.collageplayer.data.MediaItem
import com.collageplayer.data.MediaRepository
import com.collageplayer.data.Prefs
import com.collageplayer.player.PlayerActivity
import com.collageplayer.ui.MediaAdapter
import com.collageplayer.ui.Row
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private enum class Tab { VIDEOS, FOLDERS, AUDIO, COLLAGE }

    private lateinit var prefs: Prefs
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var permissionPanel: View
    private lateinit var collageHeader: View
    private lateinit var collageInfo: TextView
    private lateinit var btnLayout: MaterialButton
    private lateinit var toolbar: MaterialToolbar
    private val adapter = MediaAdapter(::onRowClick, ::onRowLongClick)
    private val bg = Executors.newSingleThreadExecutor()

    private var tab = Tab.VIDEOS
    private var openFolder: Folder? = null
    private var query = ""
    private var videos: List<MediaItem> = emptyList()
    private var audio: List<MediaItem> = emptyList()
    private var collage: List<MediaItem> = emptyList()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reload() }

    private val pickVideos =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
            if (uris.isNotEmpty()) importToCollage(uris)
        }

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { reload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        permissionPanel = findViewById(R.id.permissionPanel)
        collageHeader = findViewById(R.id.collageHeader)
        collageInfo = findViewById(R.id.collageInfo)
        btnLayout = findViewById(R.id.btnLayout)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<View>(R.id.btnGrant).setOnClickListener { requestPerms() }
        findViewById<View>(R.id.btnAddCollage).setOnClickListener {
            pickVideos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }
        btnLayout.setOnClickListener { chooseLayout() }
        findViewById<View>(R.id.btnStartCollage).setOnClickListener { startCollage() }
        updateLayoutButton()

        findViewById<BottomNavigationView>(R.id.bottomNav).setOnItemSelectedListener {
            tab = when (it.itemId) {
                R.id.nav_folders -> Tab.FOLDERS
                R.id.nav_audio -> Tab.AUDIO
                R.id.nav_collage -> Tab.COLLAGE
                else -> Tab.VIDEOS
            }
            openFolder = null
            render()
            true
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (openFolder != null) {
                    openFolder = null
                    render()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (!hasPerms()) requestPerms()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    // ---------------- permissions ----------------

    private fun neededPerms(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO
        )
        Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    private fun hasPerms(): Boolean {
        if (Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) {
            return true // partial access ("selected videos only") still works
        }
        return granted(neededPerms()[0])
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requestPerms() {
        val perms = neededPerms().toMutableList()
        if (Build.VERSION.SDK_INT >= 34) perms += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        permLauncher.launch(perms.toTypedArray())
    }

    // ---------------- data ----------------

    private fun reload() {
        bg.execute {
            val v = MediaRepository.loadVideos(this)
            val a = MediaRepository.loadAudio(this)
            val c = MediaRepository.loadCollageVideos(this)
            runOnUiThread {
                videos = v; audio = a; collage = c
                render()
            }
        }
    }

    private fun render() {
        val needPerm = !hasPerms() && tab != Tab.COLLAGE
        permissionPanel.visibility = if (needPerm) View.VISIBLE else View.GONE
        collageHeader.visibility = if (tab == Tab.COLLAGE) View.VISIBLE else View.GONE
        toolbar.subtitle = openFolder?.name

        val sorted: (List<MediaItem>) -> List<MediaItem> = { items ->
            MediaRepository.sort(items, prefs.sortOrder)
                .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
        }
        val rows: List<Row> = when (tab) {
            Tab.VIDEOS -> sorted(videos).map { Row.Media(it) }
            Tab.AUDIO -> sorted(audio).map { Row.Media(it) }
            Tab.COLLAGE -> sorted(collage).map { Row.Media(it) }
            Tab.FOLDERS -> openFolder?.let { f -> sorted(f.items).map { Row.Media(it) } }
                ?: MediaRepository.groupFolders(videos)
                    .filter { query.isBlank() || it.name.contains(query, true) }
                    .map { Row.Dir(it) }
        }
        adapter.rows = if (needPerm) emptyList() else rows

        if (tab == Tab.COLLAGE) {
            collageInfo.text = "${collage.size} video(s) in Movies/${MediaRepository.COLLAGE_FOLDER}. " +
                "Collage mode shuffles from this folder only. Long-press a video to remove it."
        }
        empty.visibility = if (!needPerm && rows.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (tab == Tab.COLLAGE) getString(R.string.collage_empty) else getString(R.string.empty_library)
    }

    // ---------------- clicks ----------------

    private fun onRowClick(row: Row) {
        when (row) {
            is Row.Dir -> {
                openFolder = row.folder
                render()
            }
            is Row.Media -> {
                val playlist = (adapter.rows.filterIsInstance<Row.Media>()).map { it.item }
                val idx = playlist.indexOf(row.item).coerceAtLeast(0)
                PlayerActivity.start(this, playlist.map { it.uri }, playlist.map { it.title }, idx)
            }
        }
    }

    private fun onRowLongClick(row: Row) {
        if (row !is Row.Media) return
        val item = row.item
        val options = if (tab == Tab.COLLAGE) arrayOf("Play", "Remove from Collage Mode folder")
        else if (!item.isAudio) arrayOf("Play", "Copy to Collage Mode folder", "Share")
        else arrayOf("Play", "Share")
        MaterialAlertDialogBuilder(this)
            .setTitle(item.title)
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Play" -> onRowClick(row)
                    "Copy to Collage Mode folder" -> importToCollage(listOf(item.uri))
                    "Remove from Collage Mode folder" -> deleteFromCollage(item)
                    "Share" -> startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType(if (item.isAudio) "audio/*" else "video/*")
                            .putExtra(Intent.EXTRA_STREAM, item.uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))
                }
            }.show()
    }

    private fun deleteFromCollage(item: MediaItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove from Collage Mode?")
            .setMessage("This deletes the copy inside the Collage Mode folder. Your original video is untouched.")
            .setPositiveButton("Remove") { _, _ ->
                val sender = CollageStore.delete(this, listOf(item.uri))
                if (sender != null) deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
                else reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------- collage ----------------

    private fun updateLayoutButton() {
        btnLayout.text = CollageLayouts.label(prefs.collageRows, prefs.collageCols)
    }

    private fun chooseLayout() {
        CollageLayouts.showPicker(this, prefs.collageRows, prefs.collageCols) { r, c ->
            prefs.collageRows = r
            prefs.collageCols = c
            updateLayoutButton()
        }
    }

    private fun startCollage() {
        if (collage.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Collage Mode folder is empty")
                .setMessage("Add some videos from your gallery first. They are copied into Movies/Collage Mode.")
                .setPositiveButton(R.string.add_from_gallery) { _, _ ->
                    pickVideos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        startActivity(Intent(this, CollageActivity::class.java))
    }

    @Suppress("DEPRECATION")
    private fun importToCollage(uris: List<Uri>) {
        val doImport = { optimize: Boolean ->
            val dlg = ProgressDialog(this).apply {
                setTitle("Adding to Collage Mode")
                setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
                max = 100
                setCancelable(false)
            }
            var job: CollageStore.ImportJob? = null
            dlg.setButton(ProgressDialog.BUTTON_NEGATIVE, "Stop") { _, _ -> job?.cancel() }
            dlg.show()
            job = CollageStore.import(this, uris, optimize, prefs.collageOptimizeHeight,
                object : CollageStore.ImportCallback {
                    override fun onProgress(index: Int, total: Int, name: String, percent: Int, optimizing: Boolean) {
                        dlg.setMessage((if (optimizing) "Optimizing" else "Copying") +
                            " ${index + 1}/$total\n$name")
                        dlg.isIndeterminate = !optimizing
                        dlg.progress = percent
                    }

                    override fun onFinished(copied: Int, failed: Int) {
                        if (!isDestroyed) dlg.dismiss()
                        Toast.makeText(this@MainActivity,
                            "Added $copied video(s)" + if (failed > 0) ", $failed failed" else "",
                            Toast.LENGTH_LONG).show()
                        reload()
                    }
                })
        }
        if (!prefs.collageOptimize) {
            doImport(false)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Optimize for collage?")
            .setMessage("Re-encoding large videos (1080p/4K) to ${prefs.collageOptimizeHeight}p makes " +
                "9 videos at once play smoothly. Your original stays untouched.\n\n" +
                "Small videos are copied as-is either way.")
            .setPositiveButton("Optimize (recommended)") { _, _ -> doImport(true) }
            .setNegativeButton("Copy original") { _, _ -> doImport(false) }
            .show()
    }

    // ---------------- menu ----------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        val sv = menu.findItem(R.id.action_search).actionView as SearchView
        sv.queryHint = getString(R.string.search)
        sv.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = true
            override fun onQueryTextChange(q: String?): Boolean {
                query = q.orEmpty()
                render()
                return true
            }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_sort -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sort)
                .setSingleChoiceItems(MediaRepository.SORT_NAMES, prefs.sortOrder) { d, w ->
                    prefs.sortOrder = w
                    render()
                    d.dismiss()
                }.show()
            R.id.action_stream -> openStreamDialog()
            R.id.action_refresh -> reload()
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun openStreamDialog() {
        val input = EditText(this).apply {
            hint = "https://… / rtsp://… / udp://…"
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.open_stream)
            .setView(box)
            .setPositiveButton("Play") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) PlayerActivity.start(this, listOf(Uri.parse(url)), listOf(url), 0)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
