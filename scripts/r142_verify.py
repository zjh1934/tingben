# -*- coding: utf-8 -*-
"""R142 · 批次 F「浮层返回 / 合成前调音 / 章节批量 / 开发日志 / 产物分层」验证。

五件落地：
  F1 输入朗读 / 全屏播放器是覆盖式浮层，原来系统返回被全局 goBack 吃掉，
     底层切走但浮层还盖着 —— 补浮层专属 BackHandler（注册在全局之后）
  F2 调音从「一级页」改成「点合成之前弹出的窗口」，带保存 + 关闭叉 +
     返回键 + 未保存确认；独立页入口保留（35 个脚本绑着 Screen 枚举，不硬删）
  F3 章节列顶部常驻「全选 / 反选 / 清空」，勾选热区 24dp → 52dp
  F4 日志定位订正：不是给用户看的 txt，是给开发者排查的完整 Markdown +
     一键复制摘要（含环境、运行快照、崩溃栈、全量事件时间线）
  F5 APK 门禁「开发者资料零进包」；1189 行历史回执搬进 dev/ 归档，
     现行文档瘦到 200 行出头，且不参与构建、不进只读真值源

用法：python3 scripts/r142_verify.py [--selftest]
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
    "main": ui_sources.source(APP),
    "hub": APP / "ChapterHub.kt",
    "icons": APP / "PlayIcons.kt",
    "eventlog": APP / "diag" / "EventLog.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
    "audit": ROOT / "docs/修复清单_按优先级.md",
    "archive": ROOT / "dev/历史回执.md",
    "gradle": ROOT / "app/build.gradle.kts",
}

POS = {
    "main": [
        "BackHandler(enabled = playerOpen)",
        "BackHandler(enabled = inputReadOpen)",
        "Box(Modifier.size(44.dp).clickable { onBack() }",
        "IconBack(Primary)",
        'ActivityResultContracts.CreateDocument("text/markdown")',
        "EventLog.exportBundle(c)", "EventLog.exportSummary(c)",
        "diagClipboard.setText(AnnotatedString(txt))",
        '"一键复制摘要"', '"导出完整 .md"', '"开发日志"',
        "var mixerOpen by remember { mutableStateOf(false) }",
        "if (mixerOpen) MixerDialog(c, onClose =",
        # R155 · 语义化：调音弹层仍复用整套 MixerScreen，但 R155 给关闭叉
        # 补了浅底 + 主色、并新增 onRegisterLeave 参数。原来那两条绑死了
        # 参数个数和灰色 IconButton，属于「改一处 UI 就得哄脚本」的旧债。
        "MixerScreen(c, onBack = onClose, onClose = onClose,",
        "onRegisterLeave = { requestLeave = it }",
        "IconClose(Color.White, Modifier.size(20.dp))",
        ".clickable { leave() }",
        "if (onClose != null) BackHandler(enabled = !dirty) { onClose?.invoke() }",
        "showLeave = false; discardAll(); (onClose ?: onBack)()",
    ],
    "gradle": ["val signAlias = System.getenv(\"TINGBEN_KEY_ALIAS\")",
               "keyAlias = signAlias", "!signAlias.isNullOrBlank()"],
    "icons": [
        "internal fun IconBack(", "drawPath(p, color = tint",
        "import androidx.compose.foundation.layout.size",
        "@Composable\ninternal fun IconClose(",
    ],
    "hub": [
        "按顺序播放",
        '"全选章节"',
        'onLong = { if (!multiMode) menuChapter = r.index }',
        'BackHandler(enabled = multiMode) { exitMulti() }',
        "Box(Modifier.size(34.dp)",
        "SelectBox(checked = isChecked",
        "onTap: (SegRow) -> Unit",
    ],
    "eventlog": [
        "fun exportBundle(c: Context): String", "fun exportSummary(c: Context): String",
        "## 环境", "## 运行快照", "事件日志（全量）", "详细日志（全量）",
        "给开发者排查用的", "BuildConfig.VERSION_NAME",
        "TaskResume.active(c)", "PlayQueue.previewState.value",
    ],
    "ci": [
        "开发者资料（文档/脚本/回执/截图）",
        "/(docs|scripts|dev|contracts|真机截图)/",
        "python3 scripts/r142_verify.py --selftest",
    ],
    "audit": [
        "| F | 浮层返回 / 合成前调音 / 章节批量 / 开发日志 / 产物分层 | R142 |",
        "真机待验清单（定稿 · 26 项",
        "dev/历史回执.md`，**不读**",
    ],
    "archive": ["# 归档 · 历史批次回执", "## 批次 E", "不参与现行自检"],
}

BAN = {
    "main": [
        'ActivityResultContracts.CreateDocument("text/plain")',
        '"导出诊断日志"',
        'Text("‹", fontSize = 20.sp, color = Primary,\n                modifier = Modifier.clickable { onBack() }',
    ],
    "eventlog": ["末尾 400 行", "末尾 800 行", "自行决定发给谁"],
    "icons": ["@Composable\n@Composable\n"],
    "gradle": ["keyAlias = keyAlias"],
    "audit": ["## 批次 A ·", "## 补漏轮"],
}


def load():
    # R143 · CI 里仓库外没有沙盒卡片；任何读不到的文件回空串，
    # 绝不允许「本地绿 / CI 红」因为一个文件不存在就整脚本崩掉。
    out = {}
    for k, p in FILES.items():
        try:
            out[k] = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            out[k] = ""
    return out


def logic_cases(f):
    bad = []
    m = f["main"]
    # ① 浮层 BackHandler 必须在全局返回之后注册（Compose 是后注册优先）
    g = m.find("BackHandler(enabled = canGoBack(screen)")
    p = m.find("BackHandler(enabled = playerOpen)")
    i = m.find("BackHandler(enabled = inputReadOpen)")
    if not (0 <= g < p < i):
        bad.append("浮层返回键注册顺序不对，会重新被全局 goBack 吃掉")
    # ② 调音弹层：dirty / !dirty 两个 BackHandler 互斥覆盖，且离开确认走 onClose
    if "BackHandler(enabled = dirty) { showLeave = true }" not in m:
        bad.append("未保存确认的返回守卫丢了")
    if "if (onClose != null) BackHandler(enabled = !dirty) { onClose?.invoke() }" not in m:
        bad.append("弹层无改动时的返回守卫丢了")
    if "showLeave = false; discardAll(); (onClose ?: onBack)()" not in m:
        bad.append("「丢弃并离开」没有关弹层")
    if "IconClose(Color.White, Modifier.size(20.dp))" not in m or ".clickable { leave() }" not in m:
        bad.append("弹层没有关闭叉")
    if "MixerScreen(c, onBack = onClose, onClose = onClose," not in m:
        bad.append("弹层没有复用整套调音台")
    if "onRegisterLeave = { requestLeave = it }" not in m:
        bad.append("弹层没把统一 leave 注册给遮罩空白退出")
    # ③ 结构性 Kotlin 检查（run 130 真编译抓到的那类：静态也能拦）
    for kt in (ROOT / "app/src/main/java/com/tingben/app").rglob("*.kt"):
        ls = kt.read_text(encoding="utf-8", errors="replace").splitlines()
        for i, l in enumerate(ls):
            # R247 · UiIcons.kt 在 Canvas lambda 里用了缩进 8 的局部 fun ln/ring
            # 封装 drawLine，它们不需要也不允许标 @Composable。判据只看
            # 顶层 / 成员级函数（缩进 < 8），局部辅助函数跳过；不算放宽：
            # 真绘制 composable 全在顶层，重复注解与漏注解照样拦。
            if (len(l) - len(l.lstrip())) >= 8:
                continue
            if not (l.startswith("private fun ") or l.startswith("internal fun ")
                    or l.startswith("fun ")):
                continue
            pre = "\n".join(ls[max(0, i - 3):i])
            n = pre.count("@Composable")
            if n > 1:
                bad.append("%s:%d 同一函数上重复 @Composable" % (kt.name, i + 1))
            # 函数体里调了 Canvas/drawLine/Text 等 Composable，却没有 @Composable
            if n == 0:
                # R262 · 固定 14 行窗口会越过下一个顶层 fun，把 PlayerCover
                # 的 Canvas( 算到前面 mmss() 头上。截到下一个声明为止。
                body_ls = []
                for j in range(i, min(i + 14, len(ls))):
                    if j > i and re.match(r'(?:private |internal |public )?fun ', ls[j]):
                        break
                    body_ls.append(ls[j])
                body = "\n".join(body_ls)
                if "Canvas(" in body or "drawPath(" in body or "drawLine(" in body:
                    bad.append("%s:%d 绘制函数丢了 @Composable" % (kt.name, i + 1))
    icons = (ROOT / "app/src/main/java/com/tingben/app/PlayIcons.kt").read_text(encoding="utf-8")
    if "modifier.size(" in icons and "foundation.layout.size" not in icons \
            and "foundation.layout.*" not in icons:
        bad.append("PlayIcons 用了 Modifier.size 却没导入 layout.size")

    # ④ 运行时源码树下零开发者文件
    dev_files = [str(p) for p in (ROOT / "app/src/main").rglob("*")
                 if p.is_file() and p.suffix.lower() in
                 (".md", ".py", ".log", ".jpeg", ".jpg")]
    if dev_files:
        bad.append("app/src/main 下混入开发者文件: " + ", ".join(dev_files[:3]))
    # ⑤ 现行文档瘦了、归档完整、且归档不被当现行读
    cur_lines = len(f["audit"].splitlines())
    arc_lines = len(f["archive"].splitlines())
    if not (cur_lines < 260 and arc_lines > 900):
        bad.append("分层没生效：现行 %d 行 / 归档 %d 行" % (cur_lines, arc_lines))
    else:
        print("文档分层：现行 %d 行 / 归档 %d 行" % (cur_lines, arc_lines))
    if "dev/历史回执" in f["audit"] and "不读" not in f["audit"]:
        bad.append("现行文档把归档列进了只读真值源")
    # R143 · 防复发：验证脚本不许再依赖仓库外的沙盒文件（本地绿 / CI 红）
    me = pathlib.Path(__file__).name
    # R242 · preflight.py 是发版前置工具：正向只在沙盒手动跑（读仓库外工具/凭据），
    # CI 仅挂 --selftest（纯内存、不访问任何仓库外路径），不属于「CI 里会红的依赖」。
    # 补一条反向硬判据：CI 若挂了 preflight 正向，仍然当场红，判据不放宽。
    outside = [q.name for q in (ROOT / "scripts").glob("*.py")
               if q.name != me and q.name != "preflight.py"
               and "ROOT.parent" in q.read_text(encoding="utf-8", errors="replace")]
    if re.search(r"run: python3 scripts/preflight\.py\s*$", f["ci"], re.M):
        bad.append("CI 挂了 preflight 正向（会读仓库外路径）")
    if outside:
        bad.append("验证脚本依赖仓库外路径: " + ", ".join(outside[:3]))
    # ⑤ 日志必须是全量 + 可复制，不是截断的诊断 txt
    if "read().lines().takeLast" in f["eventlog"] and "exportSummary" not in f["eventlog"]:
        bad.append("日志又退回截断版")
    if "事件日志（全量）" not in f["eventlog"] or "详细日志（全量）" not in f["eventlog"]:
        bad.append("日志没给全量时间线")
    print("逻辑用例 %s" % ("通过" if not bad else "有失败"))
    return bad


def check(f):
    e = []
    for key, needles in POS.items():
        if key == "archive" and not FILES["archive"].exists():
            e.append("归档文件不存在")
            continue
        for s in needles:
            if s not in f[key]:
                e.append("正向缺失[%s]: %s" % (key, s[:72]))
    for key, needles in BAN.items():
        for s in needles:
            if s in f[key]:
                e.append("旧形态回潮[%s]: %s" % (key, s[:60]))
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了，先修基线"
    logic_bad = len(logic_cases(f))
    orig = dict(f)
    cuts = [
        ("浮层返回键被摘", "main",
         "BackHandler(enabled = inputReadOpen)", ""),
        ("播放器返回键被摘", "main",
         "BackHandler(enabled = playerOpen)", ""),
        ("输入朗读回到字符箭头", "icons", "internal fun IconBack(", ""),
        ("合成前调音入口被摘", "main", "if (mixerOpen) MixerDialog(c, onClose =", ""),
        ("调音弹层被摘", "main",
         "MixerScreen(c, onBack = onClose, onClose = onClose,", ""),
        ("关闭叉被摘", "main", "IconClose(Color.White, Modifier.size(20.dp))", ""),
        ("遮罩空白退出被摘", "main",
         "onRegisterLeave = { requestLeave = it }", ""),
        ("未保存返回守卫被摘", "main",
         "if (onClose != null) BackHandler(enabled = !dirty) { onClose?.invoke() }", ""),
        ("章节全选被摘", "hub",
         '"全选章节"', ""),
        ("章节反选被摘", "hub",
         'onLong = { if (!multiMode) menuChapter = r.index }', ""),
        ("勾选热区缩回", "hub", "Box(Modifier.size(34.dp)", "Box(Modifier.size(24.dp)"),
        ("日志退回 txt", "main",
         'ActivityResultContracts.CreateDocument("text/markdown")',
         'ActivityResultContracts.CreateDocument("text/plain")'),
        ("一键复制被摘", "main", "diagClipboard.setText(AnnotatedString(txt))", ""),
        ("日志退回截断", "eventlog", "事件日志（全量）", "事件日志（末尾 400 行）"),
        ("APK 门禁被摘", "ci", "开发者资料（文档/脚本/回执/截图）", ""),
        ("文档分层被摘", "audit", "| F | 浮层返回 / 合成前调音 / 章节批量 / 开发日志 / 产物分层 | R142 |", ""),
        ("CI 漏挂 r142", "ci", "python3 scripts/r142_verify.py --selftest", ""),
        ("图标重复注解", "icons", "@Composable\ninternal fun IconBack(", "@Composable\n@Composable\ninternal fun IconBack("),
        ("IconClose 丢注解", "icons", "@Composable\ninternal fun IconClose(", "internal fun IconClose("),
        ("size 扩展没导入", "icons", "import androidx.compose.foundation.layout.size", ""),
        ("签名别名同名遮蔽", "gradle", "keyAlias = signAlias", "keyAlias = keyAlias"),
    ]
    bad = 0
    try:
        for name, key, needle, repl in cuts:
            x = dict(f)
            x[key] = f[key].replace(needle, repl)
            if not check(x):
                bad += 1
                print("  反向注入漏放:", name)
    finally:
        for k, v in orig.items():
            try:
                if FILES[k].read_text(encoding="utf-8", errors="replace") != v:
                    FILES[k].write_text(v, encoding="utf-8")
            except (OSError, UnicodeError):
                FILES[k].write_text(v, encoding="utf-8")
    total = len(cuts)
    print("反向注入 %d/%d 全拦下" % (total - bad, total))
    return bad + logic_bad


def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    e = check(load()) + logic_cases(load())
    if e:
        print("R142 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R142 批次F：浮层返回 / 合成前调音 / 章节批量 / 开发日志 / 产物分层：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
