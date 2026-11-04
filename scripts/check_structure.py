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
            "实验性：真机上曾 10 分钟只产出 0 条标注"]
for sym in R25_MAIN:
    if sym not in main_src: errs.append("缺少 R25 界面符号: " + sym)
# 反向断言：按字符腰斩的旧实现、把标注档当默认的旧写法不许回来
# 只认代码形态；注释里写这个字面量是说明旧 bug，不该把自己拦下。
if re.search(r"if\s*\(\s*buf\.length\s*>=\s*110", pipe_src):
    errs.append("R25 回退：又出现按 110 字腰斩的旧切分")
if "mutableStateOf(if (t != null && t.bookId == id && t.direct)" in main_src:
    errs.append("R25 回退：新书默认又变回标注档")

notice = (ROOT / "docs/THIRD_PARTY_NOTICES.md").read_text(encoding="utf-8")
for who in ["Finrandojin", "Xydane", "MIT License", "ensemblereads", "ReaderVoice-Mobile"]:
    if who not in notice: errs.append("第三方声明缺少: " + who)

if errs:
    print("\n".join("ERROR " + e for e in errs)); sys.exit(1)
print("自检通过: %d 项必备文件 / 权重泄漏 0 / 无 INTERNET / 包名正确 / KDoc 坑 0 / 审计符号 %d 项 / R22 符号 %d 项 / R23 符号 %d 项 / R24 符号 %d 项 / R25 符号 %d 项 / native 早停 3 项"
      % (len(REQUIRED), len(AUDIT_SYMBOLS), len(R22_SYMBOLS), len(R23_SYMBOLS), len(R24_SYMBOLS),
         len(R25_ALL) + len(R25_MAIN)))
