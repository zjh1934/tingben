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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.abs

// @ui-part: MixerUi
@Composable
internal fun MixerDialog(c: Context, onClose: () -> Unit) {
    // R155 · 用户实证两处：弹层底部会盖住系统返回/桌面手势区；点空白处返回不了。
    // 原来 onDismissRequest 是空实现，只有顶栏一枚灰叉能退出。
    // 现在让 MixerScreen 把「有改动先确认、没改动直接关」的 leave 注册上来，
    // 空白点按、返回键、关闭叉走同一个出口，不会绕过未保存确认。
    var requestLeave by remember { mutableStateOf<(() -> Unit)?>(null) }
    Dialog(
        onDismissRequest = { requestLeave?.invoke() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // statusBars + navigationBars 都避让：全屏 Dialog 默认会伸到手势条下面，
        // 底部「保存 / 试听」被系统导航区压住，看起来就像「没有出口」。
        // R214 · 上下略收：不再全屏撑满，底部留出空间，不压到内容与系统导航。
        // statusBars / navigationBars 避让照旧保留（R155）。
        Surface(Modifier.fillMaxWidth()
            .fillMaxHeight(0.86f)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(12.dp),
            shape = RoundedCornerShape(16.dp), color = Bg) {
            MixerScreen(c, onBack = onClose, onClose = onClose,
                onRegisterLeave = { requestLeave = it })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MixerScreen(c: Context, onBack: () -> Unit,
                        onClose: (() -> Unit)? = null,
                        onRegisterLeave: ((() -> Unit) -> Unit)? = null) {
    var tick by remember { mutableStateOf(0) }
    var active by remember { mutableStateOf(MixerDraft.active(c)) }
    var draft by remember { mutableStateOf(active) }
    val changes = MixerCommit.diff(active, draft)
    val dirty = changes.isNotEmpty()
    val scope = rememberCoroutineScope()

    val mode = remember(tick) { ModelStore.currentMode(c) }
    val installed = remember(tick) { ModelStore.installedModes(c).map { it.id }.toSet() }
    var missing by remember { mutableStateOf(emptyList<String>()) }
    var rows by remember { mutableStateOf(emptyList<MixerRow>()) }
    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) {
            val m = ModelStore.missing(c)
            val r = Mixer.snapshot(c)
            withContext(Dispatchers.Main) { missing = m; rows = r }
        }
    }

    var showLeave by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    var trialText by remember { mutableStateOf(Prefs.trialText(c)) }
    var savedTrialText by remember { mutableStateOf(Prefs.trialText(c)) }
    var busyTrial by remember { mutableStateOf(false) }
    var trialMsg by remember { mutableStateOf("") }

    var refState by remember(tick) { mutableStateOf(VoiceRef.status(c)) }
    // R236 · 选完文件先拿全段波形，弹裁剪框拖选，确认后才落盘
    var refClip by remember { mutableStateOf<DecodedAudio?>(null) }
    var speakerList by remember(tick) { mutableStateOf(Prefs.speakerNames(c)) }
    var speakerBusy by remember { mutableStateOf(false) }
    val pickRef = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            // R234/R236 · mp3 / m4a / aac / ogg 经平台内置解码成 24k 单声道 WAV，
            // 同时拿到波形峰值；不直接落盘，先让用户拖选一小段。
            val r = runCatching { AudioDecode.decode(c, uri) }
            withContext(Dispatchers.Main) {
                r.onSuccess { refClip = it }
                r.onFailure {
                    Toast.makeText(c, it.message ?: "这段音频读不了",
                        Toast.LENGTH_LONG).show()
                }
                tick++
            }
        }
    }
    // R237 · 试听选中段：裁出临时 wav 走 PlayQueue 的 preview 通道（"trial"），
    // 不占整本书的播放控制通知，也不进 VoiceRef 落盘，拖着把手可以反复听。
    fun trialRefClip(d: DecodedAudio, startMs: Long, endMs: Long) {
        scope.launch(Dispatchers.IO) {
            val r = runCatching {
                val bytes = AudioDecode.clipWav(d.wav, startMs, endMs)
                val f = File(c.cacheDir, "ref_clip_trial.wav")
                f.writeBytes(bytes)
                f
            }
            withContext(Dispatchers.Main) {
                r.onSuccess { f ->
                    PlayQueue.setTracks(listOf(Track(f, "裁剪试听",
                        id = "trial:ref_clip")), "trial")
                    PlayQueue.playFrom(0, id = "trial")
                }
                r.onFailure {
                    Toast.makeText(c, it.message ?: "这段试听不了",
                        Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    // R236 · 裁剪确认：纯字节裁一段，再走 VoiceRef.save 的原校验与原子落盘
    fun applyRefClip(d: DecodedAudio, startMs: Long, endMs: Long) {
        scope.launch(Dispatchers.IO) {
            val r = runCatching {
                VoiceRef.save(c, AudioDecode.clipWav(d.wav, startMs, endMs))
            }
            withContext(Dispatchers.Main) {
                refState = VoiceRef.status(c)
                refClip = null
                tick++
                Toast.makeText(c, r.exceptionOrNull()?.message ?: "参考音频已导入",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    fun commitAll() {
        val n = changes.size
        MixerCommit.commit(c, draft, n)
        active = MixerDraft.active(c)
        draft = active
        tick++
    }
    fun discardAll() { draft = active }

    fun switchMode(m: ModelStore.Mode) {
        if (Producer.isBusy()) {
            Toast.makeText(c, "正在制作，做完再切模式", Toast.LENGTH_SHORT).show()
            return
        }
        if (m == mode) return
        Prefs.setModelMode(c, m.id)
        tick++
    }

    fun play(path: String) {
        val f = File(path)
        if (!f.isFile) { Toast.makeText(c, "播放失败", Toast.LENGTH_SHORT).show(); return }
        PlayQueue.setTracks(listOf(Track(f, "调音台试听", id = "trial:${f.absolutePath}")), "trial")
        PlayQueue.playFrom(0, id = "trial")
    }

    var trialJob by remember { mutableStateOf<Job?>(null) }
    var trialToken by remember { mutableStateOf(0L) }

    DisposableEffect(Unit) { onDispose {
        trialJob?.cancel()
        if (busyTrial) { try { NativeEngine.nativeCancel() } catch (_: Throwable) { } }
    } }

    fun stopTrial() {
        trialJob?.cancel()
        trialJob = null
        trialToken++
        try { NativeEngine.nativeCancel() } catch (_: Throwable) { }
        busyTrial = false
        trialMsg = "已停止"
    }

    fun trial(label: String, src: MixerDraft, text: String, out: String) {
        if (Producer.isBusy()) {
            Toast.makeText(c, "正在制作，做完再试听", Toast.LENGTH_SHORT).show()
            return
        }
        if (busyTrial) return
        try { NativeEngine.nativeCancelReset() } catch (_: Throwable) { }
        trialJob?.cancel()
        val myToken = trialToken + 1
        trialToken = myToken
        busyTrial = true
        trialMsg = "合成中…"
        val job = scope.launch(Dispatchers.IO) {
            // R215 · 合成主体下沉到 ClipSynth，试听与首页即贴即听共用同一份
            // model / mode 分支 / 失败判定；这里只管状态、播放与 UI 文案。
            val res = runCatching {
                ClipSynth.synthesize(c, text, out, label) {
                    myToken != trialToken || !isActive
                }
            }
            withContext(Dispatchers.Main) {
                if (myToken != trialToken || !isActive) return@withContext
                busyTrial = false
                val p = res.getOrNull()
                if (!p.isNullOrBlank()) play(p)
                trialMsg = if (p != null) "已播放"
                           else "试听失败：" + (res.exceptionOrNull()?.message ?: "未知原因")
                // R165 · 原来记成 CRASH 级，日志里就冒出一条「CRASH | MIXER_TRIAL_FAIL」，
                // 把「最近一次崩溃」和崩溃前环形缓冲都污染了，其实软件根本没崩。
                res.exceptionOrNull()?.let {
                    EventLog.log(EventLog.Kind.ENGINE, "MIXER_TRIAL_FAIL",
                        "mode=${mode.id} · ${it.message ?: "未知原因"}")
                }
            }
        }
        trialJob = job
    }

    val leave = { if (dirty) showLeave = true else (onClose ?: onBack)() }
    // R155 · 把同一个 leave 交给外层 Dialog 的 onDismissRequest：
    // 点遮罩空白处也能退出，且「有改动」时照样先弹未保存确认。
    if (onRegisterLeave != null) LaunchedEffect(dirty) { onRegisterLeave(leave) }
    BackHandler(enabled = dirty) { showLeave = true }
    // 弹层形态下，没有改动时返回键直接关窗口；有改动时上面的 BackHandler 先弹确认。
    // 两者互斥，不会和外层全局返回打架（后注册优先）。
    if (onClose != null) BackHandler(enabled = !dirty) { onClose?.invoke() }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            // R148 · 原来返回箭头永远显示，弹层又叠一枚关闭叉，用户看到「两个返回键」。
            // 弹层只留关闭叉，独立页只留返回箭头，一个界面一个出口。
            if (onClose == null) {
                Box(Modifier.size(40.dp).clickable { leave() },
                    contentAlignment = Alignment.Center) { IconBack(Primary) }
            } else {
                Spacer(Modifier.width(4.dp))
            }
            Text("调音台", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = palette.text)
            Spacer(Modifier.weight(1f))
            if (dirty) {
                Button(onClick = { commitAll() },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
                    Text("保存", fontSize = 12.sp)
                }
            }
            // 弹层才有关闭叉；独立页走顶栏返回键即可，不重复给两个出口。
            // R155 · 原来是一枚灰色 IconClose，用户说「没有明确的返回按键」：
            // 换成带浅底的主色叉，形态和可点性一致，一眼看出这是出口。
            if (onClose != null) {
                Box(Modifier.size(40.dp)
                        .background(PrimarySoft, RoundedCornerShape(12.dp))
                        .clickable { leave() },
                    contentAlignment = Alignment.Center) {
                    IconClose(Primary)
                }
            }
        }
        HorizontalDivider(color = Line)

        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))

            ModeTabs(mode, installed) { switchMode(it) }
            if (mode.id !in installed) {
                Text("「${mode.label}」未导入", fontSize = 12.sp, color = Warn)
                Spacer(Modifier.height(8.dp))
            }

            when (mode) {
                ModelStore.Mode.VOICE_DESIGN -> VoiceDesignPane(draft) { draft = it }
                ModelStore.Mode.CUSTOM_VOICE -> CustomVoicePane(
                    draft = draft, onDraft = { draft = it },
                    speakerList = speakerList, speakerBusy = speakerBusy,
                    onBusy = { speakerBusy = it },
                    onSpeakers = { names ->
                        Prefs.setSpeakerNames(c, names)
                        speakerList = names
                        tick++
                    }
                )
                ModelStore.Mode.BASE -> BasePane(
                    draft = draft, onDraft = { draft = it },
                    refState = refState,
                    onPick = { pickRef.launch(arrayOf("audio/*", "*/*")) },
                    onClear = {
                        VoiceRef.clear(c); refState = VoiceRef.status(c); tick++
                    }
                )
            }


            // I1 · 用户实证「试听文本和试听两个按钮在采样参数上面」。
            // 顺序定成：选模式 → 描述想要的声音 → 马上试听 → 再细调九项参数，
            // 不用来回滚一长串滑块找试听按钮。
            OutlinedTextField(
                value = trialText, onValueChange = { trialText = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("试听文本", fontSize = 12.sp) },
                textStyle = TextStyle(fontSize = 12.sp)
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    if (trialText.isBlank()) {
                        Toast.makeText(c, "先放点文字进来", Toast.LENGTH_SHORT).show()
                    } else {
                        Prefs.setTrialText(c, trialText)
                        savedTrialText = trialText
                        Toast.makeText(c, "试听文本已保存", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("保存试听文本", fontSize = 12.sp) }
                Spacer(Modifier.weight(1f))
                if (busyTrial) {
                    TextButton(onClick = { stopTrial() }) {
                        Text("停止", fontSize = 12.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = missing.isEmpty() && !busyTrial,
                    onClick = { trial("已保存", active, savedTrialText, "mixer_trial_saved.wav") },
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary, contentColor = palette.onPrimary)
                ) { Text("试听 · 已保存", fontSize = 12.sp) }
                Button(
                    enabled = missing.isEmpty() && !busyTrial,
                    onClick = { trial("未保存", draft, trialText, "mixer_trial_draft.wav") },
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimarySoft, contentColor = Primary)
                ) { Text("试听 · 当前", fontSize = 12.sp) }
            }
            if (trialMsg.isNotBlank()) {
                Text(trialMsg, fontSize = 12.sp,
                    color = if (trialMsg.startsWith("试听失败")) Warn else Sub)
            }
            if (missing.isNotEmpty()) {
                Text("还缺模型文件，去「模型」页导入。", fontSize = 12.sp, color = Warn)
            }
            Spacer(Modifier.height(14.dp))
            if (mode == ModelStore.Mode.VOICE_DESIGN) {
                Text("采样参数 · 9 项", fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, color = palette.text)
                Spacer(Modifier.height(6.dp))
                ParamCatalog.SAMPLING.forEach { spec ->
                    ParamLine(spec = spec, cur = spec.read(draft.knobs)) { s ->
                        spec.withValue(draft.knobs, s)?.let { draft = draft.copy(knobs = it) }
                    }
                }
                TextButton(onClick = { draft = draft.copy(knobs = TtsKnobs()) }) {
                    Text("全部恢复默认", fontSize = 12.sp, color = Sub)
                }
            } else {
                Text("沿用全局设置", fontSize = 12.sp, color = Sub)
            }

            Spacer(Modifier.height(14.dp))

            Spacer(Modifier.height(24.dp))
        }
    }

    // R236 · 波形拖拽裁剪：借鉴 Ringdroid，两端把手拖选，默认给前 10 秒
    refClip?.let { d ->
        RefClipDialog(
            data = d,
            onCancel = { refClip = null },
            onTrial = { s, e -> trialRefClip(d, s, e) },
            onUse = { s, e -> applyRefClip(d, s, e) }
        )
    }

    if (showLeave) {
        AlertDialog(
            onDismissRequest = { showLeave = false },
            title = { Text("改动还没保存", fontSize = 14.sp) },
            text = { Text("离开会丢掉这次改动。", fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    showLeave = false; discardAll(); (onClose ?: onBack)()
                }) {
                    Text("丢弃并离开", fontSize = 12.sp, color = Warn)
                }
            },
            dismissButton = { TextButton(onClick = { showLeave = false }) { Text("继续编辑") } }
        )
    }
}

@Composable
internal fun ModeTabs(
    current: ModelStore.Mode,
    installed: Set<String>,
    onPick: (ModelStore.Mode) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModelStore.Mode.values().forEach { m ->
            val on = m == current
            Column(
                Modifier.weight(1f).height(92.dp)
                    .background(if (on) PrimarySoft else Card, RoundedCornerShape(10.dp))
                    .border(1.dp, if (on) Primary else Line, RoundedCornerShape(10.dp))
                    .clickable { onPick(m) }
                    .padding(horizontal = 9.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(m.label, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = if (on) Primary else palette.text)
                Text(
                    when (m) {
                        ModelStore.Mode.VOICE_DESIGN -> "写一段话描述嗓子"
                        ModelStore.Mode.CUSTOM_VOICE -> "模型自带音色里挑"
                        ModelStore.Mode.BASE -> "一段人声当样本"
                    },
                    fontSize = 12.sp, color = Sub, maxLines = 2
                )
                if (m.id !in installed) Text("未导入主文件", fontSize = 12.sp, color = Warn)
            }
        }
    }
}

@Composable
internal fun VoiceDesignPane(draft: MixerDraft, onDraft: (MixerDraft) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("声音描述", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.text)
        Spacer(Modifier.weight(1f))
        Text("清空", fontSize = 12.sp, color = Sub,
            modifier = Modifier.clickable { onDraft(draft.copy(voice = "")) })
    }
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = draft.voice, onValueChange = { onDraft(draft.copy(voice = it)) },
        modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 8,
        placeholder = { Text("描述想要的音色", fontSize = 12.sp) },
        textStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    )
    // R223 · 默认已是青年女声；留空可回到升级前的中性声并认领回旧音频
    Spacer(Modifier.height(4.dp))
    Text("留空 = 旧默认中性声，可切回旧音频",
        fontSize = 11.sp, color = Sub)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CustomVoicePane(
    draft: MixerDraft,
    onDraft: (MixerDraft) -> Unit,
    speakerList: List<String>,
    speakerBusy: Boolean,
    onBusy: (Boolean) -> Unit,
    onSpeakers: (List<String>) -> Unit
) {
    Text("选一个音色", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.text)
    Spacer(Modifier.height(6.dp))
    if (speakerList.isEmpty()) {
        val scope = rememberCoroutineScope()
        val ctx = androidx.compose.ui.platform.LocalContext.current
        Button(onClick = {
            onBusy(true)
            scope.launch(Dispatchers.IO) {
                val r = runCatching {
                    val tk = ModelStore.modeFile(ctx, ModelStore.Mode.CUSTOM_VOICE)
                        ?: throw IllegalStateException("还没导入「预设音色」那份主文件")
                    val tok = ModelStore.tokenizer(ctx)
                        ?: throw IllegalStateException("还没导入共用底座")
                    val h = NativeEngine.ttsLoadGated(tk.absolutePath, tok.absolutePath, null)
                    if (h == 0L) throw IllegalStateException("模型打不开")
                    try { NativeEngine.ttsSpeakers(h) } finally { NativeEngine.ttsFreeGated(h) }
                }
                withContext(Dispatchers.Main) {
                    onBusy(false)
                    val names = r.getOrNull()?.split(',')?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }?.distinct() ?: emptyList()
                    if (names.isNotEmpty()) onSpeakers(names)
                    Toast.makeText(ctx, r.exceptionOrNull()?.message
                        ?: if (names.isEmpty()) "这份模型没有读到音色表" else "读到 ${names.size} 个音色",
                        Toast.LENGTH_LONG).show()
                }
            }
        }, enabled = !speakerBusy) {
            Text(if (speakerBusy) "正在读模型…" else "读取模型自带的音色表 ›", fontSize = 12.sp)
        }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            speakerList.forEach { name ->
                FilterChip(
                    selected = draft.speaker == name,
                    onClick = { onDraft(draft.copy(speaker = name)) },
                    label = { Text(name, fontSize = 12.sp) }
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text("自定义", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.text)
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = draft.cvStyle, onValueChange = { onDraft(draft.copy(cvStyle = it)) },
        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
        placeholder = { Text("风格补充（可留空）", fontSize = 12.sp) },
        textStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    )
}

@Composable
internal fun BasePane(
    draft: MixerDraft,
    onDraft: (MixerDraft) -> Unit,
    refState: String,
    onPick: () -> Unit,
    onClear: () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Text("参考音频", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.text)
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(refState, fontSize = 12.sp,
            color = if (VoiceRef.exists(ctx)) Good else Warn,
            modifier = Modifier.weight(1f))
        if (VoiceRef.exists(ctx)) {
            Text("删掉", fontSize = 12.sp, color = Warn,
                modifier = Modifier.clickable { onClear() }.padding(start = 8.dp))
        }
    }
    Spacer(Modifier.height(6.dp))
    TextButton(onClick = onPick) { Text("导入并裁剪 ›", fontSize = 12.sp) }
    Spacer(Modifier.height(12.dp))
    Text("这段录音念的是什么（可留空）", fontSize = 12.sp,
        fontWeight = FontWeight.Bold, color = palette.text)
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = draft.refText, onValueChange = { onDraft(draft.copy(refText = it)) },
        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
        placeholder = { Text("（可留空）", fontSize = 12.sp) },
        textStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    )
}

@Composable
internal fun ParamLine(spec: ParamSpec, cur: String, onValue: (String) -> Unit) {
    var edit by remember { mutableStateOf(false) }
    var buf by remember(cur) { mutableStateOf(cur) }
    val lo = (spec.min ?: 0.0).toFloat()
    val hi = (spec.max ?: if (spec.key == "seed") 2000.0 else 1000.0).toFloat()
    val sv = (cur.toFloatOrNull() ?: lo).coerceIn(lo, hi)
    val intLike = spec.key == "seed" || spec.key == "maxNewTokens" ||
        spec.key == "topK" || spec.key == "subTopK"
    val step = if (spec.key == "maxNewTokens") 64f else 1f

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(spec.label, fontSize = 12.sp, color = palette.text,
                modifier = Modifier.weight(1f))
            Text(cur, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Primary,
                modifier = Modifier
                    .background(PrimarySoft, RoundedCornerShape(6.dp))
                    .clickable { buf = cur; edit = true }
                    .padding(horizontal = 10.dp, vertical = 4.dp))
            Spacer(Modifier.width(8.dp))
            // I1 · 用户实证「两个刷新键太小了根本点不到」。
            // 原来只是 12sp 字符 + 4dp padding，热区约 20dp；扩成 40dp 方块。
            // J1 · 看图又发现：光扩热区仍看不出这里有个按钮（草图里像个空 checkbox）。
            // 补上浅色圆底 + 主色字符，形态和可点性一致。
            Box(Modifier.size(40.dp)
                    .background(PrimarySoft, RoundedCornerShape(10.dp))
                    .clickable { onValue(spec.def) },
                contentAlignment = Alignment.Center) {
                Text("↺", fontSize = 17.sp, color = Primary)
            }
        }
        // R158b · 种子语义不进 UI（那是教程）。结论留在沙盒工作卡，界面只给滑块。
        Slider(
            value = sv,
            onValueChange = { v ->
                val q = if (intLike) kotlin.math.round(v / step) * step else v
                val s = if (intLike) q.toLong().toString()
                        else String.format(java.util.Locale.US, "%.2f", q)
                            .trimEnd('0').trimEnd('.')
                onValue(s)
            },
            valueRange = lo..hi,
            modifier = Modifier.padding(start = 20.dp, end = 10.dp)
                .fillMaxWidth().height(18.dp)
        )
    }

    if (edit) {
        val err = spec.validate(buf)
        AlertDialog(
            onDismissRequest = { edit = false },
            title = { Text(spec.label, fontSize = 14.sp) },
            text = {
                Column {
                    OutlinedTextField(
                        value = buf, onValueChange = { buf = it }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = TextStyle(fontSize = 14.sp),
                        isError = err != null
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("范围 ${spec.rangeLabel} · 默认 ${spec.def}", fontSize = 12.sp, color = Sub)
                    if (err != null) Text(err, fontSize = 12.sp, color = Warn)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (spec.validate(buf) == null && spec.withValue(TtsKnobs(), buf) != null) {
                        onValue(buf.trim()); edit = false
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { edit = false }) { Text("取消") } }
        )
    }
}

/**
 * R159 · 续跑弹窗重设计。
 *
 * 用户实证：「这个弹窗做的很丑，要重新设计」。原来是系统 AlertDialog 默认外形 +
 * 一句拼起来的长解释 + 主按钮和两个文字按钮挤在一行，权重全乱，还没有进度可视。
 * 现在：圆角卡 + 代码绘制图标 + 状态胶囊 + 真实进度条 + 整宽主按钮，
 * 三态（可续跑 / 继续队列 / 需手动）各自给对的出口。
 *
 * R158b 硬口径：不写教程，不写「接着跑不会重算…」这类解释句；
 * 状态用一枚胶囊说清，进度用一根条说清。
 */


/**
 * R236 · 参考音频波形裁剪弹层。
 *
 * 交互借鉴 Ringdroid（AOSP 开源铃声编辑器）：波形铺开、按住两端把手拖出选区、
 * 选好才导出。这里用 Compose 自绘 Canvas，不引波形库 / FFmpeg；
 * d.peaks 是 R234 解码时按 20ms 窗算好的峰值，直接画，不再读盘。
 * 默认选前 10 秒：参考音频公开口径 3~10 秒最稳，导入整首歌也不会一进来就超长。
 */
@Composable
internal fun RefClipDialog(
    data: DecodedAudio,
    onCancel: () -> Unit,
    onTrial: (Long, Long) -> Unit,
    onUse: (Long, Long) -> Unit
) {
    val totalMs = data.durationMs.coerceAtLeast(1L)
    var startFrac by remember(data) { mutableStateOf(0f) }
    var endFrac by remember(data) {
        mutableStateOf(minOf(1f, 10_000f / totalMs))
    }
    var dragging by remember(data) { mutableStateOf(0) } // 1=左把手 2=右把手
    val selectedMs = (totalMs * (endFrac - startFrac)).toLong()

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("裁一段人声", fontSize = 15.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("按住波形两端拖，选一小段干净人声", fontSize = 12.sp,
                    color = palette.text)
                Spacer(Modifier.height(8.dp))
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(112.dp)
                        .background(Bg)
                        .pointerInput(data.peaks.size) {
                            detectDragGestures(
                                onDragStart = { pos ->
                                    val f = (pos.x / size.width.toFloat())
                                        .coerceIn(0f, 1f)
                                    dragging = if (abs(f - startFrac) <= abs(f - endFrac)) 1 else 2
                                },
                                onDrag = { change, _ ->
                                    val f = (change.position.x / size.width.toFloat())
                                        .coerceIn(0f, 1f)
                                    if (dragging == 1)
                                        startFrac = minOf(f, endFrac - 0.02f).coerceAtLeast(0f)
                                    else
                                        endFrac = maxOf(f, startFrac + 0.02f).coerceAtMost(1f)
                                    change.consume()
                                }
                            )
                        }
                ) {
                    val w = size.width
                    val h = size.height
                    val n = data.peaks.size.coerceAtLeast(1)
                    val barW = (w / n).coerceAtLeast(1f).coerceAtMost(6f)
                    for (i in 0 until n) {
                        val x = i * w / n
                        val f = i.toFloat() / n
                        val col = if (f in startFrac..endFrac) Primary
                                  else Primary.copy(alpha = 0.22f)
                        val ph = (data.peaks[i].coerceIn(0f, 1f) * h * 0.44f)
                            .coerceAtLeast(2f)
                        drawLine(col, Offset(x, h / 2 - ph), Offset(x, h / 2 + ph),
                            strokeWidth = barW)
                    }
                    drawLine(Warn, Offset(startFrac * w, 0f), Offset(startFrac * w, h),
                        strokeWidth = 3f)
                    drawLine(Warn, Offset(endFrac * w, 0f), Offset(endFrac * w, h),
                        strokeWidth = 3f)
                }
                Spacer(Modifier.height(8.dp))
                Text("已选 %.1f 秒 / 共 %.1f 秒".format(
                    selectedMs / 1000f, totalMs / 1000f), fontSize = 12.sp,
                    color = palette.text)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onUse((totalMs * startFrac).toLong(), (totalMs * endFrac).toLong())
            }) { Text("用这一段", fontSize = 13.sp, color = Primary) }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    onTrial((totalMs * startFrac).toLong(),
                        (totalMs * endFrac).toLong())
                }) { Text("试听选中", fontSize = 13.sp, color = Primary) }
                TextButton(onClick = onCancel) { Text("取消", fontSize = 13.sp) }
            }
        }
    )
}
