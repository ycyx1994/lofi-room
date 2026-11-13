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

    /** #14 自动轮播开关（默认关，由面板「背景」页签控制） */
    var autoRotate = false
    /** #14 启动时显示的壁纸序号（默认 0 = 城市夜景） */
    var startIndex = 0

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private var names: List<String> = emptyList()
    private var index = 0
    private var current: Bitmap? = null
    private var previous: Bitmap? = null
    private var switchAtMs = -1L
    private var totalMs = 0L

    private val fadeMs = 2000L // #13 稍长的溶解 + smoothstep 缓动，过渡更柔
    private val holdMs = 600_000L // #14 自动切换间隔 10 分钟
    private val kenBurnsMs = 60_000L // 推镜时长（与切换间隔解耦）
    private var dirCurrent = 1f // #13 推镜方向逐张交替（1=推近，-1=拉远）
    private var dirPrev = 1f
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
            if (switchAtMs > 0 && autoRotate && elapsed > holdMs) advance() // #14 开关打开才自动换
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

    val currentIndex: Int get() = index
    val count: Int get() = names.size

    /** #17 壁纸文件名（供面板缩略图解码，与轮播顺序一致） */
    fun nameAt(i: Int): String? = names.getOrNull(i)

    /** #14 手动切到指定壁纸（溶解过渡），并重置自动切换计时 */
    fun show(target: Int) {
        if (names.isEmpty() || target !in names.indices || advancing || target == index) return
        advancing = true
        val targetName = names[target]
        executor.execute {
            val bmp = decode(targetName)
            post {
                advancing = false
                if (!running || bmp == null) return@post
                previous = current
                current = bmp
                dirPrev = dirCurrent
                dirCurrent = -dirCurrent
                index = target
                switchAtMs = System.currentTimeMillis()
                totalMs = kenBurnsMs
                onWallpaperChange?.invoke(index, names.size)
            }
        }
    }

    private fun loadList() {
        executor.execute {
            // #9 目录列举与首图解码都放后台线程，避免主线程磁盘 I/O
            val found = (context.assets.list("wallpapers") ?: emptyArray()).sorted()
            // #9 跳过排在前面的坏文件，找到第一张能解码的（从 startIndex 起找，支持记忆的默认图）
            var start = startIndex.coerceIn(0, (found.size - 1).coerceAtLeast(0))
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
                totalMs = kenBurnsMs
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
                dirPrev = dirCurrent
                dirCurrent = -dirCurrent // #13 逐张交替推近/拉远，避免每张同节奏
                switchAtMs = System.currentTimeMillis()
                totalMs = kenBurnsMs
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

        // #13 smoothstep 缓动的溶解比例（两端慢、中间快，比线性自然）
        val p = (elapsed.toFloat() / fadeMs).coerceIn(0f, 1f)
        val eased = p * p * (3f - 2f * p)

        // 旧图淡出：#13 保持自己的推镜轨迹继续漂移，不再跳回基准缩放
        previous?.let {
            val prevProgress = 1f + elapsed.toFloat() / totalMs
            drawBitmap(canvas, it, 1f - eased, zoomFor(prevProgress, dirPrev))
            if (elapsed > fadeMs) {
                if (it !== current) it.recycle()
                previous = null
            }
        }

        // 新图淡入 + Ken Burns 推镜
        val alpha = if (previous == null) 1f else eased
        val progress = (elapsed.toFloat() / totalMs).coerceIn(0f, 1f)
        drawBitmap(canvas, bmp, alpha, zoomFor(progress, dirCurrent))
    }

    /** #13 Ken Burns 缩放：dir=1 从 1.02 推近到 1.08，dir=-1 反向拉远 */
    private fun zoomFor(progress: Float, dir: Float): Float =
        if (dir > 0) 1.02f + 0.06f * progress else 1.08f - 0.06f * progress

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
