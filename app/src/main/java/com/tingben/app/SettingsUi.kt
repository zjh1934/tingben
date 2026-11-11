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
    SettingLine("浮", "悬浮进度",
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
            else OverlayProgress.update(c, "", "预览", 0, 0, -1f)
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
    SettingLine("续", "打开软件自动续跑", "回到软件时接着上次没做完的") {
        Switch(checked = on, onCheckedChange = {
            on = it
            Prefs.setAutoResumeOnStart(c, it)
            EventLog.ui("RESUME_SETTING", "auto=$it")
        })
    }
}

/** R171 · 「应用详情」是入口不是权限，原来复用 PermRow 会显示「已开启 · 查看」。 */
@Composable
internal fun PermEntryRow(title: String, hint: String, go: () -> Unit) {
    SettingLine(title.take(1), title, hint) {
        Button(onClick = go,
            modifier = Modifier.height(34.dp),
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(horizontal = 14.dp)) {
            Text("打开 ›", fontSize = 12.sp)
        }
    }
}

/**
 * R238 · 设置页统一行：借鉴 Material 3 Preference 的「前导图标 / 主标题 /
 * 副标题 / 尾部控件」层级，用项目调色板自绘，不引图标库、不加依赖。
 * 以前每行都手搓一遍 Column(weight) + Text + Spacer(2.dp) + 小字，
 * 行高与左右对齐全靠运气；现在所有行等高、副标题可省、危险项单独色。
 */
@Composable
internal fun SettingLine(
    mark: String,
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
            Text(mark, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = if (danger) Warn else Primary)
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

@Composable
internal fun SettingsSection(
    title: String, badge: String, open: Boolean = true, onToggle: (Boolean) -> Unit = {},
    collapsible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    // H3 · 用户实证「设置页排版很难看，乍一看不像人用的」：
    // 原来是「一条分隔线 + 一行裸标题 + 贴在一起的行」，没有边界感。
    // 改成一块一块的卡片，小徽标标归属，标题与内容拉开距离。
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = palette.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth()
                    .let { if (collapsible) it.clickable { onToggle(!open) } else it },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.background(PrimarySoft, RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)) {
                    Text(badge, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = Primary)
                }
                Spacer(Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.weight(1f))
                if (collapsible) {
                    Text(if (open) "收起 ⌃" else "展开 ›", fontSize = 12.sp, color = Sub)
                }
            }
            if (!collapsible || open) {
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
    }
}

/** R158 · 设置页权限行。必须是顶层组合函数，局部函数 Compose 编译器不接受。
 *  R165 · 用户实证「权限如果已经有了，应该是变绿，不是还跟原来一样去开启」：
 *  已开启换成绿底，未开启维持主色；绿底前景按主题取，保证深浅都读得清。 */
