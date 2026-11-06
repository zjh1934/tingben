package com.tingben.app.core

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * R18 · 播放队列。**不是手搓的**，由三份现成源码改写而来：
 *
 * - ReaderVoice-Mobile `AudioCache.kt / CachedAudioPlayer`
 *   抄：queue + currentIndex + playCurrent() + onCompletion 自动下一段 +
 *       AudioAttributes(USAGE_MEDIA / CONTENT_TYPE_SPEECH) + release()。
 *   改：把「回调 onStatus(String)」换成 StateFlow，供 Compose 双向驱动。
 * - AlexExoPlayerMP3 `core/player/PlayerController.kt`
 *   抄：snapshot() → isPlaying / positionMs / durationMs 的状态形状，以及
 *       「轮询推进度」的思路。改：不引 Media3/callbackFlow，用 UI 已有的每秒 tick() 调。
 * - ensemble-reads `player/PlaybackController.kt`
 *   抄：playFromSeg / pause / resume / nextSeg / prevSeg / setSpeed 这组 API 形状
 *       （点段落从这里播、上一段下一段、倍速）。
 * - Media3 官方 `PreviousButton / PlayPauseButton / NextButton` 的**布局语义**
 *   （三按钮、状态驱动 enabled/图标），只抄语义不引库 —— 项目无 INTERNET 权限，
 *   不能新拉 androidx.media3 依赖。
 *
 * 做成 object 单例：音频页与「分段看台」共用同一个播放器，切页不停播，
 * 且不会出现两套 MediaPlayer 抢音频焦点。
 */
/** R100 · startMs：整本成品里这一章的起点，prepared 后 seekTo，点第 5 章不再从头播。 */
data class Track(val file: File, val label: String, val startMs: Long = 0L)

data class PlaybackState(
    val index: Int = -1,
    val label: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val ended: Boolean = false
)

object PlayQueue {
    private var player: MediaPlayer? = null
    private var tracks: List<Track> = emptyList()
    private var index = -1
    private var speed = 1f

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> get() = _state

    /** 只换队列不自动播（AudioScreen 进页面 / 看台扫盘后调用）。 */
    fun setTracks(list: List<Track>) {
        if (list == tracks) return
        release()
        tracks = list
        _state.value = PlaybackState(speed = speed)
    }

    /** ensemble-reads 的 playFromSeg：点哪段从哪段开始。 */
    fun playFrom(i: Int, startMs: Long? = null) {
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
        try { player?.pause() } catch (_: Throwable) { }
        _state.value = _state.value.copy(isPlaying = false)
    }

    fun resume() {
        val p = player
        if (p == null) { if (index >= 0) open(index); return }
        try {
            p.start()
            _state.value = _state.value.copy(isPlaying = true, ended = false)
        } catch (_: Throwable) { open(index) }
    }

    fun previous() {
        if (tracks.isEmpty()) return
        open((index - 1).coerceIn(0, tracks.lastIndex))
    }

    fun next() {
        if (tracks.isEmpty()) return
        if (index + 1 >= tracks.size) {
            release()
            _state.value = PlaybackState(speed = speed, ended = true)
            return
        }
        open(index + 1)
    }

    fun seekTo(ms: Long) {
        try { player?.seekTo(ms.toInt()) } catch (_: Throwable) { }
        _state.value = _state.value.copy(positionMs = ms)
    }

    fun setSpeed(f: Float) {
        speed = f.coerceIn(0.5f, 2f)
        // MediaPlayer 只在 prepared 之后接受 playbackParams；失败就静默保持 1x。
        try { player?.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f) }
        catch (_: Throwable) { }
        _state.value = _state.value.copy(speed = speed)
    }

    /** 替代 PlayerController 的 callbackFlow 轮询：UI 已有的每秒心跳里调一次即可。 */
    fun tick() {
        val p = player ?: return
        try {
            _state.value = _state.value.copy(
                positionMs = p.currentPosition.coerceAtLeast(0).toLong(),
                durationMs = p.duration.coerceAtLeast(0).toLong()
            )
        } catch (_: Throwable) { }
    }

    /** CachedAudioPlayer.playCurrent 的改写：一段一个 MediaPlayer，放完自动下一段。 */
    private fun open(i: Int, overrideStartMs: Long? = null) {
        releasePlayer()
        index = i
        val t = tracks[i]
        val from = (overrideStartMs ?: t.startMs).coerceAtLeast(0L)
        if (!t.file.isFile) {                       // 抄它「文件不在就跳到下一段」
            if (i + 1 < tracks.size) open(i + 1)
            else { _state.value = PlaybackState(speed = speed, ended = true); return }
        }
        _state.value = PlaybackState(index = i, label = t.label, isPlaying = false,
            positionMs = from, speed = speed)
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
                    it.start()
                    _state.value = _state.value.copy(isPlaying = true, ended = false)
                }
                setOnCompletionListener {
                    it.release()
                    player = null
                    if (index + 1 < tracks.size) open(index + 1)
                    else {
                        _state.value = _state.value.copy(
                            isPlaying = false, ended = true, index = -1, label = "")
                    }
                }
                setOnErrorListener { failed, _, _ ->
                    failed.release()
                    player = null
                    if (index + 1 < tracks.size) open(index + 1)
                    true
                }
                setDataSource(t.file.absolutePath)
                prepareAsync()
            }
        } catch (_: Throwable) {
            player = null
            if (i + 1 < tracks.size) open(i + 1)
        }
    }

    private fun releasePlayer() {
        try { player?.release() } catch (_: Throwable) { }
        player = null
    }

    fun release() {
        releasePlayer()
        tracks = emptyList()
        index = -1
        _state.value = PlaybackState(speed = speed)
    }
}
