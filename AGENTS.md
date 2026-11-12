# 听本 · 仓库规则（给 AI Agent）

> 新会话第一件事：按下面的顺序读，不要先翻历史，不要先读归档。

## 每轮对话必做（用户硬规则）

认知文件**两份**：仓库内**地图**（`aoci.txt` / `aoci.meta.txt` / `aoci.code.txt` / `AGENTS.md`）、仓库外**任务看板** `../任务看板.md`。R249 用户纠正：待办不单起文件，**直接进看板、且是看板大头**——「待做」段置顶；R282 用户再放宽：**看板随当前批次变形，分区/字数/条数都不锁死，待办可膨胀、每条都要做、不因多而阉**，做完当轮删；已做/在做压成结论句。R243 曾另立 `待办清单.md`，R249 已删、口径作废；R243 的 ≤600 字 / 3~5 条锁死口径 R282 作废。其余 md 已归档，不读、不作认知证据。

- **进门**：先读地图，再读看板（**待做置顶为大头**，已做/在做给结论），然后才动源码。
- **收尾两份都维护**：
  1. 看板随当前批次改：待做是大头、可膨胀、每条不阉、做完即删；已做/在做给结论；硬约束 ≤10 条。待办不另起文件，两份都当轮维护。
  2. 地图随本轮改动维护：受影响 Entry 的 F/R/A/S 跟上新事实，`--lookup <本轮主题>` 复查后跑官方 `aoci verify` / `aoci check`；Baseline 由官方维护，不跑 `aoci_check.py --refresh` 重烧地图。
- 纯只读问答也核一遍看板状态；有产出就两份都改。
- **每次回复用户，把任务看板全文贴进代码块**（用户 R201 轮硬要求）：方便一键复制、手动改；不贴等于没维护。

## 阅读顺序

1. `aoci.txt` → `aoci.meta.txt` → `aoci.code.txt`：仓库认知地图，逐文件记着职责、强关系、对外契约和踩过的坑。**别手翻全工程**，先查：

   ```
   python3 scripts/aoci_check.py --lookup <要动的模块或关键字>
   ```

   查不到命中，说明索引该维护了；维护完再打开源码，不要直接把整个仓库重扫一遍。
2. `docs/00_现行契约.md`：现在成立的硬约束，只写现状、不写历史。
3. `STATUS.md`（现状）→ `ROADMAP.md`（排期）→ `version.txt`（版本唯一真值源）。
4. 要改哪块，再打开对应源码。改完回到第 1 步核一遍认知是否还对齐。

## 噪声边界（不读、不作认知证据）

`dev/`、`reference/`、`samples/`、`contracts/`、`真机截图/`、顶层 `R*_落地报告.md`
与 `本地改码批次*.md`、以及 `scripts/` 下未被现行清单登记的历史验证脚本，都是历史回执。
它们不参与构建、不参与现行自检，也不进 AOCI 受管范围。

**例外**：`scripts/r135_verify.py` 里 `CURRENT_VERIFIERS` / `QUICK` 登记的守门必须跑。

## 硬口径

- `version.txt` 是版本唯一真值源；**每打一次包升一个小版本**，纯改代码或纯文档轮不升。
- 教程、箭头流程、图文步骤、长解释**不进 App 界面**（用户硬口径）。删除确认里写清后果不算教程。
- 回复只给结论，不写「真机证据不足 / 静态绿不等于编译过」这类每轮重复的免责套话；内部判断仍不冒充真机验证。
- 删除独立页之前，先把它的能力搬干净；破坏性操作先预览再执行。
- **验收阶段节奏（R217，B 档历史口径）**：用户当时明确切到「每轮提问题 → 处理完 → 每轮出安装包供真机验收」。R256 重申：**A 档默认不主动出包**，这条只在用户显式切 B 档时生效，不能拿它当常态。覆盖 R204 的攒批不发：本阶段每轮对话都要升一个修订号、走 rc 真编译 → `--promote` → 发版，用户自己装包验收；仍不做逐步同步中间结果。
- **调音台与缓存口径（R220）**：内容完整、成本优先，调音台/模式改动**绝不丢已做好的段与整章成品**。换套时旧套复制留档、根目录已生成段原样认领为新套起点，点继续只把剩下的段按新参数合成；TaskResume 不得把调音台改动算任务失效或把进度归零。切回用过的设置仍逐字节还原。小段显示编号每章从 1 起（文件段号仍整本连续）；没做过也没在做的章点章名不展开；展开区顶部零留白。
- **拼接口径（R231）**：拼的是**这一章**的音频（该章小块 → 单章成品），不是整本、更不是「拼整张」。整本成品之外，每章小块齐了就单独出一份，目录页小耳机直接播它。
- **拼接借鉴来源（R231，同行开源项目）**：
  · `Ensemble Reads`（ruqinhu/ensemble-reads，Kotlin Android 听书）：短段合并避免长章节被切碎、边合成边播、失败重试；按章合并的触发形态参考它。
  · `Enzo22/Merge Audio: MP3 Joiner`：**不二次编码、逐帧拷贝无损**；接缝过渡（直连 / 静音 / 淡入淡出）；波形裁剪、M4B 每轨成章。
  · `ZeroOneZeroR/android_audio_mixer`：SEQUENTIAL 顺序拼接 + `BlankAudioInput` 插段间静音；基于 `MediaExtractor/MediaCodec/MediaMuxer`。
  · 百度智能云 / hesong 的 Android 音频拼接实践：`MediaExtractor+MediaMuxer` 输出 WAV，**前提是各段采样率 / 声道 / 位深一致**。
  · 结论：现有 `WavMerger` 本就是「流式 PCM 直拷 + 段间静音 + 首尾 ramp」，与同行无损路线同构，不是自造；R231 按同行共识补**格式一致性前置校验**（`fmtOf` 校验 24000/单声道/16bit，不一致拒拼并留小块），并把接缝 ramp 做成可配。**不引 FFmpeg** 是工程选择：全部段由同一引擎产出、天然满足无损前提，引库只增包不增益，不是禁令，后续按需要可改。
