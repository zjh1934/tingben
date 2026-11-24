package com.tingben.app.relay

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tingben.app.CardBox
import com.tingben.app.HiBodySp
import com.tingben.app.HiCaptionSp
import com.tingben.app.HiMicroSp
import com.tingben.app.HiPagePad
import com.tingben.app.HiSectionGap
import com.tingben.app.HiSectionSp
import com.tingben.app.diag.EventLog
import com.tingben.app.palette

/**
 * R381 · 中继页重设计（先按现行代码画出来看了一眼，再动手）。
 *
 * 看图抓到的四处：顶栏已有「跨网中继」页里又写一遍大标题；三坨长说明把
 * 一屏塞满；粗紫输入框 label 长到折行；状态行贴着悬浮进度球被压住；
 * 两枚全宽大按钮 + 底部两大段 11sp 字，主次完全分不出。
 *
 * 终稿：状态卡 → 连接卡 → 一枚主按钮 → 一排两枚等宽次按钮 → 默认收起的
 * 「怎么工作」卡 → 底部 72dp 留给悬浮球。所有长说明收进折叠区，键值左右
 * 对齐，尺寸全走 R370 的 Hi* 令牌，功能一条没删（含「一次复制两段」）。
 */
@Composable
private fun RelayKV(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(72.dp), fontSize = HiCaptionSp, color = palette.sub)
        Text(value, Modifier.weight(1f), fontSize = HiBodySp,
            textAlign = TextAlign.End, color = palette.text)
    }
}

