package com.tingben.app.core

/**
 * R183 · 真机日志实证：调音台试听连着 4 条「没生成声音，code=-2」，
 * 用户只能反复点，根本不知道是内存不够、模型没加载还是设置不对。
 *
 * native 侧返回码（app/src/main/cpp/tingben_native.cpp）：
 *   -1  TTS 未加载 / -10 写 WAV 失败 / -11 采样格式不符 /
 *   -99 Kotlin 调用抛异常 / 其余负数来自上游 qt_synthesize。
 * 试听和正式合成共用同一套说法，别两处各写一份。
 */
object SynthErrors {
    /** 短原因，用于「第 N 段没生成声音（…）」这种括号里。 */
    fun reason(code: Int, wrote: Boolean): String = when {
        code == 0 && !wrote -> "模型算了但没写出声音"
        code == -1 -> "本地引擎还没加载"
        code == -10 -> "音频写不进磁盘"
        code == -11 -> "引擎返回的音频格式不对"
        code == -99 -> "合成时软件出错"
        code < 0 -> "模型没算出声音，多半是内存不够或模型文件没齐"
        else -> "没生成声音（错误码 $code）"
    }

    /** 完整提示，用于试听失败这种需要给人下一步的场合。 */
    fun explain(code: Int, wrote: Boolean): String {
        val r = reason(code, wrote)
        return when {
            code == 0 && !wrote -> "$r：把试听文字改短一点，或退出重进一次再试"
            code == -1 -> "$r：退出重进一次，让引擎重新加载"
            code == -10 -> "$r：看看手机剩余空间够不够"
            code == -11 -> "$r：换一个音色模式再试"
            code == -99 -> "$r：退出重进一次再试"
            code < 0 -> "$r（错误码 $code）：关掉别的应用腾内存，" +
                    "或到模型页重新导入这一份模式主文件再试"
            else -> "$r：退出重进一次再试"
        }
    }
}