- **段级重做（R235，R266 修正）**：长按小块多选后「重做选中」把段号写进 `SegPlan.markSeg`（`redo.json`），按当前调音台重新合成；循环里每段重读 `SegPlan.pending(work)`，标记段即使已有 WAV 也强制重做。R266 修掉 R235 的错做法：制作中原先把重做排成「整章新任务」进 `TaskResume` 队列，得等整本跑完才轮到，当前任务一旦没正常完成 finally 还会把队列永久暂停——看着像「这一章不做了」。现制作中只标记，由主循环在当前段收尾后 `backlog` 就地回头重合成；空闲才 `enqueueOrStart`。
- **运行中改设置（R235）**：开跑前不再把整本 instruct 一次性拼进 `toSpeak`，改 `liveSeg(i)` 逐段实时读 `Prefs.defaultPerf / voiceDescription / ttsKnobs / nonVerbal`，下一段即按新配置念；`SynthInput.params` 与 `ttsSynthesize` 的 text/speaker/knobs 全用当段实时值。**音色分套锁在开跑时一次**（voiceStamp / cacheKey / `VoiceCache.switchTo` / 三个 stamp 文件），绝不因运行中改设置换套、删已做段或把进度归零（R220 硬口径）。拼接参数 `config.gapMs` 仍用开跑快照，保证同一本前后接缝一致。运行中切模式 / 换参考音频不生效，需下次开跑重新 `SynthInput.prepare`。
- **制作设置落盘（R235，修真 bug）**：`ProduceUi` 点合成时 `Prefs.setDefaultPerf(c, cfg)`，目录页 `startAction` 也改读 `Prefs.defaultPerf(c)`。原先制作设置页只把 cfg 传给本次 start，下次从目录页启动又回到写死默认值，段间静音 / 附加语气全丢。
- **r95 修准（R235）**：`r95_verify.py` 原本 10 条正向锚点全绑死 R148 前旧公式（60 秒兜底 / `needBytes > freeBytes` 一刀切 / `estimate()`），恒定报红。按现状换成新公式锚点，公式对拍仍由 `r95_space_parity.py` 独立承担，不镜像复跑；守恒判据一条没删，`ttsSynthesize` 从 UI 合并视图迁到 core 合并视图（R205 后合成在 Producer）。正向 34 / 守恒 8 / 注入 12 全绿。
- **设置页第二批统一行（R239）**：`OverlayProgressRow` / `AutoResumeRow` / `PermEntryRow` / `PermRow` 四个顶层组件内部全收进 `SettingLine`，**签名与调用串一字不动**（r149 / r158 / r161 / r171 / r174 都绑着，改签名会一片假红）。徽标取 `title.take(1)`，不新增参数；权限绿底、`OverlayProgress.hide(c)`、`OVERLAY_SETTING` / `RESUME_SETTING` 日志逐字保留。悬浮行缺「显示在其他应用上层」权限时 `danger = on && !permOk` 整行染警示色，比原来只在灰字副标题里藏一句更看得见。主题是三段选择器不是单行开关：标题行走 `SettingLine("主", "主题", "外观基调") { }` 只借徽标槽对齐，选择器仍独占整行。行间冗余 `Spacer(8/10dp)` 一并删，`SettingLine` 自带 `vertical=9.dp`。
- **陈旧判据连根拔（R239 实证）**：r161 里还绑着 R237 之前的 `buildNotification(this, lastTitle, lastText, ...)` 旧签名，正向恒红；它的「超时通知不收尾」注入锚点 `"lastDone, lastTotal, true"` 同样打不到现行代码、恒不红。两者一起按 R237 现行形态迁移（`lastChapterPct`），**判据一条不放宽**。判「绑死已删旧形态」要正向与注入用例一起查，只改正向会留一个永远不红的假守门。
- **设置页统一行（R238）**：新增 `SettingLine(mark, title, hint, danger, trailing)`，借鉴 Material 3 Preference 的「前导图标 / 主标题 / 副标题 / 尾部控件」层级，用项目调色板自绘、不引图标库。以前每行手搓 `Column(weight)+Text+Spacer(2.dp)+小字`，行高与左右对齐全靠运气；现在统一 32dp 徽标槽、`vertical=9.dp` 行距、副标题可省、`danger` 单独警示色。顶层 composable 必须 `internal`（合并视图会还原成 `private`，判可见性要判磁盘片段、不能判合并视图，否则恒红）。撤「本机数据」「成品文件」两张都标「本机」的碎卡，合并成「文件与数据」（R174 同类碎片回收）；顶部状态卡去掉 `Android X / ABI` 调试串，设备信息仍留在诊断日志（r141 守），不丢信息。r156 绑死旧卡标题的判据按现状迁移、一条没放宽。
- **发版前置核对（R242 更正）**：报「已发 vX」并贴 APK 直链前，`scripts/preflight.py` 六项必须实跑 READY：① CI 有 `setup-java` / `setup-gradle` / `assembleDebug` / `gh-release` / `publish_apk` 五段；② 发版脚本齐（仓库外 `rc_push.py` + 仓内 `release_preflight.py` / `publish_apk.py`）；③ **仓库外** `.secrets/gh_token` 可读；④ api.github.com 可达；⑤ 远端 main 可读（令牌有 contents 权限）；⑥ 工作区干净。**R240/R241 四条错判已作废**：真编译在 Actions 上，本地有无 Android SDK 不影响出包；项目禁 `git push`，发版走 GitHub API 快进 `refs/heads/main`，git remote 本就不该配；APK 由 CI 产出，本地不需预置；令牌在**仓库外** `.secrets/gh_token`，R240 只查了仓内 `.secrets/` 才误报。发版链路：升 `version.txt` → `rc_push.py --push`（建 rc 分支、workflow_dispatch 真编译）→ `--status` 绿 → `--promote` 快进 main → CI 发 Release + 同步直链仓。缺任一不得报「已发」、不升版本、不贴直链；静态守门全绿 ≠ 真编译通过（R202 / R216 两次实证）。
- **首页口径（R276 定稿）**：首页是「贴一段 → 点『合成这段』→ 等几分钟 → 产物进下方留白列表」，**不是粘上就响**，不建书不跳目录页。片段落 `filesDir/clips` 独立一层、不与书交叉、走主播放条；展示名取正文前 10 字、文件名缀短 id 防重名；可对它们勾选 / 长按就地菜单 / 删除 / 导出 / 按当前调音台重做。历史上把它说成贴完就响是措辞过头，已全量废弃；代码以 `ClipLibrary` + `InputReadUi` 为准。
- **R243 落地（本地编辑轮，未发包）**：① 章节成品补齐——旧逻辑只在整本跑完那一刻拼，装新版前已合成的小段永远长不出成品；`AudioOps.ensureChapterAudios` 去重（R265 起改 `hasChapterAudio`，不再按 `audioFor`）、缺块章跳过、尊重 `Prefs.autoMergeChapter`（R272 起收尾走 force=true 不受其限），Producer 收尾与目录页打开书（回溯 + `localTick++` 刷快照）共用这一处。② 裁剪试听关窗还在放——`RefClipDialog` 的取消/关闭/用这一段都先 `PlayQueue.stopPreview()`，按钮按 `previewState` 在「试听选中 / 停止试听」间切换。③ 顶栏书名 `maxLines=1` + `basicMarquee()` 向左滚，不再换行撑宽；抽屉 `title=""` 时整行不渲染（去掉「我的书」废话）；主页任务按钮改 `nav.push(Screen.TASKS)` 通全局任务页，不再打开目录页就地看板；「继续队列」按钮三处全砍（底层 `resumeQueue` 与暂停提示保留，任务看板本体按判断保留：通知点进来与主页入口都靠它）。④ 判据 r129/r231/r236/r237/shell_semantics 按现状迁移，并补「回溯补齐 / 去重 / 关窗停播 / 按钮回潮」硬判据，注入全拦下、一条没放宽。
- **需求池（R243）**：用户历轮提过、暂未全做的，统一记这里；看板「待做」段只挑活跃 3~5 条（R249 起不另起清单文件；R282 起此锁死口径作废：待办可膨胀、不锁条数）。· 界面：按钮图标化（R244 顶栏一组已做，其余页面待做；代码自绘，三角+两竖、几竖加点）、设置页重新设计（R245 已做）、制作设置是否并进全局设置页（R218 判断不并：它带当前书，并过去要重选书更绕）、任务弹窗可完全砍（R243 判断保留看板、只砍费解按钮）、调音弹层上下收小（R214 已做 86%）、悬浮窗 8 字双球/单球与可拖（R214 已做）。· 音频：播放器播放条与锁控借鉴开源、通知两条进度（R237 制作通知已做，播放态待做）、参考音频波形裁剪（R236 已做，手感待真机）、导出格式设置（**R252 已裁定不加设置项**，见下）、下拉刷新（**R253 已裁定不做**，见下）、切分粒度实测、章节表写盘失败根因（等下份真机日志）。· 其它：段级重做（R235 已做）、删段不参与合成（R233 已做）、按章自动拼（R231/R243）、真机长书尾句是否仍缺字。
- **顶栏图标化（R244）**：顶栏 5 处字按钮改代码自绘图标，界面上不再出现「调音 / 任务 / 停止 / 继续」文字。4 枚图标（`MixerIcon` 三推子带滑块圆点、`TasksIcon` 清单+圆点、`StopIcon` 方块、`PlayIcon` 三角）放 `AppChrome.kt`，全部 `Canvas` 自绘、不引图标库，深浅主题走调用方 tint，无障碍文案用 `Modifier.semantics { contentDescription = ... }` 给。**写 `internal` 不写 `private`**：MainActivity 跨文件要调；AppChrome.kt 不在 `ui_sources` 的 PARTS 里，不参与合并视图的 private 还原，所以不影响 r40/r46 逐字节守恒。⚙ 与底部动作条 / 任务弹窗 / 裁剪弹层的字按钮留下一批，拆批推进。判据按符号迁移（r226 / r230 / r214 / hub_semantics），旧字按钮串列进 BAN 防回潮；r153 / r105 / r99 判的是 `MixerUi.kt` 试听停止 `Text("停止", fontSize = 12.sp)`，**不是顶栏**，本轮未动，别顺手改错。
- **设置页重设计（R245）**：借鉴 Material 3 Preference / AOSP Settings 的分组层级。`SettingsSection` 渲染层重画：撤掉 4 枚彩色徽标框（「软件/系统/排查/本机」纯装饰还挤标题行），改**主色小字分组标题 + 一根代码画的 3×15dp 竖条**做锚，卡片圆角仍 16dp、留白 10→12dp、标题与内容间距 10→8dp。顶部状态卡从整块 `PrimarySoft` 染色改成 `palette.card` 中性底 + 1dp 淡边框 + 状态圆点，缺模型时边框转警示色。低频「开发者工具」整块挪到「文件与数据」之后（最后），普通用户不再先撞见排查项。**所有 `SettingsSection("标题", "badge", ...)` 调用签名一字不动**——r156/r171/r174/r238/r239/r73/r152 全按它定位；`badge` 参数不再渲染，只进 `Modifier.semantics { contentDescription = "$title · $badge" }` 供读屏分组。顺手删 R225 脱敏之后过时的「日志可能含书名和正文片段」，改「只记诊断信号，不记书名与正文」，r158 的 POS/ALLOWED_LONG/注入锚同步迁移。r171 里 R243 砍按钮后残留的 `Producer.resumeQueue(c)` 正向判据一并连根拔（改判 `TaskResume.queue(c)` 同源 + BAN 防按钮回潮）。判据补进 r239，不新建脚本。
- **收口发版（R246）**：R243~R245 三批本地攒批（补拼章 / 裁剪停播 / 顶栏图标 / 设置页重设计）到这轮收口，升 1.1.30 走一次 rc 真编译后 promote 发一个包。**发版前先修真 bug**：`preflight._clean_ok` 单次 `git status` 8 秒超时在机器忙时把「干净」误判成脏（实测一次正向 BLOCK、紧接着单独调用又是 READY），与 R242 修 `_net_ok` 的抖动同源；改两次机会 + timeout=20，只有两次都明确跑完且 porcelain 为空才算干净、真脏立即返回 False，判据不放宽，`self_guard` 补防回潮。这类「误报卡住发版」的自检噪声，按「拖慢进度的自检顺手清」当轮修，不攒。
- **rc #207 / #208 两次失败（R246）**：rc #207 是**静态挂项**红——R141/R163/R219 的 `--selftest` 带基线断言，R225 脱敏 + R243 砍按钮 + R244 图标化之后判据没跟着走；顺手把全套正向跑出的 r113/r78/r94 过时判据也按现状迁移（r78 整套 POS 绑死 R78 时代书架形态，换成 R215/R230 现行锚点；11.sp/10.sp 不再当旧形态禁）。rc #208 是**真编译**红——R244 往 `AppChrome.kt` 插图标时以 `internal fun AppDrawerSheet(` 为锚，把它前面原有的 `@Composable` 留在 KDoc 之前：MixerIcon 吃到两个注解报 `annotation is not repeatable`，AppDrawerSheet 反而丢了 `@Composable`；另外 `DrawScope.drawLine` 没有 `(Color, Offset, Offset, Stroke)` 重载，传 `Stroke` 对象报 `None of the following candidates is applicable`。已把注解归位、改成 `strokeWidth = sw, cap = StrokeCap.Round`，并把这两类错静态化进 `r173_verify.py`（FILES 加 `chrome`，判注解相邻 / 禁 `val s = Stroke(` / 两处具名参数齐全，3 条反向注入）。**这两类错静态守门全绿、只有 Gradle 报得出来**，再次印证「静态绿 ≠ 真编译」；发版轮必须走 rc 真编译，不能拿本地全套当结论。 rc #210 / main #211 全绿后 **1.1.30 已发**（APK 18,440,337，直链仓同尺寸）。
- **其余字按钮图标化（R247）**：新建 `UiIcons.kt`（`enum UiIcon` 15 值 + `UiIconGlyph`，全相对坐标 Canvas 自绘、不引图标库、不进资源文件）。**一律「图标 + 文字」并存**，不把「拼成整章 / 转 M4A / 另存 WAV」做成猜谜；文字保留也让 R224 以来按 `ActionChip("xxx"` 前缀定位的守门判据**一条都不用迁**。`ActionChip` 加可选 `icon: UiIcon? = null`（向后兼容所有旧调用），底部动作条 7 枚 / 全选 / 取消 / 裁剪弹层 3 枚 / 任务页 4 枚 / 任务弹窗 2 枚全部接线；抽屉左下角的 `⚙ 设置` 字符换成 R162 就画好的 `GearIcon`。三处判据按现状迁移：`r142` 放行缩进 ≥8 的 Composable lambda 局部辅助函数（`fun ln/ring` 不需要 @Composable，顶层绘制函数照旧拦）、`r230` 判据与注入锚改抽屉独有的 `GearIcon(primary)+Spacer`、`r197` 注入锚换带 icon 的现行调用串；新硬判据补进 `r224_verify.py`（不新建脚本），21 组注入全拦下。`UiIcons.kt` 地图 Entry 的 tag 必须合 `ENTRY_RE`（LAYER/MODULE 字典 + 数字 + 规模 L/M/S/T），`AU8S` 是合法值。
- **播放通知锁控（R248）**：借鉴成熟播放器通知，用**平台内嵌 `android.media.session.MediaSession`**（minSdk 26 全可用、零第三方依赖）接锁屏 / 耳机 / 蓝牙线控与元数据，**不引 Media3**——它要动 `build.gradle` 加 Service，平台方案已覆盖，按「冗余顺手清」从待办删掉。`core/PlaySession.kt` 的 `ensure` 只在 `PlayQueue.attach` 的主线程建一次（非主线程可能无 Looper，`MediaSession` 构造会抛），之后 `notifyNow` 只 `sync` 状态、`clearNotif` 只 `deactivate` 不 `release` 对象。`PlayNotif.show` 的 `MediaStyle` 接 `PlaySession.token`，`.setProgress` 出收听进度条，`.setContentText` 从「正在播放/已暂停」改成 `mmss 位置 / mmss 时长`。平台 `android.media.session.PlaybackState` 与同包 data class 同名，**必须 `import ... as SysPlaybackState`**。`r248_verify.py` 守 20 组反向注入。
- **基线口径（R250，用户纠正）**：用户口里的「基线」**只指他真机正在装的版本**，当前 **1.1.30**；写进看板板头，一句话的事，不是每轮要「维护很久」的东西。工程里的 `.aoci/baseline.json` 是 **AOCI 索引快照**（受管文件哈希，供 `aoci_check` 判漂移），**不叫基线、不进给用户的汇报**——之前每轮写「刷基线 / 137 对象零漂移」是把两件事混成一件、白占篇幅（用户嫌看板废话多）：索引快照仍按 R238 后台当轮 `--refresh`，但回复里不再复述。版本不确定时**干完活顺口问一句**「你现在装的是哪个版本」，不翻历史硬猜、不自己拍板；用户答了就改板头。发版升版本当轮同步板头基线。
- **看板铁律收敛（R251，用户质疑；R258 起此「只留 3 条」口径作废：硬约束 ≤10 条、现 9 条，内容可当轮换，以下为当时裁定记录）**：用户指出「铁律太多、有些根本不是铁律、该放别处」。裁定：看板「铁律」**只留 3 条**——① 用户基线＝真机装的版本、不确定顺口问；② A 自主推进 / B 每轮出包验收两档；③ 报「已发」前 rc 真编译绿、禁 git push、不贴假直链。判据＝**跨轮不可破 + 用户视角必须一眼看到**。其余一律**归 AGENTS 工程口径、看板不复述**：QUICK/守门分档、无用自检连根拔、索引快照 `--refresh`、日志不落用户原文、令牌在仓库外、链接进框图有才发、优先借鉴开源、发版脚本细节。**「无 INTERNET / 零第三方依赖 / 仅 arm64」从看板铁律删除**——R226 已裁定它是工程现状、不是用户指令，写进铁律等于又把现状当用户口径（自相矛盾，顺手清）。AGENTS 里这些条全在，删看板不丢信息；以后新口径先问「够不够格进铁律」，不够就进 AGENTS。
- **导出格式设置裁定（R252）**：用户 R218 模糊问过「制作设置是不是要有导出格式」，查完现有链路**判定是伪需求、不设该项、看板待做删掉**。理由：① 内部成品一律 24k 单声道 WAV，续跑 / 拼接 / 小耳机 / 音色分套全按 WAV 定位，`AudioExport` 注释里早写了「不开放内部路径与格式配置，改了会毁索引与断点」；② 格式不是制作参数，是**导出时的动作**——「另存 WAV」「转 M4A」「导出全部」三处入口已各自显式且都走 `CreateDocument` 让用户自选目录与文件名，批量导出固定 WAV、要 M4A 用章级转码；③ 加「默认导出格式」反而制造矛盾：设置选 M4A 但 `exportAll` 是直拷不经转码器，语义打架。以后别凭空补这个设置项。顺带把设置页「平铺导出」按 R157 之后的真实行为改成「按书名分文件夹 · WAV」，批量格式写死在界面上，用户不用猜。
- **章级导出切 IO（R252 真 bug）**：`ChapterHub.kt` 的两个 `CreateDocument` 回调跑在主线程，原来 `AudioOps.copyTo` / `toM4a` 同步执行；`toM4a` 是 MediaCodec 把整章 PCM 编成 AAC-LC，长章节几十秒，必卡 UI 甚至 ANR。现在 `exportScope.launch` + `withContext(Dispatchers.IO)` 包住转码与写出，转码前给「正在转 M4A…」即时反馈，临时 `.m4a` 与 `pendingSrc`/`tmpCopy` 在协程末尾清；`saveWav` 同样切 IO。不新建守门脚本（R251 不过度自检）：改动面现有 r156/r157/r224/r231/r238/r239 + r173 结构守门全绿，口径写这里与地图 S 段防回潮。
- **目录页批次（R253）**：① **长按章行 = 就地紧凑菜单**（`DropdownMenu`，像桌面右键），只放「勾选此章 / 整章重做」两项，已合成的章才出第二项；与「勾选态底部批量条」是两组不同操作，左侧常驻勾仍在、点一下即选。`menuChapter: Int?` 是唯一锚，`onLong` 只负责打开菜单。② **整章重做**＝按 `hubSegChapters` 取该章全部段 `SegPlan.markSeg` + `AudioLibrary.remove` 撤掉旧整章成品（否则 `ensureChapterAudios` 会因 `audioFor` 非空跳过、小耳机还播旧的）+ `Producer.enqueueOrStart(only=setOf(ch))`；**小块 WAV 不删**，重做失败还能用「拼成整章」恢复。③ **小节重做**原来在 `st.running` 时一口拒绝「先等当前制作收尾」，点了既不排队也没变化；现在**标记即排队不打断当前段**：没在跑就立刻开一轮，正在跑由 `enqueueOrStart` 排进队列，当前这轮收尾 `pumpQueue` 自动接上（Producer 逐段重读 `redo.json`，一行没改）。`SegRow.pendingRedo` 终于接进 `segCellColor/segNumberColor`，标记后格子立刻转琥珀「待重做」，`localTick++` 刷快照。Toast 明确说清排队时机。④ 撤两段顶部废话：「上次读到 第 N 章 · 继续」细条（R230 只在书架撤过、目录页漏网）与「整体 X%」（条内本就写「已完成 X%」，重复）；双进度条照旧常驻。⑤ 勾选条放大：`ActionChip` 最小高 44dp、字号 13sp、图标 18dp、卡片 elevation 12，不再小得不像按钮。⑥ **下拉刷新裁定不做**：制作态本来就由 `Producer.state` 实时推，非制作时切页/重进已能刷；加下拉手势还会和章行长按、纵向滚动抢手势，是净负担，从待做删掉、别再排。⑦ 通知按用户「先不动」原样保留。r134/r150/r153/r171/r224/r218/r219/r74/r230 绑死旧字面量的判据全部按现行锚点迁移，r74 NEG 加「上次读到」禁回潮，一条没放宽。
- **1.1.30 真机日志实证（R257）**：用户附 `听本调试日志_脱敏精简版.md`（源 1.38MB → 33KB，2026-11-11 15:51 / Xiaomi 24117RK2CC / Android 16 / arm64）。**这份「脱敏」是用户在导出侧做的，App 内日志必须自己干净**，不能指望导出时再洗一遍。日志自证三件事：
  - ① **R225 脱敏没兜住**：仍含 616 条 TTS_INPUT 原文续行、书名、书籍 UUID（1681 处）、音色 ID、用户文件 URI。逐点核出 20+ 处漏网：`Producer.TTS_INPUT` 把正文前 50 字 + `v.instruct` 原文 + 音色 ID 直接拼进 detail；FGS_BEGIN / TASK_RESTORE / TASK_AUTO_RESUME / RESUME_* / AUDIO_REGISTER / HUB_ACTION / CHAPTER_RESPLIT 写书名；EXTERNAL_INTENT / BOOK_IMPORT_BEGIN 写 URI；TTS_PARAMS / MIXER_TRIAL_EMPTY 写 speaker；多处写完整 UUID。全部改 `EventLog.safe(...)`（长度+短哈希）或 `id.take(8)`，排查信息不减（段号、字数、指令长度、音色是否变化都在）。新建 `r257_verify.py` 静态守门：禁止 EventLog 调用直接插值用户内容、15 组反向注入全拦下；CI / r135 / 受管根三处挂载。5 处老判据绑死旧串按现状换锚、判据没放宽。
  - ② **CHAPTER_MERGE_ENSURE 是假忙**：n=50、去重模板只有「0 / 338」一种，且每段合成完都跟一条。根因是 `ChapterHub` 的回溯补齐挂在 `LaunchedEffect(book.id, st.done, ...)` 上，制作每完成一段就全量扫 26678 段 + 338 章并写日志，白烧 IO 还把诊断日志刷满、看着像拼接坏了。拆成独立 `LaunchedEffect(book.id, st.running)`，`if (st.running) return@LaunchedEffect`；只在切书 / 任务跑与不跑切换且没在跑时补一次，收尾 Producer 自己也会补，两处幂等。**日志里 4 条 CHAPTER_MERGE_ONE / 成品音频 4 条是真的，不是没拼。**
  - ③ **速度实证，只记不改**：26678 段，相邻段间隔中位 142s / P90 282s / 最大 1218s，ETA 约 1139h；TTS_SKIP 1170 次（seg 1–183 已有 wav，续跑正常）；无崩溃堆栈。结论：慢在**单段合成的固定开销**，不是排队、不是切分逻辑。细切 110/260 是用户定的防丢内容口径，**本轮不动任何切分参数**；「切分粒度实测」待做据此收口删除。进程退出 installPackageLI 67 / SwipeUpClean 11 / MiuiMemoryService 6，FGS WakeLock 30min，无前台服务被杀记录，暂不动。
