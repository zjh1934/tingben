package com.tingben.app

import android.app.Application
import com.tingben.app.core.ModelStore
import com.tingben.app.diag.EventLog
import com.tingben.app.engine.NativeEngine

class TingbenApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        // 必须在这里就把 libtingben_native.so 装进来。
        // 之前只有点「复制诊断信息」或「开始制作」才会触发 ensure()，
        // 用户直接在设置页点「试合成一句」→ external 调用时库还没加载
        // → "No implementation found ... is the library loaded?"。
        val ok = try {
            NativeEngine.isAvailable()
        } catch (_: Throwable) { false }
        EventLog.log(EventLog.Kind.ENGINE, "ENGINE_PRELOAD",
            if (ok) "ok" else (NativeEngine.shortReason().ifBlank { "failed" }))
        ModelStore.cleanup(this)
    }
}
