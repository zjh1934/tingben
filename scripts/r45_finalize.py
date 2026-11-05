#!/usr/bin/env python3
"""R45 收尾：落地报告 / 项目文件回填 / 交付包 / 远端同步（[skip ci]）。"""
import pathlib, sys, zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
WS = pathlib.Path("/data/workspace")
RUN, SIZE, BLOB = 55, 17984619, "e5a126d4d66a62f4933d671dc5c81847b5f9061e"
VER = "1.0.0+55"

def rep(path, pairs):
    p = ROOT / path
    s = p.read_text(encoding="utf-8")
    for i, (old, new) in enumerate(pairs, 1):
        n = s.count(old)
        if n != 1:
            sys.exit(f"{path} 第 {i} 条命中 {n} 次，整体不写盘")
        s = s.replace(old, new, 1)
    p.write_text(s, encoding="utf-8")
    print("回填", path, len(pairs), "条")

report = f"""# R45 · 调音台 v4 落地报告（{VER} / run {RUN}）

**输入**：14 屏预览 v4 通过 + 用户本轮口径「该折叠就折叠 / 参数跟模型一一对齐、确认可调 /
不可调的收进后程折叠 / 固定音色看不懂也要有说明 / 预览通过才改代码 / 别什么都手搓」。
**范围**：只改界面层 + 一处 UI 偏好默认值 + 一个设置页入口。真值源、引擎、主链、Gradle 一行未动。

---

## 一、落地内容

| 项 | 落点 |
|---|---|
| 默认全折叠 | `Prefs.mixerOpenSections` 默认值 `identity` → `""`；首屏音色身份卡仍常驻不折叠。只有主动展开过才落盘，改默认值对老用户立即生效 |
| 固定音色（设置页） | 「② 音频模型」组新增「固定音色」入口，点一下把 `fixedVoice` 记成展开再进调音台；不新增 Screen、不改任何函数签名 |
| 固定音色（调音台） | 后程折叠「固定音色 · 官方模式说明」：三模式对照、9 个编号只读、当前能固定的三样、橙色提示「收敛漂移 ≠ 官方跨段一致」、明写「不画开关」 |
| 参数三态 | 后程折叠「哪些能改 · 和模型一一对齐」：✅ 真到引擎 / ⚠️ 上层 / 🔒 结构上没有，逐项只读，清单直接取 `ParamCatalog`，不手抄第二份 |
| 采样 9 项 | 标题改「9 项真到模型」+ `badge=✅ 可调`；9 项逐字段仍走 `ParamCatalog.find` 与 JNI |
| seed | 「⟳ 换一个」生成新的**固定**种子（仍可复现），`-1` 引擎随机另算一档；只写草稿，不直接生效 |
| 12 维 | 每维挂一个小自定义输入框，自定义值按「维度：值」拼进描述；窄屏仍 `FlowRow+FilterChip` 自动换行 |
| 提交与试听 | 差异弹层明写「提交不依赖试听」；全页仍只有「提交变更」一个生效动词 |
| 底部 | 滚动末尾 18dp → 40dp 留白，不重复加 `navigationBarsPadding()` |

**不手搓**：三态清单来自 `ParamCatalog` 单一定义；折叠/锚点/开关/help 全复用 R36~R42 既有组件；
设置页入口复用 `Prefs.setMixerOpenSections` 的展开态机制，没造第二套导航。

**明确没做（如实）**：v4 预览 ⑥ 画过「滑块 + 小数字框」双模，本轮**未引 Slider** ——
保留已落地的「快捷 chip + 精确值输入框 + 单项 ⟳ 默认」等价双模，避免 9 个滑块把折叠区又撑成长清单。
要滑块单独一轮。不画 speaker 选择、参考音频上传、`speed/pitch` 开关、量化档下拉。

## 二、底层逻辑没绕混（对着模型与源码核过）

- 官方三模式是三份权重，当前 `voicedesign` 没有 speaker id、没有音色锁、不吃参考音频；
  CustomVoice 的 9 编号与 Base 的 3 秒参考音频都要换权重，本软件界面一律不画开关。
- `qt_tts_params` 里 9 项采样 + `instruct` + `lang` + `speaker` 是真到引擎；
  12 维 / 表演层 / 段间静音是上层；`speed`/`pitch`/并发/24kHz/量化档结构上不存在。
- `commitAll()` 只看 `MixerCommit.diff()`，不依赖试听；`trial()` 不写 Prefs、不进 draft、不改指纹。

## 三、联动与守恒

13 处联动先列表再动代码（见 `docs/R45_调音台v4_联动表.md`）。守恒：
`MixerDraft` / `Mixer` / `ParamSpec` / `Pipeline` / `VoiceProfile` / `PlayQueue` /
`NativeEngine` / `ProduceService` / `AppChrome` / native cpp / prompt v2 / Manifest / Gradle
与 HEAD 逐字节一致；`Prefs.kt` 除默认值那一段外逐字节一致；`MixerScreen` 段内无第二个写生效态入口。

## 四、CI 失败一次，没当偶放

**run 54 failure · step 3「结构自检」**：`check_structure.py` 里 R38 的旧标题
「采样参数 · 9 项到引擎」在本地靠 docs 里的历史文档命中而 PASS，CI 环境的 `all_src`
口径不同（不含 docs）→ 假阴性变真失败。已把该断言同步到新标题「采样参数 · 9 项真到模型」，
并在本地用 `CI=true` 复现全 7 套脚本后才重提。**run 55 `completed success`**（28 步，
含新增 R45 验证 step；native 缓存命中，`交叉编译` 与 `编译失败日志回写` 均 skipped）。

期间另订正两处验证脚本自身问题（不是放水）：
- `r45_verify` 的「界面层写入口」正则误命中 `Prefs.setMixerOpenSections`（UI 偏好，非生效态），
  改成负向前瞻排除，并把该断言放进基线，反向注入 10/10 全拦下。
- `r45_apk_verify` 查「🔒 结构上没有」按 UTF-8 直搜 dex 假阴性：🔒 是增补平面字符，
  dex 的 MUTF-8 用 CESU-8 代理对编码，改查去掉 emoji 的中文子串后 PASS。

## 五、验证

- 本地 7 套脚本全绿（`check_structure` / `r38_param_audit` / `r40` / `r41a` / `r41` / `r42` / `r45`），
  且 `CI=true` 模式复现全绿。
- R45 验证：正向 39 符号 / 只读区 2 块 / 反向注入 10 例 / 守恒 15 项。
- 拆包硬验 PASS：CRC 全过 / 7 个 arm64 .so / 权重 0 / 无 INTERNET / binary Manifest `{VER}` /
  41 个新符号全在 / 18 条旧符号零残留 / `.so` 里 `TTS_PARAMS_APPLIED` 仍在。
- 直链 blob `{BLOB}` 与本地 APK **{SIZE} 字节逐字节一致**（按 run 号取包，防 R33 那种直链滞留旧版）。

## 六、真机与队列

⚠️ **真机仍是零证据**：默认全折叠顺不顺手、固定音色说明读不读得懂、seed「⟳ 换一个」是否出声、
12 维自定义框在 375px 会不会挤，都要在 24117RK2CC 上点一次。
**本轮交付的是「分层清楚、参数对得上、不可调的不装成可调」，不是声音已经变好。**

队列不变：**真机验 → V1 抽屉壳 → V2 目录页与绿勾 → V2b 看书页 → V3 迷你播放条 →
标注工厂导入通道 → U2**。采样 Slider 双模若用户要，插在真机验之后单独一轮。
"""
(ROOT / "docs/R45_调音台v4_落地.md").write_text(report, encoding="utf-8")
print("写落地报告")

