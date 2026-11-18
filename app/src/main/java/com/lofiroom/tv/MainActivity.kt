package com.lofiroom.tv

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.ComposeView
import com.lofiroom.tv.audio.AudioEngine
import com.lofiroom.tv.audio.VisualizerTap
import com.lofiroom.tv.custom.CustomLibrary
import com.lofiroom.tv.data.Caption
import com.lofiroom.tv.data.Playlist
import com.lofiroom.tv.data.Track
import com.lofiroom.tv.net.LanServer
import com.lofiroom.tv.net.QrCode
import com.lofiroom.tv.net.ReleaseInfo
import com.lofiroom.tv.net.UpdateChecker
import com.lofiroom.tv.net.UpdateInstaller
import com.lofiroom.tv.net.UpdateScheduler
import com.lofiroom.tv.net.UpdateState
import com.lofiroom.tv.ui.LofiPanel
import com.lofiroom.tv.ui.PanelTab
import com.lofiroom.tv.ui.VisualizerView
import com.lofiroom.tv.ui.WallpaperView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tap: VisualizerTap
    private lateinit var engine: AudioEngine

    private lateinit var wallpaper: WallpaperView
    private lateinit var visualizer: VisualizerView
    private lateinit var label: TextView
    private lateinit var brand: TextView
    private lateinit var captionLabel: TextView
    private lateinit var captionCn: TextView
    private lateinit var captionEn: TextView
    private lateinit var trackTitle: TextView
    private lateinit var trackSub: TextView
    private lateinit var clock: TextView
    private lateinit var hint: TextView
    private lateinit var volOsd: TextView
    private lateinit var panelWrap: FrameLayout
    private lateinit var panelCompose: ComposeView

    // #compose-tv-migration 面板状态桥（Activity 持有 → Compose 重组）
    private val panelTabState = mutableStateOf(PanelTab.TRACKS)
    private val playingIndexState = mutableIntStateOf(0)
    private val playingState = mutableStateOf(true)
    private val appliedWallState = mutableIntStateOf(0)
    private val autoRotateState = mutableStateOf(false)
    private val thumbs = mutableStateMapOf<String, Bitmap>()
    private val tabsFocus = FocusRequester()
    private val panelOpenTick = mutableIntStateOf(0)

    // #23 自定义中心：局域网 H5 管理服务 + 二维码态
    private var lanServer: LanServer? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null // #23 网络变化 → 刷新二维码/引导
    private val qrBitmapState = mutableStateOf<Bitmap?>(null)
    private val serverUrlState = mutableStateOf("")
    private val customTracks = mutableStateOf<List<Track>>(emptyList())

    // ── 应用内更新（多源检测 + 断点续传下载 + 三道闸 + 静默安装） ─────────────
    private val updateLabelState = mutableStateOf("")   // 按钮文案：检查更新 / 下载 vX.Y / 取消下载
    private val updateStatusState = mutableStateOf("")  // 状态行：已是最新 / 发现新版 / 下载中 N% / 失败原因
    private val updateNotesState = mutableStateOf("")   // 发现新版/已更新时 release notes 前 2 行摘要
    private val updateBtnFocusState = mutableStateOf(false) // 按钮焦点态：CUSTOM 页签 OK 键让位
    private var updateState: UpdateState = UpdateState.Idle  // 更新状态机（B5）
    private val updateScheduler = UpdateScheduler()          // A2 节流/退避/网络合并（纯逻辑）
    private var latestRelease: ReleaseInfo? = null      // 有新版时非空（按钮切为"下载"，含下载源列表）
    private var downloadCancelled = false               // 下载线程取消钩子（再按按钮置 true）
    private var activityResumed = false                 // 仅前台时跑退避重试
    private val updatePrefs by lazy { getSharedPreferences("updates", MODE_PRIVATE) }
    private var qrOverlay: View? = null // OK 全屏二维码遮罩（CUSTOM 页签）

    private lateinit var splash: FrameLayout
    private lateinit var splashLogo: ImageView

    private var firstWallReady = false // #12 首张壁纸就绪后才撤启动遮罩
    private var splashDismissed = false
    private val bootAtMs = System.currentTimeMillis()

    private val prefs by lazy { getSharedPreferences("lofi_prefs", MODE_PRIVATE) }

    private var captionIndex = 0
    private var lastBackPressedMs = 0L // #3 双击 BACK 退出
    private val fontSerif: Typeface by lazy { loadFont("fonts/CormorantGaramond.ttf", Typeface.SERIF) } // #5
    private val fontSans: Typeface by lazy { loadFont("fonts/Jost.ttf", Typeface.SANS_SERIF) }
    private val hintFadeAction = Runnable { fadeOut(hint) }

    // #11 触摸手势（翻页式）：轻点=播放/暂停，左右滑=下/上一首，上下滑=音量，长按=面板
    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                engine.togglePlay()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                togglePanel()
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent,
                velocityX: Float, velocityY: Float
            ): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                if (abs(dx) > abs(dy)) {
                    // 翻页习惯：左滑=下一首，右滑=上一首（与遥控器 ←→ 方向相反）
                    if (dx < 0) engine.next() else engine.prev()
                } else {
                    changeVolume(if (dy < 0) +0.05f else -0.05f)
                }
                return true
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility") // 手势由 GestureDetector 处理，无需 performClick
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()
        setContentView(R.layout.activity_main)
        hideSystemBars()
        bindViews()
        applyFonts()
        startClock()

        // #23 自定义库先于引擎初始化（AudioEngine 构建曲单读 CustomLibrary）
        CustomLibrary.init(this)
        customTracksStateSync()

        tap = VisualizerTap()
        engine = AudioEngine(this, tap)
        visualizer.tap = tap
        engine.listener = object : AudioEngine.Listener {
            override fun onTrackChanged(track: Track, index: Int, total: Int) {
                bindTrack(track, index, total)
                playingIndexState.intValue = index
            }

            override fun onPlayingChanged(playing: Boolean) {
                // 静音时 VisualizerView 自行衰减
                playingState.value = playing
            }
        }
        bindTrack(CustomLibrary.tracks[0], 0, CustomLibrary.tracks.size)

        // #14 记忆自动切换开关（默认关）与选中的壁纸（默认城市夜景）
        wallpaper.autoRotate = prefs.getBoolean("auto_rotate", false)
        wallpaper.startIndex = prefs.getInt("wallpaper_index", 0)
        autoRotateState.value = wallpaper.autoRotate
        wallpaper.onWallpaperChange = { index, _ ->
            rotateCaption(index)
            appliedWallState.intValue = index // #compose-tv-migration 刷新"已应用"金框
            if (!firstWallReady) { // #12 首图就绪：满足最短展示时长后撤遮罩
                firstWallReady = true
                val wait = (1100 - (System.currentTimeMillis() - bootAtMs)).coerceAtLeast(0)
                handler.postDelayed({ dismissSplash() }, wait)
            }
        }
        wallpaper.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event); true }
        wallpaper.start()
        rotateCaption(0)

        setupPanel()
        scheduleHintFade()

        // #23 库变更（H5 上传/删除/改名/移动）→ 引擎重建曲单 + 壁纸重载 + 缩略图补温
        CustomLibrary.onLibraryChanged = {
            engine.refreshItems()
            wallpaper.reload()
            customTracksStateSync()
            handler.post { warmThumbs() }
        }

        // #23 局域网管理服务：8788 起，二维码 = http://<ip>:<port>/t/<token>/
        startLanServer()

        // 应用内更新：A2 调度检测（启动档 3h 新鲜度/退避/网络合并由 UpdateScheduler 保证）
        updateScheduler.checkedAt = updatePrefs.getLong("checked_at", 0L)
        val updateDir = getExternalFilesDir(null) ?: filesDir
        UpdateChecker.cleanupStale(updateDir, currentVersionName()) // 清过期 APK/断点残留
        if (!detectJustUpdated()) checkUpdate(UpdateScheduler.Trigger.STARTUP)

        // #12 启动遮罩：字标淡入 + 兜底撤除（防止首图解码失败时永久遮挡）
        splashLogo.animate().alpha(1f).setDuration(380).start()
        handler.postDelayed({ dismissSplash() }, 2600)
    }

    // ── 初始化 ─────────────────────────────────────────────

    private fun bindViews() {
        wallpaper = findViewById(R.id.wallpaper)
        visualizer = findViewById(R.id.visualizer)
        label = findViewById(R.id.label)
        brand = findViewById(R.id.brand)
        captionLabel = findViewById(R.id.caption_label)
        captionCn = findViewById(R.id.caption_cn)
        captionEn = findViewById(R.id.caption_en)
        trackTitle = findViewById(R.id.track_title)
        trackSub = findViewById(R.id.track_sub)
        clock = findViewById(R.id.clock)
        hint = findViewById(R.id.hint)
        volOsd = findViewById(R.id.vol_osd)
        panelWrap = findViewById(R.id.panel_wrap)
        panelWrap.isFocusable = false // 焦点只属于 Compose 面板内部
        panelWrap.isFocusableInTouchMode = false
        panelCompose = findViewById(R.id.panel_compose)
        splash = findViewById(R.id.splash)
        splashLogo = findViewById(R.id.splash_logo)
    }

    private fun applyFonts() {
        brand.typeface = fontSerif
        trackTitle.typeface = fontSerif
        captionCn.typeface = fontSans
        label.typeface = fontSans
        captionLabel.typeface = fontSans
        captionEn.typeface = fontSans
        trackSub.typeface = fontSans
        clock.typeface = fontSans
        hint.typeface = fontSans
        volOsd.typeface = fontSans
        fixVolOsdWidth()
    }

    /** 音量 OSD 固定宽度：取全部可能文案（VOL 0%..100%）中最宽的真实测量宽，
     *  消除调节音量时盒子宽度变化带来的轻微抖动（文字左锚定后起始位置恒定） */
    private fun fixVolOsdWidth() {
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val widest = (0..100).maxOf { pct ->
            volOsd.text = String.format(Locale.ENGLISH, "VOL %d%%", pct)
            volOsd.measure(spec, spec)
            volOsd.measuredWidth
        }
        volOsd.layoutParams = volOsd.layoutParams.apply { width = widest }
        volOsd.text = ""
    }

    private fun loadFont(path: String, fallback: Typeface): Typeface = try {
        Typeface.createFromAsset(assets, path)
    } catch (e: Exception) {
        fallback
    }

    /** 全屏绘制到刘海/状态栏安全区，避免手机横屏左侧出现系统黑边。 */
    private fun configureWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode =
                if (Build.VERSION.SDK_INT >= 30) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            window.attributes = attrs
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (window.peekDecorView() == null) return // DecorView 未创建时防护，避免 MIUI 上 NPE
            try {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let {
                    it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } catch (e: Exception) {
                // MIUI 部分机型 insetsController 未就绪，忽略并在 onWindowFocusChanged 重试
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    // ── 文字联动 ────────────────────────────────────────────

    private fun bindTrack(track: Track, index: Int, total: Int) {
        fadeSwap(trackTitle, track.title)
        trackSub.text = String.format(
            Locale.ENGLISH, "%s · %02d/%02d", track.sub, index + 1, total
        )
    }

    private fun rotateCaption(wallIndex: Int) {
        captionIndex = wallIndex % Playlist.captions.size
        val c: Caption = Playlist.captions[captionIndex]
        fadeSwap(captionLabel, c.label)
        fadeSwap(captionCn, c.cn)
        fadeSwap(captionEn, c.en)
    }

    /** 淡出旧文案再淡入新文案 */
    private fun fadeSwap(view: TextView, text: String) {
        if (view.text == text) return // #7 同文案不重复动画，消除启动双重淡入
        view.animate().alpha(0f).setDuration(220).withEndAction {
            view.text = text
            view.animate().alpha(1f).setDuration(420).start()
        }.start()
    }

    private fun fadeOut(view: View) {
        view.animate().alpha(0f).setDuration(900).start()
    }

    private fun showHintNow() {
        handler.removeCallbacks(hintFadeAction)
        hint.animate().cancel()
        hint.alpha = 1f
    }

    private fun scheduleHintFade(delayMs: Long = 3000) {
        handler.removeCallbacks(hintFadeAction)
        handler.postDelayed(hintFadeAction, delayMs)
    }

    /** #12 启动遮罩淡出：字标随遮罩一起退场，露出壁纸场景 */
    private fun dismissSplash() {
        if (splashDismissed) return
        splashDismissed = true
        splash.animate().alpha(0f).setDuration(650).withEndAction {
            splash.visibility = View.GONE
        }.start()
    }

    // ── 时钟 ───────────────────────────────────────────────

    private fun startClock() {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val monthFmt = SimpleDateFormat("MMMM", Locale.ENGLISH) // #16 主页标题用英文月份
        val tick = object : Runnable {
            override fun run() {
                clock.text = fmt.format(Date())
                brand.text = monthFmt.format(Date()).uppercase(Locale.ENGLISH)
                handler.postDelayed(this, 20_000)
            }
        }
        tick.run()
    }

    // ── 播放列表面板（Compose）────────────────────────────────

    /** #23 合并曲目快照进 Compose 状态（TrackList 直接读它，不再静态引用 Playlist） */
    private fun customTracksStateSync() {
        customTracks.value = CustomLibrary.tracks
    }

    /** #23 启动局域网 H5 服务并生成二维码；无局域网 IP 时留空，CUSTOM 页签显示引导 */
    private fun startLanServer() {
        val token = prefs.getString("lan_token", null) ?: UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString("lan_token", token).apply()
        val server = LanServer(this, lanHost, token)
        lanServer = server
        if (!server.start()) return
        refreshQrState()
        // #23 网络变化即刷新：断网 → CUSTOM 页签引导；换 IP → 二维码换新地址
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { handler.post { refreshQrState(); scheduleNetworkCheck() } }
            override fun onLost(network: Network) { handler.post { refreshQrState() } }
        }
        netCallback = cb
        cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
    }

    /** #23 IP/服务变化后重算二维码（网络切换可再调） */
    private fun refreshQrState() {
        val server = lanServer ?: return
        val ip = LanServer.localIpv4()
        if (ip == null) {
            serverUrlState.value = ""
            qrBitmapState.value = null
            return
        }
        val url = server.url(ip)
        serverUrlState.value = url
        // zxing 编码很快，但仍放后台避免主线程抖动
        Thread {
            val bmp = QrCode.encode(url, 640)
            handler.post { qrBitmapState.value = bmp }
        }.start()
    }

    /** #23 LanServer 主线程桥：状态读 Compose 态，动作切主线程执行 */
    private val lanHost = object : LanServer.Host {
        override fun currentPlaying(): Boolean = playingState.value
        override fun currentTrackIndex(): Int = playingIndexState.intValue
        override fun currentWallpaperIndex(): Int = appliedWallState.intValue
        override fun applyWallpaper(index: Int) {
            // H5 高亮修复：应用意图先同步落状态/偏好——H5 apply 返回后立即读 state 就是新图，
            // 无需等异步解码回调（原先回调才更新 → 刷新读到旧值 → 高亮要点两次）
            appliedWallState.intValue = index
            prefs.edit().putInt("wallpaper_index", index).apply()
            // 越界目标不再静默丢弃：交给 WallpaperView #24 暂存补发（刚上传的图名单未同步）
            runOnUiThread { wallpaper.show(index) }
        }

        override fun playTrack(index: Int) {
            runOnUiThread {
                if (index in customTracks.value.indices) engine.playIndex(index)
            }
        }

        override fun onManagerOpened() {
            // 扫码进入管理页：自动收起二维码遮罩与面板，露出全屏背景，手机操控时即时预览效果
            runOnUiThread {
                if (qrOverlay != null) toggleQrOverlay()
                if (panelWrap.visibility == View.VISIBLE) togglePanel()
            }
        }
    }

    /** #23 OK 在 CUSTOM 页签上放大二维码（扫码距离更远），再按 OK/BACK 收起；返回是否实际发生了切换 */
    private fun toggleQrOverlay(): Boolean {
        val content = findViewById<FrameLayout>(android.R.id.content)
        val current = qrOverlay
        if (current != null) {
            content.removeView(current)
            qrOverlay = null
            return true
        }
        val bmp = qrBitmapState.value ?: return false // QR 未就绪：没得可切，消费交给上层决定
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xF2120E0B.toInt())
        }
        overlay.addView(ImageView(this@MainActivity).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(420), dp(420)))
        overlay.addView(TextView(this@MainActivity).apply {
            text = serverUrlState.value
            typeface = fontSans
            setTextColor(0xFFC8BCA9.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(24) })
        overlay.addView(TextView(this@MainActivity).apply {
            text = getString(R.string.qr_overlay_hint)
            typeface = fontSans
            setTextColor(0xFFD8B57E.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        overlay.isClickable = true
        overlay.isFocusable = true
        overlay.setOnClickListener { toggleQrOverlay() }
        content.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        qrOverlay = overlay
        return true
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 背景格删除确认弹窗：>=0 = 待删除的合并下标（长按 OK 触发） */
    private val pendingDeleteWall = mutableIntStateOf(-1)

    private fun setupPanel() {
        panelCompose.setContent {
            LofiPanel(
                tab = panelTabState.value,
                onTabChange = { panelTabState.value = it },
                tracks = customTracks.value,
                playingIndex = playingIndexState.intValue,
                playing = playingState.value,
                onTrackClick = { i ->
                    engine.playIndex(i)
                    togglePanel()
                },
                wallCount = wallpaper.count,
                thumbOf = { i -> wallpaper.nameAt(i)?.let { thumbs[it] } },
                appliedIndex = appliedWallState.intValue,
                onWallClick = { i ->
                    if (i in 0 until wallpaper.count) {
                        appliedWallState.intValue = i // 乐观置位：金框立即跟随点击，解码落定后回调校正
                        wallpaper.show(i)
                        prefs.edit().putInt("wallpaper_index", i).apply()
                    }
                },
                pendingDeleteWall = pendingDeleteWall.intValue,
                wallDeletable = { i -> CustomLibrary.isCustomWallpaper(i) },
                onWallDeleteRequest = { pendingDeleteWall.intValue = it },
                onWallDeleteConfirm = {
                    CustomLibrary.removeWallpaper(pendingDeleteWall.intValue) // 失败（内置/越界）也照常关弹窗
                    pendingDeleteWall.intValue = -1
                },
                onWallDeleteDismiss = { pendingDeleteWall.intValue = -1 },
                autoRotate = autoRotateState.value,
                onAutoRotateToggle = {
                    wallpaper.autoRotate = !wallpaper.autoRotate
                    autoRotateState.value = wallpaper.autoRotate
                    prefs.edit().putBoolean("auto_rotate", wallpaper.autoRotate).apply()
                },
                qrBitmap = qrBitmapState.value,
                serverUrl = serverUrlState.value,
                updateActionLabel = updateLabelState.value.ifEmpty { getString(R.string.update_check) },
                updateStatus = updateStatusState.value,
                updateNotes = updateNotesState.value,
                onUpdateAction = { onUpdateAction() },
                onUpdateFocusChange = { updateBtnFocusState.value = it },
                tabsFocus = tabsFocus,
                openTick = panelOpenTick.intValue,
            )
        }
        panelWrap.setOnClickListener { togglePanel() }
        warmThumbs()
    }

    private fun togglePanel() {
        if (panelWrap.visibility == View.VISIBLE) {
            panelWrap.visibility = View.GONE
            showHintNow()
            scheduleHintFade()
        } else {
            panelWrap.visibility = View.VISIBLE
            panelOpenTick.intValue++ // Compose 侧 LaunchedEffect 先请求一次页签焦点
            // A2 面板档检测：10min 新鲜度内由调度器跳过，不产生多余请求
            checkUpdate(UpdateScheduler.Trigger.PANEL_OPEN)
            // 布局完成后再抢一次：visibility 变化后的默认焦点分配可能把焦点落到 panel_wrap
            panelCompose.post {
                runCatching {
                    panelCompose.requestFocus()
                    tabsFocus.requestFocus()
                }
            }
            showHintNow()
        }
    }

    // ── 背景宫格：缩略图 ───────────────────────────────────

    /** #17 小图解码（约 320px 宽，够宫格预览用；#23 经 CustomLibrary 分流内置/自定义） */
    private fun decodeThumb(name: String): Bitmap? = CustomLibrary.decodeWallpaper(name, 320)

    /** #17 预热缩略图缓存（后台解码，完成回填 Compose 状态） */
    private fun warmThumbs() {
        val names = (0 until wallpaper.count).mapNotNull { wallpaper.nameAt(it) }
        if (names.isEmpty()) {
            handler.postDelayed({ warmThumbs() }, 600) // 等壁纸列表载入
            return
        }
        // 库变更（删除）后清掉已移除壁纸的缩略图残留，防止内存驻留与后续串图
        val nameSet = names.toSet()
        handler.post { thumbs.keys.retainAll(nameSet) }
        Thread {
            for (n in names) {
                if (!thumbs.containsKey(n)) decodeThumb(n)?.let { bmp ->
                    handler.post { thumbs[n] = bmp }
                }
            }
        }.start()
    }

    // ── 遥控器 / 按键交互 ────────────────────────────────────

    // ── 应用内更新（多源检测 + 断点续传 + 三道闸 + 静默安装/降级） ─────────

    private fun currentVersionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0"

    /** C4 刚更新完（lastUpdateTime 2 分钟内）：显示"已更新到 vX.Y"+notes，跳过自动检测 */
    private fun detectJustUpdated(): Boolean {
        val now = System.currentTimeMillis()
        val updated = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        if (now - updated !in 0 until 120_000L) return false
        val v = currentVersionName()
        val notes = updatePrefs.getString("notes_for", "")
            ?.takeIf { it == v }?.let { updatePrefs.getString("notes", "") } ?: ""
        updateScheduler.checkedAt = now // 新装版本无需再检
        applyUpdateState(UpdateState.Updated(v, notes))
        return true
    }

    /** 按钮动作：下载中→取消下载；有新版→下载；否则→手动检查 */
    private fun onUpdateAction() {
        when {
            updateState is UpdateState.Downloading -> { // D2 再按 = 取消
                downloadCancelled = true
                applyUpdateState(UpdateState.Idle)
            }
            latestRelease != null -> downloadUpdate(latestRelease!!)
            else -> checkUpdate(UpdateScheduler.Trigger.MANUAL)
        }
    }

    /** A2 检测入口：UpdateScheduler 裁决（3h/10min 新鲜度、退避、防抖、网络合并）；MANUAL 旁路 */
    private fun checkUpdate(trigger: UpdateScheduler.Trigger) {
        val st = updateState
        if (st is UpdateState.Downloading || st is UpdateState.Verifying || st is UpdateState.Installing) return
        when (updateScheduler.begin(trigger)) {
            UpdateScheduler.Decision.SKIP_CHECKING -> {
                if (trigger == UpdateScheduler.Trigger.MANUAL) {
                    updateLabelState.value = getString(R.string.update_checking) // 正在检，复用进行中结果
                }
                return
            }
            UpdateScheduler.Decision.SKIP_BACKOFF -> { scheduleRetry(); return } // 失败退避：到点自动重试
            UpdateScheduler.Decision.SKIP_INTERVAL, UpdateScheduler.Decision.SKIP_FRESH -> return
            UpdateScheduler.Decision.RUN -> Unit
        }
        if (trigger == UpdateScheduler.Trigger.MANUAL) applyUpdateState(UpdateState.Checking)
        Thread {
            val cur = currentVersionName()
            val rel = UpdateChecker.fetchLatest()
            handler.post {
                if (rel == null) {
                    updateScheduler.onFailure()
                    if (trigger == UpdateScheduler.Trigger.MANUAL) {
                        applyUpdateState(UpdateState.Failed(UpdateState.Reason.Check))
                    }
                    scheduleRetry() // 30s/2min/10min/30min 退避序列，仅前台
                    return@post
                }
                updateScheduler.onSuccess() // checkedAt 仅成功时推进
                updatePrefs.edit()
                    .putString("latest", rel.versionName)
                    .putString("notes", rel.notes)
                    .putString("notes_for", rel.versionName)
                    .putLong("checked_at", updateScheduler.checkedAt)
                    .apply()
                // 已更新终态不被后台检测覆盖（MANUAL 除外）
                if (updateState is UpdateState.Updated && trigger != UpdateScheduler.Trigger.MANUAL) return@post
                if (UpdateChecker.hasUpdate(cur, rel.versionName)) {
                    latestRelease = rel
                    applyUpdateState(UpdateState.Available(rel.versionName, rel.notes))
                } else {
                    latestRelease = null
                    if (trigger == UpdateScheduler.Trigger.MANUAL || updateState !is UpdateState.Idle) {
                        applyUpdateState(UpdateState.UpToDate(cur))
                    }
                }
            }
        }.start()
    }

    /** 失败退避重试（30s/2min/10min/30min）：仅前台执行，回后台挂起不丢 */
    private val retryRunnable = Runnable {
        if (activityResumed) checkUpdate(UpdateScheduler.Trigger.RETRY) else scheduleRetry()
    }

    private fun scheduleRetry() {
        handler.removeCallbacks(retryRunnable)
        val delay = updateScheduler.retryDelayMs() ?: return
        handler.postDelayed(retryRunnable, delay)
    }

    /** 网络恢复检测（A2）：调度器内 2s 窗口合并抖动 + 5s 最小间隔 */
    private val networkCheckRunnable = Runnable { checkUpdate(UpdateScheduler.Trigger.NETWORK) }

    private fun scheduleNetworkCheck() {
        if (updateScheduler.onNetworkEvent()) {
            handler.removeCallbacks(networkCheckRunnable)
            handler.postDelayed(networkCheckRunnable, UpdateScheduler.NETWORK_SETTLE_MS)
        }
    }

    /** B 层下载：多源断点续传 → 三道闸 → C 层安装；下载中再按按钮可取消 */
    private fun downloadUpdate(rel: ReleaseInfo) {
        if (updateState is UpdateState.Downloading) return
        downloadCancelled = false
        applyUpdateState(UpdateState.Downloading(rel.versionName, -1))
        Thread {
            val dir = getExternalFilesDir(null) ?: filesDir
            val apk = File(dir, "update-${rel.versionName}.apk")
            val ok = UpdateChecker.download(
                rel.apkUrls, apk,
                { pct -> handler.post { if (!downloadCancelled) applyUpdateState(UpdateState.Downloading(rel.versionName, pct)) } },
                cancelled = { downloadCancelled },
            )
            handler.post {
                if (downloadCancelled) { downloadCancelled = false; return@post } // 用户取消：保持 Idle
                if (!ok) {
                    applyUpdateState(UpdateState.Failed(UpdateState.Reason.Download))
                    return@post
                }
                applyUpdateState(UpdateState.Verifying)
                // B4 三道闸 fail-closed：包名/签名/版本回滚任一不过即拦截（异常也拒收）
                if (!UpdateChecker.verify(this, apk) || !UpdateChecker.sameSigner(this, apk)) {
                    apk.delete()
                    applyUpdateState(UpdateState.Failed(UpdateState.Reason.Verify))
                    return@post
                }
                if (!UpdateChecker.notOlder(this, apk)) {
                    apk.delete()
                    applyUpdateState(UpdateState.Failed(UpdateState.Reason.NotNewer)) // 防镜像未更新导致回滚
                    return@post
                }
                applyUpdateState(UpdateState.Installing)
                UpdateInstaller.listener = installerListener
                if (!UpdateInstaller.install(this, apk)) {
                    applyUpdateState(UpdateState.Failed(UpdateState.Reason.Install))
                }
            }
        }.start()
    }

    /** C 层安装结果回执（session 静默/确认页/降级 ACTION_VIEW 共用） */
    private val installerListener = object : UpdateInstaller.Listener {
        override fun onWaitingUserAction() { // 系统拉起传统确认页
            updateStatusState.value = getString(R.string.update_waiting)
        }

        override fun onSuccess() {
            applyUpdateState(
                UpdateState.Updated(
                    latestRelease?.versionName ?: currentVersionName(),
                    latestRelease?.notes ?: ""
                )
            )
        }

        override fun onAborted() {
            applyUpdateState(UpdateState.Idle)
        }

        override fun onOpened() {
            updateStatusState.value = getString(R.string.update_installing)
        }

        override fun onFailed() {
            applyUpdateState(UpdateState.Failed(UpdateState.Reason.Install))
        }
    }

    /** B5 状态机 → 按钮/状态行/notes 三路渲染 */
    private fun applyUpdateState(s: UpdateState) {
        updateState = s
        when (s) {
            UpdateState.Idle -> {
                updateLabelState.value = getString(R.string.update_check)
                updateStatusState.value = ""
                updateNotesState.value = ""
            }

            UpdateState.Checking -> updateLabelState.value = getString(R.string.update_checking)

            is UpdateState.UpToDate -> {
                updateLabelState.value = getString(R.string.update_check)
                updateStatusState.value = getString(R.string.update_none, s.versionName)
                updateNotesState.value = ""
            }

            is UpdateState.Available -> {
                updateLabelState.value = getString(R.string.update_download, s.versionName)
                updateStatusState.value = getString(R.string.update_found, s.versionName)
                updateNotesState.value = notesSummary(s.notes) // D1 前 2 行摘要
            }

            is UpdateState.Downloading -> {
                updateLabelState.value = getString(R.string.update_cancel) // D2 取消下载
                updateStatusState.value = if (s.percent >= 0) {
                    getString(R.string.update_downloading, s.percent)
                } else {
                    getString(R.string.update_downloading_unknown) // 服务端未报大小
                }
            }

            UpdateState.Verifying -> {
                updateLabelState.value = getString(R.string.update_verifying)
                updateStatusState.value = ""
            }

            UpdateState.Installing -> {
                updateLabelState.value = getString(R.string.update_installing)
                updateStatusState.value = getString(R.string.update_waiting)
            }

            is UpdateState.Updated -> {
                updateLabelState.value = getString(R.string.update_check)
                updateStatusState.value = getString(R.string.update_updated, s.versionName)
                updateNotesState.value = notesSummary(s.notes)
            }

            is UpdateState.Failed -> {
                // 下载失败保留"下载 vX.Y"按钮可重试；其余回"检查更新"
                updateLabelState.value = if (s.reason == UpdateState.Reason.Download && latestRelease != null) {
                    getString(R.string.update_download, latestRelease!!.versionName)
                } else {
                    getString(R.string.update_check)
                }
                updateStatusState.value = when (s.reason) {
                    UpdateState.Reason.Check -> getString(R.string.update_check_failed)
                    UpdateState.Reason.Download -> getString(R.string.update_download_failed)
                    UpdateState.Reason.Verify -> getString(R.string.update_verify_failed)
                    UpdateState.Reason.NotNewer -> getString(R.string.update_not_newer)
                    UpdateState.Reason.Install -> getString(R.string.update_install_failed)
                }
                updateNotesState.value = ""
            }
        }
    }

    /** D1 release notes 摘要：取前 2 个非空行，最长 120 字符 */
    private fun notesSummary(notes: String): String {
        val two = notes.lineSequence()
            .map { it.trim() }.filter { it.isNotEmpty() }.take(2)
            .joinToString(" · ")
        return if (two.length > 120) two.take(120) + "…" else two
    }

    /** #compose-tv-migration 面板打开时只拦 BACK/MENU；方向与确认键交给 Compose 焦点系统 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // #23 全屏二维码遮罩：OK/BACK 收起，其余键吞掉防穿透
        if (qrOverlay != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK,
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        toggleQrOverlay()
                        return true
                    }
                }
            }
            return true
        }
        if (panelWrap.visibility == View.VISIBLE && event.action == KeyEvent.ACTION_DOWN) {
            // 删除确认弹窗打开时 BACK 只关弹窗，不动面板
            if (pendingDeleteWall.intValue >= 0 && event.keyCode == KeyEvent.KEYCODE_BACK) {
                pendingDeleteWall.intValue = -1
                return true
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_SETTINGS -> {
                    togglePanel()
                    return true
                }

                // #23 CUSTOM 页签按 OK → 全屏二维码（Tab 切换是焦点漫游，确认键留作放大）；
                // QR 未就绪时不吞键，放行给 Compose 焦点系统；
                // 「检查更新」按钮持焦时 OK 让位给按钮点击，不切二维码
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (panelTabState.value == PanelTab.CUSTOM && !updateBtnFocusState.value && toggleQrOverlay()) {
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (panelWrap.visibility == View.VISIBLE) return false // 面板键走 Compose / dispatchKeyEvent
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                engine.togglePlay(); true
            }

            // 短按切歌（松手触发）/ 长按切背景（到阈值切一次，按住连发不重复切）
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if ((event?.repeatCount ?: 0) == 0) armLongPress(keyCode, -1)
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if ((event?.repeatCount ?: 0) == 0) armLongPress(keyCode, +1)
                true
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                changeVolume(+0.05f); true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                changeVolume(-0.05f); true
            }
            // #6 KEYCODE_VOLUME_* 不消费，放行走系统音量

            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_INFO -> {
                togglePanel(); true
            }

            KeyEvent.KEYCODE_BACK -> {
                // #3 双击 BACK 退出；单击仅闪提示，避免误触打断氛围
                val now = System.currentTimeMillis()
                if (now - lastBackPressedMs < 1500) {
                    finish()
                } else {
                    lastBackPressedMs = now
                    Toast.makeText(this, getString(R.string.exit_toast), Toast.LENGTH_SHORT).show()
                }
                true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == watchedKey) {
            pendingShortPress?.let { handler.removeCallbacks(it) }
            pendingShortPress = null
            val shortPress = !longPressFired
            watchedKey = 0
            longPressFired = false
            if (shortPress) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> engine.prev()
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> engine.next()
                }
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // ── 短按切歌 / 长按切背景（遥控器左右键）──────────────
    private var watchedKey = 0
    private var longPressFired = false
    private var pendingShortPress: Runnable? = null

    /** 装长按判定：到系统长按阈值切背景一次；阈值前松手由 onKeyUp 走短按切歌 */
    private fun armLongPress(keyCode: Int, wallpaperDelta: Int) {
        watchedKey = keyCode
        longPressFired = false
        pendingShortPress?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            longPressFired = true
            pendingShortPress = null
            switchWallpaper(wallpaperDelta)
        }
        pendingShortPress = r
        handler.postDelayed(r, ViewConfiguration.getLongPressTimeout().toLong())
    }

    /** 长按切背景：环形切换 + 记忆选择，静默切换不打扰 */
    private fun switchWallpaper(delta: Int) {
        val n = wallpaper.count
        if (n <= 0) return
        val target = ((appliedWallState.intValue + delta) % n + n) % n
        appliedWallState.intValue = target
        prefs.edit().putInt("wallpaper_index", target).apply()
        wallpaper.show(target)
    }

    private fun changeVolume(delta: Float) {
        val v = (engine.volume() + delta).coerceIn(0f, 1f)
        engine.setVolume(v)
        volOsd.text = String.format(Locale.ENGLISH, "VOL %d%%", (v * 100).toInt())
        volOsd.alpha = 1f
        volOsd.visibility = View.VISIBLE
        volOsd.animate().alpha(0f).setStartDelay(700).setDuration(500).withEndAction {
            volOsd.visibility = View.GONE
        }.start()
    }

    // ── 生命周期 ────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        activityResumed = true
        configureWindow()
        hideSystemBars()
        // C1 确认页 1.5s 闪退判定回调；回前台补一次调度检查（3h 新鲜度内自动跳过）
        UpdateInstaller.onAppResumed()
        checkUpdate(UpdateScheduler.Trigger.STARTUP)
    }

    override fun onPause() {
        activityResumed = false
        super.onPause()
    }

    override fun onDestroy() {
        // #23 先停局域网服务再释放引擎，避免 H5 请求踩到已释放的播放器
        CustomLibrary.onLibraryChanged = null
        netCallback?.let {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it)
        }
        netCallback = null
        lanServer?.stop()
        lanServer = null
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        UpdateInstaller.listener = null // 安装执行器全局持监听器，随 Activity 释放防泄漏
        wallpaper.stop()
        engine.release()
    }
}
