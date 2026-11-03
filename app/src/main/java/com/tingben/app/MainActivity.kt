package com.tingben.app

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tingben.app.core.*
import com.tingben.app.diag.EventLog
import com.tingben.app.engine.NativeEngine
import java.io.File

private enum class Screen { LIBRARY, PRODUCE, PROGRESS, AUDIO, SETTINGS, MODELS }

private val Bg = Color(0xFFF7F5FA)
private val Card = Color(0xFFFFFFFF)
private val Primary = Color(0xFF5B4B8A)
private val Sub = Color(0xFF625D6E)
private val Good = Color(0xFF1E6B4A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf(Screen.LIBRARY) }
    var bookId by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }

    val pickBook = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) { }
            val name = queryName(ctx, uri).removeSuffix(".txt").ifBlank { "未命名" }
            val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (text.isNullOrBlank()) Toast.makeText(ctx, "这个文件里没有文字", Toast.LENGTH_SHORT).show()
            else { BookStore.add(ctx, name, text); refresh++ }
        }
    }
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf"
            val stream = ctx.contentResolver.openInputStream(uri)
            if (stream != null) {
                ModelStore.import(ctx, name, stream)
                stream.close()
                Toast.makeText(ctx, "已导入，可以回书架了", Toast.LENGTH_SHORT).show()
                refresh++
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        // 顶栏：只有产品名 + 一个动作
        Row(
            Modifier.fillMaxWidth().background(Card).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("听本", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Primary)
            Spacer(Modifier.weight(1f))
            if (screen == Screen.LIBRARY) {
                TextButton(onClick = { pickBook.launch(arrayOf("text/plain", "*/*")) }) { Text("＋ 导入一本书") }
            }
        }
        Box(Modifier.weight(1f)) {
            when (screen) {
                Screen.LIBRARY -> LibraryScreen(ctx, refresh,
                    onOpen = { bookId = it; screen = Screen.PRODUCE },
                    onModels = { screen = Screen.MODELS })
                Screen.PRODUCE -> ProduceScreen(ctx, bookId, refresh,
                    onBack = { screen = Screen.LIBRARY },
                    onProgress = { screen = Screen.PROGRESS },
                    onDone = { screen = Screen.AUDIO })
                Screen.PROGRESS -> ProgressScreen(onBack = { screen = Screen.PRODUCE })
                Screen.AUDIO -> AudioScreen(ctx, refresh)
                Screen.SETTINGS -> SettingsScreen(ctx, refresh, onModels = { screen = Screen.MODELS })
                Screen.MODELS -> ModelScreen(ctx, refresh,
                    onPick = { pickModel.launch(arrayOf("*/*")) },
                    onBack = { screen = Screen.SETTINGS })
            }
        }
        // 底栏 3 个 Tab：制作/进度挂在书架底下，不另开平铺 Tab
        Row(Modifier.fillMaxWidth().background(Card).padding(vertical = 6.dp)) {
            TabButton("书架", screen == Screen.LIBRARY || screen == Screen.PRODUCE || screen == Screen.PROGRESS,
                Modifier.weight(1f)) { screen = Screen.LIBRARY }
            TabButton("我的音频", screen == Screen.AUDIO, Modifier.weight(1f)) { screen = Screen.AUDIO; refresh++ }
            TabButton("设置", screen == Screen.SETTINGS || screen == Screen.MODELS,
                Modifier.weight(1f)) { screen = Screen.SETTINGS; refresh++ }
        }
    }
}

private fun queryName(c: Context, uri: Uri): String {
    var name = ""
    c.contentResolver.query(uri, null, null, null, null)?.use { cur ->
        val i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && cur.moveToFirst()) name = cur.getString(i)
    }
    return name
}

@Composable
private fun TabButton(label: String, on: Boolean, mod: Modifier, click: () -> Unit) {
    Column(mod.clickable { click() }.padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            color = if (on) Primary else Sub)
        Spacer(Modifier.height(3.dp))
        Box(Modifier.size(if (on) 5.dp else 0.dp).background(Primary, RoundedCornerShape(50)))
    }
}

@Composable
private fun CardBox(mod: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(mod.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Card),
        shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(15.dp), content = content)
    }
}

