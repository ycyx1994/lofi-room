package com.lofiroom.tv.ui

import android.graphics.Bitmap
import android.graphics.Typeface
import android.view.ViewConfiguration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    pendingDeleteWall: Int,
    wallDeletable: (Int) -> Boolean,
    onWallDeleteRequest: (Int) -> Unit,
    onWallDeleteConfirm: () -> Unit,
    onWallDeleteDismiss: () -> Unit,
    autoRotate: Boolean,
    onAutoRotateToggle: () -> Unit,
    qrBitmap: Bitmap?,
    serverUrl: String,
    updateActionLabel: String,
    updateStatus: String,
    updateNotes: String,
    onUpdateAction: () -> Unit,
    onUpdateFocusChange: (Boolean) -> Unit,
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
        if (openTick > 0) {
            onWallDeleteDismiss() // 面板重开清掉悬挂的删除确认
            runCatching { tabsFocus.requestFocus() }
        }
    }

    Box {
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
                        wallDeletable = wallDeletable,
                        onWallLongClick = onWallDeleteRequest,
                        autoRotate = autoRotate,
                        onAutoRotateToggle = onAutoRotateToggle,
                        sans = sans,
                    )

                    PanelTab.CUSTOM -> CustomPane(
                        qrBitmap = qrBitmap,
                        serverUrl = serverUrl,
                        updateActionLabel = updateActionLabel,
                        updateStatus = updateStatus,
                        updateNotes = updateNotes,
                        onUpdateAction = onUpdateAction,
                        onUpdateFocusChange = onUpdateFocusChange,
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

        if (pendingDeleteWall >= 0) {
            DeleteWallDialog(
                // matchParentSize 只匹配面板卡、不参与外层测量：
                // ComposeView 是 wrap_content（靠右居中），fillMaxSize 会把它撑成全屏导致整页错位
                modifier = Modifier.matchParentSize(),
                onConfirm = onWallDeleteConfirm,
                onDismiss = onWallDeleteDismiss,
                sans = sans,
                serif = serif,
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
    updateActionLabel: String,
    updateStatus: String,
    updateNotes: String,
    onUpdateAction: () -> Unit,
    onUpdateFocusChange: (Boolean) -> Unit,
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
                text = if (qrBitmap != null) stringResource(R.string.custom_qr_title) else stringResource(R.string.custom_no_lan),
                color = Gold,
                fontFamily = serif,
                fontSize = 17.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (qrBitmap != null) serverUrl else stringResource(R.string.custom_no_qr),
                color = CreamDim,
                fontFamily = sans,
                fontSize = 10.sp,
                letterSpacing = 0.2.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.custom_manage_hint),
                color = CreamDim,
                fontFamily = sans,
                fontSize = 9.sp,
                letterSpacing = 0.2.sp,
            )

            // 应用内更新：检查/下载按钮 + 状态行（焦点态上报，CUSTOM 页签 OK 不再一律放大二维码）
            Spacer(Modifier.height(10.dp))
            var updateFocused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .onFocusChanged {
                        updateFocused = it.isFocused
                        onUpdateFocusChange(it.isFocused)
                    }
                    .clickable(onClick = onUpdateAction)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (updateFocused) Gold.copy(alpha = 0.16f) else Color.Transparent)
                    .border(1.dp, if (updateFocused) Gold else PanelStroke, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = updateActionLabel,
                    color = if (updateFocused) Gold else Cream,
                    fontFamily = sans,
                    fontSize = 11.sp,
                    letterSpacing = 0.2.sp,
                )
            }
            if (updateStatus.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = updateStatus,
                    color = CreamDim,
                    fontFamily = sans,
                    fontSize = 9.sp,
                    letterSpacing = 0.2.sp,
                )
            }
            if (updateNotes.isNotEmpty()) { // D1 发现新版/已更新：release notes 前 2 行摘要
                Spacer(Modifier.height(3.dp))
                Text(
                    text = updateNotes,
                    color = CreamDim,
                    fontFamily = sans,
                    fontSize = 9.sp,
                    letterSpacing = 0.2.sp,
                    maxLines = 2,
                )
            }
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
    wallDeletable: (Int) -> Boolean,
    onWallLongClick: (Int) -> Unit,
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
                deletable = wallDeletable(i),
                onClick = { onWallClick(i) },
                onLongClick = { onWallLongClick(i) },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WallCell(thumb: Bitmap?, applied: Boolean, deletable: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // 自定义格：遥控 OK 短按=应用 / 长按=删除确认；触摸长按走 combinedClickable
    // 长按判定：到系统长按阈值触发一次（连发只切一次），阈值前松手走短按
    val scope = rememberCoroutineScope()
    var longJob by remember { mutableStateOf<Job?>(null) }
    var longFired by remember { mutableStateOf(false) }
    val keyMod = if (deletable) {
        Modifier.onPreviewKeyEvent { e ->
            if (e.key != Key.DirectionCenter && e.key != Key.Enter && e.key != Key.NumPadEnter) {
                false
            } else when (e.type) {
                KeyEventType.KeyDown -> {
                    if (e.nativeKeyEvent.repeatCount == 0) {
                        longFired = false
                        longJob?.cancel()
                        longJob = scope.launch {
                            delay(ViewConfiguration.getLongPressTimeout().toLong())
                            longFired = true
                            onLongClick()
                        }
                    }
                    true
                }
                KeyEventType.KeyUp -> {
                    longJob?.cancel()
                    longJob = null
                    if (!longFired) onClick()
                    longFired = false
                    true
                }
                else -> false
            }
        }
    } else Modifier
    Box(
        modifier = Modifier
            .height(92.dp)
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .then(keyMod)
            .let {
                if (deletable) {
                    it.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    it.clickable(onClick = onClick)
                }
            }
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

/** 删除自定义背景二次确认：默认焦点在「取消」防误删，BACK/点遮罩关闭 */
@Composable
private fun DeleteWallDialog(
    modifier: Modifier,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    sans: FontFamily,
    serif: FontFamily,
) {
    val cancelFocus = remember { FocusRequester() }
    var cancelFocused by remember { mutableStateOf(false) }
    var okFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    Box(
        modifier = modifier
            .background(Color(0xB3000000))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(330.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(PanelFill, RoundedCornerShape(20.dp))
                .border(1.dp, PanelStroke, RoundedCornerShape(20.dp))
                .padding(22.dp)
        ) {
            Text(stringResource(R.string.panel_delete_title), color = Cream, fontFamily = serif, fontSize = 17.sp)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.panel_delete_body), color = CreamDim, fontFamily = sans, fontSize = 10.sp, letterSpacing = 0.2.sp)
            Spacer(Modifier.height(18.dp))
            Row {
                Box(
                    modifier = Modifier
                        .width(110.dp)
                        .height(44.dp)
                        .onFocusChanged { cancelFocused = it.isFocused }
                        .clickable(onClick = onDismiss)
                        .focusRequester(cancelFocus)
                        .cellFrame(focused = cancelFocused, applied = false),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.panel_delete_cancel), color = Cream, fontFamily = sans, fontSize = 13.sp)
                }
                Spacer(Modifier.width(14.dp))
                Box(
                    modifier = Modifier
                        .width(110.dp)
                        .height(44.dp)
                        .onFocusChanged { okFocused = it.isFocused }
                        .clickable(onClick = onConfirm)
                        .cellFrame(focused = okFocused, applied = false),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.panel_delete_confirm), color = Color(0xFFE08078), fontFamily = sans, fontSize = 13.sp)
                }
            }
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
