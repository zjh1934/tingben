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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

// @ui-part: SettingsUi
@Composable
internal fun OverlayProgressRow(c: Context) {
    var on by remember { mutableStateOf(Prefs.overlayProgress(c)) }
    // R217 · 用户说「悬浮窗的开关好像没有做」：开关其实在，但默认关、藏在
    // 设置页末尾，而且打开后若没给「显示在其他应用上层」权限会静默不显示，
    // 看起来就像开关是假的。回前台复检权限，把状态与去授权的出口摆在同一行。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var permTick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) permTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val permOk = remember(permTick) { KeepAlive.overlayGranted(c) }
    fun askPermission() {
        if (!KeepAlive.requestOverlay(c))
            Toast.makeText(c, "打不开设置页，请到应用详情里开「显示在其他应用上层」",
                Toast.LENGTH_LONG).show()
        else Toast.makeText(c, "允许后回来，制作时就能看到悬浮进度",
                Toast.LENGTH_LONG).show()
    }
    // R239 · 统一行：缺「显示在其他应用上层」权限时整行染警示色，
    // 不再只是副标题一小截灰字里藏着一句提示。
    SettingLine(UiIcon.PROGRESS, "悬浮进度",
        if (!on) "关闭" else if (!permOk) "还需允许「显示在其他应用上层」"
        else "打开即显示预览 · 制作时自动更新 · 停止即收起",
        danger = on && !permOk) {
        if (on && !permOk) {
            TextButton(onClick = { askPermission() }) {
                Text("去允许 ›", fontSize = 12.sp, color = Primary)
            }
        }
        Switch(checked = on, onCheckedChange = {
            on = it
            Prefs.setOverlayProgress(c, it)
            if (!it) OverlayProgress.hide(c)
            else if (!permOk) askPermission()
            else OverlayProgress.update(c, "", "预览", 0, 0, -1f,
                OverlaySource.PREVIEW)
            // R230 · 真修「打开开关根本不理」：以前只有制作中 update 才创建浮窗，
            // 开关打开后当前没在制作就毫无反应。现在权限齐了立刻挂一个 0% 单球预览，
            // 制作开始时由 ProduceService.update 原地更新，停止/完成照旧 hide。
            EventLog.ui("OVERLAY_SETTING", "on=$it · perm=$permOk")
        })
    }
}

/** R159 · 顶层组合函数，不能写进 SettingsScreen 里（Compose 不接受局部组合函数）。 */
@Composable
internal fun AutoResumeRow(c: Context) {
    var on by remember { mutableStateOf(Prefs.autoResumeOnStart(c)) }
    SettingLine(UiIcon.RESUME, "打开软件自动续跑", "只接着没做完的，做完的不重跑") {
        Switch(checked = on, onCheckedChange = {
            on = it
            Prefs.setAutoResumeOnStart(c, it)
            EventLog.ui("RESUME_SETTING", "auto=$it")
        })
    }
}

/** R171 · 「应用详情」是入口不是权限，原来复用 PermRow 会显示「已开启 · 查看」。
 *  R320 · 前导图标由调用方指定，不再拿标题首字放大顶数。 */
@Composable
internal fun PermEntryRow(icon: UiIcon, title: String, hint: String, go: () -> Unit) {
    SettingLine(icon, title, hint) {
        Button(onClick = go,
            modifier = Modifier.height(34.dp),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = PrimarySoft, contentColor = Primary),
            contentPadding = PaddingValues(horizontal = 14.dp)) {
            Text("打开设置 ›", fontSize = 12.sp)
        }
    }
}

/**
 * R238 · 设置页统一行：借鉴 Material 3 Preference 的「前导图标 / 主标题 /
 * 副标题 / 尾部控件」层级，用项目调色板自绘，不引图标库、不加依赖。
 * 以前每行都手搓一遍 Column(weight) + Text + Spacer(2.dp) + 小字，
 * 行高与左右对齐全靠运气；现在所有行等高、副标题可省、危险项单独色。
 * R320 · 前导槽不再用 title.take(1) 放大一个汉字冒充图标：改成 UiIcons.kt
 * 里相对坐标自绘的矢量图形，每项各配一枚有语义的 icon，深浅主题随 tint。
 */
