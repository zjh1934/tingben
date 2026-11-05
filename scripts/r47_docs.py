#!/usr/bin/env python3
"""R47 · 项目文件回填 + 落地报告 + 联动表 + 交付包。"""
import pathlib, zipfile, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
WS = pathlib.Path("/data/workspace")


def sub(path, pairs):
    p = ROOT / path
    s = p.read_text(encoding="utf-8")
    orig = s
    for i, (old, new) in enumerate(pairs, 1):
        n = s.count(old)
        if n != 1:
            sys.exit(f"{path} 第 {i} 条命中 {n} 次（应为 1）")
        s = s.replace(old, new, 1)
    p.write_text(s, encoding="utf-8")
    print(path, "更新", len(orig), "→", len(s))


STATUS_HEAD = """**2026-11-05 · R46 V1 抽屉壳已发（CI run 56）· ☰ 一整页抽屉 / 官方左滑手势 / 返回键优先 / 底栏 3 Tab 收进抽屉 / 调音台独立项**"""
STATUS_NEWHEAD = """**2026-11-05 · R47 抽屉收窄 + v4 预览 ⑥ 落地（CI run 57）· 抽屉 240dp / 采样滑块双模 / 参数人话名 / 官方字段收进 ⓘ**"""

QUEUE_OLD = """## 下轮队列（R46 之后）

1. **真机验**：`1.0.0+56` 装到 24117RK2CC —— ① ☰ 点开是不是一整页左侧抽屉；
   ② 从左边缘右滑能否开、左滑能否关（Android 16 手势导航会不会抢边缘）；
   ③ 抽屉开着按返回键是否先收抽屉；④ 7 个目的地能否切换且切完自动收起；
   ⑤ 任务条 / 内容是否不再压系统手势白条、状态栏下 ☰/＋ 不重叠；
   ⑥ 从抽屉进调音台，页内「‹ 返回」是否回到进入前的页。
2. **V2 目录页 + 按章绿勾**（R34/R35）：点书进 `Screen.CHAPTERS`、`AudioLibrary` 加 `chapters` 字段 +
   `index.json` 往返对拍（新/旧/整本/选章/重复产出），绿勾 ✓ + ▶
3. V2b 看书页（纯文本，不接任何播放/TTS）→ V3 全局迷你播放条（订阅 `PlayQueue.state`）
4. 标注工厂 JSON 导入通道（`docs/标注工厂_输出契约.md`，动 `Producer.start` 签名，单独一轮；端侧 4B 原样保留）
5. 采样 9 项「滑块 + 数字框」双模（v4 预览 ⑥ 画过，本轮未引 Slider）—— 用户要才插队
6. 调音台显示当前任务真实 `ProduceConfig` → 4B 设置沉页尾（不动逻辑）
7. U2 顺延项：其余 3 页下拉刷新、通知渠道 description 清理、设置页 1517 行「A/B」历史注释"""

QUEUE_NEW = """## 下轮队列（R47 之后）

1. **真机验**：`1.0.0+57` 装到 24117RK2CC —— ① 抽屉收窄到 240dp 后 7 个短标签是否顺手、☰/＋ 不压状态栏；
   ② 采样折叠区里拖滑块与改数字框是否同步、越界是否红字；③ 参数显示名是不是人话、ⓘ 里能查到官方字段；
   ④ 两个试听按钮仍一点即出声、提交仍不需要试听；⑤ 抽屉开着按返回先收抽屉、左滑手势不抢系统返回。
2. **V2 目录页 + 按章绿勾**（R34/R35）：点书进 `Screen.CHAPTERS`、`AudioLibrary` 加 `chapters` 字段 +
   `index.json` 往返对拍（新/旧/整本/选章/重复产出），绿勾 ✓ + ▶
3. V2b 看书页（纯文本，不接任何播放/TTS）→ V3 全局迷你播放条（订阅 `PlayQueue.state`）
4. 设置页「① 语言模型」整组沉到页尾 + 采样分组标题改人话（v4 ②/⑥ 剩余差额；动 SettingsScreen 守恒段，单独一轮）
5. 标注工厂 JSON 导入通道（`docs/标注工厂_输出契约.md`，动 `Producer.start` 签名，单独一轮；端侧 4B 原样保留）
6. 调音台显示当前任务真实 `ProduceConfig`；U2 顺延项（其余 3 页下拉刷新、通知渠道描述清理、历史注释）"""

