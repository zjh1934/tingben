#!/usr/bin/env python3
"""R49 · 项目文件维护 + 落地报告 + 交付包（CI run 58 之后执行）。"""
import pathlib, zipfile, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = pathlib.Path("/data/workspace")
SIZE = 17984624
BLOB = "8f96fc264c2fd25274be8e0c7aa579189dd9aa49"


def sub(p, pairs):
    f = ROOT / p
    s = f.read_text(encoding="utf-8")
    orig = s
    for i, (old, new) in enumerate(pairs, 1):
        n = s.count(old)
        if n != 1:
            sys.exit(f"{p} 第 {i} 条命中 {n} 次（应为 1）")
        s = s.replace(old, new, 1)
    f.write_text(s, encoding="utf-8")
    print(p, "更新", len(pairs), "条")


sub("STATUS.md", [
    ("**2026-11-05 · R47 抽屉收窄 + v4 预览 ⑥ 落地（CI run 57）· 抽屉 240dp / 采样滑块双模 / 参数人话名 / 官方字段收进 ⓘ**",
     "**2026-11-05 · R49 调音台 v5 重设计（CI run 58）· 细状态条 / 锚点换行 / 采样一行一项 / 身份卡摘要与 diff / 试听折叠**"),
    ("| 版本 | `1.0.0+57`（17,984,635 字节；直链 blob `eb7eee71965dbe5af589894feb2746be4f3ebb5a` 与本地逐字节一致） |",
     f"| 版本 | `1.0.0+58`（{SIZE:,} 字节；直链 blob `{BLOB}` 与本地逐字节一致） |"),
    ("| APK | 17,984,635 字节 / arm64-v8a / 固定 debug 签名 / 发布详情见 `docs/R47_抽屉收窄与预览落地_落地.md` |",
     f"| APK | {SIZE:,} 字节 / arm64-v8a / 固定 debug 签名 / 发布详情见 `docs/R49_调音台v5_落地.md` |"),
    ("| CI | **run 57 `completed success`**（30 步，含新增 R47 验证 step；native 缓存命中） |",
     "| CI | **run 58 `completed success`**（31 步，含新增 R49 验证 step；native 缓存命中） |"),
    ("- **R47 抽屉收窄 + v4 ⑥ 落地**：",
     "- **R49 调音台 v5**：真机截图评审后重排界面层 —— 吸顶大卡换 48dp 细状态条、两态试听移进默认折叠区、\n"
     "  锚点 `FlowRow+FilterChip` 自动换行并高亮当前区、身份卡摘要 + 展开全文/编辑 + 逐维 diff、\n"
     "  采样 9 项改一行一项（中文名·滑块·小数字框·⟳）、三态徽标分区、固定音色锚点直达、底部 48dp 留白。\n"
     "  **只改 `MainActivity.kt` 的 `AnchorBar`/`ParamRow`/`MixerScreen`**，参数一个不删、唯一提交出口与两态试听原样。\n"
     "  详见 `docs/R49_调音台v5_落地.md` / `docs/R49_调音台v5_联动表.md`。\n"
     "- **R47 抽屉收窄 + v4 ⑥ 落地**："),
])

sub("README.md", [
    ("（如 `1.0.0+57`）", "（如 `1.0.0+58`）"),
])

