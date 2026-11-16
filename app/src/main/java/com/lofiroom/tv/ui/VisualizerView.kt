package com.lofiroom.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.lofiroom.tv.audio.VisualizerTap
import kotlin.math.sin

/**
 * 音频波形条：72 根对称圆角柱，暖白色渐变 + 呼吸感，
 * 左密右疏的透明度过渡呼应参考直播间的波形美学。
 */
class VisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var tap: VisualizerTap? = null

    private val levels = FloatArray(VisualizerTap.BANDS)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var startTime = System.nanoTime()
    private var gradient: LinearGradient? = null
    private var gradW = -1

    init {
        linePaint.strokeWidth = dp(0.7f)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val cy = h / 2f
        val t = (System.nanoTime() - startTime) / 1e9f

        val tap = this.tap
        if (tap != null) {
            if (tap.playing) {
                tap.snapshot(levels)
            } else {
                tap.decay()
                tap.snapshot(levels) // #8 衰减后必须拷贝出来，否则柱子冻结在暂停帧
            }
        }

        if (gradW != w.toInt()) {
            gradW = w.toInt()
            gradient = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(
                    Color.parseColor("#F6F0E6"),
                    Color.parseColor("#E8D9BE"),
                    Color.parseColor("#D8B57E")
                ),
                null,
                Shader.TileMode.CLAMP
            )
        }
        paint.shader = gradient

        // 中轴细线
        linePaint.color = Color.parseColor("#33F6F0E6")
        canvas.drawLine(0f, cy, w, cy, linePaint)

        val n = levels.size
        val gap = dp(2.8f)
        val barW = ((w - gap * (n - 1)) / n).coerceAtLeast(dp(1.2f))
        val maxAmp = h / 2f - dp(2f)
        val breathe = 0.018f + 0.012f * sin(t * 1.1f).toFloat()

        for (i in 0 until n) {
            val v = levels[i].coerceIn(0f, 1f)
            var amp = v * maxAmp
            if (amp < breathe * h) amp = breathe * h

            val x = i * (barW + gap)
            rect.set(x, cy - amp, x + barW, cy + amp)
            // 右侧渐隐（呼应参考图的波形拖尾）
            paint.alpha = (255 - 150f * (i.toFloat() / n)).toInt().coerceIn(60, 255)
            val radius = barW / 2f
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        paint.shader = null
        paint.alpha = 255

        postInvalidateOnAnimation()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