- **待办归属（R256，用户纠正）**：看板「待做」**只放我自己能推进的代码活**，是我自己的代办清单，**不是用户的**。禁止把「装包 / 真机验收 / 收口后出包验 / 等真机日志 / 用户给反馈」写成待办条目——那等于安排用户什么时候办事。用户明说「我什么时候想出包就跟你说一声，出包以后再给你反馈，没让出包你就继续推进你的」。所以：不主动提出包、不主动催验收、不把节奏节点写成待办；出包由用户触发，反馈只在出包后发生。看板板头写明「待做＝我的开发代办」。R255 看板里「收口后出包验」「等真机日志」两条已按此删除；后者改写成我自己能写的「补本地兜底与诊断埋点」，不等用户任何东西。
- **试听文件归位（R256，B 批第一小块）**：用户口径「调音过程中产生的试听文件播完就丢、目录不用可达」。新建 `core/TrialFiles.kt`：`cacheDir/trial/` 是试听唯一落点，`ClipSynth.synthesize` 从 `File(c.filesDir, outName)` 改到 `TrialFiles.file(c, outName)`（原来 mixer_trial_*.wav / clip_listen.wav 落在 filesDir 根，和 work/audio 混着且永不回收），`MixerUi` 裁剪试听 `ref_clip_trial.wav` 同样改。`PlayQueue.previewFinish()` 在 `previewQueueId == "trial"` 时 sweep，`stopPreview()` 在清空 queueId **之前**记 `wasTrial` 再 sweep（关窗/取消也丢）。**只清 trial 目录**：seg 通道的小块在 work、整章成品在 audio，一个都不碰。r237 里绑死 `File(c.cacheDir, "ref_clip_trial.wav")` 的判据按现状换锚，判据没放宽。三层目录：cacheDir/trial（试听，播完即丢、设置不可达）/ filesDir/work（中间小块，拼成整章后清）/ filesDir/audio（整章成品）。
- **小块与章成品互斥（R270，更正 R269）**：用户口径——**对小块的操作只发生在合成过程中；章一旦拼成整章音频，中间小块就清掉扔了**，已合成的章没有小块可操作，要重做走长按章行的「整章重做」。代码实证：小块区只在 `liveChapter!=null`（即 `bookRunning`）时渲染；`mergeChapterAuto` 拼章后 `deleteAll(parts)` 清块；续跑已拼章走 `covered` 用整章成品复用、不重新长小块；R257 补拼制作中直接跳过。所以**小块和章级成品不会并存**。R269 按「小块还在 + 章成品也在」去修「重做小块后刷新整章成品」是伪场景，且加的 `holdChapters` 会让收尾跳过补拼、**中间小块留着不被清**，正违反用户口径，已连根拔：删除 `ensureChapterAudios` 的 `holdChapters` 参数、Producer 收尾与目录页回溯的 hold 计算、小块重做路径的 `invalidateChapterAudio`。`invalidateChapterAudio`（`chapterOnlyFor` 严格口径连文件删 + `clearDone`、整本.wav 不误删）**只保留给整章重做**：已拼好的章只有成品，不连文件删会被 `list()` 兜底扫描重新登记回来。`redoWholeChapter` 里「小块 WAV 不删、失败可手动拼回」是 R255 之前的过时注释，已订正。r231 判据改守真实模型：按 `onRedo` 块定位禁任何失效调用、`holdChapters` 全禁回潮（注入改 6 组），保留整章重做撤旧成品与不误删整本；r257 的「两个 effect 各一句 + 全量替换」是修真漏放，保留。
- **只按章出成品、不拼整本大文件（R272，撤销 R265/R268/R271 的整本链路）**：用户口径「一本的音频拼起来把手机撑爆，肯定不要拼」。原来 Producer 收尾把全书所有段 `WavMerger.merge` 成 `整本.wav`/`scopeFile` 再 `register`、写整本 `.chapters.json`，而 `mergeChapterAuto` 已逐章出成品并清小块——整本大文件是纯冗余。现收尾只调 `ensureChapterAudios(..., force=true)`：逐章 `merge` → `register` 章级 item → `markDone` → `deleteAll` 清小块；`force` 让收尾**不受** `Prefs.autoMergeChapter` 开关影响，否则关了开关一次制作跑完一个可播文件都没有。`Progress.output`/`TaskResume.output` 改指书名目录；`PRODUCE_DONE` 只记章数/段数。`AudioLibrary` 删 `bookWholeFile`/`scopeFile`/`dropWholeBook`，`AudioExport.collect` 不再收整本；R271「作废旧整本」整条伪需求删。**旧版已落盘的 `整本.wav` 不主动删**，`list()` 兜底扫描与 `isWholeBook`/`chapterOnlyFor`/`hasChapterAudio` 三条兼容口径保留、一条不放宽。r231/r95/r100/r151/r156/r220/r257 共 7 个守门的过时锚点按现状迁移，索引十个 Entry 的 S 段当轮改。
- **（历史留痕，已被 R272 撤销）整章重做后作废旧整本（R271）**：R268 让导出 `collect` 同时收「整章成品」和「整本.wav」。但 R265 起，只做选中章（整章重做）写独立 `scopeFile`、**不覆盖整本.wav**，于是整本里那一章还是旧声音——「导出全部」会把旧整本 + 新章成品两份矛盾音频一起给用户。修法：Producer 收尾 register 新章成品成功后，若 `!only.isNullOrEmpty() && chapters.size > 1`，调 `AudioLibrary.dropWholeBook(c, book.id)` 作废该书整本汇总；**单章书排除**（R265 重做直接更新整本.wav，删了就没成品）。`dropWholeBook` 按 `bookId + isWholeBook` 过滤、走 `removeFile` 连文件删（含 chapters/meta 侧车）：只删登记不删文件会被 `list()` 的兜底恢复扫描重新认回来，等于没作废（R270 同款坑）。完成提示补一句「旧整本已作废，各章成品仍在」；想要连续整本重新制作一次即可，续跑 `covered` 复用章成品不重算。r231 加 5 条守恒判据（含单章书排除、连文件删、isWholeBook 限定）+ 5 组反向注入；7 场景夹具对拍，含「只删登记会被兜底扫描恢复」的反例复现。
- **导出重做（R268）**：R272 后 ①「补收整本.wav」已撤销，`collect` 只给章成品；②③ 同名幂等与结果反馈保留。原三个真问题。① `AudioExport.collect` 只走 `playableItems`（章级），R261 归位后的 `整本.wav` 永远导不出去——现从 `AudioLibrary.list(c)` 补收 `isWholeBook` 且 bookId 匹配的最新一份，落 `整本.wav`；选中章独立成品 scope 是「第 N 章」，不会被误收。② 文件层一直直接 `createDocument`，目录层 `findChildDir` 有「先查再建」但文件层没有，多数 SAF provider 对同名文件自动改名为「xxx (1).wav」，**每点一次导出全部就多堆一份副本**——现加 `findChildFile` 返回 (Uri, size)：同大小跳过（幂等）、大小不同（章重做过）先 `deleteDocument` 再写新，不堆副本。③ 导出只有一闪而过的 Toast：加 `exporting` 置灰按钮 + `exportResult` 留在设置页内可回看，异常用 `runCatching` 兜住避免卡在导出中；标题从「已做好 N 个整章成品」改成固定标题 + 计数进副标题。r156/r157 同名探测锚在目录层与文件层各一处、注入只替换一处会漏放，改全量替换；r169 注入锚、r238 两条判据按现状迁移；r156 补 9 条守恒 + 7 组注入。6 场景夹具对拍（仅整本的书、章+整本不撞名、同名书分层、连点两次不堆副本、重做后重写、失效跳过）。
- **悬浮窗形态仲裁（R267）**：用户实证「一个圈 / 两个圈来回跳」不是两个浮窗——`OverlayProgress` 只有一个 `root`，却被整本制作双球、首页片段合成单球、设置页 0% 预览三方 `update` 抢着刷：预览 0/0 与片段合成 0/1 把制作进度盖掉，片段合成播完的 `hide(c)` 还把制作浮窗整个删掉，要等下一段 emit 才回来。现加顶层 `enum class OverlaySource { PREVIEW, CLIP, PRODUCE }`（ordinal 即优先级）与 `Frame` 当前真实帧：`update` 里低来源不覆盖高来源，只按 `frame` 重绘一次（顺带让制作中拨开开关立刻显示制作球，不必等 emit）；双球只在 `PRODUCE && chapterPctNow >= 0f`。新增 `hide(c, source)` 条件收起，低来源不许删高来源浮窗；停止/完成/服务销毁/关开关仍走无参 `hide(c)` 强制全清。三个调用点分别标 PRODUCE / CLIP / PREVIEW，开关只管 `enabled`。r161/r214/r215/r217/r230 判据按现状迁移；顺手连根拔两处绑死旧形态的过时判据：`r142` 的 `onTap: () -> Unit`（R264 起收 `(SegRow)`）、`r219` 的 `generated || making`（R264 起 `if (r.index == liveChapter)`）。守恒一条没放宽；5 场景夹具对拍。
- **目录页与悬浮窗批次（R255）**：① **长按菜单与左勾/底部条不抢操作**：`menuChapter != null` 时底部 `MultiSelectBar` 收起（`if (multiMode && menuChapter == null)`），已勾选的章不清、菜单一关条子回来；返回键新增 `BackHandler(enabled = menuChapter != null)` 且注册在 multiMode 之后优先消费，原来菜单弹着按返回会直接退勾选态甚至离开这本书。② 顶部第一条进度条上的「已完成 X%」撤掉（连同 R253 已撤的「整体 X%」「上次读到」），两条进度条纯色常驻、条高不动。③ **悬浮窗上球**：用户报「上球不更新、下球正常」。静态没定位到唯一那一行（done/total 与通知 bar_book 同源），所以做三层实证修：浮窗刷新从 `emit` 的 1.5s 通知节流里拆出来（`ProduceService.overlay`，合成主循环每段必刷）、`RingView` 每次 `postInvalidate()` 强制重绘、写 `OVERLAY_PCT` 诊断日志（只记 top/bottom/done/total 纯数值，不落书名）。通知仍按 stage/1.5s 节流，互不拖累；真机日志能分清是上游没传还是 View 没画。④ **拼成整章后清中间小块**：`mergeChapterAuto` 原来刻意「不删小块、不写 chapter_done、不 forget」（R231 保续跑），用户本轮明确要求清。顺序锁死：先 `AudioTree.markDone(work, ch, dst)` → 失败就保留小块并说实话 → 再 `deleteAll` + `AudioTree.forget`。不写章状态就删会让 Producer 续跑因 `already/covered` 都不命中而把整章重做。r231 守门那条「自动拼章不许动小块」是过时约束，改判成更严的顺序/兜底判据，不是放宽。
- **音频文件归位（R259 落地）**：三层定死——`cacheDir/trial`（试听，播完/关窗即丢、设置不可达）/ `work/{bookId}`（中间小块，拼章后清）/ `audio/{书名}`（整章成品）。章成品名 `NNN_目录里那章的名字.wav`（如 `003_第三章 山雨欲来.wav`），章名取 `ChapterPlan` → 老书 `work/chapters.json` → 序号兜底；`AudioOps` 两处写出与 `AudioExport.collect` 同用 `AudioLibrary.chapterFile/safeName`。同名书另开带短 id 的目录，防两本的 `001_第一章.wav` 互相覆盖。`.meta.json/.chapters.json` 随成品同目录，`list()` 扫顶层 + 一层书名子目录，顶层旧 `chapter_*.wav` 兼容解析保留、旧文件不迁移（索引存绝对路径）。`AudioTree` 不再靠 `startsWith("chapter_")` 认成品，改按单章登记判定。设置页「文件与数据」两行「查看」走 `LocalFilesDialog`（列两层 WAV、点文件 SAF 另存；私有目录第三方进不去，不甩路径）。**「裁剪源全段 WAV 归位」是伪需求**：`AudioDecode.decode` 全程在内存，裁剪试听段已走 trial 且用完即删，待办直接删。r157 旧命名判据按现状迁移、判据没放宽。**R260 补一个自己撞出来的真 bug**：新命名让两本同名书的同章成品 `id`（nameWithoutExtension）完全相同，`register` 的 `removeAll { it.id == item.id }` 会清掉另一本书的记录，`remove` 的 `filter { it.id != id }` 会连坐删两条。改成按 `file.absolutePath` 去重/精确过滤，新增 `removeFile(c, file)`；`AudioOps.deleteMerged` 与目录页「整章重做」改走它。`AudioLibrary.remove(c, it.id)` 在 Pipeline 删书处保留（r138 绑定签名），调用串不动。伴生 `.meta.json/.chapters.json` 按 `f.nameWithoutExtension` 在成品实际目录清。
- **发版轮判据按现状迁移（R262）**：收口前跑全套 158 项静态守门，8 项红全是「绑死已删旧形态」而非功能退化，逐条按语义迁移、判据不放宽：`r116` F-31 删失败/移记录两条改抓 `removeAt`（R260 把 remove 拆薄，本体只定位文件）；`r142` 绘制函数检查的 14 行窗口会越过下一个顶层 `fun`，把 `PlayerCover` 的 `Canvas(` 算到前面 `mmss()` 头上，截到下一声明为止（真正的绘制函数丢注解仍拦）；`r151` 两条 POS 与注入锚换成 R262 现行 `LaunchedEffect(book.id, st.running, …)` / `remember(…, hubSegChapters)`，并补一条「展开态刷新退化成每段全扫」注入；`r175` 侧车串换 `listOf("${stem}…")`（R260 按实际目录清）；`r225` 快照段换 `redact` 别名后，原字面量判据恒不命中等于放宽，改按裸插值正则 + `redact(` 调用数判，注入锚同步。复跑全套 158 项零失败；但 **rc #212 真编译抓到 `liveChapters` 未定义**（R262 半成品符号，判据跟着迁过去等于把 bug 写进守门），已删该符号、`r150/r151` 锚一并回到 `hubSegChapters` 形态——静态全绿仍不等于真编译。`version.txt` 升 1.1.31，README/STATUS/ROADMAP 同步。
- **（历史留痕，R272 后整本.wav 不再生成）整本成品归位 + 落盘原文补扫（R261）**：`Producer` 整本合成原来写 `audio/<UUID>.wav` 平铺顶层，改走 `AudioLibrary.bookWholeFile(c, book.id, book.title)` → `audio/<书名>/整本.wav`；空间预检两行、`WavMerger.merge` 顺序、`chapters.json` 随同目录都未动（r95 判据保持）。全量扫 EventLog 调用点补 R257 漏网：模型导入/删除/残件/疑似文件名、自定义预设名、两处 `t.message`、试听失败异常 message、以及 **`UiErrors.report` 的 `t.message`**（一处收口，覆盖 storage/playback/notif 全部）。诊断快照里 `t.title` / `p.message` / 两处 `s.label` 改脱敏；`snapshotBlock` 内有同名局部 `safe(lambda)`，故加 `redact()` 别名，不写 `EventLog.` 自引用（R187 闸门）。`VoiceCache` 的 `_voice_<hash>` 目录名、`ClipSynth` 的固定 label 属内部信号，保留。
- **目录页（R262 旧态已由 R264 作废）**：R262 的 `liveChapters` / `expandedChapter`「点章名展开、非 live 章收起」整套删掉。**R264 起制作中的那一章恒定展开**：`liveChapter = if (bookRunning) makingChapter else null` 是派生值，没有展开/收起状态、没有切换按钮、没有进出动画；`HUB_CH_EXPAND` 埋点、`ChevronIcon`、`AnimatedVisibility` 包裹、`onTap` 参数全删；没在制作的章连 `seg_body` item 都不生成；读盘 effect key 改 `if (bookRunning) st.done else 0`，收工时不再每段全量读 2 万+ 段。7 个绑死旧形态的守门（hub_semantics / r91 / r148 / r150 / r151 / r155 / r218）按新语义迁移、旧形态列入 BAN、注入全拦下，真编译与守恒判据不放宽。播放器（R262）：`PlayerSheet` 换代码自绘「摊开的书 + 声波」封面 `PlayerCover`（`MiniPlayBar` 复用 44dp 同款），删「正在播放/已暂停」状态废话改 mm:ss + 剩余；新增听书刚需 **-15s / +30s 跳转** 与 **睡眠定时**（15/30/60 分 / 本章结束，到点只 `pause()` 不 `release()`，本章结束靠 `trackId` 换轨判定）；常驻条进度条挪卡片底边、时间改 mm:ss。倍速五档、选章列表、关闭按钮、三枚图标、`SPEED_STEPS` / `speedLabel` / `nextSpeed` 调用串全部保留，r133/r147/r154/r248 判据零迁移。草图是仓库外代码画的 `播放器草图.html`（HTML，不进 APK 资源）。
- **索引/地图接得上（R238，R279 起按官方）**：改了受管文件当轮让受影响 Entry 的 S/F 反映新事实，跑官方 `aoci verify` / `aoci check` 必绿；Baseline 由官方维护，不跑 `aoci_check.py --refresh` 重烧地图，不留孤儿登记、不让漂移。新增资源若命中 `exclude_dirs`（如 `app/src/main/res`）**不硬塞索引**，并进现有受管文件的 S 段即可——R237 新增通知布局时先塞进去报「索引对象不存在 / 未在受管范围」，撤回登记并把布局描述并入 `ProduceService.kt` Entry 才干净。地图是每轮进门第一份，接不上等于后面全部判断失据；这条优先于「少写点文档」。
- **通知两条进度（R237）**：制作常驻通知改自定义 `RemoteViews`（`res/layout/notif_two_bars.xml`），上条整本 `done/total`、下条当前章 `chapterPct`，**一个文字控件都不放**——用户明确不要「正在把文字读成声音 / 整本 X% 本章 Y%」这类解释。用 `DecoratedCustomViewStyle` + `setCustomContentView` / `setCustomBigContentView`，停止按钮和锁屏 PUBLIC 兜底照旧（公共版仍不含书名，R176 隐私口径不破）。`chapterPct` 本来就从 `Producer.emit` 经 `update()` 传给悬浮窗下球，只是通知没用；现在存一份 `lastChapterPct` 即可，**Producer 链路一行不动、不引 Media3**。
- **裁剪试听（R237）**：`RefClipDialog` 多一枚「试听选中」，按当前拖出的区间 `AudioDecode.clipWav` 裁出临时 wav 写进 `cacheDir`，走 `PlayQueue` 的 `"trial"` preview 通道播放——不占整本书的播放控制通知、不落 `VoiceRef`，拖着把手可以反复听。试听与「用这一段」共用同一套区间换算，半秒下限 / 30 秒上限仍由 clipWav 把守。
- **波形裁剪（R236）**：参考音频导入改成「先解码拿全段波形 → 弹框拖选 → 才落盘」。交互借鉴 **Ringdroid**（AOSP 开源铃声编辑器）的波形 + 两端把手，但用 Compose `Canvas` 自绘，不引波形库。三条硬口径：① **30 秒硬闸挪到裁剪落盘**，预览解码只留 10 分钟防 OOM 兜底——用户手里多是整首 mp3，先看到全段才能拖选一小段；② `AudioDecode.clipWav()` 对已解码 WAV 做纯字节裁剪、不二次解码，半秒下限与 30 秒上限都在这里卡，输出仍是 24kHz/单声道/16-bit；③ 峰值数组按动态窗口封顶 **400 根柱**，3 分钟音频不会往 Canvas 塞上万次 `drawLine`。默认选前 10 秒（参考音频公开口径 3~10 秒最稳）。弹层是 UI 片段里的顶层 composable，必须 `internal` 不能 `private`（R202/R206）。日志只记时长与字节数，不落用户文件路径。
- **内嵌 SDK 选型（R234）**：分两层，都不加 INTERNET 权限、都能离线跑。
  · **音频解码 / 裁剪**：用 Android 系统内置 SDK `MediaExtractor` + `MediaCodec`（硬解码），
    路线借鉴 Ringdroid（AOSP 开源铃声编辑器）：平台解码出 PCM → 画波形 → 拖选区间 → 导出
    24kHz / 单声道 / 16-bit WAV。`core/AudioDecode.kt` 已落地，参考音频从此吃 mp3 / m4a /
    aac / ogg，不再只收未压缩 WAV；波形峰值数组按 20ms 窗产出，裁剪 UI 下一批接。
    **不引 FFmpeg / Amplituda / jitpack**：这类波形库自带 FFmpeg native so，会撑大包、
    与仅 arm64 的引擎库叠一起且引入构建期外部仓库依赖；波形从已解码 PCM 直接算即可。
  · **播放器 / 通知（R228）**：引 AndroidX **Media3** 官方内嵌库
    `androidx.media3:media3-exoplayer` + `androidx.media3:media3-session`，
    按官方标准范式用 `MediaSessionService` 托管 ExoPlayer，通知栏 / 锁屏 / 耳机线控 /
    音频焦点 / 前台服务由框架接管；本地文件播放不需要 INTERNET 权限。
    动 build.gradle + 新增 Service 属单独一批，收口必须走 rc 真编译（沙盒无 SDK，
    Media3 API 误用静态守门抓不到）。引依赖前不提前写进 gradle。
