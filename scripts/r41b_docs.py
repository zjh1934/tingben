#!/usr/bin/env python3
"""R41b 收尾：项目文件回填到 1.0.0+52 / run 52，纯文档，不碰业务代码。"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
APK_BYTES = 17_968_245
BLOB = "e32c2e9088b978d51ffeaec0cfeaa598c6904366"


def edit(rel, pairs):
    p = ROOT / rel
    t = p.read_text(encoding="utf-8")
    for old, new in pairs:
        if old not in t:
            sys.exit("未命中 %s :: %s" % (rel, old[:70]))
        t = t.replace(old, new, 1)
    p.write_text(t, encoding="utf-8")
    print("已更新", rel)


# ── STATUS.md ──────────────────────────────────────────────────────────
edit("STATUS.md", [
    ("**2026-11-05 · R41a 已发（CI run 51，run 50 编译失败已复盘修复）· 调音台试听改版：两态一步试听 / 返回常驻 / 旧 A/B 清零**",
     "**2026-11-05 · R41b 已发（CI run 52）· 调音台 P1 信息架构：锚点导航 / 编号统一 / 三组紧凑表 / 精确值范围 / 预设 diff / 音色选择器合并**"),
    ("| 版本 | `1.0.0+51`（17,935,474 字节；直链 blob `68e6b159dd63…` 与本地逐字节一致） |",
     "| 版本 | `1.0.0+52`（17,968,245 字节；直链 blob `e32c2e90…` 与本地逐字节一致） |"),
    ("| APK | 17,935,474 字节 / arm64-v8a / 固定 debug 签名 / 发布详情见 `docs/R41a_试听改版_落地.md` |",
     "| APK | 17,968,245 字节 / arm64-v8a / 固定 debug 签名 / 发布详情见 `docs/R41_P1_信息架构_落地.md` |"),
    ("| CI | **run 50 `completed failure`**（Kotlin 两处编译错，日志回写定位）→ **run 51 `completed success`**（含 R41a 验证 step） |",
     "| CI | **run 52 `completed success`**（26 步，含新增 R41 P1 验证 step；native 缓存命中） |"),
    ("- 原生引擎进包（7 个 arm64 .so）、固定签名、无 INTERNET、<100MB 闸门、CI 缓存加速",
     """- **R41 · P1 信息架构（本轮）**：锚点 chip 条（身份/采样/表演/开关/角色/预设/资料）+ 展开态持久化到 `Prefs`
  （旧 `rememberSaveable` 只跨旋转）；圈码与元信息收进资料区；9 项采样改主 5 / 副 3 / 确定性 1 三组紧凑表 + 共用 `ParamRow`；
  精确值默认带出当前值、带 min/max/单位/越界红字/回车规范化（范围唯一来源 `ParamSpec`）；预设「对比并载入」逐项 diff + 空名禁存 +
  重命名 + 删除确认；② 网格与⑤「初始音色」合并成同一个带搜索 / 最近使用的 `VoicePicker`。
  **R40 唯一编辑态 / 唯一提交出口与 R41a 两态试听一行未动**。详见 `docs/R41_P1_信息架构_联动表.md` 与落地报告。
- 原生引擎进包（7 个 arm64 .so）、固定签名、无 INTERNET、<100MB 闸门、CI 缓存加速"""),
    ("## 未验 / 未做（R41a 维护订正）", "## 未验 / 未做（R41b 维护订正）"),
    ("- **R36/R38/R40 调音台真机仍零证据**：吸顶条顺不顺手、差异计数准不准、375px 是否还碎字、",
     """- **R41 P1 真机仍零证据**：锚点跳转准不准、重进页面展开态还在不在、375px 下三组表是否溢出、
  预设 diff 是否逐项可读、音色选择器搜索是否好用 —— 都要在 24117RK2CC 上点一次（375px 12 维换行属 P2，本轮只保证不新增横向滚动）。
