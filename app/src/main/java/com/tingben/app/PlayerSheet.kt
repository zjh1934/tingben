package com.tingben.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
 * R133 · 统一播放器页：点底部常驻条展开，通知栏点进来也展开。
 * 用全屏浮层实现，不新增 Screen 枚举值 —— 它不是「另一页」，是同一份播放状态的展开视图。
 * 全部播放控制共用 PlayQueue.state 这一个真值源，通知栏、常驻条、这里三处永远同步。
 *
 * 两态隔离：制作中的小段 WAV（queueId = "seg" / "trial"）只会在看台格子就地试听，
 * 不会进这个页面；万一被打开也立刻收起。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSheet(
    c: Context, bookId: String?, onClose: () -> Unit, onGoBook: (String) -> Unit
) {
    val st by PlayQueue.state.collectAsState()
    BackHandler(enabled = true) { onClose() }
    LaunchedEffect(st.preview) { if (st.preview) onClose() }
    LaunchedEffect(st.isPlaying) {
        if (!st.isPlaying) return@LaunchedEffect
        while (isActive) {
            PlayQueue.tick()
            delay(300L)
        }
    }
    val list = remember(bookId, st.queueId) {
        bookId?.let { AudioLibrary.playableItems(c, it) } ?: emptyList()
    }
    var showList by remember { mutableStateOf(false) }
    val dur = st.durationMs
    val frac = if (dur > 0) (st.positionMs.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f

    Column(Modifier.fillMaxSize().background(palette.bg)
        .statusBarsPadding().navigationBarsPadding()) {

        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                IconChevronDown(palette.primary, Modifier.size(24.dp))
            }
            Text("正在播放", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                color = palette.text, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                PlayQueue.release()
                onClose()
            }) { IconClose(palette.sub, Modifier.size(20.dp)) }
        }

        if (showList) {
            // 选章从原来的常驻条弹窗搬到这里，全 App 只此一份
            Text("选章播放", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = palette.primary, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
            if (list.isEmpty()) {
                Text("暂无可播章节", fontSize = 13.sp,
                    color = palette.sub, modifier = Modifier.padding(horizontal = 20.dp))
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(list) { (idx, a) ->
                        Row(Modifier.fillMaxWidth().height(48.dp)
                            .clickable {
                                // R182 · bookId 偶尔为空时原来会生成 "chapter:"
                                // 这个废队列 id，断点续听存进去永远读不回来。
                                // 优先沿用当前队列，没队列才按 bookId 拼。
                                val q = if (st.queueId.startsWith("chapter:") &&
                                    st.queueId.length > 8) st.queueId
                                else "chapter:" + bookId.orEmpty()
                                PlayQueue.setTracks(list.map { (j, x) ->
                                    Track(x.file, "${x.title} · 第 ${j + 1} 章",
                                        AudioLibrary.chapterStartMs(c, x, j),
                                        id = "chapter:${x.file.absolutePath}")
                                }, q)
                                PlayQueue.playFrom(list.indexOfFirst { it.first == idx }, id = q)
                                showList = false
                            }.padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("第 ${idx + 1} 章 · ${a.scope}", fontSize = 13.sp,
                                color = palette.text)
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line))
                    }
                }
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(150.dp).background(palette.primarySoft,
                    RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) {
                    IconQueue(palette.primary, Modifier.size(64.dp))
                }
                Spacer(Modifier.height(28.dp))
                Text(st.label.ifBlank { "未在播放" }, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, color = palette.text, maxLines = 2)
                Spacer(Modifier.height(6.dp))
                Text(if (st.isPlaying) "正在播放" else "已暂停",
                    fontSize = 12.sp, color = palette.sub)
                Spacer(Modifier.height(24.dp))
                Slider(
                    value = frac,
                    onValueChange = { if (dur > 0) PlayQueue.seekTo((it * dur).toLong()) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(mmss(st.positionMs), fontSize = 12.sp, color = palette.sub)
                    Spacer(Modifier.weight(1f))
                    Text(mmss(dur), fontSize = 12.sp, color = palette.sub)
                }
                Spacer(Modifier.height(16.dp))
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

        // 底部控制行：全部代码绘制真图标，没有字符
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { PlayQueue.previous(); EventLog.ui("PLAY_PREV", "") },
                modifier = Modifier.size(48.dp)) {
                IconSkip(false, palette.primary, Modifier.size(26.dp))
            }
            IconButton(onClick = { PlayQueue.toggle(); EventLog.ui("PLAY_TOGGLE", "") },
                modifier = Modifier.size(62.dp)
                    .background(palette.primary, RoundedCornerShape(50))) {
                IconPlayPause(st.isPlaying, palette.card, Modifier.size(28.dp))
            }
            IconButton(onClick = { PlayQueue.next(); EventLog.ui("PLAY_NEXT", "") },
                modifier = Modifier.size(48.dp)) {
                IconSkip(true, palette.primary, Modifier.size(26.dp))
            }
            IconButton(onClick = {
                showList = !showList
                EventLog.ui("PLAY_QUEUE_LIST", "open=$showList")
            }, modifier = Modifier.size(48.dp)) {
                IconQueue(if (showList) palette.primary else palette.sub, Modifier.size(24.dp))
            }
        }
        if (bookId != null) {
            TextButton(onClick = { onGoBook(bookId); onClose() },
                modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("去这本书的目录 ›", fontSize = 12.sp, color = palette.primary)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
