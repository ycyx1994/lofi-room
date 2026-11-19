package com.lofiroom.tv.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * PCM 采集器：挂载在 ExoPlayer 音频链路的 TeeAudioProcessor 上，
 * 实时把解码后的 PCM 混成单声道并做 Goertzel 频带分析，
 * 供 VisualizerView 读取（无需任何录音权限）。
 */
// TeeAudioProcessor.AudioBufferSink 整条链在 media3 1.4.1 里都是 @UnstableApi：
// 这里显式 opt-in（lint 的 UnsafeOptInUsageError 只认注解，不认编译器 -opt-in 参数）。
@OptIn(UnstableApi::class)
class VisualizerTap : TeeAudioProcessor.AudioBufferSink {

    companion object {
        const val BANDS = 72
        private const val WINDOW = 1024
        private const val MIN_FREQ = 55f
        private const val MAX_FREQ = 11000f
        private const val COMPUTE_INTERVAL_NS = 33_000_000L // ~30Hz
    }

    private var sampleRate = 44100
    private var channels = 2
    private var encoding: Int = C.ENCODING_PCM_16BIT

    private val ring = FloatArray(WINDOW)
    private var ringPos = 0
    private val smoothed = FloatArray(BANDS)
    private val lock = Any()
    private var lastComputeNs = 0L

    @Volatile
    var playing = true

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        synchronized(lock) {
            sampleRate = if (sampleRateHz > 0) sampleRateHz else 44100
            channels = channelCount.coerceIn(1, 8)
            this.encoding = encoding
            ringPos = 0
        }
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        // PCM 数据按机器字节序存放
        buffer.order(ByteOrder.nativeOrder())
        val ch: Int
        val enc: Int
        synchronized(lock) {
            ch = channels
            enc = encoding
        }
        while (buffer.hasRemaining()) {
            var sum = 0f
            for (c in 0 until ch) {
                if (!buffer.hasRemaining()) break
                sum += when (enc) {
                    C.ENCODING_PCM_FLOAT -> buffer.float
                    C.ENCODING_PCM_8BIT -> (buffer.get().toInt() and 0xff) / 128f - 1f
                    else -> buffer.short / 32768f
                }
            }
            ring[ringPos] = sum / ch
            ringPos = (ringPos + 1) % WINDOW
        }
        maybeCompute()
    }

    private fun maybeCompute() {
        val now = System.nanoTime()
        if (now - lastComputeNs < COMPUTE_INTERVAL_NS) return
        lastComputeNs = now
        synchronized(lock) { computeLocked() }
    }

    /** 72 条对数分布频带的 Goertzel 能量 + 平滑 */
    private fun computeLocked() {
        val n = WINDOW
        val sr = sampleRate.toFloat()
        for (b in 0 until BANDS) {
            val frac = b / (BANDS - 1f)
            val freq = MIN_FREQ * (MAX_FREQ / MIN_FREQ).pow(frac)
            val k = freq * n / sr
            val coeff = 2.0 * cos(2.0 * Math.PI * k / n)
            var s1 = 0.0
            var s2 = 0.0
            for (i in 0 until n) {
                val idx = (ringPos + i) % n
                val s0 = ring[idx].toDouble() + coeff * s1 - s2
                s2 = s1
                s1 = s0
            }
            val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
            val mag = sqrt(power.coerceAtLeast(0.0)).toFloat() / n

            // 幅度映射 + 感知压缩
            var level = (mag * 2.6f).coerceIn(0f, 1f)
            level = sqrt(level)

            // 快攻慢放的包络平滑
            val old = smoothed[b]
            smoothed[b] = if (level > old) old * 0.45f + level * 0.55f
            else old * 0.84f + level * 0.16f
        }
        // 轻微横向扩散，视觉更柔和
        val tmp = smoothed.copyOf()
        for (b in 1 until BANDS - 1) {
            smoothed[b] = tmp[b] * 0.6f + (tmp[b - 1] + tmp[b + 1]) * 0.2f
        }
    }

    /** 把当前频带电平拷贝到 out（长度 BANDS） */
    fun snapshot(out: FloatArray) {
        synchronized(lock) {
            System.arraycopy(smoothed, 0, out, 0, BANDS)
        }
    }

    /** 静音衰减：播放暂停时由 UI 线程调用，让波形缓缓落下 */
    fun decay() {
        synchronized(lock) {
            for (b in 0 until BANDS) smoothed[b] *= 0.96f
        }
    }
}
