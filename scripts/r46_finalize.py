#!/usr/bin/env python3
"""R46 收尾：落地报告 / 项目文件回填 / 交付包。参数由命令行给，不硬编码。

用法: python3 scripts/r46_finalize.py <RUN> <SIZE> <BLOB_SHA>
例:   python3 scripts/r46_finalize.py 56 17984619 e5a126d4...
"""
import pathlib, sys, zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
WS = pathlib.Path("/data/workspace")
RUN, SIZE, BLOB = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3]
VER = "1.0.0+%d" % RUN
SIZE_S = "{:,}".format(SIZE)

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

report = f"""# R46 · V1 抽屉壳落地报告（{VER} / run {RUN}）

**输入**：R34/R35 定案后连续顺延多轮的下一轮第一件；用户本轮「继续做」＝按节奏推进，不再问方向。
**范围**：只做抽屉壳。**不做**章节页 / 按章绿勾 / 迷你播放条 / 看书页 / 沙盒导入 / 4B 设置沉页尾。

---

## 一、落地内容（对着联动表逐条）

| 项 | 落点 |
|---|---|
| 一整页抽屉 | 根布局包进 `ModalNavigationDrawer`，`drawerContent` = `AppDrawerSheet`（`ModalDrawerSheet` + `NavigationDrawerItem`），不再是 `DropdownMenu` 浮层 |
| ☰ | `AppTopBar.onMenu: () -> Unit` → `scope.launch {{ drawerState.open() }}`；原 `(Int)->Unit` 菜单回调整份删除 |
| 左滑/右滑 | 官方 `gesturesEnabled = true`，从左边缘右滑开、左滑关；**没有自写 `detectHorizontalDragGestures`** |
| 返回键优先 | 新增 `BackHandler(enabled = drawerState.isOpen)` 写在原条**之后**（后注册优先），抽屉开着按返回先收抽屉 |
| 底栏 3 Tab | `AppBottomBar` / `AppTab` / `TOP_TABS` / `MENU_ITEMS` 全删；7 个目的地收进抽屉一列：书架 / 任务 / 我的音频 ／ 设置 / 调音台 / 模型 / 角色音色 |
| 调音台独立项 | `NAV_MIXER` 独立一项，`selected` 由 `drawerSelected` 给出；`chrome.activeTab` 复用为抽屉顶层高亮，语义不变 |
| 二级页返回 | 新增 `drawerReturn`：从抽屉进二级页记来源；调音台 / 角色页「‹ 返回」回来源页；设置页 / 制作页进二级页时显式设成对应页，不再写死回 SETTINGS |
| 刷新副作用 | 原菜单 `if (idx==0) refresh++`、原底栏 `if (idx==TAB_AUDIO) refresh++` 迁到抽屉 `onNavigate`，语义不变 |
| 安全区 | 底栏删掉后，根 `Column` 补 `navigationBarsPadding()`；`statusBarsPadding()` 保留，R33 的状态栏重叠不复发 |

**不手搓**：抽屉形状抄本地 `reference/OfflineLLM/.../ChatScreen.kt:99,151-160`（Apache-2.0）；
条目与抽屉面板用官方 M3 `ModalNavigationDrawer / ModalDrawerSheet / NavigationDrawerItem / HorizontalDivider`，
BOM `2024.12.01` 已带，**零新增 Gradle 依赖**；图标继续文字字形，不引 `material-icons-extended`。

## 二、联动与守恒（R34 原表 9 条）

9 处联动先列表再动第一行代码，见 `docs/R46_V1抽屉壳_联动表.md`。守恒：

- `Screen` **9 值**、`when(screen)` **9 分支**、`chromeOf` **9 映射**逐项与 HEAD 一致
- `refresh` 计数器、`modelsFrom`、原 `BackHandler(screen != LIBRARY)`、外部「打开方式 / 分享」落地、通知回跳
- `Producer.start` 签名、`bucket()`、缓存分桶、任务书签、`ProduceService`、前台服务 / 唤醒锁
- native / prompt v2 / `Prefs` / 4B 设置 / 调音台全部数据层 / `PlayQueue` / `AudioLibrary`
- Manifest 权限、Gradle 依赖、`RefreshStrip` 与 `acc > 55f`、任务条上扒/下扒手势

本轮唯一改的业务文件是 `MainActivity.kt` 的 App() 壳与 `AppChrome.kt`；App() 之外的业务代码逐行比 HEAD 一致。

## 三、验证（不是只看绿灯）

- 本地 8 套脚本全绿，且用 `CI=true` 复现一遍全绿：`check_structure` / `r38_param_audit` /
  `r40` / `r41a` / `r41` / `r42` / `r45` / `r46`。
- **R46 验证**：正向 26 符号（含定义/调用形态，不只查裸名字）、旧壳 6 项零残留、
  反向注入 **9/9 全拦下**（删抽屉返回键 / 关官方手势 / 恢复底栏 / ☰ 回退浮层 /
  去掉底部安全区 / 调音台返回写死设置页 / 调音台不再是独立项 / 删一个分发分支 /
  抽屉 BackHandler 排到原条之前）、守恒 12 文件 + App() 之外业务代码。
- CI **run {RUN} `completed success`**，新增 R46 验证 step 挂在第 10 步；native 缓存命中。
- 拆包硬验：CRC 全过 / 7 个 arm64 .so / 权重 0 / 无 INTERNET / binary Manifest `{VER}` /
  抽屉新符号全在 / 旧壳零残留 / `.so` 里 `TTS_PARAMS_APPLIED` 仍在。
- 直链 blob `{BLOB[:8]}…` 与本地 APK **{SIZE_S} 字节逐字节一致**（每轮交付前核直链，防 R33 滞留旧版）。

### 验证脚本自身的三处订正（不是放水）

1. `check_structure.py` 里 U1/R33/R36 的旧断言还在查 `AppBottomBar` / `DropdownMenu(` /
   `MENU_ITEMS` / `onMenu = {{ idx ->` / `TOP_TABS`，V1 全删后必然假红；同步成新形态，并把
   `when(screen)` 分发块定位从裸 `when (screen) {{` 换成带 `Box(Modifier.weight(1f))` 的锚点
   —— App() 里新增的 `drawerSelected` / `from` 两个 when 曾让分支计数误报 7。
2. `r45_common.canon()` 原本把 App() 整段也算进「业务代码守恒」，V1 允许改 App() 壳；
   改成剔除 `private fun App(` → `private fun queryName(` 之间的部分，其余仍逐行守恒。
   `AppChrome.kt` 从 4 套脚本的逐字节守恒清单移除，改由 `r46_verify.py` 做形态与旧壳断言。
3. `r45_verify.py` 的 Prefs 守恒在 HEAD 里找「/** 默认只展开」，但 HEAD 已是 R45 之后的版本，
   `find` 返回 -1 把切片切成空串，基线恒 FAIL —— CI 跳过守恒段才一直没暴露；改成同一 marker 后 PASS。

## 四、真机与队列

⚠️ **真机仍是零证据**：☰ 点开是不是一整页、从左边缘右滑在 Android 16 手势导航下会不会与系统返回抢边缘、
抽屉开着按返回是否先收抽屉、任务条会不会压系统手势白条，都要在 24117RK2CC 上点一次。
本轮交付的是「导航壳换对、旧壳拆干净、底部不压白条」，**不是声音变好，也不是章节页已经能用**。

队列（各自单独发版）：
**真机验 → V2 目录页 + 按章绿勾 → V2b 看书页 → V3 全局迷你播放条 → 标注工厂导入通道 → U2**。
采样 Slider 双模、4B 设置沉页尾仍按用户「要才插队」处理。
"""
(ROOT / "docs/R46_V1抽屉壳_落地.md").write_text(report, encoding="utf-8")
print("写落地报告")

