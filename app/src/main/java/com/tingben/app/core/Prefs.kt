package com.tingben.app.core

import android.content.Context

/**
 * 用户能改的东西，全部集中在这里，不散落在界面里。
 * 默认值按「可靠优先」定：关思考、4096 上下文、4 线程、贪心。
 */
object Prefs {
    private const val FILE = "tingben_settings"
    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // 思考外显：mlabonne Qwen3-4B-abliterated（初版系）支持 /think / /no_think
    fun thinking(c: Context) = sp(c).getBoolean("thinking", false)
    fun setThinking(c: Context, v: Boolean) = sp(c).edit().putBoolean("thinking", v).apply()

    // 上下文窗口：8192 在 8Gen3 上有 OOM 风险，默认 4096
    fun nCtx(c: Context) = sp(c).getInt("nCtx", 4096)
    fun setNCtx(c: Context, v: Int) = sp(c).edit().putInt("nCtx", v).apply()

    fun threads(c: Context) = sp(c).getInt("threads", 4)
    fun setThreads(c: Context, v: Int) = sp(c).edit().putInt("threads", v).apply()

    // 0 = 贪心，输出 JSON 最稳
    fun temperature(c: Context) = sp(c).getFloat("temperature", 0f)
    fun setTemperature(c: Context, v: Float) = sp(c).edit().putFloat("temperature", v).apply()

    fun summary(c: Context) = buildString {
        append(if (thinking(c)) "思考：开" else "思考：关")
        append(" · 上下文 ").append(nCtx(c))
        append(" · 线程 ").append(threads(c))
        append(" · 温度 ").append(temperature(c))
    }
}
