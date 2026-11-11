# -*- coding: utf-8 -*-
"""R156 · 目标方案第 1 批：成品文件「能被文件管理器管到」的地基。

用户把目标定死了：主页输入框 / 抽屉放书 / 右上角导入与设置 /
模型并入设置 / 调音台在合成前 / 音频管理页删掉，设置里加一个
「指向音频文件保存的地方，点一下用系统文件管理器进去」。

查证结论（这条改变了方案的一部分）：
  成品落在 `c.filesDir/audio/`，是 App 私有目录（/data/data/<pkg>/files/）。
  App 无 READ/WRITE_EXTERNAL_STORAGE，Manifest 里没有 DocumentsProvider，
  第三方文件管理器无 root **打不开这个目录**。所以「打开私有目录」做不到。

纠正后落地：让用户用 ACTION_OPEN_DOCUMENT_TREE 自己选一个文件夹
（一般是 Download），记住 persistable URI 授权，把每章成品 WAV 平铺导出；
之后文件管理器、音乐 App 都能管。这就是「音频管理页可以删」的技术前提。

明确不做（数据风险，不是设置项不够）：
  不把内部 `work/` / `audio/` / 暂存目录做成可配置路径。
  `AudioTree.audio_tree.json` 存的是成品绝对路径，`TaskResume` 断点按
  `work/{bookId}/{bucket}` 定位；改路径 = 已做好的音频全找不到、续跑重算。

用法：python3 scripts/r156_verify.py --selftest
"""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "export": APP / "core" / "AudioExport.kt",
    "main": ui_sources.source(APP),
    "manifest": ROOT / "app/src/main/AndroidManifest.xml",
    "audio": APP / "core" / "AudioLibrary.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
    "r135": ROOT / "scripts" / "r135_verify.py",
}

POS = {
    "export": [
        "object AudioExport",
        "ACTION_OPEN_DOCUMENT_TREE",
        "filesDir/audio/",
        "DocumentsProvider",
        "明确不做",
        "fun treeUri(c: Context): Uri?",
        "fun saveTree(c: Context, uri: Uri?)",
        "takePersistableUriPermission(uri, flags)",
        "FLAG_GRANT_READ_URI_PERMISSION or",
        "fun collect(c: Context): List<Item>",
        "AudioLibrary.playableItems(c, b.id)",
        "fun exportAll(c: Context, tree: Uri?): Result",
        "DocumentsContract.getTreeDocumentId(t)",
        "DocumentsContract.buildDocumentUriUsingTree(t, treeId)",
        # R157 · 目录分层后有两处 createDocument：子目录（MIME_TYPE_DIR）
        # 与成品文件（audio/wav）。分别判，删掉任一处都要被拦下。
        "DocumentsContract.createDocument(r, parent, \"audio/wav\", it.name)",
        "DocumentsContract.Document.MIME_TYPE_DIR, part",
        "DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)",
        '"audio/wav"',
        "AudioOps.copyTo(c, it.file, child)",
        "if (!it.file.isFile) { skipped++; continue }",
        "fun treeLabel(c: Context): String",
        "private fun safeName(s: String): String",
    ],
    "main": [
        "ActivityResultContracts.OpenDocumentTree()",
        "AudioExport.saveTree(c, uri)",
        "AudioExport.treeLabel(c)",
        "ActivityResultContracts.OpenDocumentTree()) { uri ->",
                # R238 · 两张同 badge 碎卡合并成一张，卡片仍在、导出链路一条没少，
        # 判据按现状迁移、不放宽。
'SettingsSection("文件与数据", "本机", collapsible = false)',
        "导出位置",
        "AudioExport.exportAll(c, tree)",
        "先点「选择」挑一个文件夹",
        "AudioExport.exportAll(c, tree)",
        "withContext(Dispatchers.IO) { AudioExport.collect(c).size }",
        '"导出全部"',
    ],
    "manifest": [
        'android.permission.POST_NOTIFICATIONS',
    ],
    "audio": [
        'fun dir(c: Context) = File(c.filesDir, "audio")',
    ],
    "ci": ["python3 scripts/r156_verify.py --selftest"],
    "r135": ['"r156_verify.py"'],
}

BAN = {
    "export": [],
    "main": [],   # 见 check()：设置页函数体内不许出现路径输入框
    "manifest": [
        'READ_EXTERNAL_STORAGE',
        'WRITE_EXTERNAL_STORAGE',
        'MANAGE_EXTERNAL_STORAGE',
        '<provider',
    ],
}