rep("STATUS.md", [
    ("""**2026-11-05 · R45 调音台 v4 已发（CI run 55）· 默认全折叠 / 固定音色官方说明 / 参数三态对齐 / seed 换一个 / 12 维自定义框 / 设置页入口**""",
     f"""**2026-11-05 · R46 V1 抽屉壳已发（CI run {RUN}）· ☰ 一整页抽屉 / 官方左滑手势 / 返回键优先 / 底栏 3 Tab 收进抽屉 / 调音台独立项**"""),
    ("""| 版本 | `1.0.0+55`（17,984,619 字节；直链 blob `e5a126d4d66a62f4933d671dc5c81847b5f9061e` 与本地逐字节一致） |
| APK | 17,984,619 字节 / arm64-v8a / 固定 debug 签名 / 发布详情见 `docs/R45_调音台v4_落地.md` |""",
     f"""| 版本 | `{VER}`（{SIZE_S} 字节；直链 blob `{BLOB}` 与本地逐字节一致） |
| APK | {SIZE_S} 字节 / arm64-v8a / 固定 debug 签名 / 发布详情见 `docs/R46_V1抽屉壳_落地.md` |"""),
    ("""| CI | **run 55 `completed success`**（28 步，含新增 R45 验证 step；native 缓存命中）。run 54 因结构自检旧标题断言失败，已修 |""",
     f"""| CI | **run {RUN} `completed success`**（含新增 R46 验证 step；native 缓存命中） |"""),
    ("""- **R45 调音台 v4**：默认全折叠；新增「固定音色 · 官方模式说明」与「哪些能改 · 和模型一一对齐」两个后程只读折叠区（不可调项不画开关）；seed「⟳ 换一个」写草稿；12 维每维小自定义框；设置页加固定音色入口；提交与试听解耦写进差异弹层。详见 `docs/R45_调音台v4_落地.md`""",
     """- **R45 调音台 v4**：默认全折叠；新增「固定音色 · 官方模式说明」与「哪些能改 · 和模型一一对齐」两个后程只读折叠区（不可调项不画开关）；seed「⟳ 换一个」写草稿；12 维每维小自定义框；设置页加固定音色入口；提交与试听解耦写进差异弹层。详见 `docs/R45_调音台v4_落地.md`
- **R46 V1 抽屉壳**：☰ / 从左边缘右滑都开 `ModalNavigationDrawer`（抄本地 OfflineLLM `ChatScreen.kt`，Apache-2.0）；返回键优先关抽屉；底栏 3 Tab 收进抽屉一列（7 个目的地，调音台独立项）；二级页「‹ 返回」回进入前的页；底栏删掉后根 Column 补 `navigationBarsPadding()`。章节页 / 绿勾 / 迷你条 / 看书页本轮未做。详见 `docs/R46_V1抽屉壳_落地.md`"""),
    ("""## 下轮队列（R45 之后）

1. **真机验**：`1.0.0+55` 装到 24117RK2CC —— ① 进调音台是不是默认全折叠、首屏身份卡还在；
   ② 设置页「固定音色」点进去是否直接展开官方说明；③ 固定音色三模式那段读不读得懂；
   ④ seed「⟳ 换一个」是否写进待提交且能试听；⑤ 12 维自定义框在 375px 会不会挤；
   ⑥ 差异弹层「提交不依赖试听」是否清楚；⑦ 两个试听按钮点一下是否仍自动出声。
2. **V1 抽屉壳**（R34/R35 定案）：左滑抽屉、底栏 3 Tab 收敛进抽屉、调音台独立项 —— 连续顺延，仍单独一轮
3. V2 目录页 + 按章绿勾 → V2b 看书页 → V3 全局迷你播放条 → 标注工厂导入通道
4. 标注工厂 JSON 导入通道（`docs/标注工厂_输出契约.md`，动 `Producer.start` 签名，单独一轮；端侧 4B 原样保留）
5. 采样 9 项「滑块 + 数字框」双模（v4 预览 ⑥ 画过，本轮未引 Slider）—— 用户要才插队
6. 调音台显示当前任务真实 `ProduceConfig` → 4B 设置沉页尾（不动逻辑）
7. U2 顺延项：其余 3 页下拉刷新、通知渠道 description 清理、设置页 1517 行「A/B」历史注释""",
     f"""## 下轮队列（R46 之后）

1. **真机验**：`{VER}` 装到 24117RK2CC —— ① ☰ 点开是不是一整页左侧抽屉；
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
7. U2 顺延项：其余 3 页下拉刷新、通知渠道 description 清理、设置页 1517 行「A/B」历史注释"""),
])

