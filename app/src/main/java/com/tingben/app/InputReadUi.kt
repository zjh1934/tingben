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

// @ui-part: InputReadUi
@Composable
internal fun InputReadScreen(c: Context, onBack: () -> Unit, onAdded: (String) -> Unit,
                            asHome: Boolean = false,
                            importingBook: Boolean = false,
                            modelMissing: Int = 0,
                            onModels: () -> Unit = {},
                            // R230 · 调音/任务上移系统顶栏、悬浮开关收回设置页，
                            // 首页内容区不再单独占一排按钮。
                           ) {
    val clipboard = LocalClipboardManager.current
    val inputScope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    // R176 · 建书是异步落盘，连点会重复触发；成功后清空输入框，
    // 免得用户看着还在的全文以为没点上又按一次。
    var adding by rememberSaveable { mutableStateOf(false) }
    // R215 · 首页即贴即听的合成状态；与分享页的「建书」状态分开，互不顶掉。
    var listening by rememberSaveable { mutableStateOf(false) }
    val listenScope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose {
        // 离开页面时别让 native 还在闷头合成下一段
        if (listening) { try { NativeEngine.nativeCancel() } catch (_: Throwable) { } }
    } }
    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        // 这是「正在发生的事」，不是常驻说明文字：只在导入中显示
        if (importingBook) {
            Text("正在后台导入一本书…", fontSize = 11.sp, color = Sub,
                modifier = Modifier.padding(bottom = 6.dp))
        }
        // R180 · 没有模型就合成不了。模型页藏在设置里，新装用户根本不知道。
        // 这里只给一行状态 + 一个入口，模型齐了整行消失；不写教程、不常驻。
        if (modelMissing > 0) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Text("还差 $modelMissing 份模型，还不能合成", fontSize = 12.sp,
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
                Text("输入朗读", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = palette.text)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onBack) { Text("关闭", fontSize = 12.sp, color = Sub) }
            }
            Spacer(Modifier.height(12.dp))
        }
        // R171 · 用户实证「文本框上面有废话文字说明」。标题、说明书、输入框 label
        // 三处讲同一件事，全撤：空框里一句 placeholder，输入后自动消失。
        // R214 · 框不再 weight(1f) 占满整屏，收到约 1/3 屏；
        // 粘贴 / 清除搬进框内右下角，不再单独占一行。
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.36f)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("粘贴或输入要念的正文", fontSize = 12.sp) },
                modifier = Modifier.fillMaxSize().padding(bottom = 34.dp),
                textStyle = TextStyle(fontSize = 14.sp)
            )
            Row(Modifier.align(Alignment.BottomEnd)
                    .padding(end = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    val got = clipboard.getText()?.text ?: ""
                    if (got.isNotBlank()) text = got
                    else Toast.makeText(c, "剪贴板里没有文字", Toast.LENGTH_SHORT).show()
                }) { Text("粘贴", fontSize = 12.sp) }
                TextButton(onClick = { text = "" }) { Text("清除", fontSize = 12.sp) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            if (asHome) {
                // R215 · 首页就是「粘一段即贴即听」：不建书、不落书架、不跳页。
                // 文字原样留着，方便改一句再听一次。
                Button(enabled = !listening && text.isNotBlank(), onClick = {
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
                    if (listening) return@Button
                    listening = true
                    val snapshot = text
                    // 即贴即听 = 单球浮窗（chapterPct < 0）
                    OverlayProgress.update(c, "", "合成中", 0, 1, -1f)
                    listenScope.launch {
                        val res = withContext(Dispatchers.IO) {
                            runCatching {
                                ClipSynth.synthesize(c, snapshot,
                                    "clip_listen.wav", "首页即贴即听")
                            }
                        }
                        withContext(Dispatchers.Main) {
                            listening = false
                            val path = res.getOrNull()
                            if (path != null) {
                                OverlayProgress.update(c, "", "完成", 1, 1, -1f)
                                // 试听通道独立，不碰主播放队列与播放通知
                                PlayQueue.setTracks(listOf(Track(File(path), "这段朗读",
                                    id = "clip:$path")), "trial")
                                PlayQueue.playFrom(0, id = "trial")
                                EventLog.ui("CLIP_LISTEN", "chars=${snapshot.length}")
                                listenScope.launch {
                                    delay(1200)
                                    OverlayProgress.hide(c)
                                }
                            } else {
                                OverlayProgress.hide(c)
                                val why = res.exceptionOrNull()?.message ?: "未知原因"
                                EventLog.log(EventLog.Kind.ENGINE, "CLIP_LISTEN_FAIL", why)
                                Toast.makeText(c, "没合成出来：$why", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }) { Text(if (listening) "正在合成…" else "听这段", fontSize = 13.sp) }
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
                                    // R180 · 建完就进这本书，不再让用户回抽屉里找
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
                }) { Text(if (adding) "正在加…" else "建书", fontSize = 13.sp) }
            }
        }
    }
}

