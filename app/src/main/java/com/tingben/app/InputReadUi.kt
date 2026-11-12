package com.tingben.app

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tingben.app.core.*
import com.tingben.app.diag.EventLog
import com.tingben.app.engine.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

// @ui-part: InputReadUi
/**
 * R276 · 首页是「贴一段 → 点合成 → 等几分钟 → 产物进下方留白列表」，
 * 不是「粘上就响」，也不建书。片段单独一层目录、走主播放条，
 * 可对它们勾选 / 长按菜单 / 删除 / 导出 / 按当前调音台重做。
 * asHome=false（外部分享进来的输入朗读页）仍可建书，能力不删。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InputReadScreen(c: Context, onBack: () -> Unit, onAdded: (String) -> Unit,
                            asHome: Boolean = false,
                            importingBook: Boolean = false,
                            modelMissing: Int = 0,
                            onModels: () -> Unit = {},
                            // R230 · 调音/任务上移系统顶栏、悬浮开关收回设置页，
                            // 首页内容区不再单独占一排按钮。
                           ) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val inputScope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    // R176 · 建书是异步落盘，连点会重复触发；成功后清空输入框。
    var adding by rememberSaveable { mutableStateOf(false) }
    // R276 · 在跑的合成条数；离开页面时据此取消 native，别让它闷头合成。
    var busy by rememberSaveable { mutableStateOf(0) }
    val listenScope = rememberCoroutineScope()
    val synthMutex = remember { Mutex() }
    // R301 · 连点「合成这段」时浮窗按整批进度走（0/4→1/4→…→4/4），
    // 不再每条各自 0/1→1/1 互相覆盖、也不每条都重新 addView。
    // 用 remember 而非 rememberSaveable：页面重建时队列也没了，归零最稳。
    var clipBatchDone by remember { mutableStateOf(0) }
    var clipBatchTotal by remember { mutableStateOf(0) }
    DisposableEffect(Unit) { onDispose {
        if (busy > 0) { try { NativeEngine.nativeCancel() } catch (_: Throwable) { } }
    } }

    // R276 · 首页片段列表与它的多选态
    var clips by remember { mutableStateOf(emptyList<ClipLibrary.Item>()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { clips = ClipLibrary.list(c) }
    fun refresh() { clips = ClipLibrary.list(c) }

    // 单条另存：用系统保存界面自选位置；批量导出走设置里记住的目录树。
    var pendingSave by remember { mutableStateOf<ClipLibrary.Item?>(null) }
    val saveOne = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val it = pendingSave
        pendingSave = null
        if (uri != null && it != null) {
            listenScope.launch {
                val ok = withContext(Dispatchers.IO) { AudioOps.copyTo(c, it.audio, uri) }
                Toast.makeText(c, if (ok) "已另存" else "另存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 点播：片段队列就是播放器列表；进书听时 ChapterHub 会换成该书的章队列。 */
    fun playClip(item: ClipLibrary.Item) {
        val all = clips.filter { it.isDone }
        val i = all.indexOfFirst { it.id == item.id }
        if (i < 0) return
        PlayQueue.setTracks(all.map {
            Track(it.audio, it.name, id = "clip:${it.id}")
        }, "clip")
        PlayQueue.playFrom(i)
    }

    fun dropClips(ids: Set<String>) {
        ClipLibrary.remove(c, ids)
        if (PlayQueue.state.value.queueId == "clip") PlayQueue.release()
        selected = selected - ids
        menuFor = null
        refresh()
    }

    /**
     * 合成一条：Mutex 串行，不打断正在跑的那段；redo 时先写 .redo.wav，
     * 成功再覆盖正式文件，失败保留旧音频（不把能听的弄丢）。
     */
    fun synth(item: ClipLibrary.Item, snapshot: String, redo: Boolean): Job =
        listenScope.launch {
            busy++
            clipBatchTotal++
            OverlayProgress.update(c, "", "合成中", clipBatchDone, clipBatchTotal,
                -1f, OverlaySource.CLIP)
            synthMutex.withLock {
                val tmp = if (redo) ClipLibrary.redoFile(c, item.id) else item.audio
                val res = withContext(Dispatchers.IO) {
                    runCatching {
                        ClipSynth.synthesize(c, snapshot, "clip_listen.wav", "首页片段",
                            outFile = tmp)
                    }
                }
                withContext(Dispatchers.Main) {
                    val path = res.getOrNull()
                    var ok = path != null
                    if (ok && redo) {
                        ok = try {
                            tmp.copyTo(item.audio, overwrite = true); true
                        } catch (_: Throwable) { false }
                        try { tmp.delete() } catch (_: Throwable) { }
                    }
                    val reason = if (ok) "" else ClipLibrary.reasonOf(res.exceptionOrNull())
                    if (!ok) ClipLibrary.setFailReason(c, item.id, reason)
                    ClipLibrary.finish(c, item.id, ok)
                    clipBatchDone++
                    val last = busy == 1
                    if (ok) {
                        OverlayProgress.update(c, "", if (last) "完成" else "合成中",
                            clipBatchDone, clipBatchTotal, -1f, OverlaySource.CLIP)
                        EventLog.ui("CLIP_LISTEN", "chars=${snapshot.length}")
                        if (last) listenScope.launch {
                            delay(1200)
                            // R301 · 这 1.2 秒里又点了一条就别收：新条自己会排收尾
                            if (busy == 0) {
                                OverlayProgress.hide(c, OverlaySource.CLIP)
                                clipBatchDone = 0
                                clipBatchTotal = 0
                            }
                        }
                    } else {
                        if (last) OverlayProgress.hide(c, OverlaySource.CLIP)
                        else OverlayProgress.update(c, "", "合成中",
                            clipBatchDone, clipBatchTotal, -1f, OverlaySource.CLIP)
                        EventLog.log(EventLog.Kind.ENGINE, "CLIP_LISTEN_FAIL", "id=${item.id.take(6)}")
                        Toast.makeText(c,
                            "没合成出来：${ClipLibrary.reasonText(reason)}，可以长按这条重做",
                            Toast.LENGTH_LONG).show()
                        if (last) { clipBatchDone = 0; clipBatchTotal = 0 }
                    }
                    refresh()
                    busy--
                }
            }
        }

    fun redoOne(item: ClipLibrary.Item) {
        val t = ClipLibrary.text(c, item.id)
        if (t.isBlank()) {
            Toast.makeText(c, "这条的原文不在了，没法重做", Toast.LENGTH_SHORT).show()
            return
        }
        ClipLibrary.markRunning(c, item.id)
        refresh()
        synth(item, t, true)
    }

    // 菜单 / 多选态各自单独消费返回键，菜单优先（R255 互斥口径）
    BackHandler(enabled = menuFor != null) { menuFor = null }
    BackHandler(enabled = selected.isNotEmpty() && menuFor == null) { selected = emptySet() }

    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        if (importingBook) {
            Text("正在后台导入一本书…", fontSize = 11.sp, color = Sub,
                modifier = Modifier.padding(bottom = 6.dp))
        }
        if (modelMissing > 0) {
            // R307 · 裸红字改成一条淡红胶囊，不再横在内容区最上面抢视线。
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Warn.copy(alpha = 0.10f))
                    .padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text("还差 $modelMissing 份模型没导入", fontSize = 12.sp,
                    color = Warn, modifier = Modifier.weight(1f))
                TextButton(onClick = onModels) {
                    Text("去导入 ›", fontSize = 12.sp, color = Primary)
                }
            }
        }
        if (!asHome) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clickable { onBack() },
                    contentAlignment = Alignment.Center) {
                    IconBack(Primary)
                }
                Text("输入朗读", fontSize = 16.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = palette.text)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onBack) { Text("关闭", fontSize = 12.sp, color = Sub) }
            }
            Spacer(Modifier.height(12.dp))
        }
        // R307 · 主页重绘：输入区收成一张卡（标题 + 字数 + 正文 + 两枚胶囊）。
        // 口径不变：贴一段 → 点合成 → 产物进下方留白列表，空槽位不写「还没有片段」。
        if (asHome) {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("念点什么", fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, color = palette.text)
                Spacer(Modifier.weight(1f))
                Text("${text.length} 字", fontSize = 12.sp, color = Sub)
            }
        }
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.26f)
            .clip(RoundedCornerShape(20.dp))
            .background(palette.card)
            .border(1.dp, Line, RoundedCornerShape(20.dp))) {
            BasicTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxSize()
                    .padding(16.dp).padding(bottom = 36.dp),
                textStyle = TextStyle(fontSize = 14.sp, color = palette.text,
                    lineHeight = 22.sp),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize()) {
                        if (text.isEmpty()) Text("粘贴或输入要念的正文",
                            fontSize = 14.sp, color = Sub)
                        inner()
                    }
                }
            )
            Row(Modifier.align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(18.dp))
                    .background(palette.primarySoft)
                    .clickable {
                        val got = clipboard.getText()?.text ?: ""
                        if (got.isNotBlank()) text = got
                        else Toast.makeText(c, "剪贴板里没有文字",
                            Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("粘贴", fontSize = 12.sp, color = Primary)
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.clip(RoundedCornerShape(18.dp))
                    .background(palette.primarySoft)
                    .clickable { text = "" }
                    .padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("清除", fontSize = 12.sp, color = Primary)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (asHome) {
                Button(enabled = text.isNotBlank(), onClick = {
                    if (text.isBlank()) {
                        Toast.makeText(c, "先放点文字进来", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (text.length > ClipSynth.MAX_CHARS) {
                        Toast.makeText(c,
                            "这段 ${text.length} 字太长，最多 ${ClipSynth.MAX_CHARS} 字，先少粘一点",
                            Toast.LENGTH_LONG).show()
                        return@Button
                    }
                    // R301 · 60 秒内同一段文字连点不再生成几条重复片段：
                    // 1.1.32 真机日志 15:40:52~15:40:53 一秒内两条同 hash，
                    // 每条都要等好几分钟。要换音色重做走长按菜单，不靠连点。
                    val recentSame = clips.any { old ->
                        System.currentTimeMillis() - old.createdAt < 60_000L &&
                            old.chars == text.length &&
                            ClipLibrary.text(c, old.id) == text
                    }
                    if (recentSame) {
                        Toast.makeText(c, "这段刚合成过，已在列表里",
                            Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val snapshot = text
                    val item = ClipLibrary.create(c, snapshot)
                    refresh()
                    synth(item, snapshot, false)
                },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary, contentColor = palette.onPrimary,
                        disabledContainerColor = palette.primarySoft,
                        disabledContentColor = Sub)
                ) { Text("▶  " + if (busy > 0) "再合成一段" else "合成这段",
                    fontSize = 15.sp, fontWeight = FontWeight.Bold) }
            } else {
                Button(enabled = !adding, onClick = {
                    if (text.isBlank()) {
                        Toast.makeText(c, "先放点文字进来", Toast.LENGTH_SHORT).show()
                    } else if (!adding) {
                        adding = true
                        val snapshot = text
                        inputScope.launch {
                            val res = withContext(Dispatchers.IO) {
                                runCatching {
                                    BookStore.add(c, autoBookTitle(snapshot, "粘贴的文字"),
                                        snapshot, "input")
                                }
                            }
                            val err = res.exceptionOrNull()
                            if (err != null) {
                                Toast.makeText(c, err.message ?: "书加不进来",
                                    Toast.LENGTH_LONG).show()
                            } else {
                                text = ""   // 失败时正文原样留着，不丢用户输入
                                val added = res.getOrNull()
                                if (added != null) {
                                    onAdded(added.id)
                                    Toast.makeText(c, "书加好了", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(c, "这段已经在书架里了",
                                        Toast.LENGTH_SHORT).show()
                                }
                            }
                            adding = false
                        }
                    }
                },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) { Text(if (adding) "正在加…" else "建书", fontSize = 15.sp,
                    fontWeight = FontWeight.Bold) }
            }
        }

        // R276 · 下方留白 = 片段常驻列表。每条前 10 字名 + 状态，点一下走播放条。
        if (asHome) {
            Spacer(Modifier.height(18.dp))
            Text("合成好的音频", fontSize = 13.sp, color = Sub)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.weight(1f)) {
                if (clips.isEmpty()) {
                    // R307 · 空槽位改成与真实条目同构的骨架：勾选位 / 两行文字 /
                    // 播放位，一眼看得出这里以后放什么；仍不写「还没有片段」。
                    Column(Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(3) {
                            Row(Modifier.fillMaxWidth().height(64.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(palette.card)
                                .border(1.dp, Line, RoundedCornerShape(14.dp))
                                .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(20.dp)
                                    .border(1.5.dp, Line, RoundedCornerShape(10.dp)))
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Box(Modifier.width(160.dp).height(12.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(palette.sub.copy(alpha = 0.16f)))
                                    Spacer(Modifier.height(8.dp))
                                    Box(Modifier.width(96.dp).height(9.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(palette.sub.copy(alpha = 0.10f)))
                                }
                                Box(Modifier.size(28.dp)
                                    .border(1.5.dp, Line, RoundedCornerShape(14.dp)))
                            }
                        }
                    }
                } else {
                    // R285 · 片段多时最后一条要能完整滚出：列表在 weight(1f) 容器里，
                    // 多选条与全局播放条各占一行，留 10dp 尾部余量，末条不贴边。
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 10.dp)
                    ) {
                        items(clips, key = { it.id }) { item ->
                            ClipRow(
                                item = item,
                                checked = item.id in selected,
                                menuExpanded = menuFor == item.id,
                                onToggle = {
                                    selected = if (item.id in selected) selected - item.id
                                               else selected + item.id
                                },
                                onMenu = { menuFor = item.id },
                                onDismissMenu = { menuFor = null },
                                onPlay = { playClip(item) },
                                onExport = {
                                    menuFor = null
                                    if (item.isDone) {
                                        pendingSave = item
                                        saveOne.launch(item.exportName)
                                    } else Toast.makeText(c, "还没合成好", Toast.LENGTH_SHORT).show()
                                },
                                onRedo = { menuFor = null; redoOne(item) },
                                onDelete = { menuFor = null; dropClips(setOf(item.id)) }
                            )
                        }
                    }
                }
            }
            // R255 · 菜单弹着时底部条收起，两者不同时出现、不抢操作
            if (selected.isNotEmpty() && menuFor == null) {
                MultiSelectBar(
                    count = selected.size,
                    onSelectAll = { selected = clips.map { it.id }.toSet() },
                    onCancel = { selected = emptySet() }
                ) {
                    val chosen = clips.filter { it.id in selected }
                    ActionChip("删除", danger = true, icon = UiIcon.DELETE,
                        onClick = { dropClips(selected) })
                    ActionChip("重做", icon = UiIcon.SYNTH, onClick = {
                        chosen.filter { !it.isRunning }.forEach { redoOne(it) }
                        selected = emptySet()
                    })
                    ActionChip("导出", icon = UiIcon.EXPORT, onClick = {
                        val ready = chosen.filter { it.isDone }
                        if (ready.isEmpty()) {
                            Toast.makeText(c, "选中的都还没合成好", Toast.LENGTH_SHORT).show()
                        } else {
                            listenScope.launch {
                                val (ok, skip, why) = withContext(Dispatchers.IO) {
                                    ClipLibrary.exportTo(c, AudioExport.treeUri(c), ready)
                                }
                                Toast.makeText(c,
                                    if (why.isNotBlank()) why
                                    else "已导出 $ok 个，跳过 $skip 个",
                                    Toast.LENGTH_LONG).show()
                            }
                        }
                        selected = emptySet()
                    })
                }
            }
        }
    }
}

/** R276 · 一条首页片段：常驻勾 + 前 10 字名 + 状态 + 就地长按小菜单。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ClipRow(
    item: ClipLibrary.Item,
    checked: Boolean,
    menuExpanded: Boolean,
    onToggle: () -> Unit,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onPlay: () -> Unit,
    onExport: () -> Unit,
    onRedo: () -> Unit,
    onDelete: () -> Unit
) {
    val state = when {
        item.isRunning -> "合成中"
        item.isDone -> "完成"
        item.reason.isNotBlank() -> "失败 · ${ClipLibrary.reasonText(item.reason)} · 可重做"
        else -> "失败 · 可重做"
    }
    val tint = when {
        item.isRunning -> Warn
        item.isDone -> palette.primary
        else -> palette.warn
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .combinedClickable(onClick = {
                if (item.isDone) onPlay()
            }, onLongClick = onMenu)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SelectBox(checked, modifier = Modifier.size(22.dp).clickable { onToggle() })
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, fontSize = 13.sp, color = palette.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state, fontSize = 11.sp, color = tint)
        }
        if (item.isDone) {
            Box(Modifier.size(36.dp).clickable { onPlay() },
                contentAlignment = Alignment.Center) {
                UiIconGlyph(UiIcon.PLAY, palette.primary, glyphSize = 18.dp)
            }
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
            DropdownMenuItem(text = { Text("勾选此条", fontSize = 13.sp) },
                onClick = { onDismissMenu(); onToggle() })
            if (item.isDone) {
                DropdownMenuItem(text = { Text("播放", fontSize = 13.sp) },
                    onClick = { onDismissMenu(); onPlay() })
                DropdownMenuItem(text = { Text("导出", fontSize = 13.sp) },
                    onClick = onExport)
            }
            DropdownMenuItem(text = { Text("重做", fontSize = 13.sp) }, onClick = onRedo)
            DropdownMenuItem(text = { Text("删除", fontSize = 13.sp) }, onClick = onDelete)
        }
    }
}
