#!/usr/bin/env python3
"""听本 · CI 自检：必备文件 / 权重泄漏 / 无网络权限 / 包名 / KDoc 坑 / 审计符号。只依赖标准库。"""
from pathlib import Path
import os, re, sys
ROOT = Path(__file__).resolve().parent.parent
errs = []
REQUIRED = ["README.md", ".gitignore", "settings.gradle.kts", "build.gradle.kts",
 "gradle/libs.versions.toml", "gradle.properties", "app/build.gradle.kts",
 "app/src/main/AndroidManifest.xml",
 "app/src/main/cpp/CMakeLists.txt", "app/src/main/cpp/tingben_native.cpp",
 "app/src/main/java/com/tingben/app/MainActivity.kt",
 "app/src/main/java/com/tingben/app/ProduceService.kt",
 "app/src/main/java/com/tingben/app/diag/EventLog.kt",
 "docs/THIRD_PARTY_NOTICES.md",
 "scripts/build_native.sh", "scripts/check_structure.py", ".github/workflows/ci.yml", "keystore.jks",
 "contracts/model-manifest.json", "contracts/pipeline_spec.md",
 "contracts/sampling-defaults.h", "scripts/r92_verify.py",
 "app/src/main/java/com/tingben/app/core/ParamSpec.kt"]
for f in REQUIRED:
    if not (ROOT / f).exists(): errs.append("缺少必备文件: " + f)

# R68 · P1b：旧标注管线四个文件已物理删除，不许回潮。
R68_BANNED_FILES = ["app/src/main/java/com/tingben/app/diag/AuditLog.kt",
 "app/src/main/java/com/tingben/app/core/JsonSalvage.kt",
 "app/src/main/java/com/tingben/app/core/Adoption.kt",
 "app/src/main/java/com/tingben/app/diag/RawDump.kt"]
for f in R68_BANNED_FILES:
    if (ROOT / f).exists(): errs.append("R68 回潮：旧标注管线文件仍在 " + f)

# R66 · 语言模型退场：提示词资产不许再进包（源码/契约两处都不留占位）
for p in list((ROOT / "app/src/main/assets").glob("prompt_zh*")) + list((ROOT / "contracts").glob("prompt_zh*")):
    errs.append("R66 回退：仍带语言模型提示词资产 " + p.relative_to(ROOT).as_posix())

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

# R68 · P1b：语言模型退场，审计链路符号一律改成「出现即红」。
all_src = "\n".join(p.read_text(encoding="utf-8", errors="replace") for p in kt_files)
R68_GONE_A = ["AUDIT_RUN_BEGIN", "AUDIT_SUMMARY", "AbGate", "KEY_THINKING", "PROMPT_VERSION",
              "parseWithMeta", "ruleChecks", "BlockAudit", "ANN_RAW", "ANN_PREVIEW",
              "ANN_EMPTY", "ANN_AUDIT", "summaryText", "previewText", "contentText"]
for sym in R68_GONE_A:
    if sym in all_src: errs.append("R68 回潮：旧审计符号 " + sym)
for sym in ["readDetail", "ringTail", "resetEngine", "lastCrash"]:
    if sym not in all_src: errs.append("缺少软件事件日志符号: " + sym)

# R68 · 单一路径后仍活的主链符号（不许被顺手删掉）。
R68_KEEP_A = ["TTS_INPUT", "SPLIT_STATS", "directWithStats", "lang=chinese",
              "qt_tts_params", "TTS_PARAMS_APPLIED", "chapterIndices"]
for sym in R68_KEEP_A:
    if sym not in all_src: errs.append("缺少 R68 主链符号: " + sym)
R68_GONE_B = ["ANN_CONTENT", "ANN_DISPOSITION", "Annotator.reconcile", "contentJson",
              "contentLines", "annsJson", "disposition", "复制标注全文"]
for sym in R68_GONE_B:
    if sym in all_src: errs.append("R68 回潮：旧逐条采纳符号 " + sym)

pipe_src = (ROOT / "app/src/main/java/com/tingben/app/core/Pipeline.kt").read_text(encoding="utf-8", errors="replace")
# R68 · 逐字无损断句仍在，不许退回按字数腰斩。
if "a.text.take(70)" in pipe_src: errs.append("R68 回退：又把正文截成 70 字")