// ── ① 书架 ──────────────────────────────────────────────────
@Composable
private fun LibraryScreen(c: Context, refresh: Int, onOpen: (String) -> Unit, onModels: () -> Unit) {
    val books = remember(refresh) { BookStore.list(c) }
    val ready = remember(refresh) { ModelStore.ready(c) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("把小说变成能听的音频", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("导入一本书，点一次开始，剩下的交给手机自己跑。", fontSize = 12.5.sp, color = Sub)
        Spacer(Modifier.height(14.dp))
        if (!ready) {
            CardBox(Modifier.padding(bottom = 12.dp)) {
                Text("还差模型文件", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text("第一次用要准备 3 个模型文件（合计约 5GB），下好导入一次，以后每本书都用它们。",
                    fontSize = 12.sp, color = Sub)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onModels, Modifier.fillMaxWidth()) { Text("去准备") }
            }
        }
        if (books.isEmpty()) {
            CardBox {
                Text("书架还是空的", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text("点右上角「＋ 导入一本书」，选一个 txt 文件就行。", fontSize = 12.sp, color = Sub)
            }
        } else {
            books.forEach { b ->
                CardBox(Modifier.padding(bottom = 10.dp)) {
                    Text(b.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(3.dp))
                    Text("${b.chars} 字 · 纯文本", fontSize = 11.5.sp, color = Sub)
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { BookStore.delete(c, b.id) }) { Text("删除", color = Sub) }
                        Button(onClick = { onOpen(b.id) }) { Text("制作有声书") }
                    }
                }
            }
        }
    }
}

// ── ② 制作：一屏一个主按钮 ────────────────────────────────────
@Composable
private fun ProduceScreen(c: Context, id: String?, refresh: Int, onBack: () -> Unit,
                          onProgress: () -> Unit, onDone: () -> Unit) {
    val book = remember(id, refresh) { id?.let { BookStore.list(c).find { b -> b.id == it } } }
    val st by Producer.state.collectAsState()
    val chapters = remember(book) { book?.let { ChapterSplitter.split(BookStore.read(c, it)).first } ?: emptyList() }
    var selected by remember(book) { mutableStateOf<Set<Int>>(emptySet()) }
    if (book == null) {
        Column(Modifier.padding(16.dp)) { Text("这本书不在了"); TextButton(onClick = onBack) { Text("返回书架") } }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← 书架") }
        Text(book.title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("${chapters.size} 章 · ${book.chars} 字", fontSize = 12.5.sp, color = Sub)
        Spacer(Modifier.height(14.dp))

        if (st.output != null) {
            CardBox(Modifier.padding(bottom = 12.dp)) {
                Text("做好了", fontWeight = FontWeight.Bold, color = Good)
                Spacer(Modifier.height(4.dp))
                Text(st.message, fontSize = 12.5.sp, color = Sub)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onDone, Modifier.fillMaxWidth()) { Text("去听") }
            }
        }

        CardBox(Modifier.padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("制作范围", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { selected = if (selected.size == chapters.size) emptySet() else chapters.map { it.index }.toSet() }) {
                    Text(if (selected.size == chapters.size) "清空" else "全选")
                }
            }
            Text(if (selected.isEmpty()) "整本（不勾就是全部）" else "已选 ${selected.size} 章",
                fontSize = 12.sp, color = Sub)
            if (chapters.size in 2..40) {
                Spacer(Modifier.height(8.dp))
                chapters.forEach { ch ->
                    Row(Modifier.fillMaxWidth().clickable {
                        selected = if (ch.index in selected) selected - ch.index else selected + ch.index
                    }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = ch.index in selected, onCheckedChange = {
                            selected = if (it) selected + ch.index else selected - ch.index
                        })
                        Spacer(Modifier.width(6.dp))
                        Text(ch.title.take(28), fontSize = 12.5.sp, maxLines = 1)
                    }
                }
            }
        }

        Button(
            onClick = {
                Producer.start(c, book, if (selected.isEmpty()) null else selected)
                onProgress()
            },
            enabled = !st.running,
            Modifier.fillMaxWidth().height(52.dp)
        ) { Text(if (st.running) "正在制作…" else "开始制作", fontSize = 16.sp) }

        if (st.message.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(st.message, fontSize = 12.5.sp, color = if (st.output != null) Good else Sub)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onProgress) { Text("查看进度") }
    }
}

