#!/usr/bin/env python3
"""听本 · CI 自检：必备文件 / 权重泄漏 / 无网络权限 / 包名 / KDoc 坑 / 审计符号。只依赖标准库。"""
from pathlib import Path
import os, re, sys
ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(Path(__file__).resolve().parent))
import nav_semantics as nav
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
 "contracts/sampling-defaults.h", "scripts/r98_verify.py",
 "app/src/main/java/com/tingben/app/core/ParamSpec.kt"]
for f in REQUIRED:
    if not (ROOT / f).exists(): errs.append("缺少必备文件: " + f)

R68_BANNED_FILES = ["app/src/main/java/com/tingben/app/diag/AuditLog.kt",
 "app/src/main/java/com/tingben/app/core/JsonSalvage.kt",
 "app/src/main/java/com/tingben/app/core/Adoption.kt",
 "app/src/main/java/com/tingben/app/diag/RawDump.kt"]
for f in R68_BANNED_FILES:
    if (ROOT / f).exists(): errs.append("R68 回潮：旧标注管线文件仍在 " + f)

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

for p in kt_files:
    masked = mask_strings(p.read_text(encoding="utf-8", errors="replace"))
    for no, line in enumerate(masked.splitlines(), 1):
        s = line.strip()
        if s.startswith('*') and '*/' in s and s != '*/':
            errs.append("%s:%d KDoc 正文出现注释收尾符（会提前闭合块注释）"
                        % (p.relative_to(ROOT), no))

all_src = "\n".join(p.read_text(encoding="utf-8", errors="replace") for p in kt_files)
R68_GONE_A = ["AUDIT_RUN_BEGIN", "AUDIT_SUMMARY", "AbGate", "KEY_THINKING", "PROMPT_VERSION",
              "parseWithMeta", "ruleChecks", "BlockAudit", "ANN_RAW", "ANN_PREVIEW",
              "ANN_EMPTY", "ANN_AUDIT", "summaryText", "previewText", "contentText"]
for sym in R68_GONE_A:
    if sym in all_src: errs.append("R68 回潮：旧审计符号 " + sym)
for sym in ["readDetail", "ringTail", "resetEngine", "lastCrash"]:
    if sym not in all_src: errs.append("缺少软件事件日志符号: " + sym)

R68_KEEP_A = ["TTS_INPUT", "SPLIT_STATS", "directWithStats", "lang=chinese",
              "qt_tts_params", "MIXER_TRIAL", "chapterIndices"]
for sym in R68_KEEP_A:
    if sym not in all_src: errs.append("缺少 R68 主链符号: " + sym)
R68_GONE_B = ["ANN_CONTENT", "ANN_DISPOSITION", "Annotator.reconcile", "contentJson",
              "contentLines", "annsJson", "disposition", "复制标注全文"]
for sym in R68_GONE_B:
    if sym in all_src: errs.append("R68 回潮：旧逐条采纳符号 " + sym)

pipe_src = (ROOT / "app/src/main/java/com/tingben/app/core/Pipeline.kt").read_text(encoding="utf-8", errors="replace")
if "a.text.take(70)" in pipe_src: errs.append("R68 回退：又把正文截成 70 字")

cpp_src = (ROOT / "app/src/main/cpp/tingben_native.cpp").read_text(encoding="utf-8", errors="replace")
for sym in ["NATIVE_JSON_CLOSE", "jsonDepth", "jsonStarted"]:
    if sym not in cpp_src: errs.append("native 缺少 JSON 闭合早停: " + sym)

R68_GONE_C = ["JsonSalvage", "Adoption", "RawDump", "ANN_RAW_DUMP", "ANN_ADOPT_DETAIL",
              "复制最近一块模型原始产出", "cleanInstruct", "THINK_UNCLOSED",
              "bracket", "DEFAULT_INSTRUCT", "ModelStore.llm", "NativeEngine.llmLoad"]
for sym in R68_GONE_C:
    if sym in all_src: errs.append("R68 回潮：旧移植件符号 " + sym)
if "= raw.lastIndexOf(']')" in pipe_src:
    errs.append("R68 回退：又用 lastIndexOf 找 JSON 闭合")
if "o.optString(\"instruct\", \"平稳叙述\")" in pipe_src:
    errs.append("R68 回退：又出现手搓解析默认指令")

