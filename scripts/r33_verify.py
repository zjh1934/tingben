#!/usr/bin/env python3
"""R33 反向验证：把「改坏」的几种典型形态注入回去，自检必须拦下。

用户原话「改一处联动很多处，你不做验证」—— 这一轮每处断言都要证明自己真会响，
而不是恒绿的摆设。跑完自动还原，不留痕。
"""
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"
CHROME = ROOT / "app/src/main/java/com/tingben/app/AppChrome.kt"
CHECK = ROOT / "scripts/check_structure.py"

orig_main = MAIN.read_text(encoding="utf-8")
orig_chrome = CHROME.read_text(encoding="utf-8")

CASES = [
    ("顶栏又贴回状态栏", MAIN,
     "Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {",
     "Column(Modifier.fillMaxSize().background(Bg)) {"),
    ("☰ 又变回直接跳设置", MAIN,
     "onMenu = { idx ->", "onSettings = { screen = Screen.SETTINGS; refresh++ }"),
    ("删书又不刷新", MAIN,
     "BookStore.delete(c, id); confirmDelete = null; onChanged()",
     "BookStore.delete(c, id); confirmDelete = null"),
    ("下拉刷新文字又回来了", CHROME,
     "if (acc > 55f) onRefresh()", "if (acc > 55f) { Toast.makeText(c, \"下拉刷新\", Toast.LENGTH_SHORT).show(); onRefresh() }"),
    ("大标题又回来了", MAIN,
     "Text(\"还差模型文件\", fontWeight = FontWeight.Bold, fontSize = 14.sp)",
     "Text(\"把小说变成能听的音频\", fontSize = 21.sp, fontWeight = FontWeight.Bold)"),
    ("常驻任务条又退化成点整卡跳页", MAIN,
     ".clickable { taskExpanded = !taskExpanded }",
     ".clickable { screen = Screen.TASKS }"),
]

failed = []
for label, path, old, new in CASES:
    data = path.read_text(encoding="utf-8")
    if old not in data:
        failed.append(f"[注入点找不到] {label}")
        continue
    path.write_text(data.replace(old, new, 1), encoding="utf-8")
    r = subprocess.run([sys.executable, str(CHECK)], cwd=ROOT,
                       capture_output=True, text=True)
    caught = r.returncode != 0
    print(f"{'拦下' if caught else '漏过'}  {label}"
          + ("" if caught else f"  ← 输出: {r.stdout.strip()[:120]}"))
    if not caught:
        failed.append(f"[断言不响] {label}")
    # 还原
    path.write_text(orig_main if path == MAIN else orig_chrome, encoding="utf-8")

# 基线必须恢复为全绿
r = subprocess.run([sys.executable, str(CHECK)], cwd=ROOT, capture_output=True, text=True)
print(f"基线: {'通过' if r.returncode == 0 else '失败'}  {r.stdout.strip()[:110]}")
if r.returncode != 0:
    failed.append("[基线] 还原后自检不通过")

# 文件必须逐字节还原
if MAIN.read_text(encoding="utf-8") != orig_main or CHROME.read_text(encoding="utf-8") != orig_chrome:
    failed.append("[还原] 文件没有逐字节还原")

if failed:
    print("\n".join("FAIL " + x for x in failed))
    sys.exit(1)
print("R33 反向验证：%d/%d 全拦下，基线全绿，文件零残留" % (len(CASES), len(CASES)))
