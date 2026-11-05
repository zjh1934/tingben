#!/usr/bin/env python3
"""R41a 收尾：APK 到手后回填精确字节数 / blob、生成落地报告、导图与状态。"""
import hashlib, pathlib, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
APK = pathlib.Path("/data/workspace/听本_app-debug.apk")
if not APK.is_file():
    sys.exit("先跑 r41a_fetch_apk.py")
raw = APK.read_bytes()
size = len(raw)
blob = hashlib.sha1(b"blob " + str(size).encode() + b"\0" + raw).hexdigest()
short = blob[:12]
ver = "1.0.0+51"

r = subprocess.run([sys.executable, "scripts/r40_apk_verify.py", str(APK), ver],
                   cwd=ROOT, capture_output=True, text=True)
verify = r.stdout.strip()
print(verify[-1200:])
if r.returncode != 0 or "结果: PASS" not in verify:
    sys.exit("拆包硬验未通过，停止回填文档")


def edit(rel, pairs):
    p = ROOT / rel
    t = p.read_text(encoding="utf-8")
    for a, b in pairs:
        assert a in t, rel + " 未找到: " + a[:70]
        t = t.replace(a, b, 1)
    p.write_text(t, encoding="utf-8")
    print("updated", rel)


edit("STATUS.md", [
    ("**2026-11-05 · R41a 已发（CI run 50）",
     "**2026-11-05 · R41a 已发（CI run 51，run 50 编译失败已复盘修复）"),
    ("| 版本 | `1.0.0+50`（精确字节数 / 直链 blob 见文末 R41a 落地记录） |",
     "| 版本 | `%s`（%s 字节；直链 blob `%s…` 与本地逐字节一致） |" %
     (ver, format(size, ","), short)),
    ("| CI | run 50 `completed success`（新增 R41a 两态试听验证 step） |",
     "| CI | **run 50 `completed failure`**（Kotlin 两处编译错，日志回写定位）→ **run 51 `completed success`**（含 R41a 验证 step） |"),
    ("1. **真机验**：`+50` 装到 24117RK2CC", "1. **真机验**：`+51` 装到 24117RK2CC"),
])

log = ROOT / "ITERATION_LOG.md"
t = log.read_text(encoding="utf-8")
t = t.replace("## R41a · 调音台试听改版（插队轮，`1.0.0+50`）",
              "## R41a · 调音台试听改版（插队轮，`1.0.0+51`）", 1)
t += """
### run 50 真编译失败复盘（静态自检全绿，CI 抓到两处）

- `Unresolved reference 'label合成'`：`"正在用$label合成…"` 里中文紧跟 `$变量` 被 Kotlin 连成一个标识符，
  必须写 `${label}`。→ 补「字符串模板后紧跟中文」硬断言 + 反向注入用例。
- `Unresolved reference 'play'`：`trial()` 调用后面才定义的局部函数 `play()`，
  Kotlin 局部函数不许前向引用。→ `play` 移到 `trial` 之前，补「play 必须在 trial 前」断言。
- 日志由 workflow 的「编译失败 → 日志回写」步骤写回仓库 `logs/build-50.md` 才定位到；
  **再次印证「自检全绿 ≠ 编译过」**。run 51 重编通过。

### 最终交付

- **run 51 `completed success`**（20 步，native 缓存命中）；APK `%s` 字节，直链 blob `%s…` 与本地逐字节一致。
- 拆包硬验：CRC / 7 个 arm64 .so / 权重 0 / 无 INTERNET / `%s` / 新符号全在 / 旧 A/B 字面量零残留 /
  `.so` 里 `TTS_PARAMS_APPLIED` 仍在。
""" % (format(size, ","), short, ver)
log.write_text(t, encoding="utf-8")
print("updated ITERATION_LOG.md")

# 导图 ⑬
p = ROOT / "docs/听本_项目导图.html"
h = p.read_text(encoding="utf-8")
card = """
<div class="card" style="margin-top:14px">
<div class="h">⑬ R41a · 调音台试听改版（%s / run 51）</div>
<b>用户：</b>A/B 试听看不懂 → 只要两个按钮：未提交参数 / 已生效参数，放在「提交变更」附近同一模块；返回也常驻。<br>
<b>实查：</b>旧 ⑨ 折叠区在页底、6 步操作、A=active / B=预设、instruct 两边共用
<code>Prefs.voiceDescription</code> → 改了音色也听不出差别（A vs A）。<br>
<b>落地：</b>⑨ 整块删除；吸顶 <code>CardBox</code> 内两按钮 + 试听文本 + 缺模型提示 +「‹ 返回」；
点一下 = 合成 + 自动播放；两态各用整套 <code>MixerDraft</code> 经 <code>VoiceProfile.build()</code> 拼 instruct；
播放复用 <code>PlayQueue</code>；日志 <code>MIXER_AB</code> → <code>MIXER_TRIAL</code>。<br>
<b>联动：</b>11 处（状态变量 / trial / play / instruct / 吸顶条 / 返回 / ⑨ 删除 / 日志 / CI 自检 /
r36+r40 验证脚本 / 项目文件），守恒 13 项与基线逐字节一致。<br>
<b>验证：</b>5 套静态脚本全绿（含 R41a 3 组两态对拍 + 7 例反向注入）·
<b>run 50 真编译失败两处已修</b> · <b>run 51 全绿</b> · 拆包 CRC / 7 .so / 权重 0 / 无 INTERNET /
<code>%s</code> / %s 字节 / 直链 blob <code>%s…</code> 逐字节一致。<br>
<b>真机仍零证据</b>；试听期间离开页面可能只写 wav 不自动播放（协程取消），已在联动表写明。
</div>
""" % (ver, ver, format(size, ","), short)
assert "<footer>" in h
h = h.replace("<footer>", card + "\n<footer>", 1)
h = h.replace("⑫ 由 R40 追加</footer>", "⑫ 由 R40 追加，⑬ 由 R41a 追加</footer>", 1)
p.write_text(h, encoding="utf-8")
print("updated 项目导图.html")