// ── ③ 进度：只给人话 ─────────────────────────────────────────
@Composable
private fun ProgressScreen(onBack: () -> Unit) {
    val st by Producer.state.collectAsState()
    val pct = if (st.total > 0) st.done.toFloat() / st.total else 0f
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← 返回") }
        Text("制作进度", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        CardBox {
            Text(st.stage, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { pct }, Modifier.fillMaxWidth().height(6.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                if (st.total > 0) "已完成 ${st.done}/${st.total} 块" + if (st.failed > 0) " · ${st.failed} 块改走直读" else ""
                else st.message.ifBlank { "还没开始" },
                fontSize = 12.5.sp, color = Sub)
            Spacer(Modifier.height(6.dp))
            Text(st.message, fontSize = 12.sp, color = Sub)
        }
        Spacer(Modifier.height(14.dp))
        val steps = listOf("拆章", "理解文字", "生成声音", "整理音频", "完成")
        steps.forEachIndexed { i, s ->
            val active = st.stage.contains(s.take(2)) || (st.output != null && s == "完成")
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (active) Primary else Color(0xFFD6D0E2), RoundedCornerShape(50)))
                Spacer(Modifier.width(10.dp))
                Text(s, fontSize = 13.sp, color = if (active) Primary else Sub)
            }
        }
    }
}

