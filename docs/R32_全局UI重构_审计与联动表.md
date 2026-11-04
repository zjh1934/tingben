# R32 全局 UI 重构 · 审计与联动表（纯设计轮）

> 本轮**不动业务代码、不发包**。用户原话：「不是说直接就改部分代码就可以，他每一个改动后面都连带着一堆东西，这个你要协调好」。
> 交付：事实核查 → 架构根因 → 逐条对照 → 举一反三 → 拿来主义清单 → 联动清单 → 分轮施工顺序。

---

## 0. 这轮该做什么

**先协调，不上手改。** 全局 UI 重构牵动导航结构、刷新机制、外部 intent 落地页、通知回跳、任务书签、设置作用域六条线，一轮全改必然重蹈「改一处联动很多处不验证」。所以本轮只出审计与联动表，U1 才动第一行代码。

---

## 1. 两个事实核查（实查，不靠记忆）

### 1.1 「我没有看到你的调音台」—— 不是没做，是入口层级错了

拆包实查 `听本_app-debug.apk` = `1.0.0+40` / 17,869,546 字节 / 7 个 dex：

```
DEX_HAS 调音台        True
DEX_HAS MIXER_PROFILE True
DEX_HAS MIXER_AB      True
DEX_HAS 音色网格      True
DEX_HAS 稳定朗读      True
DEX_HAS 戏剧张力      True
DEX_HAS 1.0.0+40      True
```

源码 `MainActivity.kt:1461-1478`：`SettingsScreen` 的**第一张卡片**就是「🎛 调音台 · 打开音频模型控制台」，点击进 `Screen.MIXER`（`MixerScreen` 在 2085 行）；数据层 `core/Mixer.kt` 208 行完整存在。

到达路径 = 底部第 4 个 Tab「设置」→ 第一张卡片 → 调音台。

**没看到的三个可能（按概率）**：
1. 装的不是 `+40`（历次贴的诊断多为 `+32` / `+38`）→ 先验版本号；
2. 用户把「设置」预期成左上角齿轮，没把底部第 4 个 Tab 当设置入口；
3. 调音台是「设置页里的一张卡」，不是一级目的地；用户要的「控制台」应该在更显眼、更直接的位置。

**定性：信息架构问题，不是功能缺失。** U1 定完导航结构后，U4 把调音台提成一级入口（抽屉第一项或音频页顶部），不靠再塞一张卡解决。

### 1.2 4B 模型：订正上一轮的过度解读

用户明确：「不是给他放弃，我想用就用、不想用就不用，现在在软件里是啥样就保持啥样；他的调整放最后面或者就不调了」。

- **一行不动**：`Prefs.thinking / nCtx / threads / temperature`、`prompt_zh.txt` v2、`Producer` 标注 / 直读两条路径、`bucket()` 分桶、`TaskResume`、native、缓存指纹。
- 唯一可选：设置页「① 语言模型」整组**沉到页尾**（纯顺序，不动逻辑）；本轮连这个都不做。
- 上一轮我写的「端侧 4B 标注撤下主路」是错读，**作废**。标注工厂是「多一条输入来源」，不是「替换掉端侧能力」。

---

## 2. 架构根因：为什么「改一处联动很多处」是真的

| 事实 | 实查 |
|---|---|
| 全部 UI 挤在 `MainActivity.kt` | **2397 行 / 122,121 字符**；自有代码 26 个 .kt，UI 只有这 1 个文件 |
| 没有导航图 / 应用级状态容器 | 靠 `screen: Screen` 枚举 + `when(screen)` 分发 8 个目的地 |
| 全 App 唯一刷新机制 | `refresh: Int` 计数器，所有 `remember(refresh)` 缓存靠它失效 |
| 自建基础件只有 3 个 | `CardBox` / `GroupLabel` / `SettingOption` |
| 下拉刷新是手搓的 | `detectVerticalDragGestures` + 累计 `acc > 55f`（467-492 行） |

所以「删一个刷新按钮」会同时碰到：refresh 计数器、`onChanged` 回调、外部 intent 落地、通知回跳。这就是必须先列联动表再动代码的原因。

---

## 3. 首页逐条对照（用户这一页的意见 → 判定 → 连带）