log = f'''

## R49 · 调音台 v5 重设计（run 58 / 1.0.0+58）

- **输入**：用户贴 `+57` 真机截图两张（采样页 / 身份页），问「设计的合理吗，重新设计」；
  R48 出 12 屏 v5 预览 + 定案，本轮用户批「方案通过」后落代码。
- **真机八条证据**：顶部大卡占首屏 40% 且紫色试听按钮压过灰色提交；7 个锚点横溢把「固定音色」裁成「固」；
  身份卡同一段 168 字渲染两遍无 diff；单个参数 ≈300px（滑块与精确值框上下各占满宽）、9 项摊 3.5 屏；
  ⓘ 小字重复、三键系统栏压滑块下半截、固定音色说明在长页最底找不到、整体像放大版开发者表单。
- **落地（只改界面层）**：① 48dp 细状态条（返回 / 已生效 / 未提交 N / 查看差异 / **提交变更**唯一紫色实心）；
  ② 两态试听 + 试听文本 + 缺模型提示移进 `MixerSection("试听","trial")` 默认折叠，写死「改完直接提交，不必先试听。」；
  ③ `AnchorBar` 改 `FlowRow+FilterChip` 自动换行 + `current` 高亮，7 项含「固定音色」，`jumpTo` 先展开目标区；
  ④ 身份卡默认摘要 + 「展开全文 / 编辑描述」+ `voiceFieldDiff()` 逐维改动，编辑框默认仍可见（防 R38 找回的输入框又藏起来）；
  ⑤ `ParamRow` 一行一项：中文名 · 滑块 · 小数字框 · ⟳ · 展开箭头，常用值/官方字段收进 ⓘ，数字框即精确值不再画第二个框；
  ⑥ 三态徽标：采样「✅ 可调」、12 维/表演/开关「上层」并加「辅助设置 · 上层」分组标题、预设/角色「配置」、
  固定音色/对齐/资料「只读」；🔒 结构项一个开关都不画；⑦ 底部 40dp + 8dp = 48dp。
- **底层逻辑没混**：9 项全部 `ParamKind.ENGINE` 真到 `qt_tts_params`（key/official/def/min/max 逐项对拍未动）；
  ⚠️ 上层（12 维只是写描述模板、朗读档位拼 instruct、段间静音 WAV 合并）能改但不是模型字段；
  🔒 `speed`/`pitch`、并发、codec、speaker 表、量化档物理不存在。提交只看 `dirty`，**不依赖试听**。
  **固定音色**：官方三模式是三份权重（VoiceDesign 当前 / CustomVoice 9 编号 / Base 3 秒克隆），已决定不换模型，
  后两条不是可选项、不画开关；当前能固定的是「描述 + seed + 9 项采样全书不变」＝收敛漂移 ≠ 官方跨段完全一致。
- **守恒**：`ParamSpec` / `Prefs.ttsKnobs` / `MixerDraft` + `MixerCommit.commit()` / 两态 `trial()` 的 `VoiceProfile.build()` /
  native / JNI / `TTS_PARAMS_APPLIED` / 4B 设置 / prompt v2 / 两条制作路径 / 缓存分桶 / 续跑书签 / 前台服务 /
  V1 抽屉壳 / Screen 9 值 / Manifest / Gradle —— 全部一行未动。参数一个不删、一个不加、零新依赖。
- **验证**：本地 10 套 CI 脚本 + `CI=true` 复现全绿；`r49_verify.py` 正向 8 条 / 反向注入 10 例全拦下 /
  9 项参数逐字段对拍 / 三态枚举 / 13 文件逐字节守恒；CI **run 58 `completed success`（31 步）**；
  拆包 CRC 全过 / 7 个 arm64 .so / 权重 0 / 无 INTERNET / `1.0.0+58` / 35 个新符号全在 / 13 条旧形态零残留 /
  `TTS_PARAMS_APPLIED` 在；`--selftest` 反向注入正确拦下；直链 blob `{BLOB[:8]}…` 与本地 {SIZE:,} 字节逐字节一致。
- **验证脚本自身订正 5 处（不是放水）**：`r41a_verify` 旧断言要求两态按钮在吸顶 `CardBox` 内、
  `r42_verify` 断言吸顶大卡存在、`r45_common.canon` 的 AnchorBar 剥离 marker 在 R49 被替换掉、
  `r47_verify` 滑块直写 Prefs 注入按旧缩进、`r49_verify` 初版参数解析没处理跨行 / HEAD 落后一轮的守恒口径。
  首轮本地复跑真实抓到 5 处失败，逐一定位修完才推；CI 没当偶放。
- **明确不做（本轮）**：固定音色不做独立 `Screen`（要动枚举/chromeOf/抽屉/通知回跳）；
  不做老用户展开态一次性清空（R41 要求「记住上次展开」，新装/清数据默认全折叠）；
  v4 ② 设置页语言模型沉底、v4 ⑫⑬ 底部三层分区专图仍属既有差额，单列下一轮。
- ⚠️ **真机仍零证据**。队列：真机验 → V2 目录页+绿勾 → V2b 看书页 → V3 迷你条 → 设置页语言模型沉底 → 导入通道 → U2。
'''
with (ROOT / "ITERATION_LOG.md").open("a", encoding="utf-8") as f:
    f.write(log)
