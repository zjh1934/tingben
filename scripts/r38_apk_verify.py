#!/usr/bin/env python3
# R38 · 拆包硬验（复用 r36_apk_audit.py 形状；binary AXML 是 UTF-16LE，必须两种编码都查）
# 用法: python3 scripts/r38_apk_verify.py <apk> <version 如 1.0.0+48>
import zipfile, sys, pathlib

p = pathlib.Path(sys.argv[1])
ver = sys.argv[2]
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

ver_utf8 = ver.encode()
ver_utf16 = ver.encode("utf-16le")
print("版本 %s:" % ver, ver_utf8 in man or ver_utf16 in man)
print("无 INTERNET:", b"android.permission.INTERNET" not in man)
for k in [b"android.permission.FOREGROUND_SERVICE",
          b"android.permission.POST_NOTIFICATIONS",
          b"android.permission.WAKE_LOCK"]:
    print("权限", k.decode().split(".")[-1], k in man or k.decode().encode("utf-16le") in man)

# JNI 实收证据在 native 库里，不在 dex（R38 新增 TTS_PARAMS_APPLIED）
nat = b"".join(z.read(n) for n in names if n.endswith("libtingben_native.so"))
print("native 实收证据 TTS_PARAMS_APPLIED:", b"TTS_PARAMS_APPLIED" in nat)

NEW = [
    # 首屏音色身份（输入框回归）
    "音色身份", "当前生效", "草稿", "保存", "清空回默认", "保存音色身份",
    "写入草稿", "未保存的草稿不进预设",
    # 9 项官方字段一一对应
    "qt_tts_params", "官方默认", "恢复官方默认", "精确值", "到引擎", "上层 ·", "结构 ·",
    "采样参数 · 9 项", "TTS_PARAMS", "ParamSpec",
    # 触达矩阵 + 官方资料索引
    "参数触达矩阵", "⑧ 参数触达矩阵与官方资料", "官方资料（按名称自行查", "O1~O7", "O1", "O7",
    # 既有能力没被改坏
    "MIXER_PROFILE", "预设库", "A/B 试听", "跨书默认", "目标语言", "chinese",
]
OLD = [
    # R33 清掉的界面文案
    "把小说变成能听的音频", "书架只放原书", "松开即可刷新", "已刷新",
    "没有文件？粘贴一段文字", "有一本书正在工作，去查看", "⟳ 刷新", "点右上角刷新",
    # R36 -> R38 修掉：输入框旧标签 / 第二套采样折叠
    "补充描述", "声音采样（进阶）",
]
print("--- dex 新符号 ---")
for s in NEW:
    print(" ", s, s.encode() in dex)
print("--- dex 旧文案零残留 ---")
for s in OLD:
    print(" ", s, "残留!" if s.encode() in dex else "零残留")

failed = []
if bad is not None: failed.append("CRC")
if len(sos) != 7: failed.append("so 数量")
if gguf: failed.append("权重进包")
if not (ver_utf8 in man or ver_utf16 in man): failed.append("版本号")
if b"android.permission.INTERNET" in man: failed.append("INTERNET")
if b"TTS_PARAMS_APPLIED" not in nat: failed.append("native 缺 TTS_PARAMS_APPLIED")
for s in NEW:
    if s.encode() not in dex: failed.append("缺符号:" + s)
for s in OLD:
    if s.encode() in dex: failed.append("旧文案:" + s)
print("\n结论:", "全过" if not failed else "FAIL " + ", ".join(failed))
sys.exit(1 if failed else 0)
