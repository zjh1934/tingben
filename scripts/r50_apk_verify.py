#!/usr/bin/env python3
"""R50 · 调音台真机三处修补拆包硬验：CRC / 7 .so / 权重 0 / 无 INTERNET /
版本 / dex 新符号 / 旧形态零残留 / 既有能力守恒 / native 实收日志。

用法:   python3 scripts/r50_apk_verify.py <apk> <version 如 1.0.0+60>
自检:   python3 scripts/r50_apk_verify.py --selftest <apk> <version>
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
    # 反向：把 R50 标志性文案抹掉，验证脚本必须 FAIL
    dex = dex.replace("自己填".encode(), b"GONE_r50_custom")
man = z.read("AndroidManifest.xml")
nat = b"".join(z.read(n) for n in names if n.endswith("libtingben_native.so"))

# R50 · 本轮新形态（真机三处修补）
NEW = [
    # ① 紧凑参数行：带 label 的大框已被 72×38 无标签小框替换
    "框里可填任意合法值".encode(),
    # ② 表演层 5 项自定义小框 + 数据真落地
    "自己填".encode(),
    "叙事 / 对白 / 广播剧，也可自己填一句".encode(),
    "慢 / 正常 / 快，只写稳定倾向；可自己填".encode(),
    "克制 / 标准 / 饱满；可自己填".encode(),
    "合并时插入空白，不改正文；可填 0~5000 之间任意毫秒".encode(),
    "标注不通过时重试次数；可填 0~10 之间任意次数".encode(),
    "附加语气".encode(),
    # ③ 滚动 → 锚点联动（沿用 v5 锚点形态，符号守恒）
    "固定音色".encode(), "高级".encode(), "采样种子".encode(),
    # 既有能力一个都不能被改坏（R40 唯一提交 / R41a 两态试听 / R49 v5）
    b"MixerCommit", b"MIXER_COMMIT", "提交变更".encode(), "查看差异".encode(),
    "试听未提交参数".encode(), "试听已生效参数".encode(), b"MIXER_TRIAL",
    "改完直接提交，不必先试听。".encode(),
    "辅助设置 · 上层（能改，但不是模型字段）".encode(),
    "展开全文 ›".encode(), "编辑描述 ›".encode(),
    "哪些能改 · 和模型一一对齐".encode(), "采样参数 · 9 项真到模型".encode(),
    b"TTS_PARAMS", b"ANN_CONTENT", b"LLM_VERDICT", b"FGS_BEGIN",
    b"RefreshStrip", b"ModalNavigationDrawer", b"AppDrawerSheet",
    "主·随机度".encode(), "副·候选范围".encode(),
]
# 旧形态零残留（R50 修掉的东西不许回来）
OLD = [
    b"AppBottomBar", b"AppTab", b"MENU_ITEMS", b"TOP_TABS",
    "把小说变成能听的音频".encode(), "书架只放原书".encode(),
    "松开即可刷新".encode(), "已刷新".encode(), "⟳ 刷新".encode(),
    b"MIXER_AB", "A/B 试听".encode(), "保存并生效".encode(),
    "先试听再提交变更才能生效。".encode(),
    "精确值".encode(),          # R50：带 label 的大数字框已删
    "自己填（可选）".encode(),  # R50：自定义框改 140×40 小框
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
    print("自检（已抹掉 R50 自定义框文案）:", "正确拦下" if not ok else "FAIL：反向注入没被拦下")
    sys.exit(0 if not ok else 1)
print("结果:", "PASS" if ok else "FAIL " + ",".join(failed))
sys.exit(0 if ok else 1)
