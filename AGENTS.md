# 听本 · 仓库规则（给 AI Agent）

> 新会话第一件事：按下面的顺序读，不要先翻历史，不要先读归档。

## 每轮对话必做（用户硬规则）

认知文件三份：仓库内**地图**（`aoci.txt` / `aoci.meta.txt` / `aoci.code.txt` / `AGENTS.md`）、仓库外**任务看板** `../任务看板.md`（≤600 字）、仓库外**待办清单** `../待办清单.md`（只留 3~5 条未完成，做完即删）。R243 用户嫌看板废话多，压回 600 字并把「还没做的」另立清单；其余 md 已归档，不读、不作认知证据。

- **进门**：先读地图，再读看板（看板按「已做 / 在做 / 待做」给状态），然后才动源码。
- **收尾两处都维护**：
  1. 看板改写三段——前面做了什么、现在在做什么、后面要做什么；**≤600 字**（R243 压回，R232 的 500~1000 作废），不写流水账。待办清单只留 3~5 条未完成项，做完当轮删；三份都当轮维护。
  2. 地图随本轮改动维护：受影响 Entry 的 F/R/A/S 跟上新事实，`--lookup <本轮主题>` 复查后跑 `aoci_check.py --refresh` 刷新基线。
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
- **验收阶段节奏（R217）**：用户明确本阶段改为「每轮提问题 → 处理完 → 每轮出安装包供真机验收」。覆盖 R204 的攒批不发：本阶段每轮对话都要升一个修订号、走 rc 真编译 → `--promote` → 发版，用户自己装包验收；仍不做逐步同步中间结果。
- **调音台与缓存口径（R220）**：内容完整、成本优先，调音台/模式改动**绝不丢已做好的段与整章成品**。换套时旧套复制留档、根目录已生成段原样认领为新套起点，点继续只把剩下的段按新参数合成；TaskResume 不得把调音台改动算任务失效或把进度归零。切回用过的设置仍逐字节还原。小段显示编号每章从 1 起（文件段号仍整本连续）；没做过也没在做的章点章名不展开；展开区顶部零留白。
- **拼接口径（R231）**：拼的是**这一章**的音频（该章小块 → 单章成品），不是整本、更不是「拼整张」。整本成品之外，每章小块齐了就单独出一份，目录页小耳机直接播它。
- **拼接借鉴来源（R231，同行开源项目）**：
  · `Ensemble Reads`（ruqinhu/ensemble-reads，Kotlin Android 听书）：短段合并避免长章节被切碎、边合成边播、失败重试；按章合并的触发形态参考它。
  · `Enzo22/Merge Audio: MP3 Joiner`：**不二次编码、逐帧拷贝无损**；接缝过渡（直连 / 静音 / 淡入淡出）；波形裁剪、M4B 每轨成章。
  · `ZeroOneZeroR/android_audio_mixer`：SEQUENTIAL 顺序拼接 + `BlankAudioInput` 插段间静音；基于 `MediaExtractor/MediaCodec/MediaMuxer`。
  · 百度智能云 / hesong 的 Android 音频拼接实践：`MediaExtractor+MediaMuxer` 输出 WAV，**前提是各段采样率 / 声道 / 位深一致**。
  · 结论：现有 `WavMerger` 本就是「流式 PCM 直拷 + 段间静音 + 首尾 ramp」，与同行无损路线同构，不是自造；R231 按同行共识补**格式一致性前置校验**（`fmtOf` 校验 24000/单声道/16bit，不一致拒拼并留小块），并把接缝 ramp 做成可配。**不引 FFmpeg** 是工程选择：全部段由同一引擎产出、天然满足无损前提，引库只增包不增益，不是禁令，后续按需要可改。