// ── ④ 我的音频 ───────────────────────────────────────────────
@Composable
private fun AudioScreen(c: Context, refresh: Int) {
    val dir = File(c.filesDir, "audio")
    val files = remember(refresh) {
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".wav") }?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
    var playing by remember { mutableStateOf<File?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    val clipboard = LocalClipboardManager.current
    DisposableEffect(Unit) { onDispose { player?.release() } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val f = playing
        if (uri != null && f != null) {
            try { c.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().copyTo(o) }
                Toast.makeText(c, "已保存", Toast.LENGTH_SHORT).show() } catch (_: Throwable) { }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("我的音频", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("做好的有声书都在这儿，点开就听。", fontSize = 12.5.sp, color = Sub)
        Spacer(Modifier.height(14.dp))
        if (files.isEmpty()) {
            CardBox {
                Text("还没有音频", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("回书架选一本书，点「制作有声书」。", fontSize = 12.sp, color = Sub)
            }
        } else files.forEach { f ->
            val title = remember(f) {
                val id = f.name.removeSuffix(".wav")
                BookStore.list(c).find { it.id == id }?.title ?: "有声书"
            }
            CardBox(Modifier.padding(bottom = 10.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("%.1f MB".format(f.length() / 1024f / 1024f), fontSize = 11.5.sp, color = Sub)
                Spacer(Modifier.height(10.dp))
                Row {
                    Button(onClick = {
                        if (playing == f) {
                            player?.stop(); player?.release(); player = null; playing = null
                        } else {
                            player?.release()
                            val mp = MediaPlayer()
                            try {
                                mp.setDataSource(f.absolutePath); mp.prepare(); mp.start()
                                player = mp; playing = f
                                mp.setOnCompletionListener { playing = null }
                            } catch (t: Throwable) {
                                Toast.makeText(c, "播放失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Text(if (playing == f) "停止" else "试听") }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { playing = f; export.launch(title + ".wav") }) { Text("保存到手机") }
                }
            }
        }
    }
}

// ── ⑤ 设置 ──────────────────────────────────────────────────
@Composable
private fun SettingsScreen(c: Context, refresh: Int, onModels: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val missing = remember(refresh) { ModelStore.missing(c) }
    var advanced by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("设置", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        CardBox(Modifier.padding(bottom = 11.dp)) {
            Text("模型", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(5.dp))
            Text(
                if (missing.isEmpty()) "都准备好了，可以制作有声书。" else "还差：" + missing.joinToString("、"),
                fontSize = 12.5.sp, color = if (missing.isEmpty()) Good else Sub)
            Spacer(Modifier.height(9.dp))
            FilledTonalButton(onClick = onModels, Modifier.fillMaxWidth()) { Text("导入 / 查看模型") }
        }
        CardBox(Modifier.padding(bottom = 11.dp)) {
            Text("关于", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(5.dp))
            Text("听本 · 本地离线把小说变成能听的音频。模型后导入、不联网；正文一个字不改。",
                fontSize = 12.5.sp, color = Sub)
        }
        CardBox {
            Row(Modifier.clickable { advanced = !advanced }, verticalAlignment = Alignment.CenterVertically) {
                Text("高级（诊断）", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(if (advanced) "收起" else "展开", fontSize = 12.sp, color = Primary)
            }
            if (advanced) {
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = {
                    val info = buildString {
                        append("听本 ").append(com.tingben.app.BuildConfig.VERSION_NAME).append('\n')
                        append("设备 ").append(android.os.Build.MODEL).append(" / Android ")
                            .append(android.os.Build.VERSION.RELEASE).append(" / ")
                            .append(android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "").append('\n')
                        append("引擎 ").append(if (NativeEngine.isAvailable()) NativeEngine.version() else "未装载").append('\n')
                        append("模型 ").append(ModelStore.files(c).joinToString("、") { it.name }).append('\n')
                        append("最近事件（不含正文）：\n")
                        append(EventLog.read().lines().takeLast(30).joinToString("\n"))
                    }
                    clipboard.setText(AnnotatedString(info))
                    Toast.makeText(c, "已复制，直接贴给我即可", Toast.LENGTH_SHORT).show()
                }, Modifier.fillMaxWidth()) { Text("复制诊断信息") }
            }
        }
    }
}

// ── ⑤b 模型页（只有缺文件时才来） ─────────────────────────────
@Composable
private fun ModelScreen(c: Context, refresh: Int, onPick: () -> Unit, onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val files = remember(refresh) { ModelStore.files(c) }
    val missing = remember(refresh) { ModelStore.missing(c) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← 设置") }
        Text("准备模型", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("三个文件下好后在浏览器/文件管理器里选中，回这里导入一次就行。", fontSize = 12.5.sp, color = Sub)
        Spacer(Modifier.height(14.dp))
        CardBox(Modifier.padding(bottom = 11.dp)) {
            Text(if (missing.isEmpty()) "都齐了，可以回书架制作" else "还差 ${missing.size} 个文件",
                fontWeight = FontWeight.Bold, color = if (missing.isEmpty()) Good else Primary)
        }
        val slots = listOf(
            "语言模型" to ModelStore.LLM,
            "声音主文件" to ModelStore.TALKER,
            "声音配套文件" to ModelStore.TOKENIZER
        )
        slots.forEach { (label, name) ->
            val f = files.find { it.name.equals(name, true) }
            CardBox(Modifier.padding(bottom = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                    Text(if (f != null) "已导入 ✓" else "还缺", fontSize = 12.sp,
                        color = if (f != null) Good else Sub)
                }
                if (f != null) {
                    Spacer(Modifier.height(4.dp))
                    Text("%.2f GB".format(f.length() / 1024f / 1024f / 1024f), fontSize = 11.sp, color = Sub)
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { ModelStore.delete(c, f) }) { Text("删除重试", color = Sub) }
                } else {
                    Spacer(Modifier.height(5.dp))
                    TextButton(onClick = {
                        val url = when (label) {
                            "语言模型" -> "https://hf-mirror.com/bartowski/mlabonne_Qwen3-4B-abliterated-GGUF/resolve/main/mlabonne_Qwen3-4B-abliterated-Q4_K_M.gguf"
                            "声音主文件" -> "https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/main/qwen-talker-1.7b-voicedesign-Q8_0.gguf"
                            else -> "https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/main/qwen-tokenizer-12hz-Q8_0.gguf"
                        }
                        clipboard.setText(AnnotatedString(url))
                        Toast.makeText(c, "地址已复制，用浏览器下载", Toast.LENGTH_SHORT).show()
                    }) { Text("复制下载地址") }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Button(onClick = onPick, Modifier.fillMaxWidth().height(50.dp)) { Text("导入模型文件") }
        Spacer(Modifier.height(8.dp))
        Text("App 自己不联网下载。复制地址到浏览器或用电脑下好，再点上面导入。", fontSize = 11.5.sp, color = Sub)
        if (files.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("已导入的文件（给开发排查用）", fontSize = 11.sp, color = Sub)
            files.forEach { Text("· " + it.name, fontSize = 10.5.sp, color = Sub) }
        }
    }
}
