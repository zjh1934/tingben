#!/usr/bin/env python3
# R28 · 项目文件维护：删性能页、勾 P1③、修过时表述、同步代码地图。
import pathlib
R = pathlib.Path(__file__).resolve().parents[1]

def sub(p, old, new, tag, count=1):
    s = p.read_text()
    n = s.count(old)
    assert n == count, f"[{tag}] 命中 {n} 次（期望 {count}）：{old[:60]!r}"
    p.write_text(s.replace(old, new, count))

# ── R19 路线图 ──
p = R / "docs/R19_路线图与决策记录.md"
sub(p,
"> 本文件把口头结论钉成项目决策，避免下一轮又靠聊天记忆推进。**本轮只维护文档 + 推进 P0 三件，不假装全做完。**",
"> 本文件把口头结论钉成项目决策，避免下一轮又靠聊天记忆推进。**本轮只维护文档 + 推进 P0 三件，不假装全做完。**\n> **R28 维护（2026-11-04）**：P1③ 已兑现；用户判定「性能页意义不大」，从队列删除、不排期、不留占位。",
"R19 头")

sub(p,
"""3. **段级「重做这一段」**→ Workbench 从只读升级成可写，牵动 AnnCache 与 Producer → 队列
""",
"""3. ~~**段级「重做这一段」**~~ → **R28 已落地**。移植 ReaderVoice-Mobile `Scheduler.invalidate`
   / `RenderUnitRef.cacheKey`（MIT）的单元级失效：只删目标段的 wav 或目标块的 ann+flag+该块 wav，
   禁整章 / 整本清缓存；`Producer.start` 签名 / `bucket()` / `TaskResume` / native / prompt v2 全未动。
   顺带修两个真 bug：看台把「块索引」和「段索引」按同一个 i 并排画格、续跑后逐条来源全丢。
   见 `docs/R28_段级重做与判定条_联动表.md`（11 处联动）。
4. ~~**性能页（CPU / GPU / 内存浮窗）**~~ → **用户判定意义不大，删除**。
   调研结论亦不支持：Android 8/10 起 SELinux 挡 `/proc/stat`、GPU 忙闲无公开 API、浮窗不是前台服务。
   **不排期、不做占位控件。** 调研留档 `docs/后台保活与浮窗_调研.md`。
""",
"R19 P1")

sub(p, "**下一轮第一件改为 ③**。", "**③ 已于 R28 兑现；下一轮按 `docs/本次测试_语言模型生效判定卡.md` 的回传结果定。**", "R19 下一轮")

# ── STATUS ──
p = R / "STATUS.md"
s = p.read_text()
s += """

---

## R28 · 段级重做 + 语言模型生效一眼判定（2026-11-04）

**用户三条指令**：性能页不做（意义不大）／按节奏推进 P1③／「正在跑测试，一点底都没有，语言模型到底有没有起作用」。

**核心交付不是又一个版本号，是把判定从「贴日志给我」变成 App 内可判、可干预：**

- 新增 `core/SegPlan.kt`：段映射 `segmap.json`、逐条来源 `flag_%04d.json`、脏标记 `redo.json`、
  两个重做动作、verdict 汇总。语义移植 ReaderVoice-Mobile `Scheduler.invalidate` / `RenderUnitRef`（MIT）。
- 进度页看台上方新增 **「语言模型是否生效」** 判定条，四口径分开：直接朗读 / 还没数据 /
  「已生效：X/Y 段用了模型标注」/「**没起作用：Y 段全是直读**」；日志同步写 `LLM_VERDICT`。
- 看台点开一格显示该段**原文全文 + speaker + instruct + 来自第几块第几条 + 是否模型标注**，
  两个按钮：「重合成这一段」（不重跑 4B）、「重新标注本块」（只重跑这一块 4B）。
- 顺带修两个真 bug：`ann_`（块）与 `chunk_`（段）索引错格；续跑后来源全变 UNKNOWN。

**11 处联动先列表再动代码**；`Producer.start` 签名 / `bucket()` / `TaskResume` / native / prompt v2 /
`voice_stamp` / `produce_stamp` 双指纹全部未动。

**验证**：结构自检（SegRow 10 字段与 UI 引用对齐、旧字段零残留、括号差值改前改后一致）、
语义对拍（重合成只删 1 段、重标只作废目标块）、判定口径对拍、反向验证（无整目录清空 /
无递归删 / 不调用 `AnnCache.clear` / direct 不混「模型没采纳」/ 旧缓存标未知）。
验证脚本自身 3 条断言写错，逐条核实后修正再跑全绿 —— 按纪律记一笔，不把自检红字当产品失败，也不直接忽略。

⚠️ **沙盒无 Android SDK，本地只有静态与逻辑验证；以 CI 真编译为准，不谎称已编译通过。**

文档：`docs/R28_段级重做与判定条_联动表.md`、`docs/本次测试_语言模型生效判定卡.md`。
**性能页从队列删除**，不排期、不留占位。
"""
p.write_text(s)

