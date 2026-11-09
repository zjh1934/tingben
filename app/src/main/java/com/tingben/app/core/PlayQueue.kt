package com.tingben.app.core

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.lang.ref.WeakReference

data class Track(val file: File, val label: String, val startMs: Long = 0L,
                 val id: String = "")

data class PlaybackState(
    val index: Int = -1,
    val label: String = "",
    val trackId: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val ended: Boolean = false,
    val queueId: String = "",
    // true = 制作中的小段 / 调音台试听，只在原格子就地播，
    // 不占底部常驻条、不进播放器页、不进通知栏（R133 两态隔离）。
    val preview: Boolean = false
)

object PlayQueue {
    private var player: MediaPlayer? = null
    private var tracks: List<Track> = emptyList()
    private var index = -1
    private var speed = 1f

    /**
     * K1 · 倍速档位的唯一真值源，常驻条循环切档与播放器页五档直选共用，
     * 避免两处各写一份字面量、以后漂移。
     */
    val SPEED_STEPS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    private val SPEED_LABELS = listOf("0.75×", "1.0×", "1.25×", "1.5×", "2.0×")

    fun speedLabel(f: Float): String {
        val i = SPEED_STEPS.indexOfFirst { kotlin.math.abs(it - f) < 0.01f }
        return if (i >= 0) SPEED_LABELS[i]
               else String.format(java.util.Locale.US, "%.2f×", f)
    }

    /** 常驻条点一下切到下一档，到头回到 0.75×。 */
    fun nextSpeed(cur: Float): Float {
        val i = SPEED_STEPS.indexOfFirst { kotlin.math.abs(it - cur) < 0.01f }
        return SPEED_STEPS[(i + 1) % SPEED_STEPS.size]
    }
    private var queueId = ""
    private var appCtx: Context? = null
    // applicationContext 里没有 Activity，光靠它弹不出权限框；
    // attach 时留一个弱引用（不泄漏 Activity，旋转后旧引用自然失效）。
    private var activityRef: WeakReference<Activity>? = null
    private var focusRequest: AudioFocusRequest? = null
    private var noisyReceiver: BroadcastReceiver? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> get() = _state

    // 只按 queueId 判定，调用点一个都不用改：seg / trial 是临时试听态。
    private fun isPreview(id: String): Boolean = id == "seg" || id == "trial"

    // ---- R140 · 批次 D：独立试听通道 ----
    // 主播放仍用上面那一套 player/tracks/index/_state，脚本与 UI 全部不变。
    // 小段 / 调音台试听另起一套 MediaPlayer，绝不碰主队列与播放控制通知。
    private var previewPlayer: MediaPlayer? = null
    private var previewTracks: List<Track> = emptyList()
    private var previewIndex = -1
    private var previewQueueId = ""
    private val _previewState = MutableStateFlow(PlaybackState())
    val previewState: StateFlow<PlaybackState> get() = _previewState

    private fun previewReleasePlayer() {
        try { previewPlayer?.release() } catch (_: Throwable) { }
        previewPlayer = null
    }

    private fun previewSetTracks(list: List<Track>, id: String) {
        previewReleasePlayer()
        previewTracks = list
        previewQueueId = id
        _previewState.value = PlaybackState(speed = 1f, queueId = id, preview = true)
        // 关键：这里绝不去清播放通知，否则点一下格子就把整本书的通知栏控制清掉了
    }

    private fun previewFinish() {
        _previewState.value = PlaybackState(speed = 1f, ended = true,
            queueId = previewQueueId, preview = true)
    }

