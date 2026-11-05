#!/usr/bin/env python3
"""R51 · 项目文件回填：STATUS / README / ITERATION_LOG / 导图 / R51 联动表。
每处替换都要求锚点唯一，避免误伤历史段落。
"""
import pathlib, sys

R = pathlib.Path(__file__).resolve().parent.parent

def edit(rel, pairs, append=None):
    p = R / rel
    s = p.read_text()
    for old, new in pairs:
        n = s.count(old)
        if n != 1:
            sys.exit("R51 docs %s: 锚点命中 %d 次（应为 1）: %r" % (rel, n, old[:50]))
        s = s.replace(old, new, 1)
    if append:
        s += append
    p.write_text(s)
    print("  ok", rel)

# ── STATUS 头部 ────────────────────────────────────────────────────────────
edit("STATUS.md", [
("**2026-11-05 · R50 调音台真机三处修补 · 紧凑参数行 / 表演层自定义真落地 / 锚点滚动联动**",
 "**2026-11-05 · R51 卡顿根因收窄 + 抽屉联动（本轮不进 V2）**"),
("| 版本 | `1.0.0+60`（run 60；发布详情见 `docs/R50_真机三处修补_落地.md`） |",
 "| 版本 | `1.0.0+65`（run 65；发布详情见 `docs/R51_卡顿根因与抽屉联动_落地.md`） |"),
("| APK | arm64-v8a / 固定 debug 签名 / **18,001,010 字节** / 详见 `docs/R50_真机三处修补_落地.md` |",
 "| APK | arm64-v8a / 固定 debug 签名 / **18,001,017 字节** / 详见 `docs/R51_卡顿根因与抽屉联动_落地.md` |"),
("| 直链 | `https://raw.githubusercontent.com/zjh1934/tingben-apk/main/app-debug.apk`（blob `7f4297dc…`，与本地逐字节一致） |",
 "| 直链 | `https://raw.githubusercontent.com/zjh1934/ting-ben-apk/main/app-debug.apk`（blob `52bf35a2…`，与本地逐字节一致；旧名 `tingben-apk` 自动跳转） |"),
("| CI | **run 60 `completed success`**（30/32 步 ok，含新增 R50 验证 step；run 59 编译失败已修） |",
 "| CI | **run 65 `completed success`**（29 步：27 ok / 2 skipped，含新增 R51 验证 step；run 62 仅直链发布失败，63/64 修复） |"),
("| 源码仓 | `zjh1934/tingben-android`（公开，commit 见 releases） |",
 "| 源码仓 | `zjh1934/tingben`（原 `tingben-android`，GitHub 改名后旧名自动跳转；公开） |"),
("| APK 仓 | `zjh1934/tingben-apk`（公开） |",
 "| APK 仓 | `zjh1934/ting-ben-apk`（原 `tingben-apk`，旧名自动跳转；公开） |"),
], append="""

## R51 · 卡顿根因收窄 + 抽屉联动（1.0.0+65 / run 65）

- **本轮不进 V2**（目录页 / 按章绿勾 / 看书页 / 迷你条一行未动）。用户反馈「软件小却卡、界面切换卡」「抽屉应和工作页联动，不是覆盖」。
- **卡顿实查**：`App()` 顶层为 5 个 `LibraryScreen` 根本没读的参数订阅 `Producer.state/resume`，制作时每次块更新重组 `when(screen)` 下全部 9 页；三个无条件定时器（音频 300ms / 任务 1000ms / 进度 1000ms）闲着也重组；`Column+verticalScroll` 全量组合；组合期同步读磁盘；debug 产物关 Compose 优化。
- **本轮修**：2 个顶层订阅下沉到 `KeepScreenOn()` / `TaskBar()` 两个叶子；删 5 个白挂参数；续跑回调读 `.value` 不订阅；三个定时器按 `pq.isPlaying` / `st.running` 门控，任务页合成 2 秒 `loadTick`；锚点流不再每帧 `anchors.toMap()`。
- **抽屉**：`ModalNavigationDrawer`（覆盖 + 全屏遮罩）→ `DismissibleNavigationDrawer`（展开时把右侧工作页一起推走）。☰ / 左滑手势 / 返回键优先 / 7 目的地 / 240dp / 底部安全区一行未改。
- **未改（单列性能二期）**：`Column→LazyColumn`（会破坏 R50 锚点坐标语义）、组合期磁盘 IO 迁 `produceState`、release-like 构建。不宣称卡顿归零，**真机帧率仍零证据**。
- **守恒**：4B 设置 / prompt v2 / 两条制作路径 / 缓存分桶 / 续跑书签 / 唯一提交出口 / 两态试听 / `ParamSpec` / `MixerDraft` / native / JNI / 前台服务 / Manifest / Gradle 依赖全不动。
- **验证**：本地 12 套 `CI=true` 全绿；`r51_verify.py` 正向 14 项 + 反向注入 6/6；`check_structure.py` 改判 `DismissibleNavigationDrawer(`；新增 `r51_apk_verify.py`（CRC / 7 .so / 权重 0 / 无 INTERNET / `1.0.0+65` / 23 新符号 / 15 条旧形态零残留 / `TTS_PARAMS_APPLIED` 在，含 `--selftest` 反向注入）。
- **发布**：仓库改名（`tingben-android→tingben`、`tingben-apk→ting-ben-apk`）；run 61/62 失败分别是 CI 仓库引用与直链 307，run 63 修 `publish_apk` POST 307、64 恢复文件、65 全绿。直链 blob `52bf35a251532ddc8253471371d38adde1a9ea82` 与本地逐字节一致。
- **队列不变，仅顺延**：真机验 R51 → V2 目录页+按章绿勾 → V2b 看书页 → V3 迷你条 → 导入通道 → U2；性能二期单独立项。
""")

