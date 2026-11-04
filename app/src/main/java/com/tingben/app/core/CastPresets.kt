package com.tingben.app.core

import android.content.Context

/**
 * R36 · 角色预设：用户说「角色预设那些都是空的」，这里给一套可改的内置卡。
 *
 * 不手搓依据：
 *  - 角色表存储、别名、USER_LOCKED 全部复用 R14 的 VoiceCast.kt
 *    （抄 ReaderVoice-Mobile `CharacterProfileStore.kt`，MIT）。
 *  - 4 张内置卡的身份描述直接取 VoicePresets 的现成中文音色网格
 *    （移植 096b_personas.py，MIT），不自己编一套形容词。
 *  - 「旁白 / 路人等无固定声音的角色」独立标签抄 novel-voice-studio
 *    generate_personas.py 的 _NARRATOR_LABELS 与 IndexTTS WebUI 的「匿名角色」。
 *
 * 边界：只在「从没 seed 过 + 表为空」时写一次，绝不覆盖用户已改的角色表。
 * seed 会改变 VoiceCast.hash ⇒ 旧 WAV 按 R14 既有机制作废，这是预期行为。
 */
data class CastPreset(val canonical: String, val gridId: String, val note: String)

object CastPresets {
    val BUILTIN: List<CastPreset> = listOf(
        CastPreset("旁白", "P21", "儒雅轻声，叙述用"),
        CastPreset("青年男主", "P02", "温润青年"),
        CastPreset("青年女主", "P06", "温柔女青年"),
        CastPreset("长者", "P03", "中年威严，低沉浑厚")
    )

    fun descriptionOf(p: CastPreset): String =
        VoicePresets.find(p.gridId)?.render() ?: DEFAULT_IDENTITY

    /** 返回是否真的写入了内置卡；写一次后打标记，重复进页面不会反复刷。 */
    fun seedIfEmpty(c: Context): Boolean {
        if (Prefs.castSeeded(c)) return false
        if (VoiceCast.load(c).isNotEmpty()) {
            Prefs.setCastSeeded(c, true)
            return false
        }
        val list = BUILTIN.map { p ->
            CastEntry(
                id = sha(p.canonical).take(10),
                canonical = p.canonical,
                aliases = emptyList(),
                description = descriptionOf(p),
                locked = false,
                count = 0
            )
        }
        VoiceCast.save(c, list)
        Prefs.setCastSeeded(c, true)
        return true
    }

    /** 把某张网格音色套到已有角色；locked 的人工修正不被覆盖（沿用 setOverride 语义）。 */
    fun applyGrid(c: Context, id: String, gridId: String) {
        val p = VoicePresets.find(gridId) ?: return
        val list = VoiceCast.load(c).toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0 || list[i].locked) return
        list[i] = list[i].copy(description = p.render())
        VoiceCast.save(c, list)
    }

    /** 新加一张角色卡（名字重复直接忽略，避免拆成两个声音）。 */
    fun add(c: Context, name: String, gridId: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        val list = VoiceCast.load(c).toMutableList()
        if (list.any { it.canonical == n }) return false
        list.add(CastEntry(id = sha(n).take(10), canonical = n,
            description = VoicePresets.find(gridId)?.render() ?: DEFAULT_IDENTITY))
        VoiceCast.save(c, list)
        return true
    }

    fun remove(c: Context, id: String) {
        VoiceCast.save(c, VoiceCast.load(c).filter { it.id != id })
    }
}
