package com.tingben.app

import android.app.Application
import com.tingben.app.core.ModelStore
import com.tingben.app.core.Producer
import com.tingben.app.core.ServiceResume
import com.tingben.app.diag.EventLog
import com.tingben.app.engine.NativeEngine

class TingbenApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        // R161 · 先记下「上一轮进程是被谁干掉的」，再干别的；
        // 这是排查「切出去回来任务没了」的关键证据，必须在最前面读
        EventLog.logExitReasons(this)
        val ok = try {
            NativeEngine.isAvailable()
        } catch (_: Throwable) { false }
        EventLog.log(EventLog.Kind.ENGINE, "ENGINE_PRELOAD",
            if (ok) "ok" else (NativeEngine.shortReason().ifBlank { "failed" }))
        ModelStore.cleanup(this)
        Producer.restore(this)
        // R386 · 进程被更新 / 清理杀掉后，按用户已保存的开关把两个前台服务
        // 重新拉起；否则设置页显示「已开启」而服务其实是死的，得手动关再开。
        ServiceResume.resumeIfNeeded(this, "冷启动")
    }
}