| # | 现状（行号） | 判定 | 连带 |
|---|---|---|---|
| 1 | 顶栏「听本」+「把小说变成能听的音频」(294-295) | 删 | edge-to-edge / 状态栏 insets / 通知栏重叠；顶层目的地标题策略（Now in Android 只在顶层显示 TopAppBar [citation:6]） |
| 2 | 右上「⟳ 刷新」(297-301) | 删按钮，**保机制** | `refresh++` 是全 App 刷新机制，不能连机制一起删 |
| 3 | 「＋ 导入」带文字 (303-307) | 纯 + 图标 | `openBookPicker()` / SAF contract / 外部 ACTION_VIEW·EDIT·SEND / `EXTERNAL_INTENT` / `modelsFrom` 回退 |
| 4 | 「下拉刷新 · 或点右上角 ⟳ 刷新」(490) | 删注释文案 | 手搓拖拽 → Material3 `PullToRefreshBox` + `nestedScroll`（官方组合 [citation:9]） |
| 5 | 大标题「把小说变成能听的音频」+ 小字 (494-497) | 删 | 与顶栏定位重复；空状态改图标+一个动作 |
| 6 | 「有一本书正在制作 / 有没做完的任务」整卡 +「查看进度」(499-519) | 改**上扒展开、下扒收起**的常驻卡，不跳页 | `ProduceService` 通知 PendingIntent、`TaskCenterScreen`、`pendingTask`、`onProgress` / `onTasks`、`LLM_VERDICT` 判定条位置 |
| 7 | 「没有文件？粘贴一段文字」文字按钮才展开 (539-543) | 默认可见的空输入框 + 粘贴 / 清除按钮 + 可折叠 | `BookStore.add` / SHA-256 去重（+24）/ `EXTERNAL_IMPORT_EMPTY` / 空态卡片 |
| 8 | 书卡「删除」文字按钮、无确认 (605) | 图标 + AlertDialog 确认 + 删后自动刷新 | **实查真 bug**：`BookStore.delete` 后没调 `onChanged()`，列表不会更新 —— 用户猜对了 |
| 9 | 「制作有声书」文字按钮 (606) | 图标 | `onOpen` / `Screen.PRODUCE` |
| 10 | 底部 4 Tab 含「设置」(413) | 设置移左上角齿轮 / 三道杠 | `Screen` 枚举、4→3 Tab、`modelsFrom` 回退栈、`Screen.MIXER` 归属、`onBack` 分发、Manifest 默认落地页 |
| 11 | 通篇「边用边看」的注释式文案 | 举一反三清理 | 见 §4 |

---

## 4. 举一反三：其他页同款毛病

- **设置页**：标题「设置」23sp (1459)；「作用域」整卡在解释概念 (1480-1489)；页尾 1748 又重复一句产品定位 → 同样删注释，作用域用一个小徽标表达。
- **制作准备页**：四组折叠已对，但每组下面还有 757 / 771 / 786 / 790 四段解释小字（「这三项是跨书默认值，去设置页改」…）→ 只留「长期 / 仅本次」徽标。
- **进度页**：`LLM_VERDICT` 判定条 + 看台胶囊 +「手机 CPU 慢不是死机」常驻提示 → 收进可折叠，主屏只留进度与一个主按钮。
- **任务页**：空状态一句长说明 (1169) → 图标 + 一个动作。
- **音频页**：删除按钮 (1406) 同样无确认、无刷新联动。
- **调音台**：18 行「当前生效配置」是只读快照，用户要的是「显示出来」，不是「再改一份」→ 保留，U4 只定入口。
- **全 App**：Toast 当反馈（「书架已刷新」「书加好了」）→ 换 Snackbar / 状态自证。

---

## 5. 不手搓：现成件清单