cpp_src = (ROOT / "app/src/main/cpp/tingben_native.cpp").read_text(encoding="utf-8", errors="replace")
for sym in ["NATIVE_JSON_CLOSE", "jsonDepth", "jsonStarted"]:
    if sym not in cpp_src: errs.append("native 缺少 JSON 闭合早停: " + sym)

# R68 · 移植件整文件删除，出现即红；旧手搓实现同样不许回来。
R68_GONE_C = ["JsonSalvage", "Adoption", "RawDump", "ANN_RAW_DUMP", "ANN_ADOPT_DETAIL",
              "复制最近一块模型原始产出", "cleanInstruct", "THINK_UNCLOSED",
              "bracket", "DEFAULT_INSTRUCT", "ModelStore.llm", "NativeEngine.llmLoad"]
for sym in R68_GONE_C:
    if sym in all_src: errs.append("R68 回潮：旧移植件符号 " + sym)
if "= raw.lastIndexOf(']')" in pipe_src:
    errs.append("R68 回退：又用 lastIndexOf 找 JSON 闭合")
if "o.optString(\"instruct\", \"平稳叙述\")" in pipe_src:
    errs.append("R68 回退：又出现手搓解析默认指令")

# R25 · 断句移植件 / 设置分层 / 日志不许把零产出写成成功。
main_src = (ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt").read_text(encoding="utf-8", errors="replace")
R25_ALL = ["TextSplitter", "SPLIT_STATS", "directWithStats"]
for sym in R25_ALL:
    if sym not in all_src: errs.append("缺少 R25 符号: " + sym)
# R54 · 设置页重设计后不再有圈码分组标题，改验四分区组件与作用域文案。
R25_MAIN = ["private fun GroupLabel(", "private fun SettingsSection(",
            "SettingsSection(\"外观与显示\", \"软件\"", "仅本次", "作用域",
            "ACTION_MAIN 也被当成", "ProduceMode.DIRECT"]
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
           "ProduceService", "dataSync", "ProduceService.status()"]
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

# R68 · P1b：R27 那套「模型产出有没有被采纳」的判据随语言模型整套退场。
R68_GONE_D = ["ADOPT_0_KEPT", "NOT_FOUND_AFTER_", "srcModel", "adoptFirstDrop",
              "finalVerbatim", "未采纳首因", "模型产出未被采纳",
              "旧缓存未记来源", "最终进 TTS", "模型候选",
              "ANN_PHASE_HINT", "ANN_BLOCK_END", "用户中断（本次不计产出）",
              "已截断，全文见诊断页", "16384"]
for sym in R68_GONE_D:
    if sym in all_src: errs.append("R68 回潮：旧标注判据符号 " + sym)

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
if "val base = if (sg.hasAudio) Good else Line" not in main_src:
    errs.append("R91 回退：看台不是绿/灰两态（或退回三态着色）")

# R31 · 调音台：12 维网格 / 4 组采样预设 / 生效快照 / A/B 日志必须真在源码里。
# 反向断言：不许把参数埋回折叠里当唯一入口、不许出现第二份 TtsKnobs 真值源。
mixer_src = (ROOT / "app/src/main/java/com/tingben/app/core/Mixer.kt").read_text(encoding="utf-8", errors="replace")
# R41a · A/B 对比换成「草稿 / 已生效」两态一步试听；日志事件同步改名。
R31_ALL = ["VoiceDimension", "VoicePresets", "SamplingPresets", "Mixer.snapshot",
           "MIXER_PROFILE", "MIXER_TRIAL", "Mixer.CHAIN", "调音台", "private fun SettingsSection(",
           "Anatomy-First", "12 维", "试听未提交参数", "试听已生效参数",
           "MixerScreen", "Screen.MIXER", "恢复上游默认"]
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
if "onMixer = { drawerReturn = Screen.SETTINGS; screen = Screen.MIXER }" not in main_src:
    errs.append("R31 回退：设置页没有调音台入口")
# 调音台只读汇总，不许自己存一份 TtsKnobs 副本
if "private val knobs" in mixer_src or "var knobs" in mixer_src:
    errs.append("R31 回退：Mixer.kt 出现第二份 TtsKnobs 真值源")
if "MIXER_TRIAL" not in main_src:
    errs.append("R41a 回退：两态试听没把完整参数写进日志")
