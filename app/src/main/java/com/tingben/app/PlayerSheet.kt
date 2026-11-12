package com.tingben.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tingben.app.core.PlayQueue
import com.tingben.app.diag.EventLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private fun mmss(ms: Long): String {
    val s = (ms / 1000L).coerceAtLeast(0L)
    return "%02d:%02d".format(s / 60, s % 60)
}

/** 模式按钮只写两个字，点一下切到下一态：顺序 → 随机 → 循环 → 顺序。 */
private fun modeLabel(ord: Int): String = when (ord) {
    1 -> "随机"
    2 -> "循环"
    else -> "顺序"
}

/**
 * R262 · 代码自绘的「书页 + 声波」封面。不引图标库、不进资源文件，
 * 深浅主题随调用方 tint。只用 drawCircle / drawLine 两种最稳的重载
 * （R246 真编译教训：drawLine 没有传 Stroke 对象的重载，只能具名 strokeWidth）。
 */
@Composable
internal fun PlayerCover(tint: Color, coverSize: Dp = 180.dp, playing: Boolean = false) {
    Canvas(Modifier.size(coverSize)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val sw = (w * 0.012f).coerceAtLeast(1.5f)
        fun ln(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(w * x1, h * y1), Offset(w * x2, h * y2),
                strokeWidth = sw, cap = StrokeCap.Round)
        drawCircle(tint.copy(alpha = 0.10f), radius = w * 0.44f, center = Offset(cx, cy))
        drawCircle(tint.copy(alpha = 0.18f), radius = w * 0.34f,
            center = Offset(cx, cy), style = Stroke(width = sw))
        // 摊开的书：左右两页 + 中缝
        ln(0.28f, 0.40f, 0.48f, 0.36f)
        ln(0.48f, 0.36f, 0.48f, 0.62f)
        ln(0.48f, 0.62f, 0.28f, 0.66f)
        ln(0.52f, 0.36f, 0.72f, 0.40f)
        ln(0.72f, 0.40f, 0.72f, 0.66f)
        ln(0.72f, 0.66f, 0.52f, 0.62f)
        ln(0.48f, 0.62f, 0.52f, 0.62f)
        // 页上的字行
        ln(0.33f, 0.46f, 0.44f, 0.44f)
        ln(0.33f, 0.53f, 0.44f, 0.51f)
        ln(0.56f, 0.44f, 0.67f, 0.46f)
        ln(0.56f, 0.51f, 0.67f, 0.53f)
        // 声波：播放时竖条高一点，一眼看得出在响
        val bars = if (playing) floatArrayOf(0.10f, 0.20f, 0.28f, 0.20f, 0.10f)
                   else floatArrayOf(0.08f, 0.12f, 0.16f, 0.12f, 0.08f)
        bars.forEachIndexed { i, v ->
            val x = 0.34f + i * 0.08f
            ln(x, 0.76f - v / 2f, x, 0.76f + v / 2f)
        }
    }
}

/** R303 · 播放模式单按钮：顺序 / 随机 / 循环三态，点一下切一态。 */
@Composable
private fun ModeChip(label: String, onClick: () -> Unit) {
    Box(Modifier.height(44.dp)
        .background(palette.primarySoft, RoundedCornerShape(50))
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = palette.primary)
    }
}

