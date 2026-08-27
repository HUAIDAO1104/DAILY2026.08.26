package com.pengxh.daily.app.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 参考 luma-ai-voice-assistant 方案 1 的原生 Canvas 移植：透明液态球、双轨道与状态眼睛。
 * 颜色跟随 DailyTask 的暗红主题，不使用文字、emoji 或预渲染位图。
 */
class AiCompanionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State {
        IDLE,
        AWARE,
        SLEEPING,
        LISTENING,
        TRANSCRIBING,
        THINKING,
        SPEAKING,
        SUCCESS,
        INTERRUPTED,
        ERROR
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyPath = Path()
    private val liquidPath = Path()
    private val secondaryLiquidPath = Path()
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    private var pressedScale = 1f
    private var gazeX = 0f
    private var gazeY = 0f
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

        val compact = size < 64f * resources.displayMetrics.density
        val cx = width / 2f
        val motion = motionPhase()
        val floatY = sin(motion * PI.toFloat() * 2f) * size * floatAmount()
        val cy = height / 2f + floatY
        val bodyRadius = size * if (compact) 0.34f else 0.355f

        canvas.save()
        canvas.scale(pressedScale * stateScale(), pressedScale * stateScale(), cx, cy)
        drawAura(canvas, cx, cy, bodyRadius, compact)
        drawOrbits(canvas, cx, cy, bodyRadius, compact)
        drawGlassBody(canvas, cx, cy, bodyRadius, motion)
        drawEyes(canvas, cx, cy, bodyRadius)
        canvas.restore()
    }

    private fun drawAura(canvas: Canvas, cx: Float, cy: Float, radius: Float, compact: Boolean) {
        if (compact) return
        val pulse = when (state) {
            State.LISTENING, State.SPEAKING -> 1f + 0.08f * sin(motionPhase() * PI.toFloat() * 2f)
            State.SUCCESS -> 1.14f
            else -> 1f
        }
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx,
            cy + radius * 0.35f,
            radius * 1.65f * pulse,
            intArrayOf(
                Color.argb(58, 146, 21, 50),
                Color.argb(26, 72, 7, 25),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 1.65f * pulse, paint)
        paint.shader = null
    }

    private fun drawOrbits(canvas: Canvas, cx: Float, cy: Float, radius: Float, compact: Boolean) {
        val outerRadius = radius * 1.34f
        val innerRadius = radius * 1.13f
        val time = SystemClock.uptimeMillis() / 1000f
        val speed = when (state) {
            State.TRANSCRIBING -> 2.8f
            State.THINKING -> 1.85f
            State.LISTENING -> 1.2f
            State.SPEAKING -> 0.9f
            State.SLEEPING -> 0.16f
            State.ERROR, State.INTERRUPTED -> 0.1f
            else -> 0.34f
        }
        val ringAlpha = when (state) {
            State.SLEEPING -> 34
            State.ERROR, State.INTERRUPTED -> 52
            State.LISTENING, State.THINKING -> 125
            else -> 78
        }

        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, radius * 0.016f)
        paint.color = Color.argb(ringAlpha, 190, 50, 76)
        paint.pathEffect = DashPathEffect(
            floatArrayOf(maxOf(1f, radius * 0.035f), maxOf(3f, radius * 0.09f)),
            time * radius * 0.2f
        )
        canvas.drawCircle(cx, cy, outerRadius, paint)

        paint.pathEffect = null
        paint.color = Color.argb(ringAlpha + 18, 164, 31, 60)
        val innerBounds = RectF(cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius)
        if (state == State.THINKING || state == State.TRANSCRIBING) {
            canvas.drawArc(innerBounds, time * 220f * speed, 268f, false, paint)
        } else {
            canvas.drawCircle(cx, cy, innerRadius, paint)
        }

        if (!compact) {
            paint.style = Paint.Style.FILL
            val outerAngle = time * speed * PI.toFloat() * 2f
            paint.color = Color.argb(205, 203, 60, 87)
            canvas.drawCircle(
                cx + cos(outerAngle) * outerRadius,
                cy + sin(outerAngle) * outerRadius,
                maxOf(2f, radius * 0.035f),
                paint
            )
            val innerAngle = -time * speed * 1.35f * PI.toFloat() * 2f + 1.4f
            paint.color = Color.argb(150, 226, 184, 194)
            canvas.drawCircle(
                cx + cos(innerAngle) * innerRadius,
                cy + sin(innerAngle) * innerRadius,
                maxOf(1.6f, radius * 0.025f),
                paint
            )
        }
    }

    private fun drawGlassBody(canvas: Canvas, cx: Float, cy: Float, radius: Float, motion: Float) {
        createBodyPath(cx, cy, radius, motion)

        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx - radius * 0.38f,
            cy - radius * 0.44f,
            radius * 1.65f,
            when (state) {
                State.ERROR -> intArrayOf(
                    Color.argb(244, 64, 55, 58),
                    Color.argb(218, 74, 48, 52),
                    Color.argb(184, 94, 46, 51)
                )
                State.SLEEPING -> intArrayOf(
                    Color.argb(238, 48, 45, 49),
                    Color.argb(210, 54, 38, 44),
                    Color.argb(170, 72, 19, 35)
                )
                else -> intArrayOf(
                    Color.argb(246, 58, 54, 59),
                    Color.argb(225, 63, 39, 48),
                    Color.argb(205, 122, 17, 46)
                )
            },
            floatArrayOf(0f, 0.56f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(bodyPath, paint)
        paint.shader = null

        canvas.save()
        canvas.clipPath(bodyPath)
        drawLiquidLayers(canvas, cx, cy, radius, motion)
        drawHighlights(canvas, cx, cy, radius)
        canvas.restore()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1.1f, radius * 0.026f)
        paint.color = Color.argb(132, 255, 255, 255)
        canvas.drawPath(bodyPath, paint)
        paint.strokeWidth = maxOf(0.8f, radius * 0.012f)
        paint.color = Color.argb(118, 177, 99, 117)
        canvas.drawCircle(cx, cy, radius * 0.93f, paint)
    }

    private fun createBodyPath(cx: Float, cy: Float, radius: Float, motion: Float) {
        val deformation = when (state) {
            State.LISTENING -> 0.065f
            State.TRANSCRIBING, State.THINKING -> 0.055f
            State.SPEAKING -> 0.07f
            State.INTERRUPTED, State.ERROR -> 0.025f
            State.SLEEPING -> 0.018f
            else -> 0.025f
        }
        val wave = sin(motion * PI.toFloat() * 2f)
        val cross = cos(motion * PI.toFloat() * 2f)
        val left = radius * (1f + wave * deformation)
        val right = radius * (1f - wave * deformation * 0.72f)
        val top = radius * (1f + cross * deformation * 0.62f)
        val bottom = radius * (1f - cross * deformation * 0.72f)

        bodyPath.reset()
        bodyPath.moveTo(cx, cy - top)
        bodyPath.cubicTo(cx + right * 0.58f, cy - top, cx + right, cy - right * 0.6f, cx + right, cy)
        bodyPath.cubicTo(cx + right, cy + bottom * 0.63f, cx + bottom * 0.58f, cy + bottom, cx, cy + bottom)
        bodyPath.cubicTo(cx - left * 0.62f, cy + bottom, cx - left, cy + left * 0.56f, cx - left, cy)
        bodyPath.cubicTo(cx - left, cy - top * 0.62f, cx - top * 0.6f, cy - top, cx, cy - top)
        bodyPath.close()
    }

    private fun drawLiquidLayers(canvas: Canvas, cx: Float, cy: Float, radius: Float, motion: Float) {
        val liquidShift = when (state) {
            State.THINKING -> sin(motion * PI.toFloat() * 2f) * radius * 0.18f
            State.SPEAKING, State.LISTENING -> sin(motion * PI.toFloat() * 2f) * radius * 0.08f
            else -> sin(motion * PI.toFloat() * 2f) * radius * 0.035f
        }

        liquidPath.reset()
        liquidPath.moveTo(cx - radius * 1.12f, cy + radius * 0.02f + liquidShift)
        liquidPath.cubicTo(
            cx - radius * 0.62f,
            cy - radius * 0.04f,
            cx - radius * 0.42f,
            cy + radius * 0.76f,
            cx + radius * 0.08f,
            cy + radius * 0.72f
        )
        liquidPath.cubicTo(
            cx + radius * 0.62f,
            cy + radius * 0.68f,
            cx + radius * 0.57f,
            cy + radius * 0.02f,
            cx + radius * 1.1f,
            cy - radius * 0.12f + liquidShift * 0.35f
        )
        liquidPath.lineTo(cx + radius * 1.2f, cy + radius * 1.2f)
        liquidPath.lineTo(cx - radius * 1.2f, cy + radius * 1.2f)
        liquidPath.close()

        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            cx - radius,
            cy,
            cx + radius,
            cy + radius,
            intArrayOf(
                Color.argb(220, 168, 30, 62),
                Color.argb(235, 112, 12, 39),
                Color.argb(238, 60, 5, 24)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(liquidPath, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, radius * 0.025f)
        paint.color = Color.argb(155, 214, 133, 151)
        canvas.drawPath(liquidPath, paint)

        secondaryLiquidPath.reset()
        secondaryLiquidPath.moveTo(cx - radius * 1.04f, cy - radius * 0.66f)
        secondaryLiquidPath.cubicTo(
            cx - radius * 0.55f,
            cy - radius * 1.08f,
            cx + radius * 0.18f,
            cy - radius * 0.9f,
            cx + radius * 0.1f,
            cy - radius * 0.34f
        )
        secondaryLiquidPath.cubicTo(
            cx + radius * 0.02f,
            cy + radius * 0.08f,
            cx - radius * 0.56f,
            cy + radius * 0.04f,
            cx - radius * 1.02f,
            cy - radius * 0.1f
        )
        secondaryLiquidPath.close()
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            cx - radius,
            cy - radius,
            cx + radius * 0.35f,
            cy,
            intArrayOf(
                Color.argb(150, 196, 186, 190),
                Color.argb(130, 149, 89, 105),
                Color.argb(110, 137, 31, 57)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(secondaryLiquidPath, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, radius * 0.02f)
        paint.color = Color.argb(110, 255, 255, 255)
        canvas.drawPath(secondaryLiquidPath, paint)
    }

    private fun drawHighlights(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(100, 255, 255, 255)
        canvas.save()
        canvas.rotate(-24f, cx - radius * 0.3f, cy - radius * 0.48f)
        canvas.drawOval(
            RectF(
                cx - radius * 0.57f,
                cy - radius * 0.64f,
                cx - radius * 0.02f,
                cy - radius * 0.41f
            ),
            paint
        )
        canvas.restore()
        paint.color = Color.argb(48, 255, 255, 255)
        canvas.drawCircle(cx + radius * 0.53f, cy - radius * 0.46f, radius * 0.12f, paint)
    }

    private fun drawEyes(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val eyeY = cy - radius * 0.02f + gazeY * radius * 0.08f
        val gap = when (state) {
            State.LISTENING -> radius * 0.42f
            State.SLEEPING, State.TRANSCRIBING, State.ERROR -> radius * 0.27f
            else -> radius * 0.34f
        }
        val eyeColor = if (state == State.ERROR || state == State.INTERRUPTED) {
            Color.rgb(225, 190, 198)
        } else {
            Color.rgb(255, 244, 247)
        }
        paint.shader = null
        paint.color = eyeColor
        paint.strokeCap = Paint.Cap.ROUND

        when (state) {
            State.SLEEPING -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = maxOf(2f, radius * 0.07f)
                drawClosedEye(canvas, cx - gap, eyeY, radius, sleeping = true)
                drawClosedEye(canvas, cx + gap, eyeY, radius, sleeping = true)
            }
            State.TRANSCRIBING -> {
                paint.style = Paint.Style.FILL
                val pulse = 0.82f + 0.18f * sin(motionPhase() * PI.toFloat() * 2f)
                canvas.drawCircle(cx - gap, eyeY, radius * 0.075f * pulse, paint)
                canvas.drawCircle(cx + gap, eyeY, radius * 0.075f * (1.82f - pulse), paint)
            }
            State.THINKING -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = maxOf(2f, radius * 0.085f)
                canvas.drawLine(cx - gap - radius * 0.13f, eyeY, cx - gap + radius * 0.13f, eyeY - radius * 0.06f, paint)
                canvas.drawLine(cx + gap - radius * 0.13f, eyeY - radius * 0.06f, cx + gap + radius * 0.13f, eyeY, paint)
            }
            State.SPEAKING, State.SUCCESS -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = maxOf(2f, radius * 0.075f)
                drawClosedEye(canvas, cx - gap, eyeY, radius, sleeping = false)
                drawClosedEye(canvas, cx + gap, eyeY, radius, sleeping = false)
            }
            State.INTERRUPTED -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = maxOf(2f, radius * 0.08f)
                canvas.drawLine(cx - gap - radius * 0.13f, eyeY, cx - gap + radius * 0.13f, eyeY, paint)
                canvas.drawLine(cx + gap - radius * 0.13f, eyeY, cx + gap + radius * 0.13f, eyeY, paint)
            }
            State.ERROR -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = maxOf(2f, radius * 0.085f)
                canvas.drawLine(cx - gap - radius * 0.13f, eyeY - radius * 0.06f, cx - gap + radius * 0.13f, eyeY + radius * 0.04f, paint)
                canvas.drawLine(cx + gap - radius * 0.13f, eyeY + radius * 0.04f, cx + gap + radius * 0.13f, eyeY - radius * 0.06f, paint)
            }
            State.IDLE, State.AWARE, State.LISTENING -> {
                val blink = blinkScale()
                val width = maxOf(2.2f, radius * 0.105f)
                val baseHeight = when (state) {
                    State.AWARE -> radius * 0.34f
                    State.LISTENING -> radius * 0.38f
                    else -> radius * 0.31f
                }
                val height = maxOf(2f, baseHeight * blink)
                val gaze = gazeX * radius * 0.08f
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(
                    RectF(cx - gap + gaze - width / 2f, eyeY - height / 2f, cx - gap + gaze + width / 2f, eyeY + height / 2f),
                    width,
                    width,
                    paint
                )
                canvas.drawRoundRect(
                    RectF(cx + gap + gaze - width / 2f, eyeY - height / 2f, cx + gap + gaze + width / 2f, eyeY + height / 2f),
                    width,
                    width,
                    paint
                )
            }
        }
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawClosedEye(canvas: Canvas, x: Float, y: Float, radius: Float, sleeping: Boolean) {
        val bounds = if (sleeping) {
            RectF(x - radius * 0.16f, y - radius * 0.03f, x + radius * 0.16f, y + radius * 0.16f)
        } else {
            RectF(x - radius * 0.17f, y - radius * 0.08f, x + radius * 0.17f, y + radius * 0.18f)
        }
        canvas.drawArc(bounds, 200f, 140f, false, paint)
    }

    private fun motionPhase(): Float {
        val duration = when (state) {
            State.IDLE, State.AWARE -> 4_600L
            State.SLEEPING -> 6_000L
            State.LISTENING -> 900L
            State.TRANSCRIBING -> 1_000L
            State.THINKING -> 1_200L
            State.SPEAKING -> 780L
            State.SUCCESS -> 1_200L
            State.INTERRUPTED -> 1_800L
            State.ERROR -> 880L
        }
        return ((SystemClock.uptimeMillis() - stateStartedAt) % duration).toFloat() / duration
    }

    private fun blinkScale(): Float {
        val cycle = (SystemClock.uptimeMillis() % 5_400L) / 5_400f
        return if (cycle in 0.455f..0.485f) {
            (abs(cycle - 0.47f) / 0.015f).coerceIn(0.08f, 1f)
        } else {
            1f
        }
    }

    private fun floatAmount(): Float = when (state) {
        State.THINKING -> 0.018f
        State.SPEAKING -> 0.021f
        State.SUCCESS -> 0.026f
        State.ERROR, State.INTERRUPTED -> 0.005f
        State.SLEEPING -> 0.007f
        else -> 0.014f
    }

    private fun stateScale(): Float {
        val elapsed = (SystemClock.uptimeMillis() - stateStartedAt).coerceAtMost(1_200L) / 1_200f
        return when (state) {
            State.SUCCESS -> 1f + sin(elapsed * PI).toFloat() * 0.1f
            State.ERROR -> 1f - abs(sin(elapsed * PI * 4).toFloat()) * 0.02f
            else -> 1f
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isClickable) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                pressedScale = if (event.actionMasked == MotionEvent.ACTION_DOWN) 0.95f else 0.98f
                gazeX = ((event.x / width.coerceAtLeast(1)) - 0.5f).coerceIn(-0.8f, 0.8f)
                gazeY = ((event.y / height.coerceAtLeast(1)) - 0.5f).coerceIn(-0.6f, 0.6f)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                pressedScale = 1f
                gazeX = 0f
                gazeY = 0f
                invalidate()
                if (event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedScale = 1f
                gazeX = 0f
                gazeY = 0f
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

    private fun updateContentDescription() {
        contentDescription = when (state) {
            State.IDLE -> "AI 助手待命"
            State.AWARE -> "AI 助手正在关注"
            State.SLEEPING -> "AI 助手休息中"
            State.LISTENING -> "AI 助手正在聆听"
            State.TRANSCRIBING -> "AI 助手正在识别"
            State.THINKING -> "AI 助手正在思考"
            State.SPEAKING -> "AI 助手正在回答"
            State.SUCCESS -> "AI 助手已完成"
            State.INTERRUPTED -> "AI 助手已暂停"
            State.ERROR -> "AI 助手遇到问题"
        }
    }
}
