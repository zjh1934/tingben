#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R239 · 设置页重设计第二批：遗留手搓行全收进 R238 的 SettingLine。

口径：
  · OverlayProgressRow / AutoResumeRow / PermEntryRow / PermRow 四个顶层组件
    内部统一行；签名与调用串一字不动（r149/r158/r161/r171/r174 都绑着）
  · 主题是三段选择器不是单行开关：标题行走 SettingLine 徽标槽对齐、
    trailing 留空，选择器仍独占整行
  · 行间冗余 Spacer 不回潮：SettingLine 自带 vertical=9.dp
  · 顺手连根拔：r161 里绑死 R237 旧通知签名的正向锚点与注入锚点一起迁移，
    正向恒红 / 注入恒不红都算「绑死已删旧形态」，判据一条不放宽
  · 索引地图接得上的硬口径仍在（AGENTS + 看板）
用法：python3 scripts/r239_verify.py [--selftest]
"""
import pathlib, re, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources
ROOT = pathlib.Path(__file__).resolve().parents[1]

def files():
    def rd(p): return p.read_text(encoding="utf-8", errors="replace")
    return {
        "settings": rd(ROOT / "app/src/main/java/com/tingben/app/SettingsUi.kt"),
        "merged": ui_sources.source(ROOT).read_text(encoding="utf-8"),
        "r161": rd(ROOT / "scripts/r161_verify.py"),
        "agents": rd(ROOT / "AGENTS.md"),
                # R242 · 看板在仓库外、CI checkout 里没有；判据改读仓内 AGENTS.md。
        "board": rd(ROOT / "AGENTS.md"),
        "ci": rd(ROOT / ".github/workflows/ci.yml"),
        "r135": rd(ROOT / "scripts/r135_verify.py"),
        "config": rd(ROOT / ".aoci/config.json"),
    }

def body(src, sig):
    # 顶层组件之间常夹 KDoc 注释，用「下一个 @Composable」切才不会把
    # SettingLine 自己那处合法的 Column(weight) 误圈进前一个组件。
    a = src.find(sig)
    if a < 0: return ""
    nxt = src.find("\n@Composable", a + len(sig))
    return src[a:nxt] if nxt > a else src[a:]

def check(f):
    e = []
    s, m, r = f["settings"], f["merged"], f["r161"]
    # ① 四个组件签名仍在合并视图（老守门靠它定位）
    for sig in ["private fun OverlayProgressRow(c: Context)",
                "private fun AutoResumeRow(c: Context)",
                "private fun PermEntryRow(title: String, hint: String, go: () -> Unit)",
                "private fun PermRow(title: String, granted: Boolean, hint: String, go: () -> Unit)"]:
        if sig not in m: e.append("顶层组件签名丢了: " + sig[:52])
    # ② 四个组件内部都走 SettingLine，且不再手搓 Column(weight)
    for name, sig in [("悬浮", "internal fun OverlayProgressRow(c: Context)"),
                      ("续跑", "internal fun AutoResumeRow(c: Context)"),
                      ("应用详情", "internal fun PermEntryRow(title: String, hint: String, go: () -> Unit)"),
                      ("权限", "internal fun PermRow(title: String, granted: Boolean, hint: String, go: () -> Unit)")]:
        b = body(s, sig)
        if not b: e.append(name + "组件没圈出函数体"); continue
        if "SettingLine(" not in b: e.append(name + "组件没走统一行")
        if "Column(Modifier.weight(1f))" in b: e.append(name + "组件还在手搓 Column(weight)")
    # ③ 关键逻辑一条不丢
    for k in ['SettingLine("浮", "悬浮进度"', "danger = on && !permOk",
              "if (!it) OverlayProgress.hide(c)",
              'EventLog.ui("OVERLAY_SETTING"',
              'SettingLine("续", "打开软件自动续跑"',
              'EventLog.ui("RESUME_SETTING"',
              "SettingLine(title.take(1), title, hint)",
              'Text("打开 ›"',
              '"已开启 · 查看"', "containerColor = if (granted) Good else Primary",
              'SettingLine("铃", "通知权限"']:
        if k not in s: e.append("关键逻辑缺: " + k[:56])
    if s.count("SettingLine(title.take(1), title, hint)") != 2:
        e.append("PermRow/PermEntryRow 徽标首字不是正好两处")
    # ④ 主题：徽标槽对齐、选择器仍独占整行
    if 'SettingLine("主", "主题", "外观基调") { }' not in s:
        e.append("主题标题行没对齐徽标槽")
    if 'listOf("跟随系统" to 0, "浅色" to 1, "深色" to 2)' not in s:
        e.append("主题三段选择器被摘")
    if 'Text("主题", fontSize = 13.sp' in s:
        e.append("主题又退回裸标题行")
    # ⑤ 冗余间距不回潮（SettingLine 自带 9dp）
    for k in ["            }\n            Spacer(Modifier.height(10.dp))\n            OverlayProgressRow(c)",
              "            AutoResumeRow(c)\n            Spacer(Modifier.height(8.dp))\n            // R165",
              "说明放沙盒方案文档，不进 APK。\n            Spacer(Modifier.height(10.dp))\n            PermRow("]:
        if k in s: e.append("行间冗余 Spacer 回潮")
    # ⑥ 陈旧判据连根拔（r161 正向 + 注入都按 R237 现行形态迁移）
    for old in ["buildNotification(this, lastTitle, lastText,",
                '"lastDone, lastTotal, true"']:
        if old in r: e.append("r161 还绑着 R237 之前的旧形态: " + old[:48])
    for k in ["lastDone, lastTotal, lastChapterPct, true)",
              '"lastDone, lastTotal, lastChapterPct, true"',
              '"lastDone, lastTotal, lastChapterPct, false"']:
        if k not in r: e.append("r161 现行判据/注入缺: " + k[:52])
    # ⑦ 索引硬口径仍在
    if "索引/地图接得上（R238）" not in f["agents"]: e.append("AGENTS 索引硬口径被摘")
    if "--refresh" not in f["board"]: e.append("AGENTS 索引口径被摘")
    # ⑧ 登记
    for name, src, needle in [("CI", f["ci"], "python3 scripts/r239_verify.py --selftest"),
                              ("清单", f["r135"], '"r239_verify.py"'),
                              ("受管根", f["config"], '"app/src/main/java"')]:
        if needle not in src: e.append(name + " 漏登记 R239")
    return e

def selftest():
    f = files()
    assert not check(f), "基线自己就红了"
    cuts = [
        ("悬浮组件被摘", "settings", "internal fun OverlayProgressRow(c: Context)", ""),
        ("悬浮行退回手搓", "settings", 'SettingLine("浮", "悬浮进度"',
         'Row(Modifier.fillMaxWidth()) { Text("悬浮进度")'),
        ("警示色被摘", "settings", "danger = on && !permOk", ""),
        ("停止收起被摘", "settings", "if (!it) OverlayProgress.hide(c)", ""),
        ("续跑组件被摘", "settings", "internal fun AutoResumeRow(c: Context)", ""),
        ("续跑行退回手搓", "settings", 'SettingLine("续", "打开软件自动续跑"',
         'Row(Modifier.fillMaxWidth()) { Text("打开软件自动续跑")'),
        ("续跑日志被摘", "settings", 'EventLog.ui("RESUME_SETTING"', ""),
        ("权限首字徽标被摘", "settings", "SettingLine(title.take(1), title, hint)",
         'SettingLine("权", title, hint)'),
        ("绿底状态被摘", "settings", "containerColor = if (granted) Good else Primary",
         "containerColor = Primary"),
        ("应用详情按钮被摘", "settings", 'Text("打开 ›"', ""),
        ("通知行被摘", "settings", 'SettingLine("铃", "通知权限"', ""),
        ("主题徽标被摘", "settings", 'SettingLine("主", "主题", "外观基调") { }',
         'Text("主题", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)'),
        ("主题选择器被摘", "settings", 'listOf("跟随系统" to 0, "浅色" to 1, "深色" to 2)', ""),
        ("冗余间距回潮", "settings", "            OverlayProgressRow(c)",
         "            Spacer(Modifier.height(10.dp))\n            OverlayProgressRow(c)"),
        ("r161 旧正向锚点回潮", "r161", "lastDone, lastTotal, lastChapterPct, true)",
         "lastDone, lastTotal, true)"),
        ("r161 旧注入锚点回潮", "r161", '"lastDone, lastTotal, lastChapterPct, true"',
         '"lastDone, lastTotal, true"'),
        ("AGENTS 索引口径被摘", "agents", "索引/地图接得上（R238）", ""),
        ("AGENTS 索引口径被摘（board）", "board", "--refresh", ""),
        ("CI 漏挂", "ci", "python3 scripts/r239_verify.py --selftest", ""),
        ("清单漏登记", "r135", '"r239_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        # R242 · AGENTS.md 里 --refresh 出现多处，只换第一处会恒不红（漏放）；
        # 全部注入改全量替换，纯内存，源文件零写入。
        g = dict(f); g[key] = f[key].replace(needle, repl)
        if check(g): print("  反向注入 %-20s 拦下" % name)
        else: print("  反向注入漏放:", name); bad += 1
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad

if __name__ == "__main__":
    if "--selftest" in sys.argv: sys.exit(1 if selftest() else 0)
    e = check(files())
    if e:
        print("R239 FAIL", len(e))
        for x in e: print(" -", x)
        sys.exit(1)
    print("R239 四个行组件统一 / 主题对齐 / 陈旧判据连根拔：PASS")
