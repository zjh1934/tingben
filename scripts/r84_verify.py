# -*- coding: utf-8 -*-
"""R84 · 模型选择页验证：正向 / 旧形态零残留 / 守恒 / 结构 / 反向注入。

守四件事：
1. ModelStore 是「1 底座 + 3 模式」，且按模式独有关键词精确识别；
2. 模型页把四份文件分开、各自单独导入、文件名不截断、两条下载地址都在；
3. 导出日志相关入口全部砍掉，但诊断摘要 / 试音 / 清理缓存 / 电池优化保留；
4. 非 voicedesign 模式明确拦住合成，不静默产出坏音频，也不画假开关。
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"
PIPE = ROOT / "app/src/main/java/com/tingben/app/core/Pipeline.kt"
PREFS = ROOT / "app/src/main/java/com/tingben/app/core/Prefs.kt"
CHROME = ROOT / "app/src/main/java/com/tingben/app/AppChrome.kt"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
GRADLE = ROOT / "app/build.gradle.kts"
CI = ROOT / ".github/workflows/ci.yml"
CONTRACT = ROOT / "docs/00_现行契约.md"

POS = [
    'enum class Mode(',
    'VOICE_DESIGN("voice_design", "描述造音色",',
    'CUSTOM_VOICE("custom_voice", "预设音色",',
    'BASE("base", "参考音频",',
    '"qwen-talker-1.7b-voicedesign-Q8_0.gguf"',
    '"qwen-talker-1.7b-customvoice-Q8_0.gguf"',
    '"qwen-talker-1.7b-base-Q8_0.gguf"',
    '"qwen-tokenizer-12hz-Q8_0.gguf"',
    'private fun pickFor(c: Context, m: Mode): File?',
    'fun installedModes(c: Context): List<Mode>',
    'fun currentMode(c: Context): Mode',
    'fun modeFiles(c: Context): Map<Mode, File?>',
    'fun synthesisBlockReason(c: Context): String?',
    'if (tokenizer(c) == null) m.add("共用底座（12Hz）")',
    'if (installedModes(c).isEmpty()) m.add("模式主文件")',
    'private const val KEY_MODEL_MODE = "modelMode"',
    'fun modelMode(c: Context): String',
    'fun setModelMode(c: Context, v: String)',
    'val modeFiles = remember(refresh) { ModelStore.modeFiles(c) }',
    'Text(if (allReady) "已就绪 · ${installed.size} 个模式"',
    'Text("共用底座", fontSize = 13.sp',
    'Text("4 个文件 · 下载地址", fontSize = 14.sp)',
    'Text(mode.label + if (isCurrent) " · 当前" else ""',
    'ModelStore.Mode.values().forEach { mode ->',
    'Text(if (f != null) "已导入 · %.2f GB".format(',
    'Text("国内地址", fontSize = 12.sp, color = Primary)',
    'Text("备用 ›"',
    'Text("启用", fontSize = 12.sp, color = Primary)',
    'enabled = !st.running',
    'Text("查看 4 个下载地址 ›"',
    'Text("导入", fontSize = 12.sp, color = Primary)',
    'fun cnUrl(name: String)',
    'fun hfUrl(name: String)',
    'hf-mirror.com/Serveurperso/Qwen3-TTS-GGUF/resolve/main/',
    'huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/main/',
    'fontFamily = FontFamily.Monospace',
    '"回书架，导入一本书"',
    '"切回「描述造音色」"',
    'Prefs.setModelMode(c, ModelStore.Mode.VOICE_DESIGN.id)',
    'Text("一次导入 4 个模型文件"',
    'Text("已导入 ${files.size} 个"',
    'ModelStore.synthesisBlockReason(c)?.let { reason ->',
    'ModelStore.synthesisBlockReason(c)?.let { throw IllegalStateException(it) }',
    'SettingsSection("本机数据", "本机", collapsible = false)',
    'private fun ClearSegmentsRow(',
    'Text("保存试听文本"',
    'Text("清理分段音频", fontSize = 13.sp)',
    'Text("清理 ›"',
    'Text("试听 · 当前"',
    'ActivityResultContracts.OpenMultipleDocuments()',
]

BAN = [
    '"声音主文件" to ModelStore.TALKER',
    '"声音配套文件" to ModelStore.TOKENIZER',
    'Text("需要：$name", fontSize = 12.sp, color = Sub, maxLines = 1)',
    'Text("两份文件都在"',
    'Text("还差 ${missing.size} 份文件"',
    'Text("缺件就出不了声，两份都要放进 App 的模型目录。',
    'Text("2 个声音模型都准备好了"',
    'Text(if (allReady) "已就绪 · 2 份"',
    'slots.forEach',
    'fun talker(c: Context) = pick(c, TALKER, "talker")',
    'SettingsSection("数据与日志"',
    'SettingInfoRow("诊断全文"',
    'SettingInfoRow("分段记录"',
    'fun buildDiag(full: Boolean)',
    'Text("复制完整日志")',
    'Text("导出与诊断"',
    'Text("复制进诊断日志"',
    '想复盘就点下面「复制进诊断日志」',
    'Mixer.logProfile(c, "手动导出当前配置到日志")',
    'fun buildDiag(): String = buildString {',
    'Text("复制诊断信息（摘要）")',
    'Text("清理制作过程的分段音频"',
    'Text("静态快照"',
    'Text("后台运行设置（系统电池优化）"',
    'Text("默认身份锚点"',
    'append(" · instruct v")',
    'append(" · 指纹 ")',
    '合成界面下一轮做',
    '"还差：" + miss.joinToString("、") + "。去设置页导入。"',
 'private fun SoundProbeBlock(',
 'private fun DiagnosticsBlock(',
]

KEEP = [
    'private enum class Screen { LIBRARY, PRODUCE, PROGRESS, TASKS, TASK_DETAIL, AUDIO, SETTINGS, MODELS, MIXER, CHAPTERS }',
    'NAV_SETTINGS to "设置"',
    'NAV_MIXER to "调音台"',
    'NAV_MODELS to "模型"',
    'ModelStore.import(ctx, name, s)',
    'ModelStore.delete(c, f); onChanged()',
    'private fun SettingsScreen(',
    'private fun ClearSegmentsRow(',
    'fun commitAll()',
    'NativeEngine.ttsLoadGated(tk.absolutePath, tok.absolutePath, null)',
    'NativeEngine.ttsFreeGated(h)',
    'fun voiceStamp(c: Context): String',
]

def model_body(text):
    s = text.index("private fun ModelScreen(")
    e = text.index("private fun MixerScreen(", s)
    return text[s:e]

def check(files):
    errs = []
    m, p, pf, ch, mf, ci, gr = (files[k] for k in
                            ("main", "pipe", "prefs", "chrome", "manifest", "ci", "gradle"))
    all_text = m + p + pf
    for s in POS:
        if s not in all_text:
            errs.append("缺正向: " + s[:56])
    for s in BAN:
        if s in all_text:
            errs.append("旧形态残留: " + s[:56])
    for s in KEEP:
        if s not in m and s not in p and s not in pf and s not in ch and s not in gr:
            errs.append("守恒丢失: " + s[:56])
    if 'versionName = "1.1.0+"' not in gr:
        errs.append("版本号口径丢失")
    if "android.permission.INTERNET" in mf:
        errs.append("AndroidManifest 出现 INTERNET 权限")
    try:
        b = model_body(m)
        if b.count("ModelStore.Mode.values().forEach { mode ->") != 1:
            errs.append("模式行不是单一循环渲染 3 张")
        if "maxLines = 1" in b:
            errs.append("模型页仍有单行截断的文件名")
        if b.count("cnUrl(") < 2 or b.count("hfUrl(") < 2:
            errs.append("国内 / 备用两条下载地址不全")
        if b.count("FontFamily.Monospace") < 1:
            errs.append("文件名未用等宽完整显示")
        if "st.running" not in b:
            errs.append("制作中禁切缺失")
        if b.count('Text("一次导入 4 个模型文件"') != 1:
            errs.append("缺件主按钮不唯一")
    except ValueError:
        errs.append("ModelScreen 区块定位失败")
    try:
        vs = pf[pf.index("fun voiceStamp"):pf.index("fun legacyVoiceStamp")]
        if "modelMode" in vs:
            errs.append("模式误进声音指纹（会作废旧 WAV）")
    except ValueError:
        errs.append("voiceStamp 定位失败")
    try:
        ms = p[p.index("object ModelStore"):p.index("object ChapterSplitter {")]
        if 'pick(c, TALKER, "talker")' in ms:
            errs.append("泛化 talker 模糊匹配仍在")
        for k in ("-voicedesign-", "-customvoice-", "-base-"):
            if k not in ms and 'm.key' not in ms:
                errs.append("模式独有关键词缺失: " + k)
        if "synthesisBlockReason" not in ms:
            errs.append("合成拦截函数缺失")
    except ValueError:
        errs.append("ModelStore 区块定位失败")
    if not CONTRACT.exists():
        errs.append("现行契约缺失")
    if "r84_verify.py" not in ci:
        errs.append("CI 未挂 r84_verify")
    if "r81_verify.py" in ci:
        errs.append("CI 仍挂着已归档的 r81_verify")
    return errs

def main():
    files = {
        "main": MAIN.read_text(encoding="utf-8"),
        "pipe": PIPE.read_text(encoding="utf-8"),
        "prefs": PREFS.read_text(encoding="utf-8"),
        "chrome": CHROME.read_text(encoding="utf-8"),
        "manifest": MANIFEST.read_text(encoding="utf-8"),
        "ci": CI.read_text(encoding="utf-8"),
        "gradle": GRADLE.read_text(encoding="utf-8"),
    }
    errs = check(files)
    if "--selftest" in sys.argv:
        bad_forms = [
            'Text("复制完整日志")',
            'SettingsSection("数据与日志"',
            '"声音主文件" to ModelStore.TALKER',
            'Text("需要：$name", fontSize = 12.sp, color = Sub, maxLines = 1)',
            'fun talker(c: Context) = pick(c, TALKER, "talker")',
            'Text("2 个声音模型都准备好了"',
            'Text("导出与诊断"',
            'Mixer.logProfile(c, "手动导出当前配置到日志")',
            'Text("复制诊断信息（摘要）")',
            'Text("静态快照"',
            '合成界面下一轮做',
        ]
        for bad in bad_forms:
            t = dict(files)
            t["main"] = files["main"] + "\n// injected\n" + bad + "\n"
            if not check(t):
                errs.append("反向注入未拦下: " + bad[:44])
        print("反向注入 %d/%d 全拦下" % (len(bad_forms), len(bad_forms)))
    if errs:
        print("FAIL", len(errs))
        [print(" -", e) for e in errs]
        sys.exit(1)
    print("PASS · R84 正向 %d / 零残留 %d / 守恒 %d / 结构检查通过" %
          (len(POS), len(BAN), len(KEEP)))

if __name__ == "__main__":
    main()
