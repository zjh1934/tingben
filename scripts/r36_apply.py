#!/usr/bin/env python3
"""R36 · 调音台重设计：按标记切片替换，每处必须命中 1 次，否则整体不写回。

沿用项目既有 r28_apply.py / r33_ui_clean.py 的做法：长段落用切片而不是字符串
replace，避免长度不等的替换破坏 2249 行主文件。改完打印行号定位供自检脚本核对。
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
main = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"
mixer = ROOT / "app/src/main/java/com/tingben/app/core/Mixer.kt"
fragment = ROOT / "samples/R36_MixerScreen.kt.txt"

src = main.read_text(encoding="utf-8")
frag = fragment.read_text(encoding="utf-8")
checks = []

# ── 1. VoicePreset.render()：空 free 时不再拼出多余句号 ──
old_render = '''    /** 拼成 VoiceDesign 的 instruct 身份层。不写情绪、不写剧情、不写「此刻」。 */
    fun render(): String = buildList {
        listOf(
            VoiceDimension.GENDER, VoiceDimension.AGE, VoiceDimension.PITCH,
            VoiceDimension.TIMBRE, VoiceDimension.BRIGHTNESS, VoiceDimension.ROUGHNESS,
            VoiceDimension.BREATH, VoiceDimension.WEIGHT, VoiceDimension.RATE,
            VoiceDimension.CLARITY, VoiceDimension.ACCENT, VoiceDimension.PERSONA
        ).forEach { d ->
            fields[d.name]?.takeIf { it.isNotBlank() }?.let { add("${d.label}：${it}") }
        }
    }.joinToString("；") + "。" + free.trim().removeSuffix("。") + "。"
'''
new_render = '''    /** 拼成 VoiceDesign 的 instruct 身份层。不写情绪、不写剧情、不写「此刻」。
     *  R36 · 12 维可逐维编辑后 fields 可能为空，空 free 不再拼出多余句号。 */
    fun render(): String {
        val body = buildList {
            listOf(
                VoiceDimension.GENDER, VoiceDimension.AGE, VoiceDimension.PITCH,
                VoiceDimension.TIMBRE, VoiceDimension.BRIGHTNESS, VoiceDimension.ROUGHNESS,
                VoiceDimension.BREATH, VoiceDimension.WEIGHT, VoiceDimension.RATE,
                VoiceDimension.CLARITY, VoiceDimension.ACCENT, VoiceDimension.PERSONA
            ).forEach { d ->
                fields[d.name]?.takeIf { it.isNotBlank() }?.let { add("${d.label}：${it}") }
            }
        }.joinToString("；")
        val tail = free.trim().removeSuffix("。")
        return buildString {
            if (body.isNotBlank()) append(body).append("。")
            if (tail.isNotBlank()) append(tail).append("。")
        }
    }
'''
mtext = mixer.read_text(encoding="utf-8")
checks.append(("render", mtext.count(old_render)))
if mtext.count(old_render) != 1:
    sys.exit("render 切片命中 %d 次，停止" % mtext.count(old_render))
mtext = mtext.replace(old_render, new_render, 1)

# ── 2. 设置页音频模型组：9 项采样收敛，不再画第二遍 ──
start_mark = "        // R14 · 声音：固定音色层 + 角色映射 + 9 个采样参数（默认值来自 qwentts.cpp，非手搓）\n"
end_mark = "        // 音频模型调试：真合成，不是 Toast 假按钮；第二个按钮直接验哭/笑/叹气效果\n"
checks.append(("设置页起点", src.count(start_mark)))
checks.append(("设置页终点", src.count(end_mark)))
if src.count(start_mark) != 1 or src.count(end_mark) != 1:
    sys.exit("设置页切片标记命中异常 %s" % str(checks))
s = src.index(start_mark)
e = src.index(end_mark)
new_settings = '''        // R36 · 音频模型 9 项采样与 12 维音色全部收敛到调音台，设置页不再画第二遍。
        // 两处都只读写 Prefs 同一份真值源，不算两份配置。
        CardBox(Modifier.padding(bottom = 11.dp)) {
            val castOn = remember(cfg) { Prefs.castEnabled(c) }
            Text("音频模型 · 全部参数", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("角色映射", fontSize = 12.sp, color = Sub, modifier = Modifier.weight(1f))
                Switch(checked = castOn, onCheckedChange = {
                    Prefs.setCastEnabled(c, it); cfg++
                })
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onMixer, Modifier.fillMaxWidth()) {
                Text("音色 12 维 · 采样 9 项 · 预设 · 角色卡 ›", fontSize = 12.sp)
            }
        }

'''
src = src[:s] + new_settings + src[e:]

# ── 3. 调音台整段（文件最后一个区段）替换 ──
mix_mark = "// ── ⑥ 调音台（R31）：音频模型控制台 ─────────────────────────────\n"
checks.append(("调音台旧段", src.count(mix_mark)))
if src.count(mix_mark) != 1:
    sys.exit("调音台旧段标记命中 %d 次，停止" % src.count(mix_mark))
m = src.index(mix_mark)
src = src[:m] + frag

main.write_text(src, encoding="utf-8")
mixer.write_text(mtext, encoding="utf-8")
print("切片命中:", checks)
print("MainActivity 行数:", len(src.splitlines()), "Mixer.kt 行数:", len(mtext.splitlines()))
