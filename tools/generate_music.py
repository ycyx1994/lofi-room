#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Lofi Room TV — 音乐合成器
用 numpy 合成三首无版权环境音乐（44.1kHz 立体声）：
  1. Midnight Espresso  慢速爵士 · 咖啡馆电钢  72 BPM
  2. Rainy Window        雨夜钢琴 · 环境音层    60 BPM
  3. Bossa Candlelight   Bossa Nova · 烛光吉他  84 BPM
输出 WAV → ffmpeg 转 AAC m4a 到 app/src/main/assets/music/
"""
import os, sys, math, wave, subprocess
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUILD = os.path.join(ROOT, "build_audio")
ASSETS = os.path.join(ROOT, "app/src/main/assets/music")
os.makedirs(BUILD, exist_ok=True)
os.makedirs(ASSETS, exist_ok=True)
SR = 44100
RNG = np.random.default_rng(20261112)

# ---------------------------------------------------------------- 基础工具
def midi2f(m):
    return 440.0 * 2 ** ((m - 69) / 12.0)

def env_ar(n, attack, release_tau, sr=SR):
    t = np.arange(n) / sr
    a = np.clip(t / max(attack, 1e-4), 0, 1)
    return a * np.exp(-t / release_tau)

def osc_const(freq, n, phase=0.0):
    t = np.arange(n) / SR
    return np.sin(2 * np.pi * freq * t + phase)

def osc_vibrato(freq, n, vib_hz=4.6, vib_depth=0.004, delay=0.45):
    t = np.arange(n) / SR
    depth = np.clip((t - delay) / 0.6, 0, 1) * vib_depth
    phase = 2 * np.pi * freq * t + depth * np.sin(2 * np.pi * vib_hz * t) * 2 * np.pi * freq / vib_hz
    return np.sin(phase)

def fft_lowpass(x, fc, order=2):
    """零相位频域低通"""
    X = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    H = 1.0 / (1.0 + (f / fc) ** (2 * order)) ** 0.5
    return np.fft.irfft(X * H, len(x))

def fft_bandpass(x, f_lo, f_hi):
    X = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    H = (1.0 / (1.0 + (f_lo / np.maximum(f, 1e-6)) ** 4) ** 0.5) * (1.0 / (1.0 + (f / f_hi) ** 4) ** 0.5)
    return np.fft.irfft(X * H, len(x))

def oa_convolve(x, h):
    """overlap-add FFT 卷积"""
    L = len(x); M = len(h)
    block = 1 << 20
    nfft = 1 << int(np.ceil(np.log2(block + M)))
    H = np.fft.rfft(h, nfft)
    out = np.zeros(L + M)
    for s in range(0, L, block):
        seg = x[s:s + block]
        y = np.fft.irfft(np.fft.rfft(seg, nfft) * H, nfft)[:len(seg) + M - 1]
        out[s:s + len(y)] += y
    return out[:L]

def make_ir(seed, decay=2.1, pre=0.012):
    rng = np.random.default_rng(seed)
    n = int(SR * decay)
    t = np.arange(n) / SR
    ir = rng.normal(0, 1, n) * np.exp(-t / (decay / 4.2))
    # 早期反射
    for d, g in [(0.011, .5), (0.019, .38), (0.029, .3), (0.041, .22), (0.058, .16)]:
        i = int(d * SR)
        ir[i] += g
    ir[:int(pre * SR)] *= np.linspace(0, 1, int(pre * SR))
    ir = fft_lowpass(ir, 6800)
    ir /= np.abs(ir).sum() / 24
    return ir

# ---------------------------------------------------------------- 乐器
def epiano(freq, dur, vel=1.0):
    """温暖电钢：基频+泛音，快起音，tine 鞭击感"""
    n = int(SR * (dur + 1.6))
    parts = [(1.0, 1.0, 1.9), (2.0, 0.34, 0.85), (3.01, 0.13, 0.42), (4.03, 0.055, 0.22)]
    out = np.zeros(n)
    for mult, amp, tau in parts:
        det = 1 + RNG.normal(0, 0.0012)
        out += amp * osc_const(freq * mult * det, n) * env_ar(n, 0.008, tau)
    # FM tine attack
    m_env = np.exp(-np.arange(n) / SR / 0.05)
    out += 0.16 * np.sin(2 * np.pi * freq * np.arange(n) / SR +
                        2.2 * m_env * np.sin(2 * np.pi * freq * 3.4 * np.arange(n) / SR))
    out = fft_lowpass(out, 5200)
    return out * (vel * 0.24)

def bell(freq, dur, vel=1.0):
    """柔和钟琴/八音盒感，用于旋律"""
    n = int(SR * (dur + 2.2))
    out = osc_vibrato(freq, n) * env_ar(n, 0.012, 1.5)
    out += 0.32 * osc_const(freq * 2.76, n) * env_ar(n, 0.006, 0.55)
    out += 0.14 * osc_const(freq * 5.4, n) * env_ar(n, 0.004, 0.22)
    return out * (vel * 0.19)

def bass(freq, dur, vel=1.0):
    n = int(SR * (dur + 0.8))
    out = osc_const(freq, n) * env_ar(n, 0.022, 1.15)
    out += 0.30 * osc_const(freq * 2, n) * env_ar(n, 0.018, 0.5)
    out += 0.10 * osc_const(freq * 3, n) * env_ar(n, 0.012, 0.22)
    out = fft_lowpass(out, 900)
    return out * (vel * 0.42)

def pluck(freq, dur, vel=1.0):
    """尼龙吉他感"""
    n = int(SR * (dur + 1.2))
    out = np.zeros(n)
    for mult, amp, tau in [(1, 1, 0.95), (2, 0.42, 0.5), (3, 0.18, 0.28), (4.1, 0.07, 0.16)]:
        out += amp * osc_const(freq * mult, n) * env_ar(n, 0.004, tau)
    noise = RNG.normal(0, 1, int(SR * 0.02))
    out[:len(noise)] += noise * 0.22 * np.exp(-np.arange(len(noise)) / SR / 0.005)
    out = fft_lowpass(out, 4200)
    return out * (vel * 0.26)

def shaker(dur=0.12, vel=1.0):
    n = int(SR * dur)
    out = RNG.normal(0, 1, n) * env_ar(n, 0.002, 0.032)
    out = fft_bandpass(out, 3500, 11000)
    return out * (vel * 0.32)

def brush(dur=0.35, vel=1.0):
    n = int(SR * dur)
    out = RNG.normal(0, 1, n) * env_ar(n, 0.05, 0.12)
    out = fft_bandpass(out, 800, 6500)
    return out * (vel * 0.22)

# ---------------------------------------------------------------- 渲染器
class Track:
    def __init__(self, seconds):
        self.buf = np.zeros((int(SR * seconds), 2))
        self.dur = seconds

    def add(self, sig, t, pan=0.0, gain=1.0):
        i = int(t * SR)
        if i >= len(self.buf):
            return
        n = min(len(sig), len(self.buf) - i)
        lg = math.sqrt((1 - pan) / 2) * 1.414
        rg = math.sqrt((1 + pan) / 2) * 1.414
        self.buf[i:i + n, 0] += sig[:n] * gain * lg
        self.buf[i:i + n, 1] += sig[:n] * gain * rg

    def add_stereo(self, sig_l, sig_r, t, gain=1.0):
        i = int(t * SR)
        n = min(len(sig_l), len(self.buf) - i)
        self.buf[i:i + n, 0] += sig_l[:n] * gain
        self.buf[i:i + n, 1] += sig_r[:n] * gain

    def reverb(self, mix=0.36, decay=2.1):
        ir_l = make_ir(11, decay); ir_r = make_ir(29, decay)
        wl = oa_convolve(self.buf[:, 0], ir_l)[:len(self.buf)]
        wr = oa_convolve(self.buf[:, 1], ir_r)[:len(self.buf)]
        self.buf = self.buf * (1 - mix) + np.stack([wl, wr], 1) * mix

    def master(self, fade=2.5):
        # 频域暖色化：低通 + 轻微高频搁架
        for c in range(2):
            self.buf[:, c] = fft_lowpass(self.buf[:, c], 9500)
        # 软限幅
        peak = np.abs(self.buf).max()
        self.buf = np.tanh(self.buf / max(peak, 1e-9) * 1.25) * 0.88
        # 首尾淡入淡出
        n = int(fade * SR)
        self.buf[:n] *= np.linspace(0, 1, n)[:, None] ** 1.5
        self.buf[-n:] *= np.linspace(1, 0, n)[:, None] ** 1.5

    def save(self, name):
        wav = os.path.join(BUILD, name + ".wav")
        pcm = (np.clip(self.buf, -1, 1) * 32767).astype("<i2")
        with wave.open(wav, "wb") as w:
            w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
            w.writeframes(pcm.tobytes())
        m4a = os.path.join(ASSETS, name + ".m4a")
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav,
                        "-c:a", "aac", "-b:a", "128k", m4a], check=True)
        print(f"  -> {name}: {self.dur:.0f}s  {os.path.getsize(m4a)//1024}KB")

def chord_arp(tr, notes, t, dur, vel=1.0, spread=0.045, inst=epiano, pan=0.0):
    for k, m in enumerate(notes):
        tr.add(inst(midi2f(m), dur, vel * (1 - 0.04 * k)), t + k * spread, pan)

# ---------------------------------------------------------------- 曲目 1: Midnight Espresso
def track_midnight_espresso():
    BARS = 4 * 60 / 72          # 3.333s
    N_BARS = 54                 # ≈ 180s
    tr = Track(N_BARS * BARS + 4)
    prog = [  # (bass, voicing) 每 2 小节一换
        (45, [60, 64, 67, 71]), (50, [62, 65, 69, 72]),      # Am9, Dm9
        (43, [62, 65, 67, 71]), (48, [60, 64, 67, 71]),      # G13, Cmaj9
        (41, [60, 64, 67, 72]), (47, [59, 62, 65, 69]),      # Fmaj9, Bm7b5
        (40, [60, 64, 67, 70]), (45, [60, 64, 67, 71]),      # E7b9, Am9
    ]
    melody_scale = [69, 72, 74, 76, 79, 81, 84, 86]
    for bar in range(N_BARS):
        t0 = bar * BARS
        b, voic = prog[(bar // 2) % len(prog)]
        # 电钢和弦：第 1、3 拍
        chord_arp(tr, voic, t0, BARS * 0.9, vel=0.95, spread=0.04)
        chord_arp(tr, voic, t0 + BARS / 2, BARS * 0.7, vel=0.55, spread=0.03)
        # 贝斯：1、3 拍
        tr.add(bass(midi2f(b - 12), BARS * 0.42), t0, 0.0, 0.9)
        tr.add(bass(midi2f(b), BARS * 0.30), t0 + BARS / 2, 0.0, 0.5)
        # 轻刷镲：每拍后半
        for k in range(4):
            tr.add(shaker(0.1, 0.24 + 0.1 * (k % 2)), t0 + k * BARS / 4 + BARS / 8, -0.25)
        # 每 4 小节一句旋律
        if bar % 4 == 0 and bar < N_BARS - 8:
            np_ = int(RNG.integers(3, 7))
            tt = t0 + BARS * 0.5
            step = BARS / 2
            for k in range(np_):
                m = melody_scale[int(RNG.integers(0, len(melody_scale)))]
                ln = step * (1 if RNG.random() < 0.6 else 2)
                tr.add(bell(midi2f(m), ln, 0.75 + 0.2 * RNG.random()), tt, 0.18)
                tt += ln * (1.0 if RNG.random() < 0.7 else 0.5)
    tr.reverb(mix=0.38, decay=2.3)
    tr.master()
    tr.save("midnight_espresso")

# ---------------------------------------------------------------- 曲目 2: Rainy Window
def track_rainy_window():
    BAR = 4 * 60 / 60           # 4s
    N_BARS = 46                 # 184s
    tr = Track(N_BARS * BAR + 4)
    prog = [
        (48, [60, 64, 67, 71]), (40, [59, 64, 67, 71]),      # Cmaj9, Em9
        (45, [60, 64, 67, 72]), (41, [60, 65, 69, 72]),      # Am9, Fmaj9
    ]
    for bar in range(N_BARS):
        t0 = bar * BAR
        b, voic = prog[(bar // 2) % len(prog)]
        # 分解和弦琶音：八分音符缓慢上下
        seq = [0, 1, 2, 3, 2, 1, 2, 3]
        for k, vi in enumerate(seq):
            m = voic[vi] + (12 if (k in (4, 7) and RNG.random() < 0.5) else 0)
            tr.add(epiano(midi2f(m), BAR * 0.8, 0.62 + 0.12 * (k % 3 == 0)),
                   t0 + k * BAR / 8, 0.12 * ((k % 3) - 1))
        # 贝斯长音
        tr.add(bass(midi2f(b - 12), BAR * 0.9), t0, 0.0, 0.72)
        if bar % 4 == 2:
            m = [69, 72, 74, 76, 79, 81][int(RNG.integers(0, 6))]
            tr.add(bell(midi2f(m), BAR * 1.6, 0.8), t0 + BAR * 0.25, 0.22)
    # 雨声层
    n = int(tr.dur * SR)
    rain_l = RNG.normal(0, 1, n); rain_r = RNG.normal(0, 1, n)
    rain_l = fft_bandpass(rain_l, 220, 3800); rain_r = fft_bandpass(rain_r, 220, 3800)
    lfo = 0.72 + 0.28 * np.sin(2 * np.pi * 0.07 * np.arange(n) / SR)
    tr.add_stereo(rain_l * lfo, rain_r * lfo, 0, 0.055)
    # 偶发雨滴
    for _ in range(26):
        t = RNG.uniform(2, tr.dur - 3)
        f = RNG.uniform(1800, 3400)
        nn = int(SR * 0.16)
        drip = osc_const(f, nn) * env_ar(nn, 0.001, 0.05)
        tr.add(drip, t, RNG.uniform(-0.5, 0.5), 0.10)
    tr.reverb(mix=0.42, decay=2.6)
    tr.master()
    tr.save("rainy_window")

# ---------------------------------------------------------------- 曲目 3: Bossa Candlelight
def track_bossa_candlelight():
    BAR = 4 * 60 / 84           # 2.857s
    N_BARS = 66                 # ≈ 189s
    tr = Track(N_BARS * BAR + 4)
    prog = [  # 每小节一换
        (41, [60, 64, 67, 69]), (46, [62, 65, 67, 70]),      # Fmaj7, Gm7
        (48, [60, 62, 65, 69]), (41, [60, 64, 67, 69]),      # C13, Fmaj7
        (46, [62, 65, 69, 72]), (45, [60, 64, 67, 71]),      # Bbmaj7, Am7
        (50, [60, 65, 69, 72]), (43, [60, 62, 65, 67]),      # Dm9, G7
    ]
    comp = [0, 3, 6, 10, 13]     # bossa 切分（16 分格）
    for bar in range(N_BARS):
        t0 = bar * BAR
        b, voic = prog[bar % len(prog)]
        for e in comp:
            vel = 0.9 if e == 0 else 0.55 + 0.15 * RNG.random()
            chord_arp(tr, voic, t0 + e * BAR / 16, BAR * 0.5, vel=vel,
                      spread=0.012, inst=pluck)
        # 贝斯：1 拍根音 + 3 拍五度
        tr.add(bass(midi2f(b - 12), BAR * 0.30), t0, 0.0, 0.85)
        tr.add(bass(midi2f(b), BAR * 0.24), t0 + BAR / 2, 0.0, 0.6)
        # 十六分沙锤
        for k in range(16):
            if k % 2 == 0 or RNG.random() < 0.3:
                tr.add(shaker(0.07, 0.10 + 0.08 * (k % 4 == 0)), t0 + k * BAR / 16, -0.3)
        # 每 2 小节旋律短句
        if bar % 2 == 0 and 4 < bar < N_BARS - 8 and RNG.random() < 0.8:
            tt = t0 + BAR * 0.25
            for _ in range(int(RNG.integers(2, 4))):
                m = [69, 71, 72, 74, 76, 79, 81][int(RNG.integers(0, 7))]
                ln = BAR * 0.35 * (1 if RNG.random() < 0.5 else 1.8)
                tr.add(bell(midi2f(m), ln, 0.7), tt, 0.25)
                tt += ln + BAR * 0.1
        # 轻刷鼓刷
        if bar % 2 == 1:
            tr.add(brush(0.3, 0.16), t0 + BAR * 0.75, 0.15)
    tr.reverb(mix=0.34, decay=1.9)
    tr.master()
    tr.save("bossa_candlelight")

# ---------------------------------------------------------------- 入口
if __name__ == "__main__":
    print("== 合成 Midnight Espresso (72 BPM 爵士) ==")
    track_midnight_espresso()
    print("== 合成 Rainy Window (60 BPM 雨夜钢琴) ==")
    track_rainy_window()
    print("== 合成 Bossa Candlelight (84 BPM bossa) ==")
    track_bossa_candlelight()
    print("done.")
