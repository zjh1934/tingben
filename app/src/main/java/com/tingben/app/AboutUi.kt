package com.tingben.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tingben.app.core.UpdateChecker
import com.tingben.app.diag.EventLog
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/**
 * R408 · 「关于听本」页。三路调研综合定案：
 *  · 抽屉里那枚「v1.1.62」不再是「一次性跳浏览器」，而是一个入口；
 *  · 页内全部信息本地可生成，无网、无浏览器也能读完；
 *  · 检查更新只读 jsdelivr 上的公开 atom（UpdateChecker），手动触发 / 24h 节流；
 *  · 外链失败不再只给一句无法操作的提示，改为兜底面板（复制 / 重试 / 分享 / 诊断）；
 *  · 复制内容严格白名单：无书名、文件名、朗读正文、Token、Android ID、序列号。
 *
 * 这是独立全屏壳，不挂进设置页层级（用户对「设置下再叠一层」明确不满）。
 * 唯一顶栏 = 返回箭头 + 当前页名；浅深两套语义色、字号令牌、底部安全区全沿用。
 */
private const val REPO = "https://github.com/zjh1934/tingben"

private fun pkgInfo(c: Context) =
    if (Build.VERSION.SDK_INT >= 33)
        c.packageManager.getPackageInfo(c.packageName, PackageManager.PackageInfoFlags.of(0))
    else @Suppress("DEPRECATION") c.packageManager.getPackageInfo(c.packageName, 0)

private fun versionCodeOf(c: Context): Long = try {
    val p = pkgInfo(c)
    if (Build.VERSION.SDK_INT >= 28) p.longVersionCode
    else @Suppress("DEPRECATION") p.versionCode.toLong()
} catch (_: Throwable) { -1L }

/** 复制/分享内容的唯一出口：纯文本键值对，隐私白名单写死在这里。 */
private fun diagnostics(c: Context): String = buildString {
    append("听本 ").append(BuildConfig.VERSION_NAME)
        .append(" (").append(versionCodeOf(c)).append(") ")
        .append(if (BuildConfig.DEBUG) "debug" else "release").append('\n')
    append("git:").append(BuildConfig.GIT_HASH)
        .append("  build:").append(BuildConfig.BUILD_TIME).append('\n')
    append("Android ").append(Build.VERSION.RELEASE)
        .append(" (SDK ").append(Build.VERSION.SDK_INT).append(") · ")
        .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
    append("ABI:").append(Build.SUPPORTED_ABIS.firstOrNull() ?: "-")
        .append(" · 更新源:").append(UpdateChecker.CURRENT_SOURCE).append('\n')
    append("不含书名 / 文件名 / 朗读正文 / Token / 设备标识")
}

/** 连点 7 次才解锁的静态字段：只显示体积与签名短哈希，不碰合成链。 */
private fun apkMeta(c: Context): String = try {
    val p = pkgInfo(c)
    // CI 真编译实证：PackageInfo.applicationInfo 在 Kotlin 里是可空类型，
    // 直接取 sourceDir 会报 Only safe (?.) or non-null asserted calls are allowed。
    val src = p.applicationInfo?.sourceDir ?: throw IllegalStateException("no apk path")
    val size = File(src).length() / 1024 / 1024
    val sigs = if (Build.VERSION.SDK_INT >= 28)
        p.signingInfo?.apkContentsSigners
    else @Suppress("DEPRECATION") p.signatures
    val raw = sigs?.firstOrNull()?.toByteArray()
    val hash = if (raw != null) MessageDigest.getInstance("SHA-256")
        .digest(raw).joinToString("") { "%02x".format(it) }.take(12) else "-"
    "签名 sha256:$hash · 安装包 $size MB（路径不外露）"
} catch (_: Throwable) { "签名摘要暂不可用" }

/** 随包 changelog.txt：CI 由 CHANGELOG.md 生成（.txt 才过 APK 闸门），离线也能看「本版更新」与历史。 */
private fun changelogSections(c: Context): List<Pair<String, String>> {
    val txt = try { c.assets.open("changelog.txt").bufferedReader(Charsets.UTF_8).use { it.readText() } }
    catch (_: Throwable) { "" }
    if (txt.isBlank()) return listOf("本版更新" to "随包说明未就绪，可点「项目主页」查看。")
    val out = ArrayList<Pair<String, String>>()
    val parts = txt.split(Regex("(?m)^## \\[[^\\]]+\\]"))
    val heads = Regex("(?m)^## \\[([^\\]]+)\\]").findAll(txt).map { it.groupValues[1] }.toList()
    for (i in heads.indices) out.add("v" + heads[i] to (parts.getOrNull(i + 1) ?: "").trim())
    return out
}

