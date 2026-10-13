package com.collageplayer.collage

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object CollageLayouts {
    /** rows to columns. Max 3×3. */
    val ALL = listOf(1 to 2, 2 to 1, 1 to 3, 3 to 1, 2 to 2, 2 to 3, 3 to 2, 3 to 3)

    fun label(rows: Int, cols: Int) = "$rows×$cols"

    fun describe(rows: Int, cols: Int): String {
        val r = if (rows == 1) "1 row" else "$rows rows"
        val c = if (cols == 1) "1 column" else "$cols columns"
        return "${label(rows, cols)}   ($r, $c — ${rows * cols} videos)"
    }

    fun showPicker(context: Context, rows: Int, cols: Int, onPick: (Int, Int) -> Unit) {
        val cur = ALL.indexOf(rows to cols)
        MaterialAlertDialogBuilder(context)
            .setTitle("Collage layout")
            .setSingleChoiceItems(ALL.map { describe(it.first, it.second) }.toTypedArray(), cur) { d, w ->
                onPick(ALL[w].first, ALL[w].second)
                d.dismiss()
            }
            .show()
    }
}