- **段级重做（R235）**：长按小块进多选态后，「重做选中」把段号写进 `SegPlan.markSeg`（`redo.json`），按当前调音台重新合成；正在制作时先提示等收尾，不半途插队。循环里每段重读 `SegPlan.pending(work)`，标记段即使已有 WAV 也强制重做。
- **运行中改设置（R235）**：开跑前不再把整本 instruct 一次性拼进 `toSpeak`，改 `liveSeg(i)` 逐段实时读 `Prefs.defaultPerf / voiceDescription / ttsKnobs / nonVerbal`，下一段即按新配置念；`SynthInput.params` 与 `ttsSynthesize` 的 text/speaker/knobs 全用当段实时值。**音色分套锁在开跑时一次**（voiceStamp / cacheKey / `VoiceCache.switchTo` / 三个 stamp 文件），绝不因运行中改设置换套、删已做段或把进度归零（R220 硬口径）。拼接参数 `config.gapMs` 仍用开跑快照，保证同一本前后接缝一致。运行中切模式 / 换参考音频不生效，需下次开跑重新 `SynthInput.prepare`。
- **制作设置落盘（R235，修真 bug）**：`ProduceUi` 点合成时 `Prefs.setDefaultPerf(c, cfg)`，目录页 `startAction` 也改读 `Prefs.defaultPerf(c)`。原先制作设置页只把 cfg 传给本次 start，下次从目录页启动又回到写死默认值，段间静音 / 附加语气全丢。
- **r95 修准（R235）**：`r95_verify.py` 原本 10 条正向锚点全绑死 R148 前旧公式（60 秒兜底 / `needBytes > freeBytes` 一刀切 / `estimate()`），恒定报红。按现状换成新公式锚点，公式对拍仍由 `r95_space_parity.py` 独立承担，不镜像复跑；守恒判据一条没删，`ttsSynthesize` 从 UI 合并视图迁到 core 合并视图（R205 后合成在 Producer）。正向 34 / 守恒 8 / 注入 12 全绿。
- **设置页第二批统一行（R239）**：`OverlayProgressRow` / `AutoResumeRow` / `PermEntryRow` / `PermRow` 四个顶层组件内部全收进 `SettingLine`，**签名与调用串一字不动**（r149 / r158 / r161 / r171 / r174 都绑着，改签名会一片假红）。徽标取 `title.take(1)`，不新增参数；权限绿底、`OverlayProgress.hide(c)`、`OVERLAY_SETTING` / `RESUME_SETTING` 日志逐字保留。悬浮行缺「显示在其他应用上层」权限时 `danger = on && !permOk` 整行染警示色，比原来只在灰字副标题里藏一句更看得见。主题是三段选择器不是单行开关：标题行走 `SettingLine("主", "主题", "外观基调") { }` 只借徽标槽对齐，选择器仍独占整行。行间冗余 `Spacer(8/10dp)` 一并删，`SettingLine` 自带 `vertical=9.dp`。
- **陈旧判据连根拔（R239 实证）**：r161 里还绑着 R237 之前的 `buildNotification(this, lastTitle, lastText, ...)` 旧签名，正向恒红；它的「超时通知不收尾」注入锚点 `"lastDone, lastTotal, true"` 同样打不到现行代码、恒不红。两者一起按 R237 现行形态迁移（`lastChapterPct`），**判据一条不放宽**。判「绑死已删旧形态」要正向与注入用例一起查，只改正向会留一个永远不红的假守门。
- **设置页统一行（R238）**：新增 `SettingLine(mark, title, hint, danger, trailing)`，借鉴 Material 3 Preference 的「前导图标 / 主标题 / 副标题 / 尾部控件」层级，用项目调色板自绘、不引图标库。以前每行手搓 `Column(weight)+Text+Spacer(2.dp)+小字`，行高与左右对齐全靠运气；现在统一 32dp 徽标槽、`vertical=9.dp` 行距、副标题可省、`danger` 单独警示色。顶层 composable 必须 `internal`（合并视图会还原成 `private`，判可见性要判磁盘片段、不能判合并视图，否则恒红）。撤「本机数据」「成品文件」两张都标「本机」的碎卡，合并成「文件与数据」（R174 同类碎片回收）；顶部状态卡去掉 `Android X / ABI` 调试串，设备信息仍留在诊断日志（r141 守），不丢信息。r156 绑死旧卡标题的判据按现状迁移、一条没放宽。
- **发版前置核对（R242 更正）**：报「已发 vX」并贴 APK 直链前，`scripts/preflight.py` 六项必须实跑 READY：① CI 有 `setup-java` / `setup-gradle` / `assembleDebug` / `gh-release` / `publish_apk` 五段；② 发版脚本齐（仓库外 `rc_push.py` + 仓内 `release_preflight.py` / `publish_apk.py`）；③ **仓库外** `.secrets/gh_token` 可读；④ api.github.com 可达；⑤ 远端 main 可读（令牌有 contents 权限）；⑥ 工作区干净。**R240/R241 四条错判已作废**：真编译在 Actions 上，本地有无 Android SDK 不影响出包；项目禁 `git push`，发版走 GitHub API 快进 `refs/heads/main`，git remote 本就不该配；APK 由 CI 产出，本地不需预置；令牌在**仓库外** `.secrets/gh_token`，R240 只查了仓内 `.secrets/` 才误报。发版链路：升 `version.txt` → `rc_push.py --push`（建 rc 分支、workflow_dispatch 真编译）→ `--status` 绿 → `--promote` 快进 main → CI 发 Release + 同步直链仓。缺任一不得报「已发」、不升版本、不贴直链；静态守门全绿 ≠ 真编译通过（R202 / R216 两次实证）。
- **首页认知纠正（R243）**：首页是「粘一段文字 → 点『听这段』才合成并试听」，**不是粘上就自动出声**，也不建书不跳目录页（`InputReadUi.kt` 的 `onValueChange` 只更新文本，合成只在按钮 onClick 里）。R215 之后我把它汇报成「即贴即听 / 粘一段直接出声」是措辞过头，容易让用户以为粘上去就响；口径以本条为准，代码不动。
- **R243 落地（本地编辑轮，未发包）**：① 章节成品补齐——旧逻辑只在整本跑完那一刻拼，装新版前已合成的小段永远长不出成品；`AudioOps.ensureChapterAudios` 按 `audioFor` 去重、缺块章跳过、尊重 `Prefs.autoMergeChapter`，Producer 收尾与目录页打开书（回溯 + `localTick++` 刷快照）共用这一处。② 裁剪试听关窗还在放——`RefClipDialog` 的取消/关闭/用这一段都先 `PlayQueue.stopPreview()`，按钮按 `previewState` 在「试听选中 / 停止试听」间切换。③ 顶栏书名 `maxLines=1` + `basicMarquee()` 向左滚，不再换行撑宽；抽屉 `title=""` 时整行不渲染（去掉「我的书」废话）；主页任务按钮改 `nav.push(Screen.TASKS)` 通全局任务页，不再打开目录页就地看板；「继续队列」按钮三处全砍（底层 `resumeQueue` 与暂停提示保留，任务看板本体按判断保留：通知点进来与主页入口都靠它）。④ 判据 r129/r231/r236/r237/shell_semantics 按现状迁移，并补「回溯补齐 / 去重 / 关窗停播 / 按钮回潮」硬判据，注入全拦下、一条没放宽。
- **需求池（R243）**：用户历轮提过、暂未全做的，统一记这里，待办清单只挑活跃 3~5 条。· 界面：按钮图标化（R244 顶栏一组已做，其余页面待做；代码自绘，三角+两竖、几竖加点）、设置页重新设计（R245 已做）、制作设置是否并进全局设置页（R218 判断不并：它带当前书，并过去要重选书更绕）、任务弹窗可完全砍（R243 判断保留看板、只砍费解按钮）、调音弹层上下收小（R214 已做 86%）、悬浮窗 8 字双球/单球与可拖（R214 已做）。· 音频：播放器播放条与锁控借鉴开源、通知两条进度（R237 制作通知已做，播放态待做）、参考音频波形裁剪（R236 已做，手感待真机）、导出格式设置、下拉刷新、切分粒度实测、章节表写盘失败根因（等下份真机日志）。· 其它：段级重做（R235 已做）、删段不参与合成（R233 已做）、按章自动拼（R231/R243）、真机长书尾句是否仍缺字。
- **顶栏图标化（R244）**：顶栏 5 处字按钮改代码自绘图标，界面上不再出现「调音 / 任务 / 停止 / 继续」文字。4 枚图标（`MixerIcon` 三推子带滑块圆点、`TasksIcon` 清单+圆点、`StopIcon` 方块、`PlayIcon` 三角）放 `AppChrome.kt`，全部 `Canvas` 自绘、不引图标库，深浅主题走调用方 tint，无障碍文案用 `Modifier.semantics { contentDescription = ... }` 给。**写 `internal` 不写 `private`**：MainActivity 跨文件要调；AppChrome.kt 不在 `ui_sources` 的 PARTS 里，不参与合并视图的 private 还原，所以不影响 r40/r46 逐字节守恒。⚙ 与底部动作条 / 任务弹窗 / 裁剪弹层的字按钮留下一批，拆批推进。判据按符号迁移（r226 / r230 / r214 / hub_semantics），旧字按钮串列进 BAN 防回潮；r153 / r105 / r99 判的是 `MixerUi.kt` 试听停止 `Text("停止", fontSize = 12.sp)`，**不是顶栏**，本轮未动，别顺手改错。
- **设置页重设计（R245）**：借鉴 Material 3 Preference / AOSP Settings 的分组层级。`SettingsSection` 渲染层重画：撤掉 4 枚彩色徽标框（「软件/系统/排查/本机」纯装饰还挤标题行），改**主色小字分组标题 + 一根代码画的 3×15dp 竖条**做锚，卡片圆角仍 16dp、留白 10→12dp、标题与内容间距 10→8dp。顶部状态卡从整块 `PrimarySoft` 染色改成 `palette.card` 中性底 + 1dp 淡边框 + 状态圆点，缺模型时边框转警示色。低频「开发者工具」整块挪到「文件与数据」之后（最后），普通用户不再先撞见排查项。**所有 `SettingsSection("标题", "badge", ...)` 调用签名一字不动**——r156/r171/r174/r238/r239/r73/r152 全按它定位；`badge` 参数不再渲染，只进 `Modifier.semantics { contentDescription = "$title · $badge" }` 供读屏分组。顺手删 R225 脱敏之后过时的「日志可能含书名和正文片段」，改「只记诊断信号，不记书名与正文」，r158 的 POS/ALLOWED_LONG/注入锚同步迁移。r171 里 R243 砍按钮后残留的 `Producer.resumeQueue(c)` 正向判据一并连根拔（改判 `TaskResume.queue(c)` 同源 + BAN 防按钮回潮）。判据补进 r239，不新建脚本。
- **收口发版（R246）**：R243~R245 三批本地攒批（补拼章 / 裁剪停播 / 顶栏图标 / 设置页重设计）到这轮收口，升 1.1.30 走一次 rc 真编译后 promote 发一个包。**发版前先修真 bug**：`preflight._clean_ok` 单次 `git status` 8 秒超时在机器忙时把「干净」误判成脏（实测一次正向 BLOCK、紧接着单独调用又是 READY），与 R242 修 `_net_ok` 的抖动同源；改两次机会 + timeout=20，只有两次都明确跑完且 porcelain 为空才算干净、真脏立即返回 False，判据不放宽，`self_guard` 补防回潮。这类「误报卡住发版」的自检噪声，按「拖慢进度的自检顺手清」当轮修，不攒。
- **rc #207 / #208 两次失败（R246）**：rc #207 是**静态挂项**红——R141/R163/R219 的 `--selftest` 带基线断言，R225 脱敏 + R243 砍按钮 + R244 图标化之后判据没跟着走；顺手把全套正向跑出的 r113/r78/r94 过时判据也按现状迁移（r78 整套 POS 绑死 R78 时代书架形态，换成 R215/R230 现行锚点；11.sp/10.sp 不再当旧形态禁）。rc #208 是**真编译**红——R244 往 `AppChrome.kt` 插图标时以 `internal fun AppDrawerSheet(` 为锚，把它前面原有的 `@Composable` 留在 KDoc 之前：MixerIcon 吃到两个注解报 `annotation is not repeatable`，AppDrawerSheet 反而丢了 `@Composable`；另外 `DrawScope.drawLine` 没有 `(Color, Offset, Offset, Stroke)` 重载，传 `Stroke` 对象报 `None of the following candidates is applicable`。已把注解归位、改成 `strokeWidth = sw, cap = StrokeCap.Round`，并把这两类错静态化进 `r173_verify.py`（FILES 加 `chrome`，判注解相邻 / 禁 `val s = Stroke(` / 两处具名参数齐全，3 条反向注入）。**这两类错静态守门全绿、只有 Gradle 报得出来**，再次印证「静态绿 ≠ 真编译」；发版轮必须走 rc 真编译，不能拿本地全套当结论。
- **rc #207 / #208 两次失败（R246）**：rc #207 是**静态挂项**红——R141/R163/R219 的 `--selftest` 带基线断言，R225 脱敏 + R243 砍按钮 + R244 图标化之后判据没跟着走；顺手把全套正向跑出的 r113/r78/r94 过时判据也按现状迁移（r78 整套 POS 绑死 R78 时代书架形态，换成 R215/R230 现行锚点；11.sp/10.sp 不再当旧形态禁）。rc #208 是**真编译**红——R244 往 `AppChrome.kt` 插图标时以 `internal fun AppDrawerSheet(` 为锚，把它前面原有的 `@Composable` 留在 KDoc 之前：MixerIcon 吃到两个注解报 `annotation is not repeatable`，AppDrawerSheet 反而丢了 `@Composable`；另外 `DrawScope.drawLine` 没有 `(Color, Offset, Offset, Stroke)` 重载，传 `Stroke` 对象报 `None of the following candidates is applicable`。已把注解归位、改成 `strokeWidth = sw, cap = StrokeCap.Round`，并把这两类错静态化进 `r173_verify.py`（FILES 加 `chrome`，判注解相邻 / 禁 `val s = Stroke(` / 两处具名参数齐全，3 条反向注入）。**这两类错静态守门全绿、只有 Gradle 报得出来**，再次印证「静态绿 ≠ 真编译」；发版轮必须走 rc 真编译，不能拿本地全套当结论。
- **索引/地图接得上（R238）**：每轮受管文件改动**当轮**跑 `--refresh` 并补齐 Entry，aoci_check 必绿才收尾；不留孤儿登记、不让基线漂移。新增资源若命中 `exclude_dirs`（如 `app/src/main/res`）**不硬塞索引**，并进现有受管文件的 S 段即可——R237 新增通知布局时先塞进去报「索引对象不存在 / 未在受管范围」，撤回登记并把布局描述并入 `ProduceService.kt` Entry 才干净。地图是每轮进门第一份，接不上等于后面全部判断失据；这条优先于「少写点文档」。
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
- **合成真源唯一（R215）**：「一段文字 → 一个 wav」只由 ClipSynth 负责，首页即贴即听与调音台试听都调它，不许各写一份 mode 分支 / 模型路径 / 失败判定；真源搬出 UI 片段时，老守门经 clip_view.merged() 跟着换视图，判据不放宽。
- **主动推进（R214）**：用户说「自己看着做」时，不再把建议只记成待办等确认；能从上下文推断的口径自己拍板并落地，落地不了的那块明确写成「我自己接着做」，不用用户再给一轮。界面批次不必攒齐才动手。
- **静默失效与连带回归（R213）**：诊断日志里「代码记了 A、快照却是非 A」优先怀疑写入被底层校验静默拒掉，不要只改写入点就收工；必须过一遍「修复后用户主动操作会不会被新状态卡住」，把交互对拍写进守门。
- **验证与发版解耦（R203）**：重构 / 守门 / 文档轮只跑 rc 分支真编译验证，**不升版本、不 `--promote`、不产出 Release APK**；版本号跟发版走，不再每轮 +1。攒到一批改动收口，或出现用户可感知的改动（界面布局 / 功能 / 修会崩会丢数据的 bug）才升一个修订号发一次包。真编译 ≠ 打包：R202 的漏 import 静态守门抓不到、只有真编译抓得到，所以验证轮仍必须跑 rc 真编译，只是不 promote。版本闸门由脚本按 GITHUB_REF 自行跳过，不改 workflow 结构。
- 宿主 MCP 配置（`.mcp.json`、`opencode.json`、`.codex/`、`.claude/`）含本机绝对路径，**不提交**。
- 密钥、安装包、模型权重、令牌不进仓库，也不进沙盒产物目录。