sub("STATUS.md", [
    (STATUS_HEAD, STATUS_NEWHEAD),
    ("| 版本 | `1.0.0+56`（17,984,627 字节；直链 blob `533dcb1c109b8f008929db1c53c4bb7c9072736b` 与本地逐字节一致） |",
     "| 版本 | `1.0.0+57`（17,984,635 字节；直链 blob `eb7eee71965dbe5af589894feb2746be4f3ebb5a` 与本地逐字节一致） |"),
    ("| APK | 17,984,627 字节 / arm64-v8a / 固定 debug 签名 / 发布详情见 `docs/R46_V1抽屉壳_落地.md` |",
     "| APK | 17,984,635 字节 / arm64-v8a / 固定 debug 签名 / 发布详情见 `docs/R47_抽屉收窄与预览落地_落地.md` |"),
    ("| CI | **run 56 `completed success`**（含新增 R46 验证 step；native 缓存命中） |",
     "| CI | **run 57 `completed success`**（30 步，含新增 R47 验证 step；native 缓存命中） |"),
    ("- 独立「**调音台**」页已落地（R31）；R33 起 ☰ 菜单有一级入口，不再只能从设置页翻两层。",
     "- 独立「**调音台**」页已落地（R31）；R46 起 ☰ / 左滑打开一整页左侧抽屉，7 个目的地收进抽屉一列，调音台是独立项；R47 抽屉收窄到 240dp。"),
    ("- 导航（U1/R33 后）：底栏 **3 Tab**（书架 / 任务 / 我的音频）+ 左上角 ☰ 一级菜单（设置 / 调音台 / 模型 / 角色音色）；制作准备页四组折叠预设（本次级、随书签落盘）",
     "- 导航（R46 后）：**无底栏**，☰ / 从左边缘右滑开一整页左侧抽屉，7 个目的地一列（书架 / 任务 / 我的音频 ／ 设置 / 调音台 / 模型 / 角色音色）；R47 抽屉宽 240dp；制作准备页四组折叠预设（本次级、随书签落盘）"),
    ("- **R46 V1 抽屉壳**：", "- **R47 抽屉收窄 + v4 ⑥ 落地**：抽屉 320dp → `requiredWidth(240.dp)`、条目 13sp 紧凑；采样 9 项补「滑块 + 精确值框」双模（共值、范围取 `ParamSpec` 唯一来源）；9 个显示名改人话、官方字段收进 ⓘ。详见 `docs/R47_抽屉收窄与预览落地_落地.md`\n- **R46 V1 抽屉壳**："),
    (QUEUE_OLD, QUEUE_NEW),
])

sub("README.md", [
    ("- 固定地址**永远是最新版**，版本号跟 CI run 号（如 `1.0.0+56`），当前版本见 `STATUS.md` 头部",
     "- 固定地址**永远是最新版**，版本号跟 CI run 号（如 `1.0.0+57`），当前版本见 `STATUS.md` 头部"),
    ("- **调音台（R31 建，R33 提一级，R36/R38 重设计，R40 统一提交，R41a 试听改版，R41 P1 信息架构，R42 P2 文案/响应式/视觉，R45 v4 分层）**",
     "- **调音台（R31 建，R33 提一级，R36/R38 重设计，R40 统一提交，R41a 试听改版，R41 P1 信息架构，R42 P2 文案/响应式/视觉，R45 v4 分层，R47 补滑块双模与人话名）**"),
    ("  **R42 P2**：开发者信息收进默认折叠的「高级」抽屉；12 维与表演层改 `FlowRow + FilterChip` 自动换行（375px 不碎字）；说明降级 ⓘ 点击展开；分区改细分割线；时机提示全页统一。",
     """  **R42 P2**：开发者信息收进默认折叠的「高级」抽屉；12 维与表演层改 `FlowRow + FilterChip` 自动换行（375px 不碎字）；说明降级 ⓘ 点击展开；分区改细分割线；时机提示全页统一。
  **R47**：v4 预览 ⑥ 真落地 —— 9 项采样每行「滑块 + 精确值框」共用一个值（滑块范围取 `ParamSpec.min/max` 唯一来源，seed 无上界时取 2000）；
  9 个显示名改人话（主·随机度 / 副·候选范围 / 采样种子…），英文官方字段收进 ⓘ 参数说明，不新增第二份真值源。
  抽屉宽 320dp → 240dp、条目字号与 padding 同步收紧。详见 `docs/R47_抽屉收窄与预览落地_落地.md`。"""),
    ("  AppChrome.kt             U1 抽出的全局壳：顶栏 ☰ / ＋、V1 左侧抽屉一列（7 个目的地）、零文案下拉刷新、chromeOf 映射、状态栏安全区",
     "  AppChrome.kt             U1 抽出的全局壳：顶栏 ☰ / ＋、V1 左侧抽屉一列（7 个目的地，R47 收窄到 240dp）、零文案下拉刷新、chromeOf 映射、状态栏安全区"),
])

