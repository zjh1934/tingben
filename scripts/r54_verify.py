#!/usr/bin/env python3
"""R54 · 设置页重设计验证：四分区 / 零跳转入口 / 能力守恒 / 反向注入。

不依赖 Android SDK，只做源码级断言；真编译由 CI 的 assembleDebug 负责。
"""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"
s = MAIN.read_text(encoding="utf-8")
errs = []


def need(cond, msg):
    if not cond:
        errs.append(msg)


def settings_body(text):
    """只取设置页函数体，避免把别处的同名字符串算成残留。"""
    a = text.index("private fun SettingsScreen(")
    return text[a:text.index("// ── ⑤b 模型页", a)]


S = settings_body(s)

# ── 1) 正向：四分区与首屏形态 ────────────────────────────────────────────
need("private fun SettingsSection(" in s, "缺分区组件 SettingsSection")
need("private fun SettingSwitchRow(" in s, "缺开关行组件 SettingSwitchRow")
need('SettingsSection("声音", "长期默认"' in S, "缺声音分区")
need('SettingsSection("语言模型", "想用就用"' in S, "缺语言模型分区")
need('SettingsSection("工具", "按需"' in S, "缺工具分区")
need('SettingsSection("诊断", "给排查用"' in S, "缺诊断分区")
need(S.index('SettingsSection("声音"') < S.index('SettingsSection("语言模型"')
     < S.index('SettingsSection("工具"') < S.index('SettingsSection("诊断"'),
     "四分区顺序不对（声音须在语言模型之前）")
need('var soundOpen by rememberSaveable { mutableStateOf(true) }' in S, "声音区不是默认展开")
for v in ["var llmOpen by rememberSaveable { mutableStateOf(false) }",
          "var toolsOpen by rememberSaveable { mutableStateOf(false) }",
          "var diagOpen by rememberSaveable { mutableStateOf(false) }"]:
    need(v in S, "默认展开态不对: " + v)
need('Text(if (checked) "开" else "关"' in s[s.index("private fun SettingSwitchRow("):s.index("private fun SettingsScreen(")],
     "开关没补开/关文字")
need(S.count("改动立即保存 · 正在跑的任务不受影响") == 1, "时机提示不是恰好一句")
need('TextButton(onClick = onModels) { Text("导入 / 查看 ›"' in S, "模型状态行丢了")
need("3 个模型文件都准备好了" in S, "模型状态文案丢了")

# ── 2) 零残留：用户点名要删的旧形态 ──────────────────────────────────────
BANNED = ["🎛 调音台", "固定音色", "音色 12 维 · 采样 9 项", "去书架制作",
          "①", "②", "提交变更", "共用一份文件表", "R45_FIXED_VOICE_ENTRY",
          "onMixer()", "onLibrary()", "onCast()", "GroupLabel(", "CardBox(",
          "高级（诊断）", "关于", "模型文件（两个模型共用）"]
for b in BANNED:
    need(b not in S, "设置页残留旧形态: " + b)
need("@Suppress(\"UNUSED_PARAMETER\")" in S, "零调用参数没标 Suppress")

# ── 3) 能力守恒：一个都不许删 ────────────────────────────────────────────
ABILITY = ["Prefs.setNonVerbal", "Prefs.setCastEnabled", "Prefs.setThinking",
           "Prefs.setNCtx", "Prefs.setThreads", "Prefs.setTemperature",
           "NONVERBAL_MODE", "THINK_MODE",
           "SettingOption(\"上下文窗口\"", "SettingOption(\"运算线程\"", "SettingOption(\"随机度\"",
           "fun doProbe(", "OutlinedButton(", "试合成一句", "试哭笑叹气",
           "NativeEngine.ttsSynthesize", "\"chinese\"", "Prefs.ttsKnobs(c)",
           "ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS", "BATTERY_SETTINGS_OPEN",
           "后台运行设置（系统电池优化）",
           "delay(3000)", "buildDiag", "RawDump.latest", "AuditLog.summaryText",
           "showBlocks", "showRules", "showPreview", "showContent", "showRaw",
           "复制标注全文", "复制最近一块模型原始产出（全文）", "复制诊断信息（摘要）",
           "复制完整日志（含产出片段）", "已复制完整日志"]
for a in ABILITY:
    need(a in S, "能力被删: " + a)
need(S.count("OutlinedButton(") == 2, "两个试音按钮没换成描边")

# ── 4) 全局守恒（一行都不能动） ──────────────────────────────────────────
need(len(re.findall(r'^private enum class Screen \{.*\}$', s, re.M)) == 1, "Screen 枚举变了")
need(s.count("Screen.") >= 9, "Screen 分支数异常")
need("onMixer = { drawerReturn = Screen.SETTINGS; screen = Screen.MIXER }" in s,
     "App() 的调音台分发被改动（抽屉独立项依据）")
need("chromeOf(screen)" in s, "chromeOf 调用丢了")
need("BackHandler(enabled = drawerState.isOpen)" in s, "抽屉返回键优先丢了")
need("DismissibleNavigationDrawer(" in s, "联动抽屉丢了")
need("navigationBarsPadding()" in s and "statusBarsPadding()" in s, "安全区丢了")
need("MixerCommit.commit(c, draft, n)" in s, "调音台唯一提交出口被改")
need("试听未提交参数" in s and "试听已生效参数" in s, "两态试听被改")
need("ProduceService" in (ROOT / "app/src/main/AndroidManifest.xml").read_text(),
     "前台服务声明丢了")