rep("STATUS.md", [
    (f"""**2026-11-05 · R42 P2 已发（CI run 53）· 调音台文案 / 响应式 / 视觉：开发者信息收高级抽屉 / 12 维 FlowRow 自动换行 / 开关 info / 细分割线 / 时机收口**""",
     f"""**2026-11-05 · R45 调音台 v4 已发（CI run {RUN}）· 默认全折叠 / 固定音色官方说明 / 参数三态对齐 / seed 换一个 / 12 维自定义框 / 设置页入口**"""),
    ("""| 版本 | `1.0.0+53`（17,968,240 字节；直链 blob `f984cc977abbee2e233d18d96b264f8f6ede3aab` 与本地逐字节一致） |
| APK | 17,968,240 字节 / arm64-v8a / 固定 debug 签名 / 发布详情见 `docs/R42_P2_落地.md` |""",
     f"""| 版本 | `{VER}`（{SIZE} 字节；直链 blob `{BLOB}` 与本地逐字节一致） |
| APK | {SIZE} 字节 / arm64-v8a / 固定 debug 签名 / 发布详情见 `docs/R45_调音台v4_落地.md` |"""),
    ("""| CI | **run 53 `completed success`**（27 步，含新增 R42 P2 验证 step；native 缓存命中） |""",
     f"""| CI | **run {RUN} `completed success`**（28 步，含新增 R45 验证 step；native 缓存命中）。run 54 因结构自检旧标题断言失败，已修 |"""),
    ("""- **R42 P2**：调音台开发者信息收「高级」默认折叠、12 维与表演层 `FlowRow+FilterChip` 375px 不碎字、开关 info、细分割线、时机提示统一；详见 `docs/R42_P2_落地.md`""",
     """- **R42 P2**：调音台开发者信息收「高级」默认折叠、12 维与表演层 `FlowRow+FilterChip` 375px 不碎字、开关 info、细分割线、时机提示统一；详见 `docs/R42_P2_落地.md`
- **R45 调音台 v4**：默认全折叠；新增「固定音色 · 官方模式说明」与「哪些能改 · 和模型一一对齐」两个后程只读折叠区（不可调项不画开关）；seed「⟳ 换一个」写草稿；12 维每维小自定义框；设置页加固定音色入口；提交与试听解耦写进差异弹层。详见 `docs/R45_调音台v4_落地.md`"""),
    ("""## 下轮队列（R41b 之后）

1. **真机验**：`+52` 装到 24117RK2CC —— ① 锚点 chip 能否跳到对应区；② 离开重进，展开态是否还在；
   ③ 精确值框是否带出当前值、越界是否红字；④ 预设「对比并载入」的 diff 是否逐项可读；
   ⑤ 两个试听按钮点一下是否仍自动出声（`MIXER_TRIAL` 两套 hash 可对照）；⑥ 全页是否再无 A/B 字样。
2. **R42 · P2**（评审 13~17）：开发者信息收「高级」抽屉、375px 12 维 chip 换行、toggle 补开/关文字、
   说明降级为 help 图标、时机提示抽统一组件 —— 单独一轮
3. **V1 抽屉壳**（R34/R35 定案）：左滑抽屉、底栏 3 Tab 收敛进抽屉、调音台独立项 —— 连续顺延，仍单独一轮
5. V2 目录页 + 按章绿勾 → V2b 看书页 → V3 全局迷你播放条 → 标注工厂导入通道
7. 标注工厂 JSON 导入通道（`docs/标注工厂_输出契约.md`，动 `Producer.start` 签名，单独一轮；端侧 4B 原样保留）
8. 调音台显示当前任务真实 `ProduceConfig` → 4B 设置沉页尾（不动逻辑）
9. U2 顺延项：其余 3 页下拉刷新、通知渠道 description 清理、设置页 1517 行「A/B」历史注释""",
     f"""## 下轮队列（R45 之后）

1. **真机验**：`{VER}` 装到 24117RK2CC —— ① 进调音台是不是默认全折叠、首屏身份卡还在；
   ② 设置页「固定音色」点进去是否直接展开官方说明；③ 固定音色三模式那段读不读得懂；
   ④ seed「⟳ 换一个」是否写进待提交且能试听；⑤ 12 维自定义框在 375px 会不会挤；
   ⑥ 差异弹层「提交不依赖试听」是否清楚；⑦ 两个试听按钮点一下是否仍自动出声。
2. **V1 抽屉壳**（R34/R35 定案）：左滑抽屉、底栏 3 Tab 收敛进抽屉、调音台独立项 —— 连续顺延，仍单独一轮
3. V2 目录页 + 按章绿勾 → V2b 看书页 → V3 全局迷你播放条 → 标注工厂导入通道
4. 标注工厂 JSON 导入通道（`docs/标注工厂_输出契约.md`，动 `Producer.start` 签名，单独一轮；端侧 4B 原样保留）
5. 采样 9 项「滑块 + 数字框」双模（v4 预览 ⑥ 画过，本轮未引 Slider）—— 用户要才插队
6. 调音台显示当前任务真实 `ProduceConfig` → 4B 设置沉页尾（不动逻辑）
7. U2 顺延项：其余 3 页下拉刷新、通知渠道 description 清理、设置页 1517 行「A/B」历史注释"""),
])

