package com.autolyrics.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/** Background: palette gradient plus two soft aurora glows drifting very slowly. */
class AuroraView(context: Context) : View(context) {

    private var palette: Palette = Themes.current(context)
    private val bgPaint = Paint()
    private val blob1 = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blob2 = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m1 = Matrix()
    private val m2 = Matrix()
    private val dp = resources.displayMetrics.density
    private var t = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 22_000
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
    }

    fun setPalette(p: Palette) {
        palette = p
        buildShaders(width, height)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = buildShaders(w, h)

    private fun buildShaders(w: Int, h: Int) {
        if (w == 0 || h == 0) return
        bgPaint.shader = LinearGradient(
            0f, 0f, w * 0.35f, h.toFloat(),
            intArrayOf(palette.bgTop, palette.bgMid, palette.bgBottom), floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        // Unit-radius glows, placed and scaled with a matrix each frame.
        blob1.shader = RadialGradient(0f, 0f, 1f, palette.aurora1, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        blob2.shader = RadialGradient(0f, 0f, 1f, palette.aurora2, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val r1 = 220 * dp * (1f + 0.15f * t)
        m1.setScale(r1, r1)
        m1.postTranslate(w * 0.1f + 60 * dp * t, h * 0.22f + 40 * dp * t)
        blob1.shader?.setLocalMatrix(m1)
        canvas.drawRect(0f, 0f, w, h, blob1)

        val r2 = 240 * dp * (1.1f - 0.15f * t)
        m2.setScale(r2, r2)
        m2.postTranslate(w * 0.95f - 50 * dp * t, h * 0.65f - 30 * dp * t)
        blob2.shader?.setLocalMatrix(m2)
        canvas.drawRect(0f, 0f, w, h, blob2)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
