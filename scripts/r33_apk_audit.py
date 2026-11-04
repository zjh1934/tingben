#!/usr/bin/env python3
# R33 拆包硬验（复用 scripts/r28_apk_verify.py 形状，不另起一套）
# 用法: python3 scripts/r33_apk_audit.py <apk>
import zipfile, sys, pathlib

p = pathlib.Path(sys.argv[1])
z = zipfile.ZipFile(p)
bad = z.testzip()
print("CRC:", "全过" if bad is None else "损坏 " + str(bad))
names = z.namelist()
sos = [n for n in names if n.endswith(".so") and "arm64-v8a" in n]
print("arm64 .so:", len(sos), "(必须 7)")
gguf = [n for n in names if n.lower().endswith(".gguf")]
print("权重进包:", len(gguf), "(必须 0)")
dex = b"".join(z.read(n) for n in names if n.endswith(".dex"))
man = z.read("AndroidManifest.xml")

# binary manifest 字符串池里版本号通常以 UTF-16LE 或 MUTF-8 各存一份
ver_utf8 = "1.0.0+43".encode()
ver_utf16 = "1.0.0+43".encode("utf-16le")
print("版本 1.0.0+43:", ver_utf8 in man or ver_utf16 in man)
print("无 INTERNET:", b"android.permission.INTERNET" not in man)
for k in [b"android.permission.FOREGROUND_SERVICE",
          b"android.permission.POST_NOTIFICATIONS",
          b"android.permission.WAKE_LOCK"]:
    # binary AXML 字符串池多为 UTF-16LE，ASCII 直搜会假阴性（R33 第一次校验踩过）
    ok = k in man or k.decode().encode("utf-16le") in man
    print("权限", k.decode().split(".")[-1], ok)

NEW = [
    "statusBarsPadding", "DropdownMenu", "AlertDialog", "IconButton",
    "onMenu", "detectVerticalDragGestures", "taskExpanded", "confirmDelete",
    "粘贴", "清除", "建书", "调音台", "MIXER_PROFILE", "onChanged",
]
print("--- dex 新符号 ---")
for s in NEW:
    print(" ", s, s.encode() in dex)

OLD = [
    "把小说变成能听的音频", "书架只放原书", "下拉刷新", "松开即可刷新",
    "已刷新", "没有文件？粘贴一段文字", "有一本书正在工作，去查看",
    "⟳ 刷新", "点右上角刷新",
]
print("--- dex 旧文案零残留（精确串）---")
for s in OLD:
    print(" ", s, "残留!" if s.encode() in dex else "零残留")
# 例外（如实登记，不假装零残留）：ProduceService.kt 通知渠道 description
# 「离线把小说变成音频的逐块进度与停止入口」是系统通知渠道说明，不是 App 界面文案，
# 用词含「把小说变成」但不含被删的大标题。U2 一并清理，不为此重发 +43。
CHANNEL_DESC = "离线把小说变成音频的逐块进度与停止入口"
print("通知渠道描述仍在:", CHANNEL_DESC.encode() in dex, "（系统级说明，非界面文案，U2 清理）")

failed = []
if bad is not None: failed.append("CRC")
if len(sos) != 7: failed.append("so 数量")
if gguf: failed.append("权重进包")
if not (ver_utf8 in man or ver_utf16 in man): failed.append("版本号")
if b"android.permission.INTERNET" in man: failed.append("INTERNET")
for s in NEW:
    if s.encode() not in dex: failed.append("缺符号:" + s)
for s in OLD:
    if s.encode() in dex: failed.append("旧文案:" + s)
print("\n结论:", "全过" if not failed else "FAIL " + ", ".join(failed))
sys.exit(1 if failed else 0)
