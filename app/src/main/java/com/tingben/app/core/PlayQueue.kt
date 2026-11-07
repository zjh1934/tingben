package com.tingben.app.core

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

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
    val queueId: String = ""
)

object PlayQueue {
    private var player: MediaPlayer? = null
    private var tracks: List<Track> = emptyList()
    private var index = -1
    private var speed = 1f
    private var queueId = ""

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> get() = _state

    fun setTracks(list: List<Track>, id: String = "") {
        if (list == tracks && id == queueId) return
        release()
        tracks = list
        queueId = id
        _state.value = PlaybackState(speed = speed, queueId = id)
    }

    fun playFrom(i: Int, startMs: Long? = null, id: String? = null) {
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
            val q = queueId
            release()
            _state.value = PlaybackState(speed = speed, ended = true, queueId = q)
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
        try { player?.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f) }
        catch (_: Throwable) { }
        _state.value = _state.value.copy(speed = speed)
    }

    fun tick() {
        val p = player ?: return
        try {
            _state.value = _state.value.copy(
                positionMs = p.currentPosition.coerceAtLeast(0).toLong(),
                durationMs = p.duration.coerceAtLeast(0).toLong()
            )
        } catch (_: Throwable) { }
    }

    private fun open(i: Int, overrideStartMs: Long? = null) {
        releasePlayer()
        index = i
        val t = tracks[i]
        val from = (overrideStartMs ?: t.startMs).coerceAtLeast(0L)
        if (!t.file.isFile) {
            if (i + 1 < tracks.size) open(i + 1)
            else { _state.value = PlaybackState(speed = speed, ended = true, queueId = queueId); return }
        }
        _state.value = PlaybackState(index = i, label = t.label, trackId = t.id,
            isPlaying = false, positionMs = from, speed = speed, queueId = queueId)
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
                            isPlaying = false, ended = true, index = -1, label = "",
                            trackId = "")
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
        queueId = ""
        _state.value = PlaybackState(speed = speed)
    }
}