- **删段记账（R233）**：用户手工删掉的音频小块按 `bookId` 单独记进 `work/{bookId}/dropped.json`（带段文本前 200 字的短哈希，段号重排时对不上就不跳过，宁可重做也不丢内容）。合成循环、pending 计数、整章自动拼接三处都排除这些段；删书时 `BookStore.purge` 整棵 work 目录删掉，记账自然清。旧提示「下次续跑会按原顺序重新生成」作废。
- **抽屉/首页布局（R230）**：主页右上角不再放 ＋（导入）与齿轮（设置）；导入挪到抽屉顶部**独占一排**、设置收进抽屉**左下角**，主页顶栏右侧只留「调音 / 任务」两枚（任务无活置灰）。书卡片置顶/删除改为**长按书名**弹菜单，撤「上次读到 第 N 章」、保留制作进度条。悬浮窗开关回设置页，并修「打开开关不理」：权限齐了立刻挂 0% 单球预览，制作时原地更新、停止/完成照旧收起（旧根因是只有制作中 update 才创建浮窗）。
- **日志脱敏（R225）**：书名 / 书正文 / 音色描述 / 自填附加语气**原文一律不落盘**，统一走 `EventLog.safe()` 只留 `len + 短哈希`；书架/任务事件改用 `bookId` 内部 id、字数、章号、字节数，诊断信息不减反增。`ProduceConfig.summary()` 仍给 UI 用，日志一律 `summarySafe()`；诊断包抬头、运行快照、导出摘要同步收口。播放通知的曲目标题仍显示书名（UI 需要，不属日志），R228 重设计通知时一并处理。
- **R225 新批需求（用户授权自主排期）**：R231 已做「这一章小块齐了自动拼这一章的成品 + 小耳机播成品」；删过的小块不参与合成/拼接仍待做（现状删了下次重做，与需求相反）；R227 参考音频裁剪（现只吃未压缩 WAV / 上限 30s / 无 mp3 与波形；目标：平台解码 mp3·m4a → 24kHz 单声道 16-bit WAV + 波形拖选）；R228 播放器与通知两条进度；R229 设置页重设计。
- **技术选型的归属（R226 更正）**：「无 INTERNET 权限 / 零第三方依赖 / 仅 arm64」是项目一路走下来的**工程现状**，不是用户下达的指令，不该拿它当用户口径去替用户拍板。引不引 Media3 / FFmpeg / 平台解码，是每一批按需要自己权衡的事，可随需要调整；当前 R227 暂用平台 `MediaExtractor/MediaCodec` 是工程选择，不是禁令。
- **协作节奏（R224 起）**：回到自主推进。用户只在「装包真机反馈 / 体验别扭 / 走偏拉回」三处出现；一批反馈我自己拆成几轮、自己排期，沙盒攒批、不逐步 commit/push/跑 CI，攒到该出包或发版才动 GitHub。用户明说「说的也不一定对」，故每条反馈先核现状再落，不照单全收。
- **R224 反馈拆批（R230 收口）**：批次1 选中底部菜单按钮化、左滑抽屉仅主页；批次2 目录页三按钮并入书名同排右上角、置顶/删除改长按书名、去掉「上次读到多少章」、保留制作进度条；批次3 悬浮窗开关挪回设置页并修「不理」，设置入口最终落在抽屉左下角（不再是顶栏）。
- **默认音色身份（R223）**：采样参数不决定性别/年龄（只管抽签随机度与长度上限），网上分享的「青年女声」是 instruct 描述词。现有 9 项采样默认 （0.9/50/1.0/1.05/0.9/50/1.0/2048/42）已是官方评测与社区常用起点，**锁死不动**。默认身份改为 `Prefs.DEFAULT_VOICE` 青年女声（适合长篇有声书）。`voiceDescription` 用 `contains` 区分「从没设置」与「显式清空」：清空 → 回落 `DEFAULT_IDENTITY` 中性串、`voiceStamp` 回旧值 → VoiceCache 认领回升级前那套，**旧进度不丢**。换默认音色会使旧套归档（不删），调音台点「清空」即可切回。
- **采样参数认知（R222）**：9 项（seed / maxNewTokens / temperature / topK / topP / repetitionPenalty / 副 talker 三项）**不进 prompt**，走 `qt_tts_params` 数值字段：UI → `Prefs.setTtsKnobs` → `NativeEngine.ttsSynthesize` 拆 9 个 JNI 参 → C++ `qt_synthesize(q,&tp,&audio)`，每段写 `TTS_PARAMS_APPLIED` 日志。三模式都生效；任务 start 时读一次，运行中改不影响正在跑的任务；试听同。改 knobs → `voiceStamp` 变 → 音色套切换（旧套归档不删、当前套从头算）。调音台仅 VOICE_DESIGN 显示 9 项，另两模式「沿用全局设置」= 同一份全局值，不是失效。
- **「怎么念」已砍（R221）**：制作设置页只剩 ①做什么 / ②怎么跑；朗读档位 / 语速 / 情绪三档写死预设彻底不再进 `instruct`，风格层只认自填 `extraInstruct`。三个枚举仅为读旧任务 JSON 保留、不生效。**`audioStamp()` 公式锁死不许动** —— 指纹一变，旧套分段会归档、已做进度归零。调音台 diff / 内置预设 / 快照 / 参数目录同步零回潮。
- **切分与目录页口径（R219）**：内容完整优先于快，段切分维持细切 110/260，不许回到 240/420 大块；SPLIT_EPOCH 必须进音色缓存键与续跑判据，旧分段绝不按段号复用。目录页顶栏只放停止/继续、任务、制作设置三枚右对齐按钮；两条进度条加高，整本条内写「已完成 X%」、整体百分比在条左，本章只靠条走量；点进度条滚到正在制作的章；没小段的章点章名不展开；章名左边常驻勾（不靠长按）；展开头部非多选态不渲染，不留白。
- **目录页口径（R218）**：顶栏写书名不写「目录」；重新分章收进制作设置页做次要入口，不占顶栏；制作设置页只管「怎么念 / 怎么跑」，不塞拆章勾选与调音台入口，且必须带当前书上下文，不并进全局设置页；章行右侧小眼睛（看原文）+ 小耳机（听成品），点章名展开小段；整本/本章两条进度条常驻，展开用动效；不许再写「共 N 小段 / 已合成 M 小段」。
- **悬浮窗口径（R217 + R230）**：球上只写百分比数值，0% 写 0、90% 写 90，右侧补小号 %；绝不写段号 / 书名 / 解释。开关在**设置页**（首页不再放），打开即挂 0% 预览球，无系统 overlay 权限要引导授权，不许静默不显示。
- **UI 分家（R206）**：MainActivity.kt 的顶层 `private` 是文件私有，拆出去的片段磁盘上必须是 `internal`；历史守门与 git HEAD 守恒认的是 `ui_sources` 合并视图（仍为 `private`），两者靠双向变换对齐。遍历真实 .kt 文件的脚本必须用 `ui_sources.view_for`，不许直读磁盘判 private 字面量。
- **纯结构轮发包判据（R207）**：老树盘根式的拆文件轮，功能零改动、用户装上看不到差别，中途不发包也不跑 CI；等各组全部搬完、合并视图逐字节一致、QUICK 绿，再走一次 rc 真编译，过了才升一个修订号出包，让用户装一次确认「没拆坏」。真机反馈 / 体验别扭 / 走偏拉回三处之外，节奏由 AI 自定。
- **真机日志收口（R209）**：用户贴诊断日志时，先按「没拆坏 / 真问题 / 界面建议」三类分开；日志里用粘贴建书留下的短句常是体验建议，认下来但不急着改，等后续批次一起收。不碰界面布局的行为类修复可当轮收口，纯结构轮不发包。
- **真编译错静态化（R216）**：rc 分支真编译报的错（如 Unresolved reference），修完必须给对应静态守门补一条全仓扫描 + 反向注入，让同一类错下次本地就红，不靠 CI 兜底；本地静态全绿不等于编译过，发版轮仍必须跑 rc 真编译。
- **合成真源唯一（R215）**：「一段文字 → 一个 wav」只由 ClipSynth 负责，首页片段合成与调音台试听都调它，不许各写一份 mode 分支 / 模型路径 / 失败判定；真源搬出 UI 片段时，老守门经 clip_view.merged() 跟着换视图，判据不放宽。
- **主动推进（R214）**：用户说「自己看着做」时，不再把建议只记成待办等确认；能从上下文推断的口径自己拍板并落地，落地不了的那块明确写成「我自己接着做」，不用用户再给一轮。界面批次不必攒齐才动手。
- **静默失效与连带回归（R213）**：诊断日志里「代码记了 A、快照却是非 A」优先怀疑写入被底层校验静默拒掉，不要只改写入点就收工；必须过一遍「修复后用户主动操作会不会被新状态卡住」，把交互对拍写进守门。
- **验证与发版解耦（R203）**：重构 / 守门 / 文档轮只跑 rc 分支真编译验证，**不升版本、不 `--promote`、不产出 Release APK**；版本号跟发版走，不再每轮 +1。攒到一批改动收口，或出现用户可感知的改动（界面布局 / 功能 / 修会崩会丢数据的 bug）才升一个修订号发一次包。真编译 ≠ 打包：R202 的漏 import 静态守门抓不到、只有真编译抓得到，所以验证轮仍必须跑 rc 真编译，只是不 promote。版本闸门由脚本按 GITHUB_REF 自行跳过，不改 workflow 结构。
- 宿主 MCP 配置（`.mcp.json`、`opencode.json`、`.codex/`、`.claude/`）含本机绝对路径，**不提交**。
- 密钥、安装包、模型权重、令牌不进仓库，也不进沙盒产物目录。

