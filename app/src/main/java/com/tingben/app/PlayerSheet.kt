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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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

/** R319 · 队列里一条就是「一份音频」：章音频，或首页粘贴合成的一段。
 *  不再叫「第 N 段」——段是内部小块，不该露给用户。 */
private fun trackKind(label: String): String =
    if (label.contains("片段") || label.contains("粘贴")) "粘贴片段" else "章音频"

/** R319 · 下滑跟手：0 = 展开到位，1 = 完全滑出。控件先淡出，只留封面与标题，
 *  半收起时倍速 / 模式 / 列表胶囊不再叠成一团。 */
private fun chromeAlpha(pull: Float): Float = (1f - pull * 1.6f).coerceIn(0f, 1f)

/**
 * R359 · 播放器封面换成经典双八分音符（♫），撤掉 R262 那套自绘书页图形与声波竖条。
 * 柔底圆仍随播放态深浅变化；图标由 PlayIcons.IconMusicNote 一处定义、两处复用
 * （展开页 88.dp / 底部收起条 44.dp），主题取色不变。
 */
@Composable
internal fun PlayerCover(tint: Color, coverSize: Dp, playing: Boolean = false) {
    Box(Modifier.size(coverSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val sw = (w * 0.012f).coerceAtLeast(1.5f)
            drawCircle(tint.copy(alpha = if (playing) 0.14f else 0.10f),
                radius = w * 0.44f, center = Offset(cx, cy))
            drawCircle(tint.copy(alpha = 0.18f), radius = w * 0.34f,
                center = Offset(cx, cy), style = Stroke(width = sw))
        }
        IconMusicNote(tint, Modifier.size(coverSize * 0.54f))
    }
}

/** R303 · 播放模式单按钮：顺序 / 随机 / 循环三态，点一下切一态。
 *  R319 · 补一枚状态图标，不再像「展开 ›」那样让人以为是菜单。 */
@Composable
private fun ModeChip(ord: Int, label: String, onClick: () -> Unit) {
    Box(Modifier.height(44.dp)
        .background(palette.primarySoft, RoundedCornerShape(50))
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconMode(ord, palette.primary, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = palette.primary)
        }
    }
}

