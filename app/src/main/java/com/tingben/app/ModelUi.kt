package com.tingben.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tingben.app.core.*
import com.tingben.app.diag.EventLog
import com.tingben.app.engine.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import java.io.File

// @ui-part: ModelUi
@Composable
internal fun ClearSegmentsRow(c: Context) {
    var confirmClear by remember { mutableStateOf(false) }
    val clearScope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("清理分段音频", fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text("只删分段 WAV", fontSize = 11.sp, color = Sub)
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = { confirmClear = true },
            modifier = Modifier.height(34.dp),
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(horizontal = 14.dp)) {
            Text("清理", fontSize = 12.sp)
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清理分段音频？", fontSize = 14.sp) },
            text = { Text("只删制作过程里生成的分段 WAV（会连留着的其他音色缓存一起清掉），" +
                    "书架原书、成品和模型文件都不动。", fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    // R139 · 遍历整个 work/ 删文件原来在按钮回调（主线程）同步跑，
                    // 分段多的时候会卡住界面。改 IO 线程，回来再弹 Toast。
                    clearScope.launch {
                        val n = withContext(Dispatchers.IO) {
                            var k = 0
                            try {
                                val work = File(c.filesDir, "work")
                                work.walkTopDown()
                                    .filter { it.isFile && it.name.endsWith(".wav") }
                                    .forEach { if (it.delete()) k++ }
                            } catch (_: Throwable) { }
                            k
                        }
                        Toast.makeText(c, "已清理 $n 个分段音频", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("确认清理") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
        )
    }
}

@Composable
internal fun ModelScreen(c: Context, refresh: Int,
                         importing: String?, importBytes: Long, importError: String?,
                         onPick: () -> Unit, onBack: () -> Unit, onLibrary: () -> Unit,
                         onChanged: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val st by Producer.state.collectAsState()
    val files = remember(refresh) { ModelStore.files(c) }
    val missing = remember(refresh) { ModelStore.missing(c) }
    val installed = remember(refresh) { ModelStore.installedModes(c) }
    val current = remember(refresh) { ModelStore.currentMode(c) }
    val modeFiles = remember(refresh) { ModelStore.modeFiles(c) }
    val baseFile = remember(refresh) { ModelStore.tokenizer(c) }
    val allReady = missing.isEmpty()

    fun copyUrl(url: String) {
        clipboard.setText(AnnotatedString(url))
        Toast.makeText(c, "地址已复制，用浏览器下载", Toast.LENGTH_SHORT).show()
    }
    fun cnUrl(name: String) =
        "https://hf-mirror.com/Serveurperso/Qwen3-TTS-GGUF/resolve/main/$name"
    fun hfUrl(name: String) =
        "https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/main/$name"

    // R136 · 一屏装得下：去掉整页滚动，默认只显示「缺什么 / 装了什么 / 点哪导入」。
    // 文件名、下载地址、删除重试、启用模式这些低频细节收进可展开区，
    // 一次只展开一项 —— 功能一个没删，但常规手机不用往下拉。
    // 展开态是用户主动操作，不算默认态要滚动。
    var expanded by remember { mutableStateOf<String?>(null) }
    fun toggle(key: String) { expanded = if (expanded == key) null else key }
    // H2 · 用户实证：「导入模型的按钮变成了回书架导入一本书」——
    // 那个按钮会随状态变形，本身就是错的设计。现在主按钮永远叫「导入模型文件」，
    // 就绪后另外给一个明确的红色「卸载模型」危险操作，走二次确认。
    var confirmUninstall by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("模型", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            Text(if (allReady) "已就绪 · ${installed.size} 个模式"
                 else if (baseFile == null) "还缺共用底座" else "还缺模式主文件",
                fontSize = 12.sp, color = if (allReady) Good else Warn)
        }

        if (importing != null) {
            CardBox(Modifier.padding(vertical = 4.dp)) {
                Text("正在复制，别关掉 App · 已复制 %.2f GB".format(
                    importBytes / 1024f / 1024f / 1024f),
                    fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Primary)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(5.dp))
            }
        }
        if (importError != null) {
            CardBox(Modifier.padding(vertical = 4.dp)) {
                Text("上一个文件没导入成功 · $importError", fontSize = 12.sp, color = Warn)
                Text("重新下载再导入一次，或先重启 App。", fontSize = 11.sp, color = Sub)
            }
        }

        Spacer(Modifier.height(6.dp))

        // 共用底座：一行摘要 + 点「详情」打开统一详情弹层
        CardBox(Modifier.padding(bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("共用底座", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(if (baseFile != null)
                            "已导入 ✓ · %.2f GB".format(baseFile.length() / 1024f / 1024f / 1024f)
                        else "还缺这一份 · 三个模式都要用",
                        fontSize = 11.sp, color = if (baseFile != null) Good else Warn)
                }
                TextButton(onClick = { toggle("base") }) {
                    Text(if (expanded == "base") "收起" else "详情",
                        fontSize = 12.sp, color = Primary)
                }
            }
        }

        Text("模式主文件 · 装一份就能用", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))

        // R170 · 真机截图 26：展开一张卡，详情全塞在卡里，那张卡被撑到两倍高，
        // 同一行另一列空出一大块，第三张还被挤到下一行 —— 看着像没排完。
        // 现在三卡固定等高，详情一律移到下方统一弹层，一次只开一项；
        // 页面本身永远一屏（R136），弹层由系统处理返回键与点空白关闭。
        val modes = ModelStore.Mode.values()
        modes.toList().chunked(2).forEach { rowModes ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowModes.forEach { mode ->
                    val f = modeFiles[mode]
                    val isCurrent = mode == current
                    val open = expanded == mode.id
                    CardBox(Modifier.weight(1f).height(96.dp).padding(bottom = 6.dp)
                        .clickable { toggle(mode.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(mode.label, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                                color = if (isCurrent || open) Primary else Color.Unspecified)
                            Text(if (open) "收起" else "详情 ›",
                                fontSize = 10.sp, color = Primary)
                            Spacer(Modifier.width(4.dp))
                            Text(if (f != null) "✓" else "＋", fontSize = 12.sp,
                                color = if (f != null) Good else Warn)
                        }
                        Text(if (f != null)
                                "%.2f GB%s".format(f.length() / 1024f / 1024f / 1024f,
                                    if (isCurrent) " · 当前" else "")
                            else "待导入", fontSize = 11.sp,
                            color = if (f != null) Good else Warn)
                        Text(mode.hint, fontSize = 10.sp, color = Sub, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
                if (rowModes.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        // R170 · 统一详情弹层：完整文件名（可点复制）、两个下载地址、
        // 启用 / 删除重试 / 导入这份，全都在这里。卡里只留摘要，所以永远等高。
        expanded?.let { key ->
            val isBase = key == "base"
            val mode = ModelStore.Mode.values().find { it.id == key }
            val f = mode?.let { modeFiles[it] }
            val isCurrent = mode == current
            AlertDialog(
                onDismissRequest = { toggle(key) },
                title = {
                    Text(if (isBase) "共用底座" else (mode?.label ?: "模型详情"),
                        fontSize = 15.sp, fontWeight = FontWeight.Bold)
                },
                text = {
                    Column {
                        if (expanded == "base") {
                            Text(ModelStore.TOKENIZER, fontSize = 11.sp, color = Sub,
                                fontFamily = FontFamily.Monospace)
                            Text("12Hz 码 ↔ 24kHz 波形，导入一次就够。",
                                fontSize = 11.sp, color = Sub)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    copyUrl(cnUrl(ModelStore.TOKENIZER))
                                }) { Text("复制国内地址 ›", fontSize = 12.sp, color = Primary) }
                                TextButton(onClick = {
                                    copyUrl(hfUrl(ModelStore.TOKENIZER))
                                }) { Text("备用 ›", fontSize = 12.sp, color = Sub) }
                            }
                            if (baseFile != null) {
                                TextButton(onClick = {
                                    ModelStore.delete(c, baseFile); onChanged(); toggle(key)
                                }) { Text("删除重试", fontSize = 12.sp, color = Sub) }
                            }
                        } else if (mode != null) {
                            Text(mode.id, fontSize = 11.sp, color = Sub)
                            // R169 · 两列卡太窄会把 40 字符文件名竖着拆成 5 行；
                            // R170 移到全宽弹层，单行省略 + 点一下复制完整名。
                            Text(mode.file, fontSize = 11.sp, color = Sub, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.clickable {
                                    clipboard.setText(AnnotatedString(mode.file))
                                    Toast.makeText(c, "文件名已复制",
                                        Toast.LENGTH_SHORT).show()
                                })
                            Text(mode.hint, fontSize = 11.sp, color = Sub)
                            if (f != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!isCurrent) {
                                        TextButton(onClick = {
                                            if (!st.running) {
                                                Prefs.setModelMode(c, mode.id)
                                                onChanged(); toggle(key)
                                            }
                                        }, enabled = !st.running) {
                                            Text("启用 ›", fontSize = 11.sp, color = Primary)
                                        }
                                    } else Text("当前模式", fontSize = 11.sp, color = Sub)
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = {
                                        ModelStore.delete(c, f); onChanged(); toggle(key)
                                    }) { Text("删除重试", fontSize = 11.sp, color = Sub) }
                                }
                                if (!isCurrent && st.running)
                                    Text("正在制作，跑完前不能切", fontSize = 11.sp, color = Warn)
                            } else {
                                TextButton(onClick = onPick, enabled = importing == null) {
                                    Text("导入这份 ›", fontSize = 11.sp, color = Primary)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { copyUrl(cnUrl(mode.file)) }) {
                                    Text("国内地址 ›", fontSize = 11.sp, color = Primary)
                                }
                                TextButton(onClick = { copyUrl(hfUrl(mode.file)) }) {
                                    Text("备用地址 ›", fontSize = 11.sp, color = Sub)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { toggle(key) }) { Text("关闭", fontSize = 13.sp) }
                }
            )
        }

        Spacer(Modifier.height(2.dp))

        CardBox(Modifier.padding(bottom = 6.dp)) {
            if (allReady) {
                Text("底座 + 至少 1 个模式都在", fontSize = 13.sp,
                    fontWeight = FontWeight.Bold)
                Text(current.label + " 是当前模式。" + (if (current == ModelStore.Mode.VOICE_DESIGN)
                    "可以合成音频了。" else "切回「描述造音色」后即可继续。"),
                    fontSize = 11.sp, color = Sub)
                if (current != ModelStore.Mode.VOICE_DESIGN) {
                    TextButton(onClick = {
                        if (!st.running) {
                            Prefs.setModelMode(c, ModelStore.Mode.VOICE_DESIGN.id)
                            onChanged()
                        }
                    }, enabled = !st.running) {
                        Text("切回「描述造音色」 ›", fontSize = 11.sp, color = Primary)
                    }
                    if (st.running)
                        Text("正在制作，跑完前不能切", fontSize = 10.sp, color = Warn)
                }
            } else {
                Text("还差 " + missing.joinToString("、"), fontSize = 13.sp,
                    fontWeight = FontWeight.Bold)
            }
            // H2 · 主按钮语义固定，不随就绪状态变形。
            Button(onClick = onPick, enabled = importing == null,
                modifier = Modifier.fillMaxWidth().height(40.dp)) {
                Text("导入模型文件", fontSize = 13.sp)
            }
            // R165 · 用户实证「模型页不需要去书架按钮」：二级页返回由顶栏统一给，
            // 这里再放一枚只会让人不知道该点哪个。onLibrary 保留给调用方兼容。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                if (files.isNotEmpty()) {
                    Button(onClick = {
                        EventLog.ui("MODEL_UNINSTALL_ASK", "n=${files.size}")
                        confirmUninstall = true
                    }, enabled = !st.running,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Warn)) {
                        Text("卸载模型", fontSize = 12.sp, color = palette.onWarn)
                    }
                }
            }
            if (st.running && files.isNotEmpty())
                Text("正在制作，跑完才能卸载模型", fontSize = 10.sp, color = Sub)
        }

        // H2 · 卸载是破坏性操作：二次确认，只删模型文件，
        // 原书、已做好的音频、分段缓存一律不动；卸载后要重新导入才能再合成。
        if (confirmUninstall) {
            AlertDialog(
                onDismissRequest = { confirmUninstall = false },
                title = { Text("卸载已导入的模型？", fontSize = 15.sp,
                    fontWeight = FontWeight.Bold) },
                text = {
                    Text("会删掉本机 ${ModelStore.files(c).size} 个模型文件。" +
                            "书架里的书、已做好的音频和分段缓存都不动；" +
                            "卸载后要重新导入才能再合成。",
                        fontSize = 13.sp, color = Sub)
                },
                confirmButton = {
                    TextButton(onClick = {
                        val n = ModelStore.files(c).size
                        ModelStore.files(c).forEach { ModelStore.delete(c, it) }
                        EventLog.ui("MODEL_UNINSTALL", "deleted=$n")
                        confirmUninstall = false
                        onChanged()
                        Toast.makeText(c, "已卸载 $n 个模型文件",
                            Toast.LENGTH_SHORT).show()
                    }) { Text("卸载", fontSize = 13.sp, color = Warn) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmUninstall = false }) {
                        Text("取消", fontSize = 13.sp)
                    }
                }
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPick, enabled = importing == null) {
                Text("一次导入多个模型文件 ›", fontSize = 12.sp, color = Primary)
            }
            Spacer(Modifier.weight(1f))
            if (files.isNotEmpty())
                Text("已导入 ${files.size} 个文件", fontSize = 11.sp, color = Sub)
        }
    }
}


/**
 * R142 · 合成前的调音弹层。调音本来就是「点开始之前」的准备工作，
 * 之前却是一张和书架平级的一级页，来回跳还不知道该回哪。
 * 这里用全屏 Dialog 复用整套 MixerScreen（保存、试听、未保存确认都在里面），
 * 顶栏多给一枚关闭叉，返回键也由窗口自己消费。
 */