## AOCI 适配说明

官方 `aoci` CLI v0.1.1 已接入本仓，`aoci verify` / `aoci check` 是治理权威：
`cognition-volumes/v1` + `object-fras-v2` 三卷、Managed Scope 的
`index` / `exclude` 角色、Baseline 指纹与事务记录都由官方维护。
历史回执、真机截图、构建产物、退役验证脚本等负空间，已用官方
`scope rule add --action exclude` 正式登记在 `.aoci/config.json` 的
`managed_scope.rules`，不再靠自写清单隐式挡；`build.gradle.kts` 与
`app/build.gradle.kts` 仍在 index 角色，零误伤，攒下的业务条目一条未删。
三份卷资产（`aoci.txt` / `aoci.meta.txt` / `aoci.code.txt`）按 volumes-v1
不占 code 卷 Entry，其元条目原文以注释形式保留在 `aoci.code.txt` 开头。

`scripts/aoci_check.py` 退为辅助格式守门（标签字典、FRAS 四段、S 配额、
R 引用、私有拓扑、负空间规则不被摘），不替代官方校验；CI 仍挂它的
`--selftest`。语义一律由模型基于源码证据创作，禁止从路径、文件名、
AST、符号表或模板反推 F/R/A/S。

`.aoci/config.json` 与 `.aoci/baseline.json` 按官方结构写，本仓**提交**它们
（官方通常不提交），目的是让 CI 跨机器验证漂移。改完受管文件后在支持
CAS 的投影副本上重立 Baseline，再回写；只读的 verify/check 直接跑：

