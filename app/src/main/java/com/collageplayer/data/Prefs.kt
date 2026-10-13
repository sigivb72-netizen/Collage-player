package com.collageplayer.data

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

class Prefs(context: Context) {
    private val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val positions: SharedPreferences =
        context.getSharedPreferences("positions", Context.MODE_PRIVATE)

    /** "auto" | "on" | "off" */
    val hwDecoding: String get() = sp.getString("hw_decoding", "auto") ?: "auto"
    val resumePlayback: Boolean get() = sp.getBoolean("resume_playback", true)
    val defaultSpeedLock: Boolean get() = sp.getBoolean("keep_speed", false)
    val doubleTapSeekSec: Int get() = sp.getString("double_tap_seek", "10")?.toIntOrNull() ?: 10
    val gestures: Boolean get() = sp.getBoolean("gestures", true)
    val autoPip: Boolean get() = sp.getBoolean("auto_pip", false)
    val subtitleSize: String get() = sp.getString("subtitle_size", "16") ?: "16"
    val audioTimeStretch: Boolean get() = sp.getBoolean("time_stretch", true)
    val skipLoopFilter: Boolean get() = sp.getBoolean("skip_loop_filter", false)

    var sortOrder: Int
        get() = sp.getInt("sort_order", 0)
        set(v) = sp.edit().putInt("sort_order", v).apply()

    var lastSpeed: Float
        get() = sp.getFloat("last_speed", 1f)
        set(v) = sp.edit().putFloat("last_speed", v).apply()

    // ---- Collage ----
    var collageRows: Int
        get() = sp.getInt("collage_rows", 1)
        set(v) = sp.edit().putInt("collage_rows", v).apply()
    var collageCols: Int
        get() = sp.getInt("collage_cols", 2)
        set(v) = sp.edit().putInt("collage_cols", v).apply()
    val collageAutoNext: Boolean get() = sp.getBoolean("collage_auto_next", false)
    val collageOptimize: Boolean get() = sp.getBoolean("collage_optimize", true)
    val collageOptimizeHeight: Int
        get() = sp.getString("collage_optimize_size", "720")?.toIntOrNull() ?: 720
    val collageStagger: Boolean get() = sp.getBoolean("collage_stagger", true)
    val collageLagMonitor: Boolean get() = sp.getBoolean("collage_lag_monitor", true)
    val collageRandomStart: Boolean get() = sp.getBoolean("collage_random_start", true)

    fun savePosition(key: String, ms: Long) = positions.edit().putLong(key, ms).apply()
    fun getPosition(key: String): Long = positions.getLong(key, 0L)
    fun clearPosition(key: String) = positions.edit().remove(key).apply()
}