report = """# R41a · 调音台试听改版 · 落地报告

**版本 `%s` · %s 字节 · CI run 51 `completed success` · 直链 blob `%s…` 与本地逐字节一致**

## 做了什么

把「看不懂的 A/B 试听」换成两个朴素按钮，**都在吸顶状态条那一块**（与「提交变更」同模块）：

- **试听未提交参数**：用 `draft`（未提交的音色身份 + 风格层 + 9 项采样）
- **试听已生效参数**：用 `active`（`Prefs` 真值源）
- 点一下 = 合成 + **自动播放**，不再有「合成 A / ▶ 听 A」两步；旁边一行可改试听文本
- 「‹ 返回」也挪进这一块常驻；有未提交变更时点它照旧先弹确认，草稿不静默丢
- 页面底部 ⑨「A/B 试听」整块删除

## 为什么必须整套跟着走

旧 A/B 刻意「两边同 instruct、只换采样」，但换成「草稿 vs 已生效」后就是骗人：
音色身份、朗读档位也是能改的参数。现在两态都经 `VoiceProfile.build(identity, styleInstruct(), 本段情绪)`
拼 instruct，与主链 `Pipeline.kt:1280` 同一个函数；日志 `MIXER_TRIAL` 写来源 + 9 参数 + hash +
`voiceChars` + `styleChars` + instruct 全文，`NativeEngine.ttsSynthesize` 仍自动写 `TTS_PARAMS`。
播放复用 R18 的 `PlayQueue`，不手搓第二套 MediaPlayer。

## 联动（11 处，逐处签）

状态变量、`trial()`、`play()`、instruct 构造、吸顶条、滚动区返回、⑨ 删除、日志事件、
`check_structure.py`（CI 必跑）、`r36_verify.py` / `r40_apk_verify.py`、README/STATUS/迭代日志。
详见 `docs/R41a_试听改版_联动表.md`。

## run 50 失败复盘（不粉饰）

静态自检 5 套全绿，**真 Kotlin 编译失败两处**，靠 workflow「编译失败 → 日志回写」拿到 `logs/build-50.md` 才定位：

1. `Unresolved reference 'label合成'`：`"正在用$label合成…"`，中文紧跟 `$变量` 被连成标识符 → 改 `${label}`
2. `Unresolved reference 'play'`：局部函数 `trial` 调用后面才定义的 `play`，Kotlin 不许前向引用 → `play` 前置

修完补了两条硬断言（模板中文 / play 顺序）和一条反向注入用例，验证脚本自己还误报过一次
（注释里举例 `$label合成` 被拦下），改成先屏蔽纯注释行。**这轮再次印证：自检全绿 ≠ 编译过。**

## 验证结论

```
%s
```

本地 5 套脚本全绿：结构自检 28 项、R38 参数矩阵 6 例反向、R40 6 组对拍 + 6 例反向、
R36 7/7 + 6 组预设往返、R41a 3 组两态对拍 + 7 例反向注入；守恒 13 项与基线逐字节一致。
CI run 51 共 20 步全绿（native 缓存命中，未重编 .so）。

## 边界

- 真机仍是零证据：按钮顺不顺手、两套参数能不能听出差别，要在 24117RK2CC 上点一次
- 试听走 `rememberCoroutineScope()`：合成中离开页面可能只写出 wav 不自动播放，回来再点一次
- 试听不写 `Prefs`、不改 `voiceStamp`、不进 `AudioLibrary`，不是制作
- `!dirty` 时两态内容相同，日志写明「与已生效一致」，不假装有差异
- 设置页 1517 行历史注释仍写「A/B」，不进 dex、不影响界面，登记 U2 清理

## 队列不变

真机验 → R41(P1) → R42(P2) → V1 抽屉壳（连续顺延）→ V2/V2b/V3 → 标注工厂导入通道 → U2。
""" % (ver, format(size, ","), short, verify)
(ROOT / "docs/R41a_试听改版_落地.md").write_text(report, encoding="utf-8")
print("wrote docs/R41a_试听改版_落地.md")
