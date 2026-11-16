package com.lofiroom.tv.ui

import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.lofiroom.tv.R
import com.lofiroom.tv.data.Track

// #compose-tv-migration P1-P3：面板 Compose 实现。
// 焦点/点击/遥控+触摸交互交给 Compose 焦点系统（不再手写 panelKey），
// 视觉沿用 v1.3 设计语言：金色=已应用、米色=光标、20dp 同心圆角、暗色卡片。

enum class PanelTab { TRACKS, BACKGROUNDS, CUSTOM }

private val Gold = Color(0xFFD8B57E)
private val Cream = Color(0xFFF6F0E6)
private val CreamDim = Color(0xFFC8BCA9)
private val PanelFill = Color(0xE6120E0B)
private val PanelStroke = Color(0x26D8B57E)
private val CursorFill = Color(0xD9120E0B)
private val CellShape = RoundedCornerShape(20.dp)

@Composable
fun LofiPanel(
    tab: PanelTab,
    onTabChange: (PanelTab) -> Unit,
    tracks: List<Track>,
    playingIndex: Int,
    playing: Boolean,
    onTrackClick: (Int) -> Unit,
    wallCount: Int,
    thumbOf: (Int) -> Bitmap?,
    appliedIndex: Int,
    onWallClick: (Int) -> Unit,
    autoRotate: Boolean,
    onAutoRotateToggle: () -> Unit,
    qrBitmap: Bitmap?,
    serverUrl: String,
    tabsFocus: FocusRequester,
    openTick: Int,
) {
    val context = LocalContext.current
    val serif = remember {
        FontFamily(Typeface.createFromAsset(context.assets, "fonts/CormorantGaramond.ttf"))
    }
    val sans = remember {
        FontFamily(Typeface.createFromAsset(context.assets, "fonts/Jost.ttf"))
    }

    // #compose-tv-migration 面板每次打开后把焦点交给页签（LaunchedEffect 保证组合完成后执行）
    LaunchedEffect(openTick) {
        if (openTick > 0) runCatching { tabsFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .width(480.dp)
            .background(PanelFill, CellShape)
            .border(1.dp, PanelStroke, CellShape)
            // 消费卡片本体的点按（含 padding/间隙），避免穿透到遮罩触发关闭
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(start = 30.dp, top = 26.dp, end = 30.dp, bottom = 22.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PanelTabText(
                    text = stringResource(R.string.panel_title),
                    selected = tab == PanelTab.TRACKS,
                    fontFamily = sans,
                    focusRequester = tabsFocus,
                    onClick = { onTabChange(PanelTab.TRACKS) },
                )
                Spacer(Modifier.width(22.dp))
                PanelTabText(
                    text = stringResource(R.string.panel_title_bg),
                    selected = tab == PanelTab.BACKGROUNDS,
                    fontFamily = sans,
                    onClick = { onTabChange(PanelTab.BACKGROUNDS) },
                )
                Spacer(Modifier.width(22.dp))
                PanelTabText(
                    text = stringResource(R.string.panel_title_custom),
                    selected = tab == PanelTab.CUSTOM,
                    fontFamily = sans,
                    onClick = { onTabChange(PanelTab.CUSTOM) },
                )
            }

            Spacer(Modifier.height(16.dp))

            Box(Modifier.height(192.dp).fillMaxWidth()) {
                when (tab) {
                    PanelTab.TRACKS -> TrackList(
                        tracks = tracks,
                        playingIndex = playingIndex,
                        playing = playing,
                        onTrackClick = onTrackClick,
                        serif = serif,
                        sans = sans,
                    )

                    PanelTab.BACKGROUNDS -> WallGrid(
                        wallCount = wallCount,
                        thumbOf = thumbOf,
                        appliedIndex = appliedIndex,
                        onWallClick = onWallClick,
                        autoRotate = autoRotate,
                        onAutoRotateToggle = onAutoRotateToggle,
                        sans = sans,
                    )

                    PanelTab.CUSTOM -> CustomPane(
                        qrBitmap = qrBitmap,
                        serverUrl = serverUrl,
                        sans = sans,
                        serif = serif,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(
                    when (tab) {
                        PanelTab.TRACKS -> R.string.panel_hint
                        PanelTab.BACKGROUNDS -> R.string.panel_hint_bg
                        PanelTab.CUSTOM -> R.string.panel_hint_custom
                    }
                ),
                color = CreamDim,
                fontFamily = sans,
                fontSize = 10.sp,
                letterSpacing = 0.2.sp,
                maxLines = 1,
            )
        }
    }
}

/** 页签：获得焦点即切换（TV 惯例），选中金色、未选弱化 */
@Composable
private fun PanelTabText(
    text: String,
    selected: Boolean,
    fontFamily: FontFamily,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused && !selected) onClick()
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = if (selected || focused) Gold else CreamDim,
            fontFamily = fontFamily,
            fontSize = 11.sp,
            letterSpacing = 0.4.sp,
        )
    }
}

