package com.tingben.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 角色 → 音色表（R14 · 不手搓，四份现成实现拼起来）：
 *
 * - 存储层抄 ReaderVoice `CharacterProfileStore.kt`：
 *   `character_overrides.json` 的 `.tmp → renameTo` 原子落盘、`USER_LOCKED`、
 *   人工修正优先、未修正字段保留自动结果。这里换成 `cast.json`。
 * - 字段设计抄 ReaderVoice `CharacterModel.kt` / `CosyVoiceVoiceProfile.kt`：
 *   稳定 id、canonicalName、schemaVersion、profileHash。
 * - 别名检测抄 novel-voice-studio `generate_personas.py`：
 *   `_token_jaccard` (L69) / `_resolve_to_canonical` (L84) / `alias_of` (L252) /
 *   `_NARRATOR_LABELS` (L125，旁白独立)；`project.py` 的 `_resolve_alias` 链。
 * - 跨块一致性来自 profile id，而不是让 4B 每块重新发明音色。
 *
 * 4B 输出的 `speaker` 只当 raw 标签存，不再是音色键。
 */
private const val CAST_SCHEMA = 1

data class CastEntry(
    val id: String,
    val canonical: String,
    val aliases: List<String> = emptyList(),
    val description: String = "",
    val locked: Boolean = false,
    val count: Int = 0
)

object VoiceCast {
    private fun file(c: Context) = File(c.filesDir, "cast.json")
    private fun part(c: Context) = File(c.filesDir, "cast.json.part")

    private val NARRATOR = setOf("旁白", "叙述", "叙述者", "narrator", "narration", "narrative")

    fun isNarrator(raw: String) = NARRATOR.contains(normalize(raw))

    /** 抄 generate_personas.py 的 normalize_speaker_name：去称谓前缀、去标点、忽略大小写。 */
    fun normalize(name: String): String {
        var s = name.trim().lowercase()
        listOf("年轻的", "年迈的", "老", "小", "mr.", "mrs.", "ms.", "dr.", "mr ", "mrs ", "dr ").forEach {
            if (s.startsWith(it) && s.length > it.length + 1) s = s.removePrefix(it)
        }
        return s.filter { it.isLetterOrDigit() || it == ' ' }.replace(Regex("\\s+"), " ").trim()
    }

    /** 抄 generate_personas.py 的 _token_jaccard：中文按 2-gram，英文按词。 */
    fun tokenJaccard(a: String, b: String): Double {
        fun grams(s: String): Set<String> {
            val t = normalize(s).replace(" ", "")
            if (t.isEmpty()) return emptySet()
            val out = mutableSetOf<String>()
            if (t.any { it.code > 0x2fff }) {
                for (i in 0 until t.length - 1) out.add(t.substring(i, i + 2))
                if (t.length == 1) out.add(t)
            } else {
                out.addAll(normalize(s).split(" ").filter { it.isNotEmpty() })
            }
            return out
        }
        val x = grams(a); val y = grams(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val inter = x.intersect(y).size.toDouble()
        return inter / (x.size + y.size - inter)
    }

    fun load(c: Context): List<CastEntry> = try {
        val f = file(c)
        if (!f.exists()) emptyList() else {
            val o = JSONObject(f.readText())
            // 未知 schema 宽容处理：不崩，尽量读（抄 PocketPal 的 fromJson 宽容策略）
            val arr = o.optJSONArray("entries") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = e.optString("id").ifBlank { return@mapNotNull null }
                val al = e.optJSONArray("aliases")
                CastEntry(
                    id = id,
                    canonical = e.optString("canonical").ifBlank { id },
                    aliases = if (al == null) emptyList() else (0 until al.length()).map { al.getString(it) },
                    description = e.optString("description", ""),
                    locked = e.optBoolean("locked", false),
                    count = e.optInt("count", 0)
                )
            }
        }
    } catch (_: Throwable) { emptyList() }

    /** 原子落位：写一半被杀不会留下坏 JSON（抄 CharacterProfileStore.save）。 */
    fun save(c: Context, list: List<CastEntry>) {
        try {
            val arr = JSONArray()
            list.forEach { e ->
                val al = JSONArray(); e.aliases.forEach { al.put(it) }
                arr.put(JSONObject()
                    .put("id", e.id).put("canonical", e.canonical).put("aliases", al)
                    .put("description", e.description).put("locked", e.locked).put("count", e.count))
            }
            val root = JSONObject().put("schemaVersion", CAST_SCHEMA).put("entries", arr)
            val tmp = part(c); tmp.writeText(root.toString(1))
            if (!tmp.renameTo(file(c))) { file(c).writeText(root.toString(1)); tmp.delete() }
        } catch (_: Throwable) { }
    }