# ── ITERATION_LOG ──
p = R / "ITERATION_LOG.md"
p.write_text(p.read_text() + """

---

## R28 · 2026-11-04 · 段级重做 + 判定条 + 项目文件维护

**用户**：性能页不做（意义不大）；按节奏推进；正在跑真机测试，「一点底都没有，语言模型到底有没有起作用」；**别手搓，去看别人怎么做的拿过来改**。

**拿来主义**：逐行读了本地 `reference/ReaderVoice-Mobile/scheduler/Scheduler.kt`（44-68 `plan`、70-73 `invalidate`）、
`RenderUnitRef.kt`（16-34 稳定段 id + cacheKey）、`AudioCache.kt`（只认 wav 按序），MIT。
改写成线性 Producer 能用的形状：脏标记一个 `redo.json`，重做靠删文件让既有跳过逻辑自然失效，
**零签名改动**。

**改了 4 个源码文件 + 新增 1 个**：
- 新增 `core/SegPlan.kt`（段映射 / flags / 脏标记 / 两动作 / verdict）
- `core/Pipeline.kt`：公共展开记 `segOrigin`、写 flags、`segmap.json` + `LLM_VERDICT`、成功后清待办
- `core/Workbench.kt` 重写：segmap 为主、旧目录降级且一律 UNKNOWN
- `MainActivity.kt`：判定条 + 逐段详情 + 两个重做按钮 + `redoNonce`

**修掉两个真 bug**：看台把块索引与段索引按同一 i 并排（颜色与声音永远对不上）；
R27 的 `srcFlags` 只在内存里算，续跑后来源全丢。

**验证**：结构自检 + 语义对拍 + 判定口径对拍 + 反向注入，全绿。
期间验证脚本自己写错 3 条断言（`val direct` 实为 `run()` 参数、期望 (1,1,1,4) 实为 (2,1,1,4)、
`AnnCache.clear` 是既有 `clearProgress`），逐条核实后修正再跑，未当产品失败。

**维护**：R19 勾掉 P1③、删除性能页 P1④、修「下一轮第一件」；STATUS 追加 R28；
README 修「前台服务顺延到下一轮」的过时表述、代码地图补 `SegPlan.kt`；导图同步。

**队列**：下一轮按判定卡回传的 `ANN_CONTENT` / 未采纳首因定 —— 仍是 `NOT_FOUND_AFTER_0`
就启动骨架化标注（换契约，单独一轮）；`model > 0` 但听不出差别就转音频侧。
""")

# ── README ──
p = R / "README.md"
sub(p,
"  进度页与分段看台也区分「模型真标注」和「模型没采纳按原文直读」",
"  进度页与分段看台也区分「模型真标注」和「模型没采纳按原文直读」；\n  看台上方有「语言模型是否生效」判定条，不用贴日志就能判（见 `docs/本次测试_语言模型生效判定卡.md`）",
"README 判定条")

sub(p,
"4. 进度页看阶段、分段看台点小格试听单段 →「任务」页管长任务 →「我的音频」里完整播放器试听 / 保存到手机",
"4. 进度页看阶段、分段看台点小格看原文 / 试听 / 重做这一段 →「任务」页管长任务 →「我的音频」里完整播放器试听 / 保存到手机",
"README 步骤")

sub(p,
"  core/Workbench.kt        分段看台数据（扫 work 目录，不造状态）",
"  core/SegPlan.kt          段映射 / 逐条来源 / 单元级失效（移植 ReaderVoice scheduler, MIT）\n  core/Workbench.kt        分段看台数据（读 segmap.json，旧缓存降级且标未知）",
"README 代码地图")

sub(p,
"- **后台**：任务跑着时前台屏幕常亮（`FLAG_KEEP_SCREEN_ON`，零权限）；**这不等于保活**。锁屏/切后台后靠逐块落盘恢复，前台服务 + 唤醒锁顺延到下一轮。详见 `docs/后台保活与浮窗_调研.md`。",
"- **后台**：任务跑着时前台屏幕常亮（`FLAG_KEEP_SCREEN_ON`，零权限）；**这不等于保活**。前台服务 `dataSync` + 可见通知 + 按块 `PARTIAL_WAKE_LOCK`（R26 已落地），锁屏/切后台后靠逐块原子落盘 + 任务页恢复，目标仍是「到点停 + 回来点继续」。详见 `docs/后台保活与浮窗_调研.md`。",
"README 后台过时表述")

print("R19 / STATUS / ITERATION_LOG / README 已维护")
