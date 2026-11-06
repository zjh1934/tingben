# -*- coding: utf-8 -*-
"""R77 · P1d 底层残留清理验证。

正向：删文件 / 零残留 / 双指纹迁移 / 守恒 / 界面零改动 / CI 已挂。
--selftest：8 类反向注入，每类都必须被拦下（防「断言永远 PASS」）。
"""
import pathlib, shutil, sys, tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
CORE = "app/src/main/java/com/tingben/app/core"
MAIN = "app/src/main/java/com/tingben/app/MainActivity.kt"

def txt(root, rel):
    p = root / rel
    return p.read_text(encoding="utf-8") if p.exists() else ""

def check(root) -> list:
    e = []
    def ck(ok, msg):
        if not ok: e.append(msg)

    # ① 两个文件物理删除
    for n in ("CastPresets.kt", "VoiceCast.kt"):
        ck(not (root / CORE / n).exists(), f"① {n} 仍在源码树")

    kts = list((root / "app/src/main/java").rglob("*.kt"))
    allkt = "\n".join(p.read_text(encoding="utf-8") for p in kts)

    # ② 旧符号零残留（legacy 兼容读只用字符串常量，不出现函数/字段形态）
    for bad, why in [
        ("VoiceCast.", "② VoiceCast 调用残留"),
        ("CastPresets.", "② CastPresets 调用残留"),
        ("KEY_CAST_ENABLED", "② castEnabled 设置键残留"),
        ("KEY_CAST_SEEDED", "② castSeeded 设置键残留"),
        ("setCastEnabled", "② setCastEnabled 残留"),
        ("Prefs.castEnabled", "② Prefs.castEnabled 残留"),
        ("castSeeded(", "② castSeeded 残留"),
        ("VOICE_UNMAPPED", "② 角色未映射事件残留"),
        ("val castEnabled", "② MixerDraft 角色开关字段残留"),
        ("角色音色", "② 界面旧页名残留"),
        ("角色表=${", "② 日志里的角色表残留"),
    ]:
        ck(bad not in allkt, why)

    prefs = txt(root, CORE + "/Prefs.kt")
    pipe = txt(root, CORE + "/Pipeline.kt")
    draft = txt(root, CORE + "/MixerDraft.kt")

    # ③ 新指纹三项 + 旧指纹四项（迁移专用）
    ck("fun voiceStamp(c: Context): String" in prefs, "③ voiceStamp 不存在")
    ck("VoiceCast.hash(c)" not in prefs, "③ voiceStamp 仍含角色表项")
    ck("ttsKnobs(c).hash()\n    ).joinToString(\"||\"))" in prefs,
       "③ voiceStamp 不是三项收尾")
    ck("fun legacyVoiceStamp(c: Context): String" in prefs, "③ 缺迁移用旧指纹")
    ck("legacyCastHash(c)" in prefs and '"off"' in prefs, "③ 旧指纹算法不完整")
    ck('getBoolean("castEnabled", true)' in prefs, "③ 缺旧设置键兼容读")
    ck("import java.io.File" in prefs, "③ 缺 File import")

    # ④ 双指纹比对与迁移事件
    ck("old != voiceStamp && old != legacyStamp" in pipe, "④ 双指纹比对被改回单比对")
    ck("VOICE_STAMP_MIGRATE" in pipe, "④ 缺迁移事件")
    ck("Prefs.legacyVoiceStamp(c)" in pipe, "④ Pipeline 没取旧指纹")
    ck("val castOn" not in pipe and "VoiceCast.resolve" not in pipe,
       "④ 合成循环仍在查角色表")
    ck("VoiceProfile.buildWithReport(globalVoice," in pipe,
       "④ 身份层没直接用全局音色")

    # ⑤ 守恒：仍活的类型一行没删
    for n in ("NonVerbal.kt", "SegPlan.kt", "Workbench.kt", "VoiceProfile.kt"):
        ck((root / CORE / n).exists(), f"⑤ 误删仍在用的 {n}")
    for k, v in [("MAX_NEW_TOKENS = 2048", ""), ("TEMPERATURE = 0.9f", ""),
                 ("TOP_K = 50", ""), ("TOP_P = 1.0f", ""),
                 ("REPETITION_PENALTY = 1.05f", ""), ("SEED = 42L", "")]:
        ck(k in prefs, f"⑤ 采样默认值被改：{k}")
    ck("const val INSTRUCT_TEMPLATE_VERSION = 4" in txt(root, CORE + "/VoiceProfile.kt"),
       "⑤ instruct 模板版本被改")
    ck("NonVerbal.apply(ann)" in pipe, "⑤ 拟声表达被顺手删掉")
    ck("角色映射" not in prefs, "⑤ 设置摘要仍报角色映射")
    ck("Prefs.setNonVerbal(c, d.nonVerbal)" in draft, "⑤ 调音台提交出口被改")

    # ⑥ 界面零改动
    main = txt(root, MAIN)
    ck("VoiceCast" not in main and "CastPresets" not in main, "⑥ 主界面残留旧模块引用")

    # ⑦ CI 挂上本轮验证
    ci = txt(root, ".github/workflows/ci.yml")
    ck("scripts/r77_verify.py" in ci, "⑦ CI 未挂 r77_verify")
    ck("scripts/r77_stamp_migration.py" in ci, "⑦ CI 未挂迁移对拍")
    return e

