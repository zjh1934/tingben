package com.tingben.app.gateway

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
 * R381 · 局域网关页重设计（与中继页同一套卡片 / 对齐 / 折叠语言）。
 *
 * 改前：页内重复大标题、地址与 Token 左对齐平铺、三行按钮、底部一大段
 * R377 说明；开着时一屏塞得很满，主次分不出。
 * 终稿：状态卡 → 地址卡（键值左右对齐）→ 一枚主按钮 → 一排两枚等宽次按钮
 * → 绑定开关卡 → 默认收起的说明卡 → 底部 72dp 给悬浮球。
 * 复制 / 重新生成 Token / 切绑定 / 开关，能力一条没删。
 */
@Composable
private fun GatewayKV(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(72.dp), fontSize = HiCaptionSp, color = palette.sub)
        Text(value, Modifier.weight(1f), fontSize = HiBodySp,
            textAlign = TextAlign.End, maxLines = 1,
            overflow = TextOverflow.Ellipsis, color = palette.text)
    }
}

@Composable
internal fun GatewayScreen(c: Context) {
    var tick by remember { mutableStateOf(0) }
    val on = remember(tick) { GatewayPrefs.enabled(c) }
    val url = remember(tick) { GatewayPrefs.displayUrl(c) }
    val token = remember(tick) { GatewayPrefs.token(c) }
    val lan = remember(tick) { GatewayPrefs.bind(c) == GatewayContract.BIND_LAN }
    var showHow by remember { mutableStateOf(false) }
    val clip = LocalClipboardManager.current

    Column(Modifier.fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(HiPagePad),
        verticalArrangement = Arrangement.spacedBy(HiSectionGap)) {

        // ① 状态 + 绑定，一眼看清现在监听在哪
        CardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(
                    if (on) palette.good else palette.grey, CircleShape))
                Spacer(Modifier.width(9.dp))
                Text(if (on) "已开启" else "未开启", fontSize = HiSectionSp,
                    fontWeight = FontWeight.Bold, color = palette.primary,
                    modifier = Modifier.weight(1f))
                Text(if (lan) "局域网" else "只本机", fontSize = HiCaptionSp,
                    color = palette.sub)
            }
            Spacer(Modifier.height(7.dp))
            Text("调用方改不了音色 · 九项采样取本机调音台预设",
                fontSize = HiCaptionSp, color = palette.sub)
            Spacer(Modifier.height(4.dp))
            Text("错误一律 JSON，不回 HTML；跨网请用「跨网中继」页",
                fontSize = HiMicroSp, color = palette.sub)
        }

        // ② 开了才亮出地址与 Token，键值左右对齐，不再平铺一大片
        if (on) {
            CardBox {
                Text("接口", fontSize = HiSectionSp, fontWeight = FontWeight.Bold,
                    color = palette.primary)
                Spacer(Modifier.height(8.dp))
                GatewayKV("地址", url)
                GatewayKV("Token", token)
                Spacer(Modifier.height(5.dp))
                Text("Token 只存本机，不进安装包、不进日志",
                    fontSize = HiMicroSp, color = palette.sub)
            }
        }

        // ③ 主操作
        Button(onClick = {
            GatewayPrefs.setEnabled(c, !on)
            if (on) GatewayService.stop(c) else GatewayService.start(c)
            EventLog.log(EventLog.Kind.UI, "GATEWAY_CFG",
                if (on) "用户在面板关闭网关" else "用户在面板开启网关")
            tick++
            Toast.makeText(c, if (on) "网关已关闭" else "网关已开启",
                Toast.LENGTH_SHORT).show()
        }, Modifier.fillMaxWidth().height(44.dp)) {
            Text(if (on) "关闭网关" else "开启网关")
        }

        // ④ 次操作：一排两枚等宽
        if (on) {
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    clip.setText(AnnotatedString("$url\nAuthorization: Bearer $token"))
                    Toast.makeText(c, "已复制地址与 Token", Toast.LENGTH_SHORT).show()
                }, Modifier.weight(1f).height(44.dp)) { Text("复制地址与 Token") }
                OutlinedButton(onClick = {
                    GatewayPrefs.regenerate(c)
                    EventLog.log(EventLog.Kind.UI, "GATEWAY_TOKEN_ROTATE",
                        "用户重新生成了本机 Token（值不落盘）")
                    tick++
                    Toast.makeText(c, "Token 已重新生成", Toast.LENGTH_SHORT).show()
                }, Modifier.weight(1f).height(44.dp)) { Text("重新生成 Token") }
            }
        }

        // ⑤ 绑定：一行开关，不再单独占一枚大按钮
        CardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("允许局域网", fontSize = HiSectionSp,
                        fontWeight = FontWeight.Bold, color = palette.text)
                    Text(if (lan) "同 Wi-Fi 的设备可访问" else "只 127.0.0.1，最稳",
                        fontSize = HiCaptionSp, color = palette.sub)
                }
                Switch(checked = lan, onCheckedChange = { wantLan ->
                    GatewayPrefs.setBind(c, wantLan)
                    GatewayService.stop(c)
                    GatewayService.start(c)
                    EventLog.log(EventLog.Kind.UI, "GATEWAY_BIND",
                        if (wantLan) "切到局域网" else "切回只本机")
                    tick++
                })
            }
        }

        // ⑥ 说明默认收起
        CardBox {
            Row(Modifier.fillMaxWidth().clickable { showHow = !showHow },
                verticalAlignment = Alignment.CenterVertically) {
                Text("怎么调用", fontSize = HiSectionSp, fontWeight = FontWeight.Bold,
                    color = palette.primary, modifier = Modifier.weight(1f))
                Text(if (showHow) "收起 ⌃" else "展开 ›", fontSize = HiCaptionSp,
                    color = palette.sub)
            }
            if (!showHow) {
                Spacer(Modifier.height(6.dp))
                Text("短文本直接回音频 · 长文本走任务 · 回传前压 M4A",
                    fontSize = HiMicroSp, color = palette.sub)
            }
            if (showHow) {
                Spacer(Modifier.height(8.dp))
                GatewayKV("短文本", "POST /v1/speak → 直接回音频")
                GatewayKV("长文本", "POST /v1/jobs → 轮询取回")
                GatewayKV("网页", "浏览器打开地址填 Token")
                Spacer(Modifier.height(6.dp))
                Text("回传前在手机本地压成 M4A（约 WAV 的 1/10）；并发锁 1；"
                    + "长文本分段仍留 Phase 2。",
                    fontSize = HiMicroSp, color = palette.sub)
            }
        }

        Spacer(Modifier.height(72.dp))
    }
}
