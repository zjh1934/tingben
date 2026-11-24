package com.tingben.app.relay

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * R377 · 设置页 →「跨网中继」独立页（沿用 R354 各入口各自成页）。
 *
 * Token 只由用户在这里填一次，绝不打进安装包。页面明写三条代价：
 * 文字经过 GitHub、异步秒级、拿到的是手机本地压缩好的音频。
 */
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
    val clip = LocalClipboardManager.current

    Column(Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("跨网中继", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("用你自己的 GitHub 免费私有仓库当中间服务器：沙盒 / 任何拿到地址的人，"
            + "不装东西、不买服务器、不走局域网，就能把文字发到这台手机合成。"
            + "音色用调音台当前预设，接口不许改。",
            fontSize = 14.sp)
        Text("默认关闭 · 开启前先填 Token · 文字会经过 GitHub（合成完立刻删请求文件）",
            fontSize = 12.sp)

        OutlinedTextField(
            value = tokenInput, onValueChange = { tokenInput = it },
            label = { Text("GitHub 细粒度 PAT（只给这个仓库 Contents 读写）") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = ownerInput, onValueChange = { ownerInput = it },
                label = { Text("账号") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(value = repoInput, onValueChange = { repoInput = it },
                label = { Text("仓库") }, modifier = Modifier.weight(1f), singleLine = true)
        }

        Button(onClick = {
            RelayPrefs.setToken(c, tokenInput)
            RelayPrefs.setOwner(c, ownerInput)
            RelayPrefs.setRepo(c, repoInput)
            if (RelayPrefs.token(c).isBlank()) {
                Toast.makeText(c, "先填 Token 再开", Toast.LENGTH_SHORT).show()
            } else {
                RelayPrefs.setEnabled(c, !on)
                if (on) RelayService.stop(c) else RelayService.start(c)
                tick++
                Toast.makeText(c, if (on) "跨网中继已关闭" else "跨网中继已开启",
                    Toast.LENGTH_SHORT).show()
            }
        }, Modifier.fillMaxWidth().height(44.dp)) {
            Text(if (on) "关闭跨网中继" else "开启跨网中继")
        }

        Text("状态：" + state, fontSize = 12.sp)
        Button(onClick = {
            clip.setText(AnnotatedString(
                "https://github.com/" + owner + "/" + repo + "\n" +
                "https://zjh1934.github.io/ting-ben-apk/relay.html"))
            Toast.makeText(c, "已复制仓库地址与网页地址", Toast.LENGTH_SHORT).show()
        }, Modifier.fillMaxWidth().height(44.dp)) { Text("复制仓库地址与网页地址") }

        Text("数据怎么走：调用方写 queue/{id}.json → 手机取走 → 开始震动 → 本机预设合成 "
            + "→ 回传前在手机本地压成 M4A（约 WAV 的 1/10）→ 写 out + status → 结束震动 "
            + "→ 删掉请求文件 → 调用方取回压缩音频。开始是短促两下，完成是三连渐强加长收尾，"
            + "节奏不一样。", fontSize = 11.sp)
        Text("手机被远程调用时会震动；异步，几秒到十几秒；Doze 熄屏久了会断，属于系统行为，"
            + "不强拉活。输出 M4A/AAC 不是 MP3：安卓全版本没有原生 MP3 编码器，"
            + "平台自带 AAC 压缩效果一致，真 .mp3 需另排一轮内嵌 LAME。", fontSize = 11.sp)
    }
}