@Composable
internal fun RelayScreen(c: Context) {
    var tick by remember { mutableStateOf(0) }
    val on = remember(tick) { RelayPrefs.enabled(c) }
    val owner = remember(tick) { RelayPrefs.owner(c) }
    val repo = remember(tick) { RelayPrefs.repo(c) }
    val token = remember(tick) { RelayPrefs.token(c) }
    val state = remember(tick) { RelayPrefs.state(c) }
    var tokenInput by remember { mutableStateOf(token) }
    var ownerInput by remember { mutableStateOf(owner) }
    var repoInput by remember { mutableStateOf(repo) }
    var showFlow by remember { mutableStateOf(false) }
    val clip = LocalClipboardManager.current
    val repoUrl = "https://github.com/$owner/$repo"
    val pageUrl = "https://zjh1934.github.io/ting-ben-apk/relay.html"

    // R383 · 真机日志实证：设置页外壳 SettingsUi 已经是
    // Column(fillMaxSize().verticalScroll())，这里再套一层 verticalScroll，
    // 内层滚动容器就会收到「无限高」约束，Compose 在 measure 阶段直接抛
    // IllegalStateException 闪退。本页只放内容，滚动交给外层那唯一一层。
    Column(Modifier.fillMaxWidth().padding(HiPagePad),
        verticalArrangement = Arrangement.spacedBy(HiSectionGap)) {

        // ① 状态：一行开关态 + 一行实时状态，不再让悬浮球压住文字
        CardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(
                    if (on) palette.good else palette.grey, CircleShape))
                Spacer(Modifier.width(9.dp))
                Text(if (on) "已开启" else "未开启", fontSize = HiSectionSp,
                    fontWeight = FontWeight.Bold, color = palette.primary,
                    modifier = Modifier.weight(1f))
                Text(owner + "/" + repo, fontSize = HiCaptionSp, color = palette.sub)
            }
            Spacer(Modifier.height(7.dp))
            Text(if (on) state.ifBlank { "轮询中 · 等请求" }
                 else "默认关闭 · 开启后手机会轮询仓库",
                fontSize = HiCaptionSp, color = palette.sub)
            Spacer(Modifier.height(5.dp))
            Text("音色取调音台当前预设 · 文字经 GitHub，合成完即删请求",
                fontSize = HiMicroSp, color = palette.sub)
        }

        // ② 连接：短标签、两列等宽、Token 只在本机
        CardBox {
            Text("连接", fontSize = HiSectionSp, fontWeight = FontWeight.Bold,
                color = palette.primary)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = tokenInput, onValueChange = { tokenInput = it },
                label = { Text("GitHub Token") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = ownerInput, onValueChange = { ownerInput = it },
                    label = { Text("账号") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(value = repoInput, onValueChange = { repoInput = it },
                    label = { Text("仓库") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            Spacer(Modifier.height(7.dp))
            Text("Token 只存在本机，不进安装包、不进日志、不进诊断摘要",
                fontSize = HiMicroSp, color = palette.sub)
        }

        // ③ 主操作：一枚，状态可反悔
        Button(onClick = {
            RelayPrefs.setToken(c, tokenInput)
            RelayPrefs.setOwner(c, ownerInput)
            RelayPrefs.setRepo(c, repoInput)
            if (RelayPrefs.token(c).isBlank()) {
                Toast.makeText(c, "先填 Token 再开", Toast.LENGTH_SHORT).show()
            } else {
                EventLog.log(EventLog.Kind.UI, "RELAY_CFG",
                    "owner=" + ownerInput.trim() + " · repo=" + repoInput.trim() +
                        " · Token=已填（值不落盘）")
                RelayPrefs.setEnabled(c, !on)
                if (on) RelayService.stop(c) else RelayService.start(c)
                tick++
                Toast.makeText(c, if (on) "跨网中继已关闭" else "跨网中继已开启",
                    Toast.LENGTH_SHORT).show()
            }
        }, Modifier.fillMaxWidth().height(44.dp)) {
            Text(if (on) "关闭跨网中继" else "开启跨网中继")
        }

        // ④ 次操作：一排两枚等宽，不再占两三排
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                clip.setText(AnnotatedString(repoUrl))
                Toast.makeText(c, "已复制仓库地址", Toast.LENGTH_SHORT).show()
            }, Modifier.weight(1f).height(44.dp)) { Text("复制仓库地址") }
            OutlinedButton(onClick = {
                clip.setText(AnnotatedString(pageUrl))
                Toast.makeText(c, "已复制网页地址", Toast.LENGTH_SHORT).show()
            }, Modifier.weight(1f).height(44.dp)) { Text("复制网页地址") }
        }

        // ⑤ 细节默认收起：要看的人点开，不看的人一屏干净
        CardBox {
            Row(Modifier.fillMaxWidth().clickable { showFlow = !showFlow },
                verticalAlignment = Alignment.CenterVertically) {
                Text("怎么工作", fontSize = HiSectionSp, fontWeight = FontWeight.Bold,
                    color = palette.primary, modifier = Modifier.weight(1f))
                Text(if (showFlow) "收起 ⌃" else "展开 ›", fontSize = HiCaptionSp,
                    color = palette.sub)
            }
            if (!showFlow) {
                Spacer(Modifier.height(6.dp))
                Text("3 步闭环 · 开始两下，完成是近 1 秒长收尾",
                    fontSize = HiMicroSp, color = palette.sub)
            }
            if (showFlow) {
                Spacer(Modifier.height(8.dp))
                RelayKV("① 调用方", "写 queue/{id}.json")
                RelayKV("② 手机", "取走 → 震动 → 本机预设合成 → 压 M4A")
                RelayKV("③ 取回", "写 out + status → 删请求 → 取音频")
                Spacer(Modifier.height(6.dp))
                Text("开始＝短促两下；完成＝三连渐强＋约 1 秒长收尾；失败＝三下轻急。",
                    fontSize = HiMicroSp, color = palette.sub)
                Spacer(Modifier.height(4.dp))
                Text("异步几秒到十几秒；Doze 熄屏久了会断。输出 M4A/AAC 不是 MP3："
                    + "安卓无原生 MP3 编码器，平台自带 AAC 压缩效果一致。",
                    fontSize = HiMicroSp, color = palette.sub)
                TextButton(onClick = {
                    clip.setText(AnnotatedString(repoUrl + "\n" + pageUrl))
                    Toast.makeText(c, "已复制两段地址", Toast.LENGTH_SHORT).show()
                }) { Text("一次复制两段地址", fontSize = HiCaptionSp) }
            }
        }

        // 悬浮进度球常驻左下，底部留足，别再压住状态与说明
        Spacer(Modifier.height(72.dp))
    }
}