## AOCI 适配说明（如实）

本仓采用 **AOCI-CODE v0.1.1 公开协议级适配**：`cognition-volumes/v1` + `object-fras-v2`。
官方 CLI 二进制在构建环境里拿不到 Release 资产（CDN 被出网策略拦），且无 Go 1.26 工具链，
所以治理由 `scripts/aoci_check.py` 等价实现，**不要宣称跑过 `aoci verify` / `aoci check`**。
官方源码包、协议文本与中文模板放在沙盒 `tools/aoci-code/`（不进本仓）。

语义一律由模型基于源码证据创作，程序只做结构、覆盖与漂移治理；禁止从路径、文件名、
AST、符号表或模板反推 F/R/A/S。装了官方 `aoci` 的机器上可直接沿用同一套资产：

```
aoci --repo . init --locale zh-CN --agent <宿主>
aoci --repo . scan
aoci --repo . verify
aoci --repo . check
```

`.aoci/config.json` 与 `.aoci/baseline.json` 按官方结构写，但本仓**提交**它们
（官方通常不提交），目的是让 CI 跨机器验证「代码改了、索引没跟着维护」的漂移。
改完受管文件后运行：

```
python3 scripts/aoci_check.py --refresh
python3 scripts/aoci_check.py --selftest
```

## 协作与验证节奏（R232 重新设计）

