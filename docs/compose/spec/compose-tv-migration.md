---
feature: compose-tv-migration
status: planned
updated: 2026-11-14
source: https://github.com/ycyx1994/lofi-room
---

# Compose for TV 重构计划

## [S1] Problem

面板的列表/宫格/页签/光标全部手写（ListView/GridView + 自定义 adapter + `panelKey`
手写二维导航 + `dispatchKeyEvent` 拦截 + adapter 内自绘光标）。v1.3 迭代中连续修掉
三类"重造焦点/选择系统"的典型缺陷：

1. 焦点停在可点击面板元素上时，`View.onKeyDown` 吞掉单击 OK（仅长按 repeat 漏下）；
2. GridView `listSelector` 在真机不绘制，选择光标完全不可见；
3. 手写二维导航与描边圆角/图片裁剪的几何一致性问题（描边四角被缩略图盖断）。

官方背景：`androidx.leanback` 已被标记 deprecated（Use Compose for TV instead），
`androidx.tv:tv-material` 1.1.0 / `tv-foundation` 1.0.0 均已稳定（minSdk 21），
是 Google 对 TV UI 的长期路线。焦点指示（border/glow/scale）、遥控输入语义、
触摸点击由组件内建，正是本项目反复踩坑的问题域。

## [S2] Design

### S2.1 范围

**重构**（交互层）：播放列表面板 —— 页签、曲目列表、背景宫格、自动切换开关、
光标/已应用视觉、面板级按键语义。

**保留不动**（渲染与数据层）：

- `WallpaperView`（Canvas 轮播/推镜/溶解）、`VisualizerView`（Canvas 波形）
- `AudioEngine` / `VisualizerTap`、`Playlist` 数据、assets 素材
- 壁纸上的触摸手势（轻点/滑动/长按开面板）
- 全局快捷键（播放/切歌/音量 OSD/MENU/双击 BACK）

**策略**：渐进式 —— 用 `ComposeView` 只承载面板，Activity 其余 View 原样嵌入。

### S2.2 工具链前置（P0）

- Kotlin 1.9.24 → 2.x（Compose Compiler 随 Kotlin 2.0+ 内置），AGP 8.5.2 → 8.7+
- `buildFeatures.compose = true` + Compose BOM；Media3 1.4.1 兼容性单独验证
- 依赖：`androidx.tv:tv-material:1.1.0`、`androidx.activity:activity-compose`
- 注意：tv-foundation 的 `TvLazy*` 已弃用，用标准 `LazyColumn`/`LazyVerticalGrid`
  + `focusRestorer`；`tv-material` 1.1.x 消费方需 KGP 2.0+

### S2.3 组件映射

| 现状（手写） | Compose for TV |
|---|---|
| panel_wrap + 面板卡片 | `ComposeView` + `Surface` |
| tab_tracks / tab_backgrounds | `TabRow` + `Tab` |
| track_list（ListView + row_cursor） | `LazyColumn` + `ListItem` |
| wall_grid（GridView + cell_cursor） | `LazyVerticalGrid` + `CompactCard` |
| 自动切换开关（格 0） | `ListItem`/`Surface` + `Switch` |
| 光标 / 已应用金框 | Card 焦点 border（内建）+ selected 状态边框 |
| panelKey / dispatchKeyEvent 拦截 | Compose 焦点系统 + `focusProperties` |
| OK 应用 / 触摸点按 | `onClick`（遥控 OK 与触摸点击同一语义） |

### S2.4 按键模型

- 删除 `panelKey`、`panelCursor`/`movePanelCursor`、自绘光标与
  `dispatchKeyEvent` 面板拦截；面板打开时方向键全部交给 Compose 焦点系统
- 保留 Activity `onKeyDown` 全局快捷键；面板打开时 BACK/MENU 关面板
- 「行端 ←→ 切页签」用 `FocusProperties.onEnter/onExit`（tv-material 1.1 API）
  或外层 key 拦截复刻，提示文案同步
- 焦点记住位置用 `focusRestorer`

### S2.5 视觉对齐

- 米色=光标、金色=已应用 的设计语言映射到 Card 的 focused border 与
  selected border；缩略图 `centerCrop` + 圆角沿用
- tv-material 默认 glow/scale 动效偏"亮"，与本项目静谧氛围冲突 ——
  关 scale、仅保留细边框（组件均支持定制，见 Border/Glow API）

## [S3] Phases

- **P0 工具链升级**：Kotlin 2.x + Compose 依赖，不改任何 UI。
  acceptance：`assembleDebug`/`assembleRelease` 通过，真机行为与 v1.3 一致。
- **P1 面板壳**：ComposeView + Surface + TabRow，页签可遥控/触摸切换，内容暂为占位。
  acceptance：遥控 ←→ 切页签、BACK/MENU 关面板；触摸点按页签。
- **P2 曲目页**：LazyColumn + ListItem；OK/点按播放并收起面板，PLAYING 标记随状态刷新。
  acceptance：与 v1.3 曲目页行为逐项一致。
- **P3 背景页**：LazyVerticalGrid + Switch + Card；OK 应用（面板保持打开）、
  金框跟随、端点切页签；缩略图复用 `decodeThumb`/`thumbCache`（后台线程）。
  acceptance：v1.3 背景页全部交互与视觉回归通过。
- **P4 清理**：删除 panelKey/自绘光标/cell_cursor、row_cursor、thumb_clip 等；
  README 按键表与本 spec 更新为 delivered。

每阶段均在小米电视 Pro MiniLED 真机做遥控 + 触摸双验证。

## [S4] Risks

1. Kotlin/AGP 升级连锁（Media3、lint、KSP）——P0 单独成段，失败可回退不动 UI。
2. Compose 焦点与 Activity onKeyDown 分工不清会重现"双处理/吞键"——
   面板打开时方向键必须只进 Compose，全局键只留 BACK/MENU/媒体键。
3. tv-material 默认动效与氛围感冲突——用 Border 定制压掉 scale/glow（S2.5）。
4. 缩略图解码线程模型迁移——继续沿用现有后台解码 + 主线程回填缓存。
5. minSdk 21：tv-material 1.1 支持 ✓；注意 leanback 路线仅作备选（已弃用）。
