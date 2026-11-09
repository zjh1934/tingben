# -*- coding: utf-8 -*-
"""R94 · 续跑对拍真值 + 导入失败可恢复 / 大文件保护：正向 / 旧形态零残留 / 守恒 / 反向注入。"""
import ui_sources
import core_sources
import pathlib, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
FILES = {
    "resume": ROOT / "app/src/main/java/com/tingben/app/core/TaskResume.kt",
    "pipe": core_sources.source(ROOT),
    "imp": ROOT / "app/src/main/java/com/tingben/app/core/Importer.kt",
    "main": ui_sources.source(ROOT),
    "chrome": ROOT / "app/src/main/java/com/tingben/app/AppChrome.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
    "parity": ROOT / "scripts/r94_resume_parity.py",
}

def check(f):
    e = []
    r, p, i, m, ch, ci = f["resume"], f["pipe"], f["imp"], f["main"], f["chrome"], f["ci"]
    for s in ['fun expectedModeStamp(c: Context): String',
              'fun invalidationReason(c: Context, t: TaskInfo): String?',
              'mode_stamp.txt', 'voice_stamp.txt',
              'oldVoice != Prefs.legacyVoiceStamp(c)',
              'if (invalidationReason(c, t) != null) return 0']:
        if s not in r: e.append("续跑对拍缺: " + s)
    for s in ['val why = TaskResume.invalidationReason(c, fixed)',
              '分段文字还留着',
              '不用重算。', 'invalidate=$why',
              'val modeStamp = TaskResume.expectedModeStamp(c)']:
        if s not in p: e.append("主链缺: " + s)
    if 'val modeStamp = sha(listOf(' in p: e.append("run 仍在自己算指纹（两处会漂移）")
    if '换过模式或音色输入' not in r: e.append("作废原因文案缺")
    for s in ['const val MAX_BYTES = 64 * 1024 * 1024',
              'const val MAX_TXT_BYTES = 24 * 1024 * 1024',
              'MAX_TXT_BYTES.toLong()',
              'private fun readLimited(s: InputStream, txtLimit: Long, hardLimit: Long): ByteArray',
              'if (total > limit)', 'f.length() > MAX_BYTES',
              "buf[0] == 'P'.code.toByte() && buf[1] == 'K'.code.toByte()",
              'catch (e: OutOfMemoryError)',
              '手机内存不够读这本书，关掉别的应用再试',
              '读文件时出错：', '这个 epub 读不开，文件可能坏了']:
        if s not in i: e.append("导入保护缺: " + s)
    for s in ['java.io.File(p).readBytes()', 'f.readBytes()',
              'openInputStream(uri)?.use { it.readBytes() }']:
        if s in i: e.append("整文件无上限读取仍在: " + s)
    for s in ['var bookImportError by rememberSaveable { mutableStateOf<String?>(null) }',
              'bookImportError = why', 'bookImportError = null',
              'importError = bookImportError',
              'onDismissImportError = { bookImportError = null }',
              'importError: String? = null',
              '上次没导进来', '再选一次文件 ›', '关闭']:
        if s not in m: e.append("失败可恢复缺: " + s)
    for s in ['scripts/r94_verify.py', 'scripts/r94_resume_parity.py']:
        if s not in ci: e.append("CI 未挂: " + s)
    if 'def continue_run(' not in f["parity"]: e.append("对拍脚本缺 run 侧模拟")
    for s in ['internal fun AppDrawerSheet(', 'private fun GearIcon(']:
        if s not in ch: e.append("新壳层守恒失效: " + s)
    if 'SETTINGS, MODELS, CHAPTERS }' not in m: e.append("Screen 枚举失效")
    for s in ['commitAll()', 'ttsSynthesize(', 'verticalScroll(scroll)']:
        if s not in m + p: e.append("主链守恒失效: " + s)
    return e

def load():
    return {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}

def run():
    e = check(load())
    if e:
        print("FAIL"); [print(" -", x) for x in e]; return 1
    print("OK · R94 正向 / 旧形态零残留 / 守恒全过"); return 0

if __name__ == "__main__":
    if "--selftest" in sys.argv:
        f = load()
        cases = [
            ("删指纹真源", "resume", lambda t: t.replace('fun expectedModeStamp(c: Context): String', 'fun gone(')),
            ("realProgress 不先对拍", "resume", lambda t: t.replace('if (invalidationReason(c, t) != null) return 0', '// x')),
            ("删 mode_stamp 预判", "resume", lambda t: t.replace('File(w, "mode_stamp.txt")', 'File(w, "x.txt")')),
            ("删 legacy 双指纹兼容", "resume", lambda t: t.replace('oldVoice != Prefs.legacyVoiceStamp(c)', 'false')),
            ("restore 塌回单文案", "pipe", lambda t: t.replace('val why = TaskResume.invalidationReason(c, fixed)', 'val why: String? = null')),
            ("run 自己算指纹", "pipe", lambda t: t.replace('val modeStamp = TaskResume.expectedModeStamp(c)', 'val modeStamp = sha(listOf("x").joinToString("||"))')),
            ("去掉字节闸门", "imp", lambda t: t.replace('if (total > limit)', 'if (false)')),
            ("闸门退回 64MB", "imp", lambda t: t.replace('MAX_TXT_BYTES.toLong()', 'MAX_BYTES.toLong()')),
            ("去掉 OOM 兜底", "imp", lambda t: t.replace('catch (e: OutOfMemoryError)', 'catch (e: RuntimeException)')),
            ("恢复无上限读取", "imp", lambda t: t.replace('f.inputStream().use { readLimited(it, MAX_TXT_BYTES.toLong(), MAX_BYTES.toLong()) }', 'f.readBytes()')),
            ("失败只弹 Toast", "main", lambda t: t.replace('bookImportError = why', '// x')),
            ("删失败卡", "main", lambda t: t.replace('上次没导进来', '提示')),
            ("CI 摘 r94_verify", "ci", lambda t: t.replace('scripts/r94_verify.py', 'x.py')),
            ("CI 摘对拍", "ci", lambda t: t.replace('scripts/r94_resume_parity.py', 'x.py')),
        ]
        bad = 0
        for name, k, fn in cases:
            g = dict(f); g[k] = fn(f[k])
            if not check(g): bad += 1; print("反向注入未拦下:", name)
        print("反向注入 %d/%d 拦下" % (len(cases)-bad, len(cases)))
        sys.exit(1 if bad else 0)
    sys.exit(run())