# R54 · 设置页重设计：界面里不许再出现跳转卡 / 圈码 / 跨页大按钮 / 教程腔。
_s54 = main_src.split("private fun SettingsScreen(")[-1].split("// ── ⑤b 模型页")[0]
for _banned in ["🎛 调音台", "固定音色", "音色 12 维 · 采样 9 项", "去书架制作",
                "①", "②", "提交变更", "共用一份文件表", "R45_FIXED_VOICE_ENTRY"]:
    if _banned in _s54:
        errs.append("R54 回退：设置页残留 " + _banned)
for s in ["A/B 试听", "合成 A", "▶ 听 A", "MIXER_AB", "bPresetId", "对比用文本",
          "B 用哪组预设", "fileA", "abMsg"]:
    if s in main_src: errs.append("R41a 回退：旧 A/B 试听残留 " + s)

notice = (ROOT / "docs/THIRD_PARTY_NOTICES.md").read_text(encoding="utf-8")
for who in ["Finrandojin", "Xydane", "MIT License", "ensemblereads", "ReaderVoice-Mobile"]:
    if who not in notice: errs.append("第三方声明缺少: " + who)

# R20 补强：mask_strings 会把 KDoc 里提前出现的 */ 当成闭合符抹掉，
# 于是上面那条检查对「KEY_*/DEFAULT_」这类注入恒不触发。改用原始文本行级检查兜底。
for p in kt_files:
    raw_src = p.read_text(encoding="utf-8", errors="replace")
    for no, line in enumerate(raw_src.splitlines(), 1):
        s = line.strip()
        if s.startswith('*') and '*/' in s:
            # 行尾的 */ 是合法收尾（单行 KDoc 会把正文与收尾写在同一行）；
            # 只拦正文中间出现、其后还有内容的 */ —— 那才会提前闭合块注释。
            body = s[:-2].rstrip() if s.endswith('*/') else s
            if '*/' in body:
                errs.append("KDoc 正文出现注释收尾符（原始文本）: %s:%d" % (p.relative_to(ROOT), no))

# ── U1 · 全局壳：只许改表现层，导航骨架/数据流必须守恒 ──────────────
chrome_path = ROOT / "app/src/main/java/com/tingben/app/AppChrome.kt"
chrome_src = chrome_path.read_text(encoding="utf-8", errors="replace") if chrome_path.exists() else ""
for sym in ["AppTopBar", "AppDrawerSheet", "RefreshStrip", "ChromeSpec",
            "TAB_LIBRARY", "TAB_TASKS", "TAB_AUDIO", "TAB_NONE",
            "NAV_LIBRARY", "NAV_TASKS", "NAV_AUDIO", "NAV_SETTINGS",
            "NAV_MIXER", "NAV_MODELS",
            "ModalDrawerSheet(", "NavigationDrawerItem("]:
    if sym not in chrome_src: errs.append("U1 缺壳层符号: " + sym)
for sym in ["chromeOf(screen)",
            "onMenu = { scope.launch { drawerState.open() } }"]:
    if sym not in main_src: errs.append("U1 回退：MainActivity 没接壳层: " + sym)
# 调用必须是真实代码行，注释里出现同名字符串不算（反向验证抓到过这个漏洞）
for sym in ["AppTopBar", "AppDrawerSheet", "RefreshStrip"]:
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
# R61 · 目的地随版本增长：白名单显式列出，数量一律由它推算，不再写死 10。
WANT_SCREENS = {"LIBRARY", "PRODUCE", "PROGRESS", "TASKS", "AUDIO",
                "SETTINGS", "MODELS", "MIXER", "CHAPTERS"}
enum_m = re.search(r"private enum class Screen \{([^}]*)\}", main_src)
if enum_m:
    got = set(re.findall(r"\b[A-Z_]+\b", enum_m.group(1)))
    if got != WANT_SCREENS: errs.append("U1 回退：Screen 枚举被改 " + str(got ^ WANT_SCREENS))
else:
    errs.append("U1 回退：找不到 Screen 枚举")
# mask_strings 保持字符数不变，用它做括号配对定位，避免正则被内层 } 提前截断
masked_main = mask_strings(main_src)
# R46：App() 里新增了 drawerSelected / from 两个 when(screen)，
# 用「Box + 分发 when」这段带上下文的锚点定位真正的 9 分支，避免切错块。
head = "Box(Modifier.weight(1f)) {\n            when (screen) {"
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
    if n != len(WANT_SCREENS): errs.append("U1 回退：when(screen) 分发分支 %d 个，应为 %d" % (n, len(WANT_SCREENS)))