@Composable
internal fun SettingLine(
    icon: UiIcon,
    title: String,
    hint: String = "",
    danger: Boolean = false,
    trailing: @Composable RowScope.() -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(32.dp)
                .background(if (danger) Warn.copy(alpha = 0.14f) else PrimarySoft,
                    RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            UiIconGlyph(icon, if (danger) Warn else Primary, 18.dp)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = if (danger) Warn else palette.text)
            if (hint.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(hint, fontSize = 11.sp, color = Sub, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
    }
}

/**
 * R245 · 设置页分组重画：借鉴 Material 3 Preference / AOSP Settings 的分组层级
 * ——分组标题用主色小字 + 一根代码画的竖条做锚，卡片留白统一，低频项后置。
 * 旧形态是「彩色小徽标框 + 粗黑大标题」，四张卡各挂「软件/系统/排查/本机」，
 * 纯装饰还把标题行挤满。badge **不再渲染**，只进 semantics 供读屏分组；
 * 调用签名一字不动（r156/r171/r174/r238/r239/r73/r152 都按它定位）。
 */
@Composable
internal fun SettingsSection(
    title: String, badge: String, open: Boolean = true, onToggle: (Boolean) -> Unit = {},
    collapsible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = palette.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth()
                    .let { if (collapsible) it.clickable { onToggle(!open) } else it }
                    .semantics { contentDescription = "$title · $badge" },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 代码画的主色竖条，替代原来的彩色徽标框
                Box(Modifier.size(width = 3.dp, height = 15.dp)
                    .background(Primary, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(9.dp))
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    color = Primary, modifier = Modifier.weight(1f))
                if (collapsible) {
                    Text(if (open) "收起 ⌃" else "展开 ›", fontSize = 12.sp, color = Sub)
                }
            }
            if (!collapsible || open) {
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}

/** R158 · 设置页权限行。必须是顶层组合函数，局部函数 Compose 编译器不接受。
 *  R165 · 用户实证「权限如果已经有了，应该是变绿，不是还跟原来一样去开启」：
 *  已开启换成绿底，未开启维持主色；绿底前景按主题取，保证深浅都读得清。 */
@Composable
internal fun PermRow(icon: UiIcon, title: String, granted: Boolean,
                     hint: String, go: () -> Unit) {
    val goodFg = if (appThemeDark) Color(0xFF0E1117) else Color(0xFFFFFFFF)
    SettingLine(icon, title, hint) {
        Button(onClick = go,
            modifier = Modifier.height(34.dp),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (granted) Good else Primary,
                contentColor = if (granted) goodFg else palette.onPrimary),
            contentPadding = PaddingValues(horizontal = 14.dp)) {
            Text(if (granted) "已打开" else "去打开", fontSize = 12.sp)
        }
    }
}

/**
 * R341 · 合成设置缺的不是开关，是「改完到底生效成啥」的一眼可见。
 * 三档朗读预设早停用（会抢 prompt）、采样九项在调音台、64kbps 单声道
 * M4A 是硬口径，都不该开成新选项（r221/r231 守恒）。所以只补只读摘要：
 * 音色模式 / 节奏 / 成章方式 / 接缝 / 成品格式，外加「改一次对所有书生效」。
 * 数值直接吃当前 state，点一下档位立刻跟着变，不是另一份会漂的副本。
 */