main_src = (ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt").read_text(encoding="utf-8", errors="replace")
R25_ALL = ["TextSplitter", "SPLIT_STATS", "directWithStats"]
for sym in R25_ALL:
    if sym not in all_src: errs.append("缺少 R25 符号: " + sym)
R25_MAIN = ["private fun GroupLabel(", "private fun SettingsSection(",
            "SettingsSection(\"外观与显示\", \"软件\"", "directRead = true",
            "private fun ProduceScreen("]
for sym in R25_MAIN:
    if sym not in main_src: errs.append("缺少 R25 界面符号: " + sym)
if re.search(r'enum class ProduceMode\s*\{[^}]*,', main_src):
    errs.append("R114 回潮：制作档位又出现多档枚举")
if re.search(r"if\s*\(\s*buf\.length\s*>=\s*110", pipe_src):
    errs.append("R25 回退：又出现按 110 字腰斩的旧切分")
if "mutableStateOf(if (t != null && t.bookId == id && t.direct)" in main_src:
    errs.append("R25 回退：新书默认又变回标注档")

for perm in ["android.permission.FOREGROUND_SERVICE",
             "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
             "android.permission.POST_NOTIFICATIONS",
             "android.permission.WAKE_LOCK"]:
    if perm not in m: errs.append("Manifest 缺少 R26 权限: " + perm)
if 'android:name=".ProduceService"' not in m: errs.append("Manifest 未注册 ProduceService")
fgs = re.search(r'android:foregroundServiceType="([^"]+)"', m)
fgs_types = fgs.group(1).split("|") if fgs else []
if "dataSync" not in fgs_types: errs.append("前台服务类型缺 dataSync 低版本分支")
if any(t not in ("dataSync", "mediaProcessing") for t in fgs_types):
    errs.append("前台服务类型出现未复核的类型")
for banned in ["BIND_ACCESSIBILITY_SERVICE", "SYSTEM_ALERT_WINDOW", "systemExempted",
               "foregroundServiceType=\"mediaPlayback\""]:
    if banned in m: errs.append("R26 回退：出现了明确不做的权限/类型 " + banned)

fgs_src = (ROOT / "app/src/main/java/com/tingben/app/ProduceService.kt").read_text(encoding="utf-8", errors="replace")
R26_ALL = ["FGS_BEGIN", "FGS_READY", "FGS_FINISH", "FGS_DESTROY", "FGS_WAKELOCK",
           "FGS_CANCEL", "FGS_NOTIF_PERMISSION", "FGS_START_FAIL", "OpenMultipleDocuments()",
           "PARTIAL_WAKE_LOCK", "FOREGROUND_SERVICE_TYPE_DATA_SYNC", "UNIT_TIMEOUT_MS",
           "POST_NOTIFICATIONS", "DEFAULT_TRIAL_TEXT",
           "ProduceService", "dataSync", "Prefs.trialText(c)"]
for sym in R26_ALL:
    if sym not in all_src: errs.append("缺少 R26 符号: " + sym)
for call in ["ProduceService.begin(", "ProduceService.finish(", "ProduceService.update(",
             "ProduceService.touch(", "ProduceService.stopping("]:
    if call not in pipe_src: errs.append("Producer 缺少 R26 调用点: " + call)
for sym in ["FOREGROUND_SERVICE_TYPE_DATA_SYNC", "PARTIAL_WAKE_LOCK", "startForegroundCompat",
            "STOP_FOREGROUND_DETACH", "START_NOT_STICKY"]:
    if sym not in fgs_src: errs.append("ProduceService 缺少移植件形状: " + sym)
if "wl.acquire(UNIT_TIMEOUT_MS)" not in fgs_src:
    errs.append("R26 回退：唤醒锁没有按块带超时申请")
if "START_STICKY" in fgs_src:
    errs.append("R26 回退：前台服务变成自动重启")

R68_GONE_D = ["ADOPT_0_KEPT", "NOT_FOUND_AFTER_", "srcModel", "adoptFirstDrop",
              "finalVerbatim", "未采纳首因", "模型产出未被采纳",
              "旧缓存未记来源", "最终进 TTS", "模型候选",
              "ANN_PHASE_HINT", "ANN_BLOCK_END", "用户中断（本次不计产出）",
              "已截断，全文见诊断页", "16384"]
for sym in R68_GONE_D:
    if sym in all_src: errs.append("R68 回潮：旧标注判据符号 " + sym)

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
if "val base = if (playable) Good else Line" not in main_src:
    errs.append("R91/R103 回退：看台不是绿/灰两态，或可播判定不再同源")
if "pqSeg.label == label" in main_src:
    errs.append("R103 回退：格子又拿显示文字当播放身份")
if "val playable = sg.hasAudio && segId.isNotEmpty()" not in main_src:
    errs.append("R103 回退：格子缺少稳定 segmentId")

mixer_src = (ROOT / "app/src/main/java/com/tingben/app/core/Mixer.kt").read_text(encoding="utf-8", errors="replace")
R31_ALL = ["VoiceDimension", "VoicePresets", "SamplingPresets", "Mixer.snapshot",
           "MIXER_PROFILE", "MIXER_TRIAL", "ParamLine(", "调音台", "private fun SettingsSection(",
           "12 维", "试听 · 已保存", "试听 · 当前",
           "MixerScreen", "Screen.MIXER", "全部恢复默认"]
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
if not nav.mixer_entries(main_src):
    errs.append("R31 回退：设置页没有记录来源的调音台入口")
if "private val knobs" in mixer_src or "var knobs" in mixer_src:
    errs.append("R31 回退：Mixer.kt 出现第二份 TtsKnobs 真值源")
if "MIXER_TRIAL" not in main_src:
    errs.append("R41a 回退：两态试听没把完整参数写进日志")
_s54 = main_src.split("private fun SettingsScreen(")[-1].split("private fun ModelScreen(")[0]
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

for p in kt_files:
    raw_src = p.read_text(encoding="utf-8", errors="replace")
    for no, line in enumerate(raw_src.splitlines(), 1):
        s = line.strip()
        if s.startswith('*') and '*/' in s:
            body = s[:-2].rstrip() if s.endswith('*/') else s
            if '*/' in body:
                errs.append("KDoc 正文出现注释收尾符（原始文本）: %s:%d" % (p.relative_to(ROOT), no))

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
for sym in ["AppTopBar", "AppDrawerSheet"]:
    if not re.search(r"^\s*" + sym + r"\(", main_src, re.M):
        errs.append("U1 回退：MainActivity 没有真实调用 " + sym)
if re.search(r"^\s*RefreshStrip\(", main_src, re.M):
    errs.append("U1 回退：书架刷新条又回到 MainActivity")
for sym in ["⟳ 刷新", "TabButton(", '把小说变成能听的音频", fontSize = 11',
            "下拉刷新 · 或点右上角", '听本", fontSize = 22']:
    if sym in main_src: errs.append("U1 回退：旧 UI 残留 " + sym)
for msg in nav.semantics(main_src):
    errs.append("U1 回退：导航语义丢失 — " + msg)
WANT_SCREENS = {"LIBRARY", "PRODUCE", "PROGRESS", "TASKS", "AUDIO",
                "SETTINGS", "MODELS", "MIXER", "CHAPTERS", "TASK_DETAIL"}
enum_m = re.search(r"private enum class Screen \{([^}]*)\}", main_src)
if enum_m:
    got = set(re.findall(r"\b[A-Z_]+\b", enum_m.group(1)))
    if got != WANT_SCREENS: errs.append("U1 回退：Screen 枚举被改 " + str(got ^ WANT_SCREENS))
else:
    errs.append("U1 回退：找不到 Screen 枚举")
masked_main = mask_strings(main_src)
head = "Box(Modifier.weight(1f)) {\n            when (screen) {"
start = masked_main.find(head)
if start >= 0:
    open_i = start + len(head) - 1
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
co_m = re.search(r"private fun chromeOf\(s: Screen\): ChromeSpec = when \(s\) \{(.*?)\n\}",
                 main_src, re.S)
if co_m:
    covered = set(re.findall(r"Screen\.([A-Z_]+)\s*->", co_m.group(1)))
    if covered != WANT_SCREENS: errs.append("U1 回退：chromeOf 没覆盖全部目的地 " + str(covered ^ WANT_SCREENS))
else:
    errs.append("U1 回退：找不到 chromeOf 映射")
tabs_m = re.search(r"DRAWER_TOP\s*=\s*listOf\((.*?)\n\)", chrome_src, re.S)
more_m = re.search(r"DRAWER_MORE\s*=\s*listOf\((.*?)\n\)", chrome_src, re.S)
if tabs_m:
    body = tabs_m.group(1)
    if body.count(" to ") != 5 or "设置" in body or "调音台" not in body:
        errs.append("U1 回退：抽屉一级不是 5 项 / 调音台不在一级 / 混入了设置")
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
if "if (acc > 55f)" not in chrome_src or "onRefresh()" not in chrome_src:
    errs.append("U1 回退：下拉刷新机制或阈值被改动")
if "BookStore.delete(c, id); confirmDelete = null; onChanged()" not in main_src:
    errs.append("U1 回退：删书后没调 onChanged()，列表不会刷新")
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

def ui_literals(src):
    """只取字符串字面量；注释里提到「下拉刷新」是说明，不算 UI 文案。"""
    return re.findall(r'"((?:\\.|[^"\\])*)"', src)

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

R36_ALL = ["ModeTabs(", "MixerPresetStore", "MixerPreset(",
           "legacyVoiceStamp", "VOICE_STAMP_MIGRATE", "MIXER_PRESET", "MIXER_COMMIT",
           "本机数据",
           "单次音频长度上限", "TIMING_NEXT",
           "PRESET_SCHEMA", "safeName", "fun coverage()", "logPreset("]
for sym in R36_ALL:
    if sym not in all_src: errs.append("缺少 R36 控制台符号: " + sym)
for sym in ["private fun ModeTabs(", "Screen.MIXER -> MixerScreen",
            "t.config else Prefs.defaultPerf(c)"]:
    if sym not in main_src: errs.append("缺少 R36 界面符号: " + sym)
if not nav.mixer_entries(main_src):
    errs.append("缺少 R36 调音台入口语义")
for banned in ["声音采样（进阶）", "putKnobs(", "descDraft"]:
    if banned in main_src: errs.append("R36 回退：设置页又出现第二套采样 " + banned)
if "var knobs" in mixer_src:
    errs.append("R36 回退：Mixer.kt 出现第二份 TtsKnobs 真值源")
if main_src.count("Screen.MIXER -> MixerScreen") != 1:
    errs.append("R36 回退：调音台导航分支异常")
bld = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8", errors="replace")
for banned in ["compose.animation", "material-icons-extended", "accompanist"]:
    if banned in bld: errs.append("R36 回退：为折叠动画引了新依赖 " + banned)

for sym in ["DismissibleNavigationDrawer(", "drawerState = drawerState",
            "gesturesEnabled = true", "drawerContent = {",
            "AppDrawerSheet(", "rememberDrawerState(DrawerValue.Closed)",
            "BackHandler(enabled = drawerState.isOpen)",
            "scope.launch { drawerState.close() }",
            ".navigationBarsPadding()", "drawerReturn"]:
    if sym not in main_src: errs.append("R46/R51 缺 V1 抽屉符号: " + sym)
if "ModalNavigationDrawer(" in main_src:
    errs.append("R51 回退：又用回覆盖式 ModalNavigationDrawer(")
if not nav.back_order_ok(main_src):
    errs.append("R46 回退：抽屉 BackHandler 没排在全局回退之后")
for sym in ["AppBottomBar(", "DropdownMenu(", "MENU_ITEMS", "TOP_TABS",
            "onMenu = { idx ->", "AppTab("]:
    if sym in main_src or sym in chrome_src:
        errs.append("R46 回退：旧壳残留 " + sym)
if not nav.mixer_back_ok(main_src):
    errs.append("R46 回退：调音台返回没按来源")
if not nav.models_back_ok(main_src):
    errs.append("R46 回退：模型页返回没按来源")

R38_ALL = ["ParamCatalog", "ParamSpec", "TTS_PARAMS", "音色身份", "保存",
           "全部恢复默认", 'Text("采样参数 · 9 项"', "声音描述"]
for sym in R38_ALL:
    if sym not in all_src: errs.append("缺少 R38 控制台符号: " + sym)
cpp_src = (ROOT / "app/src/main/cpp/tingben_native.cpp").read_text(encoding="utf-8", errors="replace")
if "TTS_PARAMS_APPLIED" not in cpp_src:
    errs.append("R38 回退：JNI 缺引擎实收参数日志")
if 'null, "auto"' in main_src:
    errs.append("R38 回退：试音又用 auto，与主链 chinese 不一致")
if 'MixerFold("音色身份"' in main_src:
    errs.append("R38 回退：音色身份又藏进折叠组")
if "ParamCatalog.SAMPLING.forEach" not in main_src:
    errs.append("R98 回退：采样参数没走 ParamCatalog 单一目录")
if "private fun ParamLine(" not in main_src:
    errs.append("R98 回退：数值控件没走 ParamLine（数字会被裁）")

draft_path = ROOT / "app/src/main/java/com/tingben/app/core/MixerDraft.kt"
draft_src = draft_path.read_text(encoding="utf-8", errors="replace") if draft_path.exists() else ""
R40_ALL = ["MixerDraft", "MixerCommit", "MIXER_COMMIT", "fun diff(", "fun commit(",
           "TIMING_NOW", "TIMING_NEXT", "active(c: Context)", "保存",
           "丢弃并离开", "BackHandler(enabled = dirty)", "改动还没保存",
           "fun commitAll()"]
for sym in R40_ALL:
    if sym not in all_src: errs.append("缺少 R40 统一提交符号: " + sym)
blk_i = main_src.find("private fun MixerScreen(")
mixer_block = main_src[blk_i:] if blk_i >= 0 else ""
for banned in ["Prefs.setTtsKnobs(", "Prefs.setVoiceDescription(", "Prefs.setDefaultPerf(",
               "Prefs.setNonVerbal(", "Prefs.resetTtsKnobs("]:
    if banned in mixer_block:
        errs.append("R40 回退：界面里出现第二个生效写入口 " + banned)
if "MixerCommit.commit(c, draft, n)" not in mixer_block:
    errs.append("R40 回退：界面没走唯一出口 MixerCommit.commit")
if draft_src.count("Prefs.set") != 7:
    errs.append("R98 回退：commit 写入口不是 7 个（实际 %d）" % draft_src.count("Prefs.set"))
if draft_src.count("put(") != 13:
    errs.append("R98 回退：diff 源码调用数不是 12（实际 %d）" % (draft_src.count("put(") - 1))
lits = ui_literals(main_src) + ui_literals(draft_src)
for banned in ["设定", "套用", "保存并生效", "直接保存生效", "应用预设"]:
    if any(banned in x for x in lits):
        errs.append("R40 回退：又出现旧生效动词 " + banned)
prefs_src = (ROOT / "app/src/main/java/com/tingben/app/core/Prefs.kt").read_text(encoding="utf-8", errors="replace")
if '?: ""' not in prefs_src:
    errs.append("R45 回退：展开态默认值不是全部折叠")
R99_MUST = ['Text("采样参数 · 9 项"', 'Text("自定义"', 'Text("保存"',
            'Text("试听 · 已保存"', 'Text("试听 · 当前"', '改动还没保存', '离开会丢掉这次改动。',
            'val mode = remember(tick) { ModelStore.currentMode(c) }']
for sym in R99_MUST:
    if sym not in main_src: errs.append("R99 回退：新调音台缺 " + sym)
_mixer99 = main_src[main_src.index("private fun MixerScreen("):]
for banned in ['MixerFold(', 'samplingOpen', 'trialOpen', '提交变更',
               '待提交', '查看差异', '丢弃待提交', '有未提交变更', 'showDiff']:
    if banned in _mixer99: errs.append("R99 回退：旧折叠/待提交话术残留 " + banned)
R97_GONE = ["当前模式 · 只读", "送进模型 · 可随便改", "这段描述和下面 9 项采样同时生效",
            "每次合成会重新采样音色", "改动先进入待提交", "9 项真到模型", "✅ 可调",
            "改完直接提交，不必先试听", "试听未提交参数", "试听已生效参数",
            "丢弃全部待提交", "查看差异", "提交不依赖试听", "当前生效 / 待提交差异",
            "出声了 ✓", "已自动播放", "TimingHint("]
_mixer97 = main_src[main_src.find("private fun MixerScreen("):]
_ui97 = ui_literals(_mixer97)
for gone in R97_GONE:
    if any(gone in x for x in _ui97):
        errs.append("R97 回退：旧话术回潮 " + gone)
_mixer = main_src[main_src.index("private fun MixerScreen("):]
for banned in ["音色身份生成器", "固定音色 · 官方模式说明", "哪些能改 · 和模型一一对齐",
               "参数触达与官方资料", "openMap.contains", "showVoiceEdit", "AnchorBar(",
               "snapshotFlow { scroll.value }"]:
    if banned in _mixer: errs.append("R92 回退：旧调音台形态残留 " + banned)

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
