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
import com.tingben.app.core.AudioLibrary
import com.tingben.app.core.PlayQueue
import com.tingben.app.core.Track
import com.tingben.app.diag.EventLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private fun mmss(ms: Long): String {
    val s = (ms / 1000L).coerceAtLeast(0L)
    return "%02d:%02d".format(s / 60, s % 60)
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

@Composable
private fun SleepGlyph(tint: Color, glyphSize: Dp = 16.dp) {
    Canvas(Modifier.size(glyphSize)) {
        val w = this.size.width
        val sw = (w * 0.09f).coerceAtLeast(1.2f)
        drawCircle(tint, radius = w * 0.38f, center = Offset(w / 2f, w / 2f),
            style = Stroke(width = sw))
        drawLine(tint, Offset(w / 2f, w / 2f), Offset(w / 2f, w * 0.30f),
            strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(w / 2f, w / 2f), Offset(w * 0.70f, w / 2f),
            strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
private fun JumpChip(label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp)
        .background(palette.card, RoundedCornerShape(50))
        .clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = palette.primary)
    }
}

/**
 * R133 · 统一播放器页：点底部常驻条展开，通知栏点进来也展开。
 * 用全屏浮层实现，不新增 Screen 枚举值 —— 它不是「另一页」，是同一份播放状态的展开视图。
 * 全部播放控制共用 PlayQueue.state 这一个真值源，通知栏、常驻条、这里三处永远同步。
 *
 * 两态隔离：制作中的小段 WAV（queueId = "seg" / "trial"）只会在看台格子就地试听，
 * 不会进这个页面；万一被打开也立刻收起。
 *
 * R262 · 重设计：封面代码自绘、状态废话换成时间/剩余、±跳转与睡眠定时
 * 是听长书真正需要的功能；倍速档位与选章仍沿用 PlayQueue 单一真值源。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSheet(
    c: Context, bookId: String?, onClose: () -> Unit, onGoBook: (String) -> Unit
) {
    val st by PlayQueue.state.collectAsState()
    BackHandler(enabled = true) { onClose() }
    LaunchedEffect(st.preview) { if (st.preview) onClose() }

    // R262 · 睡眠定时：0=关 / 15、30、60 分钟 / -1=本章结束。
    // 到点只暂停，不释放队列、不丢断点；本章结束靠 trackId 换轨判定。
    var sleepMinutes by remember { mutableStateOf(0) }
    var sleepEndAt by remember { mutableStateOf(0L) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var chapterArmed by remember { mutableStateOf(false) }
    LaunchedEffect(st.isPlaying) {
        if (!st.isPlaying) return@LaunchedEffect
        while (isActive) {
            PlayQueue.tick()
            nowMs = System.currentTimeMillis()
            delay(300L)
        }
    }
    LaunchedEffect(sleepEndAt, st.isPlaying) {
        if (sleepEndAt <= 0L || !st.isPlaying) return@LaunchedEffect
        while (isActive) {
            delay(1000L)
            if (System.currentTimeMillis() >= sleepEndAt) {
                PlayQueue.pause()
                sleepMinutes = 0
                sleepEndAt = 0L
                EventLog.ui("PLAY_SLEEP_END", "at=timer")
                break
            }
        }
    }
    LaunchedEffect(sleepMinutes, st.trackId) {
        if (sleepMinutes != -1) {
            chapterArmed = false
            return@LaunchedEffect
        }
        if (st.trackId.isEmpty()) return@LaunchedEffect
        if (!chapterArmed) {
            chapterArmed = true
            return@LaunchedEffect
        }
        PlayQueue.pause()
        sleepMinutes = 0
        sleepEndAt = 0L
        EventLog.ui("PLAY_SLEEP_END", "at=chapter")
    }
    fun cycleSleep() {
        val next = when (sleepMinutes) {
            0 -> 15
            15 -> 30
            30 -> 60
            60 -> -1
            else -> 0
        }
        sleepMinutes = next
        sleepEndAt = if (next > 0) System.currentTimeMillis() + next * 60_000L else 0L
        chapterArmed = false
        EventLog.ui("PLAY_SLEEP", "min=$next")
    }

    val list = remember(bookId, st.queueId) {
        bookId?.let { AudioLibrary.playableItems(c, it) } ?: emptyList()
    }
    var showList by remember { mutableStateOf(false) }
    val dur = st.durationMs
    val frac = if (dur > 0) (st.positionMs.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f
    val left = (dur - st.positionMs).coerceAtLeast(0L)
    val sleepLeft = if (sleepEndAt > 0L)
        ((sleepEndAt - nowMs) / 60_000L + 1).coerceAtLeast(0L) else 0L
    val sleepLabel = when {
        sleepMinutes == -1 -> "本章完"
        sleepMinutes > 0 -> "$sleepLeft 分"
        else -> "睡眠定时"
    }

    Column(Modifier.fillMaxSize().background(palette.bg)
        .statusBarsPadding().navigationBarsPadding()) {

        // R272 · 音乐播放器式：拖顶部这一行下滑收起。手势只挂在把手行，
        // 不铺满全屏，避免与选章列表滚动 / 进度条拖拽抢事件；点 ‹ 关闭照旧。
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
            .pointerInput(Unit) {
                var acc = 0f
                detectVerticalDragGestures(
                    onDragStart = { acc = 0f },
                    onDragEnd = { if (acc > 55f) onClose(); acc = 0f },
                    onVerticalDrag = { _, dy -> if (dy > 0) acc += dy }
                )
            },
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                IconChevronDown(palette.primary, Modifier.size(24.dp))
            }
            Text("正在听", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = palette.text, modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton(onClick = {
                showList = !showList
                EventLog.ui("PLAY_QUEUE_LIST", "open=$showList")
            }) { IconQueue(palette.primary, Modifier.size(22.dp)) }
            IconButton(onClick = {
                PlayQueue.release()
                onClose()
            }) { IconClose(palette.sub, Modifier.size(20.dp)) }
        }

        if (showList) {
            Text("选章播放", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = palette.primary, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
            if (list.isEmpty()) {
                Text("暂无可播章节", fontSize = 13.sp,
                    color = palette.sub, modifier = Modifier.padding(horizontal = 20.dp))
            } else {
                // R285 · 选章列表与下方主控制行 / 功能行同在一个 Column：
                // weight(1f) 已保证不被挤掉，再留 8dp 尾部余量，最后一章不贴控制行。
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(list) { (idx, a) ->
                        Row(Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 3.dp)
                            .background(palette.card, RoundedCornerShape(12.dp))
                            .clickable {
                                // R182 · bookId 偶尔为空时原来会生成 "chapter:"
                                // 这个废队列 id，断点续听存进去永远读不回来。
                                val q = if (st.queueId.startsWith("chapter:") &&
                                    st.queueId.length > 8) st.queueId
                                else "chapter:" + bookId.orEmpty()
                                PlayQueue.setTracks(list.map { (j, x) ->
                                    Track(x.file, "${x.title} · 第 ${j + 1} 章" +
                                        (if (AudioLibrary.isWholeBook(x) && x.chapters.size > 1) "（从整本开头播）" else ""),
                                        AudioLibrary.chapterStartMs(c, x, j),
                                        id = "chapter:${x.file.absolutePath}")
                                }, q)
                                PlayQueue.playFrom(list.indexOfFirst { it.first == idx }, id = q)
                                showList = false
                            }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("第 ${idx + 1} 章", fontSize = 13.sp,
                                fontWeight = FontWeight.Bold, color = palette.text)
                            Spacer(Modifier.weight(1f))
                            Text(a.scope, fontSize = 11.sp, color = palette.sub)
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

        // 主控制行：上一章 / 回退 15 秒 / 播放 / 快进 30 秒 / 下一章
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { PlayQueue.previous(); EventLog.ui("PLAY_PREV", "") },
                modifier = Modifier.size(44.dp)) {
                IconSkip(false, palette.primary, Modifier.size(24.dp))
            }
            JumpChip("-15s") {
                PlayQueue.seekTo((st.positionMs - 15_000L).coerceAtLeast(0L))
                EventLog.ui("PLAY_JUMP", "ms=-15000")
            }
            IconButton(onClick = { PlayQueue.toggle(); EventLog.ui("PLAY_TOGGLE", "") },
                modifier = Modifier.size(62.dp)
                    .background(palette.primary, RoundedCornerShape(50))) {
                IconPlayPause(st.isPlaying, palette.card, Modifier.size(28.dp))
            }
            JumpChip("+30s") {
                PlayQueue.seekTo((st.positionMs + 30_000L).coerceAtMost(dur))
                EventLog.ui("PLAY_JUMP", "ms=30000")
            }
            IconButton(onClick = { PlayQueue.next(); EventLog.ui("PLAY_NEXT", "") },
                modifier = Modifier.size(44.dp)) {
                IconSkip(true, palette.primary, Modifier.size(24.dp))
            }
        }

        // 功能行：睡眠定时（新）与去目录，各占一头，不再堆字
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier
                .background(if (sleepMinutes == 0) palette.card else palette.primarySoft,
                    RoundedCornerShape(50))
                .clickable { cycleSleep() }
                .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically) {
                SleepGlyph(palette.primary)
                Spacer(Modifier.width(6.dp))
                Text(sleepLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = palette.primary)
            }
            if (bookId != null) {
                TextButton(onClick = { onGoBook(bookId); onClose() }) {
                    Text("去这本书的目录 ›", fontSize = 12.sp, color = palette.primary)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
