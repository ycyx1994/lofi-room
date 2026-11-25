package com.lofiroom.tv.ui

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.lofiroom.tv.custom.CustomLibrary
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

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

    private val fadeMs = 2000L // #13 稍长的溶解 + smoothstep 缓动，过渡更柔
    private val holdMs = 900_000L // #14 自动切换间隔 15 分钟
    private val kenBurnsMs = 60_000L // 推镜时长（与切换间隔解耦）
    private var dirCurrent = 1f // #13 推镜方向逐张交替（1=推近，-1=拉远）
    private var dirPrev = 1f
    private var zoomStart = KEN_BURNS_ZOOM_MIN // #22 当前图出现时的缩放（接续上一张此刻的缩放，切换无跳变）
    private var prevZoomStart = KEN_BURNS_ZOOM_MIN // #22 旧图被换下时的起始缩放
    private var prevBaseP = 0f // #22 旧图被换下时的推镜进度（不再假设走满 60s）
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile
    private var running = true
    private var advancing = false // #1 防重入：解码未回前不再重复触发 advance
    private var pendingShow = -1 // #24 应用竞态补救：目标暂存，列表同步/溶解收尾后补发
    private var failStreak = 0 // 连续解码失败计数，完整一轮无一成功则退避
    private var frameCount = 0L
    /** 帧回调是否已排队：start() 幂等的守卫，重复调用不再挂第二条帧循环 */
    private var framePending = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            framePending = false // 本帧已被 Choreographer 消费，重投前先复位（stop() 才能真停）
            if (!running) return
            val elapsed = if (switchAtMs > 0) System.currentTimeMillis() - switchAtMs else 0L
            if (switchAtMs > 0 && autoRotate && elapsed > holdMs) advance() // #14 开关打开才自动换
            // #2 淡入淡出期全帧率；推镜段隔帧 30fps，降低低端盒子 GPU 填充压力
            // P3-17 静止段（推镜 60s 走完后到自动切换前）画面不变，不再每帧空转重绘
            frameCount++
            val inFade = switchAtMs > 0 && elapsed < fadeMs
            val pushing = switchAtMs > 0 && elapsed < kenBurnsMs
            when {
                inFade || previous != null -> invalidate()
                pushing && frameCount % 2 == 0L -> invalidate()
            }
            // P2-1 只有真在画（溶解/推镜）才全帧率跟手；静止段改延后唤醒，别让 Choreographer 60 次/秒空转
            if (inFade || previous != null || pushing) postFrame() else postIdleFrame(elapsed)
        }
    }

    /** 重投下一帧（唯一入口）：已在队列里就不重复 post，避免多次 start() 叠出多条帧循环 */
    private fun postFrame() {
        if (framePending) return
        framePending = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /**
     * P2-1 静止段（溶解/推镜都走完）画面不变：不再 60fps 空转唤醒，改延后到下个有意义事件再醒。
     * autoRotate 待触发则睡到到点附近（醒来 doFrame 顶部会 advance()）；无定时事件只留兜底心跳。
     * stop() 的 removeFrameCallback 对延后回调同样生效，不会漏停。
     */
    private fun postIdleFrame(elapsed: Long) {
        if (framePending) return
        val delayMs = when {
            switchAtMs > 0 && autoRotate && elapsed < holdMs ->
                (holdMs - elapsed).coerceIn(IDLE_MIN_MS, IDLE_MAX_MS) // 睡到 autoRotate 到点附近
            else -> IDLE_MAX_MS                                      // 无定时事件：只留兜底心跳
        }
        framePending = true
        Choreographer.getInstance().postFrameCallbackDelayed(frameCallback, delayMs)
    }

    /** 立即把帧循环拉回全帧率：撤销可能排队的静止段延后回调，避免新动画等延后帧才起步（解码异步，触发帧可能已 idle） */
    private fun kickFrameNow() {
        if (!running) return
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        framePending = false
        postFrame()
    }

    /** 启动轮播：幂等，已在跑则只补一次 post；stop() 之后调用可重新拉起来 */
    fun start() {
        running = true
        if (names.isEmpty()) loadList()
        postFrame()
    }

    /** 停止轮播：必须真正把帧回调从 Choreographer 摘掉，否则 doFrame 会一直跑（只是不画） */
    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        framePending = false
    }

    /**
     * 系统内存紧张（ComponentCallbacks2.onTrimMemory / onLowMemory）时回收旧图层。
     * 只在非溶解期回收：溶解期 previous 正被 onDraw 逐帧引用，此时 recycle 会让正在绘制的位图失效
     * （Canvas 抛 "trying to use a recycled bitmap"），所以要等 switchAtMs 起 fadeMs 走完再收。
     * 回收动作一律 post 到主线程：位图引用由绘制线程持有，解码线程不能直接动它。
     */
    fun trimMemory() {
        post {
            val elapsed = if (switchAtMs >= 0) System.currentTimeMillis() - switchAtMs else Long.MAX_VALUE
            if (elapsed <= fadeMs) return@post // 溶解进行中：旧图层还在屏上，下一轮 onDraw 会自己收
            val old = previous ?: return@post
            previous = null
            if (old !== current) old.recycle()
        }
    }

    val currentIndex: Int get() = index
    val count: Int get() = names.size

    /** #17 壁纸文件名（供面板缩略图解码，与轮播顺序一致；自定义项带 "custom/" 前缀） */
    fun nameAt(i: Int): String? = names.getOrNull(i)

    /** P0：onDetachedFromWindow 后线程池已 shutdown，裸 execute 抛 RejectedExecutionException 会崩溃；
     *  统一走这里，提交失败返回 false 由调用方复位标志 */
    private fun submit(block: Runnable): Boolean {
        if (executor.isShutdown) return false
        return try {
            executor.execute(block)
            true
        } catch (e: RejectedExecutionException) {
            false
        }
    }

    /** #23 自定义壁纸增删后重载列表：按文件名保住当前显示，被删则切第一张 */
    fun reload() {
        submit {
            val found = CustomLibrary.wallpaperNames
            post {
                if (!running) return@post
                val curName = names.getOrNull(index)
                names = found
                if (found.isEmpty()) return@post
                val newIndex = curName?.let { found.indexOf(it) } ?: -1
                if (newIndex >= 0) {
                    index = newIndex // 位置平移但画面不变，只同步面板金框
                    onWallpaperChange?.invoke(index, names.size)
                } else {
                    show(0, force = true) // 当前图被删：溶解到第一张
                }
                replayPending() // #24 列表已同步：补发暂存的应用意图
            }
        }
    }

    /** #14 手动切到指定壁纸（溶解过渡），并重置自动切换计时；force 时允许重画同一张（reload 兜底） */
    fun show(target: Int, force: Boolean = false) {
        // #24 应用竞态补救：上传刚落盘时 names 还是旧列表，目标越界不再静默丢弃——暂存后等 reload 补发
        if (target !in names.indices) {
            pendingShow = target
            reload()
            return
        }
        if (advancing) {
            pendingShow = target // 溶解进行中：本轮收尾 replayPending 补发
            return
        }
        if (!force && target == index) {
            pendingShow = -1 // 已经是这张：最新意图即取消更早的暂存
            return
        }
        pendingShow = -1 // 成交的 show 是最新意图，作废更早的暂存
        advancing = true
        val targetName = names[target]
        val submitted = submit {
            val bmp = decode(targetName)
            post {
                advancing = false
                // #24 收尾时若已有更新的应用意图，本次结果作废改道（新图未上屏可直接回收）
                val t = pendingShow
                if (t == target) pendingShow = -1
                if (running && t >= 0 && t != target && t in names.indices) {
                    if (bmp != null && bmp !== current && bmp !== previous) bmp.recycle()
                    pendingShow = -1
                    show(t)
                    return@post
                }
                if (!running) {
                    if (bmp != null && bmp !== current && bmp !== previous) bmp.recycle() // 放弃：未上屏即回收，防泄漏
                    return@post
                }
                if (bmp == null) {
                    onWallpaperChange?.invoke(index, names.size) // P1：解码失败回校正回调，撤销调用方的乐观置位
                    return@post
                }
                // #22 推镜接续：旧图从真实进度续行，新图从旧图此刻缩放出发，消除切换跳变
                prevBaseP = ((System.currentTimeMillis() - switchAtMs).coerceAtLeast(0L)
                    .toFloat() / kenBurnsMs).coerceIn(0f, 1f)
                prevZoomStart = zoomStart
                val dropped = previous // #22 快速连切时回收被挤掉的旧图层，防位图堆积
                previous = current
                current = bmp
                if (dropped != null && dropped !== bmp) dropped.recycle()
                dirPrev = dirCurrent
                dirCurrent = -dirCurrent
                zoomStart = zoomFor(prevBaseP, dirPrev, prevZoomStart)
                index = target
                switchAtMs = System.currentTimeMillis()
                onWallpaperChange?.invoke(index, names.size)
                kickFrameNow() // 新溶解已起：立刻拉回全帧率（帧循环可能停在静止段 idle），别等延后帧才起步
            }
        }
        if (!submitted) advancing = false // P0：View 已销毁提交失败，复位防重入标志，避免后续 show 永久卡死
    }

    /** #24 补发暂存的应用意图：列表同步后把用户点过的目标切过去 */
    private fun replayPending() {
        val t = pendingShow
        pendingShow = -1 // 无论成败都清空：目标仍不在列表（已删除/非法）即作废，防旧意图在后续 reload 时误切
        if (t < 0 || t !in names.indices) {
            if (t >= 0) onWallpaperChange?.invoke(index, names.size) // P1：应用意图被作废时同步校正，防金框停在不存在的目标上
            return // 没有暂存或列表还没到位
        }
        show(t)
    }

    private fun loadList() {
        submit {
            // #9 目录列举与首图解码都放后台线程，避免主线程磁盘 I/O
            // #23 走合并库：内置 assets + 自定义 custom/（命名约定见 CustomLibrary）
            val found = CustomLibrary.wallpaperNames
            // #9 跳过排在前面的坏文件，找到第一张能解码的（从 startIndex 起找，支持记忆的默认图）
            var start = startIndex.coerceIn(0, (found.size - 1).coerceAtLeast(0))
            var bmp: Bitmap? = null
            while (start < found.size && bmp == null) {
                bmp = decode(found[start])
                if (bmp == null) start++
            }
            post {
                if (!running) {
                    if (bmp != null && bmp !== current && bmp !== previous) bmp.recycle() // 放弃：未上屏即回收，防泄漏
                    return@post
                }
                names = found
                if (names.isEmpty()) return@post
                // #9 无论成败都武装定时器，否则 doFrame 的 switchAtMs>0 守卫会让 advance 永不触发
                switchAtMs = System.currentTimeMillis()
                replayPending() // #24 首载完成：补发启动期间暂存的应用意图
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
        val submitted = submit {
            val bmp = decode(nextName)
            post {
                advancing = false // #1 无论成败都复位标志
                index = next // #9 失败也推进，避免卡在同一个坏文件上
                // #24 自动轮播收尾时若已有用户应用意图，让位改道（新图未上屏可直接回收）
                val t = pendingShow
                if (running && t >= 0 && t in names.indices) {
                    if (bmp != null && bmp !== current && bmp !== previous) bmp.recycle()
                    pendingShow = -1
                    show(t)
                    return@post
                }
                if (!running) {
                    if (bmp != null && bmp !== current && bmp !== previous) bmp.recycle() // 放弃：未上屏即回收，防泄漏
                    return@post
                }
                if (bmp == null) {
                    failStreak++
                    // #9 整轮全失败则退避 holdMs，防止坏资产导致忙循环
                    if (failStreak >= names.size) switchAtMs = System.currentTimeMillis()
                    onWallpaperChange?.invoke(index, names.size) // P1：失败推进也同步外部状态，防金框/基准漂移
                    return@post
                }
                failStreak = 0
                // #22 推镜接续：同 show()
                prevBaseP = ((System.currentTimeMillis() - switchAtMs).coerceAtLeast(0L)
                    .toFloat() / kenBurnsMs).coerceIn(0f, 1f)
                prevZoomStart = zoomStart
                val dropped = previous // #22 快速连切时回收被挤掉的旧图层
                previous = current
                current = bmp
                if (dropped != null && dropped !== bmp) dropped.recycle()
                dirPrev = dirCurrent
                dirCurrent = -dirCurrent
                zoomStart = zoomFor(prevBaseP, dirPrev, prevZoomStart)
                switchAtMs = System.currentTimeMillis()
                onWallpaperChange?.invoke(index, names.size)
                kickFrameNow() // advance 由 doFrame 触发但解码异步，收尾时触发帧已 idle：立刻拉回全帧率起新溶解
            }
        }
        if (!submitted) advancing = false // P0：同 show()，提交失败复位防重入标志
    }

    private fun decode(name: String): Bitmap? {
        // #2 按屏幕尺寸算目标宽，CustomLibrary 负责抽样与自定义图 EXIF 转正。
        // Ken Burns 最大缩放 1.08（见 zoomFor / KEN_BURNS_ZOOM_MAX）→ 只需 1.08 倍余量；
        // 旧值 1.25 是无谓上采样：1080p 电视按 1.25 解出 2400px 宽（2400×1350 ARGB ≈ 12.9MB/张），
        // current + previous 常驻 ≈ 26MB，1GB RAM 的盒子上有 OOM 风险，而放大不增加任何清晰度。
        val dm = context.resources.displayMetrics
        val targetW = (maxOf(dm.widthPixels, dm.heightPixels) * KEN_BURNS_ZOOM_MAX).toInt()
        return CustomLibrary.decodeWallpaper(name, targetW)
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

        // 旧图淡出：#22 从被换下时的真实进度沿自身轨迹续行，与新图此刻缩放一致
        previous?.let {
            // P3-18 续行进度夹取，防旧图层推镜越过终点外插（与新图 progress 口径一致）
            val prevProgress = (prevBaseP + elapsed.toFloat() / kenBurnsMs).coerceIn(0f, 1f)
            drawBitmap(canvas, it, 1f - eased, zoomFor(prevProgress, dirPrev, prevZoomStart))
            if (elapsed > fadeMs) {
                if (it !== current) it.recycle()
                previous = null
            }
        }

        // 新图淡入 + Ken Burns 推镜（#22 从旧图此刻缩放出发，切换无跳变）
        val alpha = if (previous == null) 1f else eased
        val progress = (elapsed.toFloat() / kenBurnsMs).coerceIn(0f, 1f)
        drawBitmap(canvas, bmp, alpha, zoomFor(progress, dirCurrent, zoomStart))
    }

    /** #22 推镜：从出现时缩放线性驶向本张极值（dir=1 推近至 1.08，dir=-1 拉远至 1.02） */
    private fun zoomFor(progress: Float, dir: Float, start: Float): Float {
        val end = if (dir > 0) KEN_BURNS_ZOOM_MAX else KEN_BURNS_ZOOM_MIN
        return start + (end - start) * progress
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 自己吃系统内存压力回调：接线（MainActivity）不必再记得调 trimMemory()，漏接也不会退化
        context.applicationContext.registerComponentCallbacks(memoryCallbacks)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop() // 摘掉帧回调，别让 Choreographer 继续空转
        context.applicationContext.unregisterComponentCallbacks(memoryCallbacks)
        executor.shutdown()
    }

    /** 系统内存紧张时回收旧图层（Android 会把 onTrimMemory/onLowMemory 广播到已注册的 callbacks） */
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) = trimMemory()
        override fun onLowMemory() = trimMemory()
        override fun onConfigurationChanged(newConfig: Configuration) {}
    }

    private companion object {
        /**
         * Ken Burns 推镜的最大缩放 = zoomFor() 里 dir>0 的终点（推近方向）。
         * 解码余量取它本身：1.08 倍屏幕宽时，最大缩放下正好 1 屏像素 ↔ 1 源像素，
         * 再多就是纯上采样——不增加清晰度，只让 Bitmap 字节数翻倍。
         * （旧值 1.25 在 1080p 电视上解出 2400px 宽 ≈ 12.9MB/张，双图层常驻 ≈ 26MB。）
         */
        const val KEN_BURNS_ZOOM_MAX = 1.08f
        /** 拉远方向终点（dir<0），只用于推镜插值起点，不参与解码尺寸 */
        const val KEN_BURNS_ZOOM_MIN = 1.02f

        /** P2-1 静止段延后唤醒下限/上限（ms）：防到点瞬间 spin，也防睡眠过久漏看状态变化 */
        const val IDLE_MIN_MS = 200L
        const val IDLE_MAX_MS = 30_000L
    }
}
