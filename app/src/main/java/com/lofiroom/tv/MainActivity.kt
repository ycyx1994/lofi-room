package com.lofiroom.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.lofiroom.tv.audio.AudioEngine
import com.lofiroom.tv.audio.VisualizerTap
import com.lofiroom.tv.data.Caption
import com.lofiroom.tv.data.Playlist
import com.lofiroom.tv.data.Track
import com.lofiroom.tv.ui.VisualizerView
import com.lofiroom.tv.ui.WallpaperView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity() {

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
    private lateinit var trackList: ListView

    private var captionIndex = 0
    private var totalTracks = Playlist.tracks.size
    private var lastBackPressedMs = 0L // #3 双击 BACK 退出
    private val fontSerif: Typeface by lazy { loadFont("fonts/CormorantGaramond.ttf", Typeface.SERIF) } // #5
    private val fontSans: Typeface by lazy { loadFont("fonts/Jost.ttf", Typeface.SANS_SERIF) }

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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        hideSystemBars()
        bindViews()
        applyFonts()
        startClock()

        tap = VisualizerTap()
        engine = AudioEngine(this, tap)
        visualizer.tap = tap
        engine.listener = object : AudioEngine.Listener {
            override fun onTrackChanged(track: Track, index: Int, total: Int) {
                totalTracks = total
                bindTrack(track, index, total)
                if (panelWrap.visibility == View.VISIBLE) trackList.invalidateViews()
            }

            override fun onPlayingChanged(playing: Boolean) {
                // 静音时 VisualizerView 自行衰减
                if (panelWrap.visibility == View.VISIBLE) trackList.invalidateViews()
            }
        }
        bindTrack(Playlist.tracks[0], 0, totalTracks)

        wallpaper.onWallpaperChange = { index, _ -> rotateCaption(index) }
        wallpaper.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event); true }
        wallpaper.start()
        rotateCaption(0)

        setupPanel()
        handler.postDelayed({ fadeOut(hint) }, 9000)
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
        trackList = findViewById(R.id.track_list)
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
    }

    private fun loadFont(path: String, fallback: Typeface): Typeface = try {
        Typeface.createFromAsset(assets, path)
    } catch (e: Exception) {
        fallback
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

    // ── 时钟 ───────────────────────────────────────────────

    private fun startClock() {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val tick = object : Runnable {
            override fun run() {
                clock.text = fmt.format(Date())
                handler.postDelayed(this, 20_000)
            }
        }
        tick.run()
    }

    // ── 播放列表面板 ────────────────────────────────────────

    private fun setupPanel() {
        trackList.adapter = TrackAdapter()
        trackList.choiceMode = ListView.CHOICE_MODE_SINGLE
        // #11 触摸点按曲目行直接播放，行为与遥控器 OK 一致
        trackList.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            if (position in Playlist.tracks.indices) engine.playIndex(position)
            trackList.invalidateViews()
            togglePanel()
        }
        // #11 点面板外暗色区域关闭（卡片本体 clickable 消费，点卡片不关闭）
        panelWrap.setOnClickListener { togglePanel() }
    }

    private fun togglePanel() {
        if (panelWrap.visibility == View.VISIBLE) {
            panelWrap.visibility = View.GONE
        } else {
            panelWrap.visibility = View.VISIBLE
            trackList.setSelection(engine.currentIndex)
            hint.alpha = 1f
            fadeOut(hint)
        }
    }

    private inner class TrackAdapter : BaseAdapter() {
        override fun getCount(): Int = Playlist.tracks.size
        override fun getItem(position: Int): Any = Playlist.tracks[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.row_track, parent, false)
            val t = Playlist.tracks[position]
            v.findViewById<TextView>(R.id.row_no).text =
                String.format(Locale.ENGLISH, "%02d", position + 1)
            v.findViewById<TextView>(R.id.row_title).text = t.title
            v.findViewById<TextView>(R.id.row_sub).text = t.sub
            val state = v.findViewById<TextView>(R.id.row_state)
            state.text = if (position == engine.currentIndex && engine.isPlaying) "PLAYING" else ""
            v.findViewById<TextView>(R.id.row_title).typeface = fontSerif // #5 复用缓存字体
            v.findViewById<TextView>(R.id.row_sub).typeface = fontSans
            v.findViewById<TextView>(R.id.row_no).typeface = fontSans
            state.typeface = fontSans
            return v
        }
    }

    // ── 遥控器 / 按键交互 ────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (panelWrap.visibility == View.VISIBLE) return panelKey(keyCode)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                engine.togglePlay(); true
            }

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                engine.prev(); true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                engine.next(); true
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
                    Toast.makeText(this, "再按一次返回键退出", Toast.LENGTH_SHORT).show()
                }
                true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun panelKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> {
            val p = (trackList.selectedItemPosition - 1)
                .coerceAtLeast(0)
            trackList.setSelection(p); true
        }

        KeyEvent.KEYCODE_DPAD_DOWN -> {
            val p = (trackList.selectedItemPosition + 1)
                .coerceAtMost(Playlist.tracks.size - 1)
            trackList.setSelection(p); true
        }

        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
            val i = trackList.selectedItemPosition
            if (i in Playlist.tracks.indices) engine.playIndex(i)
            trackList.invalidateViews()
            togglePanel(); true
        }

        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_SETTINGS -> {
            togglePanel(); true
        }

        else -> false
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
        hideSystemBars()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        wallpaper.stop()
        engine.release()
    }
}