else:
    errs.append("U1 回退：找不到 when(screen) 分发")
# chromeOf 必须覆盖全部目的地（漏一个 = 那一页顶栏标题为空、Tab 不高亮）
co_m = re.search(r"private fun chromeOf\(s: Screen\): ChromeSpec = when \(s\) \{(.*?)\n\}",
                 main_src, re.S)
if co_m:
    covered = set(re.findall(r"Screen\.([A-Z_]+)\s*->", co_m.group(1)))
    if covered != WANT_SCREENS: errs.append("U1 回退：chromeOf 没覆盖全部目的地 " + str(covered ^ WANT_SCREENS))
else:
    errs.append("U1 回退：找不到 chromeOf 映射")
# R89 · 抽屉一列：一级 4 项（调音台与选项卡平级）+ 更多 2 项，总数恒 6
tabs_m = re.search(r"DRAWER_TOP\s*=\s*listOf\((.*?)\n\)", chrome_src, re.S)
more_m = re.search(r"DRAWER_MORE\s*=\s*listOf\((.*?)\n\)", chrome_src, re.S)
if tabs_m:
    body = tabs_m.group(1)
    if body.count(" to ") != 4 or "设置" in body or "调音台" not in body:
        errs.append("U1 回退：抽屉一级不是 4 项 / 调音台不在一级 / 混入了设置")
else:
    errs.append("U1 回退：找不到 DRAWER_TOP")
if more_m:
    body2 = more_m.group(1)
    if body2.count(" to ") != 2 or "调音台" in body2:
        errs.append("U1 回退：更多组不是 2 项或调音台仍留在更多组")
    if "语言模型" in body2 or "文本处理" in body2:
        errs.append("U1 回退：抽屉仍含已删模块")
else:
    errs.append("U1 回退：找不到 DRAWER_MORE")
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

# R65 · 贴底任务卡整条删除，taskExpanded 两个符号随之消失；
# 换成仍存活的 R61 悬浮条与新进度页入口，断言强度不降。
R33_MUST = ["statusBarsPadding()", "MiniPlayBar(ctx, miniBook", "confirmDelete",
            "AlertDialog(", "onMenu = { scope.launch { drawerState.open() } }",
            "BookStore.delete(c, id); confirmDelete = null; onChanged()",
            "private fun ProgressScreen("]
for sym in R33_MUST:
    if sym not in main_src: errs.append("R33 缺新壳层符号: " + sym)
for sym in ["ModalDrawerSheet(", "NavigationDrawerItem(", "width(if (pulled)"]:
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

# ── R36 · 调音台重设计：折叠分组 / 自定义预设 / 12 维 / 9 采样 ──
# R77 · 角色卡整套退场：CastPresets / seedIfEmpty 已随文件删除，换成迁移期符号
R36_ALL = ["MixerSection(", "MixerPresetStore", "MixerPreset(",
           "legacyVoiceStamp", "VOICE_STAMP_MIGRATE", "MIXER_PRESET", "MIXER_COMMIT",
           "本机数据",
           "单次音频长度上限", "TIMING_NEXT", "同名覆盖",
           "PRESET_SCHEMA", "safeName", "fun coverage()", "logPreset("]
for sym in R36_ALL:
    if sym not in all_src: errs.append("缺少 R36 控制台符号: " + sym)
for sym in ["private fun MixerSection(", "Screen.MIXER -> MixerScreen",
            "onMixer = { drawerReturn = Screen.SETTINGS; screen = Screen.MIXER }",
            "t.config else Prefs.defaultPerf(c)"]:
    if sym not in main_src: errs.append("缺少 R36 界面符号: " + sym)
# 反向：设置页不许再画第二遍 9 项采样，不许出现第二份真值源
for banned in ["声音采样（进阶）", "putKnobs(", "descDraft"]:
    if banned in main_src: errs.append("R36 回退：设置页又出现第二套采样 " + banned)
if "var knobs" in mixer_src:
    errs.append("R36 回退：Mixer.kt 出现第二份 TtsKnobs 真值源")
# R46：drawerSelected 的 when 里也有一行 Screen.MIXER ->，只数真实分发调用
if main_src.count("Screen.MIXER -> MixerScreen") != 1:
    errs.append("R36 回退：调音台导航分支异常")
