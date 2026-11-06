package com.stark.jarvis

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View

class GlowView(c: Context) : View(c) {
    private val p = Paint()
    private var a = 0.6f
    private val anim = ValueAnimator.ofFloat(0.55f, 1f).apply {
        duration = 700
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener { a = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = resources.displayMetrics.density * 56f
        val c1 = Color.argb((255 * a).toInt(), 25, 211, 255)
        val c0 = Color.argb(0, 25, 211, 255)
        fun edge(x0: Float, y0: Float, x1: Float, y1: Float, l: Float, tp: Float, r: Float, b: Float) {
            p.shader = LinearGradient(x0, y0, x1, y1, c1, c0, Shader.TileMode.CLAMP)
            cv.drawRect(l, tp, r, b, p)
        }
        edge(0f, 0f, 0f, t, 0f, 0f, w, t)
        edge(0f, h, 0f, h - t, 0f, h - t, w, h)
        edge(0f, 0f, t, 0f, 0f, 0f, t, h)
        edge(w, 0f, w - t, 0f, w - t, 0f, w, h)
    }
}
