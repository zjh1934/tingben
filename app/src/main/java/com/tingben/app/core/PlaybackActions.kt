package com.tingben.app.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PlaybackActions : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        when (i?.action) {
            "com.tingben.app.PLAY_TOGGLE" -> PlayQueue.toggle()
            "com.tingben.app.PLAY_NEXT" -> PlayQueue.next()
            "com.tingben.app.PLAY_PREV" -> PlayQueue.previous()
            "com.tingben.app.PLAY_STOP" -> {
                PlayQueue.release()
                PlayNotif.cancel(c)
            }
        }
    }
}