@Composable
private fun AboutBackGlyph(tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val s = size.width
        drawLine(tint, Offset(s * 0.62f, s * 0.18f), Offset(s * 0.30f, s * 0.50f),
            strokeWidth = s * 0.09f, cap = StrokeCap.Round)
        drawLine(tint, Offset(s * 0.30f, s * 0.50f), Offset(s * 0.62f, s * 0.82f),
            strokeWidth = s * 0.09f, cap = StrokeCap.Round)
    }
}

@Composable
private fun AboutCard(title: String, body: @Composable ColumnScope.() -> Unit) {
    val p = palette
    Column(Modifier.fillMaxWidth()
        .padding(horizontal = HiPagePad, vertical = 6.dp)
        .background(p.card, RoundedCornerShape(HiCardRadius))
        .padding(HiCardPad)) {
        Text(title, fontSize = HiSectionSp, fontWeight = FontWeight.Bold, color = p.primary)
        Spacer(Modifier.height(8.dp))
        body()
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    val p = palette
    Row(Modifier.fillMaxWidth().heightIn(min = 24.dp)) {
        Text(label, fontSize = HiCaptionSp, color = p.sub,
            modifier = Modifier.width(76.dp))
        Text(value, fontSize = HiBodySp, color = p.text,
            modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AboutAction(label: String, primary: Boolean = false,
                        enabled: Boolean = true, onClick: () -> Unit) {
    val p = palette
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .background(if (primary) p.primary.copy(alpha = 0.12f) else Color.Transparent,
            RoundedCornerShape(HiChipRadius))
        .clickable(enabled = enabled, onClick = onClick)
        .semantics { contentDescription = label }
        .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = HiBodySp, fontWeight = FontWeight.SemiBold,
            color = (if (primary) p.primary else p.text)
                .copy(alpha = if (enabled) 1f else 0.4f))
    }
}

@Composable
internal fun AboutScreen(c: Context, onClose: () -> Unit) {
    val p = palette
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateChecker.State>(UpdateChecker.State.Idle) }
    var snack by remember { mutableStateOf("") }
    var snackText by remember { mutableStateOf("") }
    var fallbackUrl by remember { mutableStateOf<String?>(null) }
    var showUpdate by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var taps by remember { mutableStateOf(0) }
    var lastTap by remember { mutableStateOf(0L) }
    var unlocked by remember { mutableStateOf(false) }

    fun copy(text: String, msg: String) {
        clip.setText(AnnotatedString(text))
        snackText = text; snack = msg
    }
    fun share(text: String) {
        try {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
            }
            c.startActivity(Intent.createChooser(i, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
            copy(text, "没有可分享的应用，内容已复制")
        }
    }
    fun open(url: String, onFail: () -> Unit) {
        try {
            c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
            copy(url, "链接已复制")
            onFail()
        }
    }
    fun recheck(force: Boolean) {
        state = UpdateChecker.State.Checking
        scope.launch { state = UpdateChecker.check(c, force) }
    }

    LaunchedEffect(Unit) { recheck(false) }

    Dialog(onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()
            .statusBarsPadding().navigationBarsPadding().imePadding(),
            color = p.bg) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()
                    .then(if (fallbackUrl != null || showUpdate || showLog)
                        Modifier.clickable { } else Modifier)) {
                    // 唯一顶栏：返回箭头 + 当前页名（不叠「设置 / 关于」两层）
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .padding(horizontal = HiPagePad),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose,
                            modifier = Modifier.semantics { contentDescription = "返回" }) {
                            AboutBackGlyph(p.primary)
                        }
                        Spacer(Modifier.width(4.dp))
                        Text("关于听本", fontSize = HiTitleSp,
                            fontWeight = FontWeight.Bold, color = p.text)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp)
                        .background(p.line.copy(alpha = HiHairAlpha)))
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(bottom = 96.dp)) {

                        // ① 检查更新（首行主卡）
                        AboutCard("检查更新") {
                            when (val st = state) {
                                is UpdateChecker.State.Checking ->
                                    Text("正在检查…", fontSize = HiBodySp, color = p.text)
                                // R409 · 失败态只给原因，按钮统一由卡片底部那一个给，
                                // 不再自己再画一个「再查一次」（真机截图出现过两个）。
                                is UpdateChecker.State.Failed ->
                                    Text(st.reason, fontSize = HiBodySp, color = p.warn)
                                is UpdateChecker.State.Latest ->
                                    Text("已是最新版本 v" + st.version +
                                        if (st.fromCache) "（上次检查结果）" else "",
                                        fontSize = HiBodySp, color = p.text)
                                is UpdateChecker.State.Available -> {
                                    Text("发现新版本 v" + st.info.version + " · 点此查看",
                                        fontSize = HiBodySp, fontWeight = FontWeight.Bold,
                                        color = p.primary)
                                    Spacer(Modifier.height(6.dp))
                                    AboutAction("查看更新了什么", primary = true) { showUpdate = true }
                                }
                                is UpdateChecker.State.Idle ->
                                    Text("点击「再查一次」检查更新", fontSize = HiBodySp, color = p.text)
                            }
                            Spacer(Modifier.height(4.dp))
                            AboutAction("再查一次",
                                enabled = state !is UpdateChecker.State.Checking) { recheck(true) }
                            Text("更新源：jsdelivr 公开订阅 · 不上传任何个人信息 · 24 小时内只自动查一次",
                                fontSize = HiMicroSp, color = p.sub)
                        }

                        // ② 版本信息（连点 7 次彩蛋只解锁静态字段）
                        AboutCard("版本信息") {
                            AboutRow("版本", "v" + BuildConfig.VERSION_NAME)
                            AboutRow("构建", versionCodeOf(c).toString() + " · " +
                                (if (BuildConfig.DEBUG) "debug" else "release") +
                                " · git:" + BuildConfig.GIT_HASH)
                            AboutRow("构建时间", BuildConfig.BUILD_TIME)
                            AboutRow("系统", "Android " + Build.VERSION.RELEASE +
                                " (SDK " + Build.VERSION.SDK_INT + ")")
                            AboutRow("设备", Build.MANUFACTURER + " " + Build.MODEL)
                            AboutRow("运行环境", (Build.SUPPORTED_ABIS.firstOrNull() ?: "-") +
                                " · 命中源 " + UpdateChecker.CURRENT_SOURCE)
                            if (unlocked) AboutRow("彩蛋", apkMeta(c))
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clickable {
                                    val now = System.currentTimeMillis()
                                    taps = if (now - lastTap < 2000L) taps + 1 else 1
                                    lastTap = now
                                    if (taps >= 7) {
                                        unlocked = true
                                        EventLog.log(EventLog.Kind.UI, "ABOUT_EASTER_EGG", "静态字段解锁")
                                    }
                                }
                                .semantics { contentDescription = "版本号 v" + BuildConfig.VERSION_NAME },
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("v" + BuildConfig.VERSION_NAME, fontSize = HiCaptionSp,
                                    fontWeight = FontWeight.SemiBold, color = p.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("连点可看签名与体积", fontSize = HiMicroSp, color = p.sub)
                            }
                        }

                        // ③ 诊断信息（隐私承诺写在按钮旁，不写在隐私政策里）
                        AboutCard("诊断信息") {
                            Text("一键复制给开发者排查用；不含任何书名、文件、朗读正文、Token 与设备标识。",
                                fontSize = HiCaptionSp, color = p.sub)
                            Spacer(Modifier.height(6.dp))
                            AboutAction("复制诊断信息", primary = true) {
                                copy(diagnostics(c), "已复制，可粘贴到 Issue（不含任何文件信息）")
                            }
                        }

                        // ④ 项目主页（外链降级 + 失败兜底，不再有死路 toast）
                        AboutCard("项目主页") {
                            SelectionContainer {
                                Text(REPO, fontSize = HiBodySp, color = p.text)
                            }
                            Spacer(Modifier.height(6.dp))
                            AboutAction("打开网页") { open(REPO) { fallbackUrl = REPO } }
                            AboutAction("复制链接") { copy(REPO, "链接已复制") }
                            AboutAction("分享链接") { share(REPO) }
                        }

                        // ⑤ 发版说明（随包，离线可读；历史更新常驻按钮，不藏小问号）
                        val sections = remember { changelogSections(c) }
                        AboutCard("发版说明") {
                            val cur = sections.firstOrNull()
                            Text(cur?.first ?: "v" + BuildConfig.VERSION_NAME,
                                fontSize = HiBodySp, fontWeight = FontWeight.SemiBold, color = p.text)
                            Spacer(Modifier.height(4.dp))
                            Text((cur?.second ?: "").lineSequence().take(6).joinToString("\n"),
                                fontSize = HiCaptionSp, color = p.sub)
                            Spacer(Modifier.height(6.dp))
                            AboutAction("查看完整更新记录（共 " + sections.size + " 条）") { showLog = true }
                        }

                        // ⑥ 开源组件（手写静态列表，不引 AboutLibraries）
                        AboutCard("开源组件") {
                            AboutRow("界面", "Jetpack Compose · Apache-2.0")
                            AboutRow("设计", "Material3 · Apache-2.0")
                            AboutRow("语言", "Kotlin · Apache-2.0")
                            AboutRow("异步", "kotlinx-coroutines · Apache-2.0")
                            Text("全部在本地运行；默认零出站，网络能力只用于你手动开启的功能。",
                                fontSize = HiMicroSp, color = p.sub)
                        }
                    }
                }

                // 兜底面板：content → scrim → dialog（R397 模态协议）
                fallbackUrl?.let { url ->
                    Box(Modifier.fillMaxSize().background(p.text.copy(alpha = 0.45f))
                        .clickable { fallbackUrl = null }) { }
                    Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(12.dp), color = p.card,
                        shape = RoundedCornerShape(HiCardRadius)) {
                        Column(Modifier.padding(HiCardPad)) {
                            Text("这台设备暂时打不开网页", fontSize = HiSectionSp,
                                fontWeight = FontWeight.Bold, color = p.text)
                            Spacer(Modifier.height(8.dp))
                            Text("链接已复制，粘贴到任意浏览器即可打开：",
                                fontSize = HiCaptionSp, color = p.sub)
                            Spacer(Modifier.height(6.dp))
                            SelectionContainer {
                                Text(url, fontSize = HiBodySp, color = p.primary)
                            }
                            Spacer(Modifier.height(10.dp))
                            AboutAction("重试打开", primary = true) {
                                fallbackUrl = null; open(url) { fallbackUrl = url }
                            }
                            AboutAction("分享链接") { share(url) }
                            AboutAction("复制诊断信息") {
                                copy(diagnostics(c), "已复制，可粘贴到 Issue（不含任何文件信息）")
                            }
                            TextButton(onClick = { fallbackUrl = null }) { Text("关闭") }
                        }
                    }
                }

                // 发现新版本：本版说明 + 四个动作，全程不强制
                if (showUpdate && state is UpdateChecker.State.Available) {
                    val info = (state as UpdateChecker.State.Available).info
                    Box(Modifier.fillMaxSize().background(p.text.copy(alpha = 0.45f))
                        .clickable { showUpdate = false }) { }
                    Surface(Modifier.align(Alignment.Center).fillMaxWidth()
                        .padding(16.dp), color = p.card,
                        shape = RoundedCornerShape(HiCardRadius)) {
                        Column(Modifier.padding(HiCardPad)) {
                            Text("v" + info.version + " 更新了什么", fontSize = HiSectionSp,
                                fontWeight = FontWeight.Bold, color = p.primary)
                            Spacer(Modifier.height(8.dp))
                            val note = info.summary.ifBlank {
                                changelogSections(c).firstOrNull()?.second.orEmpty()
                            }
                            Text(note.lineSequence().take(8).joinToString("\n")
                                .ifBlank { "本次更新说明见项目主页。" },
                                fontSize = HiCaptionSp, color = p.text)
                            if (info.bytes > 0) Text("安装包约 " +
                                (info.bytes / 1024 / 1024) + " MB",
                                fontSize = HiMicroSp, color = p.sub)
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { open(info.url) { fallbackUrl = info.url } },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text("下载更新")
                            }
                            AboutAction("复制直链") { copy(info.url, "下载直链已复制") }
                            AboutAction("查看历史更新") { showLog = true }
                            AboutAction("跳过此版本") {
                                UpdateChecker.skip(c, info.version); showUpdate = false
                            }
                            TextButton(onClick = { showUpdate = false }) { Text("稍后") }
                        }
                    }
                }

                // 完整更新记录（随包，离线可读）
                if (showLog) {
                    Box(Modifier.fillMaxSize().background(p.text.copy(alpha = 0.45f))
                        .clickable { showLog = false }) { }
                    Surface(Modifier.fillMaxSize().padding(24.dp), color = p.card,
                        shape = RoundedCornerShape(HiCardRadius)) {
                        Column(Modifier.padding(HiCardPad)) {
                            Text("完整更新记录", fontSize = HiTitleSp,
                                fontWeight = FontWeight.Bold, color = p.text)
                            Spacer(Modifier.height(8.dp))
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                changelogSections(c).forEach { (v, body) ->
                                    Spacer(Modifier.height(10.dp))
                                    Text(v, fontSize = HiSectionSp,
                                        fontWeight = FontWeight.Bold, color = p.primary)
                                    Text(body, fontSize = HiCaptionSp, color = p.sub)
                                }
                            }
                            Button(onClick = { showLog = false },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text("返回")
                            }
                        }
                    }
                }

                if (snack.isNotEmpty()) {
                    Snackbar(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                        action = {
                            TextButton(onClick = {
                                if (snackText.isNotBlank()) share(snackText)
                                snack = ""
                            }) { Text("分享") }
                        }) { Text(snack, fontSize = HiCaptionSp) }
                }
            }
        }
    }
}
