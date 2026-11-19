# Lofi Room TV — R8 保留规则
#
# 原则：R8 开启后（isMinifyEnabled/isShrinkResources = true）宁可多 keep，也不要上线即崩。
# 反射入口、JNI/可选依赖、注解与泛型签名一旦被裁，崩溃点都在运行时（且往往只在特定设备/特定
# 播放路径上出现），成本远高于多留几 KB dex。等攒够真机回归样本再逐条收窄。

# ── Media3 ────────────────────────────────────────────────────────────────
# media3 的 renderer / MediaSource.Factory / Downloader 都是 DefaultRenderersFactory、
# DefaultMediaSourceFactory、DefaultDownloaderFactory 里按类名反射实例化的，裁掉就是
# IllegalAccessException / NoSuchMethodException → 播放器 NullPointer。
# media3-*.aar 自带 proguard.txt（已在 ~/.gradle 缓存里核对过 media3-exoplayer 1.4.1），
# 但仍整包保留：AudioEngine 用匿名 DefaultRenderersFactory 覆写了 buildAudioSink，
# 一旦漏 keep 表现为「能起播不出声」这种最难定位的线上问题。
-keep class androidx.media3.** { *; }
# media3 1.4.1 里对可选解码器/平台私有类（libvpx、libgav1、ffmpeg、opus、flac、midi、
# 部分 API 34+ 平台类）有编译期引用，本工程不打包这些扩展库：只静音告警，不要让 R8 卡死构建。
-dontwarn androidx.media3.**

# ── NanoHTTPD（#23 局域网 H5 管理页） ──────────────────────────────────────
# NanoHTTPD 的 ServerSocketFactory / 匿名 Service 内部类大量走反射与动态类名，
# 且 2.3.1 对 javax 可选依赖有编译期引用。裁掉即「管理页 404 或直接起不来服务」。
-keep class org.nanohttpd.** { *; }
-dontwarn org.nanohttpd.**

# ── zxing core（#23 二维码编码） ───────────────────────────────────────────
# QrCode.kt 走 QRCodeWriter → Encoder → ByteMatrix/BitMatrix 的具体实现链，
# 其中 encoder/decoder 的 ErrorCorrectionLevel 枚举与 qrcode 包内部类按类型查找；
# 只做 encode 也保留整包（core 只有几百 KB，qrcode 分支裁错就是空 Bitmap 或 NPE）。
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ── 本工程的 Audio 包 ─────────────────────────────────────────────────────
# AudioEngine 里的匿名 DefaultRenderersFactory 子类（覆写 buildAudioSink）以及
# TeeAudioProcessor / VisualizerTap 在 ExoPlayer 内部被回调，类名被反射路径牵到；
# 整包保留，防「R8 重命名后 buildAudioSink 不再被调用 → 波形永远静止」。
-keep class com.lofiroom.tv.audio.** { *; }

# ── Manifest 引用组件 ────────────────────────────────────────────────────
# AGP 会为 AndroidManifest 里的组件自动生成 keep 规则，这里显式再兜一层：
# UpdateResultReceiver / UpdateTestReceiver 及 FileProvider 由系统按类名实例化，
# 构造器被裁就是 ClassNotFoundException / InstantiationException（装包后才炸）。
-keepclassmembers class * extends android.content.BroadcastReceiver { <init>(...); }
-keepclassmembers class * extends android.content.ContentProvider { <init>(...); }

# ── 元数据 ───────────────────────────────────────────────────────────────
# 注解（含 Compose/Kotlin 运行时查找的注解）、泛型 Signature、内部类/外围类信息：
# R8 默认可能整段丢弃，保留后反射查泛型、Gson 式反序列化、协程/内联类的调试链路才可用；
# 代价只是 dex 里多几段字符串，换来 crash 栈可读与反射不返回 null 类型。
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
