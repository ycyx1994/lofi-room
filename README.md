# Lofi Room · CHILLNOVA

一个 Android TV 音乐壁纸播放器：打开即播放松音乐（lofi 爵士 / 咖啡馆氛围），
壁纸轮播精美图片，中央优雅文字排版 + 实时音频波形，遥控器即可完整操控。

## 功能

- **开机即播**：启动自动播放 3 首内置放松曲目（自合成，免版权）
- **壁纸轮播**：7 张暖调壁纸，交叉溶解 + Ken Burns 缓慢推镜；面板可手动选图，自动切换默认关闭（开启后 10 分钟一换）
- **实时波形**：72 根对称圆角波形条，挂在播放器音频链路上实时分析（无需录音权限）
- **诗意文案**：每换一张壁纸，左下角文案随之切换（中英双语）
- **遥控器 + 触摸双交互**：
  | 遥控器按键 | 功能 |
  |---|---|
  | OK / 播放暂停 | 播放 / 暂停 |
  | ← / → | 上一首 / 下一首 |
  | ↑ / ↓ | 音量 +5% / −5%（屏幕中央 OSD 提示） |
  | MENU / 设置 / INFO | 面板（←→ 切页签：曲目 / 背景，↑↓ 选择，OK 应用） |
  | BACK | 收起提示 / 关闭面板（不退出，保持氛围） |

  触摸手势（手机 / 平板 / 带触屏电视）：
  | 手势 | 功能 |
  |---|---|
  | 轻点 | 播放 / 暂停 |
  | 左滑 / 右滑 | 下一首 / 上一首（翻页式，与遥控器 ←→ 方向相反） |
  | 上滑 / 下滑 | 音量 +5% / −5% |
  | 长按 | 播放列表面板（点按曲目行直接播放，点面板外区域关闭） |

## 构建

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

安装到电视盒子：

```bash
adb connect [TV_IP]
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

环境要求：JDK 17、Android SDK（compileSdk 34）。Gradle 8.9 由 wrapper 自动下载。

## 目录结构

```
app/src/main/
├── java/com/lofiroom/tv/
│   ├── MainActivity.kt            # 主界面 / 遥控器交互 / 文案联动
│   ├── audio/
│   │   ├── AudioEngine.kt         # ExoPlayer + TeeAudioProcessor 音频引擎
│   │   └── VisualizerTap.kt       # PCM 采集 + Goertzel 频带分析
│   ├── ui/
│   │   ├── WallpaperView.kt       # 壁纸轮播（crossfade + Ken Burns）
│   │   └── VisualizerView.kt      # 音频波形条绘制
│   └── data/Playlist.kt           # 曲目与文案数据
├── assets/
│   ├── music/                     # 3 首内置曲目 (.m4a)
│   ├── wallpapers/                # 7 张壁纸 (wall_01..07.jpg)
│   └── fonts/                     # Cormorant Garamond + Jost
└── res/
    ├── layout/activity_main.xml   # 主布局
    └── drawable/                  # 渐变遮罩 / 面板背景 / TV banner
```

## 自定义

- **换壁纸**：替换 `app/src/main/assets/wallpapers/` 下的图片（建议 2560 宽 16:9），重新构建
- **换音乐**：替换 `app/src/main/assets/music/` 下的 `.m4a`，同步修改
  `app/src/main/java/com/lofiroom/tv/data/Playlist.kt` 中的曲目元数据
- **换文案**：修改 `Playlist.kt` 中的 `captions`
- **素材处理**：`tools/process_wallpapers.py`（文字擦除 + 抽象壁纸 + 图标）、
  `tools/generate_music.py`（numpy 合成三首曲目）可复现全部内置素材

## 技术要点

- 波形可视化走 `DefaultAudioSink.Builder.setAudioProcessors(TeeAudioProcessor)`，
  在解码 PCM 旁路取样做 Goertzel 频带分析——不占麦克风、不需要任何运行时权限
- 壁纸修复管线：边缘偏离掩膜 + 5 层金字塔扩散填充 + 纹理移植 + 胶片颗粒
- 字体：Cormorant Garamond（衬线标题）+ Jost（几何无衬线标签），随包加载
- Media3 ExoPlayer 1.4.1，minSdk 21，leanback launcher 适配