- **R36/R38/R40 调音台真机仍零证据**：吸顶条顺不顺手、差异计数准不准、375px 是否还碎字、"""),
    ("""## 下轮队列（R41a 插队后）

1. **真机验**：`+51` 装到 24117RK2CC —— ① 首屏吸顶那一块能否看到两个试听按钮、点一下是否自动出声；
   ② 改了音色但未提交，两个按钮听感是否真不同（`MIXER_TRIAL` 里两套 hash / voiceChars 可对照）；
   ③ 全页是否再无 A/B 字样；④「‹ 返回」常驻、有未提交仍先问丢弃。
2. **R41 · P1**（评审 7~12）：锚点导航 + 记住展开态、编号统一、九项紧凑表、精确值 min/max/越界、
   预设「对比并应用」diff 弹层、音色选择器合并搜索 —— 单独一轮
3. **R42 · P2**（评审 13~17）：开发者信息收「高级」抽屉、375px 12 维 chip 换行、视觉与说明降级
4. **V1 抽屉壳**（R34/R35 定案）：左滑抽屉、底栏 3 Tab 收敛进抽屉、调音台独立项 —— 连续顺延，仍单独一轮""",
     """## 下轮队列（R41b 之后）

1. **真机验**：`+52` 装到 24117RK2CC —— ① 锚点 chip 能否跳到对应区；② 离开重进，展开态是否还在；
   ③ 精确值框是否带出当前值、越界是否红字；④ 预设「对比并载入」的 diff 是否逐项可读；
   ⑤ 两个试听按钮点一下是否仍自动出声（`MIXER_TRIAL` 两套 hash 可对照）；⑥ 全页是否再无 A/B 字样。
2. **R42 · P2**（评审 13~17）：开发者信息收「高级」抽屉、375px 12 维 chip 换行、toggle 补开/关文字、
   说明降级为 help 图标、时机提示抽统一组件 —— 单独一轮
3. **V1 抽屉壳**（R34/R35 定案）：左滑抽屉、底栏 3 Tab 收敛进抽屉、调音台独立项 —— 连续顺延，仍单独一轮"""),
])

# 文末追加本轮记录
p = ROOT / "STATUS.md"
t = p.read_text(encoding="utf-8")
t += """

## R41b · 调音台 P1 收尾（本轮）

**上一轮没做完的三件**：CI 未挂 `r41_verify.py`（联动表第 80 条）、项目文件头部停在 `+51`、APK 未取回核验。
本轮全补齐，且**没改一行业务代码**。

- CI：`.github/workflows/ci.yml` 新增「R41 P1 信息架构验证」step；run 52 `completed success`，26 步零失败。
- 验证脚本自身订正两处过期断言（不是放松）：`r36_verify.py` 的 4 条旧字面量换成 R41 等价形态
  （受控 `openMap` + `Prefs` 持久化 / 三组紧凑表 / `ParamRow.onReset` / 删除确认与重命名调用点），
  反向注入 9/9 全拦下；`r40_apk_verify.py` 的资料区标题订正为「参数触达与官方资料」，
  补 12 个 R41 新符号 + 7 条旧编号零残留，**反向注入 2/2 拦下**（删新符号 / 恢复圈码）。
- 新增 `scripts/r41b_fetch_apk.py`：按 run 号参数化取包，替代写死 51 的旧脚本；每轮交付前必跑，
  避免 R33 那种「本地发了版、直链还是旧的」。
- 拆包硬验 PASS：CRC 全过 / 7 个 arm64 .so / 权重 0 / 无 INTERNET / `1.0.0+52` /
  新符号全在 / 旧编号与旧生效动词零残留 / `.so` 里 `TTS_PARAMS_APPLIED` 仍在。
- 直链 blob `e32c2e9088b978d51ffeaec0cfeaa598c6904366`、%d 字节，与本地 APK 逐字节一致。