bld = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8", errors="replace")
for banned in ["compose.animation", "material-icons-extended", "accompanist"]:
    if banned in bld: errs.append("R36 回退：为折叠动画引了新依赖 " + banned)

# ── R46 · V1 抽屉壳：一整页抽屉 / 返回键优先 / 底部安全区 / 旧壳零残留 ──
for sym in ["DismissibleNavigationDrawer(", "drawerState = drawerState",
            "gesturesEnabled = true", "drawerContent = {",
            "AppDrawerSheet(", "rememberDrawerState(DrawerValue.Closed)",
            "BackHandler(enabled = drawerState.isOpen)",
            "scope.launch { drawerState.close() }",
            ".navigationBarsPadding()", "drawerReturn"]:
    if sym not in main_src: errs.append("R46/R51 缺 V1 抽屉符号: " + sym)
# R51 · 覆盖式抽屉必须真的换掉（注释里提到名字不带 "("，不算命中）
if "ModalNavigationDrawer(" in main_src:
    errs.append("R51 回退：又用回覆盖式 ModalNavigationDrawer(")
# 后注册的 callback 优先：抽屉 BackHandler 必须紧跟在原 BackHandler 之后
i_old = main_src.find("BackHandler(enabled = screen != Screen.LIBRARY)")
i_new = main_src.find("BackHandler(enabled = drawerState.isOpen)")
if i_old < 0 or i_new < 0 or i_new <= i_old:
    errs.append("R46 回退：抽屉 BackHandler 没排在原 BackHandler 之后")
# 旧壳零残留（底栏 / 下拉菜单 / 旧 Tab 表 / 旧菜单回调）
for sym in ["AppBottomBar(", "DropdownMenu(", "MENU_ITEMS", "TOP_TABS",
            "onMenu = { idx ->", "AppTab("]:
    if sym in main_src or sym in chrome_src:
        errs.append("R46 回退：旧壳残留 " + sym)
# 二级页返回回「进入前的那一页」，不再写死 SETTINGS
for sym in ["Screen.MIXER -> MixerScreen(ctx, onBack = { screen = drawerReturn })"]:
    if sym not in main_src: errs.append("R46 回退：二级页返回没回来源 " + sym)

# ── R38 · 调音台重做：首屏身份输入 / 官方字段一一对应 / 触达证据 ──
R38_ALL = ["ParamCatalog", "ParamSpec", "TTS_PARAMS", "音色身份", "提交变更",
           "恢复本组官方默认", "采样参数 · 9 项真到模型", "声音描述"]
for sym in R38_ALL:
    if sym not in all_src: errs.append("缺少 R38 控制台符号: " + sym)
cpp_src = (ROOT / "app/src/main/cpp/tingben_native.cpp").read_text(encoding="utf-8", errors="replace")
if "TTS_PARAMS_APPLIED" not in cpp_src:
    errs.append("R38 回退：JNI 缺引擎实收参数日志")
if 'null, "auto"' in main_src:
    errs.append("R38 回退：试音又用 auto，与主链 chinese 不一致")
if 'MixerSection("音色身份"' in main_src:
    errs.append("R38 回退：音色身份又藏进折叠组")
if "ParamCatalog.find(" not in main_src:
    errs.append("R41 回退：采样参数没有走 ParamCatalog 单一目录")
if "ParamCatalog.SAMPLING.forEach" in main_src:
    errs.append("R41 回退：九项又退回一张张平铺卡（没走分组）")

# ── R40 · 调音台统一提交：唯一编辑态 / 唯一出口 / 旧生效动词零残留 ──────
draft_path = ROOT / "app/src/main/java/com/tingben/app/core/MixerDraft.kt"
draft_src = draft_path.read_text(encoding="utf-8", errors="replace") if draft_path.exists() else ""
R40_ALL = ["MixerDraft", "MixerCommit", "MIXER_COMMIT", "fun diff(", "fun commit(",
           "TIMING_NOW", "TIMING_NEXT", "active(c: Context)", "查看差异", "提交变更",
           "未提交变更", "丢弃全部待提交", "TimingHint(", "有未提交变更",
           "当前生效全貌"]
for sym in R40_ALL:
    if sym not in all_src: errs.append("缺少 R40 统一提交符号: " + sym)