```
aoci --repo . verify
aoci --repo . check
python3 scripts/aoci_check.py --selftest
```

本仓所在持久目录不支持 CAS 原子写（`renameat2` / `link` 均不可用），
官方写命令需在 `/tmp` 这类支持 CAS 的投影副本上完成，再把治理状态回写。

## 协作与验证节奏（R232 重新设计）

- **两档沟通模式**：A 自主推进（默认）——自己拆批、沙盒攒批、不逐步同步，**不主动出包**；出包只由用户一句话触发，用户没让就继续写自己的代码代办，反馈只发生在出包之后。B 验收轮——用户一句话切换，每轮提问题 → 处理完 → 每轮出安装包。当前档位写进看板板头，切换由用户触发，不自己改档。
- **验证分三档，不把时间花在跑脚本上**：改码轮只跑 `scripts/r135_verify.py --selftest` 的 QUICK 6 项 + 改动面直接相关的 1~3 个守门，**不本地重跑全套 138 步**；收口/发版轮走 rc 分支真编译（编译错静态守门抓不到，不能省）；全套由 CI 承担。项目本来就有「改码轮只跑这 6 项，全套留到发版轮」的规则，每轮跑全套是后来自己养成的习惯，已连根拔掉。
- **无用自检连根拔**：命中任一即清——① 判据已被更新守门完全覆盖、只剩自我对拍；② 只镜像复跑别的脚本、自己不判；③ 绑死已删除的旧形态（正向恒真 / 注入恒不红）。清理动作一次做全：删脚本文件 + `r135` 现行清单 + CI 步骤 + `.aoci/config.json` 受管根 + 基线 + 地图 Entry，不留孤儿登记。删前先查谁 import 它的符号表；**不许借清理之名放宽真编译、守恒判据，或删掉仍被引用的共享判据表**。
- **过时约束顺手删改、冗余顺手清**：每轮进门读看板与地图，发现与当前代码矛盾的口径当轮改掉、不攒批；重复口径合并、一次性脚本归档，不新建文件。改完只刷受影响 Entry，不重复维护。
- **功能实现优先借鉴开源项目**：交互、播放器、音频处理等先找成熟开源做法再结合本项目适配；引不引依赖按每批需要自己权衡，不把工程现状说成用户指令。

