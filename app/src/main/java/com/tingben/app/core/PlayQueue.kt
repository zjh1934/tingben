package com.tingben.app.core

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
    private var appCtx: Context? = null
    private var focusRequest: AudioFocusRequest? = null
    private var noisyReceiver: BroadcastReceiver? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> get() = _state

    private fun notifyNow() { appCtx?.let { PlayNotif.show(it, _state.value) } }
    private fun clearNotif() { appCtx?.let { PlayNotif.cancel(it) } }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> pause()
        }
    }

    fun attach(c: Context) {
        if (appCtx != null) return
        val ctx = c.applicationContext
        appCtx = ctx
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
        if (list == tracks && id == queueId) return
        release()
        tracks = list
        queueId = id
        _state.value = PlaybackState(speed = speed, queueId = id)
        clearNotif()
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
            _state.value = PlaybackState(speed = speed, ended = true, queueId = q)
            clearNotif()
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
            if (i + 1 < tracks.size) { open(i + 1); return }
            finish()
            return
        }
        _state.value = PlaybackState(index = i, label = t.label, trackId = t.id,
            isPlaying = false, positionMs = from, speed = speed, queueId = queueId)
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
                    if (index + 1 < tracks.size) open(index + 1)
                    else finish()
                    true
                }
                setDataSource(t.file.absolutePath)
                prepareAsync()
            }
        } catch (_: Throwable) {
            player = null
            if (i + 1 < tracks.size) open(i + 1)
            else finish()
        }
    }

    private fun finish() {
        abandonFocus()
        _state.value = PlaybackState(speed = speed, ended = true, queueId = queueId)
        clearNotif()
    }

    private fun releasePlayer() {
        try { player?.release() } catch (_: Throwable) { }
        player = null
    }

    fun release() {
        abandonFocus()
        releasePlayer()
        tracks = emptyList()
        index = -1
        queueId = ""
        _state.value = PlaybackState(speed = speed)
        clearNotif()
    }
}