# ── README ─────────────────────────────────────────────────────────────────
edit("README.md", [
("https://raw.githubusercontent.com/zjh1934/tingben-apk/main/app-debug.apk",
 "https://raw.githubusercontent.com/zjh1934/ting-ben-apk/main/app-debug.apk"),
("备用（Release 页）：`https://github.com/zjh1934/tingben-android/releases/latest`",
 "备用（Release 页）：`https://github.com/zjh1934/tingben/releases/latest`。\n> 仓库已改名：源码仓 `tingben-android→tingben`，APK 仓 `tingben-apk→ting-ben-apk`；旧地址自动跳转。"),
("- 固定地址**永远是最新版**，版本号跟 CI run 号（当前 `1.0.0+60`）",
 "- 固定地址**永远是最新版**，版本号跟 CI run 号（当前 `1.0.0+65`）"),
("- 源码：`https://github.com/zjh1934/tingben-android`（公开）",
 "- 源码：`https://github.com/zjh1934/tingben`（公开）"),
("  详见 `docs/R50_真机三处修补_落地.md` / `docs/R50_真机三处修补_联动表.md`。",
 """  详见 `docs/R50_真机三处修补_落地.md` / `docs/R50_真机三处修补_联动表.md`。
  **R51 卡顿根因与抽屉联动**：`App()` 顶层两个高频引擎订阅下沉到 `KeepScreenOn()` / `TaskBar()`
  叶子（书架页 5 个从未被读取的参数删掉），三个无条件定时器按播放态 / 制作态门控，锚点流不再每帧复制整张表；
  外层抽屉 `ModalNavigationDrawer` → `DismissibleNavigationDrawer`，展开时右侧工作页一起推走（不再是覆盖 + 遮罩）。
  `Column→LazyColumn` / 组合期磁盘 IO / release-like 构建单列性能二期，本轮未做。
  详见 `docs/R51_卡顿根因与抽屉联动_落地.md` / `docs/R51_卡顿根因与抽屉联动_联动表.md`。"""),
])

