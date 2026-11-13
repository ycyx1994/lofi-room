#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Lofi Room TV — 素材处理脚本
1) 把参考图里烤死的叠加文字用「高斯扩散修复」抹掉，得到干净壁纸
2) 裁剪/缩放到 2560 宽，输出到 app/src/main/assets/wallpapers/
3) 生成 3 张暖色抽象壁纸（渐变+颗粒+暗角）
4) 生成应用图标 / Android TV banner
"""
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(ROOT, "wallpapers_raw")
OUT = os.path.join(ROOT, "app/src/main/assets/wallpapers")
RES = os.path.join(ROOT, "app/src/main/res")
os.makedirs(OUT, exist_ok=True)

# ---------------------------------------------------------------- 修复工具
def load(path):
    return np.asarray(Image.open(path).convert("RGB"), dtype=np.float64)

def dilate_mask(mask, radius_px):
    """用 MaxFilter 膨胀 mask（mask: bool ndarray）"""
    img = Image.fromarray((mask * 255).astype(np.uint8))
    size = radius_px * 2 + 1
    img = img.filter(ImageFilter.MaxFilter(size))
    return np.asarray(img) > 127

def gaussian(arr_u8, radius):
    return np.asarray(
        Image.fromarray(arr_u8).filter(ImageFilter.GaussianBlur(radius))
    ).astype(np.float64)

def auto_mask(img, boxes, thresh=32.0):
    """文字 mask = 边缘偏离(亮字暗字都抓) ∪ 亮度偏高，只在 bbox 内生效"""
    lum = img.mean(axis=2)
    dev = np.abs(img - gaussian(np.clip(img, 0, 255).astype(np.uint8), 16)).max(axis=2)
    mask = np.zeros(lum.shape, dtype=bool)
    H, W = lum.shape
    for (x0, y0, x1, y1) in boxes:
        x0, y0 = max(0, x0), max(0, y0)
        x1, y1 = min(W, x1), min(H, y1)
        if x0 >= x1 or y0 >= y1:
            continue
        d = dev[y0:y1, x0:x1]
        r = lum[y0:y1, x0:x1]
        sub = (d > 12) | (r > np.median(r) + thresh)
        mask[y0:y1, x0:x1] |= sub
    return mask

def pyramid_fill(img, mask, levels=5, iters=150, blur=2.2):
    """多重网格扩散修复：粗层先填大洞，逐层细化，避免大字留残影"""
    imgs, masks = [img.copy()], [mask]
    for l in range(1, levels):
        h, w = imgs[-1].shape[:2]
        if min(h, w) < 80:
            break
        small = np.asarray(Image.fromarray(np.clip(imgs[-1], 0, 255).astype(np.uint8))
                           .resize((w // 2, h // 2), Image.LANCZOS)).astype(np.float64)
        smask = np.asarray(Image.fromarray((masks[-1] * 255).astype(np.uint8))
                           .resize((w // 2, h // 2), Image.BILINEAR)) > 60
        imgs.append(small); masks.append(smask)
    u, m = imgs[-1].copy(), masks[-1]
    for _ in range(iters * 2):
        if not m.any():
            break
        b = gaussian(np.clip(u, 0, 255).astype(np.uint8), blur * 2)
        u[m] = b[m]
    for l in range(len(imgs) - 2, -1, -1):
        h, w = imgs[l].shape[:2]
        up = np.asarray(Image.fromarray(np.clip(u, 0, 255).astype(np.uint8))
                        .resize((w, h), Image.BICUBIC)).astype(np.float64)
        u, m = imgs[l].copy(), masks[l]
        u[m] = up[m]
        for _ in range(iters):
            b = gaussian(np.clip(u, 0, 255).astype(np.uint8), blur)
            u[m] = b[m]
    return u

def add_texture(img, filled, mask, levels=4, iters=70):
    """v3 纹理移植：把周边高频残差扩散进修复区，保留木纹/砖缝等结构延续"""
    hf = img - gaussian(np.clip(img, 0, 255).astype(np.uint8), 2.0)
    hf_in = hf.copy()
    hf_in[mask] = 0.0
    tex = pyramid_fill(hf_in + 128.0, mask, levels=levels, iters=iters, blur=2.4) - 128.0
    out = filled.copy()
    out[mask] = filled[mask] + tex[mask] * 0.85
    return out

def gaussian_f(arr, sigma):
    """浮点高斯模糊（numpy 可分离卷积）——PIL GaussianBlur 不支持浮点图"""
    radius = max(1, int(3 * sigma))
    x = np.arange(-radius, radius + 1, dtype=np.float64)
    k = np.exp(-(x * x) / (2.0 * sigma * sigma))
    k /= k.sum()
    tmp = np.stack([np.convolve(row, k, mode="same") for row in arr])
    return np.stack([np.convolve(tmp[:, j], k, mode="same") for j in range(tmp.shape[1])], axis=1)


def add_grain(arr, mask):
    """v3.1 胶片颗粒：柔化归一化噪声 + 幅度封顶，避免椒盐噪斑"""
    hf = arr - gaussian(np.clip(arr, 0, 255).astype(np.uint8), 1.2)
    sigma = float(np.median(np.abs(hf[~mask])) * 1.4826 + 0.5)
    sigma = min(sigma, 2.2)  # 幅度封顶（灰阶 std），防椒盐感
    h, w = mask.shape
    n = np.random.normal(0.0, 1.0, (h, w))
    n = gaussian_f(n, 1.1)  # 柔化噪声，接近胶片颗粒
    n = n / max(float(n.std()), 1e-6)  # 归一化到 std=1
    out = arr.copy()
    out[mask] = arr[mask] + (n[mask] * sigma)[:, None]
    return out

def diffusion_inpaint(img, mask, dilate=7, iters=150, blur=2.2):
    mask = dilate_mask(mask, dilate)
    if not mask.any():
        return img
    u = pyramid_fill(img, mask, iters=iters, blur=blur)
    alpha = gaussian((mask * 255).astype(np.uint8), 3.5) / 255.0
    alpha = np.clip(alpha * 1.35, 0, 1)[:, :, None]
    out = img * (1 - alpha) + u * alpha
    out = add_texture(img, out, mask)
    out = add_grain(out, mask)
    return out

def save_wall(arr, name, max_w=2560):
    im = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))
    if im.width > max_w:
        im = im.resize((max_w, round(im.height * max_w / im.width)), Image.LANCZOS)
    path = os.path.join(OUT, name)
    im.save(path, "JPEG", quality=88, optimize=True, progressive=True)
    print(f"  -> {name}  {im.width}x{im.height}  {os.path.getsize(path)//1024}KB")

# ---------------------------------------------------------------- 每张图的修复配置
# boxes 为原图坐标 (x0, y0, x1, y1)，圈住烤死的叠加文字
JOBS = [
    # 用户截图1：阳台黄昏 "Lofi Room Playlist / CHILLNOVA + 波形"（中心） + 左下角"9人正在看"
    dict(src="shot_1.jpg", name="wall_01.jpg", thresh=22, boxes=[
        (880, 490, 1765, 760),      # 中心标题块 + 波形
        (210, 1135, 480, 1200),     # 左下角人数水印
    ]),
    # 用户截图2：深夜书房 "SoulHaus + 小波形 + FLOW 段落 + 右侧手写体"
    dict(src="shot_2.jpg", name="wall_02.jpg", thresh=22, boxes=[
        (1080, 35, 1540, 165),      # SoulHaus
        (1110, 185, 1500, 255),     # 顶部小波形
        (240, 1045, 1225, 1200),    # 左下 FLOW + 段落
        (1875, 750, 2270, 845),     # 右侧手写体
        (210, 1135, 480, 1200),     # 水印
    ]),
    # 用户截图3：黄昏拱门 "SoulHaus + ARCADE 段落 + 右侧羽毛笔"
    dict(src="shot_3.jpg", name="wall_03.jpg", thresh=22, boxes=[
        (1080, 35, 1540, 165),
        (240, 1025, 1510, 1200),    # 左下 ARCADE + 段落（含第三行）
        (1960, 695, 2180, 910),     # 右侧羽毛笔 + 手写体
        (210, 1135, 480, 1200),
    ]),
    # 视频封面1：暖桌书房 "SoulHaus + 巨型 FLOW + 底部说明"
    dict(src="cover_1.jpg", name="wall_04.jpg", thresh=22, boxes=[
        (770, 30, 1160, 140),       # SoulHaus
        (415, 285, 1525, 725),      # FLOW 巨字
        (490, 945, 1540, 1045),     # 底部段落
    ]),
    # 视频封面2：壁炉与猫 "Jazz Vocal Playlist / CHILLNOVA"
    dict(src="cover_2.jpg", name="wall_05.jpg", thresh=18, boxes=[
        (120, 370, 1285, 595),
    ]),
    # 视频封面3：藤椅与窗 "Jazz Vocal Playlist / CHILLMUSIC"
    dict(src="cover_3.jpg", name="wall_06.jpg", thresh=18, boxes=[
        (540, 440, 1385, 595),
    ]),
]

print("== 修复参考图文字 ==")
for job in JOBS:
    src = os.path.join(RAW, job["src"])
    img = load(src)
    mask = auto_mask(img, job["boxes"], thresh=job["thresh"])
    print(f"{job['src']}: mask px = {mask.sum()}")
    fixed = diffusion_inpaint(img, mask, iters=280)
    save_wall(fixed, job["name"])

# ---------------------------------------------------------------- 抽象暖色壁纸
def warm_abstract(seed, palette, name):
    rng = np.random.default_rng(seed)
    W, H = 2560, 1440
    # 多个暖色光斑叠加
    base = np.zeros((H, W, 3), dtype=np.float64)
    yy, xx = np.mgrid[0:H, 0:W]
    wsum = np.zeros((H, W))
    for i in range(6):
        cx, cy = rng.uniform(-0.2, 1.2) * W, rng.uniform(-0.2, 1.2) * H
        r = rng.uniform(0.35, 0.9) * W
        col = np.array(palette[i % len(palette)], dtype=np.float64)
        w = np.exp(-(((xx - cx) ** 2 + (yy - cy) ** 2) / (2 * (r * 0.55) ** 2)))
        base += w[:, :, None] * col[None, None, :]
        wsum += w
    img = base / np.maximum(wsum, 1e-6)[:, :, None]
    img = gaussian(np.clip(img, 0, 255).astype(np.uint8), 60)
    img = img.astype(np.float64)
    # 暗角
    cx, cy = W / 2, H / 2
    d = np.sqrt(((xx - cx) / (W * 0.62)) ** 2 + ((yy - cy) / (H * 0.62)) ** 2)
    vig = np.clip(1.06 - 0.42 * d ** 2.1, 0.42, 1)[:, :, None]
    img *= vig
    # 细颗粒
    grain = rng.normal(0, 3.2, (H, W, 1))
    img += grain
    save_wall(img, name)

print("== 生成抽象壁纸 ==")
warm_abstract(7, [(43, 27, 18), (201, 123, 61), (242, 227, 200), (92, 52, 30), (226, 168, 105), (58, 34, 22)], "wall_07.jpg")
warm_abstract(21, [(28, 22, 20), (170, 92, 48), (235, 205, 168), (74, 40, 26), (212, 138, 82), (36, 26, 22)], "wall_08.jpg")
warm_abstract(42, [(32, 30, 22), (140, 118, 74), (238, 226, 196), (72, 62, 38), (196, 158, 102), (42, 38, 26)], "wall_09.jpg")

# ---------------------------------------------------------------- 图标 & TV banner
# 品牌字标「lofi」：DM Serif Display 小写 + 暖金光晕 + 深蓝黑圆角块（用户提供参考样式）
FONT_DIR = os.path.join(ROOT, "app/src/main/assets/fonts")
LOGO_FONT = os.path.join(ROOT, "tools/fonts/DMSerifDisplay-Regular.ttf")
SANS = os.path.join(FONT_DIR, "Jost.ttf")                # 与应用内标签同款

TILE_DARK = (13.0, 19.0, 27.0)   # 深蓝黑块面
GOLD = (238, 190, 130)           # 光晕金
GOLD_HI = (247, 210, 163)        # 字面亮金

def film_grain(img, sigma, seed):
    rng = np.random.default_rng(seed)
    return np.clip(img + rng.normal(0, sigma, img.shape[:2] + (1,)), 0, 255)

def glow_wordmark(im, text, cx, cy, width, blur, track=0.055):
    """小写暖金字标 + 双层光晕；track 为字距（字号的比例），逐字排布避免 f/i 相贴"""
    f0 = ImageFont.truetype(LOGO_FONT, 100)
    nat = sum(f0.getlength(c) for c in text)
    size = max(8, int(100 * width / (nat + 100 * track * (len(text) - 1))))
    f = ImageFont.truetype(LOGO_FONT, size)
    gap = size * track
    total = sum(f.getlength(c) for c in text) + gap * (len(text) - 1)

    def stamp(fill):
        layer = Image.new("RGBA", im.size, (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        x = cx - total / 2
        for c in text:
            d.text((x, cy), c, font=f, fill=fill, anchor="lm")
            x += f.getlength(c) + gap
        return layer

    for _ in range(2):
        im = Image.alpha_composite(im, stamp(GOLD + (255,)).filter(ImageFilter.GaussianBlur(blur)))
    return Image.alpha_composite(im, stamp(GOLD_HI + (255,)))

def rounded_tile(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.size[0]-1, img.size[1]-1], radius=radius, fill=255)
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out

def make_icon():
    """「lofi」字标图标：深蓝黑圆角块 + 小写暖金字标 + 光晕 + 颗粒"""
    S = 1024
    yy, xx = np.mgrid[0:S, 0:S]
    y = yy / S
    img = np.zeros((S, S, 3), dtype=np.float64)
    img[:] = TILE_DARK
    img += y[..., None] * np.array([5.0, 5.0, 7.0])                       # 下部微亮
    img += np.clip(1 - y * 3.2, 0, 1)[..., None] * np.array([8.0, 8.0, 10.0])  # 顶部微光
    im = Image.fromarray(film_grain(img, 2.0, 11).astype(np.uint8)).convert("RGBA")
    im = glow_wordmark(im, "lofi", S * 0.5, S * 0.5 - 8, S * 0.66, 26)
    im = rounded_tile(im, int(S * 0.22))
    for dpi, folder in [(48, "mipmap-mdpi"), (72, "mipmap-hdpi"), (96, "mipmap-xhdpi"),
                        (144, "mipmap-xxhdpi"), (192, "mipmap-xxxhdpi")]:
        outdir = os.path.join(RES, folder)
        os.makedirs(outdir, exist_ok=True)
        im.resize((dpi, dpi), Image.LANCZOS).save(os.path.join(outdir, "ic_launcher.png"))
    print("  -> ic_launcher.png (48/72/96/144/192)")

def make_tv_banner():
    """TV banner：lofi room 小写字标 + 标语，同一套光晕语言"""
    W, H = 640, 360  # 320x180 dp @2x
    yy, xx = np.mgrid[0:H, 0:W]
    x, y = xx / W, yy / H
    img = np.zeros((H, W, 3), dtype=np.float64)
    img[:] = TILE_DARK
    img += y[..., None] * np.array([7.0, 7.0, 9.0])
    pool = np.exp(-(((x - 0.5) / 0.75) ** 2 + ((y - 1.05) / 0.5) ** 2))
    img += pool[..., None] * np.array([30.0, 24.0, 18.0])     # 底部微光（克制）
    im = Image.fromarray(film_grain(img, 1.8, 23).astype(np.uint8)).convert("RGBA")
    im = glow_wordmark(im, "lofi room", W * 0.5, H * 0.42, 400, 11)
    d = ImageDraw.Draw(im)
    f2 = ImageFont.truetype(SANS, 15)

    def tracked_text(d, xy, text, font, fill, spacing):
        x0, y0 = xy
        for ch in text:
            d.text((x0, y0), ch, font=font, fill=fill)
            x0 += d.textlength(ch, font=font) + spacing

    sub = "MUSIC · WALLPAPER · CALM"
    w2 = sum(d.textlength(ch, font=f2) + 5 for ch in sub)
    tracked_text(d, ((W - w2) / 2, 252), sub, f2, (216, 181, 126), 5)
    os.makedirs(os.path.join(RES, "drawable"), exist_ok=True)
    im.convert("RGB").save(os.path.join(RES, "drawable", "tv_banner.png"))
    print("  -> drawable/tv_banner.png")

def make_splash_logo():
    """启动遮罩字标：透明底的 lofi 暖金光晕字（splash 淡入淡出用）"""
    W, H = 1024, 320
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    im = glow_wordmark(im, "lofi", W * 0.5, H * 0.5 - 4, W * 0.62, 22)
    out = os.path.join(RES, "drawable", "splash_logo.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    im.save(out)
    print("  -> drawable/splash_logo.png")

print("== 生成图标与 banner ==")
make_icon()
make_tv_banner()
make_splash_logo()
print("done.")