rep("README.md", [
    ("""- 固定地址**永远是最新版**，版本号跟 CI run 号（如 `1.0.0+55`），当前版本见 `STATUS.md` 头部""",
     f"""- 固定地址**永远是最新版**，版本号跟 CI run 号（如 `{VER}`），当前版本见 `STATUS.md` 头部"""),
    ("""1. 装包，左上角 ☰ →「模型」→ 导入三个 GGUF（约 5GB，App 自己不联网）""",
     """1. 装包，左上角 ☰（或从左边缘右滑）打开抽屉 →「模型」→ 导入三个 GGUF（约 5GB，App 自己不联网）"""),
    ("""  AppChrome.kt             U1 抽出的全局壳：顶栏 ☰ 菜单 / ＋、底栏 3 Tab、零文案下拉刷新、chromeOf 映射、状态栏安全区""",
     """  AppChrome.kt             U1 抽出的全局壳：顶栏 ☰ / ＋、V1 左侧抽屉一列（7 个目的地）、零文案下拉刷新、chromeOf 映射、状态栏安全区"""),
])

log = ROOT / "ITERATION_LOG.md"
log.write_text(log.read_text(encoding="utf-8") + f"""

## R46 · V1 抽屉壳落地（run {RUN} / {VER}）

- **按队列推进**：R34/R35 定案的 V1 连续顺延多轮后兑现，只做抽屉壳，没夹带章节页 / 绿勾 / 迷你条 / 看书页。
- **不手搓**：`ModalNavigationDrawer` 形状抄本地 `reference/OfflineLLM/ChatScreen.kt:99,151-160`（Apache-2.0）；
  条目用官方 M3 `ModalDrawerSheet / NavigationDrawerItem / HorizontalDivider`，零新增 Gradle 依赖。
- **落地**：☰ 改开抽屉、`gesturesEnabled=true` 官方手势、新增 `BackHandler(drawerState.isOpen)` 且排在原条之后（LIFO 优先）；
  底栏 `AppBottomBar` / `AppTab` / `TOP_TABS` / `MENU_ITEMS` / `DropdownMenu` 全删，7 个目的地收进抽屉一列，调音台独立项；
  `drawerReturn` 让二级页「‹ 返回」回进入前的页；刷新副作用迁入 `onNavigate`；根 Column 补 `navigationBarsPadding()`。
- **守恒**：Screen 9 值 / when 9 分支 / chromeOf 9 映射 / refresh / modelsFrom / 原 BackHandler / 外部导入落地 / 通知回跳 /
  native / prompt v2 / Prefs / 4B 设置 / 调音台数据层 / PlayQueue / AudioLibrary / Manifest / Gradle 全部与 HEAD 一致；
  App() 之外的业务代码逐行守恒。
- **验证**：本地 8 套脚本 + `CI=true` 复现全绿；R46 正向 26 符号 / 旧壳 6 项零残留 / 反向注入 9 例全拦下 / 守恒 12 文件；
  CI **run {RUN} `completed success`**（新增 R46 验证 step）；拆包 CRC / 7 .so / 权重 0 / 无 INTERNET / `{VER}` /
  抽屉新符号全在 / 旧壳零残留 / `TTS_PARAMS_APPLIED` 在；直链 blob `{BLOB[:8]}…` 与本地 {SIZE_S} 字节逐字节一致。
- **验证脚本自身订正 3 处**：`check_structure.py` 的 U1/R33/R36 旧壳断言同步到 V1 形态、`when(screen)` 分发块改带上下文锚点
  （新增的两个 when 曾让分支计数误报 7）；`r45_common.canon()` 剔除 App() 壳区域；`r45_verify.py` Prefs 守恒 marker 笔误（HEAD 里找不到旧注释，切片恒空）。
- ⚠️ **真机仍零证据**。队列：真机验 → V2 目录页+绿勾 → V2b 看书页 → V3 迷你条 → 导入通道 → U2。
""", encoding="utf-8")
print("回填 ITERATION_LOG")

