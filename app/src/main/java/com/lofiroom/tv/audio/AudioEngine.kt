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
            .build()

        // #23 曲目走合并库（内置 assets + 自定义文件），mediaUri() 统一分流
        val items = CustomLibrary.tracks.map { MediaItem.fromUri(it.mediaUri()) }
        player.setMediaItems(items)
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.volume = 0.8f

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val i = player.currentMediaItemIndex
                mainHandler.post {
                    val ts = CustomLibrary.tracks
                    ts.getOrNull(i)?.let { listener?.onTrackChanged(it, i, ts.size) }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) consecutiveErrors = 0
                tap.playing = isPlaying
                mainHandler.post { listener?.onPlayingChanged(isPlaying) }
            }

            override fun onPlayerError(error: PlaybackException) {
                // #10 坏曲目自动跳下一首；连续失败一轮说明全部损坏，停止重试避免忙循环
                consecutiveErrors++
                if (consecutiveErrors >= player.mediaItemCount) {
                    player.playWhenReady = false
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
        player.playWhenReady = !player.playWhenReady
    }

    fun next() {
        player.seekToNextMediaItem()
    }

    fun prev() {
        if (player.currentPosition > 3000) player.seekTo(0)
        else player.seekToPreviousMediaItem()
    }

    fun playIndex(i: Int) {
        player.seekTo(i, 0)
        player.playWhenReady = true
    }

    /** #23 自定义曲目增删/重命名/移动后重建播放列表：
     *  URI 无变化则不动（如仅改名），否则保留当前曲目与进度换源 */
    fun refreshItems() {
        val ts = CustomLibrary.tracks
        val uris = ts.map { it.mediaUri() }
        if (uris == currentUris) return
        val keepUri = currentUris.getOrNull(player.currentMediaItemIndex)
        var idx = uris.indexOf(keepUri)
        if (idx < 0) idx = player.currentMediaItemIndex.coerceIn(0, (uris.size - 1).coerceAtLeast(0))
        val pos = player.currentPosition
        val wasPlaying = player.playWhenReady
        currentUris = uris
        if (uris.isEmpty()) return
        player.setMediaItems(ts.map { MediaItem.fromUri(it.mediaUri()) }, idx, pos)
        player.prepare()
        player.playWhenReady = wasPlaying
        consecutiveErrors = 0
        mainHandler.post {
            CustomLibrary.tracks.getOrNull(idx)?.let {
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
