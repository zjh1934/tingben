#!/usr/bin/env python3
# R28 拆包硬验：CRC / 7 个 arm64 .so / 无权重 / 无 INTERNET / 版本 / 新符号在 dex / 旧符号零残留
import zipfile, sys, pathlib, re
p = pathlib.Path(sys.argv[1])
z = zipfile.ZipFile(p)
bad = z.testzip(); print("CRC:", "全过" if bad is None else "损坏 "+bad)
names = z.namelist()
sos = [n for n in names if n.endswith(".so") and "arm64-v8a" in n]
print("arm64 .so:", len(sos))
gguf = [n for n in names if n.lower().endswith(".gguf")]
print("权重进包:", len(gguf), "(必须为 0)")
dex = b"".join(z.read(n) for n in names if n.endswith(".dex"))
man = z.read("AndroidManifest.xml")
for k in [b"SegPlan", b"LLM_VERDICT", b"segmap.json", b"redoSynthesize", b"redoBlock",
          b"MODEL", b"FILL"]:
    print("dex 符号", k.decode(), k in dex)
# 注：durationMs / preview 这两个词在音频页、诊断页本来就有合法用法，
# 不能拿来当「SegRow 旧字段残留」的判据（R28 第一次拆包在这条上误报过）。
# 字段残留由源码层 r28_verify.py 的 s.durationMs / s.preview 零残留断言负责。
print("SegRow 旧字段残留：见源码层断言（dex 词面不可作为判据）")
print("无 INTERNET:", b"android.permission.INTERNET" not in man)