- **两档沟通模式**：A 自主推进（默认）——自己拆批、沙盒攒批、不逐步同步，只在收口或该出包时动 GitHub，每轮回复贴看板全文；B 验收轮——用户一句话切换，每轮提问题 → 处理完 → **每轮出安装包**供真机验收。当前档位写进看板板头，切换由用户触发，不自己改档。
- **验证分三档，不把时间花在跑脚本上**：改码轮只跑 `scripts/r135_verify.py --selftest` 的 QUICK 6 项 + 改动面直接相关的 1~3 个守门，**不本地重跑全套 138 步**；收口/发版轮走 rc 分支真编译（编译错静态守门抓不到，不能省）；全套由 CI 承担。项目本来就有「改码轮只跑这 6 项，全套留到发版轮」的规则，每轮跑全套是后来自己养成的习惯，已连根拔掉。
- **无用自检连根拔**：命中任一即清——① 判据已被更新守门完全覆盖、只剩自我对拍；② 只镜像复跑别的脚本、自己不判；③ 绑死已删除的旧形态（正向恒真 / 注入恒不红）。清理动作一次做全：删脚本文件 + `r135` 现行清单 + CI 步骤 + `.aoci/config.json` 受管根 + 基线 + 地图 Entry，不留孤儿登记。删前先查谁 import 它的符号表；**不许借清理之名放宽真编译、守恒判据，或删掉仍被引用的共享判据表**。
- **过时约束顺手删改、冗余顺手清**：每轮进门读看板与地图，发现与当前代码矛盾的口径当轮改掉、不攒批；重复口径合并、一次性脚本归档，不新建文件。改完只刷受影响 Entry，不重复维护。
- **功能实现优先借鉴开源项目**：交互、播放器、音频处理等先找成熟开源做法再结合本项目适配；引不引依赖按每批需要自己权衡，不把工程现状说成用户指令。

