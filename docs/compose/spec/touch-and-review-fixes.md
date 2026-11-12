---
feature: touch-and-review-fixes
status: designed
updated: 2026-11-12
source: feature/touch-and-review-fixes
---

# 触摸支持与 Review 修复

## Report

## [S1] Problem

代码评审（2026-11-12）确认了两个 bug 和三个小问题，另外应用只支持遥控器 D-pad，触摸设备无法操控：

1. 暂停时波形柱冻结在最后一帧，而不是缓缓落下（`VisualizerView.kt:47` 只衰减了 tap 内部数组，视图侧 `levels` 从未刷新）。
2. 壁纸轮播遇到解码失败的文件会永久卡死（`WallpaperView.advance()` 失败时不推进 `index`，反复重试同一个坏文件）。
3. 仅支持遥控器：无任何触摸手势，播放列表面板也无法点按选曲。
4. 播放出错（如曲目文件损坏）时静默停止，不跳下一首、无提示。
5. `WallpaperView.loadList()` 在主线程调用 `assets.list()` 做磁盘 I/O。
6. 播放列表面板的 PLAYING 标记只在手动选曲时刷新，自动切歌后不更新。

## [S2] Design

### S2.1 波形暂停衰减

`VisualizerView.onDraw` 在非播放分支先 `tap.decay()` 再 `tap.snapshot(levels)`，让视图侧电平随衰减落下。播放分支保持只 `snapshot`。

### S2.2 轮播坏图跳过

`WallpaperView.advance()` 解码失败时也把 `index` 推进到 `next`，下一帧重试下一张而非同一张。连续失败计数 `failStreak`：达 `names.size`（完整一轮无一成功）时置 `switchAtMs = now` 暂停轮询 `holdMs`，避免全部文件损坏时的忙循环；任一成功即清零。`decode` 改为接收文件名参数（不再读 `names` 字段），消除跨线程读写。

### S2.3 触摸手势（翻页式）

挂在 `WallpaperView` 上的 `android.view.GestureDetector`（不新增依赖）：

| 手势 | 行为 |
|---|---|
| 轻点（singleTapConfirmed） | 播放 / 暂停 |
| 左滑（onFling，水平主导且 dx<0） | 下一首 |
| 右滑（水平主导且 dx>0） | 上一首 |
| 上滑（垂直主导且 dy<0） | 音量 +5% |
| 下滑（垂直主导且 dy>0） | 音量 −5% |
| 长按 | 播放列表面板 开/关 |

判定：`|dx| > |dy|` 走水平，否则走垂直。音量步长、OSD 与遥控器共用 `changeVolume`。注意左右滑方向与遥控器 ←(上一首)/→(下一首) 相反——触摸按手机翻页习惯，用户已确认。面板打开时手势不生效（`panel_wrap` 可点击拦截）。

### S2.4 面板触摸点选

`trackList.onItemClickListener`：点按行即 `playIndex(position)` + 刷新 + 收起面板，与遥控器 OK 行为一致。面板卡片本体（内层 LinearLayout）设为 clickable 消费点击（点卡片空白处不关闭）；点面板外暗色区域关闭面板。

### S2.5 出错跳下一首

`AudioEngine` 的 `Player.Listener` 增加 `onPlayerError`：计数连续失败，未满 `Playlist.tracks.size` 则 `seekToNextMediaItem()` + `prepare()` + 恢复播放；满一轮全部失败则 `playWhenReady = false` 停止（防坏资产忙循环）。`onIsPlayingChanged(true)` 时清零计数。

### S2.6 loadList I/O 线程化

壁纸目录列举与首张解码合并进 executor 单任务，主线程只在 `post` 回调里赋值 `names`/`current`。

### S2.7 面板 PLAYING 刷新

`MainActivity` 的 `onTrackChanged` 与 `onPlayingChanged` 里，面板可见时 `trackList.invalidateViews()`。

## [S3] Out of Scope

- 前台服务 / MediaSession（保持 Activity 内播放的现状）
- Goertzel 分析窗口 1024→2048（视觉调优，非缺陷）
- JVM 单元测试基础设施（Android View 逻辑需 Robolectric，超出本次范围）
- release 正式签名（需用户提供密钥）
- 遥控器既有按键映射、双击 BACK 退出、音量 OSD 视觉

## Tasks

- [ ] T1: 修复波形暂停衰减 — acceptance: 暂停后波形柱在 1–2 秒内缓缓落到底部而非冻结 (covers: S2.1)
- [ ] T2: 修复轮播坏图卡死 — acceptance: 夹入一张损坏 jpg 后轮播跳过它继续换图；全部损坏时不再忙循环 (covers: S2.2)
- [ ] T3: 实现触摸手势 — acceptance: 轻点播放/暂停、左右滑切歌、上下滑调音量、长按开关面板 (covers: S2.3)
- [ ] T4: 面板触摸点选 — acceptance: 点按曲目行即播放并收起面板；点暗色区域关闭面板 (covers: S2.4)
- [ ] T5: 出错自动跳下一首 — acceptance: 移除/损坏一个 m4a 后播放自动进入下一首，全部坏时停止轮询 (covers: S2.5)
- [ ] T6: loadList 挪出主线程 — acceptance: 壁纸目录列举与首图解码不在主线程执行 (covers: S2.6)
- [ ] T7: 面板 PLAYING 随状态刷新 — acceptance: 自动切歌或暂停/恢复后面板标记同步 (covers: S2.7)
- [ ] T8: README 补充触摸交互说明 — acceptance: README 遥控器表格旁有触摸手势表
- [ ] T9: 构建验证 — acceptance: `./gradlew :app:assembleDebug` 通过

## Journey log
