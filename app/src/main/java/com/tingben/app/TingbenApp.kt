package com.tingben.app

import android.app.Application
import com.tingben.app.core.ModelStore
import com.tingben.app.core.Producer
import com.tingben.app.diag.EventLog
import com.tingben.app.engine.NativeEngine

class TingbenApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        val ok = try {
            NativeEngine.isAvailable()
        } catch (_: Throwable) { false }
        EventLog.log(EventLog.Kind.ENGINE, "ENGINE_PRELOAD",
            if (ok) "ok" else (NativeEngine.shortReason().ifBlank { "failed" }))
        ModelStore.cleanup(this)
        Producer.restore(this)
    }
}
