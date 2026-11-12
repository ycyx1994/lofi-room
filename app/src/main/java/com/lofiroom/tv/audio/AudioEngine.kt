package com.lofiroom.tv.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.lofiroom.tv.data.Playlist
import com.lofiroom.tv.data.Track

/**
 * 音频引擎：ExoPlayer + 自定义 AudioSink 挂 TeeAudioProcessor，
 * 把解码 PCM 旁路给 VisualizerTap 做波形分析。
 */
class AudioEngine(context: Context, private val tap: VisualizerTap) {

    interface Listener {
        fun onTrackChanged(track: Track, index: Int, total: Int)
        fun onPlayingChanged(playing: Boolean)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val player: ExoPlayer
    var listener: Listener? = null
    private var consecutiveErrors = 0

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

        val items = Playlist.tracks.map { MediaItem.fromUri("asset:///" + it.asset) }
        player.setMediaItems(items)
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.volume = 0.8f

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val i = player.currentMediaItemIndex
                mainHandler.post {
                    listener?.onTrackChanged(Playlist.tracks[i], i, Playlist.tracks.size)
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
                if (consecutiveErrors >= Playlist.tracks.size) {
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

    fun setVolume(v: Float) {
        player.volume = v.coerceIn(0f, 1f)
    }

    fun volume(): Float = player.volume

    fun release() {
        player.release()
    }
}