/** #23 自定义中心：二维码 + 管理页地址；无局域网时给引导文案 */
@Composable
private fun CustomPane(
    qrBitmap: Bitmap?,
    serverUrl: String,
    sans: FontFamily,
    serif: FontFamily,
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(156.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Cream),
            )
            Spacer(Modifier.width(20.dp))
        }
        Column(verticalArrangement = Arrangement.Center) {
            Text(
                text = if (qrBitmap != null) "手机扫码打开管理页" else "请连接局域网",
                color = Gold,
                fontFamily = serif,
                fontSize = 17.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (qrBitmap != null) serverUrl else "连接 Wi-Fi 后这里会出现二维码",
                color = CreamDim,
                fontFamily = sans,
                fontSize = 10.sp,
                letterSpacing = 0.2.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "背景 · 照片上传    音乐 · 上传管理",
                color = CreamDim,
                fontFamily = sans,
                fontSize = 9.sp,
                letterSpacing = 0.2.sp,
            )
        }
    }
}

@Composable
private fun TrackList(
    tracks: List<Track>,    playingIndex: Int,
    playing: Boolean,
    onTrackClick: (Int) -> Unit,
    serif: FontFamily,
    sans: FontFamily,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(tracks.size) { i ->
            val t = tracks[i]
            TrackRow(
                no = "%02d".format(i + 1),
                title = t.title,
                sub = t.sub,
                state = if (i == playingIndex && playing) "PLAYING" else "",
                serif = serif,
                sans = sans,
                onClick = { onTrackClick(i) },
            )
        }
    }
}

@Composable
private fun TrackRow(
    no: String,
    title: String,
    sub: String,
    state: String,
    serif: FontFamily,
    sans: FontFamily,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .background(if (focused) Color(0x24D8B57E) else Color.Transparent, CellShape)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 13.dp),
        ) {
            Text(no, color = Gold, fontFamily = sans, fontSize = 12.sp, letterSpacing = 0.1.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Cream, fontFamily = serif, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text(sub, color = CreamDim, fontFamily = sans, fontSize = 9.sp, letterSpacing = 0.25.sp)
            }
            Text(state, color = Gold, fontFamily = sans, fontSize = 10.sp, letterSpacing = 0.2.sp)
        }
    }
}

@Composable
private fun WallGrid(
    wallCount: Int,
    thumbOf: (Int) -> Bitmap?,
    appliedIndex: Int,
    onWallClick: (Int) -> Unit,
    autoRotate: Boolean,
    onAutoRotateToggle: () -> Unit,
    sans: FontFamily,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            ToggleCell(autoRotate = autoRotate, onToggle = onAutoRotateToggle, sans = sans)
        }
        items(wallCount) { i ->
            WallCell(
                thumb = thumbOf(i),
                applied = i == appliedIndex,
                onClick = { onWallClick(i) },
            )
        }
    }
}

/** 格 0：自动切换开关（整格可点，ON/OFF 文案沿用 v1.3） */
@Composable
private fun ToggleCell(autoRotate: Boolean, onToggle: () -> Unit, sans: FontFamily) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .height(92.dp)
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onToggle)
            .cellFrame(focused = focused, applied = false)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(
                stringResource(R.string.auto_rotate),
                color = CreamDim,
                fontFamily = sans,
                fontSize = 12.sp,
                letterSpacing = 0.15.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.auto_rotate_sub),
                color = CreamDim,
                fontFamily = sans,
                fontSize = 9.sp,
                letterSpacing = 0.1.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                if (autoRotate) "ON" else "OFF",
                color = if (autoRotate) Gold else CreamDim,
                fontFamily = sans,
                fontSize = 18.sp,
                letterSpacing = 0.2.sp,
            )
        }
    }
}

@Composable
private fun WallCell(thumb: Bitmap?, applied: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .height(92.dp)
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .cellFrame(focused = focused, applied = applied)
    ) {
        if (thumb != null) {
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(2.dp)
                    .clip(RoundedCornerShape(18.dp)),
            )
        }
    }
}

/** v1.3 视觉语言：光标=米色描边，已应用=金色描边，无描边=素格（20dp 同心圆角） */
private fun Modifier.cellFrame(focused: Boolean, applied: Boolean): Modifier = this
    .clip(CellShape)
    .background(if (focused) CursorFill else PanelFill, CellShape)
    .then(
        when {
            focused -> Modifier.border(2.dp, Cream, CellShape)
            applied -> Modifier.border(2.dp, Gold, CellShape)
            else -> Modifier.border(1.dp, PanelStroke, CellShape)
        }
    )