rep("README.md", [
    ("""- 固定地址**永远是最新版**，版本号跟 CI run 号（如 `1.0.0+53`），当前版本见 `STATUS.md` 头部""",
     f"""- 固定地址**永远是最新版**，版本号跟 CI run 号（如 `{VER}`），当前版本见 `STATUS.md` 头部"""),
    ("""- **调音台（R31 建，R33 提一级，R36/R38 重设计，R40 统一提交，R41a 试听改版，R41 P1 信息架构，R42 P2 文案/响应式/视觉）**""",
     """- **调音台（R31 建，R33 提一级，R36/R38 重设计，R40 统一提交，R41a 试听改版，R41 P1 信息架构，R42 P2 文案/响应式/视觉，R45 v4 分层）**"""),
])

log = ROOT / "ITERATION_LOG.md"
log.write_text(log.read_text(encoding="utf-8") + f"""

## R45 · 调音台 v4 落地（run {RUN} / {VER}）

- **v4 预览 14 屏通过后落代码**：默认全折叠（`mixerOpenSections` 默认值 → 空）；新增两个后程只读折叠区
  「固定音色 · 官方模式说明」（三模式 / 9 编号 / 当前能固定的三样 / 收敛漂移≠官方一致 / 明写不画开关）与
  「哪些能改 · 和模型一一对齐」（✅ 到引擎 / ⚠️ 上层 / 🔒 结构不可调，清单取 `ParamCatalog`）；
  设置页「② 音频模型」加固定音色入口（复用展开态偏好，不改签名）；采样标题改「9 项真到模型」；
  seed「⟳ 换一个」写草稿；12 维每维小自定义框；差异弹层写「提交不依赖试听」；末尾留白 40dp。
- **底层对齐**：官方三模式是三份权重，当前 voicedesign 无 speaker id / 音色锁 / 参考音频入口；
  `speed`/`pitch`/并发/24kHz/量化档在 `qt_tts_params` 里物理不存在，一律不画开关。
- **不手搓**：三态清单来自 `ParamCatalog`；折叠/锚点/help/开关全复用既有组件；没引 Slider（v4 ⑥ 的滑块双模本轮未做，如实登记）。
- **联动 13 处先列表再动**；守恒 13 个 core/引擎/主链文件 + prompt/Manifest/Gradle 与 HEAD 逐字节一致，
  `Prefs.kt` 除默认值那段外一致，`MixerScreen` 段内无第二个写生效态入口。
- **CI run 54 failure**：`check_structure.py` 的 R38 旧标题断言在本地靠 docs 命中、CI 的 `all_src` 不含 docs 而失败；
  同步到新标题并本地 `CI=true` 复现全 7 套脚本后重提。**run 55 `completed success`**（28 步，新增 R45 验证 step）。
- **验证脚本自身订正 2 处**：`r45_verify` 写入口正则误命中 `Prefs.setMixerOpenSections`（UI 偏好），改负向前瞻并纳入基线（反向 10/10）；
  `r45_apk_verify` 查 🔒 按 UTF-8 直搜 dex 假阴性（MUTF-8/CESU-8 代理对），改查中文子串。
- **拆包硬验 PASS**：CRC / 7 个 arm64 .so / 权重 0 / 无 INTERNET / `{VER}` / 41 个新符号 / 18 条旧符号零残留 /
  `TTS_PARAMS_APPLIED` 在；直链 blob `{BLOB}` 与本地 {SIZE} 字节逐字节一致。
- ⚠️ **真机仍零证据**。队列：真机验 → V1 抽屉壳 → V2/V2b/V3 → 导入通道 → U2（采样 Slider 双模待用户点头）。
""", encoding="utf-8")
print("回填 ITERATION_LOG")

