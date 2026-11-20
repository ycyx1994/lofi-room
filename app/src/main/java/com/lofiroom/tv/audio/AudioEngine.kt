package com.lofiroom.tv.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.lofiroom.tv.custom.CustomLibrary
import com.lofiroom.tv.data.Track
import com.lofiroom.tv.data.mediaUri

/**
 * 音频引擎：ExoPlayer + 自定义 AudioSink 挂 TeeAudioProcessor，
 * 把解码 PCM 旁路给 VisualizerTap 做波形分析。
 */
// DefaultAudioSink / TeeAudioProcessor / AudioSink 在 media3 1.4.1 里是 @UnstableApi，
// 显式 opt-in（lint 只认注解，不认编译器 -opt-in 参数）。
@OptIn(UnstableApi::class)
class AudioEngine(context: Context, private val tap: VisualizerTap) {

    interface Listener {
        fun onTrackChanged(track: Track, index: Int, total: Int)
        fun onPlayingChanged(playing: Boolean)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val player: ExoPlayer
    var listener: Listener? = null
    private var consecutiveErrors = 0
    // 暂停期间出错时攒下的跳曲请求：不立即切曲，等恢复播放再兑现（避免曲目静默改变）
    private var pendingSkip = false
    private var currentUris: List<String> = CustomLibrary.tracks.map { it.mediaUri() }

    init {
        val tee = TeeAudioProcessor(tap)
        val sink: AudioSink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf<AudioProcessor>(tee))
            .build()
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = sink
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player = ExoPlayer.Builder(context, renderersFactory)
            .setAudioAttributes(attrs, true)
            // 插拔耳机/HDMI 音频路由切换时自动暂停，避免继续外放
            .setHandleAudioBecomingNoisy(true)
            .build()

        // #23 曲目走合并库（内置 assets + 自定义文件），mediaUri() 统一分流
        val items = CustomLibrary.tracks.map { MediaItem.fromUri(it.mediaUri()) }
        player.setMediaItems(items)
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.volume = 0.8f

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val i = player.currentMediaItemIndex
                // 事件回调处先快照曲目列表，post 执行时不再现读，避免与 refreshItems 并发时旧下标配新曲单
                val ts = CustomLibrary.tracks
                mainHandler.post {
                    // post 执行时索引可能已变（并发切歌/重建列表），校验后再回调，失配则以最新索引重取
                    if (player.currentMediaItemIndex == i) {
                        ts.getOrNull(i)?.let { listener?.onTrackChanged(it, i, ts.size) }
                    } else {
                        val cur = player.currentMediaItemIndex
                        val latest = CustomLibrary.tracks
                        latest.getOrNull(cur)?.let { listener?.onTrackChanged(it, cur, latest.size) }
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    consecutiveErrors = 0
                    // 兜底：暂停期出错攒下的跳曲请求在真正开播时兑现（togglePlay 已处理则为空操作）
                    if (pendingSkip) {
                        pendingSkip = false
                        player.seekToNextMediaItem()
                        player.prepare()
                    }
                }
                tap.playing = isPlaying
                mainHandler.post { listener?.onPlayingChanged(isPlaying) }
            }

            override fun onPlayerError(error: PlaybackException) {
                // #10 坏曲目自动跳下一首；连续失败一轮说明全部损坏，停止重试避免忙循环
                // 仅真实播放失败才累计：暂停态 prepare 失败不计，避免误触耗尽分支
                if (player.playWhenReady) consecutiveErrors++
                if (player.playWhenReady && consecutiveErrors >= player.mediaItemCount) {
                    player.playWhenReady = false
                    // 耗尽后 ExoPlayer 处于 IDLE+error 态，补 prepare() 让引擎恢复可操作
                    player.prepare()
                    return
                }
                if (!player.playWhenReady) {
                    // 用户主动暂停期间出错不强行换曲，恢复播放时再跳
                    pendingSkip = true
                    return
                }
                player.seekToNextMediaItem()
                player.prepare()
                // 不改 playWhenReady：保持用户暂停/播放意图，正常恢复时它本身即 true
            }
        })

        player.prepare()
        player.playWhenReady = true
    }

    val isPlaying: Boolean get() = player.isPlaying
    val positionMs: Long get() = player.currentPosition
    val durationMs: Long get() = player.duration
    val currentIndex: Int get() = player.currentMediaItemIndex

    fun togglePlay() {
        val turningOn = !player.playWhenReady
        if (turningOn && pendingSkip) {
            // 暂停期出错攒下的跳曲请求，恢复播放时兑现
            pendingSkip = false
            player.seekToNextMediaItem()
        }
        // 防御：error/IDLE 态下翻标志无效，先 prepare 恢复可播
        if (player.playerError != null || player.playbackState == Player.STATE_IDLE) player.prepare()
        player.playWhenReady = turningOn
    }

    fun next() {
        // 防御：error/IDLE 态下 seek 不会起播，先 prepare 恢复可播
        if (player.playerError != null || player.playbackState == Player.STATE_IDLE) player.prepare()
        player.seekToNextMediaItem()
    }

    fun prev() {
        // 防御：error/IDLE 态下 seek 不会起播，先 prepare 恢复可播
        if (player.playerError != null || player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.currentPosition > 3000) player.seekTo(0)
        else player.seekToPreviousMediaItem()
    }

    fun playIndex(i: Int) {
        // 防御：error/IDLE 态下 seek 不会起播，先 prepare 恢复可播
        if (player.playerError != null || player.playbackState == Player.STATE_IDLE) player.prepare()
        player.seekTo(i, 0)
        player.playWhenReady = true
        // 手动切歌是真实播放意图，清零错误累计与待跳曲请求
        consecutiveErrors = 0
        pendingSkip = false
    }

    /** #23 自定义曲目增删/重命名/移动后重建播放列表：
     *  URI 无变化则不动（如仅改名），否则保留当前曲目与进度换源 */
    fun refreshItems() {
        val ts = CustomLibrary.tracks
        val uris = ts.map { it.mediaUri() }
        if (uris == currentUris) return
        val keepUri = currentUris.getOrNull(player.currentMediaItemIndex)
        val keepIdx = uris.indexOf(keepUri)
        // 仅当当前曲仍在新列表命中时保留进度，被删/换源时新曲从头播
        val idx = if (keepIdx >= 0) keepIdx
        else player.currentMediaItemIndex.coerceIn(0, (uris.size - 1).coerceAtLeast(0))
        val pos = if (keepIdx >= 0) player.currentPosition else 0L
        val wasPlaying = player.playWhenReady
        currentUris = uris
        if (uris.isEmpty()) return
        player.setMediaItems(ts.map { MediaItem.fromUri(it.mediaUri()) }, idx, pos)
        player.prepare()
        player.playWhenReady = wasPlaying
        consecutiveErrors = 0
        // 重建列表即用户已决定曲目，作废待跳曲请求
        pendingSkip = false
        mainHandler.post {
            // 用快照 ts 而非现读，避免 post 执行时列表又变导致错位（同 onMediaItemTransition 的修法）
            ts.getOrNull(idx)?.let {
                listener?.onTrackChanged(it, idx, ts.size)
            }
        }
    }

    fun setVolume(v: Float) {
        player.volume = v.coerceIn(0f, 1f)
    }

    fun volume(): Float = player.volume

    fun release() {
        player.release()
    }
}
