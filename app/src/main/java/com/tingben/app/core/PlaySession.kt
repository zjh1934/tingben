package com.tingben.app.core

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as SysPlaybackState
import android.os.SystemClock

/**
 * R248 · 平台内嵌 MediaSession：把播放态交给系统，锁屏 / 耳机 / 蓝牙 / 车机
 * 才能显示媒体控制并把线控按键转回来。零第三方依赖（minSdk 26 全可用），
 * 不引 Media3 —— 它要动 build.gradle 加 Service，这里已够用。
 *
 * 生命周期：在 PlayQueue.attach 的主线程 ensure 一次，之后 notifyNow 只 sync
 * 状态、不再重建（非主线程可能没有 Looper，构造 MediaSession 会抛异常）。
 * clearNotif 只 deactivate，不 release 对象，避免下次又要在别的线程重建。
 */
object PlaySession {
    private var session: MediaSession? = null

    /** 给 Notification.MediaStyle 用，锁屏与通知栏才会认这是一次媒体会话。 */
    val token: MediaSession.Token? get() = session?.sessionToken

    fun ensure(c: Context) {
        if (session != null) return
        val s = MediaSession(c.applicationContext, "tingben")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { PlayQueue.resume() }
            override fun onPause() { PlayQueue.pause() }
            override fun onSkipToNext() { PlayQueue.next() }
            override fun onSkipToPrevious() { PlayQueue.previous() }
            override fun onStop() { PlayQueue.release() }
        })
        s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS)
        session = s
    }

    /** 状态唯一真值源仍是 PlayQueue.state；这里只把它镜像给系统。 */
    fun sync(c: Context, st: PlaybackState) {
        ensure(c)
        val ms = session ?: return
        val active = !st.preview && st.label.isNotEmpty()
        ms.setActive(active)
        ms.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, st.label.ifBlank { "听本" })
            .putLong(MediaMetadata.METADATA_KEY_DURATION, st.durationMs.coerceAtLeast(0L))
            .build())
        val state = when {
            st.isPlaying -> SysPlaybackState.STATE_PLAYING
            st.ended -> SysPlaybackState.STATE_STOPPED
            else -> SysPlaybackState.STATE_PAUSED
        }
        ms.setPlaybackState(SysPlaybackState.Builder()
            .setState(state, st.positionMs.coerceAtLeast(0L), st.speed,
                SystemClock.elapsedRealtime())
            .setActions(
                SysPlaybackState.ACTION_PLAY or
                SysPlaybackState.ACTION_PAUSE or
                SysPlaybackState.ACTION_PLAY_PAUSE or
                SysPlaybackState.ACTION_SKIP_TO_NEXT or
                SysPlaybackState.ACTION_SKIP_TO_PREVIOUS or
                SysPlaybackState.ACTION_STOP)
            .build())
    }

    /** 播放结束 / 换队列 / 被关掉：会话不再 active，别留一条幽灵锁控。 */
    fun deactivate() {
        val ms = session ?: return
        ms.setActive(false)
        ms.setPlaybackState(SysPlaybackState.Builder()
            .setState(SysPlaybackState.STATE_NONE, 0L, 0f,
                SystemClock.elapsedRealtime())
            .setActions(0L)
            .build())
    }
}
