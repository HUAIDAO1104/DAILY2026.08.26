package com.pengxh.daily.app.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * DailyTask 的 AI 形象。所有图形都在 Canvas 中绘制，不依赖文字、emoji 或位图资源。
 */
class AiCompanionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State {
        IDLE, AWARE, THINKING, HAPPY, ERROR, SLEEPING
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyPath = Path()
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 5200L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }
    private var phase = 0f
    private var pressedScale = 1f
    private var stateStartedAt = SystemClock.uptimeMillis()

    var state: State = State.IDLE
        private set

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateContentDescription()
    }

    fun setState(newState: State) {
        if (state == newState) return
        state = newState
        stateStartedAt = SystemClock.uptimeMillis()
        updateContentDescription()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (ValueAnimator.areAnimatorsEnabled() && !animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val cx = width / 2f
        val cy = height / 2f + sin(phase * PI * 2).toFloat() * size * floatAmount()
        val bodyRadius = size * 0.335f
        val scale = pressedScale * stateScale()

        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        drawAura(canvas, cx, cy, bodyRadius)
        drawOrbits(canvas, cx, cy, bodyRadius)
        drawBody(canvas, cx, cy, bodyRadius)
        drawEyes(canvas, cx, cy, bodyRadius)
        canvas.restore()
    }

    private fun drawAura(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val pulse = 1f + 0.08f * sin(phase * PI * 4).toFloat()
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx,
            cy,
            radius * 1.65f * pulse,
            intArrayOf(Color.argb(82, 255, 54, 91), Color.argb(28, 190, 12, 48), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 1.65f * pulse, paint)
        paint.shader = null
    }

    private fun drawOrbits(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val speed = when (state) {
            State.THINKING -> 4.2f
            State.AWARE -> 1.8f
            State.HAPPY -> 2.2f
            State.ERROR -> 0.35f
            State.SLEEPING -> 0.2f
            State.IDLE -> 1f
        }
        val alpha = when (state) {
            State.SLEEPING -> 30
            State.ERROR -> 70
            else -> 105
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, radius * 0.022f)
        paint.color = Color.argb(alpha, 255, 92, 116)
        paint.pathEffect = DashPathEffect(floatArrayOf(radius * 0.09f, radius * 0.13f), 0f)
        val orbit = RectF(cx - radius * 1.37f, cy - radius * 1.14f, cx + radius * 1.37f, cy + radius * 1.14f)
        canvas.save()
        canvas.rotate(phase * 360f * speed, cx, cy)
        canvas.drawOval(orbit, paint)
        paint.pathEffect = null

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(230, 255, 109, 132)
        val nodeAngle = phase * PI.toFloat() * 2f * speed
        val nx = cx + cos(nodeAngle) * radius * 1.37f
        val ny = cy + sin(nodeAngle) * radius * 1.14f
        canvas.drawCircle(nx, ny, maxOf(1.6f, radius * 0.045f), paint)
        canvas.restore()
    }

    private fun drawBody(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val motion = when (state) {
            State.THINKING -> 0.065f
            State.AWARE -> 0.035f
            State.ERROR -> 0.025f
            State.SLEEPING -> 0.012f
            else -> 0.025f
        }
        val p = phase * PI.toFloat() * 2f
        val left = radius * (1f + sin(p) * motion)
        val right = radius * (1f + cos(p * 1.1f) * motion)
        val top = radius * (1f + sin(p + 1.3f) * motion)
        val bottom = radius * (1f + cos(p + 0.7f) * motion)

        bodyPath.reset()
        bodyPath.moveTo(cx, cy - top)
        bodyPath.cubicTo(cx + right * 0.78f, cy - top, cx + right, cy - bottom * 0.58f, cx + right, cy)
        bodyPath.cubicTo(cx + right, cy + bottom * 0.72f, cx + right * 0.56f, cy + bottom, cx, cy + bottom)
        bodyPath.cubicTo(cx - left * 0.68f, cy + bottom, cx - left, cy + bottom * 0.55f, cx - left, cy)
        bodyPath.cubicTo(cx - left, cy - top * 0.7f, cx - left * 0.52f, cy - top, cx, cy - top)
        bodyPath.close()

        val tint = when (state) {
            State.ERROR -> intArrayOf(Color.rgb(255, 196, 201), Color.rgb(144, 34, 48), Color.rgb(55, 13, 24))
            State.SLEEPING -> intArrayOf(Color.rgb(225, 181, 189), Color.rgb(115, 35, 53), Color.rgb(45, 17, 26))
            else -> intArrayOf(Color.rgb(255, 222, 225), Color.rgb(226, 48, 79), Color.rgb(91, 8, 31))
        }
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx - radius * 0.34f,
            cy - radius * 0.4f,
            radius * 1.7f,
            tint,
            floatArrayOf(0f, 0.46f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(bodyPath, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, radius * 0.025f)
        paint.color = Color.argb(150, 255, 255, 255)
        canvas.drawPath(bodyPath, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(132, 255, 255, 255)
        canvas.drawOval(
            RectF(cx - radius * 0.48f, cy - radius * 0.63f, cx + radius * 0.08f, cy - radius * 0.34f),
            paint
        )
    }

    private fun drawEyes(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val blink = blinkScale()
        val eyeY = cy + radius * 0.04f
        val gap = radius * 0.42f
        val eyeWidth = maxOf(1.8f, radius * 0.15f)
        val eyeHeight = maxOf(3f, radius * 0.42f) * blink
        paint.shader = null
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.rgb(71, 7, 26)

        when (state) {
            State.HAPPY -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = maxOf(2f, radius * 0.12f)
                drawHappyEye(canvas, cx - gap, eyeY, radius)
                drawHappyEye(canvas, cx + gap, eyeY, radius)
            }
            State.THINKING -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = maxOf(2f, radius * 0.11f)
                canvas.drawLine(cx - gap - radius * 0.12f, eyeY, cx - gap + radius * 0.12f, eyeY - radius * 0.07f, paint)
                canvas.drawLine(cx + gap - radius * 0.12f, eyeY - radius * 0.07f, cx + gap + radius * 0.12f, eyeY, paint)
            }
            State.ERROR -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = maxOf(2f, radius * 0.11f)
                canvas.drawLine(cx - gap - radius * 0.13f, eyeY - radius * 0.06f, cx - gap + radius * 0.13f, eyeY + radius * 0.04f, paint)
                canvas.drawLine(cx + gap - radius * 0.13f, eyeY + radius * 0.04f, cx + gap + radius * 0.13f, eyeY - radius * 0.06f, paint)
            }
            State.SLEEPING -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = maxOf(2f, radius * 0.1f)
                canvas.drawArc(RectF(cx - gap - radius * 0.18f, eyeY - radius * 0.05f, cx - gap + radius * 0.18f, eyeY + radius * 0.18f), 200f, 140f, false, paint)
                canvas.drawArc(RectF(cx + gap - radius * 0.18f, eyeY - radius * 0.05f, cx + gap + radius * 0.18f, eyeY + radius * 0.18f), 200f, 140f, false, paint)
            }
            State.IDLE, State.AWARE -> {
                val gaze = if (state == State.AWARE) radius * 0.05f else sin(phase * PI * 2).toFloat() * radius * 0.025f
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(
                    RectF(cx - gap - eyeWidth / 2, eyeY - eyeHeight / 2, cx - gap + eyeWidth / 2, eyeY + eyeHeight / 2),
                    eyeWidth,
                    eyeWidth,
                    paint
                )
                canvas.drawRoundRect(
                    RectF(cx + gap - eyeWidth / 2, eyeY - eyeHeight / 2, cx + gap + eyeWidth / 2, eyeY + eyeHeight / 2),
                    eyeWidth,
                    eyeWidth,
                    paint
                )
                if (radius > 22f && blink > 0.35f) {
                    paint.color = Color.argb(200, 255, 235, 238)
                    canvas.drawCircle(cx - gap + gaze, eyeY - eyeHeight * 0.18f, eyeWidth * 0.2f, paint)
                    canvas.drawCircle(cx + gap + gaze, eyeY - eyeHeight * 0.18f, eyeWidth * 0.2f, paint)
                }
            }
        }
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawHappyEye(canvas: Canvas, x: Float, y: Float, radius: Float) {
        canvas.drawArc(RectF(x - radius * 0.2f, y - radius * 0.1f, x + radius * 0.2f, y + radius * 0.22f), 195f, 150f, false, paint)
    }

    private fun blinkScale(): Float {
        if (state != State.IDLE && state != State.AWARE) return 1f
        val cycle = (phase * 5.2f) % 1f
        return if (cycle > 0.93f) ((cycle - 0.93f) / 0.035f - 1f).let { kotlin.math.abs(it).coerceIn(0.08f, 1f) } else 1f
    }

    private fun floatAmount(): Float = when (state) {
        State.THINKING -> 0.018f
        State.HAPPY -> 0.026f
        State.ERROR -> 0.006f
        State.SLEEPING -> 0.008f
        else -> 0.012f
    }

    private fun stateScale(): Float {
        val elapsed = (SystemClock.uptimeMillis() - stateStartedAt).coerceAtMost(600L) / 600f
        return when (state) {
            State.HAPPY -> 1f + sin(elapsed * PI).toFloat() * 0.1f
            State.ERROR -> 1f - sin(elapsed * PI * 2).toFloat() * 0.025f
            else -> 1f
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isClickable) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedScale = 0.93f
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                pressedScale = 1f
                invalidate()
                if (isPointInside(event.x, event.y)) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedScale = 1f
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun isPointInside(x: Float, y: Float) = x in 0f..width.toFloat() && y in 0f..height.toFloat()

    private fun updateContentDescription() {
        contentDescription = when (state) {
            State.IDLE -> "AI 助手待命"
            State.AWARE -> "AI 助手正在关注"
            State.THINKING -> "AI 助手正在思考"
            State.HAPPY -> "AI 助手已完成"
            State.ERROR -> "AI 助手遇到问题"
            State.SLEEPING -> "AI 助手休息中"
        }
    }
}