print("ITERATION_LOG.md 追加 R49")

card = f'''
<div class="card" style="margin-top:14px">
<div class="h">⑲ R49 · 调音台 v5 重设计（1.0.0+58 / run 58）</div>
<b>输入：</b>用户贴 <code>+57</code> 真机截图两张，问「设计的合理吗，重新设计」；R48 出 12 屏 v5 预览，本轮批「方案通过」后落代码。<br>
<b>真机坐实：</b>吸顶大卡吃掉首屏 40%、两紫色试听按钮压过灰色提交；锚点横溢把「固定音色」裁成「固」；
身份卡同一段 168 字渲染两遍；单个参数 ≈300px、9 项摊 3.5 屏；三键系统栏压住滑块。<br>
<b>落地（只改 <code>AnchorBar</code>/<code>ParamRow</code>/<code>MixerScreen</code>）：</b>48dp 细状态条 + 试听默认折叠区；
锚点 <code>FlowRow+FilterChip</code> 换行高亮、固定音色直达；身份卡摘要 + 展开全文/编辑 + 逐维 diff；
采样一行一项（中文名·滑块·小数字框·⟳）；三态徽标分区；底部 48dp。<br>
<b>底层对齐：</b>9 项全部 <code>ENGINE</code> 真到 <code>qt_tts_params</code>；⚠️ 上层能改但模型只看文字；🔒 结构项不画开关；
提交只看 <code>dirty</code>，<b>不依赖试听</b>。固定音色是另一份权重，当前只是「描述+seed+9 项全书不变」＝收敛漂移。<br>
<b>守恒：</b>真值源 / 唯一出口 / 两态试听拼装 / native / 4B / prompt v2 / 主链 / 抽屉壳 / Manifest / Gradle 一行未动，参数一个不删。<br>
<b>验证：</b>本地 10 套 + <code>CI=true</code> 全绿 · R49 正向 8 条 / 反向注入 10 例 / 9 项对拍 / 13 文件守恒 ·
CI <b>run 58 全绿（31 步）</b> · 拆包 CRC / 7 .so / 权重 0 / 无 INTERNET / <code>1.0.0+58</code> / 35 新符号 / 13 旧形态零残留 ·
直链 blob <code>{BLOB[:8]}…</code> 与本地 {SIZE:,} 字节逐字节一致。<br>
<b>真机仍零证据</b>；队列：真机验 → V2 目录页+绿勾 → V2b 看书页 → V3 迷你条 → 设置页沉底 → 导入通道 → U2。
</div>
'''
p = ROOT / "docs/听本_项目导图.html"
h = p.read_text(encoding="utf-8")
old_footer = "⑱ 由 R47 追加</footer>"
new_footer = "⑱ 由 R47 追加，⑲ 由 R49 追加</footer>"
if h.count(old_footer) != 1:
    sys.exit("导图 footer 标记不唯一")
h = h.replace(old_footer, card + "\n\n<footer>听本 · 项目导图 ｜ 按「导图 → 扒源码 → 加工 → 串 → 落软件 → 推 GitHub → 出 APK」顺序生成 ｜ ⑥ 由 R19 追加、R33/R37 订正，⑧ 由 R26 追加，⑨ 由 R28 追加，⑩ 由 R37 追加，⑪ 由 R38 追加，⑫ 由 R40 追加，⑬ 由 R41a 追加，⑭ 由 R41b 追加，⑮ 由 R42 追加，⑯ 由 R45 追加，⑰ 由 R46 追加，⑱ 由 R47 追加，⑲ 由 R49 追加</footer>", 1)
p.write_text(h, encoding="utf-8")
print("导图追加 ⑲")