need("android.permission.INTERNET" not in (ROOT / "app/src/main/AndroidManifest.xml").read_text(),
     "Manifest 混进 INTERNET")
need("python3 scripts/r54_verify.py" in
     (ROOT / ".github/workflows/ci.yml").read_text(), "CI 没挂 R54 验证")

# ── 5) 反向注入：任一处改坏，断言必须响 ──────────────────────────────────
def baseline(text):
    e = []
    def ck(c, m):
        if not c: e.append(m)
    b = settings_body(text)
    for x in ["🎛 调音台", "固定音色", "去书架制作", "①", "②", "提交变更",
              "onMixer()", "GroupLabel(", "CardBox("]:
        ck(x not in b, "残留 " + x)
    ck("Card(Modifier.clickable { onMixer() })" not in b, "出现跳转卡")
    ck(b.count("改动立即保存 · 正在跑的任务不受影响") == 1, "时机提示重复/丢失")
    ck('Text(if (checked) "开" else "关"' in text, "开关文字被删")
    ck(b.index('SettingsSection("声音"') < b.index('SettingsSection("语言模型"'), "分区顺序倒了")
    ck('var soundOpen by rememberSaveable { mutableStateOf(true) }' in b, "默认展开态变了")
    for x in ["复制完整日志（含产出片段）", "fun doProbe(", "delay(3000)"]:
        ck(x in b, "能力被删 " + x)
    return e


def blocked(mut, label):
    m = mut(s)
    try:
        e = baseline(m)
    except Exception as ex:
        errs.append("反向注入 %s 导致切片异常: %s" % (label, ex))
        return
    need(bool(e), "反向注入没拦下：" + label)


blocked(lambda x: x.replace('SettingsSection("声音"',
                            'Card(Modifier.clickable { onMixer() })\n        SettingsSection("声音"', 1),
        "恢复调音台跳转卡")
blocked(lambda x: x.replace('SettingsSection("语言模型", "想用就用"',
                            'SettingsSection("① 语言模型", "想用就用"', 1),
        "恢复圈码标题")
blocked(lambda x: x.replace('Text("改动立即保存 · 正在跑的任务不受影响", fontSize = 11.sp, color = Sub)',
                            'Text("改动立即保存 · 正在跑的任务不受影响", fontSize = 11.sp, color = Sub)\n            Button(onClick = {}) { Text("提交变更") }', 1),
        "设置页出现第二个生效动作")
blocked(lambda x: x.replace('Text(if (checked) "开" else "关"',
                            'Text("开关"', 1),
        "开关删掉开/关文字")
blocked(lambda x: x.replace('复制完整日志（含产出片段）', '复制日志', 1),
        "诊断复制能力被删")
blocked(lambda x: x.replace('SettingsSection("声音", "长期默认"', 'SettingsSection("声音X", "长期默认"', 1)
        .replace('SettingsSection("语言模型", "想用就用"', 'SettingsSection("声音", "长期默认"', 1)
        .replace('SettingsSection("声音X", "长期默认"', 'SettingsSection("语言模型", "想用就用"', 1),
        "声音区被挪到语言模型之后")
blocked(lambda x: x.replace('var soundOpen by rememberSaveable { mutableStateOf(true) }',
                            'var soundOpen by rememberSaveable { mutableStateOf(false) }', 1),
        "声音区默认收起")
blocked(lambda x: x.replace('"chinese"', '"auto"', 1),
        "试音口径退回 auto")

# ── 6) 括号平衡（先屏蔽字符串/注释） ─────────────────────────────────────
def strip_code(text):
    out = []; i = 0; n = len(text)
    while i < n:
        ch = text[i]
        if ch == '"' and text[i+1:i+3] == '""':
            j = text.find('"""', i+3); j = n if j < 0 else j+3
            out.append(' '*(j-i)); i = j; continue
        if ch == '"' or ch == "'":
            q = ch; j = i+1
            while j < n:
                if text[j] == '\\': j += 2; continue
                if text[j] == q: j += 1; break
                j += 1
            out.append(' '*(j-i)); i = j; continue
        if ch == '/' and i+1 < n and text[i+1] == '/':
            j = text.find('\n', i); j = n if j < 0 else j
            out.append(' '*(j-i)); i = j; continue
        if ch == '/' and i+1 < n and text[i+1] == '*':
            j = text.find('*/', i+2); j = n if j < 0 else j+2
            out.append(' '*(j-i)); i = j; continue
        out.append(ch); i += 1
    return ''.join(out)

z = strip_code(s)
for o, cl in [('{','}'), ('(',')'), ('[',']')]:
    need(z.count(o) == z.count(cl), "MainActivity.kt 括号不平衡 %s%s" % (o, cl))

if errs:
    print("R54 FAIL")
    for e in errs:
        print("  -", e)
    sys.exit(1)
print("R54 PASS · 正向 %d 项 / 反向注入 8 例 / 守恒与括号全过"
      % (len(ABILITY) + len(BANNED) + 25))