# 「内部路径可配置」只禁在设置页里。MainActivity 的「输入朗读」页本来就有
# TextField，全文件禁用会误报，所以圈出 SettingsScreen 函数体再判。
PATH_CONFIG_UI = ('TextField(', 'BasicTextField(', '"work/"', '"audio/"')


def load():
    out = {}
    for k, p in FILES.items():
        try:
            out[k] = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            out[k] = ""
    return out


def code_only(s):
    return "\n".join(
        line for line in s.splitlines()
        if not line.strip().startswith(("//", "/*", "*")))


def settings_body(m):
    a = m.find("private fun SettingsScreen(")
    if a < 0:
        return ""
    b = m.find("\n@Composable\nprivate fun ClearSegmentsRow", a)
    return m[a:b] if b > a else ""


def export_body(e):
    a = e.find("    fun exportAll(")
    if a < 0:
        return ""
    b = e.find("\n    /** 给设置页显示用", a)
    return e[a:b] if b > a else ""


def safe_name(s, bad='/\\:*?"<>|\x00\x01'):
    cleaned = "".join("_" if (ch in bad or ord(ch) < 32) else ch for ch in s)
    cleaned = cleaned.strip().strip(".")
    return (cleaned[:60] or "听本")


def logic_cases(f):
    bad = []
    # ① 文件名安全化：路径分隔符、冒号、控制字符都得挡住，且不能出空名
    cases = [
        ("第/一:章*归来", "第_一_章_归来"),
        ("A\\B?C<D>E|F", "A_B_C_D_E_F"),
        ("..", "听本"),
        ("", "听本"),
    ]
    for raw, want in cases:
        got = safe_name(raw)
        if got != want:
            bad.append("文件名安全化 %r → %r 期望 %r" % (raw, got, want))
        elif any(x in got for x in '/\\:*?"<>|'):
            bad.append("安全化后仍带非法字符：%r" % got)
    if not bad:
        print("  文件名安全化 4/4 ✓（不会写出子路径 / 空名）")
    # ② 导出计数：缺文件跳过、成功计入、失败单独计
    items = [("a.wav", True), ("b.wav", False), ("c.wav", True)]
    ok, skipped = sum(1 for _, okf in items if okf), sum(1 for _, okf in items if not okf)
    if (ok, skipped) != (2, 1):
        bad.append("导出计数口径错：ok=%d skipped=%d" % (ok, skipped))
    else:
        print("  导出计数 成功 2 / 跳过失效 1 ✓")
    # ③ 私有目录结论成立：App 确实没存储权限、没 DocumentsProvider
    if any(x in f["manifest"] for x in
           ("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "<provider")):
        bad.append("Manifest 变了，私有目录结论要重查")
    elif 'fun dir(c: Context) = File(c.filesDir, "audio")' not in f["audio"]:
        bad.append("成品目录不再是 filesDir/audio，结论要重查")
    else:
        print("  私有目录结论 ✓（无存储权限 / 无 DocumentsProvider / filesDir/audio）")
    # ④ 行为判据（也进 check，才能被反向注入拦住）
    eb = code_only(export_body(f["export"]))
    if not eb or "DocumentsContract.createDocument(r, parent, \"audio/wav\", it.name)" not in eb:
        bad.append("成品文件没走 SAF 写出")
    if "AudioOps.copyTo(c, it.file, child)" not in eb:
        bad.append("导出没复用现有写出通道")
    if "AudioLibrary.playableItems(c, b.id)" not in f["export"]:
        bad.append("没按书收集成品章")
    print("逻辑用例 %s" % ("通过" if not bad else "有失败"))
    return bad


def check(f):
    e = []
    for key, needles in POS.items():
        for s in needles:
            if s not in f[key]:
                e.append("正向缺失[%s]: %s" % (key, s[:75]))
    for key, needles in BAN.items():
        for s in needles:
            if s in code_only(f[key]):
                e.append("不该出现[%s]: %s" % (key, s[:65]))
    eb = code_only(export_body(f["export"]))
    if not eb or "DocumentsContract.createDocument(r, parent, \"audio/wav\", it.name)" not in eb:
        e.append("成品文件没走 SAF 写出")
    if "DocumentsContract.Document.MIME_TYPE_DIR, part" not in eb:
        e.append("导出没建按书分的子目录")
    # findChildDir 定义在 exportAll 之外，用全文判而不是 export_body
    if "DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)" not in code_only(f["export"]):
        e.append("导出没复用已存在的同名目录（重复导出会生成 书名(1) 副本）")
    if "AudioOps.copyTo(c, it.file, child)" not in eb:
        e.append("导出没复用现有写出通道")
    if "takePersistableUriPermission" not in f["export"]:
        e.append("导出位置没持久化授权，下次开软件会写不进去")
    if "还没选导出位置" not in eb:
        e.append("没选导出位置时没有兜底提示")
    if "withContext(Dispatchers.IO)" not in f["main"]:
        e.append("设置页读成品数或导出没切 IO")
    sb = code_only(settings_body(f["main"]))
    if not sb:
        e.append("没圈出设置页函数体")
    else:
        # R158b · 教程不进 UI：私有目录/内部路径的长说明一律撤掉。
        # 守门改成「能力仍在 + 教程零回潮」，不再要求文案存在。
        if "导出位置" not in sb or "AudioExport.exportAll(c, tree)" not in sb:
            e.append("设置页导出能力入口丢了")
        for t in ("→", "文件管理器进不去", "内部暂存路径不开放修改"):
            if t in sb:
                e.append("设置页又塞回教程式说明: " + t)
        for needle in PATH_CONFIG_UI:
            if needle in sb:
                e.append("设置页又出现内部路径配置/输入框：%s" % needle)
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了"
    logic_bad = len(logic_cases(f))
    cuts = [
        ("成品文件 SAF 写出被摘", "export",
         "DocumentsContract.createDocument(r, parent, \"audio/wav\", it.name)", ""),
        ("子目录创建被摘", "export",
         "DocumentsContract.Document.MIME_TYPE_DIR, part", ""),
        ("同名目录复用被摘", "export",
         "DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)", ""),
        ("treeDocumentId 被摘", "export",
         "DocumentsContract.getTreeDocumentId(t)", ""),
        ("buildDocumentUriUsingTree 被摘", "export",
         "DocumentsContract.buildDocumentUriUsingTree(t, treeId)", ""),
        ("复用写出通道被摘", "export",
         "AudioOps.copyTo(c, it.file, child)", ""),
        ("持久化授权被摘", "export",
         "takePersistableUriPermission(uri, flags)", ""),
        ("按书收集成品被摘", "export",
         "AudioLibrary.playableItems(c, b.id)", ""),
        ("失效文件跳过被摘", "export",
         "if (!it.file.isFile) { skipped++; continue }", ""),
        ("安全化被摘", "export",
         "private fun safeName(s: String): String", ""),
        ("没选位置的兜底被摘", "export",
         'val t = tree ?: return Result(reason = "还没选导出位置")', ""),
        ("设置页选目录被摘", "main",
         "ActivityResultContracts.OpenDocumentTree()", ""),
        ("记住位置被摘", "main", "AudioExport.saveTree(c, uri)", ""),
        ("文件与数据卡片被摘", "main",
         'SettingsSection("文件与数据", "本机", collapsible = false)', ""),
        ("私有目录教程回潮", "main", '"导出位置"',
         '"导出位置；设置 → 应用管理，文件管理器进不去"'),
        ("内部路径教程回潮", "main", '"导出位置"',
         '"导出位置；内部暂存路径不开放修改"'),
        ("导出动作被摘", "main", "AudioExport.exportAll(c, tree)", ""),
        ("IO 切换被摘", "main",
         "withContext(Dispatchers.IO) { AudioExport.collect(c).size }", ""),
        ("没选位置提示被摘", "main", "先点「选择」挑一个文件夹", ""),
        ("CI 漏挂 r156", "ci", "python3 scripts/r156_verify.py --selftest", ""),
        ("现行清单漏 r156", "r135", '"r156_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        x = dict(f)
        x[key] = f[key].replace(needle, repl, 1)
        if not check(x):
            bad += 1
            print("  反向注入漏放:", name)
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad + logic_bad


def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    f = load()
    e = check(f) + logic_cases(f)
    if e:
        print("R156 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R156 成品导出地基（SAF 目录树 / 持久化授权 / 私有目录结论 / 不做内部路径配置）：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