| 要做的事 | 抄谁 | 依据 |
|---|---|---|
| 应用级脚手架 / 顶层导航 / 设置入口 | Now in Android `NiaAppState` `NiaNavigationSuiteScaffold` `TopLevelDestination`；设置用 dialog 而非 Tab | [citation:6][citation:8][citation:12][citation:15] |
| 左上角导航图标 | Android 官方 `CenterAlignedTopAppBar` + `navigationIcon` | [citation:5] |
| 下拉刷新（4 页统一） | Material3 `PullToRefreshBox` + `TopAppBarDefaults.exitUntilCollapsedScrollBehavior` + `Modifier.nestedScroll` | [citation:9] |
| 上扒展开 / 下扒收起常驻卡 | `ch4rl3x/BottomDrawerScaffold`（Apache-2.0，`drawerPeekHeight` + `drawerGesturesEnabled`） | [citation:11] |
| 删除确认 / 破坏性色 | Composables UI `BottomSheet` action menu（`destructiveColor`） | [citation:16] |
| 可折叠分组 | 本地 `reference/SSComposeExpandableListView`（MIT）、`ensemble-reads/ChaptersScreen` | 已在库 |
| 设置页 / Repository / ViewModel | 本地 `reference/精选_Kotlin现成件/SettingsScreen.kt` `SettingsViewModel.kt` `SettingsRepository.kt`（OfflineLLM） | 已在库 |
| 底栏 | Material3 `NavigationBar`（不引入 `SmoothBottomBar`：要求 API 32+ 且要加 Jitpack 依赖） | [citation:14] |

**硬约束**：不新增 Gradle 依赖（离线构建 + CI 缓存），一律「读源码、改写形状」而非加库。

---

## 6. 联动清单（U1 动代码前逐条签，14 项）

1. `refresh: Int` 计数器 → 换 StateFlow / 事件前，不得删按钮
2. `Screen` 枚举 8 个值 → 删 Tab 同步 `when(screen)` 全部分支
3. 回调签名：`onChanged` / `onModels` / `onBack` / `onProgress` / `onTasks` / `onCast` / `onMixer`
4. 外部 intent（`ACTION_VIEW` / `EDIT` / `SEND`）默认落地页 + `EXTERNAL_INTENT` 日志
5. `ProduceService` 通知 PendingIntent 仍指进度页
6. `TaskResume` 书签 / `pendingTask` / `realProgress`
7. **`BookStore.delete` 后 `onChanged()`**（真 bug，U2 修）
8. `AudioLibrary` 的 `index.json` 与书卡引用、成品删除
9. `Prefs` 长期默认 vs `ProduceConfig` 仅本次：作用域标识不能丢
10. 缓存分桶 `bucket()` / `voice_stamp` / prompt hash —— **一行不动**
11. 标注 / 直读两条路径、`LLM_VERDICT`、判定条 —— **一行不动**
12. 前台服务 / `PARTIAL_WAKE_LOCK` / 通知渠道 —— **一行不动**
13. 诊断摘要 `Prefs.summary(c)` 文案依赖设置项顺序
14. `ModelStore.ready` / `missing` 与顶栏「去准备」入口

---

## 7. 分轮施工（每轮单独发版，先联动表后代码）

| 轮 | 范围 | 验收 |
|---|---|---|
| **U1** | 全局壳：`AppState` / 导航骨架、顶栏精简、设置移左上角、4 Tab→3、Material3 下拉刷新统一到 4 页 | 外部「打开方式」仍能加书；通知点进去仍能到进度；每页下拉都能刷；版本/任务不丢 |
| **U2** | 书架页：粘贴框、图标操作、删除确认+自动刷新、任务卡上扒/下扒手势 | 删书立刻消失；任务卡展开收起不跳页 |
| **U3** | 其余页一致性：注释清理、空状态、Snackbar、标题层级、音频页删除确认 | 全 App 无「边用边读注释」长句 |
| **U4** | 调音台一级入口定案（抽屉第一项或音频页顶部），保留 18 行快照与 A/B | 从首页两步内可达；不产生第二份真值源 |

每轮固定动作：联动表 → 源码级反向断言 → CI → 拆包硬验 → **单独发版**，不合并。

---

## 8. 明确不做

- 不动 4B / prompt v2 / native / 缓存 / 前台服务 / 标注与直读两条路径
- 不换模型、不加网络权限、不加新 Gradle 依赖
- 不做性能页（已删）、不做无障碍 / 浮窗 / 模拟点击保活
- **本轮不发包**

---

## 9. 真 bug 登记

`MainActivity.kt:605`：`TextButton(onClick = { BookStore.delete(c, b.id) })` 之后**没有 `onChanged()`**，删除后列表不刷新。U2 修，不动数据层。

---

## 10. 附：四个「资源解析失败」链接

不是坏链：三个是 GB 级 GGUF 二进制被平台解析器挡下，第四条是另一个项目（`kongzhishouji`）的 APK，与听本无关。用浏览器直接打开即可。