/**
 * R133 · 统一播放器页：点底部常驻条展开，通知栏点进来也展开。
 * 用全屏浮层实现，不新增 Screen 枚举值 —— 它不是「另一页」，是同一份播放状态的展开视图。
 * 全部播放控制共用 PlayQueue.state 这一个真值源，通知栏、常驻条、这里三处永远同步。
 *
 * R303 · 跟手：整页位移由外层按拖拽量实时给，拖动中每帧跟手，松手才补回弹/滑出动画。
 * 下滑手势挂在整页 Column 上，子控件（列表滚动 / 进度条）先消费事件，不抢它们的手势。
 * 砍掉睡眠定时与 ±15/30s 跳转；快进快退的位置换成播放模式三态按钮。
 * 列表曾从顶栏挪到右下角；R357 已归位顶栏、带真实条数，浮胶囊撤掉。
 *
 * R319 · 下滑收起时进度/倍速/控制/列表胶囊先淡出，半收起态只剩封面与标题，
 * 不再整层平移叠成一团；列表展开时控制行压成细条。
 *
 * R332 · pullFraction 只认「从展开态往下拉收起」这一路，点开 / 上扒进入时恒 0：
 * 展开过程控件全程可见，不再先漏一片透明再突然全亮。顶部补一条收起把手，
 * 与常驻条顶部那条上扒把手同款、方向各异，两态不再抢同一块位置。
 *
 * R357 · 按真机截图重设计：大封面缩成 88dp 并和标题 / 状态 / 来源收进一张卡，
 * 撤掉顶栏「正在听」废话与右下角浮胶囊（队列入口归位顶栏、带真实条数），
 * 进度条换细轨实心 thumb 且右侧写总时长，五个大圆倍速换成等宽小 chip，
 * 播放模式与倍速并成一条工具条，主控制行只留上一首 / 播放 / 下一首。
 * 全部播放能力、上扒跟手、下滑淡出、列表展开压细条、通知回跳一条没删。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSheet(
    c: Context, bookId: String?, onClose: () -> Unit, onGoBook: (String) -> Unit,
    onPullDown: (Float) -> Unit = {},
    onPullSettle: () -> Unit = {},
    pullFraction: Float = 0f
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
    val chrome = chromeAlpha(pullFraction)
    // R332 · 下拉收起时把手一直可见，拖起来变色变长；这层不跟着 chrome 淡出。
    var dragging by remember { mutableStateOf(false) }
    // R319 · 列表展开时主控制行让位，不再占掉半屏。
    val barPad = if (showList) 2.dp else 10.dp
    val playSize = if (showList) 44.dp else 62.dp

    Column(Modifier.fillMaxSize().background(palette.bg)
        .statusBarsPadding().navigationBarsPadding()
        .pointerInput(Unit) {
            var acc = 0f
            detectVerticalDragGestures(
                onDragStart = { acc = 0f; dragging = true },
                onDragEnd = { dragging = false; onPullSettle() },
                onDragCancel = { dragging = false; onPullSettle() },
                // 正负都传，手指往回带时页面也跟着回，位移由外层夹在 [0, 屏高]
                onVerticalDrag = { _, dy ->
                    acc = (acc + dy).coerceAtLeast(0f)
                    onPullDown(acc)
                }
            )
        }) {
        // R332 · 收起把手：和常驻条顶部那条上扒把手同款，展开态从这一带往下拉就收起；
        // 上下两态各有一条、方向各异，不再让「小横条」和上扒位置互相打架。
        Box(Modifier.fillMaxWidth().height(20.dp),
            contentAlignment = Alignment.Center) {
            Box(Modifier.width(if (dragging) 48.dp else 36.dp).height(4.dp)
                .background(if (dragging) palette.primary else palette.primary.copy(alpha = 0.35f),
                    RoundedCornerShape(50)))
        }

        // R357 · 顶栏三件事都有用：收起 / 队列（条数 + 一键展开或收起）/ 停止。
        // 「正在听」是顶栏废话，撤掉；右下角那枚浮胶囊整段撤掉，队列入口归位到顶栏。
        Row(Modifier.fillMaxWidth().alpha(chrome)
            .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                IconChevronDown(palette.primary, Modifier.size(24.dp))
            }
            Row(Modifier.height(40.dp).weight(1f)
                .background(palette.primarySoft, RoundedCornerShape(50))
                .clickable {
                    showList = !showList
                    EventLog.ui("PLAY_QUEUE_LIST", "open=$showList")
                }
                .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center) {
                IconQueue(palette.primary, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (showList) "收起列表" else "播放队列 · ${list.size} 条",
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.primary)
            }
            // R319 · 右上写明是停止（会清队列），左上下拉才是收起，两件事不混。
            Row(Modifier.height(40.dp)
                .background(palette.card, RoundedCornerShape(50))
                .clickable {
                    PlayQueue.release()
                    onClose()
                }
                .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("停止", fontSize = 11.sp, color = palette.sub)
                Spacer(Modifier.width(4.dp))
                IconClose(palette.sub, Modifier.size(16.dp))
            }
        }

        if (showList) {
            Text("播放队列 · ${list.size} 条", fontSize = 13.sp,
                fontWeight = FontWeight.Bold, color = palette.primary,
                modifier = Modifier.padding(horizontal = 20.dp).alpha(chrome))
            Spacer(Modifier.height(8.dp))
            if (list.isEmpty()) {
                Text("现在没有在播的音频", fontSize = 13.sp,
                    color = palette.sub, modifier = Modifier.padding(horizontal = 20.dp))
                if (!bookId.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { onClose(); onGoBook(bookId) }) {
                        Text("去挑一本", fontSize = 12.sp, color = palette.primary)
                    }
                }
            } else {
                // R285 · 选章列表与下方主控制行 / 功能行同在一个 Column：
                // weight(1f) 已保证不被挤掉，再留 8dp 尾部余量，最后一条不贴控制行。
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().alpha(chrome),
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
                            // R319 · 一条 = 一份音频：序号 + 真实条目名 + 来源，
                            // 正在播的那条给主色竖条。不再写「第 N 段」。
                            if (idx == st.index) {
                                Box(Modifier.width(3.dp).height(36.dp)
                                    .background(palette.primary, RoundedCornerShape(50)))
                                Spacer(Modifier.width(10.dp))
                            } else {
                                Text("%02d".format(idx + 1), fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold, color = palette.sub,
                                    modifier = Modifier.width(26.dp))
                                Spacer(Modifier.width(10.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(t.label, fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold, color = palette.text,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(trackKind(t.label), fontSize = 11.sp, color = palette.sub)
                            }
                            if (idx == st.index) {
                                Spacer(Modifier.width(6.dp))
                                Text("正在播", fontSize = 11.sp, color = palette.primary)
                            }
                        }
                    }
                }
            }
        } else {
            // R357 · 一张「当前音频卡」把封面 / 标题 / 状态 / 来源收在一起，
            // 不再让大封面独占半屏、下面再散一堆控件。下滑跟手时这张卡不淡出，
            // 仍守住 R319「只留封面与标题」的口径。
            Column(Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .background(palette.card, RoundedCornerShape(18.dp))
                    .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    PlayerCover(palette.primary, 88.dp, st.isPlaying)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(st.label.ifBlank { "未在播放" }, fontSize = 17.sp,
                            fontWeight = FontWeight.Bold, color = palette.text,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        Text(if (st.index >= 0)
                                (if (st.isPlaying) "正在播放" else "已暂停") +
                                    " · 第 ${st.index + 1}/${list.size} 条 · ${mmss(dur)}"
                             else "准备好就可以听了",
                            fontSize = 12.sp, color = palette.sub)
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.background(palette.primarySoft, RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 4.dp)) {
                            Text(trackKind(st.label), fontSize = 11.sp,
                                fontWeight = FontWeight.Bold, color = palette.primary)
                        }
                    }
                }
                // R319 / R332 · 控制层在下滑收起时先淡出，只留上面这张卡。
                Column(Modifier.alpha(chrome).padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(22.dp))
                    // R357 · 细轨 + 实心圆 thumb，撤掉旧图里那根突兀的竖线与端点小点；
                    // 右侧写总时长，不再让人自己拿负号去减。
                    Slider(
                        value = frac,
                        onValueChange = { if (dur > 0) PlayQueue.seekTo((it * dur).toLong()) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = palette.primary,
                            activeTrackColor = palette.primary,
                            // R357 · 未播放段用稍深的灰轨：primarySoft 在浅底上几乎看不见，
                            // 进度到哪儿一眼看得出；深色主题下 grey 同样可见。
                            inactiveTrackColor = palette.grey.copy(alpha = 0.55f))
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(mmss(st.positionMs), fontSize = 12.sp, color = palette.sub)
                        Spacer(Modifier.weight(1f))
                        Text(mmss(dur), fontSize = 12.sp, color = palette.sub)
                    }
                    Spacer(Modifier.height(18.dp))
                    Text("倍速", fontSize = 12.sp, color = palette.sub)
                    // K1 · 合成侧只有「慢/中/快」倾向，播放倍速才是可靠控制点。
                    // R158b · 这层关系不进 UI（教程），入口够得着就行。
                    Spacer(Modifier.height(8.dp))
                    // R357 · 一个工具条里把播放模式与五档倍速并齐：小 chip 等宽，
                    // 撤掉五个大圆，也不再让「顺序」胶囊挤在大播放键左边。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ModeChip(modeOrd, modeLabel(modeOrd)) {
                            PlayQueue.cycleMode()
                            EventLog.ui("PLAY_MODE", "mode=$modeOrd")
                        }
                        Spacer(Modifier.width(8.dp))
                        Row(Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PlayQueue.SPEED_STEPS.forEach { v ->
                                val label = PlayQueue.speedLabel(v)
                                val on = kotlin.math.abs(st.speed - v) < 0.01f
                                Box(Modifier.weight(1f).height(44.dp)
                                    .background(if (on) palette.primary else palette.card,
                                        RoundedCornerShape(10.dp))
                                    .clickable {
                                        PlayQueue.setSpeed(v)
                                        EventLog.ui("PLAY_SPEED", "x=$v")
                                    },
                                    contentAlignment = Alignment.Center) {
                                    Text(label, fontSize = 11.sp,
                                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                        color = if (on) palette.card else palette.sub)
                                }
                            }
                        }
                    }
                }
            }
        }

        // R357 · 主控制行只留三件事：上一首 / 播放 / 下一首，等距对称。
        // 播放模式已挪进上面的工具条，不再挤在大播放键左边；列表入口归位到顶栏，
        // 右下角那枚浮胶囊整段撤掉。R319 的列表展开压细条仍在。
        // R303 · ±15s / +30s 两枚跳转不回来。
        Row(Modifier.fillMaxWidth().alpha(chrome)
            .padding(horizontal = 24.dp, vertical = barPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { PlayQueue.previous(); EventLog.ui("PLAY_PREV", "") },
                modifier = Modifier.size(44.dp)) {
                IconSkip(false, palette.primary, Modifier.size(24.dp))
            }
            IconButton(onClick = { PlayQueue.toggle(); EventLog.ui("PLAY_TOGGLE", "") },
                modifier = Modifier.size(playSize)
                    .background(palette.primary, RoundedCornerShape(50))) {
                IconPlayPause(st.isPlaying, palette.card, Modifier.size(28.dp))
            }
            IconButton(onClick = { PlayQueue.next(); EventLog.ui("PLAY_NEXT", "") },
                modifier = Modifier.size(44.dp)) {
                IconSkip(true, palette.primary, Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
