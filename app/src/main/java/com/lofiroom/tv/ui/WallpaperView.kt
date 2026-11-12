package com.lofiroom.tv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import java.util.concurrent.Executors

/**
 * 壁纸轮播：assets/wallpapers/ 读图，crossfade 过渡 + 缓慢 Ken Burns 推镜。
 */
class WallpaperView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onWallpaperChange: ((index: Int, total: Int) -> Unit)? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private var names: List<String> = emptyList()
    private var index = 0
    private var current: Bitmap? = null
    private var previous: Bitmap? = null
    private var switchAtMs = -1L
    private var totalMs = 0L

    private val fadeMs = 1600L
    private val holdMs = 38_000L
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile
    private var running = true
    private var advancing = false // #1 防重入：解码未回前不再重复触发 advance
    private var frameCount = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val elapsed = if (switchAtMs > 0) System.currentTimeMillis() - switchAtMs else 0L
            if (switchAtMs > 0 && elapsed > holdMs) advance()
            // #2 淡入淡出期全帧率；推镜/静止段隔帧 30fps，降低低端盒子 GPU 填充压力
            frameCount++
            val inFade = switchAtMs > 0 && elapsed < fadeMs
            if (inFade || frameCount % 2 == 0L) invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (names.isEmpty()) loadList()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stop() {
        running = false
    }

    private fun loadList() {
        names = (context.assets.list("wallpapers") ?: emptyArray()).sorted()
        if (names.isEmpty()) return
        executor.execute {
            val bmp = decode(0)
            post {
                if (!running || bmp == null) return@post
                current = bmp
                switchAtMs = System.currentTimeMillis()
                totalMs = holdMs
                onWallpaperChange?.invoke(0, names.size)
                invalidate()
            }
        }
    }

    private fun advance() {
        if (names.isEmpty() || advancing) return // #1 防重入
        advancing = true
        val next = (index + 1) % names.size
        executor.execute {
            val bmp = decode(next)
            post {
                advancing = false // #1 无论成败都复位标志
                if (!running || bmp == null) return@post
                previous = current
                current = bmp
                index = next
                switchAtMs = System.currentTimeMillis()
                totalMs = holdMs
                onWallpaperChange?.invoke(index, names.size)
            }
        }
    }

    private fun decode(i: Int): Bitmap? = try {
        val path = "wallpapers/${names[i]}"
        // #2 先探边界，按屏幕尺寸算 inSampleSize，避免超大位图常驻内存
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
        val dm = context.resources.displayMetrics
        val targetW = (maxOf(dm.widthPixels, dm.heightPixels) * 1.25f).toInt()
        var sample = 1
        while (opts.outWidth / (sample * 2) >= targetW) sample *= 2
        opts.inSampleSize = sample
        opts.inJustDecodeBounds = false
        context.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) {
        null
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = current
        if (bmp == null) {
            canvas.drawColor(Color.parseColor("#0B0908"))
            return
        }
        val now = System.currentTimeMillis()
        val elapsed = if (switchAtMs > 0) now - switchAtMs else 0L

        // 旧图淡出（保持在最终推镜位）
        previous?.let {
            drawBitmap(canvas, it, 1f - (elapsed.toFloat() / fadeMs).coerceIn(0f, 1f), 1f)
            if (elapsed > fadeMs) {
                if (it !== current) it.recycle()
                previous = null
            }
        }

        // 新图淡入 + Ken Burns 推镜
        val alpha = if (previous == null) 1f else (elapsed.toFloat() / fadeMs).coerceIn(0f, 1f)
        val progress = (elapsed.toFloat() / totalMs).coerceIn(0f, 1f)
        drawBitmap(canvas, bmp, alpha, 1.02f + 0.06f * progress)
    }

    private fun drawBitmap(canvas: Canvas, bmp: Bitmap, alpha: Float, zoom: Float) {
        if (alpha <= 0f || bmp.isRecycled) return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val base = maxOf(vw / bmp.width, vh / bmp.height) * zoom
        val w = bmp.width * base
        val h = bmp.height * base
        matrix.reset()
        matrix.setScale(base, base)
        matrix.postTranslate((vw - w) / 2f, (vh - h) / 2f)
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        canvas.drawBitmap(bmp, matrix, paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        executor.shutdown()
    }
}