/**
 * R133 · 统一播放器页：点底部常驻条展开，通知栏点进来也展开。
 * 用全屏浮层实现，不新增 Screen 枚举值 —— 它不是「另一页」，是同一份播放状态的展开视图。
 * 全部播放控制共用 PlayQueue.state 这一个真值源，通知栏、常驻条、这里三处永远同步。
 *
 * R303 · 跟手：整页位移由外层按拖拽量实时给，拖动中每帧跟手，松手才补回弹/滑出动画。
 * 下滑手势挂在整页 Column 上，子控件（列表滚动 / 进度条）先消费事件，不抢它们的手势。
 * 砍掉睡眠定时与 ±15/30s 跳转；快进快退的位置换成播放模式三态按钮；
 * 播放列表从顶栏挪到右下角，显示的是「当前播放的音频列表」而不是整本书的章节表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSheet(
    c: Context, bookId: String?, onClose: () -> Unit, onGoBook: (String) -> Unit,
    onPullDown: (Float) -> Unit = {},
    onPullSettle: () -> Unit = {}
) {
    val st by PlayQueue.state.collectAsState()
    val modeOrd by PlayQueue.modeState.collectAsState()
    BackHandler(enabled = true) { onClose() }
    LaunchedEffect(st.preview) { if (st.preview) onClose() }

    LaunchedEffect(st.isPlaying) {
        if (!st.isPlaying) return@LaunchedEffect
        while (isActive) {
            PlayQueue.tick()
            delay(300L)
        }
    }

    // R303 · 列表就是当前播放队列本身，不再按 bookId 另扫一遍整本书的成品。
    // 在首页听片段、进某本书听章，这里自动跟着切，无需要用户再选一次书。
    val list = remember(st.queueId) {
        PlayQueue.snapshot().mapIndexed { i, t -> i to t }
    }
    var showList by remember { mutableStateOf(false) }
    val dur = st.durationMs
    val frac = if (dur > 0) (st.positionMs.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f
    val left = (dur - st.positionMs).coerceAtLeast(0L)

    Column(Modifier.fillMaxSize().background(palette.bg)
        .statusBarsPadding().navigationBarsPadding()
        .pointerInput(Unit) {
            var acc = 0f
            detectVerticalDragGestures(
                onDragStart = { acc = 0f },
                onDragEnd = { onPullSettle() },
                onDragCancel = { onPullSettle() },
                // 正负都传，手指往回带时页面也跟着回，位移由外层夹在 [0, 屏高]
                onVerticalDrag = { _, dy ->
                    acc = (acc + dy).coerceAtLeast(0f)
                    onPullDown(acc)
                }
            )
        }) {

        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                IconChevronDown(palette.primary, Modifier.size(24.dp))
            }
            Text("正在听", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = palette.text, modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton(onClick = {
                PlayQueue.release()
                onClose()
            }) { IconClose(palette.sub, Modifier.size(20.dp)) }
        }

        if (showList) {
            Text("当前播放的音频列表", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = palette.primary, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
            if (list.isEmpty()) {
                Text("现在没有在播的音频", fontSize = 13.sp,
                    color = palette.sub, modifier = Modifier.padding(horizontal = 20.dp))
            } else {
                // R285 · 选章列表与下方主控制行 / 功能行同在一个 Column：
                // weight(1f) 已保证不被挤掉，再留 8dp 尾部余量，最后一条不贴控制行。
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(list) { (idx, t) ->
                        Row(Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 3.dp)
                            .background(palette.card, RoundedCornerShape(12.dp))
                            .clickable {
                                // R182 · q 取当前队列 id，不另造一个空串队列；
                                // 列表本身就是这份队列，点第几条就播第几条。
                                val q = st.queueId
                                PlayQueue.playFrom(list.indexOfFirst { it.first == idx }, id = q)
                                showList = false
                            }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("第 ${idx + 1} 段", fontSize = 13.sp,
                                fontWeight = FontWeight.Bold, color = palette.text)
                            Spacer(Modifier.width(10.dp))
                            Text(t.label, fontSize = 12.sp, color = palette.sub,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            if (idx == st.index) {
                                Spacer(Modifier.width(6.dp))
                                Text("正在播", fontSize = 11.sp, color = palette.primary)
                            }
                        }
                    }
                }
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                PlayerCover(palette.primary, 180.dp, st.isPlaying)
                Spacer(Modifier.height(26.dp))
                Text(st.label.ifBlank { "未在播放" }, fontSize = 20.sp,
                    fontWeight = FontWeight.Bold, color = palette.text, maxLines = 2)
                Spacer(Modifier.height(6.dp))
                Text(if (st.index >= 0) "第 ${st.index + 1} 段 · ${mmss(dur)}"
                     else "准备好就可以听了",
                    fontSize = 12.sp, color = palette.sub)
                Spacer(Modifier.height(26.dp))
                Slider(
                    value = frac,
                    onValueChange = { if (dur > 0) PlayQueue.seekTo((it * dur).toLong()) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(mmss(st.positionMs), fontSize = 12.sp, color = palette.sub)
                    Spacer(Modifier.weight(1f))
                    Text("-" + mmss(left), fontSize = 12.sp, color = palette.sub)
                }
                Spacer(Modifier.height(18.dp))
                Text("倍速", fontSize = 12.sp, color = palette.sub)
                // K1 · 合成侧只有「慢/中/快」倾向，播放倍速才是可靠控制点。
                // R158b · 这层关系不进 UI（教程），入口够得着就行。
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlayQueue.SPEED_STEPS.forEach { v ->
                        val label = PlayQueue.speedLabel(v)
                        val on = kotlin.math.abs(st.speed - v) < 0.01f
                        TextButton(onClick = {
                            PlayQueue.setSpeed(v)
                            EventLog.ui("PLAY_SPEED", "x=$v")
                        },
                            modifier = Modifier.background(
                                if (on) palette.primary else palette.card,
                                RoundedCornerShape(50))) {
                            Text(label, fontSize = 12.sp,
                                color = if (on) palette.card else palette.sub)
                        }
                    }
                }
            }
        }

        // 主控制行：上一章 / 播放模式三态 / 播放 / 下一章。
        // R303 · ±15s / +30s 两枚跳转砍掉，位置让给顺序·随机·循环单按钮。
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { PlayQueue.previous(); EventLog.ui("PLAY_PREV", "") },
                modifier = Modifier.size(44.dp)) {
                IconSkip(false, palette.primary, Modifier.size(24.dp))
            }
            ModeChip(modeLabel(modeOrd)) {
                PlayQueue.cycleMode()
                EventLog.ui("PLAY_MODE", "mode=$modeOrd")
            }
            IconButton(onClick = { PlayQueue.toggle(); EventLog.ui("PLAY_TOGGLE", "") },
                modifier = Modifier.size(62.dp)
                    .background(palette.primary, RoundedCornerShape(50))) {
                IconPlayPause(st.isPlaying, palette.card, Modifier.size(28.dp))
            }
            IconButton(onClick = { PlayQueue.next(); EventLog.ui("PLAY_NEXT", "") },
                modifier = Modifier.size(44.dp)) {
                IconSkip(true, palette.primary, Modifier.size(24.dp))
            }
        }

        // 功能行：R303 睡眠定时整块砍掉，播放列表从顶栏挪到右下角。
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End) {
            Row(Modifier.height(40.dp)
                .background(palette.card, RoundedCornerShape(50))
                .clickable {
                    showList = !showList
                    EventLog.ui("PLAY_QUEUE_LIST", "open=$showList")
                }
                .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconQueue(palette.primary, Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text("当前播放的音频列表", fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, color = palette.primary)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