report = f'''# R49 · 调音台 v5 落地报告（1.0.0+58 / run 58）

> 用户批「方案通过」后的单独一轮。先列 `docs/R49_调音台v5_联动表.md`（11 处联动），再动代码。
> **只改 `MainActivity.kt` 的 `AnchorBar` / `ParamRow` / `MixerScreen` 三块 + 对应自检脚本**，
> 零业务数据改动、零新依赖、零新页面、零 Screen 枚举改动。

## 一、v5 八条逐条落地

| 条 | 真机问题 | 这轮 |
|---|---|---|
| 1 | 顶部大卡占首屏 40%，紫色试听按钮压过灰色提交 | 48dp 细状态条常驻：‹ 返回 · 已生效 v4 · 未提交 N · 查看差异 · **提交变更**（唯一紫色实心）；试听移进默认折叠区 |
| 2 | 7 锚点横溢，「固定音色」裁成「固」 | `FlowRow + FilterChip` 自动换行 + `current` 高亮；7 项两行零横溢 |
| 3 | 身份卡同一段 168 字渲染两遍、无 diff | 已生效摘要两行 +「展开全文」；草稿编辑框默认可见 +「编辑描述」；`voiceFieldDiff()` 只列改动维度 |
| 4 | 单参数 ≈300px，滑块与精确值框上下各占满宽 | 一行一项：中文名 · 滑块 · 小数字框 · ⟳ · 展开箭头；**只有一个精确值框**（反向注入专门拦「画第二个」） |
| 5 | 三态混在一起，普通视图全是字段名 | 采样「✅ 可调」／12 维·表演·开关「上层」+「辅助设置 · 上层」标题／预设·角色「配置」／固定音色·对齐·资料「只读」；🔒 结构项一个开关都不画 |
| 6 | 固定音色说明在长页最底 + 锚点被裁，连问两轮不懂 | 锚点「固定音色」直达，`jumpTo` 先展开目标折叠区再滚；身份卡保留「固定音色是什么？ ›」 |
| 7 | 三键系统栏压住滑块 | 保留 40dp + 新增 8dp = 48dp 留白 |
| 8 | 满屏教操作注释 | 删注释腔；试听区只留一句「改完直接提交，不必先试听。」 |

## 二、底层逻辑没绕混（用户上轮三问）

- **提交不需要试听**：`commitAll()` 只看 `dirty`；差异弹层仍写「提交不依赖试听：改完直接点「提交变更」即可生效。」；
  试听不写 Prefs、不进 draft、不改 voiceStamp。两态各用整套配置（身份 + 风格 + 9 项采样），R41a 原样保留。
- **可调 / 不可调三态**：✅ 9 项 `ENGINE`（key/official/默认/范围逐项对拍 `ParamCatalog.SAMPLING`）；
  ⚠️ 上层能改但模型只看得到文字或 WAV 合并结果；🔒 `speed`/`pitch`、并发、codec、speaker 表、量化档物理不存在。
  **9 项参数一个不删、一个不加，`ParamSpec.kt` 逐字节未动。**
- **固定音色**：官方三模式是三份权重 —— VoiceDesign（当前，`instruct` 必填，无编号无音色锁）／
  CustomVoice（换权重后 `--speaker` 9 编号）／Base（3 秒参考音频）。已决定不换模型，后两条不是可选项，
  界面不画编号、不画上传。当前能固定的是「描述 + seed + 9 项采样全书不变」＝**收敛漂移，≠ 官方承诺跨段完全一致**。

## 三、守恒（R48 §6 原样）

未动：`Prefs.ttsKnobs` 真值源、`ParamSpec.kt`、`MixerDraft` + `MixerCommit.commit()`、
两态 `trial()` 的 `VoiceProfile.build()` 拼装、native / JNI / `TTS_PARAMS_APPLIED`、
4B 设置与 prompt v2、两条制作路径、缓存分桶、续跑书签、前台服务、V1 抽屉壳（`AppChrome.kt`）、
`Screen` 9 值、`AndroidManifest.xml`、Gradle 依赖。逐字节核对 13 个文件。

## 四、验证（首轮复跑抓到 5 处，逐一定位修完才推）

- 本地 10 套 CI 脚本 + `CI=true` 复现全绿；`r49_verify.py`：正向 8 条、反向注入 **10/10 全拦下**、
  9 项参数逐字段对拍、三态枚举、13 文件逐字节守恒。
- 反向注入覆盖：试听塞回大卡 / 锚点改回横向滚动 / 删当前区高亮 / 身份两遍全文 / 删逐维 diff /
  画第二个精确值框 / 提交绑回试听 / 删底部 8dp / 删固定音色锚点 / 界面直写生效态。
- **验证脚本自身订正 5 处（不是放水）**：`r41a_verify` 旧断言要求两态按钮在吸顶 `CardBox` 内、
  `r42_verify` 断言吸顶大卡存在、`r45_common.canon` 的 AnchorBar 剥离 marker 在 R49 被替换掉、
  `r47_verify` 滑块直写 Prefs 注入按旧缩进、`r49_verify` 初版参数解析没处理跨行 + HEAD 落后一轮的守恒口径。
  首轮本地复跑真实报 5 处 ERROR，修完才推 CI。
- CI **run 58 `completed success`（31 步，新增 R49 step）**。
- 拆包硬验：CRC 全过 / 7 个 arm64 .so / 权重 0 / 无 INTERNET / binary Manifest `1.0.0+58` /
  dex 35 个新符号全在 / 13 条旧形态零残留 / `.so` 里 `TTS_PARAMS_APPLIED` 在；`--selftest` 反向正确拦下。
- 直链 blob `{BLOB}` 与本地 APK **{SIZE:,} 字节逐字节一致**（`r41b_fetch_apk.py 58` 自校验）。

## 五、如实限定

1. ⚠️ **真机仍是零证据**：细状态条顺不顺手、375px 锚点是否真不溢出、一行一项是否好拖、
   固定音色那段读不读得懂，都没在 24117RK2CC 上点过。这轮交付的是「不绕、不装、参数对得上」，不是声音变好。
2. **老用户展开态不清空**：R41 明确要求「记住上次展开状态」，不擅自丢用户状态；新装/清数据默认全折叠。
   若装完仍看到旧展开态，手动收一次即可。
3. **固定音色没做成独立 Screen**：预览 v5 ⑩ 是独立页形态，但那要动 `Screen` 枚举、`chromeOf`、抽屉 7 项、
   通知回跳，违反「只改界面层」；本轮用「锚点直达 + 展开独立折叠区」达成同样的「找得到」。
4. **仍存差额**：v4 ② 设置页语言模型组沉底、v4 ⑫⑬ 底部三层分区专图 —— 前者动 `SettingsScreen` 守恒段，
   后者现为等效 48dp 留白；单列下一轮，不夹带。
5. 身份卡编辑框默认展开（v5 ① 首屏没画框）——刻意偏离预览一屏，避免 R38 找回来的输入框再次藏起来。

## 六、队列

真机验 → V2 目录页 + 按章绿勾 → V2b 看书页 → V3 迷你播放条 → 设置页语言模型沉底 → 沙盒标注导入通道 → U2。
各自单独一轮，不合并发版。
'''
(ROOT / "docs/R49_调音台v5_落地.md").write_text(report, encoding="utf-8")
print("落地报告写入")

# 交付包
files = [
    ("STATUS.md", "STATUS.md"),
    ("README.md", "README.md"),
    ("ITERATION_LOG.md", "ITERATION_LOG.md"),
    ("docs/听本_项目导图.html", "听本_项目导图.html"),
    ("docs/R48_调音台真机评审与v5重设计.md", "R48_调音台真机评审与v5重设计.md"),
    ("docs/R49_调音台v5_联动表.md", "R49_调音台v5_联动表.md"),
    ("docs/R49_调音台v5_落地.md", "R49_调音台v5_落地.md"),
    ("scripts/r49_verify.py", "r49_verify.py"),
    ("scripts/r49_apk_verify.py", "r49_apk_verify.py"),
]
zp = OUT / "听本_R49_项目文件.zip"
with zipfile.ZipFile(zp, "w", zipfile.ZIP_DEFLATED) as z:
    for src, arc in files:
        z.write(ROOT / src, arc)
print("交付包", zp, zp.stat().st_size, "字节")
