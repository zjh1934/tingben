#!/usr/bin/env python3
"""R40 · 拆包硬验：产物完整性 + 新符号在 dex + 旧生效动词零残留 + 守恒。

用法: python3 scripts/r40_apk_verify.py <apk> <version 如 1.0.0+49>
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
    # 统一提交（R40 核心）
    "MixerDraft", "MixerCommit", "MIXER_COMMIT", "未提交变更", "查看差异",
    "提交变更", "丢弃全部待提交", "载入为待提交", "当前生效全貌", "导出与诊断",
    "有未提交变更", "填入草稿", "未绑定音色", "TimingHint", "TIMING_NOW", "TIMING_NEXT",
    # 既有能力没被改坏
    "MIXER_PROFILE", "MIXER_TRIAL", "MIXER_CAST", "预设库",
    "试听未提交参数", "试听已生效参数", "试听文本",
    "参数触达与官方资料", "qt_tts_params", "TTS_PARAMS", "PROMPT_VERSION",
    "ANN_CONTENT", "LLM_VERDICT", "chinese", "FGS_BEGIN",
    # R41 P1 · 锚点导航 / 三组紧凑表 / 精确值范围 / 预设 diff / 合并音色选择器
    "对比并载入", "主采样（5 项）", "副采样（3 项）", "确定性（1 项）",
    "恢复本组官方默认（待提交）", "允许范围", "最近使用", "重命名",
    "音色身份生成器",
    # R42 P2 · 文案 / 响应式 / 视觉
    "高级 · 开发者资料", "怎么用这份控制台", "可选值", "什么时候生效",
    "12 维是什么", "采样顺序", "参数说明", "现成音色",
]
OLD = [
    # 5 个旧生效动词 + R33 清掉的界面文案
    "保存并生效", "直接保存生效", "套用", "应用预设", "点一条直接保存生效",
    "设定", "把小说变成能听的音频", "书架只放原书", "松开即可刷新", "已刷新",
    "⟳ 刷新", "补充描述", "声音采样（进阶）",
    # R41a · 旧 A/B 对比界面与事件名不许再进包
    "A/B 试听", "合成 A", "▶ 听 A", "MIXER_AB", "对比用文本", "B 用哪组预设",
    # R41 P1 · 圈码编号、第二套音色控件、元信息外泄、9 卡平铺都不许回到包里
    "① 采样参数", "② 音色身份生成器", "⑦ 当前生效配置", "现成音色网格",
    "可编辑 ·", "只读 ·", "恢复官方默认 ${spec.def}",
    # R42 P2 · 第二套时机说法与开发者信息不许摊在普通视图
    "不会自己生效", "096b_personas.py", "12 维是帮你写 instruct 的拼装模板",
]
print("--- dex 新符号 ---")
new_bad = [s for s in NEW if s.encode() not in dex]
for s in NEW:
    print(" ", s, s.encode() in dex)
print("--- dex 旧动词零残留 ---")
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
if old_bad: failed.append("旧动词残留 " + str(old_bad))
if b"TTS_PARAMS_APPLIED" not in nat: failed.append("native 实收日志丢失")
print("结果:", "PASS" if not failed else "FAIL " + "; ".join(failed))
sys.exit(0 if not failed else 1)
