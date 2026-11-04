#!/usr/bin/env python3
"""听本 · CI 自检：必备文件 / 权重泄漏 / 无网络权限 / 包名 / KDoc 坑 / 审计符号。只依赖标准库。"""
from pathlib import Path
import os, re, sys
ROOT = Path(__file__).resolve().parent.parent
errs = []
REQUIRED = ["README.md", ".gitignore", "settings.gradle.kts", "build.gradle.kts",
 "gradle/libs.versions.toml", "gradle.properties", "app/build.gradle.kts",
 "app/src/main/AndroidManifest.xml", "app/src/main/assets/prompt_zh.txt",
 "app/src/main/cpp/CMakeLists.txt", "app/src/main/cpp/tingben_native.cpp",
 "app/src/main/java/com/tingben/app/MainActivity.kt",
 "app/src/main/java/com/tingben/app/ProduceService.kt",
 "app/src/main/java/com/tingben/app/diag/EventLog.kt",
 "app/src/main/java/com/tingben/app/diag/AuditLog.kt",
 "app/src/main/java/com/tingben/app/core/JsonSalvage.kt",
 "app/src/main/java/com/tingben/app/core/Adoption.kt",
 "app/src/main/java/com/tingben/app/diag/RawDump.kt",
 "docs/THIRD_PARTY_NOTICES.md",
 "scripts/build_native.sh", "scripts/check_structure.py", ".github/workflows/ci.yml", "keystore.jks",
 "contracts/model-manifest.json", "contracts/pipeline_spec.md"]
for f in REQUIRED:
    if not (ROOT / f).exists(): errs.append("缺少必备文件: " + f)

SKIP = {".git", ".gradle", "build", ".idea", "__pycache__", "reference"}
kt_files = []
for root, dirs, files in os.walk(ROOT):
    dirs[:] = [d for d in dirs if d not in SKIP]
    for f in files:
        p = Path(root) / f
        if f.lower().endswith((".gguf", ".safetensors", ".pth", ".onnx")):
            errs.append("模型权重泄漏进源码树: " + str(p))
        if f.endswith(".kt"):
            kt_files.append(p)

m = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
if "android.permission.INTERNET" in m: errs.append("Manifest 含 INTERNET 权限")
if 'applicationId = "com.tingben.app"' not in (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8"):
    errs.append("包名不是 com.tingben.app")

def mask_strings(src):
    """把字符串/字符字面量/行注释内容抹成空格，只留注释正文与代码结构。"""
    out = list(src); i = 0; n = len(src); st = 'CODE'; esc = False
    while i < n:
        c = src[i]; nx = src[i + 1] if i + 1 < n else ''
        if st == 'CODE':
            if c == '/' and nx == '/': st = 'LINE'; out[i] = out[i+1] = ' '; i += 2; continue
            if c == '/' and nx == '*': st = 'BLOCK'; out[i] = out[i+1] = ' '; i += 2; continue
            if c == '"': st = 'STR'; out[i] = ' '; i += 1; continue
            if c == "'": st = 'CHAR'; out[i] = ' '; i += 1; continue
            i += 1; continue
        if st == 'LINE':
            if c == '\n': st = 'CODE'
            else: out[i] = ' '
            i += 1; continue
        if st == 'BLOCK':
            if c == '*' and nx == '/': out[i] = out[i+1] = ' '; st = 'CODE'; i += 2; continue
            i += 1; continue
        if st in ('STR', 'CHAR'):
            if esc: esc = False; out[i] = ' '; i += 1; continue
            if c == '\\': esc = True; out[i] = ' '; i += 1; continue
            if (st == 'STR' and c == '"') or (st == 'CHAR' and c == "'"):
                out[i] = ' '; st = 'CODE'; i += 1; continue
            out[i] = ' '; i += 1; continue
    return ''.join(out)


# R20 踩过的坑：KDoc 正文里写 KEY_ 星斜杠 DEFAULT_星 之类字面量会提前闭合块注释，
# 后面十几行全变成顶层声明，编译器刷上百条错。括号计数抓不到，这里单独拦。
# 先 mask 字符串，避免把 type = 星斜杠星 这种正常代码误判成注释。
for p in kt_files:
    masked = mask_strings(p.read_text(encoding="utf-8", errors="replace"))
    for no, line in enumerate(masked.splitlines(), 1):
        s = line.strip()
        if s.startswith('*') and '*/' in s and s != '*/':
            errs.append("%s:%d KDoc 正文出现注释收尾符（会提前闭合块注释）"
                        % (p.relative_to(ROOT), no))

# R21 · 标注审计链路的关键符号必须真的在源码里，防止「文档写了、代码没进包」。
all_src = "\n".join(p.read_text(encoding="utf-8", errors="replace") for p in kt_files)
AUDIT_SYMBOLS = ["AUDIT_RUN_BEGIN", "ANN_RAW", "ANN_PREVIEW", "ANN_EMPTY", "ANN_AUDIT",
                 "AUDIT_SUMMARY", "parseWithMeta", "ruleChecks", "BlockAudit",
                 "readDetail", "ringTail", "summaryText", "previewText"]
for sym in AUDIT_SYMBOLS:
    if sym not in all_src: errs.append("缺少标注审计符号: " + sym)

# R22 · 核心链路（逐条采纳）与「标注内容可看见」的符号必须真在源码里。
R22_SYMBOLS = ["ANN_CONTENT", "ANN_DISPOSITION", "TTS_INPUT", "reconcile", "contentJson",
               "contentLines", "contentText", "annsJson", "disposition", "lang=chinese",
               "查看标注内容", "复制标注全文"]
for sym in R22_SYMBOLS:
    if sym not in all_src: errs.append("缺少 R22 核心链路符号: " + sym)

# R23 · 主事件流 ANN_CONTENT 必须给逐条全文，不许退回 70 字截取。
R23_SYMBOLS = ["已截断，全文见诊断页", "16384", "R23 · 主事件流里给人看的逐条内容"]
pipe_src = (ROOT / "app/src/main/java/com/tingben/app/core/Pipeline.kt").read_text(encoding="utf-8", errors="replace")
for sym in R23_SYMBOLS:
    if sym not in pipe_src: errs.append("缺少 R23 主日志全文符号: " + sym)
if "a.text.take(70)" in pipe_src: errs.append("R23 回退：contentLines 又把正文截成 70 字")

cpp_src = (ROOT / "app/src/main/cpp/tingben_native.cpp").read_text(encoding="utf-8", errors="replace")
for sym in ["NATIVE_JSON_CLOSE", "jsonDepth", "jsonStarted"]:
    if sym not in cpp_src: errs.append("native 缺少 JSON 闭合早停: " + sym)

# R24 · 三处手搓换成移植件：符号必须真在源码里，旧手搓实现不许残留。
R24_SYMBOLS = ["JsonSalvage", "Adoption", "RawDump", "ANN_RAW_DUMP", "ANN_ADOPT_DETAIL",
               "复制最近一块模型原始产出", "查看模型原始产出", "cleanInstruct",
               "THINK_UNCLOSED", "bracket", "DEFAULT_INSTRUCT"]
for sym in R24_SYMBOLS:
    if sym not in all_src: errs.append("缺少 R24 移植件符号: " + sym)
if "= raw.lastIndexOf(']')" in pipe_src:
    errs.append("R24 回退：parseWithMeta 又用 lastIndexOf 找 JSON 闭合")
if "o.optString(\"instruct\", \"平稳叙述\")" in pipe_src:
    errs.append("R24 回退：又出现手搓解析里的默认指令")
# R25 · 断句移植件 / 设置分层 / 日志不许把零产出写成成功。
main_src = (ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt").read_text(encoding="utf-8", errors="replace")
R25_ALL = ["TextSplitter", "SPLIT_STATS", "ANN_PHASE_HINT", "ANN_BLOCK_END",
           "directWithStats", "零标注 ⚠️ 白跑", "用户中断（本次不计产出）"]
for sym in R25_ALL:
    if sym not in all_src: errs.append("缺少 R25 符号: " + sym)
R25_MAIN = ["private fun GroupLabel(", "① 语言模型 · 长期默认", "② 音频模型 · 长期默认",
            "仅本次", "作用域", "ACTION_MAIN 也被当成", "ProduceMode.DIRECT",
            "标注后合成（实验性）"]
for sym in R25_MAIN:
    if sym not in main_src: errs.append("缺少 R25 界面符号: " + sym)
# 反向断言：按字符腰斩的旧实现、把标注档当默认的旧写法不许回来
# 只认代码形态；注释里写这个字面量是说明旧 bug，不该把自己拦下。
if re.search(r"if\s*\(\s*buf\.length\s*>=\s*110", pipe_src):
    errs.append("R25 回退：又出现按 110 字腰斩的旧切分")
if "mutableStateOf(if (t != null && t.bookId == id && t.direct)" in main_src:
    errs.append("R25 回退：新书默认又变回标注档")

# R26 · 前台服务：manifest 四项权限 + dataSync 服务声明必须真在，
# 同时把「无障碍 / 浮窗 / 伪装播放」这三条明确不做的路线钉成反向断言。
for perm in ["android.permission.FOREGROUND_SERVICE",
             "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
             "android.permission.POST_NOTIFICATIONS",
             "android.permission.WAKE_LOCK"]:
    if perm not in m: errs.append("Manifest 缺少 R26 权限: " + perm)
if 'android:name=".ProduceService"' not in m: errs.append("Manifest 未注册 ProduceService")
if 'android:foregroundServiceType="dataSync"' not in m: errs.append("前台服务类型不是 dataSync")
for banned in ["BIND_ACCESSIBILITY_SERVICE", "SYSTEM_ALERT_WINDOW", "systemExempted",
               "foregroundServiceType=\"mediaPlayback\""]:
    if banned in m: errs.append("R26 回退：出现了明确不做的权限/类型 " + banned)

fgs_src = (ROOT / "app/src/main/java/com/tingben/app/ProduceService.kt").read_text(encoding="utf-8", errors="replace")
R26_ALL = ["FGS_BEGIN", "FGS_READY", "FGS_FINISH", "FGS_DESTROY", "FGS_WAKELOCK",
           "FGS_CANCEL", "FGS_NOTIF_PERMISSION", "FGS_START_FAIL", "BATTERY_SETTINGS_OPEN",
           "PARTIAL_WAKE_LOCK", "FOREGROUND_SERVICE_TYPE_DATA_SYNC", "UNIT_TIMEOUT_MS",
           "POST_NOTIFICATIONS", "ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS",
           "ProduceService", "dataSync", "前台服务 "]
for sym in R26_ALL:
    if sym not in all_src: errs.append("缺少 R26 符号: " + sym)
for call in ["ProduceService.begin(", "ProduceService.finish(", "ProduceService.update(",
             "ProduceService.touch(", "ProduceService.stopping("]:
    if call not in pipe_src: errs.append("Producer 缺少 R26 调用点: " + call)
for sym in ["FOREGROUND_SERVICE_TYPE_DATA_SYNC", "PARTIAL_WAKE_LOCK", "startForegroundCompat",
            "STOP_FOREGROUND_DETACH", "START_NOT_STICKY"]:
    if sym not in fgs_src: errs.append("ProduceService 缺少移植件形状: " + sym)
# 反向断言：唤醒锁不许整轮常驻（必须带超时 acquire），不许变成自动重启
if "wl.acquire(UNIT_TIMEOUT_MS)" not in fgs_src:
    errs.append("R26 回退：唤醒锁没有按块带超时申请")
if "START_STICKY" in fgs_src:
    errs.append("R26 回退：前台服务变成自动重启")

# R27 · 真机截图定案：模型吐了合规 JSON 却一条没进合成，且三处显示口径混用。
# 正向：有序子串+缺口补齐、逐条来源、首因、真标注计数、最终逐字相等都要真在源码里。
R27_ALL = ["ADOPT_0_KEPT", "NOT_FOUND_AFTER_", "srcModel", "adoptFirstDrop",
           "finalVerbatim", "未采纳首因", "模型产出未被采纳", "模型真标注",
           "ann_%04d.src", "旧缓存未记来源", "最终进 TTS", "模型候选"]
for sym in R27_ALL:
    if sym not in all_src: errs.append("缺少 R27 符号: " + sym)
# 反向断言只认代码形态，注释里描述旧 bug 的字面量不该把自己拦下。
if re.search(r"original\.regionMatches\(\s*cursor", all_src):
    errs.append("R27 回退：又变回「从原文第 0 字严格前缀」")
if re.search(r"indexOf\(a\.text,\s*cursor\)\s*!=\s*cursor", pipe_src):
    errs.append("R27 回退：规则判定又要求严格前缀")
if '"NOT_PREFIX_AT_$cursor"' in all_src:
    errs.append("R27 回退：采纳首因又变回 NOT_PREFIX_AT")
if "if (i < kept) \"MODEL\"" in pipe_src:
    errs.append("R27 回退：逐条来源又按「前 kept 条」猜")
if '"已标注 ${i + 1}/${chunks.size} 块"' in pipe_src:
    errs.append("R27 回退：进度又按跑完块数写「已标注」")
if "s.src == \"MODEL\"" not in main_src:
    errs.append("R27 回退：看台没有按真实来源着色")

# R31 · 调音台：12 维网格 / 4 组采样预设 / 生效快照 / A/B 日志必须真在源码里。
# 反向断言：不许把参数埋回折叠里当唯一入口、不许出现第二份 TtsKnobs 真值源。
mixer_src = (ROOT / "app/src/main/java/com/tingben/app/core/Mixer.kt").read_text(encoding="utf-8", errors="replace")
R31_ALL = ["VoiceDimension", "VoicePresets", "SamplingPresets", "Mixer.snapshot",
           "MIXER_PROFILE", "MIXER_AB", "Mixer.CHAIN", "调音台", "🎛 调音台",
           "Anatomy-First", "12 维", "A/B 试听", "MixerScreen", "Screen.MIXER",
           "当前生效配置", "恢复上游默认值"]
for sym in R31_ALL:
    if sym not in all_src: errs.append("缺少 R31 控制台符号: " + sym)
for sym in ["GENDER", "PITCH", "TIMBRE", "BREATH", "PERSONA",
            "SamplingPreset", "VoicePreset", "fun render()", "fun snapshot("]:
    if sym not in mixer_src: errs.append("Mixer.kt 缺少移植件形状: " + sym)
for pid in ["P01", "P02", "P03", "P06", "P10", "P11", "P21"]:
    if pid not in mixer_src: errs.append("Mixer.kt 缺少现成音色网格条目: " + pid)
for sid in ["upstream", "stable", "drama", "deterministic"]:
    if sid not in mixer_src: errs.append("Mixer.kt 缺少采样预设: " + sid)
if "Screen.MIXER -> MixerScreen" not in main_src:
    errs.append("R31 回退：调音台页没接进导航")
if "onMixer = { screen = Screen.MIXER }" not in main_src:
    errs.append("R31 回退：设置页没有调音台入口")
# 调音台只读汇总，不许自己存一份 TtsKnobs 副本
if "private val knobs" in mixer_src or "var knobs" in mixer_src:
    errs.append("R31 回退：Mixer.kt 出现第二份 TtsKnobs 真值源")
if "MIXER_AB" not in main_src:
    errs.append("R31 回退：A/B 试听没把完整参数写进日志")

notice = (ROOT / "docs/THIRD_PARTY_NOTICES.md").read_text(encoding="utf-8")
for who in ["Finrandojin", "Xydane", "MIT License", "ensemblereads", "ReaderVoice-Mobile"]:
    if who not in notice: errs.append("第三方声明缺少: " + who)

# R20 补强：mask_strings 会把 KDoc 里提前出现的 */ 当成闭合符抹掉，
# 于是上面那条检查对「KEY_*/DEFAULT_」这类注入恒不触发。改用原始文本行级检查兜底。
for p in kt_files:
    raw_src = p.read_text(encoding="utf-8", errors="replace")
    for no, line in enumerate(raw_src.splitlines(), 1):
        s = line.strip()
        if s.startswith('*') and '*/' in s and s != '*/':
            errs.append("KDoc 正文出现注释收尾符（原始文本）: %s:%d" % (p.relative_to(ROOT), no))

# ── U1 · 全局壳：只许改表现层，导航骨架/数据流必须守恒 ──────────────
chrome_path = ROOT / "app/src/main/java/com/tingben/app/AppChrome.kt"
chrome_src = chrome_path.read_text(encoding="utf-8", errors="replace") if chrome_path.exists() else ""
for sym in ["AppTopBar", "AppBottomBar", "RefreshStrip", "ChromeSpec",
            "TAB_LIBRARY", "TAB_TASKS", "TAB_AUDIO", "TAB_NONE"]:
    if sym not in chrome_src: errs.append("U1 缺壳层符号: " + sym)
for sym in ["chromeOf(screen)",
            "onMenu = { idx ->"]:
    if sym not in main_src: errs.append("U1 回退：MainActivity 没接壳层: " + sym)
# 调用必须是真实代码行，注释里出现同名字符串不算（反向验证抓到过这个漏洞）
for sym in ["AppTopBar", "AppBottomBar", "RefreshStrip"]:
    if not re.search(r"^\s*" + sym + r"\(", main_src, re.M):
        errs.append("U1 回退：MainActivity 没有真实调用 " + sym)
# 旧 UI 不许残留（产品名/定位/文字刷新按钮/4 Tab 组件/注释式提示）
for sym in ["⟳ 刷新", "TabButton(", '把小说变成能听的音频", fontSize = 11',
            "下拉刷新 · 或点右上角", '听本", fontSize = 22']:
    if sym in main_src: errs.append("U1 回退：旧 UI 残留 " + sym)
# 导航骨架守恒：这些一行都不许动
for sym in [
    "BackHandler(enabled = screen != Screen.LIBRARY) { screen = Screen.LIBRARY }",
    "var modelsFrom by remember { mutableStateOf(Screen.LIBRARY) }",
    "onBack = { screen = modelsFrom }",
    "screen = Screen.LIBRARY",
]:
    if sym not in main_src: errs.append("U1 回退：导航骨架被改动 " + sym)
enum_m = re.search(r"private enum class Screen \{([^}]*)\}", main_src)
if enum_m:
    got = set(re.findall(r"\b[A-Z_]+\b", enum_m.group(1)))
    want = {"LIBRARY", "PRODUCE", "PROGRESS", "TASKS", "AUDIO",
            "SETTINGS", "MODELS", "CAST", "MIXER"}
    if got != want: errs.append("U1 回退：Screen 枚举被改 " + str(got ^ want))
else:
    errs.append("U1 回退：找不到 Screen 枚举")
# mask_strings 保持字符数不变，用它做括号配对定位，避免正则被内层 } 提前截断
masked_main = mask_strings(main_src)
head = "when (screen) {"
start = masked_main.find(head)
if start >= 0:
    open_i = start + len(head) - 1  # 指向那个 '{'
    depth = 0
    end = None
    for k in range(open_i, len(masked_main)):
        if masked_main[k] == "{": depth += 1
        elif masked_main[k] == "}":
            depth -= 1
            if depth == 0:
                end = k
                break
    block = main_src[open_i:(end + 1)] if end else ""
    n = len(re.findall(r"Screen\.[A-Z_]+\s*->", block))
    if n != 9: errs.append("U1 回退：when(screen) 分发分支 %d 个，应为 9" % n)
else:
    errs.append("U1 回退：找不到 when(screen) 分发")
# chromeOf 必须覆盖全部 9 个目的地（漏一个 = 那一页顶栏标题为空、Tab 不高亮）
co_m = re.search(r"private fun chromeOf\(s: Screen\): ChromeSpec = when \(s\) \{(.*?)\n\}",
                 main_src, re.S)
if co_m:
    covered = set(re.findall(r"Screen\.([A-Z_]+)\s*->", co_m.group(1)))
    if len(covered) != 9: errs.append("U1 回退：chromeOf 只覆盖 %d/9 个目的地" % len(covered))
else:
    errs.append("U1 回退：找不到 chromeOf 映射")
# 底栏只能有 3 个顶层 Tab，且不含「设置」
tabs_m = re.search(r"TOP_TABS\s*=\s*listOf\((.*?)\n\)", chrome_src, re.S)
if tabs_m:
    body = tabs_m.group(1)
    if body.count(" to ") != 3 or "设置" in body:
        errs.append("U1 回退：顶层 Tab 不是 3 个或仍含设置入口")
else:
    errs.append("U1 回退：找不到 TOP_TABS")
# 下拉刷新机制保留、阈值不变（必须查代码行，注释里也写了 55f，会被字符串包含骗过）
if "if (acc > 55f)" not in chrome_src or "onRefresh()" not in chrome_src:
    errs.append("U1 回退：下拉刷新机制或阈值被改动")
# 真 bug 回归：删书必须触发刷新（R33 起走确认弹窗）
if "BookStore.delete(c, id); confirmDelete = null; onChanged()" not in main_src:
    errs.append("U1 回退：删书后没调 onChanged()，列表不会刷新")
# 括号平衡（用项目自带的 mask_strings，避免字符串/注释里的括号误判）
for p in kt_files:
    masked = mask_strings(p.read_text(encoding="utf-8", errors="replace"))
    depth = 0
    ok = True
    for ch in masked:
        if ch == "{": depth += 1
        elif ch == "}":
            depth -= 1
            if depth < 0: ok = False; break
    if not ok or depth != 0:
        errs.append("U1 括号不平衡: %s (depth=%d)" % (p.relative_to(ROOT), depth))

# ── R33 · UI 返工：安全区 / 零文案刷新 / 一级菜单 / 全局清注释 ─────────
def ui_literals(src):
    """只取字符串字面量；注释里提到「下拉刷新」是说明，不算 UI 文案。"""
    return re.findall(r'"((?:\\.|[^"\\])*)"', src)

R33_MUST = ["statusBarsPadding()", "taskExpanded", "confirmDelete",
            "AlertDialog(", "onMenu = { idx ->",
            "BookStore.delete(c, id); confirmDelete = null; onChanged()",
            ".clickable { taskExpanded = !taskExpanded }"]
for sym in R33_MUST:
    if sym not in main_src: errs.append("R33 缺新壳层符号: " + sym)
for sym in ["DropdownMenu(", "MENU_ITEMS", "width(if (pulled)"]:
    if sym not in chrome_src: errs.append("R33 缺壳层符号: " + sym)
R33_BAN = ["下拉刷新", "松开即可刷新", "已刷新", "把小说变成能听的音频",
           "书架只放原书", "每块约 500 字", "算完一块才会往上走一格",
           "下滑收起细节", "本页 = 长期默认", "关于后台运行",
           "每次制作产出一条成品", "听本 · 本地离线把小说变成能听的音频",
           "①②③ 仅本次、不写回设置", "这台手机给不起模型要的思考长度",
           "同一句文本、同一音色描述"]
for src_name, src in [("MainActivity", main_src), ("AppChrome", chrome_src)]:
    lits = ui_literals(src)
    for ban in R33_BAN:
        if any(ban in x for x in lits):
            errs.append("R33 回退：%s 又出现注释式文案 %s" % (src_name, ban))
if "statusBarsPadding()" not in main_src:
    errs.append("R33 回退：顶栏又贴回状态栏")

if errs:
    print("\n".join("ERROR " + e for e in errs)); sys.exit(1)
print("自检通过: %d 项必备文件 / 权重泄漏 0 / 无 INTERNET / 包名正确 / KDoc 坑 0 / 审计符号 %d 项 / R22 符号 %d 项 / R23 符号 %d 项 / R24 符号 %d 项 / R25 符号 %d 项 / R26 符号 %d 项 / R31 符号 %d 项 / native 早停 3 项"
      % (len(REQUIRED), len(AUDIT_SYMBOLS), len(R22_SYMBOLS), len(R23_SYMBOLS), len(R24_SYMBOLS),
         len(R25_ALL) + len(R25_MAIN), len(R26_ALL) + 5 + 5, len(R31_ALL) + 11 + 7 + 4))