# ── ITERATION_LOG ──────────────────────────────────────────────────────────
edit("ITERATION_LOG.md", [], append="""

## R51 · 卡顿根因收窄 + 抽屉从覆盖改联动（1.0.0+65 / run 65）

- **用户问句**：「为什么这么小的软件用起来这么卡？界面切换也卡得要死」「抽屉应该是左滑展开收起、和右边工作页有联动，不是覆盖」。本轮先不进 V2。
- **结论**：不是包小就该快。① `App()` 顶层订阅 `Producer.state/resume`，只为给 `LibraryScreen` 传 5 个函数体零引用的参数 → 制作时每次块更新重组 `when(screen)` 全部 9 页；② 9 页全 `Column+verticalScroll` 一次性全量组合；③ 音频页 300ms / 任务页 1000ms / 进度页 1000ms 三个无条件 tick，闲着也重组；④ `remember(refresh){...list()}` 组合期同步读盘；⑤ debug 包关闭 Compose 编译器优化。
- **落地**：删 5 参 + 2 顶层订阅；`KeepScreenOn()` / `TaskBar()` 叶子订阅，续跑回调读 `Producer.resume.value`；三个 tick 分别改 `LaunchedEffect(pq.isPlaying)` + 500ms / 2 秒 `loadTick` / `LaunchedEffect(st.running)`；`snapshotFlow { scroll.value }` 不再每帧 `anchors.toMap()`。
- **抽屉**：官方 `DismissibleNavigationDrawer` 展开推走工作页，替代 modal 覆盖式；`@OptIn(ExperimentalMaterial3Api::class)`；☰ / 左滑 / 返回键 / 7 目的地 / 240dp / 安全区守恒。
- **未做（如实）**：`Column→LazyColumn`（破坏 R50 `onGloballyPositioned` 锚点语义）、组合期 IO 迁 `produceState`、release-like 构建 —— 单列性能二期，不宣称卡顿归零。
- **验证**：12 套 `CI=true` 全绿；`r51_verify.py` 正向 14 + 反向 6/6；脚本自身两处假绿（注释里的 `TaskBar(onNavigate` 骗过 `in` 断言 → 改行首正则、注释锚点名写错）已修；`check_structure.py` 改判联动抽屉 + 覆盖式零残留。
- **发布复盘**：仓库改名 `tingben-android→tingben` / `tingben-apk→ting-ben-apk`；run 61（CI 仓库引用）、run 62（直链发布 307）失败，**run 62 真编译与 APK 闸门都过**；run 63 修 `publish_apk` POST 307、run 64 恢复文件、run 65 补挂 `r51_verify` 后 `completed success`（29 步 27 ok / 2 skipped）。
- **拆包**：新增 `scripts/r51_apk_verify.py` + `--selftest` —— CRC 全过 / 7 个 arm64 .so / 权重 0 / 无 INTERNET / binary Manifest `1.0.0+65` / 23 新符号 / 15 条旧形态零残留 / `TTS_PARAMS_APPLIED` 在。debug dex 仍保留 M3 内部引用的 `ModalNavigationDrawer` 类名，调用清零由源码层断言负责。
- **直链**：18,001,017 字节，blob `52bf35a251532ddc8253471371d38adde1a9ea82`（官方 `download_url` 为新仓库名），与本地逐字节一致；文档同步带 `[skip ci]`。
- ⚠️ **真机帧率仍零证据**。队列：真机验 R51 → V2 目录页+按章绿勾 → V2b 看书页 → V3 迷你条 → 导入通道 → U2；性能二期单独立项。
""")