log_add = """

## R47 · 抽屉收窄 + v4 预览 ⑥ 落地（run 57 / 1.0.0+57）

- **用户两条批评**：抽屉 7 个三五字标签占满宽；上几轮渲染的调音台预览「一点都不落地」。
- **实查坐实**：`AppChrome.kt` 用无宽度 `ModalDrawerSheet` → M3 `DrawerDefaults.MaximumDrawerWidth` 默认 320dp；
  `docs/R45_调音台v4_落地.md` 自己白纸黑字写「v4 预览 ⑥ 画过滑块+小数字框双模，本轮未引 Slider」，
  且 `ParamSpec.SAMPLING` 的 9 个 label 仍是 `主 temperature / 副 top_k` 等英文 → 预览与装上的包确实不是一回事。
- **落地**：① 抽屉 `requiredWidth(240.dp)` + 条目 13sp / padding 10·0 / 分隔线 14·6；
  ② `ParamRow` 补 `Slider`，与精确值框共用 `onInput`，`valueRange` 取 `ParamSpec` 唯一范围来源，整数/浮点分别格式化，`coerceIn` 防越界崩；
  ③ 9 个 label 改人话，官方字段名进 ⓘ `HelpNote`。不改真值源、不新增 Gradle 依赖、不改 4B / prompt v2 / native。
- **仍存差额（如实）**：v4 ② 设置页语言模型组沉底、v4 ⑥ 三组标题改人话、⑫⑬ 底部三层分区专图 —— 后两项现有等价形态（40dp 留白 + 全局 navigationBarsPadding），
  前一项动 `SettingsScreen` 守恒段，单列下一轮，不夹带。
- **不手搓**：抽屉宽度覆写用官方 `requiredWidth` 形态（社区 250dp / M2 上限 280dp 之间取值）；滑块用项目已有 M3 `Slider`（BOM 2024.12.01，音频页进度条早就在用）。
- **验证**：本地 9 套脚本 + `CI=true` 复现全绿；R47 正向 22 符号 / 反向注入 6 例全拦下 / ParamRow 逐行对拍 / ParamSpec 逐字段对拍 / 11 文件守恒；
  CI **run 57 `completed success`**（30 步，新增 R47 step）；拆包 4 套 PASS（CRC / 7 .so / 权重 0 / 无 INTERNET / `1.0.0+57` / 新符号全在 / 旧壳零残留 / `TTS_PARAMS_APPLIED` 在）；
  直链 blob `eb7eee71…` 与本地 17,984,635 字节逐字节一致。
- **验证脚本自身订正 6 处（不是放水）**：`check_structure` 旧英文 label、`r42_verify` 守恒清单移除 ParamSpec（改由 R47 字段对拍）、
  `r38_apk_verify` 8 个 R38 旧符号换成现行等价符号、`r46_verify` 抽屉形态字面量、`r45_verify` KEEP 清单、`r45_common.canon` 剔除 ParamRow 段。
- ⚠️ **真机仍零证据**。队列：真机验 → V2 目录页+绿勾 → V2b 看书页 → V3 迷你条 → 设置页语言模型沉底 → 导入通道 → U2。
"""
with (ROOT / "ITERATION_LOG.md").open("a", encoding="utf-8") as f:
    f.write(log_add)
print("ITERATION_LOG.md 追加 R47")