⚠️ 真机仍是零证据。队列：真机验 → R42(P2) → V1 抽屉壳 → V2/V2b/V3 → 导入通道 → U2。
""" % APK_BYTES
p.write_text(t, encoding="utf-8")
print("STATUS.md 已追加 R41b 段")

# ── README.md ──────────────────────────────────────────────────────────
edit("README.md", [
    ("当前版本见 `STATUS.md` 头部".replace("当前版本见 `STATUS.md` 头部", "如 `1.0.0+51`"),
     "如 `1.0.0+52`"),
    ("- **调音台（R31 建，R33 提一级，R36/R38 重设计，R40 统一提交，R41a 试听改版）**",
     "- **调音台（R31 建，R33 提一级，R36/R38 重设计，R40 统一提交，R41a 试听改版，R41 P1 信息架构）**"),
    ("触达状态 · 常用档位 · 精确值输入 · 单项恢复官方默认（R40 起默认收起，身份生成器默认展开）。",
     """触达状态 · 常用档位 · 精确值输入 · 单项恢复官方默认（R40 起默认收起，身份生成器默认展开）。
  **R41 P1**：顶部锚点 chip 条一键跳区、展开态重进页面仍在；9 项改主 5 / 副 3 / 确定性 1 三组紧凑表；
  精确值框默认带出当前值并标 min/max/单位、越界即时红字；预设「对比并载入」先逐项 diff 再确认，
  空名禁存、可重命名、删除二次确认；音色选择器合并为带搜索 / 最近使用的单一控件。"""),
    ("试听改版见 `docs/R41a_试听改版_联动表.md`。",
     """试听改版见 `docs/R41a_试听改版_联动表.md`；P1 联动表见 `docs/R41_P1_信息架构_联动表.md`、
  落地见 `docs/R41_P1_信息架构_落地.md`。"""),
])

# ── 迭代日志 ───────────────────────────────────────────────────────────
p = ROOT / "ITERATION_LOG.md"
t = p.read_text(encoding="utf-8")
t += """

## R41b · P1 收尾（run 52 / 1.0.0+52）

- **补齐上一轮三处未完**：CI 挂 `r41_verify.py`（联动表第 80 条）、项目文件头部刷到 `+52`、APK 取回核验。
  本轮零业务代码改动，`MainActivity.kt` 与 run 52 构建产物一致。
- **验证脚本自身订正**（不是放水）：`r36_verify.py` 4 条过期字面量换成 R41 等价断言 + 反向注入 9/9；
  `r40_apk_verify.py` 资料区标题订正、补 12 新符号 / 7 条旧编号零残留 + 反向注入 2/2。
  新增按 run 号参数化的 `r41b_fetch_apk.py`，每轮交付前核直链 blob，不再写死版本号。
- **CI run 52 `completed success`**（26 步，native 缓存命中）；拆包 CRC / 7 .so / 权重 0 / 无 INTERNET /
  `1.0.0+52` / 新符号全在 / 旧编号零残留 / `TTS_PARAMS_APPLIED` 在；直链 blob `e32c2e90…` 与本地
  17,968,245 字节逐字节一致。
- 队列：真机验 → R42(P2) → V1 抽屉壳 → V2/V2b/V3 → 导入通道 → U2。
"""
p.write_text(t, encoding="utf-8")
print("ITERATION_LOG.md 已追加 R41b")

# ── 项目导图 ───────────────────────────────────────────────────────────
p = ROOT / "docs/听本_项目导图.html"
t = p.read_text(encoding="utf-8")
card = """

