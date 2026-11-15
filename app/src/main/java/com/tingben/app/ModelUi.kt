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
internal fun ModelScreen(c: Context, refresh: Int,
                         importing: String?, importBytes: Long, importError: String?,
                         onPick: () -> Unit, onBack: () -> Unit, onLibrary: () -> Unit,
                         onChanged: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val st by Producer.state.collectAsState()
    val files = remember(refresh) { ModelStore.files(c) }
    val missing = remember(refresh) { ModelStore.missing(c) }
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

        // R355 · 顶栏已经写了「模型」，页内不再重复一个大标题。
        // 状态与「已导入 N 个文件」并成一行，底下那行重复入口一并撤掉。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (allReady) "4 份已就绪"
                 else "还差 ${missing.size} 份模型",
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = if (allReady) Good else Warn,
                modifier = Modifier.weight(1f))
            if (files.isNotEmpty())
                Text("已导入 ${files.size} 个文件", fontSize = 11.sp, color = Sub)
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
        // R355 · 三张卡同一行等宽：不再两列 + 第三张孤零零掉到下一行，
        // 右边空半格还正好被悬浮进度球压住，看着像没排完。
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            modes.forEach { mode ->
                val f = modeFiles[mode]
                val isCurrent = mode == current
                val open = expanded == mode.id
                CardBox(Modifier.weight(1f).height(96.dp).padding(bottom = 6.dp)
                    .clickable { toggle(mode.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(mode.label, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                            color = if (isCurrent || open) Primary else Color.Unspecified,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    Text(if (open) "收起" else "详情 ›",
                        fontSize = 10.sp, color = Primary)
                }
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
                                }) { Text("删这一份重导", fontSize = 12.sp, color = Warn) }
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
                                    }) { Text("删这一份重导", fontSize = 11.sp, color = Warn) }
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
                // R355 · 顶部已写「4 份已就绪」，这里不再重复一遍份数。
                Text("当前 · 「${current.label}」", fontSize = 13.sp,
                    fontWeight = FontWeight.Bold)
                // R326 · 三种模式本来就能各自合成，别再诱导手动切回第一种。
                Text("合成就用这一模式，不用切回。", fontSize = 11.sp, color = Sub)
                if (current != ModelStore.Mode.VOICE_DESIGN) {
                    TextButton(onClick = {
                        if (!st.running) {
                            Prefs.setModelMode(c, ModelStore.Mode.VOICE_DESIGN.id)
                            onChanged()
                        }
                    }, enabled = !st.running) {
                        Text("改用「描述造音色」（会换掉当前模式） ›",
                            fontSize = 11.sp, color = Primary)
                    }
                }
            } else {
                Text("还有 ${missing.size} 份没导入", fontSize = 13.sp,
                    fontWeight = FontWeight.Bold)
                missing.forEach { m ->
                    Text("· $m", fontSize = 11.sp, color = Warn,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (st.running) {
                // R355 · 两行几乎一样的「跑完前不能…」合成一句。
                Text("正在制作，跑完前不能切换或卸载", fontSize = 10.sp, color = Warn)
            }
            // R355 · 导入与卸载并列等大两枚；不再一大一小、不再有第二个导入入口。
            // H2 · 主按钮语义仍固定，不随就绪状态变形；卸载仍走二次确认。
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onPick, enabled = importing == null,
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(12.dp)) {
                    Text("导入模型文件", fontSize = 13.sp)
                }
                if (files.isNotEmpty()) {
                    Button(onClick = {
                        EventLog.ui("MODEL_UNINSTALL_ASK", "n=${files.size}")
                        confirmUninstall = true
                    }, enabled = !st.running,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Warn)) {
                        Text("卸载模型", fontSize = 12.sp, color = palette.onWarn)
                    }
                }
            }
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
                            "抽屉里的书、已做好的音频和分段缓存都不动；" +
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
        // R355 · 底部第二个「一次导入多个模型文件」入口撤掉：
        // 主按钮本来就支持多选文件，两个入口只会让人不知道该点哪个。
    }
}


/**
 * R142 · 合成前的调音弹层。调音本来就是「点开始之前」的准备工作，
 * 之前却是一张和书架平级的一级页，来回跳还不知道该回哪。
 * 这里用全屏 Dialog 复用整套 MixerScreen（保存、试听、未保存确认都在里面），
 * 顶栏多给一枚关闭叉，返回键也由窗口自己消费。
 */