map_add = """
<div class="card" style="margin-top:14px">
<div class="h">⑱ R47 · 抽屉收窄 + v4 预览 ⑥ 落地（1.0.0+57 / run 57）</div>
<b>输入：</b>用户「抽屉选项卡平均三五个字做那么宽干嘛」「上几轮渲染的调音台一点都不落地」。<br>
<b>实查：</b><code>ModalDrawerSheet</code> 无宽度 → M3 默认 320dp；R45 报告自己承认 v4 ⑥ 的滑块双模没做，9 个参数名仍是英文。<br>
<b>落地：</b>抽屉 <code>requiredWidth(240.dp)</code> + 条目 13sp 紧凑；<code>ParamRow</code> 补滑块与精确值框共值（范围取 <code>ParamSpec</code> 唯一来源）；
9 个显示名改人话、官方字段进 ⓘ。真值源 / native / 4B / prompt v2 / Gradle 一行未动。<br>
<b>仍存差额：</b>设置页语言模型沉底、分组标题人话、底部三层分区专图（现有等价留白），单列下一轮。<br>
<b>验证：</b>本地 9 套 + <code>CI=true</code> 全绿 · 反向注入 6/6 · ParamRow/ParamSpec 对拍 · CI <b>run 57 全绿（30 步）</b> ·
拆包 4 套 PASS · 直链 blob <code>eb7eee71…</code> 与本地 17,984,635 字节逐字节一致。<br>
<b>真机仍零证据</b>；队列：真机验 → V2 目录页+绿勾 → V2b 看书页 → V3 迷你条 → 设置页沉底 → 导入通道 → U2。
</div>

"""
p = ROOT / "docs/听本_项目导图.html"
s = p.read_text(encoding="utf-8")
s = s.replace("<footer>听本 · 项目导图", map_add + "<footer>听本 · 项目导图")
s = s.replace("⑰ 由 R46 追加", "⑰ 由 R46 追加，⑱ 由 R47 追加")
p.write_text(s, encoding="utf-8")
print("导图追加 ⑱")

