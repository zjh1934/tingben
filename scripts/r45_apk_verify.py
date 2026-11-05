#!/usr/bin/env python3
"""R45 · 拆包硬验：CRC / 7 .so / 权重 0 / 无 INTERNET / 版本 / dex 新符号 / 旧符号零残留。

用法: python3 scripts/r45_apk_verify.py <apk> <version 如 1.0.0+55>
"""
import zipfile, sys, pathlib

p = pathlib.Path(sys.argv[1]); ver = sys.argv[2]
z = zipfile.ZipFile(p)
bad = z.testzip()
print("CRC:", "全过" if bad is None else "损坏 " + str(bad))
names = z.namelist()
sos = [n for n in names if n.endswith(".so") and "arm64-v8a" in n]
gguf = [n for n in names if n.lower().endswith(".gguf")]
dex = b"".join(z.read(n) for n in names if n.endswith(".dex"))
man = z.read("AndroidManifest.xml")
nat = b"".join(z.read(n) for n in names if n.endswith("libtingben_native.so"))
print("arm64 .so:", len(sos), "(必须 7)")
print("权重进包:", len(gguf), "(必须 0)")
print("版本 %s:" % ver, ver.encode() in man or ver.encode("utf-16le") in man)
print("无 INTERNET:", b"android.permission.INTERNET" not in man)

NEW = [
    "固定音色 · 官方模式说明", "哪些能改 · 和模型一一对齐",
    "固定音色", "官方三模式说明 · 当前权重能固定哪三样",
    "声音设计（当前）", "预置音色", "声音克隆", "界面不画开关",
    "不等于官方承诺的跨段完全一致", "当前能固定的三样",
    "✅ 真到声音模型（能调）", "⚠️ 上层（能改，但模型只看到文字或 App 流程）",
    # 🔒 是增补平面字符，dex 的 MUTF-8 用 CESU-8 代理对编码，按 UTF-8 直搜会假阴性；
    # 改查去掉 emoji 的中文子串（R38/R42 同类订正）。
    "结构上没有（不画开关）", "✅ 可调", "只读",
    "采样参数 · 9 项真到模型", "⟳ 换一个", "⟳ 默认", "MIXER_SEED",
    "自己填（可选）", "固定音色是什么？ ›",
    "固定音色 = 这段描述 + 种子 + 9 项采样全书不变。",
    "提交不依赖试听：改完直接点「提交变更」即可生效。",
    # 既有能力没被改坏
    "MixerCommit", "MIXER_COMMIT", "提交变更", "查看差异", "未提交变更",
    "试听未提交参数", "试听已生效参数", "MIXER_TRIAL", "MIXER_PROFILE",
    "MIXER_CAST", "ANN_CONTENT", "LLM_VERDICT", "TTS_PARAMS", "chinese", "FGS_BEGIN",
    "高级 · 开发者资料", "音色身份生成器", "对比并载入", "最近使用",
]
OLD = [
    "采样参数 · 9 项到引擎", "① 采样参数", "② 音色身份生成器",
    "保存并生效", "直接保存生效", "套用", "应用预设", "设定",
    "把小说变成能听的音频", "书架只放原书", "松开即可刷新", "已刷新",
    "⟳ 刷新", "补充描述", "A/B 试听", "MIXER_AB",
    "不会自己生效", "12 维是帮你写 instruct 的拼装模板",
]
print("--- dex 新符号 ---")
new_bad = [s for s in NEW if s.encode() not in dex]
for s in NEW:
    print(" ", s, s.encode() in dex)
print("--- dex 旧符号零残留 ---")
old_bad = [s for s in OLD if s.encode() in dex]
for s in OLD:
    print(" ", s, "残留!" if s.encode() in dex else "零残留")
print("--- native 守恒 ---")
print(" TTS_PARAMS_APPLIED:", b"TTS_PARAMS_APPLIED" in nat)

failed = []
if bad is not None: failed.append("CRC")
if len(sos) != 7: failed.append("so 数量")
if gguf: failed.append("权重进包")
if not (ver.encode() in man or ver.encode("utf-16le") in man): failed.append("版本号")
if b"android.permission.INTERNET" in man: failed.append("INTERNET 权限")
if new_bad: failed.append("新符号缺失 " + str(new_bad))
if old_bad: failed.append("旧符号残留 " + str(old_bad))
if b"TTS_PARAMS_APPLIED" not in nat: failed.append("native 实收日志丢失")
print("结果:", "PASS" if not failed else "FAIL " + "; ".join(failed))
sys.exit(0 if not failed else 1)