card = f"""
<div class="card" style="margin-top:14px">
<div class="h">⑰ R46 · V1 抽屉壳（{VER} / run {RUN}）</div>
<b>输入：</b>R34/R35 定案的 V1，连续顺延后按队列兑现；只做导航壳，不夹带章节页 / 绿勾 / 迷你条。<br>
<b>落地：</b>根布局包进 <code>ModalNavigationDrawer</code>；☰ 与从左边缘右滑都开一整页抽屉
（官方 <code>gesturesEnabled=true</code>，不自写横向手势）；新增 <code>BackHandler(drawerState.isOpen)</code> 排在原条之后，返回键先收抽屉；
底栏 3 Tab 全删，7 个目的地（书架 / 任务 / 我的音频 ／ 设置 / 调音台 / 模型 / 角色音色）收进抽屉一列，调音台独立项；
<code>drawerReturn</code> 让二级页「‹ 返回」回进入前的页；根 Column 补 <code>navigationBarsPadding()</code>，底栏删掉后不压系统白条。<br>
<b>不手搓：</b>形状抄本地 <code>OfflineLLM/ChatScreen.kt</code>（Apache-2.0），条目用官方 M3 组件，零新增依赖。<br>
<b>守恒：</b>Screen 9 值 / when 9 分支 / chromeOf 9 映射 / refresh / modelsFrom / 外部导入落地 / 通知回跳 /
native / prompt v2 / Prefs / 4B / 调音台数据层 / PlayQueue / AudioLibrary / Manifest / Gradle 与 HEAD 一致。<br>
<b>验证：</b>本地 8 套脚本 + <code>CI=true</code> 全绿（R46 正向 26 符号 / 旧壳 6 项零残留 / 反向注入 9 例 / 守恒 12 文件）·
CI <b>run {RUN} 全绿</b> · 拆包 CRC / 7 .so / 权重 0 / 无 INTERNET / <code>{VER}</code> / {SIZE_S} 字节 /
抽屉新符号全在 / 旧壳零残留 · 直链 blob <code>{BLOB[:8]}…</code> 逐字节一致。<br>
<b>真机仍零证据</b>；队列：真机验 → V2 目录页+绿勾 → V2b 看书页 → V3 迷你条 → 导入通道 → U2。
</div>
"""
html = ROOT / "docs/听本_项目导图.html"
h = html.read_text(encoding="utf-8")
h = h.replace("<footer>听本 · 项目导图", card + "\n<footer>听本 · 项目导图", 1)
h = h.replace("⑯ 由 R45 追加", "⑯ 由 R45 追加，⑰ 由 R46 追加", 1)
html.write_text(h, encoding="utf-8")
print("回填项目导图 ⑰")

out = WS / "听本_R46_项目文件.zip"
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for rel in ["docs/R46_V1抽屉壳_联动表.md", "docs/R46_V1抽屉壳_落地.md",
                "STATUS.md", "README.md", "ITERATION_LOG.md",
                "docs/听本_项目导图.html", "scripts/r46_verify.py",
                "scripts/r46_apk_verify.py"]:
        z.write(ROOT / rel, rel)
print("交付包", out, out.stat().st_size, "字节")