    private fun previewOpen(i: Int) {
        previewReleasePlayer()
        previewIndex = i
        val t = previewTracks[i]
        if (!t.file.isFile) {
            UiErrors.playback("这段音频文件不在了，已跳过", null)
            if (i + 1 < previewTracks.size) { previewOpen(i + 1); return }
            previewFinish()
            return
        }
        // 试听是「这一块听起来对不对」：只暂停主播放，不销毁队列、不动通知栏
        if (_state.value.isPlaying) pause()
        _previewState.value = PlaybackState(index = i, label = t.label, trackId = t.id,
            isPlaying = false, speed = 1f, queueId = previewQueueId, preview = true)
        try {
            previewPlayer = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                setOnPreparedListener {
                    // K1 · 试听恒原速，不吃主播放的倍速
                    try { it.playbackParams = PlaybackParams().setSpeed(1f).setPitch(1f) }
                    catch (_: Throwable) { }
                    it.start()
                    _previewState.value = _previewState.value.copy(isPlaying = true, ended = false)
                }
                setOnCompletionListener {
                    it.release()
                    previewPlayer = null
                    if (previewIndex + 1 < previewTracks.size) previewOpen(previewIndex + 1)
                    else previewFinish()
                }
                setOnErrorListener { failed, _, _ ->
                    failed.release()
                    previewPlayer = null
                    UiErrors.playback("这段试听打不开，可能文件坏了", null)
                    if (previewIndex + 1 < previewTracks.size) previewOpen(previewIndex + 1)
                    else previewFinish()
                    true
                }
                setDataSource(t.file.absolutePath)
                prepareAsync()
            }
        } catch (_: Throwable) {
            previewPlayer = null
            UiErrors.playback("音频打开失败，已跳过这一段", null)
            if (i + 1 < previewTracks.size) previewOpen(i + 1)
            else previewFinish()
        }
    }

    fun pausePreview() {
        try { previewPlayer?.pause() } catch (_: Throwable) { }
        _previewState.value = _previewState.value.copy(isPlaying = false)
    }

    fun resumePreview() {
        val p = previewPlayer
        if (p == null) { if (previewIndex >= 0) previewOpen(previewIndex); return }
        try {
            p.start()
            _previewState.value = _previewState.value.copy(isPlaying = true, ended = false)
        } catch (_: Throwable) { previewOpen(previewIndex) }
    }

    fun togglePreview() {
        when {
            previewIndex < 0 -> if (previewTracks.isNotEmpty()) previewOpen(0)
            _previewState.value.isPlaying -> pausePreview()
            else -> resumePreview()
        }
    }

    fun stopPreview() {
        previewReleasePlayer()
        previewTracks = emptyList()
        previewIndex = -1
        previewQueueId = ""
        _previewState.value = PlaybackState(speed = 1f)
    }

    fun isPreviewPlaying(): Boolean = _previewState.value.isPlaying

    private fun notifyNow() {
        val s = _state.value
        // 小段试听不进通知栏：它是「这一块听起来对不对」，不是一次收听会话。
        if (s.preview) { clearNotif(); return }
        appCtx?.let { PlayNotif.show(it, s) }
    }
    private fun clearNotif() { appCtx?.let { PlayNotif.cancel(it) } }

    // R182 · 短暂失焦（来电、导航播报）结束后要自动续播。原来三种 loss 一律
    // pause()，而 pause() 会 abandonFocus() —— 请求都放弃了，系统的 GAIN 再也不会
    // 回调回来，用户挂了电话还得自己手动点播放。听书被 duck 压低也听不清，
    // 统一按「短暂暂停 + 保留焦点」处理，GAIN 时接着念。
    @Volatile private var pausedByTransient = false

    private fun pauseKeepFocus() {
        savePos()
        try { player?.pause() } catch (_: Throwable) { }
        _state.value = _state.value.copy(isPlaying = false)
        notifyNow()
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                val was = _state.value.isPlaying
                pauseKeepFocus()          // 故意不 abandonFocus，才能收到 GAIN
                pausedByTransient = was
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedByTransient = false
                pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByTransient) {
                    pausedByTransient = false
                    resume()
                }
            }
        }
    }

    fun attach(c: Context) {
        if (appCtx != null) return
        val ctx = c.applicationContext
        appCtx = ctx
        var x: Context? = c
        while (x is android.content.ContextWrapper) {
            if (x is Activity) { activityRef = WeakReference(x); break }
            x = x.baseContext
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
            }
        }
        try {
            ContextCompat.registerReceiver(
                ctx, r, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyReceiver = r
        } catch (_: Throwable) { }
    }

    private fun ensureFocus(): Boolean {
        val ctx = appCtx ?: return true
        abandonFocus()
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = req
            am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (_: Throwable) { true }
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        focusRequest = null
        val ctx = appCtx ?: return
        try {
            (ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                ?.abandonAudioFocusRequest(req)
        } catch (_: Throwable) { }
    }

    fun setTracks(list: List<Track>, id: String = "") {
        if (isPreview(id)) { previewSetTracks(list, id); return }
        lastPosPersistMs = 0L
        if (list == tracks && id == queueId) return
        release()
        tracks = list
        queueId = id
        _state.value = PlaybackState(speed = speed, queueId = id,
            preview = isPreview(id))
        clearNotif()
        // R140 · 只播听、不做书的用户也要拿到播放控制通知；
        // 以前只有「开始制作」才申请 POST_NOTIFICATIONS。
        NotifPerm.ensureForPlayback(appCtx, activityRef?.get())
    }

    fun playFrom(i: Int, startMs: Long? = null, id: String? = null) {
        if (id != null && isPreview(id)) {
            if (id != previewQueueId) return
            if (i < 0 || i >= previewTracks.size) return
            previewOpen(i)
            return
        }
        if (id != null && id != queueId) return
        if (tracks.isEmpty() || i < 0 || i >= tracks.size) return
        open(i, startMs)
    }

    fun toggle() {
        when {
            index < 0 -> playFrom(0)
            _state.value.isPlaying -> pause()
            else -> resume()
        }
    }

    fun pause() {
        savePos()
        abandonFocus()
        try { player?.pause() } catch (_: Throwable) { }
        _state.value = _state.value.copy(isPlaying = false)
        notifyNow()
    }

    fun resume() {
        val p = player
        if (p == null) { if (index >= 0) open(index); return }
        if (!ensureFocus()) { pause(); return }
        try {
            p.start()
            _state.value = _state.value.copy(isPlaying = true, ended = false)
            notifyNow()
        } catch (_: Throwable) { open(index) }
    }

    fun previous() {
        if (tracks.isEmpty()) return
        open((index - 1).coerceIn(0, tracks.lastIndex))
    }

    fun next() {
        if (tracks.isEmpty()) return
        if (index + 1 >= tracks.size) {
            val q = queueId
            release()
            _state.value = PlaybackState(speed = speed, ended = true, queueId = q,
                preview = isPreview(q))
            clearNotif()
            return
        }
        open(index + 1)
    }

    fun seekTo(ms: Long) {
        try { player?.seekTo(ms.toInt()) } catch (_: Throwable) { }
        _state.value = _state.value.copy(positionMs = ms)
        val ctx = appCtx
        if (ctx != null && !isPreview(queueId) && index in tracks.indices)
            ListenState.save(ctx, queueId, tracks[index].id, ms, tracks[index].label)
    }

    /**
     * K1 · 倍速只作用于「整章 / 整本收听」，不作用于小段试听。
     *
     * 用户实证：合成侧的语速只是文字倾向、调不准；播放倍速才是可靠控制点 ——
     * 即时生效、无损、改多少次都行，不用重做音频。
     * 但试听是用来判断「这一块念得对不对」的，必须保持原速：否则整章听 2.0×
     * 之后去点格子，试听也变 2.0×，根本判断不了音色。
     */
    fun setSpeed(f: Float) {
        speed = f.coerceIn(0.5f, 2f)
        try { player?.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f) }
        catch (_: Throwable) { }
        // 故意不动 previewPlayer，也不改 _previewState.speed：试听永远 1.0×
        _state.value = _state.value.copy(speed = speed)
    }

    // R182 · 位置只在暂停/拖动/切曲/释放时写盘；听几小时的书被系统回收、
    // 直接划掉进程、来电没来得及点暂停，位置就全丢了。tick 由播放条与播放器页
    // 每 300~500ms 调一次，这里按 20 秒节流补写一次，磁盘开销可忽略。
    @Volatile private var lastPosPersistMs = 0L
    private val POS_PERSIST_INTERVAL = 20_000L

    fun tick() {
        val p = player ?: return
        val now = System.currentTimeMillis()
        if (now - lastPosPersistMs >= POS_PERSIST_INTERVAL) {
            lastPosPersistMs = now
            if (_state.value.isPlaying) savePos()
        }
        try {
            _state.value = _state.value.copy(
                positionMs = p.currentPosition.coerceAtLeast(0).toLong(),
                durationMs = p.duration.coerceAtLeast(0).toLong()
            )
        } catch (_: Throwable) { }
    }

    private fun open(i: Int, overrideStartMs: Long? = null) {
        savePos()   // 切到下一曲前先记下上一曲听到哪
        releasePlayer()
        index = i
        val t = tracks[i]
        val from = (overrideStartMs ?: t.startMs).coerceAtLeast(0L)
        if (!t.file.isFile) {
            UiErrors.playback("第 ${i + 1} 段的音频文件不在了，已跳过", null)
            if (i + 1 < tracks.size) { open(i + 1); return }
            finish()
            return
        }
        _state.value = PlaybackState(index = i, label = t.label, trackId = t.id,
            isPlaying = false, positionMs = from, speed = speed, queueId = queueId,
            preview = isPreview(queueId))
        notifyNow()
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                setOnPreparedListener {
                    try { it.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f) }
                    catch (_: Throwable) { }
                    if (from > 0L) { try { it.seekTo(from.toInt()) } catch (_: Throwable) { } }
                    if (!ensureFocus()) { pause(); return@setOnPreparedListener }
                    it.start()
                    _state.value = _state.value.copy(isPlaying = true, ended = false)
                    notifyNow()
                }
                setOnCompletionListener {
                    it.release()
                    player = null
                    if (index + 1 < tracks.size) open(index + 1)
                    else finish()
                }
                setOnErrorListener { failed, _, _ ->
                    failed.release()
                    player = null
                    UiErrors.playback("这段音频打不开，跳到下一段", null)
                    if (index + 1 < tracks.size) open(index + 1)
                    else finish()
                    true
                }
                setDataSource(t.file.absolutePath)
                prepareAsync()
            }
        } catch (_: Throwable) {
            player = null
            UiErrors.playback("音频打开失败，已跳过这一段", null)
            if (i + 1 < tracks.size) open(i + 1)
            else finish()
        }
    }

    private fun finish() {
        appCtx?.let { ListenState.clear(it, queueId) }
        abandonFocus()
        _state.value = PlaybackState(speed = speed, ended = true, queueId = queueId,
            preview = isPreview(queueId))
        clearNotif()
    }

    /**
     * R180 · 把「这一章听到第几秒」落盘。只在主播放队列（非试听）存，
     * 暂停 / 拖动 / 切曲 / 释放 / 换队列都各存一次；播完整章就清掉，
     * 免得下次「接着听」直接指到结尾。
     */
    private fun savePos() {
        val ctx = appCtx ?: return
        if (queueId.isEmpty() || isPreview(queueId)) return
        val i = index
        if (i < 0 || i >= tracks.size) return
        val ms = try {
            (player?.currentPosition ?: _state.value.positionMs.toInt())
                .coerceAtLeast(0).toLong()
        } catch (_: Throwable) { _state.value.positionMs }
        ListenState.save(ctx, queueId, tracks[i].id, ms, tracks[i].label)
    }

    private fun releasePlayer() {
        try { player?.release() } catch (_: Throwable) { }
        player = null
    }

    fun release() {
        pausedByTransient = false
        savePos()   // 换队列 / 退出前，先把旧队列的位置留住
        abandonFocus()
        stopPreview()
        releasePlayer()
        tracks = emptyList()
        index = -1
        queueId = ""
        _state.value = PlaybackState(speed = speed)
        clearNotif()
    }
}