# ── 导图：追加 ㉑ 段 ───────────────────────────────────────────────────────
edit("docs/听本_项目导图.html", [
("⑳ 由 R50 追加</footer>", "⑳ 由 R50 追加，㉑ 由 R51 追加</footer>"),
("\n\n\n<footer>", """

<div class="card" style="margin-top:14px">
<div class="h">㉑ R51 · 卡顿根因收窄 + 抽屉从覆盖改联动（1.0.0+65 / run 65）</div>
<b>输入：</b>「软件小却卡、界面切换卡得要死」「抽屉和工作页应该有联动，不是覆盖」。本轮<b>不进 V2</b>。<br>
<b>卡顿实查：</b><code>App()</code> 顶层订阅 <code>Producer.state/resume</code>，只为给 <code>LibraryScreen</code>
传 5 个函数体零引用的参数 → 每次块更新重组 <code>when(screen)</code> 全部 9 页；三个无条件 tick（音频 300ms /
任务 1000ms / 进度 1000ms）；9 页 <code>Column+verticalScroll</code> 全量组合；组合期同步读盘；debug 关 Compose 优化。<br>
<b>落地：</b>2 顶层订阅下沉 <code>KeepScreenOn()</code> / <code>TaskBar()</code> 叶子、续跑读 <code>.value</code>；
三个 tick 按播放态 / 制作态门控，任务页合成 2 秒 <code>loadTick</code>；锚点流不再每帧 <code>anchors.toMap()</code>。<br>
<b>抽屉：</b><code>ModalNavigationDrawer</code>（覆盖 + 遮罩）→ <code>DismissibleNavigationDrawer</code>（展开把右侧工作页一起推走）；
☰ / 左滑 / 返回键 / 7 目的地 / 240dp / 安全区守恒。<br>
<b>未做（性能二期）：</b><code>Column→LazyColumn</code>（会破坏 R50 锚点坐标语义）/ 组合期 IO 迁 <code>produceState</code> /
release-like 构建。<b>不宣称卡顿归零，真机帧率仍零证据。</b><br>
<b>验证：</b>12 套 <code>CI=true</code> 全绿 · <code>r51_verify</code> 正向 14 + 反向 6/6 · 新增 <code>r51_apk_verify.py</code>
（CRC / 7 .so / 权重 0 / 无 INTERNET / <code>1.0.0+65</code> / 23 新符号 / 15 条旧形态零残留，含 <code>--selftest</code>）。<br>
<b>发布：</b>仓库改名 <code>tingben-android→tingben</code> / <code>tingben-apk→ting-ben-apk</code>；run 62 仅直链 307 失败、
63 修 <code>publish_apk</code>、65 全绿（29 步 27 ok / 2 skipped）· <b>18,001,017 字节</b> / blob <code>52bf35a2…</code> 与本地逐字节一致。<br>
<b>队列：</b>真机验 R51 → V2 目录页+按章绿勾 → V2b 看书页 → V3 迷你条 → 导入通道 → U2；性能二期单独立项。
</div>


<footer>"""),
])

# ── R51 联动表：落地回执 ───────────────────────────────────────────────────
edit("docs/R51_卡顿根因与抽屉联动_联动表.md", [], append="""

---

## 六、落地回执（run 65）

- 代码、验证脚本、`ci.yml` 均已同步远端；**CI run 65 `completed success`**（29 步：27 ok / 2 skipped，真 Kotlin 编译通过）。
- 产物 `1.0.0+65` / **18,001,017 字节** / blob `52bf35a251532ddc8253471371d38adde1a9ea82`，与本地 APK 逐字节一致；Release `v1.0.0-65` asset 同尺寸。
- 拆包硬验 `scripts/r51_apk_verify.py`：CRC / 7 个 arm64 .so / 权重 0 / 无 INTERNET / 23 新符号 / 15 条旧形态零残留 / `TTS_PARAMS_APPLIED` 全过，`--selftest` 反向注入正确拦下。
- 仓库改名：源码仓 `zjh1934/tingben`，APK 仓 `zjh1934/ting-ben-apk`；主直链为新名，旧名自动跳转。
- 完整复盘见 `docs/R51_卡顿根因与抽屉联动_落地.md`。队列不变，仅顺延。
""")

print("R51 docs done")
