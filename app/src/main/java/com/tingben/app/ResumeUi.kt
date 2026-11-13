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

// @ui-part: ResumeUi
@Composable
internal fun ResumeAskDialog(
    title: String, done: Int, total: Int, label: String,
    action: Producer.ResumeAction,
    onPrimary: () -> Unit, onView: () -> Unit, onLater: () -> Unit
) {
    Dialog(onDismissRequest = onLater) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = palette.card),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp)
                            .background(palette.primarySoft, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center) {
                        IconResume(palette.primary, Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("上次没做完", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        color = palette.text)
                    Spacer(Modifier.weight(1f))
                    // R320 · 图 45：右上角 chip 原来直接塞 label（可能是百分比），
                    // 和下面按 done/total 算出来的百分比互相打架。这里只写状态，
                    // 整张卡只留一个百分比数字。
                    Box(Modifier.background(palette.primarySoft, RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("未完成", fontSize = 11.sp, color = palette.primary)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = palette.text, maxLines = 2)
                Spacer(Modifier.height(14.dp))

                val frac = if (total > 0)
                    (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(6.dp)
                        .clip(RoundedCornerShape(3.dp)).background(palette.line)) {
                    Box(Modifier.fillMaxWidth(frac).height(6.dp)
                            .clip(RoundedCornerShape(3.dp)).background(palette.primary))
                }
                Spacer(Modifier.height(8.dp))
                // R320 · 「385 / 26678 块」是调试数字，用户看不懂也不该看；
                // 换成一句状态说明，百分比仍由同一份 frac 算，全卡只出现一次。
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (label.isNotBlank() && !label.contains("%")) label
                         else "接着上次继续，不会从头再来",
                        fontSize = 12.sp, color = palette.sub,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text("${(frac * 100).toInt()}%", fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, color = palette.primary)
                }
                Spacer(Modifier.height(20.dp))

                Button(onClick = onPrimary,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(50)) {
                    Text(when (action) {
                        Producer.ResumeAction.AUTO -> "继续制作"
                        Producer.ResumeAction.QUEUE -> "继续队列"
                        Producer.ResumeAction.VIEW -> "查看任务"
                    }, fontSize = 14.sp)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (action != Producer.ResumeAction.VIEW) {
                        OutlinedButton(onClick = onView,
                            modifier = Modifier.weight(1f).height(40.dp),
                            shape = RoundedCornerShape(50)) {
                            Text("查看任务", fontSize = 12.sp)
                        }
                    }
                    // J 系列看图结论：并排的次按钮要同形，一个描边一个裸文字
                    // 看着像「少画了一个框」；层级靠文字颜色区分，不靠外形。
                    OutlinedButton(onClick = onLater,
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RoundedCornerShape(50)) {
                        Text("稍后", fontSize = 12.sp, color = palette.sub)
                    }
                }
            }
        }
    }
}