<div class="card" style="margin-top:14px">
<div class="h">⑭ R41 P1 · 调音台信息架构（1.0.0+52 / run 52）</div>
<b>输入：</b>《调音台 · 界面/交互评审意见》P1 六条（7~12），R40 统一提交 / R41a 两态试听是本轮地基，一行未动。<br>
<b>六条：</b>锚点 chip 条 + 展开态持久化到 <code>Prefs</code>（旧 <code>rememberSaveable</code> 只跨旋转）；
圈码与元信息收进资料区；9 项改主 5 / 副 3 / 确定性 1 三组紧凑表 + 共用 <code>ParamRow</code>；
精确值默认带出当前值、min/max/单位/越界红字/回车规范化（<code>ParamSpec</code> 唯一范围来源，抄 PocketPal <code>validateNumericField</code>）；
预设「对比并载入」逐项 diff + 空名禁存 + 重命名 + 删除确认；两处音色控件合并成带搜索 / 最近使用的 <code>VoicePicker</code>。<br>
<b>不手搓：</b>PocketPal 元数据校验 / OfflineLLM <code>rememberLazyListState</code> 锚点 / IndexTTS <code>.part</code> 原子落盘 /
ProAndroidDev 折叠分区，均只读形状；GPL 与未核实许可证仓库不复制源码行。<br>
<b>验证：</b>本地 6 套脚本全绿（R41 正向 46 符号 / 36 例范围对拍 / 9 例反向注入 / 29 项守恒）·
CI <b>run 52 全绿</b>（26 步，新增 R41 验证 step）· 拆包 CRC / 7 .so / 权重 0 / 无 INTERNET /
<code>+52</code> / 12 个 R41 新符号 / 旧编号零残留 / <code>TTS_PARAMS_APPLIED</code> 仍在 ·
直链 blob <code>e32c2e90…</code> 与本地 17,968,245 字节逐字节一致。<br>
<b>收尾订正：</b><code>r36_verify.py</code> 4 条过期断言同步到 R41 形态（反向 9/9 真拦下），
<code>r40_apk_verify.py</code> 补新符号与旧编号断言（反向 2/2）；新增按 run 号参数化取包脚本，防直链滞留旧版。<br>
<b>真机仍零证据</b>；队列：真机验 → R42(P2) → V1 抽屉壳 → V2/V2b/V3 → 导入通道 → U2。
</div>
"""
old_footer = "<footer>听本 · 项目导图 ｜ 按「导图 → 扒源码 → 加工 → 串 → 落软件 → 推 GitHub → 出 APK」顺序生成 ｜ ⑥ 由 R19 追加、R33/R37 订正，⑧ 由 R26 追加，⑨ 由 R28 追加，⑩ 由 R37 追加，⑪ 由 R38 追加，⑫ 由 R40 追加，⑬ 由 R41a 追加</footer>"
new_footer = old_footer.replace("⑬ 由 R41a 追加", "⑬ 由 R41a 追加，⑭ 由 R41b 追加")
if old_footer not in t:
    sys.exit("导图 footer 未命中")
t = t.replace(old_footer, card + "\n" + new_footer, 1)
p.write_text(t, encoding="utf-8")
print("导图已追加 ⑭")

# ── 联动表状态行 ───────────────────────────────────────────────────────
p = ROOT / "docs/R41_P1_信息架构_联动表.md"
t = p.read_text(encoding="utf-8")
if "**状态：" not in t:
    t = t.replace("> 施工顺序：", "> **状态：已落地（1.0.0+52 / CI run 52），落地报告见 `docs/R41_P1_信息架构_落地.md`。**\n> 施工顺序：", 1)
    p.write_text(t, encoding="utf-8")
    print("联动表已标状态")

# ── 落地报告 ───────────────────────────────────────────────────────────
report = """# R41b｜调音台 P1 信息架构 · 落地报告

> 版本 `1.0.0+52` · CI run 52 `completed success`（26 步）· APK 17,968,245 字节
> 直链 blob `e32c2e9088b978d51ffeaec0cfeaa598c6904366`（与本地逐字节一致）

## 这轮干了什么

上一轮跑到最大调用轮次，留下三件未完：CI 没挂 `r41_verify.py`、项目文件头部仍停在 `+51`、
新包没取回核验。本轮**只做收尾，没改一行业务代码** —— 源码与 run 52 构建产物一致。

