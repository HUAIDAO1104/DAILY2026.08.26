package com.pengxh.daily.app.widget

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
import kotlin.math.sin

class AuroraBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 14_000L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(14, 5, 10))
        if (width == 0 || height == 0) return
        drawGlow(
            canvas,
            width * (0.15f + 0.18f * phase),
            height * (0.18f + 0.05f * sin(phase * Math.PI).toFloat()),
            width * 0.78f,
            Color.argb(92, 187, 24, 59)
        )
        drawGlow(
            canvas,
            width * (0.92f - 0.22f * phase),
            height * 0.52f,
            width * 0.72f,
            Color.argb(54, 132, 30, 80)
        )
        drawGlow(
            canvas,
            width * 0.38f,
            height * (0.92f - 0.12f * phase),
            width * 0.65f,
            Color.argb(42, 205, 96, 83)
        )
    }

    private fun drawGlow(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        paint.shader = RadialGradient(
            x, y, radius,
            intArrayOf(color, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x, y, radius, paint)
        paint.shader = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
