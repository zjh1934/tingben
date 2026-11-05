#!/usr/bin/env python3
"""R51 · 卡顿根因收窄 + 抽屉联动拆包硬验：CRC / 7 .so / 权重 0 / 无 INTERNET /
版本 / dex 新符号 / 旧形态零残留 / 既有能力守恒 / native 实收日志。

用法:   python3 scripts/r51_apk_verify.py <apk> <version 如 1.0.0+65>
自检:   python3 scripts/r51_apk_verify.py --selftest <apk> <version>

注意：debug 包的 dex 字符串池里仍可能保留 M3 库内部引用的 `ModalNavigationDrawer`
类名（不生成 R8，类名按引用保留），所以「覆盖式调用是否清零」由 r51_verify.py
在源码层用 `ModalNavigationDrawer(` 断言，不在拆包层误判。
"""
import sys, pathlib, zipfile

SELFTEST = "--selftest" in sys.argv
args = [a for a in sys.argv[1:] if a != "--selftest"]
p = pathlib.Path(args[0])
ver = args[1]
z = zipfile.ZipFile(p)
bad = z.testzip()
names = z.namelist()
sos = [n for n in names if n.endswith(".so") and "arm64-v8a" in n]
gguf = [n for n in names if n.lower().endswith(".gguf")]
dex = b"".join(z.read(n) for n in names if n.endswith(".dex"))
if SELFTEST:
    # 反向：抹掉联动抽屉标志性符号，验证脚本必须 FAIL
    dex = dex.replace(b"DismissibleNavigationDrawer", b"GONE_r51_drawer")
man = z.read("AndroidManifest.xml")
nat = b"".join(z.read(n) for n in names if n.endswith("libtingben_native.so"))

# R51 · 新形态：联动抽屉 + 状态下沉到叶子 + 既有能力一个不改坏
NEW = [
    b"DismissibleNavigationDrawer", b"KeepScreenOn", b"TaskBar",
    b"AppDrawerSheet", b"RefreshStrip", b"MixerCommit", b"MIXER_COMMIT",
    b"MIXER_TRIAL", b"TTS_PARAMS", b"ANN_CONTENT", b"LLM_VERDICT",
    b"FGS_BEGIN", b"ProduceService",
    "提交变更".encode(), "查看差异".encode(),
    "试听未提交参数".encode(), "试听已生效参数".encode(),
    "改完直接提交，不必先试听。".encode(),
    "固定音色".encode(), "主·随机度".encode(), "副·候选范围".encode(),
    "展开 ↓".encode(), "正在制作 ·".encode(),
]
# 旧形态零残留：R33 起的界面返工 / R41a 删掉的 A/B / R51 删掉的旧文案
OLD = [
    b"AppBottomBar", b"AppTab", b"MENU_ITEMS", b"TOP_TABS",
    "把小说变成能听的音频".encode(), "书架只放原书".encode(),
    "松开即可刷新".encode(), "已刷新".encode(), "⟳ 刷新".encode(),
    b"MIXER_AB", "A/B 试听".encode(), "保存并生效".encode(),
    "先试听再提交变更才能生效。".encode(),
    "精确值".encode(), "自己填（可选）".encode(),
]

new_bad = [s for s in NEW if s not in dex]
old_bad = [s for s in OLD if s in dex]
print("CRC:", "全过" if bad is None else "损坏 " + str(bad))
print("arm64 .so:", len(sos), "(必须 7)")
print("权重进包:", len(gguf), "(必须 0)")
print("版本 %s:" % ver, ver.encode() in man or ver.encode("utf-16le") in man)
print("无 INTERNET:", b"android.permission.INTERNET" not in man)
print("dex 新符号:", len(NEW) - len(new_bad), "/", len(NEW),
      "缺失:", [s.decode("utf-8", "replace") for s in new_bad])
print("dex 旧形态零残留:", len(OLD) - len(old_bad), "/", len(OLD),
      "残留:", [s.decode("utf-8", "replace") for s in old_bad])
print("native 实收日志:", b"TTS_PARAMS_APPLIED" in nat)

failed = []
if bad is not None: failed.append("CRC")
if len(sos) != 7: failed.append("so 数量")
if gguf: failed.append("权重进包")
if not (ver.encode() in man or ver.encode("utf-16le") in man): failed.append("版本号")
if b"android.permission.INTERNET" in man: failed.append("INTERNET 权限")
if new_bad: failed.append("新符号缺失")
if old_bad: failed.append("旧形态残留")
if b"TTS_PARAMS_APPLIED" not in nat: failed.append("native 实收日志丢失")
ok = not failed
if SELFTEST:
    print("自检（已抹掉联动抽屉符号）:", "正确拦下" if not ok else "FAIL：反向注入没被拦下")
    sys.exit(0 if not ok else 1)
print("结果:", "PASS" if ok else "FAIL " + ",".join(failed))
sys.exit(0 if ok else 1)