1. `.github/workflows/ci.yml` 补「R41 P1 信息架构验证」step（联动表第 80 条）。
2. 本地提交 → API 同步 → **run 52 真 Kotlin 编译通过**，26 步零失败，native 缓存命中。
3. 新增 `scripts/r41b_fetch_apk.py`：按 run 号取包 + 校验 CI 全绿 + blob 自校验 + 落盘，
   替代写死 `51` 的 `r41a_fetch_apk.py`。**每轮交付前必跑**，防 R33 那种「直链还是旧版」。
4. 拆包硬验 PASS（CRC / 7 .so / 权重 0 / 无 INTERNET / `+52` / 新符号 / 旧编号零残留 / `TTS_PARAMS_APPLIED`）。
5. 项目文件回填：STATUS / README / ITERATION_LOG / 导图 ⑭ / 联动表状态 / 本报告。

## 验证脚本自身的订正（不放水，只同步形态）

| 脚本 | 问题 | 处理 |
|---|---|---|
| `r36_verify.py` | 4 条断言查的字面量在 R41 后已不存在（`rememberSaveable(key)`、`MixerPresetStore.delete(c, p.id)`、`ParamCatalog.SAMPLING.forEach`、`恢复官方默认 ${spec.def}`），本地跑是假红 | 换成等价新形态：受控 `openMap` + `Prefs` 读写、三组紧凑表、`ParamRow.onReset`、删除确认 / 重命名调用点；反向注入 9/9 真拦下，对拍 6 组 |
| `r40_apk_verify.py` | 资料区标题已从「参数触达矩阵与官方资料」改名，旧断言假红；未覆盖 R41 新增符号 | 标题订正，补 12 个 R41 新符号 + 7 条旧编号零残留；反向注入 2/2 拦下（删「对比并载入」/ 恢复「① 采样参数」） |

两张表都按长期记忆 playbook 做了「注入违规必须 FAIL」的反向验证，不是把断言删绿。

## P1 六条（已在 +52 包内）

- **7** 锚点 chip 条 `身份 | 采样 | 表演 | 开关 | 角色 | 预设 | 资料`，点一下滚到对应区；展开态存 `Prefs.mixerOpenSections`，重进页面仍在（旧 `rememberSaveable` 只跨旋转）。
- **8** 去掉圈码与 `O1~O7` 元信息外泄，统一用分区标题；「导出与诊断」并入资料区，8 区收成 7 区。
- **9** 9 项采样改主 5 / 副 3 / 确定性 1 三组 + 共用 `ParamRow`，组尾「恢复本组官方默认」，全页仍只有顶部「提交变更」。
- **10** 精确值框默认带出当前值，带 min/max/单位、越界红字、回车规范化；范围只在 `ParamSpec` 定义一处（抄 PocketPal `validateNumericField`）。
- **11** 预设「对比并载入」逐项 `当前生效 → 该预设` diff 后确认；空名与 `untitled` 禁存，可重命名，删除二次确认。
- **12** ② 网格与 ⑤「初始音色」合并成同一个 `VoicePicker`（搜索 + 最近使用），不再两处各写一套。

## 守恒（与 run 51 基线逐字节比对）

`MixerDraft` / `MixerCommit.commit()` / 吸顶三段式 / R41a 两态试听 / `VoiceProfile.build` /
4B 设置 / `prompt_zh.txt` v2 / 两条制作路径 / `bucket()` / `TaskResume` / `ProduceService` /
`AndroidManifest` / native / JNI / `qt_tts_params` 9 字段 / Gradle 依赖 —— 一行未动。

## 边界

- 不声称「界面已改好 / 声音已变好」；本轮只解决「看得清、找得到、不误操作」+ 收尾核验。
- **真机仍是零证据**：用户已装 `+51`，`+52` 需在 24117RK2CC 上点一次，P1 六条验收才算数。
- 375px 12 维 chip 换行属 P2，本轮只保证不新增横向滚动。
- P2（13~17）与 V1 抽屉壳按队列顺延，各自单独发版，不夹带。
"""
(ROOT / "docs/R41_P1_信息架构_落地.md").write_text(report, encoding="utf-8")
print("落地报告已生成")
print("R41b 项目文件回填完成")
