package com.alayed.ecutester.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import kotlin.math.min

/**
 * Hosts a single fixed 1920x1080 child (the dashboard) and scales it to fit the
 * screen, centered — the native equivalent of web app.js `fitStage()`. The child
 * is authored in a fixed 1920x1080 px coordinate system (so the web layout's exact
 * geometry ports 1:1); this view does the uniform scale on the GPU.
 */
class StageLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {

    companion object {
        const val DW = 1920
        const val DH = 1080
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // always measure the child at exactly the design size
        val wSpec = MeasureSpec.makeMeasureSpec(DW, MeasureSpec.EXACTLY)
        val hSpec = MeasureSpec.makeMeasureSpec(DH, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(wSpec, hSpec)
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) getChildAt(i).layout(0, 0, DW, DH)
        applyScale(r - l, b - t)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        applyScale(w, h)
    }

    private fun applyScale(w: Int, h: Int) {
        if (w == 0 || h == 0) return
        val scale = min(w.toFloat() / DW, h.toFloat() / DH)
        for (i in 0 until childCount) {
            val c: View = getChildAt(i)
            c.pivotX = 0f
            c.pivotY = 0f
            c.scaleX = scale
            c.scaleY = scale
            c.translationX = (w - DW * scale) / 2f
            c.translationY = (h - DH * scale) / 2f
        }
    }
}