## 收口清单

- 改了受管文件 → 刷新基线，并让受影响 Entry 的 `S` / `F` 反映新事实
- 收口前用 `--lookup` 复查本轮主题：查不到就补索引，`--refresh` 后基线才不会假红
- 新增守门脚本 → 登记进 `scripts/r135_verify.py` 的现行清单与 CI
- 升版本 → `version.txt`、Gradle、README / STATUS / ROADMAP、Release 标签一次对齐
- 发版 → 真编译、签名证书指纹、7 个 arm64 `.so`、包内无开发者资料，缺一不发

<!-- aoci:begin -->
## AOCI Repository Cognition

AOCI maintains a stable, versioned, incrementally updatable repository-level cognition layer so models can reuse their understanding of this system across tasks and sessions. `aoci.txt` is the Root Manifest; `aoci.meta.txt` holds the tag dictionary and FRAS discipline; `aoci.code.txt` assigns one independent Entry to every managed object. Together they form the Whole-Index: a high-density view of responsibilities, strong relationships, public contracts, and non-obvious constraints.

At the start of every new Agent Run, read `aoci.txt`, then `aoci.meta.txt`, then `aoci.code.txt` before opening source. Reuse that cognition while it remains reliable; after host context compaction, treat prior model cognition as unreliable and reload the Whole-Index from these assets. Do not substitute historical memory, `dev/` archives, or landing reports for current repository-bound cognition.

Entry semantics must come from the model's understanding of actual evidence. Never derive, prefill, assemble, or rewrite index semantics solely from paths, filenames, extensions, an AST, symbol lists, dependency scans, regular expressions, fixed templates, or rule engines. `R` names strong relationships as `code:<repository-relative-path>`; `S` records only non-inferable, error-preventing constraints. Scale tags are machine facts derived from line counts; importance tags reflect architectural weight, not file size.

When managed objects change, close the task by refreshing the governed baseline and updating only the affected Entries. After alignment, do not repeat maintenance. Purely read-only questions need no maintenance. AOCI is not an AI Agent, RAG replacement, AST replacement, LSP replacement, daemon, or cloud service: agents still inspect real source and tests. Directory section headers are historical coordinates, not runtime roots; readers logically relocate them, and a public repository must keep hostnames, user names, and secrets out of them.

This repository uses protocol-level integration: `scripts/aoci_check.py` performs the governance that the official CLI would, because the binary is unavailable in the build environment. If an official `aoci` binary is installed, its `verify` and `check` remain authoritative and the live Guide and tool schema override this block.
<!-- aoci:end -->