card = f"""
<div class="card" style="margin-top:14px">
<div class="h">⑯ R45 · 调音台 v4 分层（{VER} / run {RUN}）</div>
<b>输入：</b>14 屏预览 v4 通过；用户口径「该折叠就折叠 / 参数跟模型一一对齐 / 不可调的收进后程折叠 / 固定音色看不懂也要说明」。<br>
<b>落地：</b>默认全折叠（首屏身份卡仍常驻）；设置页与调音台各一个「固定音色」入口；两个后程只读折叠区
「固定音色 · 官方模式说明」（三模式 / 9 编号 / 收敛漂移≠官方一致 / 不画开关）与
「哪些能改 · 和模型一一对齐」（✅ 到引擎 / ⚠️ 上层 / 🔒 结构不可调，清单取 <code>ParamCatalog</code>）；
采样 9 项标「真到模型」；seed「⟳ 换一个」写草稿；12 维每维小自定义框；差异弹层写「提交不依赖试听」。<br>
<b>底层：</b>官方三模式是三份权重，当前 <code>voicedesign</code> 无 speaker id / 音色锁 / 参考音频入口；
<code>speed</code>/<code>pitch</code>/并发/24kHz/量化档在 <code>qt_tts_params</code> 里不存在，一律不画开关。<br>
<b>没做：</b>v4 ⑥ 的滑块双模本轮未引 Slider（保留 chip + 精确值等价双模），如实登记；不画 speaker 选择、参考音频上传。<br>
<b>联动：</b>13 处；守恒 13 个 core/引擎/主链文件 + prompt/Manifest/Gradle 与 HEAD 逐字节一致。<br>
<b>验证：</b>本地 7 套脚本全绿且 <code>CI=true</code> 复现全绿（R45 正向 39 符号 / 只读区 2 块 / 反向注入 10 例 / 守恒 15 项）·
CI <b>run 54 failure</b>（结构自检旧标题断言，已修）→ <b>run 55 全绿</b>（28 步）·
拆包 CRC / 7 .so / 权重 0 / 无 INTERNET / <code>{VER}</code> / {SIZE} 字节 / 41 个新符号 / 18 条旧符号零残留 ·
直链 blob <code>{BLOB[:8]}…</code> 逐字节一致。<br>
<b>真机仍零证据</b>；队列：真机验 → V1 抽屉壳 → V2/V2b/V3 → 导入通道 → U2。
</div>
"""
html = ROOT / "docs/听本_项目导图.html"
h = html.read_text(encoding="utf-8")
h = h.replace("<footer>听本 · 项目导图", card + "\n<footer>听本 · 项目导图", 1)
h = h.replace("⑮ 由 R42 追加", "⑮ 由 R42 追加，⑯ 由 R45 追加", 1)
html.write_text(h, encoding="utf-8")
print("回填项目导图 ⑯")

out = WS / "听本_R45_项目文件.zip"
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for rel in ["docs/R45_调音台v4_联动表.md", "docs/R45_调音台v4_落地.md",
                "STATUS.md", "README.md", "ITERATION_LOG.md",
                "docs/听本_项目导图.html", "scripts/r45_verify.py",
                "scripts/r45_apk_verify.py"]:
        z.write(ROOT / rel, rel)
print("交付包", out, out.stat().st_size, "字节")
