package com.tingben.app.gateway

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * R375 · 设置页 →「语音网关」独立页（沿用 R354 六入口各自成页的口径）。
 *
 * 默认关。开了才显示地址与 Token；绑定默认只 127.0.0.1，点「允许局域网」
 * 才切 0.0.0.0（网关面板同时给地址，普通用户不用查 IP）。
 * 本轮明确告诉用户：跨网/公网还没开，下一轮配沙盒侧环境。
 */
@Composable
internal fun GatewayScreen(c: Context) {
    var tick by remember { mutableStateOf(0) }
    val on = remember(tick) { GatewayPrefs.enabled(c) }
    val url = remember(tick) { GatewayPrefs.displayUrl(c) }
    val token = remember(tick) { GatewayPrefs.token(c) }
    val lan = remember(tick) { GatewayPrefs.bind(c) == GatewayContract.BIND_LAN }
    val clip = LocalClipboardManager.current

    Column(Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("语音网关", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("把这台手机变成一个只认文字的语音合成接口。音色、节奏、九项采样"
            + "全部用你在这台手机上已经设好的预设，调用方改不了。",
            fontSize = 14.sp)
        Text("默认关闭 · 开启后才会监听 · 只要开启就要 Token",
            fontSize = 12.sp)

        Button(onClick = {
            GatewayPrefs.setEnabled(c, !on)
            if (on) GatewayService.stop(c) else GatewayService.start(c)
            tick++
            Toast.makeText(c, if (on) "网关已关闭" else "网关已开启",
                Toast.LENGTH_SHORT).show()
        }, Modifier.fillMaxWidth().height(44.dp)) {
            Text(if (on) "关闭网关" else "开启网关")
        }

        if (on) {
            Text("地址", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(url, fontSize = 14.sp)
            Text("Token", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(token, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    clip.setText(AnnotatedString("$url\nAuthorization: Bearer $token"))
                    Toast.makeText(c, "已复制地址与 Token", Toast.LENGTH_SHORT).show()
                }, Modifier.weight(1f).height(44.dp)) { Text("复制地址与 Token") }
                Button(onClick = {
                    GatewayPrefs.regenerate(c)
                    tick++
                    Toast.makeText(c, "Token 已重新生成", Toast.LENGTH_SHORT).show()
                }, Modifier.weight(1f).height(44.dp)) { Text("重新生成 Token") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    GatewayPrefs.setBind(c, !lan)
                    GatewayService.stop(c)
                    GatewayService.start(c)
                    tick++
                }, Modifier.weight(1f).height(44.dp)) {
                    Text(if (lan) "改回只本机" else "允许局域网")
                }
            }
            Text("当前绑定：${if (lan) "局域网（同 Wi-Fi 可访问）" else "只本机"}",
                fontSize = 12.sp)
            Text("在浏览器打开上面的地址，把 Token 填进页面就能直接合成并播放。",
                fontSize = 12.sp)
        }

        Text("R377 起：本机 / 局域网网关保留，回传前已在手机本地压成 M4A（不是大 WAV）。"
            + "跨网走设置页「跨网中继」独立页，借你自己的 GitHub 免费仓库，零付费、"
            + "不装东西。长文本分段仍留 Phase 2。",
            fontSize = 11.sp)
    }
}