report = """# R47 · 抽屉收窄与 v4 预览 ⑥ 落地报告（1.0.0+57 / run 57）

**输入**：用户本轮两条批评 ——「抽屉页选项卡平均三五个字，做那么宽干嘛」「上几轮渲染出来的调音台一点都不落地」。
**范围**：只改界面层与一份显示名元数据。真值源、引擎、主链、Gradle、4B 设置一行未动。

---

## 一、不是排版小毛病，是两处实锤

| 批评 | 实查证据 |
|---|---|
| 抽屉太宽 | `AppChrome.kt` 用 `ModalDrawerSheet(drawerContainerColor = surface)`，**没给宽度** → M3 `DrawerDefaults.MaximumDrawerWidth` 默认 **320dp**；官方 M2 规范上限 280dp，社区常用 `requiredWidth(250.dp)` 覆写。7 个「书架 / 任务 / 我的音频 / 设置 / 调音台 / 模型 / 角色音色」全是三五字，320dp 确实空 |
| 预览不落地 | `docs/R45_调音台v4_落地.md` 白纸黑字：「v4 预览 ⑥ 画过『滑块 + 小数字框』双模，本轮**未引 Slider**」；且 `ParamSpec.SAMPLING` 9 个 label 仍是 `主 temperature` / `副 top_k` / `seed（随机种子）` 等英文，而 v4 ⑥ 画的是「主·随机度 / 副·候选范围 / 采样种子」 |

R45 当时不引滑块的理由是「怕 9 个滑块把折叠区撑成长清单」；但 R45 同时又把**默认展开态收成全折叠**，这条理由已经不成立了 —— 折叠区里放滑块不会污染首屏。这是本轮把它补上的直接依据。

## 二、落地内容

| 项 | 落点 |
|---|---|
| 抽屉收窄 | `ModalDrawerSheet(modifier = Modifier.requiredWidth(240.dp), ...)`；条目 14sp→13sp、padding 12·2→10·0、分隔线 20·10→14·6、纵向 18→12。左滑手势、返回键优先、7 个目的地、调音台独立项全不动 |
| 滑块双模 | `ParamRow` 新增 `Slider`：`value = (input ?: cur).coerceIn(lo, hi)`，`valueRange = spec.min..spec.max`（seed 无上界取 2000）；`onValueChange` 格式化后走**同一个 `onInput`**，写草稿、不碰 Prefs；整数/浮点分别格式化；`coerceIn` 防止精确框填超范围值时滑块崩 |
| 人话参数名 | `ParamSpec.SAMPLING` 9 个 `label` 改中文（唯一来源不变）；英文官方字段 `spec.official` 进 ⓘ `HelpNote("参数说明")`，普通视图不显示路径 |
| 折叠不变 | 采样区仍默认折叠，首屏身份卡 + 吸顶状态条不变；R40 唯一提交出口、R41a 两态试听、R45 默认全折叠全原样 |

**不手搓**：`requiredWidth` 是官方 M3 抽屉覆写形态（社区 250dp / M2 上限 280dp 之间取 240dp）；`Slider` 是项目 BOM 已带、音频页进度条早在用的 M3 组件，零新增依赖。

## 三、v4 预览差额表（画了 vs 落了）

| v4 屏 | 画过的 | 现状 |
|---|---|---|
| ① 首屏 | 一屏装完、固定音色行、提交/试听分离 | ✅ 已落（R45/R41a） |
| ② 设置页 | 语言模型组沉到下方 | ⚠️ **未落**：动 `SettingsScreen` 守恒段，单列下一轮 |
| ③/④ 固定音色 | 三模式对照、seed 两档、结构不可调单列 | ✅ R45 后程折叠区 + seed 换一个 |
| ⑤ 12 维 | 每维小自定义框、实时预览 | ✅ R45 |
| ⑥ 采样 9 项 | 滑块 + 数字框双模、每行 ⟳、人话分组标题 | ✅ **本轮补滑块 + 人话参数名**；分组标题仍是「主采样（5 项）」等，登记下一轮 |
| ⑦~⑪ | 表演层 / 角色 / 预设 / 差异 / 更多设置逐块摊开 | ✅ R40~R42 已落，默认折叠 |
| ⑫⑬ 底部安全区 | 三层分区专图 | ⚠️ 现为 40dp 末尾留白 + 全局 `navigationBarsPadding()`，效果等价但未画成分区 |
| ⑭ 全展开长图 | 10 区块同时摊开 | ✅ 能力都在，只是默认折叠 |

**这轮解决的是「画了没落」里最实的那一项，不是「全部 14 屏 1:1 复刻」**；剩余三项要么动守恒段、要么只是示意画法，如实登记，不假装做完。

## 四、联动与守恒

10 处联动先列表再动代码（见 `docs/R47_抽屉收窄与预览落地_联动表.md`）。守恒：

- `MixerDraft` / `Mixer` / `MixerCommit` / `Pipeline` / `VoiceProfile` / `PlayQueue` / `NativeEngine` / `ProduceService` / native cpp / prompt v2 / Manifest / Gradle **逐字节比 HEAD 一致**
- `ParamSpec` 只有 9 个 `label` 变化：`key / official / def / hint / min / max / options / kind` 逐字段对拍全等
- `ParamRow` 除新增滑块块与 HelpNote 加字段外，逐行与 HEAD 一致
- 唯一提交出口、两态试听、4B 设置、缓存分桶、前台服务全未动

## 五、验证（不是只看绿灯）

- 本地 9 套脚本全绿，`CI=true` 复现全绿（`check_structure` / `r38_param_audit` / `r40` / `r41a` / `r41` / `r42` / `r45` / `r46` / `r47`）。
- **R47 验证**：正向 22 符号、反向注入 **6/6 全拦下**（抽屉宽度回退 320 / 恢复宽松条目 / 删滑块块 / 滑块直写 Prefs / 滑块改用快捷值 / 参数名回退英文）、ParamRow 逐行对拍、ParamSpec 逐字段对拍、11 文件守恒。
- CI **run 57 `completed success`**，30 步（新增 R47 验证 step 挂第 10 步），native 缓存命中。
- 拆包硬验 4 套 PASS：CRC 全过 / 7 个 arm64 .so / 权重 0 / 无 INTERNET / binary Manifest `1.0.0+57` / 抽屉与人话参数名新符号全在 / 旧壳零残留 / `.so` 里 `TTS_PARAMS_APPLIED` 在。
- 直链 blob `eb7eee71965dbe5af589894feb2746be4f3ebb5a` 与本地 APK **17,984,635 字节逐字节一致**（按 run 号取包，防 R33 直链滞留旧版）。

### 验证脚本自身订正 6 处（不是放水）

`check_structure` 查旧英文 label、`r42_verify` 守恒清单里 ParamSpec、`r38_apk_verify` 8 个 R38 旧符号（保存音色身份 / A/B 试听等在 R40~R47 被刻意删除）、`r46_verify` 抽屉形态字面量、`r45_verify` KEEP 清单、`r45_common.canon` 剔除 ParamRow 允许改的段落。**全部换成等价新形态或改由更严的字段对拍负责**，反向注入仍全拦下。

## 六、真机与队列

⚠️ **真机仍是零证据**：240dp 在 24117RK2CC 上顺不顺手、滑块拖动与数字框是否同步、人话名读不读得懂、两个试听按钮是否仍一点即出声，都要装一次才算数。
本轮交付的是「抽屉不空、预览里画过的滑块真进了包、参数名是人话」，**不是声音变好**。

队列（各自单独发版）：**真机验 → V2 目录页 + 按章绿勾 → V2b 看书页 → V3 全局迷你播放条 → 设置页语言模型沉底 + 分组标题人话 → 标注工厂导入通道 → U2**。
"""
(ROOT / "docs/R47_抽屉收窄与预览落地_落地.md").write_text(report, encoding="utf-8")

