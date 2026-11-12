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
    private var failStreak = 0 // 连续解码失败计数，完整一轮无一成功则退避
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
        executor.execute {
            // #9 目录列举与首图解码都放后台线程，避免主线程磁盘 I/O
            val found = (context.assets.list("wallpapers") ?: emptyArray()).sorted()
            // #9 跳过排在前面的坏文件，找到第一张能解码的
            var start = 0
            var bmp: Bitmap? = null
            while (start < found.size && bmp == null) {
                bmp = decode(found[start])
                if (bmp == null) start++
            }
            post {
                if (!running) return@post
                names = found
                if (names.isEmpty()) return@post
                // #9 无论成败都武装定时器，否则 doFrame 的 switchAtMs>0 守卫会让 advance 永不触发
                switchAtMs = System.currentTimeMillis()
                totalMs = holdMs
                if (bmp == null) return@post
                index = start
                current = bmp
                onWallpaperChange?.invoke(index, names.size)
                invalidate()
            }
        }
    }

    private fun advance() {
        if (names.isEmpty() || advancing) return // #1 防重入
        advancing = true
        val next = (index + 1) % names.size
        val nextName = names[next] // 主线程捕获，后台线程不读 names
        executor.execute {
            val bmp = decode(nextName)
            post {
                advancing = false // #1 无论成败都复位标志
                index = next // #9 失败也推进，避免卡在同一个坏文件上
                if (!running) return@post
                if (bmp == null) {
                    failStreak++
                    // #9 整轮全失败则退避 holdMs，防止坏资产导致忙循环
                    if (failStreak >= names.size) switchAtMs = System.currentTimeMillis()
                    return@post
                }
                failStreak = 0
                previous = current
                current = bmp
                switchAtMs = System.currentTimeMillis()
                totalMs = holdMs
                onWallpaperChange?.invoke(index, names.size)
            }
        }
    }

    private fun decode(name: String): Bitmap? = try {
        val path = "wallpapers/$name"
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
