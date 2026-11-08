# -*- coding: utf-8 -*-
"""R158 · 清掉过时硬约束 + 分段格子居中 + 后台权限引导。

用户明确推翻 1.1.1 立下的硬约束：「能拿多大权限就拿多大权限，浮窗也要做，
无障碍也要，这种约束上一轮就该清掉」。这轮把「不做系统级浮窗 / 绝不申请
无障碍」从契约、Manifest、设置页文案、结构守门、接手卡片里清掉，并做成
正式能力（KeepAlive 引导）。

同时用户随口提「小方块靠屏幕左边，没居中」—— 查实两处格子行都是
`Row(fillMaxWidth) + spacedBy`（默认 Start），最后一行不足 8/10 个时
右侧空一大片。R164 再纠正：逐行居中会让不满的行像文字一样飘在中间，
改成固定 10 列网格——整块居中、行内从第 1 列往右排、末行靠左。

调研结论（沙盒根 `听本_后台保活与权限_方案.md`，多源交叉验证）：
权限多 ≠ 保活。SYSTEM_ALERT_WINDOW 不是保活白名单，AccessibilityService
不是官方保活组件。所以：
  · 只走官方标准 Intent（忽略电池优化 / 浮窗 / 应用详情），返回后复检
  · 小米深层自启动页无官方稳定组件，不硬编码；R158b 起图文步骤也不进应用
  · 无障碍「绝不申请」禁令清掉，但当前无真实用途，暂不声明服务
  · R158b 用户「教程啥的真的不要塞进应用里面」：箭头流程、图文步骤、
    种子/倍速长解释、成品目录长文全部撤掉，只留按钮 + 状态 +
    删除确认里的后果告知。守门见 check() 的 tutorial_check()
  · 不做双进程 / 静音音频 / 1px Activity / 定时自唤醒

用法：python3 scripts/r158_verify.py --selftest
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "hub": APP / "ChapterHub.kt",
    "main": APP / "MainActivity.kt",
    "ka": APP / "core" / "KeepAlive.kt",
    "player": APP / "PlayerSheet.kt",
    "manifest": ROOT / "app/src/main/AndroidManifest.xml",
    "contract": ROOT / "docs" / "00_现行契约.md",
    "structure": ROOT / "scripts" / "check_structure.py",
    "ci": ROOT / ".github/workflows/ci.yml",
    "r135": ROOT / "scripts" / "r135_verify.py",
}

POS = {
    "hub": [
        "R163 · 目录页合成一列",
        # R164 · 固定 10 列：整块居中、行内左对齐，不再逐行居中
        "internal const val SEG_GRID_COLUMNS = 10",
        "BoxWithConstraints(",
        "contentAlignment = Alignment.Center",
        "repeat(SEG_GRID_COLUMNS)",
        "Spacer(Modifier.size(cell))",
        "Row(Modifier.width(gridWidth)",
        "R164 · 一排固定 10 列",
    ],
    "main": [
        "private fun PermRow(title: String, granted: Boolean, hint: String, go: () -> Unit)",
        "PermRow(\"忽略电池优化\", batOk,",
        "PermRow(\"显示在其他应用上层\", ovOk,",
        "PermRow(\"应用详情\", true,",
        'SettingsSection("后台运行与权限", "系统", collapsible = false)',
        "KeepAlive.batteryIgnoring(c)",
        "KeepAlive.overlayGranted(c)",
        "KeepAlive.requestIgnoreBattery(c)",
        "KeepAlive.requestOverlay(c)",
        "KeepAlive.openAppDetails(c)",
        "R158b · 用户明确「教程不要塞进应用里」",
        "R158b · 种子语义不进 UI",
        "Lifecycle.Event.ON_RESUME) permTick++",
        "日志可能含书名和正文片段",
    ],
    "ka": [
        "object KeepAlive",
        "旧硬约束",
        "不是保活白名单",
        "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        "ACTION_MANAGE_OVERLAY_PERMISSION",
        "ACTION_APPLICATION_DETAILS_SETTINGS",
        "isIgnoringBatteryOptimizations(c.packageName)",
        "fun overlayGranted(c: Context): Boolean",
        "Settings.canDrawOverlays(c)",
        "resolveActivity(pm)",
        "Uri.parse(\"package:\" + c.packageName)",
        "图文步骤也不进应用",
        "fun snapshot(c: Context): String",
        "不硬编码组件名",
        "AccessibilityService",
    ],
    "manifest": [
        'android.permission.SYSTEM_ALERT_WINDOW',
        "R158 · 旧约束「不申请 SYSTEM_ALERT_WINDOW」已由用户推翻",
        "R161：onTimeout(int,int)",
    ],
    "contract": [
        "R158 · 旧硬约束已由用户推翻",
        "按需申请",
        "权限多 ≠ 保活",
        "不硬编码组件名",
    ],
    "structure": [
        "R158 · 旧硬约束",
        "声明了浮窗权限却没有 KeepAlive 引导入口",
        "canDrawOverlays",
    ],
    "ci": ["python3 scripts/r158_verify.py --selftest"],
    "r135": ['"r158_verify.py"'],
}

# R158b · 教程零进 UI。这些是「教你怎么操作」的痕迹，不是界面文案。
# 「转 M4A」这类功能标签含箭头，用具体流程短语判，不误伤。
TUTORIAL = ["复制地址 →", "按章切分 →", "设置 →", "逐段送模型",
            "例如：", "建议检查", "先补齐", "点一下试听",
            "一次可勾选", "图文步骤"]
ALLOWED_LONG = ("只删", "章号会变", "日志可能含")

BAN = {
    "main": [
        "本机不做系统悬浮窗",
        "fun permRow(",              # Compose 不接受局部组合函数
    ],
    "ka": [
        "com.miui.securitycenter",   # 无小米官方稳定组件，不许硬编码
        "AutoStartManagementActivity",
        "TYPE_APPLICATION_OVERLAY",  # R161 起悬浮控件本体在 OverlayProgress.kt；KeepAlive 仍只做权限引导
        "startService(",             # 不许从引导里偷偷拉服务
    ],
    "contract": [
        "不申请 `SYSTEM_ALERT_WINDOW`。",
        "没有悬浮窗开关",
    ],
}


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


def ui_texts(code):
    return re.findall(r'Text\(\s*"((?:[^"\\]|\\.)*)"', code)


def tutorial_check(f):
    """R158b · 应用里不许再出现教程/步骤/长解释。"""
    e = []
    for key in ("main", "hub", "player"):
        code = code_only(f[key])
        for t in TUTORIAL:
            if t in code:
                e.append("教程痕迹回潮[%s]: %s" % (key, t))
        longs = [t for t in ui_texts(code)
                 if len(re.findall(r"[\u4e00-\u9fff]", t)) >= 16]
        if len(longs) > 6:
            e.append("长说明太多[%s]: %d 条（教程该放文档不该进 UI）" % (key, len(longs)))
        for t in longs:
            if not any(a in t for a in ALLOWED_LONG):
                e.append("非后果告知的长文案[%s]: %s" % (key, t[:50]))
    return e


def grid_bodies(hub):
    """R163 · 单列后只剩一处格子行，两处检查合并到同一真源。"""
    a = hub.find("private fun ChapterSegGrid(")
    b = hub.find("// 代码绘制的三点操作图标", a)
    first = hub[a:b] if a >= 0 and b > a else ""
    return first, first


def centered(body):
    """R164 · 固定 10 列网格：整块居中 + 行内左对齐，逐行居中不许回潮。"""
    # 只看容器头部：小格内部也有 Center，拿整段判会被误满足。
    head = body.split("val gap = 4.dp")[0] if "val gap = 4.dp" in body else body
    return ("BoxWithConstraints(" in head
            and "contentAlignment = Alignment.Center" in head
            and "repeat(SEG_GRID_COLUMNS)" in body
            and "Spacer(Modifier.size(cell))" in body
            and "wrapContentWidth" not in body)


def logic_cases(f):
    bad = []
    first, second = grid_bodies(f["hub"])
    if not first or not second:
        bad.append("没圈出两处格子行")
    else:
        for name, body in (("章内展开 10 列/行", first), ("同一格子行复检", second)):
            if not centered(body):
                bad.append(name + " 不是固定列网格 / 整块没居中")
        if not bad:
            print("  单列格子行：固定 10 列 · 整块居中 · 行内左对齐 ✓")
    # 居中的语义判据：旧的「fillMaxWidth 的 Row + spacedBy」不能回来
    for name, body in (("格子行", first), ("格子行复检", second)):
        if re.search(r"Row\(Modifier\.fillMaxWidth\(\)\s*\n?\s*\.padding\([^)]*\)\s*,\s*\n?\s*horizontalArrangement", body):
            bad.append(name + " 又出现铺满整宽的 Row")
    # 权限引导只用标准 Intent + 复检，不硬编码厂商组件
    ka = f["ka"]
    if "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in ka and \
            "isIgnoringBatteryOptimizations" not in ka:
        bad.append("电池优化引导缺复检")
    if "ACTION_MANAGE_OVERLAY_PERMISSION" in ka and "canDrawOverlays" not in ka:
        bad.append("浮窗引导缺复检")
    if "resolveActivity" not in ka:
        bad.append("设置页跳转缺可解析性检查，HyperOS 上会崩")
    else:
        print("  权限引导：标准 Intent + resolveActivity 回退 + 状态复检 ✓")
    print("逻辑用例 %s" % ("通过" if not bad else "有失败"))
    return bad


def check(f):
    e = []
    for key, needles in POS.items():
        for s in needles:
            if s not in f[key]:
                e.append("正向缺失[%s]: %s" % (key, s[:70]))
    for key, needles in BAN.items():
        for s in needles:
            if s in code_only(f[key]):
                e.append("旧约束/危险写法回潮[%s]: %s" % (key, s[:60]))
    first, second = grid_bodies(f["hub"])
    if not first or not second:
        e.append("没圈出两处格子行")
    else:
        for name, body in (("格子行", first), ("格子行复检", second)):
            if not centered(body):
                e.append(name + " 分段格子没居中")
    if "private fun PermRow" not in f["main"]:
        e.append("权限行没抽成顶层组合函数（局部函数真编译会炸）")
    e += tutorial_check(f)
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了"
    logic_bad = len(logic_cases(f))
    cuts = [
        ("固定列网格容器被摘", "hub",
         "BoxWithConstraints(", "Box(Modifier.fillMaxWidth()", 1),
        ("固定 10 槽被摘", "hub",
         "repeat(SEG_GRID_COLUMNS)", "row.forEachIndexed { _, _ ->", 1),
        ("逐行居中回潮", "hub",
         "Row(Modifier.width(gridWidth)", "Row(Modifier.wrapContentWidth()", 1),
        ("整块居中被摘", "hub",
         "        contentAlignment = Alignment.Center\n    ) {\n        val gap = 4.dp",
         "        contentAlignment = Alignment.TopStart\n    ) {\n        val gap = 4.dp", 1),
        ("末行补齐占位被摘", "hub",
         "Spacer(Modifier.size(cell))", "", 1),
        ("PermRow 顶层函数被摘", "main",
         "private fun PermRow(title: String, granted: Boolean, hint: String, go: () -> Unit)", ""),
        ("又写成局部 composable", "main",
         "PermRow(\"忽略电池优化\", batOk,", "fun permRow(\"忽略电池优化\", batOk,"),
        ("电池优化引导被摘", "main", "KeepAlive.requestIgnoreBattery(c)", ""),
        ("浮窗引导被摘", "main", "KeepAlive.requestOverlay(c)", ""),
        ("应用详情兜底被摘", "main", "KeepAlive.openAppDetails(c)", ""),
        ("R158b 教程口径被摘", "main", "R158b · 用户明确「教程不要塞进应用里」", ""),
        ("ON_RESUME 复检被摘", "main", "Lifecycle.Event.ON_RESUME) permTick++", ""),
        ("后台权限卡片被摘", "main",
         'SettingsSection("后台运行与权限", "系统", collapsible = false)', ""),
        ("教程长说明回潮", "main", '"日志可能含书名和正文片段"',
         'Text("设置 → 应用设置 → 应用管理 → 找到听本，先补齐上面那一份")'),
        ("KeepAlive 对象被摘", "ka", "object KeepAlive", ""),
        ("电池标准 Intent 被摘", "ka", "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", ""),
        ("浮窗标准 Intent 被摘", "ka", "ACTION_MANAGE_OVERLAY_PERMISSION", ""),
        ("应用详情兜底被摘", "ka", "ACTION_APPLICATION_DETAILS_SETTINGS", ""),
        ("电池复检被摘", "ka", "isIgnoringBatteryOptimizations(c.packageName)", ""),
        ("浮窗复检被摘", "ka", "Settings.canDrawOverlays(c)", ""),
        ("resolveActivity 被摘", "ka", "resolveActivity(pm)", ""),
        ("包名参数被摘", "ka", 'Uri.parse("package:" + c.packageName)', ""),
        ("图文步骤说明被摘", "ka", "图文步骤也不进应用", ""),
        ("诊断快照被摘", "ka", "fun snapshot(c: Context): String", ""),
        ("偷偷拉服务", "ka", "fun openAppDetails(c: Context): Boolean",
         "fun openAppDetails(c: Context): Boolean { c.startService(Intent()); return true }; private fun unused("),
        ("Manifest 浮窗权限被摘", "manifest",
         'android.permission.SYSTEM_ALERT_WINDOW', ''),
        ("契约旧禁令回潮", "contract", "R158 · 旧硬约束已由用户推翻",
         "不申请 `SYSTEM_ALERT_WINDOW`。"),
        ("结构守门放行声明被摘", "structure",
         "声明了浮窗权限却没有 KeepAlive 引导入口", ""),
        ("CI 漏挂 r158", "ci", "python3 scripts/r158_verify.py --selftest", ""),
        ("现行清单漏 r158", "r135", '"r158_verify.py"', ""),
    ]
    bad = 0
    for item in cuts:
        name, key, needle, repl = item[:4]
        nth = item[4] if len(item) > 4 else 0
        x = dict(f)
        if needle not in f[key]:
            bad += 1
            print("  反向注入用例无效:", name)
            continue
        # 默认替换**全部**出现：KDoc 注释里常和代码同名，只删第一处会漏放。
        # 个别用例需要只动第 n 处（例如「第二列」），显式传 nth。
        if nth:
            parts = f[key].split(needle)
            if len(parts) <= nth:
                bad += 1
                print("  反向注入用例无效:", name)
                continue
            x[key] = needle.join(parts[:nth]) + repl + needle.join(parts[nth:])
        else:
            x[key] = f[key].replace(needle, repl)
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
        print("R158 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R158 硬约束清理 / 分段格子居中 / 后台权限引导：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
