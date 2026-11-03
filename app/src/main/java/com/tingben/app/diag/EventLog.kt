package com.tingben.app.diag

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * L2/L3 工程事件，只落盘、不进主界面。
 * native 侧通过 FindClass 调 nativeNote（JNI 名字已随包名改过）。
 */
object EventLog {
    enum class Kind { UI, ENGINE, PIPELINE, MODEL, CRASH }

    @Volatile private var file: File? = null

    fun init(c: Context) {
        file = File(c.filesDir, "events.log")
        log(Kind.UI, "APP_START", "version=" + c.packageManager.getPackageInfo(c.packageName, 0).versionName)
    }

    fun log(kind: Kind, action: String, detail: String = "") {
        val f = file ?: return
        try {
            val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            f.appendText("$ts | $kind | $action | $detail\n")
            if (f.length() > 512 * 1024) {
                val lines = f.readLines()
                f.writeText(lines.takeLast(500).joinToString("\n") + "\n")
            }
        } catch (_: Throwable) { }
    }

    fun read(): String = try { file?.readText() ?: "" } catch (_: Throwable) { "" }

    /** JNI 回调入口，必须 @JvmStatic + 三个 String 参数。 */
    @JvmStatic
    fun nativeNote(kind: String, action: String, detail: String) {
        val k = try { Kind.valueOf(kind) } catch (_: Throwable) { Kind.ENGINE }
        log(k, action, detail)
    }
}