@Composable
internal fun PermRow(title: String, granted: Boolean, hint: String, go: () -> Unit) {
    val goodFg = if (appThemeDark) Color(0xFF0E1117) else Color(0xFFFFFFFF)
    SettingLine(title.take(1), title, hint) {
        Button(onClick = go,
            modifier = Modifier.height(34.dp),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (granted) Good else Primary,
                contentColor = if (granted) goodFg else palette.onPrimary),
            contentPadding = PaddingValues(horizontal = 14.dp)) {
            Text(if (granted) "已开启 · 查看" else "去开启", fontSize = 12.sp)
        }
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun SettingsScreen(c: Context, refresh: Int, onModels: () -> Unit,
                           onLibrary: () -> Unit,
                           onMixer: () -> Unit) {
    val missing = remember(refresh) { ModelStore.missing(c) }
    val modeCount = remember(refresh) { ModelStore.installedModes(c).size }
    var themeMode by remember { mutableStateOf(Prefs.themeMode(c)) }
    // R171 · 开发者工具默认收起，普通用户打开设置不再先看到两枚排查按钮。
    var devOpen by remember { mutableStateOf(false) }

    // R156 · 成品导出：让用户用系统文件管理器自己选一个文件夹（一般是 Download），
    // 记住后把每章成品 WAV 平铺写进去。不开放内部 work/audio 路径配置，
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
    LaunchedEffect(refresh) {
        expCount = withContext(Dispatchers.IO) { AudioExport.collect(c).size }
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
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (missing.isEmpty()) PrimarySoft else Warn.copy(alpha = 0.12f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                Text(if (missing.isEmpty()) "$modeCount 个模式可用"
                     else "还差：" + missing.joinToString("、"),
                    fontSize = 12.sp, color = Sub)
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
            SettingLine("主", "主题", "外观基调") { }
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
        }

        // R171 · 合并「启动行为」与「通知与权限」：原来一个开关独占一张卡，
        // 另一张卡只有一行，都是把设置页拉得又长又碎的根因。
        SettingsSection("运行与通知", "系统", collapsible = false) {
            AutoResumeRow(c)
            // R165 · 原来写死「去开启」，权限已经给了还是这句。按真实状态复检，
            // 回前台（permTick）就刷新；已给换绿底，和下面两行权限同一口径。
            val notifOk = remember(permTick) { NotifPerm.granted(c) }
            val goodFg = if (appThemeDark) Color(0xFF0E1117) else Color(0xFFFFFFFF)
            SettingLine("铃", "通知权限",
                if (notifOk) "已允许，制作与播放进度会显示在通知栏" else "还没允许，点右边去开启") {
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
                    Text(if (notifOk) "已开启 · 查看" else "去开启", fontSize = 12.sp)
                }
            }
            // R174 · 自审发现：设置页原来有两张都标「系统」的卡（运行与通知 /
            // 后台运行与权限），同类拆两处正是「设置页不合理」的残留。
            // 现在系统类归到这一张，少一段空白、少一个重复分组。
            // R158b · 用户明确「教程不要塞进应用里」：长说明、图文步骤、
            // 箭头流程全部撤掉，只留按钮 + 状态。说明放沙盒方案文档，不进 APK。
            PermRow("忽略电池优化", batOk, "后台限制") {
                if (!KeepAlive.requestIgnoreBattery(c))
                    Toast.makeText(c, "打不开设置页，请手动到应用详情里找",
                        Toast.LENGTH_SHORT).show()
            }
            PermRow("显示在其他应用上层", ovOk, "可选") {
                if (!KeepAlive.requestOverlay(c))
                    Toast.makeText(c, "打不开设置页，请手动到应用详情里找",
                        Toast.LENGTH_SHORT).show()
            }
            PermEntryRow("应用详情", "系统设置") {
                if (!KeepAlive.openAppDetails(c))
                    Toast.makeText(c, "这台机器打不开应用详情", Toast.LENGTH_SHORT).show()
            }
        }
        SettingsSection("开发者工具", "排查", open = devOpen,
                        onToggle = { devOpen = it }) {
            // R142 · 日志定位：给开发者排查用，不是给使用者看的。
            Text("开发日志", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text("日志可能含书名和正文片段", fontSize = 11.sp, color = Sub)
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
        // R238 · 「本机数据」只放一行、「成品文件」又一张卡，两张都标「本机」，
        // 正是 R174 修过的同类碎片。合并成一张，行全部走 SettingLine 统一层级。
        SettingsSection("文件与数据", "本机", collapsible = false) {
            ClearSegmentsRow(c)
            Spacer(Modifier.height(2.dp))
            SettingLine("导", "导出位置", expTreeLabel) {
                Button(onClick = {
                        try { treeLauncher.launch(AudioExport.treeUri(c)) }
                        catch (_: Throwable) {
                            Toast.makeText(c, "这台机器打不开文件夹选择", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.height(34.dp),
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Text("选择", fontSize = 12.sp)
                }
            }
            SettingLine("成", "已做好 $expCount 个整章成品", "平铺导出到你选的文件夹") {
                Button(enabled = expCount > 0, onClick = {
                        val tree = AudioExport.treeUri(c)
                        if (tree == null) {
                            Toast.makeText(c, "先点「选择」挑一个文件夹",
                                Toast.LENGTH_SHORT).show()
                        } else {
                            expScope.launch {
                                val r = withContext(Dispatchers.IO) {
                                    AudioExport.exportAll(c, tree)
                                }
                                val msg = when {
                                    r.ok == 0 && r.reason.isNotEmpty() -> r.reason
                                    r.ok == 0 -> "还没有可导出的成品"
                                    r.failed > 0 -> "导出 ${r.ok} 个，失败 ${r.failed} 个" +
                                            if (r.reason.isNotEmpty()) "（${r.reason}）" else ""
                                    r.skipped > 0 -> "已导出 ${r.ok} 个，跳过 ${r.skipped} 个失效文件"
                                    else -> "已导出 ${r.ok} 个成品"
                                }
                                Toast.makeText(c, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier.height(34.dp),
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Text("导出全部", fontSize = 12.sp)
                }
            }
        }
    }
}

