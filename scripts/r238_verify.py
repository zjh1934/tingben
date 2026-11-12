#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R238 · 设置页重设计第一批 + 索引/地图接得上的硬口径。

口径：
  · SettingLine 统一行（前导徽标/标题/副标题/尾部控件），借鉴 Material 3 Preference
    层级，自绘不引图标库；顶层 composable 必须 internal
  · 撤「本机数据」「成品文件」两张同 badge 碎卡，合并「文件与数据」（R174 同类）
  · 顶部卡只留软件版本，Android/ABI 调试串仍留在诊断日志（r141 守）
  · 每轮受管文件改动当轮 --refresh + Entry 齐全、aoci_check 必绿；
    新增资源命中 exclude_dirs 时并进现有 Entry，不硬塞索引（R237 实证）
用法：python3 scripts/r238_verify.py [--selftest]
"""
import pathlib, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources
ROOT = pathlib.Path(__file__).resolve().parents[1]

def files():
    def rd(p): return p.read_text(encoding="utf-8", errors="replace")
    return {
        "settings": rd(ROOT / "app/src/main/java/com/tingben/app/SettingsUi.kt"),
        "merged": ui_sources.source(ROOT).read_text(encoding="utf-8"),
        "agents": rd(ROOT / "AGENTS.md"),
                # R242 · 看板在仓库外、CI checkout 里没有；判据改读仓内 AGENTS.md。
        "board": rd(ROOT / "AGENTS.md"),
        "ci": rd(ROOT / ".github/workflows/ci.yml"),
        "r135": rd(ROOT / "scripts/r135_verify.py"),
        "config": rd(ROOT / ".aoci/config.json"),
        "r156": rd(ROOT / "scripts/r156_verify.py"),
    }

def check(f):
    e = []
    s, m = f["settings"], f["merged"]
    # ① 统一行组件
    for k in ["internal fun SettingLine(",
              "mark: String,", "title: String,", 'hint: String = "",',
              "danger: Boolean = false,",
              "trailing: @Composable RowScope.() -> Unit",
              "Modifier.size(32.dp)",
              "if (danger) Warn.copy(alpha = 0.14f) else PrimarySoft",
              "RoundedCornerShape(10.dp)",
              "Modifier.fillMaxWidth().padding(vertical = 9.dp)",
              "TextOverflow.Ellipsis",
              "color = if (danger) Warn else palette.text"]:
        if k not in s: e.append("SettingLine 缺: " + k[:56])
    # 合并视图会把 internal 还原成 private（R206 可见性双向变换），
    # 所以「不许 private」只能判磁盘片段；判合并视图会恒红。
    if "private fun SettingLine(" in s:
        e.append("SettingLine 用了 private，UI 片段跨文件不可见")
    # 至少三处统一行（通知权限 / 导出位置 / 导出全部）
    for k in ['SettingLine("铃", "通知权限"',
              'SettingLine("导", "导出位置", expTreeLabel)',
              'SettingLine("成", "整章成品", "已做好 $expCount 个']:
        if k not in s: e.append("统一行没接上: " + k[:52])
    # ② 碎卡合并
    if 'SettingsSection("文件与数据", "本机", collapsible = false)' not in s:
        e.append("合并卡「文件与数据」不在")
    for old in ['SettingsSection("本机数据"', 'SettingsSection("成品文件"']:
        if old in s: e.append("旧碎卡回潮: " + old)
    # 「本机」badge 的卡只能有一张
    import re
    n = len(re.findall(r'SettingsSection\([^)]*"本机"', s))
    if n != 1: e.append("标「本机」的卡不是正好一张（实 %d）" % n)
    card = s[s.find('SettingsSection("文件与数据"'):]
    for k in ["ClearSegmentsRow(c)", "AudioExport.exportAll(c, tree)",
              '"导出位置"', '"导出全部"', "treeLauncher.launch(AudioExport.treeUri(c))"]:
        if k not in card: e.append("合并卡内容缺: " + k[:52])
    # ③ 顶部卡去调试串（诊断日志仍保留，r141 守，不是删信息）
    for banned in ['" · 本机 Android "', "android.os.Build.SUPPORTED_ABIS.firstOrNull()"]:
        if banned in s: e.append("设置首页又塞回调试串: " + banned[:48])
    if '"软件版本 " + com.tingben.app.BuildConfig.VERSION_NAME' not in s:
        e.append("软件版本也没了")
    # ④ r156 按现状迁移，判据不放宽
    if 'SettingsSection("文件与数据", "本机", collapsible = false)' not in f["r156"]:
        e.append("r156 还绑旧标题，重设计后判据没接上")
    if 'SettingsSection("成品文件"' in f["r156"]:
        e.append("r156 旧标题残留")
    # ⑤ 索引硬口径（用户点名「索引地图很重要，别接不上」）
    for k in ["aoci verify", "managed_scope.rules", "Baseline 由官方维护"]:
        if k not in f["agents"]: e.append("AGENTS 漏写官方治理口径: " + k)
    for k in ["aoci verify", "Baseline 由官方维护"]:
        if k not in f["board"]: e.append("看板铁律漏写官方治理口径: " + k)
    # ⑥ 登记
    for name, src, needle in [("CI", f["ci"], "python3 scripts/r238_verify.py --selftest"),
                              ("清单", f["r135"], '"r238_verify.py"'),
                              ("受管根", f["config"], '"app/src/main/java"')]:
        if name == "受管根":
            from r45_common import scope_indexed
            if not scope_indexed(f["config"], "app/src/main/java"):
                e.append(name + " 漏登记 R238")
        elif needle not in src: e.append(name + " 漏登记 R238")
    return e

def selftest():
    f = files()
    assert not check(f), "基线自己就红了"
    cuts = [
        ("SettingLine 被摘", "settings", "internal fun SettingLine(", ""),
        ("改 private", "settings", "internal fun SettingLine(", "private fun SettingLine("),
        ("徽标槽被摘", "settings", "Modifier.size(32.dp)", "Modifier.size(0.dp)"),
        ("危险色被摘", "settings", "if (danger) Warn.copy(alpha = 0.14f) else PrimarySoft", "PrimarySoft"),
        ("统一行高被摘", "settings", "Modifier.fillMaxWidth().padding(vertical = 9.dp)", "Modifier.fillMaxWidth()"),
        ("通知行没接", "settings", 'SettingLine("铃", "通知权限"', ""),
        ("导出行没接", "settings", 'SettingLine("导", "导出位置", expTreeLabel)', ""),
        ("成品行没接", "settings", 'SettingLine("成", "整章成品", "已做好 $expCount 个', ""),
        ("合并卡被摘", "settings", 'SettingsSection("文件与数据", "本机", collapsible = false)', ""),
        ("旧本机数据卡回潮", "settings", 'SettingsSection("运行与通知", "系统", collapsible = false)',
         'SettingsSection("本机数据", "本机", collapsible = false)\n        SettingsSection("运行与通知", "系统", collapsible = false)'),
        ("旧成品卡回潮", "settings", 'SettingsSection("文件与数据", "本机", collapsible = false)',
         'SettingsSection("成品文件", "本机", collapsible = false)'),
        ("清缓存行被摘", "settings", "ClearSegmentsRow(c)", ""),
        ("导出动作被摘", "settings", "AudioExport.exportAll(c, tree)", ""),
        ("调试串回潮", "settings", '"软件版本 " + com.tingben.app.BuildConfig.VERSION_NAME',
         '"软件版本 " + com.tingben.app.BuildConfig.VERSION_NAME + " · 本机 Android " + android.os.Build.VERSION.RELEASE'),
        ("软件版本被摘", "settings", '"软件版本 " + com.tingben.app.BuildConfig.VERSION_NAME', '"版本"'),
        ("r156 没迁移", "r156", 'SettingsSection("文件与数据", "本机", collapsible = false)',
         'SettingsSection("成品文件", "本机", collapsible = false)'),
        ("AGENTS 官方口径被摘", "agents", "aoci verify", ""),
        ("AGENTS 官方口径被摘(board)", "board", "Baseline 由官方维护", ""),
        ("CI 漏挂", "ci", "python3 scripts/r238_verify.py --selftest", ""),
        ("清单漏登记", "r135", '"r238_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        g = dict(f); g[key] = f[key].replace(needle, repl)
        if check(g): print("  反向注入 %-20s 拦下" % name)
        else: print("  反向注入漏放:", name); bad += 1
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad

if __name__ == "__main__":
    if "--selftest" in sys.argv: sys.exit(1 if selftest() else 0)
    e = check(files())
    if e:
        print("R238 FAIL", len(e))
        for x in e: print(" -", x)
        sys.exit(1)
    print("R238 设置页统一行 / 碎卡合并 / 索引硬口径：PASS")