@Composable
internal fun ProduceOverviewCard(
    c: Context, gapMs: Int, retries: Int, autoMerge: Boolean, declickMs: Int
) {
    Column(Modifier.fillMaxWidth()
        .background(palette.primarySoft.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
        .padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("当前这一套", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = Primary, modifier = Modifier.weight(1f))
            Text("对所有书生效", fontSize = 11.sp, color = Sub)
        }
        Spacer(Modifier.height(6.dp))
        ProduceOverviewLine("音色", modeText(Prefs.modelMode(c)))
        ProduceOverviewLine("节奏", "段间静音 ${gapMs}ms · 失败重做 $retries 次")
        ProduceOverviewLine("成章",
            if (autoMerge) "小块齐了自动拼这一章"
            else "关自动 · 去目录页点「拼接并转码」")
        ProduceOverviewLine("接缝",
            when (declickMs) { 0 -> "直接接"; 15 -> "轻"; else -> "标准" })
        ProduceOverviewLine("成品", "64kbps 单声道 M4A · 验过才删内部 WAV")
    }
}

@Composable
internal fun ProduceOverviewLine(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(k, fontSize = 11.sp, color = Sub, modifier = Modifier.width(40.dp))
        Text(v, fontSize = 11.sp, color = palette.text,
            modifier = Modifier.weight(1f))
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun SettingsScreen(c: Context, refresh: Int, onModels: () -> Unit,
                           onLibrary: () -> Unit,
                           onMixer: () -> Unit) {
    val missing = remember(refresh) { ModelStore.missing(c) }
    var themeMode by remember { mutableStateOf(Prefs.themeMode(c)) }
    // R171 · 开发者工具默认收起，普通用户打开设置不再先看到两枚排查按钮。
    var devOpen by remember { mutableStateOf(false) }

    // R156 · 成品导出：让用户用系统文件管理器自己选一个文件夹（一般是 Download），
    // 记住后把每章成品 WAV 按书名分文件夹写进去。不开放内部 work/audio 路径配置，
    // 理由写在 core/AudioExport.kt 的类注释里。
    val expScope = rememberCoroutineScope()
    // R158 · 系统特殊权限不给 result code，用户从设置页返回后必须复检。
    var permTick by remember { mutableStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) permTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val batOk = remember(permTick) { KeepAlive.batteryIgnoring(c) }
    val ovOk = remember(permTick) { KeepAlive.overlayGranted(c) }
    var expTreeLabel by remember { mutableStateOf(AudioExport.treeLabel(c)) }
    var expCount by remember { mutableStateOf(0) }
    // 中间小块按书分组：bookId → 书名，避免把 UUID 当分组名露给用户
    var bookTitles by remember { mutableStateOf(emptyMap<String, String>()) }
    // R268 · 导出以前只有一闪而过的 Toast：点完没反应、结果也回看不了。
    // 现在按钮有导出中态、结果留在页里，重复点也进不来。
    var exporting by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf("") }
    LaunchedEffect(refresh) {
        expCount = withContext(Dispatchers.IO) { AudioExport.collect(c).size }
        bookTitles = withContext(Dispatchers.IO) {
            BookStore.list(c).associate { it.id to it.title }
        }
    }
    // 两个本机目录在设置里可达：私有目录第三方文件管理器进不去，
    // 所以应用内列清单，点一个文件另存到用户自己挑的位置。
    // R320 · 两个本机目录合成一个浏览入口，弹层里用页签切，不再各占一行
    var browseOpen by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<File?>(null) }
    val saveOne = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val src = pendingSave
        pendingSave = null
        if (uri != null && src != null) {
            expScope.launch {
                val ok = withContext(Dispatchers.IO) { AudioOps.copyTo(c, src, uri) }
                Toast.makeText(c, if (ok) "已另存" else "另存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
    // R308 · M4A 成品用 audio/mp4 打开系统保存界面，别把压缩音频当 WAV 写出去。
    val saveOneM4a = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/mp4")) { uri ->
        val src = pendingSave
        pendingSave = null
        if (uri != null && src != null) {
            expScope.launch {
                val ok = withContext(Dispatchers.IO) { AudioOps.copyTo(c, src, uri) }
                Toast.makeText(c, if (ok) "已另存" else "另存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        AudioExport.saveTree(c, uri)
        expTreeLabel = AudioExport.treeLabel(c)
        Toast.makeText(c, "导出位置已记住", Toast.LENGTH_SHORT).show()
    }

    // R142 · 日志是给开发者排查用的：导出完整 .md，或一键复制摘要直接贴过来。
    val exportScope = rememberCoroutineScope()
    val diagClipboard = LocalClipboardManager.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exportScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val os = c.contentResolver.openOutputStream(uri)
                    if (os == null) {
                        UiErrors.storage("诊断日志没导出来：系统没给写入通道", null)
                        false
                    } else {
                        os.use { it.write(EventLog.exportBundle(c).toByteArray(Charsets.UTF_8)) }
                        true
                    }
                } catch (t: Throwable) {
                    UiErrors.storage("诊断日志没导出来", t)
                    false
                }
            }
            if (ok) Toast.makeText(c, "诊断日志已保存", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 14.dp, vertical = 12.dp)) {
        // R245 · 状态卡不再是页面上一大块色斑：中性底 + 一圈淡边框 + 一枚
        // 状态圆点，和下面分组卡同一套材质，缺模型时边框转警示色。
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                .border(1.dp,
                    if (missing.isEmpty()) Primary.copy(alpha = 0.30f)
                    else Warn.copy(alpha = 0.45f),
                    RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = palette.card),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp)
                        .background(if (missing.isEmpty()) Good else Warn,
                            RoundedCornerShape(50)))
                    Spacer(Modifier.width(9.dp))
                    Text(if (missing.isEmpty()) "模型已就绪" else "模型还没齐",
                        fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        color = if (missing.isEmpty()) Primary else Warn,
                        modifier = Modifier.weight(1f))
                    Button(onClick = onModels,
                        modifier = Modifier.height(32.dp),
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 14.dp)) {
                        Text("查看 ›", fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(if (missing.isEmpty()) "4 份已就绪"
                     else "还差 ${missing.size} 份 · 共 4 份：" + missing.joinToString("、"),
                    fontSize = 12.sp, color = Sub, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                // R238 · 设备型号 / ABI 是诊断日志的内容（r141 仍在那里守），
                // 摆到设置首页只是调试噪声；这里只留软件版本。
                Text("软件版本 " + com.tingben.app.BuildConfig.VERSION_NAME,
                    fontSize = 11.sp, color = Sub)
            }
        }

        SettingsSection("外观与显示", "软件", collapsible = false) {
            // R239 · 主题是三段选择器，不是「一行 + 尾部控件」；但标题行也走
            // SettingLine 的徽标槽，和下面「悬浮进度」左右对齐，trailing 留空。
            SettingLine(UiIcon.PALETTE, "主题", "外观基调") { }
            Row(Modifier.fillMaxWidth()
                .background(PrimarySoft, RoundedCornerShape(10.dp))
                .padding(3.dp)) {
                listOf("跟随系统" to 0, "浅色" to 1, "深色" to 2).forEach { (label, m) ->
                    val on = themeMode == m
                    Box(Modifier.weight(1f).height(34.dp)
                        .background(if (on) palette.card else Color.Transparent,
                            RoundedCornerShape(8.dp))
                        .clickable {
                            themeMode = m
                            Prefs.setThemeMode(c, m)
                            applyAppTheme(c, m)
                        },
                        contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 12.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            color = if (on) Primary else Sub)
                    }
                }
            }
            OverlayProgressRow(c)
            // R320 · 图 46：不透明浅底的双球是「上＝整本、下＝这一章」，
            // 旧图里两个环叠在一起没有说明，谁也认不出哪个是哪个。浮窗本体
            // 仍按 R214 口径不写字，图例只放设置页里。
            Row(Modifier.fillMaxWidth()
                    .padding(start = 43.dp, top = 2.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                UiIconGlyph(UiIcon.PROGRESS, Primary, 14.dp)
                Text(" 整本", fontSize = 11.sp, color = Sub)
                Spacer(Modifier.width(12.dp))
                UiIconGlyph(UiIcon.PROGRESS, Sub, 14.dp)
                Text(" 这一章", fontSize = 11.sp, color = Sub)
                Spacer(Modifier.weight(1f))
                Text("不透明浅底", fontSize = 10.sp, color = Sub)
            }
        }

        SettingsSection("运行与通知", "系统", collapsible = false) {
            AutoResumeRow(c)
        }
        // R320 · 埋的雷兑现：段间静音 / 失败重做 / 自动拼章 / 接缝处理本来就是
        // 全局偏好（写 Prefs，与哪一本书无关），却一直挂在带 bookId 的独立
        // 「制作设置」页里，用户想改一次参数还得先开一本书。现在统一收进设置页，
        // 点一下即落盘；那张独立页降级成只管「这本书」的操作页，不再定参数。
        SettingsSection("制作设置", "合成", collapsible = false) {
            var cfg by remember { mutableStateOf(Prefs.defaultPerf(c)) }
            var autoMerge by remember { mutableStateOf(Prefs.autoMergeChapter(c)) }
            var declickMs by remember { mutableStateOf(Prefs.mergeDeclickMs(c)) }
            // R341 · 先看「现在生效的是哪一套」，再逐项改；四枚 OptionRow 一字未动
            ProduceOverviewCard(c, cfg.gapMs, cfg.gateRetries, autoMerge, declickMs)
            Spacer(Modifier.height(8.dp))
            OptionRow("段间静音", "两小段之间的停顿",
                ProduceConfig.GAP_OPTIONS.map { it to "${it}ms" }, cfg.gapMs) {
                cfg = cfg.copy(gapMs = it)
                Prefs.setDefaultPerf(c, cfg)
            }
            OptionRow("分段失败重做", "某一段没出来时再试几次",
                ProduceConfig.RETRY_OPTIONS.map { it to "${it}次" }, cfg.gateRetries) {
                cfg = cfg.copy(gateRetries = it)
                Prefs.setDefaultPerf(c, cfg)
            }
            OptionRow("自动拼成整章", "关掉后小块先留着，去目录页小块下面点「拼接并转码」",
                listOf(true to "开", false to "关"), autoMerge) {
                autoMerge = it
                Prefs.setAutoMergeChapter(c, it)
            }
            OptionRow("接缝处理", "相邻小块淡入淡出，压掉咔哒声",
                listOf(0 to "直接接", 15 to "轻", 30 to "标准"), declickMs) {
                declickMs = it
                Prefs.setMergeDeclickMs(c, it)
            }
            TextButton(onClick = {
                cfg = ProduceConfig()
                Prefs.setDefaultPerf(c, cfg)
                autoMerge = true
                Prefs.setAutoMergeChapter(c, true)
                declickMs = 30
                Prefs.setMergeDeclickMs(c, 30)
                Toast.makeText(c, "已恢复默认制作设置", Toast.LENGTH_SHORT).show()
            }) { Text("恢复默认", fontSize = 12.sp, color = Sub) }
        }
        // R303 · 权限单独成块：每行按真实状态显示「已打开」/「去打开」，
        // 点一下都直接落到安卓系统对应的权限设置页，应用里不写教程。
        // 回前台（permTick）复检，已给绿底、未给主色，四行同一口径。
        SettingsSection("权限", "权限", collapsible = false) {
            val notifOk = remember(permTick) { NotifPerm.granted(c) }
            val goodFg = if (appThemeDark) Color(0xFF0E1117) else Color(0xFFFFFFFF)
            SettingLine(UiIcon.BELL, "通知权限",
                if (notifOk) "制作进度会显示在通知栏" else "还没允许") {
                Button(onClick = {
                        try {
                            val i = android.content.Intent(
                                android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, c.packageName)
                            c.startActivity(i)
                        } catch (_: Throwable) {
                            Toast.makeText(c, "这台机器没有通知设置入口", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.height(34.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (notifOk) Good else Primary,
                        contentColor = if (notifOk) goodFg else palette.onPrimary),
                    contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Text(if (notifOk) "已打开" else "去打开", fontSize = 12.sp)
                }
            }
            // R158b · 用户明确「教程不要塞进应用里」：长说明、图文步骤、
            // 箭头流程全部撤掉，只留按钮 + 状态。说明放沙盒方案文档，不进 APK。
            PermRow(UiIcon.SHIELD, "忽略电池优化", batOk, "后台不被系统掐断") {
                if (!KeepAlive.requestIgnoreBattery(c))
                    Toast.makeText(c, "打不开设置页，请手动到应用详情里找",
                        Toast.LENGTH_SHORT).show()
            }
            PermRow(UiIcon.PROGRESS, "显示在其他应用上层", ovOk, "悬浮进度需要") {
                if (!KeepAlive.requestOverlay(c))
                    Toast.makeText(c, "打不开设置页，请手动到应用详情里找",
                        Toast.LENGTH_SHORT).show()
            }
            PermEntryRow(UiIcon.SETTINGS, "应用详情", "系统设置") {
                if (!KeepAlive.openAppDetails(c))
                    Toast.makeText(c, "这台机器打不开应用详情", Toast.LENGTH_SHORT).show()
            }
        }
        // R320 · 图 37 / 旧「文件与数据」重设计：五行把「选导出位置 / 导出全部 /
        // 看临时小块 / 看整章成品目录 / 清理临时段」平铺；「整章成品」和
        // 「整章成品目录」几乎同名，只读查看与破坏性清理还挤在同一层。
        // 收成三件事：导出成品（位置并进副行）、浏览本机音频（成品/小块切换）、
        // 清理临时段（警示色 + 二次确认）。四种能力一条没丢，行从 5 压到 3。
        SettingsSection("音频文件", "本机", collapsible = false) {
            SettingLine(UiIcon.M4A, "整章成品",
                "已做好 $expCount 个 · M4A · 拼好即转码") {
                Button(enabled = expCount > 0 && !exporting, onClick = {
                        val tree = AudioExport.treeUri(c)
                        if (tree == null) {
                            Toast.makeText(c, "先点「更改」挑一个文件夹",
                                Toast.LENGTH_SHORT).show()
                        } else {
                            exporting = true
                            exportResult = ""
                            expScope.launch {
                                val r = try {
                                    withContext(Dispatchers.IO) {
                                        AudioExport.exportAll(c, tree)
                                    }
                                } catch (_: Throwable) {
                                    AudioExport.Result(reason = "导出中断，请重试")
                                }
                                val msg = when {
                                    r.failed > 0 -> "导出 ${r.ok} 个，失败 ${r.failed} 个" +
                                            if (r.reason.isNotEmpty()) "（${r.reason}）" else ""
                                    r.ok == 0 && r.reason.isNotEmpty() -> r.reason
                                    r.ok == 0 && r.skipped > 0 ->
                                        "没有新文件要写，${r.skipped} 个之前已导出"
                                    r.ok == 0 -> "还没有可导出的成品"
                                    r.skipped > 0 -> "已导出 ${r.ok} 个，跳过 ${r.skipped} 个已存在的"
                                    else -> "已导出 ${r.ok} 个成品"
                                }
                                exporting = false
                                exportResult = msg
                                Toast.makeText(c, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier.height(34.dp),
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Text(if (exporting) "正在导出…" else "导出全部", fontSize = 12.sp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 43.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("导出到 " + expTreeLabel, fontSize = 11.sp, color = Sub,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = {
                        try { treeLauncher.launch(AudioExport.treeUri(c)) }
                        catch (_: Throwable) {
                            Toast.makeText(c, "这台机器打不开文件夹选择",
                                Toast.LENGTH_SHORT).show()
                        }
                    }, modifier = Modifier.height(30.dp)) {
                    Text("更改", fontSize = 11.sp, color = Primary)
                }
            }
            if (exportResult.isNotEmpty()) {
                Text(exportResult, fontSize = 11.sp, color = Sub,
                    modifier = Modifier.padding(start = 43.dp, bottom = 4.dp))
            }
            SettingLine(UiIcon.FOLDER, "本机音频",
                "章成品 / 中间小块 · 按书分组 · 点一个另存") {
                Button(onClick = { browseOpen = true },
                    modifier = Modifier.height(34.dp),
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Text("浏览", fontSize = 12.sp)
                }
            }
            ClearSegmentsSettingLine(c)
        }
        SettingsSection("开发者工具", "排查", open = devOpen,
                        onToggle = { devOpen = it }) {
            // R142 · 日志定位：给开发者排查用，不是给使用者看的。
            Text("开发日志", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            // R225 脱敏之后日志只留长度/哈希/id，这句旧提示会误导人以为有隐私风险
            Text("只记诊断信号，不记书名与正文", fontSize = 11.sp, color = Sub)
            Spacer(Modifier.height(8.dp))
            // R165 · 用户实证「一键复制 / 导出 md 也应该是按钮，要有圆角矩形的底」：
            // 原来只是两枚裸文字按钮，看着不像能按。两枚等宽，次/主靠底色区分。
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    EventLog.ui("DIAG_COPY", "summary")
                    exportScope.launch {
                        val txt = withContext(Dispatchers.IO) { EventLog.exportSummary(c) }
                        diagClipboard.setText(AnnotatedString(txt))
                        Toast.makeText(c, "诊断摘要已复制，直接贴给开发者",
                            Toast.LENGTH_SHORT).show()
                    }
                },
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimarySoft, contentColor = Primary),
                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("一键复制摘要", fontSize = 12.sp)
                }
                Button(onClick = {
                    EventLog.ui("DIAG_EXPORT", "markdown")
                    val name = "tingben_diag_" +
                        java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                            .format(java.util.Date()) + ".md"
                    try { exportLauncher.launch(name) }
                    catch (t: Throwable) {
                        UiErrors.storage("打不开系统保存界面，没法导出日志", t)
                    }
                },
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary, contentColor = palette.onPrimary),
                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("导出完整 .md", fontSize = 12.sp)
                }
            }
        }

        if (browseOpen) {
            LocalFilesDialog(
                roots = listOf("整章成品" to AudioLibrary.dir(c),
                               "中间小块" to File(c.filesDir, "work")),
                bookTitles = bookTitles,
                onSave = { f ->
                    pendingSave = f
                    try {
                        if (f.name.endsWith(".m4a", ignoreCase = true))
                            saveOneM4a.launch(f.name)
                        else saveOne.launch(f.name)
                    } catch (_: Throwable) { pendingSave = null }
                },
                onDismiss = { browseOpen = false })
        }

    }
}

/** 按书分组递归列 WAV。
 *  中间小块实际在 work/<bookId>/v11/direct/all/ 第四层，旧的两层列举根本列不出来；
 *  bookId 是 UUID 也不该当组名露给用户，用书名替换，查不到的长 id 只留前 8 位。
 *  整章成品在 audio/<书名>/ 下，第一层目录名本身就是书名，直接沿用。 */
internal fun browseGroups(root: File, bookTitles: Map<String, String>): List<Pair<String, List<File>>> {
    val prefix = root.absolutePath + File.separator
    val byGroup = linkedMapOf<String, MutableList<File>>()
    try {
        root.walkTopDown().forEach { f ->
            // R308 · 章成品已改成 M4A，只列 WAV 会让「我的音频」空掉。
            if (!f.isFile || !(f.name.endsWith(".wav", ignoreCase = true) ||
                    f.name.endsWith(".m4a", ignoreCase = true))) return@forEach
            val rel = f.absolutePath.removePrefix(prefix)
            val top = rel.substringBefore(File.separatorChar, "")
            val group = when {
                top.isEmpty() -> root.name
                bookTitles.containsKey(top) -> bookTitles.getValue(top)
                top.length > 24 -> top.take(8) + "…"
                else -> top
            }
            byGroup.getOrPut(group) { mutableListOf() }.add(f)
        }
    } catch (_: Throwable) { }
    return byGroup.map { (g, fs) -> g to fs.sortedByDescending { it.lastModified() } }
        .sortedBy { it.first }
}

/** R320 · 清理临时段改成统一设置行 + 警示色 + 二次确认：
 *  以前它和「查看目录」这种只读动作平铺在同一张卡里，点错一下就删一堆文件。
 *  逻辑与 ModelUi.ClearSegmentsRow 一致，IO 删、只清 work/ 下的 WAV。 */
@Composable
internal fun ClearSegmentsSettingLine(c: Context) {
    var confirm by remember { mutableStateOf(false) }
    val clearScope = rememberCoroutineScope()
    SettingLine(UiIcon.BROOM, "清理中间小块",
        "只删制作过程的临时 WAV，成品和模型不动", danger = true) {
        Button(onClick = { confirm = true },
            modifier = Modifier.height(34.dp),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = Warn.copy(alpha = 0.14f), contentColor = Warn),
            contentPadding = PaddingValues(horizontal = 14.dp)) {
            Text("清理", fontSize = 12.sp)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("清理中间小块？", fontSize = 14.sp) },
            text = { Text("只删制作过程里生成的分段 WAV（会连留着的其他音色缓存一起清掉），" +
                    "书架原书、成品和模型文件都不动。", fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    clearScope.launch {
                        val n = withContext(Dispatchers.IO) {
                            var k = 0
                            try {
                                File(c.filesDir, "work").walkTopDown()
                                    .filter { it.isFile && it.name.endsWith(".wav") }
                                    .forEach { if (it.delete()) k++ }
                            } catch (_: Throwable) { }
                            k
                        }
                        Toast.makeText(c, "已清理 $n 个分段音频",
                            Toast.LENGTH_SHORT).show()
                    }
                }) { Text("确认清理", fontSize = 13.sp, color = Warn) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) {
                    Text("取消", fontSize = 13.sp)
                }
            }
        )
    }
}

/** 本机音频目录的应用内浏览：按书分组列 M4A / WAV，点一个另存到用户挑的位置。
 *  R320 · 两个目录收进同一个弹层，用页签切，不再在设置页各占一行。 */
@Composable
internal fun LocalFilesDialog(roots: List<Pair<String, File>>,
                              bookTitles: Map<String, String>,
                              onSave: (File) -> Unit, onDismiss: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val (label, root) = roots[tab]
    val groups = remember(root.absolutePath) { browseGroups(root, bookTitles) }
    val total = groups.sumOf { it.second.size }
    val segRe = Regex("""^(?:chunk|direct)_(\d{4})\.wav$""")
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(Modifier.fillMaxWidth(0.92f), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text("按书分组 · $total 个 · 可另存",
                    fontSize = 11.sp, color = Sub)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()
                    .background(PrimarySoft, RoundedCornerShape(10.dp))
                    .padding(3.dp)) {
                    roots.forEachIndexed { i, (name, _) ->
                        val on = i == tab
                        Box(Modifier.weight(1f).height(32.dp)
                            .background(if (on) palette.card else Color.Transparent,
                                RoundedCornerShape(8.dp))
                            .clickable { tab = i },
                            contentAlignment = Alignment.Center) {
                            Text(name, fontSize = 12.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                color = if (on) Primary else Sub)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (groups.isEmpty()) {
                    Text("这里现在没有音频文件", fontSize = 12.sp, color = Sub)
                } else {
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        groups.forEach { (g, fs) ->
                            item(key = "group_$g") {
                                Text("$g · ${fs.size} 个", fontSize = 11.sp,
                                    color = Primary, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(
                                        start = 10.dp, end = 10.dp,
                                        top = 8.dp, bottom = 3.dp))
                            }
                            items(fs, key = { it.absolutePath }) { f ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onSave(f) }
                                        .padding(horizontal = 10.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        val seg = segRe.matchEntire(f.name)
                                        Text(seg?.let { "第 ${it.groupValues[1]} 段" } ?: f.name,
                                            fontSize = 12.sp, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis)
                                        Text(f.name, fontSize = 10.sp, color = Sub,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text("%.1f MB".format(f.length() / 1024f / 1024f),
                                        fontSize = 11.sp, color = Sub)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)) { Text("关闭") }
            }
        }
    }
}
