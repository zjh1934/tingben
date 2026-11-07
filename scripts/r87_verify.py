# -*- coding: utf-8 -*-
"""R87 · 界面文案净化验证：正向 / 旧话术零残留 / 守恒 / 反向注入。

守四件事：
1. 设置页不画假入口：去系统设置、清理分段音频都是真可点；
2. 日志导出与复制整块退场，当前配置只报用户能核对的现行真值；
3. 模型页说明不写开发排期、不暴露内部字段名；
4. 语言模型时代的死常量与错误提示指向一并清掉。
"""
import pathlib, sys, tempfile, shutil

ROOT = pathlib.Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"
PIPE = ROOT / "app/src/main/java/com/tingben/app/core/Pipeline.kt"
PREFS = ROOT / "app/src/main/java/com/tingben/app/core/Prefs.kt"
CHROME = ROOT / "app/src/main/java/com/tingben/app/AppChrome.kt"
GRADLE = ROOT / "app/build.gradle.kts"
CI = ROOT / ".github/workflows/ci.yml"

POS = [
    'onClick: (() -> Unit)? = null',
    'private fun ClearSegmentsRow(',
    'Text("清理 ›"',
    'Text("清理分段音频", fontSize = 13.sp)',
    'SettingsSection("外观与显示", "软件", collapsible = false)',
    'SettingsSection("本机数据", "本机", collapsible = false)',
 'SettingsSection("通知与权限", "系统", collapsible = false)',
    'append("\\n声音 · 模式 ")',
    'append(" · 音色 ")',
    'append(" · seed ")',
    '用一段文字描述想要的嗓音，可叠加速度、情绪等可调项。',
    '从模型自带的音色里挑一个，再给它一句念法要求。',
    '给一小段人声做样本，照着这个声音来念；不再用文字描述。',
    '先在调音台点「读取模型自带的音色表」挑一个音色，再合成。',
    '没能生成音频，可以回书架重新合成这一本',
]

BAN = [
    'Text("静态快照"',
    'Text("复制诊断信息（摘要）")',
    'Text("清理制作过程的分段音频"',
    'Text("后台运行设置（系统电池优化）"',
    'Text("清理音频缓存", "需确认"',
    'append(" · 拟声表达 ")',
    'append(" · instruct v")',
    'append(" · 指纹 ")',
    '默认身份锚点',
    '合成界面下一轮做',
    '请点设置页复制诊断信息发我',
    '这个模式的合成界面还没做完',
    'const val LLM =',
    'const val LLM_LEGACY =',
    'const val TALKER =',
 'private fun SoundProbeBlock(',
 'private fun DiagnosticsBlock(',
]

KEEP = [
    'private enum class Screen { LIBRARY, PRODUCE, PROGRESS, TASKS, TASK_DETAIL, AUDIO, SETTINGS, MODELS, MIXER, CHAPTERS }',
    'SettingSwitchRow("通知栏播放控制", notifyOn)',
    'Prefs.setThemeMode(c, m)',
    'Prefs.setTrialText(c, trialText)',
    'fun commitAll()',
    'fun voiceStamp(c: Context): String',
    'fun synthesisBlockReason(c: Context): String?',
    'ActivityResultContracts.OpenMultipleDocuments()',
]

def check(files):
    errs = []
    m, p, pf, ch, gr, ci = (files[k] for k in
                            ("main", "pipe", "prefs", "chrome", "gradle", "ci"))
    all_text = m + p + pf
    for s in POS:
        if s not in all_text:
            errs.append("缺正向: " + s[:56])
    for s in BAN:
        if s in all_text:
            errs.append("旧形态残留: " + s[:56])
    for s in KEEP:
        if s not in all_text + ch:
            errs.append("守恒丢失: " + s[:56])
    if "r87_verify.py" not in ci:
        errs.append("CI 未挂 r87_verify")
    if "1.1.0+" not in gr:
        errs.append("版本口径被改")
    return errs

def txt(path, encoding="utf-8"):
    return path.read_text(encoding=encoding, errors="replace")

def main():
    files = {
        "main": txt(MAIN), "pipe": txt(PIPE), "prefs": txt(PREFS),
        "chrome": txt(CHROME), "gradle": txt(GRADLE), "ci": txt(CI),
    }
    errs = check(files)
    if "--selftest" in sys.argv:
        injections = [
            ("复制诊断按钮回潮",
             lambda t: dict(t, main=t["main"] + '\nText("复制诊断信息（摘要）")\n')),
            ("静态快照回潮",
             lambda t: dict(t, main=t["main"] + '\nText("静态快照")\n')),
            ("开发排期话术回潮",
             lambda t: dict(t, pipe=t["pipe"] + '\n// 合成界面下一轮做\n')),
            ("拟声表达旧话术回潮",
             lambda t: dict(t, prefs=t["prefs"] + '\nappend(" · 拟声表达 ")\n')),
            ("instruct 版本旧话术回潮",
             lambda t: dict(t, prefs=t["prefs"] + '\nappend(" · instruct v")\n')),
            ("指纹 hash 回潮",
             lambda t: dict(t, prefs=t["prefs"] + '\nappend(" · 指纹 ")\n')),
            ("整宽假按钮回潮",
             lambda t: dict(t, main=t["main"] + '\nText("后台运行设置（系统电池优化）")\n')),
            ("只读假信息行回潮",
             lambda t: dict(t, main=t["main"] + '\nText("清理音频缓存", "需确认"\n')),
            ("LLM 死常量回潮",
             lambda t: dict(t, pipe=t["pipe"] + '\nconst val LLM = "x.gguf"\n')),
            ("错误提示指向已删功能",
             lambda t: dict(t, pipe=t["pipe"] + '\n请点设置页复制诊断信息发我\n')),
            ("真清理入口被删",
             lambda t: dict(t, main=t["main"].replace('Text("清理 ›"', 'Text("已清理"'))),
            ("真点击被降级为只读",
             lambda t: dict(t, main=t["main"].replace('onClick: (() -> Unit)? = null', ''))),
            ("通知与权限被删",
             lambda t: dict(t, main=t["main"].replace('SettingsSection("通知与权限"', 'SettingsSection("旧分区"'))),
            ("拦截文案回潮",
             lambda t: dict(t, pipe=t["pipe"] + '\n这个模式的合成界面还没做完\n')),
        ]
        bad = 0
        for name, mutate in injections:
            if not check(mutate(files)):
                bad += 1
                errs.append("反向注入漏放: " + name)
        print("反向注入 %d/%d 全拦下" % (len(injections) - bad, len(injections)))
    if errs:
        print("R87 FAIL", len(errs))
        [print(" -", e) for e in errs]
        return 1
    print("R87 文案净化 / 零残留 / 守恒：PASS")
    return 0

if __name__ == "__main__":
    sys.exit(main())