# ── 反向注入 ────────────────────────────────────────────────────────────────
INJECT = [
    ("恢复 VoiceCast.kt", lambda r: (r / CORE / "VoiceCast.kt").write_text(
        "package com.tingben.app.core\nobject VoiceCast {}\n", encoding="utf-8")),
    ("voiceStamp 插回角色表项", lambda r: (r / CORE / "Prefs.kt").write_text(
        txt(r, CORE + "/Prefs.kt").replace(
            "        voiceDescription(c),\n        ttsKnobs(c).hash()\n    ).joinToString(\"||\"))",
            "        voiceDescription(c),\n        VoiceCast.hash(c),\n        ttsKnobs(c).hash()\n    ).joinToString(\"||\"))", 1), encoding="utf-8")),
    ("双比对改回单比对", lambda r: (r / CORE / "Pipeline.kt").write_text(
        txt(r, CORE + "/Pipeline.kt").replace(
            "old != voiceStamp && old != legacyStamp", "old != voiceStamp", 1), encoding="utf-8")),
    ("删掉 legacyVoiceStamp", lambda r: (r / CORE / "Prefs.kt").write_text(
        txt(r, CORE + "/Prefs.kt").replace(
            "    fun legacyVoiceStamp(c: Context): String", "    private fun unusedLegacy(c: Context): String", 1), encoding="utf-8")),
    ("Pipeline 恢复 castEnabled", lambda r: (r / CORE / "Pipeline.kt").write_text(
        txt(r, CORE + "/Pipeline.kt").replace(
            "        val knobs = Prefs.ttsKnobs(c)",
            "        val castOn = Prefs.castEnabled(c)\n        val knobs = Prefs.ttsKnobs(c)", 1), encoding="utf-8")),
    ("MixerDraft 恢复角色开关字段", lambda r: (r / CORE / "MixerDraft.kt").write_text(
        txt(r, CORE + "/MixerDraft.kt").replace(
            "    val nonVerbal: Boolean = true,",
            "    val nonVerbal: Boolean = true,\n    val castEnabled: Boolean = true,", 1), encoding="utf-8")),
    ("恢复 VOICE_UNMAPPED 事件", lambda r: (r / CORE / "Pipeline.kt").write_text(
        txt(r, CORE + "/Pipeline.kt").replace(
            "        val wavs = mutableListOf<File>()",
            '        EventLog.log(EventLog.Kind.PIPELINE, "VOICE_UNMAPPED", "角色未映射")\n'
            "        val wavs = mutableListOf<File>()", 1), encoding="utf-8")),
    ("设置摘要恢复角色映射", lambda r: (r / CORE / "Prefs.kt").write_text(
        txt(r, CORE + "/Prefs.kt").replace(
            '        append(" · instruct v")',
            '        append(" · 角色映射 开")\n        append(" · instruct v")', 1), encoding="utf-8")),
]

def selftest():
    bad = 0
    with tempfile.TemporaryDirectory() as td:
        for name, fn in INJECT:
            r = pathlib.Path(td) / name.replace(" ", "_")
            shutil.copytree(ROOT / "app", r / "app")
            shutil.copytree(ROOT / ".github", r / ".github")
            fn(r)
            errs = check(r)
            ok = len(errs) > 0
            print(("  PASS 拦下 " if ok else "  FAIL 漏放 ") + name +
                  (f" · {errs[0]}" if ok else ""))
            if not ok: bad += 1
    print(f"反向注入 {len(INJECT)} 类，漏放 {bad}")
    return bad

if __name__ == "__main__":
    errs = check(ROOT)
    for x in errs: print("  FAIL", x)
    print(f"正向检查：{'全绿' if not errs else str(len(errs)) + ' 条失败'}")
    rc = 1 if errs else 0
    if "--selftest" in sys.argv:
        rc |= 1 if selftest() else 0
    sys.exit(rc)