- **R263 收口发版**：1.1.30 之后 R247~R262 攒批一次性收口，本地提交 `406fbd5`（version.txt 1.1.31）；全套静态 157/157 绿（9 项并发超时单跑全过）、preflight 六项 READY、rc #214 真编译绿、main #215 五步全绿（assembleDebug / APK 闸门 / 上传 / Release / 直链同步）。Release v1.1.31 资产与 `ting-ben-apk` 直链仓均 18,489,474 字节。沙盒出口对 github.com 下载 403 是已知链路限制，api.github.com 与 CI 均通，不影响用户侧下载。
- **R264 目录页制作章恒定展开**：删 `expandedChapter` 状态、点章名切换、`liveChapters`、`HUB_CH_EXPAND`、`ChevronIcon`、`AnimatedVisibility` 进出动画与 5 个 import；`liveChapter = if (bookRunning) makingChapter else null` 是派生值，小段区只给这一章、常驻渲染，收工即无展开体；7 个绑死旧形态的守门按新语义迁移、旧串列 BAN。
- **（历史留痕，R272 后整本登记不再产生，三条函数仅兼容旧盘）R265 整本/章级成品分流**：整本收尾 `register` 把 `整本.wav` 登记成覆盖全部章，旧 `audioFor` 去重让 338 章补拼全被跳过（日志 50 次「补拼 0 章」的真因之一）。现 `hasChapterAudio` 宽松口径供补拼（多章整本不算、单章整本算，不重复拼），`chapterOnlyFor` 严格口径供重做/删除（绝不删整本），`isWholeBook` 按 scope 或 `整本.wav` 文件名判定。Producer 收尾 `outFile` 按 `only.isNullOrEmpty() || chapters.size == 1` 走整本文件，否则 `scopeFile`（`第 N 章.wav`）——修掉「整章重做把整本成品覆盖成那一章」的真 bug；单章书重做直接更新整本，不留两份不同声音。r231 判据与 28 组注入按现状迁移。
- **R266 小块重做不再排队**：用户实证「点重做后这一章就不合成了、像杀掉进程」。根因是 `enqueueOrStart` 把重做排成整章新任务，要等整本跑完才由队列泵接上，且 `finally` 里只要 stage≠完成就 `queuePaused=true`，队列永久睡着。现制作中 `onRedo` / `redoWholeChapter` 只 `markSeg`，`Producer.run` 每段主 `emit` 后取 `SegPlan.pending(work)` 里 `j <= i` 的 `backlog`，逐段 `ttsSynthesize` 覆盖同一 wav（`handledRedo` 防本轮重复、失败保留旧 wav 与标记、不追加 wavs 不打断主循环），成功 `clearRedo`；前后各一次 `emit` 切 stage。颜色不靠 StateFlow 合并：ChapterHub 新增 `redoKeys`，心跳每秒只读 `redo.json`（几 KB），`chapterSegs` 用它 `copy(pendingRedo=…)`，转黄/转绿都不等整本做完。r235 / r150 判据按现状迁移，28+20 组注入全拦下。

