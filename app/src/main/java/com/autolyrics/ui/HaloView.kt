package com.autolyrics.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/** Soft glow behind the album art that slowly "breathes" while music plays. */
class HaloView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var color = Themes.current(context).halo

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3_000
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { setPhase(it.animatedValue as Float) }
    }

    init {
        setPhase(0f)
    }

    private fun setPhase(f: Float) {
        val s = 0.95f + 0.11f * f
        scaleX = s
        scaleY = s
        alpha = 0.4f + 0.5f * f
    }

    fun setPalette(p: Palette) {
        color = p.halo
        buildShader()
        invalidate()
    }

    /** Breathes while playing, rests dim when paused. */
    fun setBreathing(on: Boolean) {
        if (on && !animator.isStarted) animator.start()
        else if (!on && animator.isStarted) {
            animator.cancel()
            setPhase(0f)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = buildShader()

    private fun buildShader() {
        if (width == 0) return
        paint.shader = RadialGradient(
            width / 2f, height / 2f, width / 2f, color, Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(width / 2f, height / 2f, width / 2f, paint)
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