# 唯一出口：MixerScreen 区块里禁止出现任何直接写 Prefs 的调用
blk_i = main_src.find("// ── ⑥ 调音台")
mixer_block = main_src[blk_i:] if blk_i >= 0 else ""
for banned in ["Prefs.setTtsKnobs(", "Prefs.setVoiceDescription(", "Prefs.setDefaultPerf(",
               "Prefs.setNonVerbal(", "Prefs.resetTtsKnobs("]:
    if banned in mixer_block:
        errs.append("R40 回退：界面里出现第二个生效写入口 " + banned)
if "MixerCommit.commit(c, draft, n)" not in mixer_block:
    errs.append("R40 回退：界面没走唯一出口 MixerCommit.commit")
if draft_src.count("Prefs.set") != 4:
    errs.append("R40 回退：commit 写入口不是 4 个（实际 %d）" % draft_src.count("Prefs.set"))
if draft_src.count("put(") != 10:  # R76 起 9 次字段调用 + 1 次 put() 定义
    errs.append("R40 回退：diff 源码调用数不是 9（实际 %d）" % (draft_src.count("put(") - 1))
# 旧生效动词（只查字符串字面量，注释里描述旧 bug 不算）
lits = ui_literals(main_src) + ui_literals(draft_src)
for banned in ["设定", "套用", "保存并生效", "直接保存生效", "应用预设"]:
    if any(banned in x for x in lits):
        errs.append("R40 回退：又出现旧生效动词 " + banned)
# 默认展开（R41）：身份生成器开、采样组收；展开态由 Prefs 持久化，不再靠字面 true/false
prefs_src = (ROOT / "app/src/main/java/com/tingben/app/core/Prefs.kt").read_text(encoding="utf-8", errors="replace")
if '?: ""' not in prefs_src:
    errs.append("R45 回退：展开态默认值不是全部折叠")
# R92 · 调音台整页重做：只守新形态，不再要求已删分区存在
for sym in ['MixerSection("采样参数 · 9 项真到模型", "sampling",',
            'samplingOpen', 'trialOpen', 'val mode = remember(tick) { ModelStore.currentMode(c) }',
            'OutlinedTextField(', 'submit' if False else '提交变更']:
    if sym not in main_src: errs.append("R92 回退：新调音台缺 " + sym)
_mixer = main_src[main_src.index("private fun MixerScreen("):]
for banned in ["音色身份生成器", "固定音色 · 官方模式说明", "哪些能改 · 和模型一一对齐",
               "参数触达与官方资料", "openMap.contains", "showVoiceEdit", "AnchorBar(",
               "snapshotFlow { scroll.value }"]:
    if banned in _mixer: errs.append("R92 回退：旧调音台形态残留 " + banned)

# R66 · 1.1.0 语言模型退场：4 条旧说法不许以界面字符串回潮。
# 注释里描述旧 bug 不算；只认字符串字面量，和 r66_verify.py 的口径一致。
R66_GONE = ["查看标注内容", "查看模型原始产出", "零标注 ⚠️ 白跑", "标注后合成（实验性）"]
_all_ui = []
for p in kt_files:
    _all_ui += ui_literals(p.read_text(encoding="utf-8", errors="replace"))
def r66_errs(lits):
    return ["R66 回退：又出现已退场文案 " + s for s in R66_GONE if any(s in x for x in lits)]
errs += r66_errs(_all_ui)
if "--selftest" in sys.argv:
    probe = ui_literals('Text("标注后合成（实验性）")')
    ok = bool(r66_errs(_all_ui + probe)) and not r66_errs(_all_ui)
    print("R66 结构自检注入:", "PASS" if ok else "FAIL")
    if not ok: sys.exit(1)

if errs:
    print("\n".join("ERROR " + e for e in errs)); sys.exit(1)
print("自检通过: %d 项必备文件 / 权重泄漏 0 / 无 INTERNET / 包名正确 / KDoc 坑 0 / R68 旧符号零残留 %d 项 / 主链 %d 项 / R25 符号 %d 项 / R26 符号 %d 项 / R31 符号 %d 项 / native 早停 3 项"
      % (len(REQUIRED), len(R68_GONE_A) + len(R68_GONE_B) + len(R68_GONE_C) + len(R68_GONE_D),
         len(R68_KEEP_A), len(R25_ALL) + len(R25_MAIN),
         len(R26_ALL) + 5 + 5, len(R31_ALL) + 11 + 7 + 4))