- **恒红守门按现状改准（R277）**：`r114_verify.py` 正向 6 条恒红（字号阶梯仍守 12sp 下限、R221 已砍的「② 怎么念」、R163 已合并的三列 `HubTitles`、R221 已删的朗读档位），而 CI 挂的是 `--selftest`——正向恒红意味着注入后 `check()` 永远非空，22 组注入全部「被拦下」是**假绿**。按现行形态改准：阶梯扩到全仓实测 `{10,11,12,13,14,15,16,17,18,20}`、下限 10（9sp 与 12.5sp 两条注入仍拦）、分区锚换 `② 怎么跑 / ③ 拼成这一章`、守恒换「段间静音 / 分段失败重做」与 `liveChapter` 派生值，旧串一律 BAN；注入补到 28 例，正向 0 失败后 28/28 才真拦下。踩到一个坑：注入锚不能用 `private fun ProduceScreen(`（合并视图里它紧跟 `@Composable`，插一行会劈开 `ui_sources.PARTS` 的 start 锚，`write_text` 整树写回被跳过、注入假生效），改用函数体内 `OptionRow("段间静音"` 作锚。`r129` 里镜像 r114 的两条（基准树只建一次 / 写回原文）仍成立、不删；焦点/主题/指令/提交等真守恒一条没放宽。

- **日志噪音与播放诊断（R278，读 1.1.31 真机摘要）**：① 摘要显示事件日志 266554 / 详细 1010942 字节，最近一轮里续跑恢复的前 300+ 段逐条打「段 N/26678 已有音频，跳过」，把详细日志撑满。Producer 合成循环改为按 500 段窗口汇总 `TTS_SKIP`（带 wav/chapter 计数与首尾段号），`flushSkip()` 在循环 `finally` 里再打一次尾巴，覆盖 return/break/异常；「已手工删除」是用户行为证据仍逐条保留。r157 的逐条字面量判据按现状迁移成汇总判据 + 旧串 BAN，注入补到 29 例；踩到「TTS_SKIP 有逐条/汇总两处、只摘第一处判据仍绿」的坑，该注入改全量替换。焦点/TTS_INPUT 只在真合成段等真守恒一条没放宽。② 摘要里三条 `UI_ERROR` 播放报错只有固定文案，分不清文件被清、格式坏还是旧路径引用。PlayQueue 在 `open`/`previewOpen` 缺文件、`setOnErrorListener`（保留 what/extra）、`setDataSource` 抛异常四处补 `PIPELINE + WARN` 纯数值诊断（队列类别/段号/存在/长度/异常类名），不落路径、书名、正文，也不进 `allErrors()` 的 ERROR 列表。摘要另证实：进程退出全是 SwipeUpClean / MIUI 内存管理强杀 / 装包，不是 App 自杀；`SPLIT_STATS` 逐字无损（标点优先切分）。「队列 1 项 · 暂停=true」且任务 stopped 的自动恢复语义只记待办、本轮不动，不猜。

## 收口清单

- 改了受管文件 → 跑官方 `aoci verify` / `aoci check`，并让受影响 Entry 的 `S` / `F` 反映新事实
- 收口前用 `--lookup` 复查本轮主题：查不到就补索引，官方 check 绿、Baseline 无漂移才收尾
- 新增守门脚本 → 登记进 `scripts/r135_verify.py` 的现行清单与 CI
- 升版本 → `version.txt`、Gradle、README / STATUS / ROADMAP、Release 标签一次对齐
- 发版 → 真编译、签名证书指纹、7 个 arm64 `.so`、包内无开发者资料，缺一不发

<!-- aoci:begin -->
## AOCI Repository Cognition

AOCI maintains a stable, versioned, incrementally updatable repository-level cognition layer so models can reuse their understanding of this system across tasks and sessions. `aoci.txt` is the Root Manifest; `aoci.meta.txt` holds the tag dictionary and FRAS discipline; `aoci.code.txt` assigns one independent Entry to every managed object. Together they form the Whole-Index: a high-density view of responsibilities, strong relationships, public contracts, and non-obvious constraints.

At the start of every new Agent Run, read `aoci.txt`, then `aoci.meta.txt`, then `aoci.code.txt` before opening source. Reuse that cognition while it remains reliable; after host context compaction, treat prior model cognition as unreliable and reload the Whole-Index from these assets. Do not substitute historical memory, `dev/` archives, or landing reports for current repository-bound cognition.

Entry semantics must come from the model's understanding of actual evidence. Never derive, prefill, assemble, or rewrite index semantics solely from paths, filenames, extensions, an AST, symbol lists, dependency scans, regular expressions, fixed templates, or rule engines. `R` names strong relationships as `code:<repository-relative-path>`; `S` records only non-inferable, error-preventing constraints. Scale tags are machine facts derived from line counts; importance tags reflect architectural weight, not file size.

When managed objects change, close the task by refreshing the governed baseline and updating only the affected Entries. After alignment, do not repeat maintenance. Purely read-only questions need no maintenance. AOCI is not an AI Agent, RAG replacement, AST replacement, LSP replacement, daemon, or cloud service: agents still inspect real source and tests. Directory section headers are historical coordinates, not runtime roots; readers logically relocate them, and a public repository must keep hostnames, user names, and secrets out of them.

This repository uses the official `aoci` CLI v0.1.1 as the governance authority; `scripts/aoci_check.py` is an auxiliary format guard only. If an official `aoci` binary is installed, its `verify` and `check` remain authoritative and the live Guide and tool schema override this block.
<!-- aoci:end -->