link = """# R47 · 联动表与验收映射（落代码前）

## 一、10 处联动

| # | 改动 | 连带 | 处理 |
|---|---|---|---|
| 1 | `ModalDrawerSheet` 宽度 | `r46_verify.CHROME_POS` 字面量 | 同步断言，改查 `requiredWidth(240.dp)` |
| 2 | DrawerRow 字号/padding | 抽屉 7 行高度、分隔线 | 一起收紧，反向注入能拦回退 |
| 3 | `ParamRow` 加 Slider | `r41/r42/r45/r46` 的 App() 之外业务守恒 | `r45_common.canon` 剔除 ParamRow 段，另由 R47 逐行对拍 |
| 4 | 滑块 `onInput` | `paramDrafts` / `draft.knobs` / `paramErr` | 复用既有回调，不新增写入口；断言 ParamRow 内无 `Prefs.set*` |
| 5 | 滑块 `valueRange` | `ParamSpec.min/max` 唯一来源 | 不硬编码范围；seed 无上界取 2000 并写注释 |
| 6 | 9 个 label | `r38_apk_verify` / `check_structure` 旧字面量 | 同步成现行人话名 |
| 7 | ParamSpec 改 | `r45/r42` 守恒清单 | 移除逐字节守恒，改由 R47 逐字段对拍（key/official/def/min/max 必须不变） |
| 8 | HelpNote 加官方字段 | `r42_verify` 只查前缀 `HelpNote("参数说明",` | 前缀不变，断言不假红 |
| 9 | CI | 新增 R47 step | 挂在 R46 之后、JDK 之前 |
| 10 | 拆包 | `r46_apk_verify.NEW` 与 `r38_apk_verify.NEW` | 加人话参数名；历史旧符号换现行等价 |

## 二、验收映射

| 验收点 | 断言 |
|---|---|
| 抽屉不再占满屏 | `requiredWidth(240.dp)` 在 `AppChrome.kt`；反向注入回退 320 被拦 |
| 条目不再松散 | `fontSize = 13.sp` / `padding(10.dp, 0.dp)`；恢复 12·2 被拦 |
| 滑块与数字框是同一个值 | `onInput(s)` 在 ParamRow 内；改成 `onPick(s)` 被拦 |
| 滑块不绕过草稿直写生效态 | ParamRow 内无 `Prefs.set*`；注入直写被拦 |
| 参数名是人话、字段名仍可查 | 9 个新人话名在 `ParamSpec`、旧英文名零残留、`字段 ${spec.official}` 在 HelpNote |
| 没顺手改真值 | ParamSpec 逐字段对拍 + ParamRow 逐行对拍 + 11 文件逐字节守恒 |
| 旧能力没被改坏 | 4 套拆包脚本全绿、CI 30 步全绿、直链逐字节一致 |

## 三、明确不做

- 设置页语言模型组沉底（动 `SettingsScreen` 守恒段，单独一轮）
- 采样三组标题改人话（动 `MixerScreen` 守恒段，同一轮）
- 底部三层分区专图（现有 40dp 留白 + 全局 `navigationBarsPadding()` 已等效）
- 4B 设置、prompt v2、两条制作路径、native/JNI、缓存分桶、前台服务、Gradle 依赖
"""
(ROOT / "docs/R47_抽屉收窄与预览落地_联动表.md").write_text(link, encoding="utf-8")
print("落地报告与联动表已写")

out = WS / "听本_R47_项目文件.zip"
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for rel in ["STATUS.md", "README.md", "ITERATION_LOG.md",
                "docs/听本_项目导图.html",
                "docs/R47_抽屉收窄与预览落地_落地.md",
                "docs/R47_抽屉收窄与预览落地_联动表.md",
                "scripts/r47_apply.py", "scripts/r47_verify.py", "scripts/r47_docs.py"]:
        z.write(ROOT / rel, rel)
print("交付包:", out, out.stat().st_size, "字节")
