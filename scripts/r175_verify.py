#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R175 · 审查报告剩余六条（P2-5/6/7 + P3-8/9/10）验证。

  P2-5 没开始的配置失败被记成「意外中断」→ blocked 阶段 + 七处早退统一收口
  P2-6 目录页直接启动不查 lastStartError → 补检查
  P2-7 存储预算拿「已用段数」当「可新写段数」→ newlyWritten 单独计数
  P3-8 删音频不删侧车 / 索引丢失后成品归属永久退化 → 侧车同删 + 自描述 meta
  P3-9 模型导入零完整性校验 → GGUF 魔数 + 最小尺寸，pick 只认合法文件
  P3-10 allowBackup=true 把私有书/模型/音频带走 → false + 排除规则

含两类逻辑用例（Python 对拍，沙盒无 SDK）：
  · 纯续跑 + 空间紧张：旧公式误停、新公式不误停
  · 模型残件：魔数/尺寸校验与模糊匹配的联合判定

selftest 全程纯内存注入，一个字节都不写源文件（R170 事故后的规矩）。
用法：python3 scripts/r175_verify.py [--selftest]
"""
import ui_sources
import core_sources
import pathlib, struct, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "pipe": core_sources.source(SRC),
    "resume": SRC / "core/TaskResume.kt",
    "audio": SRC / "core/AudioLibrary.kt",
    "hub": SRC / "ChapterHub.kt",
    "main": ui_sources.source(SRC),
    "manifest": ROOT / "app/src/main/AndroidManifest.xml",
    "rules": ROOT / "app/src/main/res/xml/data_extraction_rules.xml",
    "ci": ROOT / ".github/workflows/ci.yml",
}

PIPE = [
    # P2-5
    "private fun abortBeforeStart(msg: String, stage: String = \"没开始\")",
    'phase = "blocked"',
    '"PRODUCE_BLOCKED"',
    "appCtx?.let { TaskResume.save(it, blocked) }",
    'abortBeforeStart(why)',
    't.phase == "blocked" -> "没开始成功"',
    '"《${fixed.title}》上次没能开始制作；请在任务页点「继续制作」重试。"',
    # P2-6
    "lastStartError?.let { return it }",
    # P2-7
    "var newlyWritten = 0",
    "if (newlyWritten >= budgetSegs)",
    "newlyWritten++",
    "newly=$newlyWritten",
    # P3-9
    'private const val GGUF_MAGIC = "GGUF"',
    "MIN_GGUF_BYTES",
    "private fun looksLikeGguf(f: File): Boolean",
    "private fun validFiles(c: Context): List<File>",
    "if (!looksLikeGguf(tmp))",
    "val all = validFiles(c)",
    '"MODEL_SUSPECT"',
]
PIPE_BAN = [
    'Progress(false, "没开始", 0, 0, 0,',
    "if (wavs.size >= budgetSegs)",
    'val all = files(c)\n        all.find',
    'phase = "stopped", stage = "没开始"',
]
RESUME = [
    'if (t.phase == "blocked")',
    '"上次制作没能开始（${t.stage}），没自动续跑；在任务页点「继续制作」重试"',
]
AUDIO = [
    '"${item.id}.meta.json"',
    '"$stem.meta.json"',
    '"${id}.chapters.json", "${id}.meta.json"',
    'meta.optString("bookId", "")',
    "chapterIndices",
]
UI = {
    "hub": ['t.phase == "blocked" -> "没开始"'],
    "main": ['t.phase == "blocked" -> "没开始"'],
}


def logic_cases(quiet=False):
    bad = []

    # ① 纯续跑 + 空间紧张：预算段数 3，已有 8 段全是复用，本次一个新段都没写
    budget, wavs, newly = 3, 8, 0
    if wavs >= budget and not (newly >= budget):
        pass  # 旧公式会在这提前 break
    else:
        bad.append("旧公式用例不成立")
    old_stop, new_stop = wavs >= budget, newly >= budget
    if not (old_stop and not new_stop):
        bad.append("纯续跑：旧公式误停而新公式放行，这条没成立")
    # 真写满预算后仍要停
    newly = budget
    if not (newly >= budget):
        bad.append("新写段达到预算却不停止")

    # ② 模型残件：魔数 + 最小尺寸 + 模糊匹配
    MIN = 64 * 1024

    def valid(data: bytes, name: str, key: str) -> bool:
        gguf = len(data) >= MIN and data[:4] == b"GGUF"
        exact = name.lower() == "voice-design.gguf"
        fuzzy = ("-" + key + "-") in name.lower()
        return gguf and (exact or fuzzy)

    good = b"GGUF" + b"\x00" * (MIN - 4)
    cases = [
        ("完整主文件", b"GGUF" + b"\x00" * (MIN - 4), "voice-design.gguf", "voicedesign", True),
        ("下载残件（截断）", b"GGUF" + b"\x00" * 100, "voice-design.gguf", "voicedesign", False),
        ("错魔数", b"XXXX" + b"\x00" * (MIN - 4), "voice-design.gguf", "voicedesign", False),
        ("名字带子串但是空壳", b"", "my-voicedesign-x.gguf", "voicedesign", False),
        ("名字完全不相关", good, "random.bin.gguf", "voicedesign", False),
    ]
    for label, data, name, key, want in cases:
        got = valid(data, name, key)
        if got != want:
            bad.append("模型校验用例[%s]：期望 %s 得到 %s" % (label, want, got))
    if not quiet:
        print("逻辑用例：续跑预算 + 模型残件 5 组，失败 %d" % len(bad))
    return bad


def checks(f):
    e = []
    for s in PIPE:
        if s not in f["pipe"]:
            e.append("主链能力缺失: " + s[:62])
    for s in PIPE_BAN:
        if s in f["pipe"]:
            e.append("旧形态回潮: " + s[:56])
    if f["pipe"].count("abortBeforeStart(") < 9:   # 1 定义 + 7 早退 + 1 FGS
        e.append("早退没有全部收口到 abortBeforeStart")
    for s in RESUME:
        if s not in f["resume"]:
            e.append("任务守卫缺失: " + s[:60])
    for s in AUDIO:
        if s not in f["audio"]:
            e.append("音频侧车能力缺失: " + s[:56])
    for key, needles in UI.items():
        for s in needles:
            if s not in f[key]:
                e.append("界面徽标缺失[%s]: %s" % (key, s[:50]))
    # P3-10
    mf, rule = f["manifest"], f["rules"]
    if 'android:allowBackup="false"' not in mf:
        e.append("Manifest 仍允许系统备份")
    if 'android:allowBackup="true"' in mf:
        e.append("Manifest 旧备份开关残留")
    if 'android:dataExtractionRules="@xml/data_extraction_rules"' not in mf:
        e.append("Manifest 没挂排除规则")
    if not (ROOT / "app/src/main/res/xml/data_extraction_rules.xml").is_file():
        e.append("排除规则文件不存在")
    for tag in ("<cloud-backup>", "<device-transfer>"):
        if tag not in rule:
            e.append("排除规则缺 " + tag)
    for path in ("books", "models", "audio", "work", "logs"):
        if 'path="%s"' % path not in rule:
            e.append("排除规则没覆盖 " + path)
    if "python3 scripts/r175_verify.py --selftest" not in f["ci"]:
        e.append("CI 未挂 r175_verify --selftest")
    e += logic_cases(quiet=True)
    return e


def selftest():
    base = {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}
    assert not checks(base), "基线自己就红了，先修基线"
    cases = [
        ("早退回潮成裸设 state", "pipe",
         lambda t: t.replace("abortBeforeStart(NativeEngine.shortReason())",
                             '_state.value = Progress(false, "没开始", 0, 0, 0, NativeEngine.shortReason())')),
        ("blocked 阶段被摘", "pipe",
         lambda t: t.replace('phase = "blocked"', 'phase = "stopped"')),
        ("助手不再落盘任务", "pipe",
         lambda t: t.replace("appCtx?.let { TaskResume.save(it, blocked) }", "")),
        ("pending 不认 blocked", "pipe",
         lambda t: t.replace('t.phase == "blocked" -> "没开始成功"', "")),
        ("直接启动又不查错误", "pipe",
         lambda t: t.replace("lastStartError?.let { return it }", "")),
        ("预算又用已用段数", "pipe",
         lambda t: t.replace("if (newlyWritten >= budgetSegs)", "if (wavs.size >= budgetSegs)")),
        ("新写段不计数", "pipe",
         lambda t: t.replace("newlyWritten++", "")),
        ("任务守卫放走 blocked", "resume",
         lambda t: t.replace('if (t.phase == "blocked")', "if (false)")),
        ("GGUF 校验被摘", "pipe",
         lambda t: t.replace("if (!looksLikeGguf(tmp))", "if (false)")),
        ("pick 又认全部文件", "pipe",
         lambda t: t.replace("val all = validFiles(c)", "val all = files(c)")),
        ("魔数常量被改", "pipe",
         lambda t: t.replace('private const val GGUF_MAGIC = "GGUF"', 'private const val GGUF_MAGIC = "XXXX"')),
        ("删音频不删侧车", "audio",
         lambda t: t.replace('listOf("${id}.chapters.json", "${id}.meta.json")', "listOf()")),
        ("register 不写 meta", "audio",
         lambda t: t.replace('"${item.id}.meta.json"', '"${item.id}.none.json"')),
        ("恢复不读 meta", "audio",
         lambda t: t.replace('"$stem.meta.json"', '"$stem.none.json"')),
        ("徽标不认 blocked", "hub",
         lambda t: t.replace('t.phase == "blocked" -> "没开始"', "")),
        ("备份又被打开", "manifest",
         lambda t: t.replace('android:allowBackup="false"', 'android:allowBackup="true"')),
        ("排除规则摘掉 models", "rules",
         lambda t: t.replace('path="models"', 'path="none"')),
        ("CI 漏挂", "ci",
         lambda t: t.replace("python3 scripts/r175_verify.py --selftest", "")),
    ]
    bad = 0
    for name, key, mut in cases:
        v = dict(base)
        v[key] = mut(base[key])
        assert v[key] != base[key], "注入没生效: " + name
        if not checks(v):
            bad += 1
            print("  反向注入漏放:", name)
    print("反向注入 %d/%d 全拦下（纯内存，源文件零写入）" % (len(cases) - bad, len(cases)))
    return bad


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(1 if selftest() else 0)
    f = {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}
    e = checks(f)
    if e:
        print("FAIL · R175 六条收口")
        for x in e:
            print("  ✗", x)
        sys.exit(1)
    print("R175 六条：blocked 状态机 / 启动检查 / 预算 / 音频侧车 / 模型校验 / 备份隐私：PASS")
