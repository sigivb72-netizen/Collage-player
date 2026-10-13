package com.collageplayer.collage

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/** Lays its children out in an evenly divided rows × cols grid. */
class CollageGrid @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    var rows = 1
        set(v) { field = v; requestLayout() }
    var cols = 2
        set(v) { field = v; requestLayout() }

    private val gap = (2 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val cw = (w - gap * (cols - 1)) / cols
        val ch = (h - gap * (rows - 1)) / rows
        for (i in 0 until childCount) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val cw = (w - gap * (cols - 1)) / cols
        val ch = (h - gap * (rows - 1)) / rows
        for (i in 0 until childCount) {
            val child: View = getChildAt(i)
            val row = i / cols
            val col = i % cols
            if (row >= rows) {
                child.layout(0, 0, 0, 0)
                continue
            }
            val x = col * (cw + gap)
            val y = row * (ch + gap)
            child.layout(x, y, x + cw, y + ch)
        }
    }
}