    /** 供 Prefs.voiceStamp 用：角色表任何改动都会让旧 WAV 失效。 */
    fun hash(c: Context): String = try {
        val f = file(c)
        if (f.exists()) sha(f.readText()) else "empty"
    } catch (_: Throwable) { "err" }

    /**
     * 扫已落盘的标注结果，收集所有出现过的说话人标签与次数。
     * 只读磁盘，不重跑 4B；标注还没做的书返回空表。
     */
    fun collect(c: Context): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        try {
            val root = File(c.filesDir, "work")
            root.walkTopDown().filter { it.isFile && it.name.startsWith("ann_") && it.name.endsWith(".json") }
                .forEach { f ->
                    try {
                        val a = JSONArray(f.readText())
                        for (i in 0 until a.length()) {
                            val s = a.optJSONObject(i)?.optString("speaker", "").orEmpty().trim()
                            if (s.isNotEmpty() && !isNarrator(s)) out[s] = (out[s] ?: 0) + 1
                        }
                    } catch (_: Throwable) { }
                }
        } catch (_: Throwable) { }
        return out
    }

    /** 精确 → 别名 → 模糊（阈值 0.8）。返回 null = 还没映射到音色。 */
    fun resolve(c: Context, raw: String): CastEntry? {
        if (raw.isBlank() || isNarrator(raw)) return null
        val list = load(c)
        val n = normalize(raw)
        list.firstOrNull { normalize(it.canonical) == n || it.aliases.any { normalize(it) == n } }
            ?.let { return it }
        return list.maxByOrNull { tokenJaccard(it.canonical, raw) }
            ?.takeIf { tokenJaccard(it.canonical, raw) >= 0.8 }
    }

    /**
     * 把新出现的标签并入角色表：能模糊归到已有角色就写成 alias，否则新建规范角色。
     * `locked` 的人工修正绝不被覆盖（抄 CharacterProfileStore.apply 的 USER_LOCKED 语义）。
     */
    fun reconcile(c: Context): List<CastEntry> {
        val observed = collect(c)
        if (observed.isEmpty()) return load(c)
        val list = load(c).toMutableList()
        for ((raw, cnt) in observed) {
            val n = normalize(raw)
            val exact = list.firstOrNull {
                normalize(it.canonical) == n || it.aliases.any { normalize(it) == n }
            }
            if (exact != null) {
                val idx = list.indexOf(exact)
                if (!exact.locked) list[idx] = exact.copy(count = exact.count + cnt)
                continue
            }
            // 别名链：高置信度模糊匹配，避免「李寻欢 / 小李探花」拆成两个声音
            val near = list.maxByOrNull { tokenJaccard(it.canonical, raw) }
                ?.takeIf { tokenJaccard(it.canonical, raw) >= 0.8 }
            if (near != null) {
                val idx = list.indexOf(near)
                if (raw !in near.aliases)
                    list[idx] = near.copy(aliases = near.aliases + raw, count = near.count + cnt)
            } else {
                list.add(CastEntry(id = sha(n).take(10), canonical = raw, count = cnt))
            }
        }
        save(c, list)
        return list
    }

    /** 用户在角色页改描述 / 锁定。改完 hash 自然变化 ⇒ 旧 WAV 作废。 */
    fun setOverride(c: Context, id: String, description: String, locked: Boolean) {
        val list = load(c).toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) { list[i] = list[i].copy(description = description.trim(), locked = locked); save(c, list) }
    }

    /** 手动把 fromId 并进 toId（别名修正），抄 project.py 的 _resolve_alias 思路。 */
    fun merge(c: Context, fromId: String, toId: String) {
        val list = load(c).toMutableList()
        val from = list.firstOrNull { it.id == fromId } ?: return
        val toIdx = list.indexOfFirst { it.id == toId }
        if (toIdx < 0 || fromId == toId) return
        val to = list[toIdx]
        val mergedAliases = (to.aliases + from.canonical + from.aliases).distinct()
        list[toIdx] = to.copy(aliases = mergedAliases, count = to.count + from.count)
        list.removeIf { it.id == fromId }
        save(c, list)
    }

    fun clear(c: Context) { try { file(c).delete(); part(c).delete() } catch (_: Throwable) { } }
}
