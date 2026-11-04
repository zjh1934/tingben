# 听本 · 迭代记录

## R1 · 立项（旧项目切割）

用户判定旧「墨听」是调试台，要求从零重做、换名换仓库。
新建 `/data/workspace/听本`、`zjh1934/tingben-android`、`zjh1934/tingben-apk`；旧项目冻结。
产出：调研 248 行 / 20 来源、从零起步总纲、唯一主路与验收契约、项目边界。
**一行代码没写。**

## R2 · 四层移植 + 全量落地（本轮）

用户要求：导图 → 扒参考项目核心 → 逐件加工 → 串起来 → 做进软件 → 推 GitHub → 出 APK。

1. **导图**：`docs/听本_项目导图.html`（主路 / 四层移植 / 数据流水线 / 5 页 UI / 工程树 / 6 道闸门）
2. **扒**：本地已有 Alexandria / ebook2audiobook / qwentts.cpp / nabu / HX-SayBook / Unitale 全量源码；补拉 Antioch、ReaderVoice-Mobile、OfflineLLM 三个仓库 tarball。`reference/精选/` 落 15 个核心文件 + 许可证
3. **加工**：`contracts/prompt_zh.txt`（中文标注契约，双段结构 + 逐字红线）、`contracts/pipeline_spec.md`（A~G 七组可实现规格）
4. **串**：`core/Pipeline.kt` 一条链 20KB；`engine/NativeEngine.kt` + 改包名后的 JNI 胶水；`MainActivity.kt` 5 页 25KB
5. **底座**：Gradle 8.9 / AGP 8.7.3 / JDK17 / compileSdk35、固定 keystore、无 INTERNET Manifest、CI 缓存 + 符号差集守门 + APK 闸门 + Release + raw 直链
6. **发布**：run 1 自检失败（根 build.gradle.kts 漏推）→ run 2 Kotlin 三处编译错（表达式体 return、Button 位置参数）→ run 3 修复后 success → run 5 加 Release → **run 6 加 raw 同步，全绿**

### 修过的真问题

- 根 `build.gradle.kts` 用 Git Trees API 推送时漏掉（本地存在、远端没有）→ 自检失败
- `pcmOf` / `merge` 用 `= try { ... return ... }` 表达式体 → Kotlin 禁止裸 return
- `Button(onClick, enabled, Modifier...)` 第三个位置参数 → 必须写 `modifier =`
- CI 失败日志回写步骤自身 `exit 1` 让 job 标红（但日志成功落盘，能定位）

### 如实说

- 参考项目分处 PC Python / Rust CLI / Android 聊天 App，**没有一个能整包微调**；是分层移植，不是 fork
- CI 全绿 ≠ 真机能用；出声仍需用户在真机点一次
- `pipeline_spec.md` 的 C3/C4/D3 在首版未实现，已在文档里标「实现差距」，不假装做了

## R3 · 真机首轮反馈：导入模型闪退 + 没有下一步按钮

用户贴回 1.0.0+7 诊断日志：三次 `MODEL_IMPORT` 后各跟一次 `APP_START`（间隔 16~23 秒），三个 GGUF 都导入成功但 App 每次重启；模型页齐了之后**没有任何「下一步」按钮**。

### 根因（两条都实锤）

1. **`pickModel` 的 ActivityResult 回调在主线程同步复制 2.5GB**。Android 主线程被长 IO 占满 → ANR / 被系统杀进程 → 用户看到「导入时闪退」。且回调里没有 try/catch，SAF 流读取抛异常会直接崩在回调里。
2. **模型文件名取 `uri.lastPathSegment`**，content:// URI 常返回 `document/1234` 这类编号而不是真名；`ModelScreen` 在 `missing.isEmpty()` 时只给一句「都齐了」文案，没有跳转按钮，用户被卡在设置页。

### 修复

- 复制搬到 `Dispatchers.IO`，`runCatching` 全包，`.part` 原子改名 + 失败即删半截文件
- 用 `OpenableColumns.DISPLAY_NAME` 取真名；补 `takePersistableUriPermission`
- 导入中显示「正在复制，别关掉 App」+ 实时 GB 数（`MutableStateFlow` 跨线程）
- **三个文件齐了自动跳回书架**，不再让用户找下一步；模型页/设置页/书架页各补一个显眼主按钮
- `TingbenApp` 装 `UncaughtExceptionHandler`：Java 层崩溃写 `crash.log`（含 25 行堆栈 + 崩溃前 25 条事件），诊断信息里带出来
- 启动时清 `.part` 残留并记 `PART_DISCARD`
- 去掉字符串模板里的内嵌引号（避免编译器歧义）

### 顺带订正

上一轮把版本写成 `1.0.0+6` 是错的：`versionName = "1.0.0+" + BUILD_NUMBER`，run 7 → **1.0.0+7 (107)**。用户日志与 APK 一致。

## R4 · 测试用书 + 粘贴文字 + 底栏避让

- 用户反馈「手上没有现成的书」，写两本原创测试短篇放进 `samples/`，并写清各用来验什么、建议先跑哪本
- 兑现 R3 改文案时写下的「也可以粘贴文字」——之前是空头承诺，本轮补了真实输入框与 `BookStore.add` 落盘
- 书架页底部 Tab 与 MIUI 三键导航栏重叠（用户截图可见），加 `navigationBarsPadding()`
- 附带：粘贴建书后 `onChanged()` 触发列表刷新，否则 `remember(refresh)` 不更新会以为没加上

## R5 · 进度可达 / 心跳 / 三项设置

- 用户按返回后进度页消失：系统返回键未接管 + 进度页无全局入口 → `BackHandler` + 跨页常驻任务条 + 书架卡
- 「一直不动」：prefill 单批 70 秒无 UI 事件 → `EventLog.lastEngine()` 缓存 + 进度页每秒心跳 + native DECODE_STEP 步长 32→8
- 阶段条「拆章」永不点亮：`st.stage.contains("拆章")` 对不上「正在分章」→ 改显式映射
- 补回从零重写时漏掉的三项：思考开关（含 `ThinkingStore` 逐块落盘与进度页展开）、模型参数（`Prefs`）、音频试合成（真出 WAV）
- 教训：**"一整块做完才更新"在手机端侧等于"看起来死了"**，必须给阶段内心跳

## R6 · JNI 预热 / prefill 合并 / 直接朗读

- 「No implementation found」不等于符号缺失：`nm -D` 验证 9 个符号齐全，根因是 `ensure()` 从未在 App 启动时调用。凡是 external 方法，必须保证调用前库已装载，不能指望用户先走某条路径。
- 手工分批 prefill 是反优化：真机 512 token 70s、195 token 115s，固定开销按次付。改 `n_batch=n_ctx / n_ubatch=512` 一次提交。
- 端侧 4B 在手机上逐 token 9.6 秒时，「整本先标注再合成」对用户等于永远跑不完。补一条不依赖 LLM 的直接朗读路径，先把出声链路验通。这是产品决策，不是降级糊弄。

## R7 · 停止与断点续传

- 用户点破「一跑起来就不停」：确实没有停止入口，标注结果也没落盘。端侧 4B 一块几分钟，不缓存等于永远重跑。
- 停止用「标志 + 块边界退出」而非 `job.cancel()`：JNI 是阻塞调用，协程取消打断不了 native，硬杀会留下半截状态。
- 缓存分桶必须包含「模式 + 选章」，否则换章节后 `i` 重排会把 A 章的标注套到 B 章上。
- 续跑时仍需把缓存块依次喂回 roster/recent，否则跨块角色上下文断掉。

## R8 · 真机首次出声 + KV cache 修复

- 首次 `PRODUCE_DONE`：5,802,284 字节 WAV ≈ 121 秒人声。整链真通，此前所有绿灯都只是编译。
- `llama_decode` 的 batch.pos 为 NULL 时由 ctx 自动追踪。**同一个 ctx 连续 generate 必须清 KV**，否则第二次 prefill 位置接着累加，592 token 也能把 4096 窗口撑爆。这是逐块标注 + 重试架构的必踩坑。
- 降级路径不能偷懒返回整块原文：TTS 对长文本单次合成在手机上是几十分钟级，降级也要切短句。
- 严格 JSON gate 与「思考外显」在手机小预算下本质冲突：思考吃掉预算 → 截断 → gate 必败 → 全降级。不是 bug，是资源约束，应该在 UI 上说清而不是假装两者能兼得。

## R9 · 停止失效 / 外部打开 / epub

- 「停止无效」不是单点 bug，是四处叠加：prefill 不响应取消、generate 入口复位取消标志、重试绕过块边界检查、UI 可重复点。只修其中一处仍然会复发。
- 凡是「取消/停止」语义，复位必须放在**整次任务的起点**，不能放在每次工作单元入口；否则工作单元一重试就把取消吃掉了。
- 外部打开方式是 manifest 层能力，不加任何权限；epub 解析用 zip + OPF spine 就能做基础版，不需要 jsoup 之类依赖。

## R10 · 导入按钮 / epub 结构化分章

- 「点不动」多半不是按钮坏，是 SAF 选择器静默返回 + 回调吞掉 null。任何外部交互都要有「点了 / 取消 / 失败」三态反馈，否则用户无法区分「没点到」和「点了没结果」。
- epub 按章的正确来源是 **OPF spine / toc.ncx**，不是从正文里正则猜「第X章」。猜标题这条路对英文 Chapter、中文楔子/序必然失效，而且会把「整本」当成 1 章，让按章功能形同虚设。
- 结构化信息尽量用显式分隔符从解析层传到切分层，别在下游重新推断。私有区 `\uE000` 做内部边界成本极低且不会与用户正文冲突。
- content:// 流只读一次；二次 open 对一次性 URI 会失败，且失败常被 catch 吞掉变成静默误判。

## R11 · 一次多改 + 打包前联动验证

- 用户指出「改一处联动很多处」「改完不验证就打包」。本轮把六组改动一起做完，先跑 60 项交叉引用检查再推 CI。
- 外部 intent 的坑是组合式的：`onNewIntent` 未接管 + 只查 `data` + 未取持久授权 + 相同值被 StateFlow 吞 + 无 Uri 时静默。只修一处仍会复现「点了没反应」。
- 括号/语法的粗正则检查会误报（三引号、字符串模板、注释里的 `//`）；改用字符级状态机才可信。静态检查能抓符号与联动，但**真编译仍以 CI 为准**，两者不能互相替代。
- 「哭声笑声」这类需求要先分清：模型原生标签 / 自然语言导演 / 拟声词当文本 / 音效拼接，四条路代价完全不同。别把新一代模型的能力算到手上权重头上。
- 严守逐字契约：任何「让声音更生动」的增强只能动 `instruct`，不能动 `text`；否则字符级 gate 与成品语义一起破。

## R12 · 任务持久化与独立任务区

- 用户说的「任务没有了」不等于数据丢了：`work/` 里块文件还在，缺的是**入口**。以后遇到「找不到了」先分清是数据丢还是 UI 没暴露。
- 内存态（StateFlow / busy）永远不能当持久化用；进程重启后必须把 `running` 如实降级成 `interrupted`，不能显示「正在制作」骗人。
- 恢复时**以磁盘实况重算 done**，不信死前写下的数字：写了一半被杀是常态。
- 任何「续跑」必须把建任务时的档位（含 think / 选章 / direct）一起带上，否则缓存分桶 key 不一致，表面显示「续上」，实际全书重算。
- 长任务的产品形态不是多画一条进度条，而是**单独一个可管理区域**：状态、继续、重做、放弃、去听成品，五个动作齐了才算完整。

## R13 · 先查本地再上网

「别手搓」不等于「上网找一圈就算数」：**本项目 `reference/` 里从立项起就躺着两个完整 Android 工程**（OfflineLLM / ReaderVoice-Mobile），里面有现成 Kotlin 角色表、USER_LOCKED 修正层、profile hash、采样参数常量与 Compose 设置页。之前写移植方案时没翻，差点得出「组件只能自己写」的错误结论。
以后接到「参考别人怎么写的」，顺序应是：① 翻本地 `reference/` → ② 再上网补缺口 → ③ 最后才判断哪些真需要自己写。

## R14 · 先跑静态检查再推 CI

用户点名过「改完东西不做验证、不检查，改一处联动很多处」。本轮在推送前跑了一份本地静态自检：Kotlin 括号平衡、JNI Kotlin external 参数数 ↔ C++ 参数数、9 个采样字段在 `qwen.h` 与 cpp 双侧存在、所有 `ttsSynthesize` 调用点都传 `TtsKnobs`、声音指纹联动齐全、逐字 gate 未被改、断点分桶未被动、无 INTERNET。结构自检 18 项也过。
另：把「缓存失效」做成 `voice_stamp.txt` 而不是改 `bucket()` 签名 —— 后者会连带 TaskResume / realProgress / Producer 三处，属于典型的「改一处崩一片」。

## R15 · 先读代码再改，改完先静态验

这轮没有凭感觉答「没问题」：逐行读了 `Producer.run()` 的标注循环、`Annotator.buildPrompt`、`llmGenerate` 全段、`llama.h` 的 memory/batch 声明，还翻了 `reference/OfflineLLM/smollm/src/main/cpp/LLMInference.cpp` 看别人怎么管 pos。
两个差点漏掉的坑：① `contains("")` 恒真；② 我自己新写的 `THINK_CLOSE` 常量第一次也误写成空串，靠脚本断言抓到。
改 C++ 后按纪律跑静态检查再去 CI，避免「改一处联动崩一片」。

## R16 · 把 prompt 当契约，不是当文案

这轮先派研究、再读本地一手源码（Alexandria `default_prompts.txt` / Antioch `annotator.rs` / ReaderVoice `DirectorPrompt.kt` / `llama.h` grammar 声明），才动笔写模板。
两个自我修正：① 委托报告称「未找到 Alexandria 一手实现」，是检索范围所限，本地 reference 里就有原文，已在归档文件头部补正；② v2 初稿 system 写到 1246 字（净增 512），按端侧 prefill 成本主动压到 968 字（净增 173），没有把「写得全」当成「写得好」。
改完先跑模拟装配与 Kotlin 静态检查，再上 CI —— 改 prompt 最哑巴的错是新增占位符忘了在装配里替换，已用 `PROMPT_PLACEHOLDER_LEAK` 兜住。

## R17 · 把「直接朗读」从捷径升成选项，顺手修三处联动

没有新写链路：`directRead=true` 时 `run()` 连 LLM 都不加载、分桶独立、任务页已有模式文案，能力早就在。这轮动的只是「用户能不能看见、能不能选、选了之后旁边几处会不会跟错」。
最险的是 `hasCached` 里那个写死的 `false`：不跟着模式走，切到直接朗读还会显示「继续制作」，一点就进标注档的旧缓存。这类哑巴联动正是前几轮被骂的地方，所以这轮先把 6 个联动点列成表逐个过，再上 CI。
逐字保真按用户要求搁置：gate 表达式、prompt v2、native 全部未动；第三档「改编剧本」只留在设计文档，不塞进界面。

## R18 · 「别手搓」要落到内核，不只落到 UI

用户明确：模块不该手搓，要扒现成代码改写。这轮先翻本地 `reference/`（ReaderVoice 的 `CachedAudioPlayer` / `AudioCacheScanner` 早已在库里），再联网抓了 `AlexExoPlayerMP3`、`SSComposeExpandableListView`（MIT）、`ensemble-reads`（Apache-2.0）的真实源码，逐文件读完才动手。
中途自己写的第一版音频页仍留着一处手搓 MediaPlayer、看台又留了第二套 —— 等于「UI 抄了别人的，内核还是自己搓的，而且两套播放器抢焦点」。已推倒统一成 `core/PlayQueue.kt`：三份源码各取一层（队列 / 快照 / 控制 API），用系统 MediaPlayer + StateFlow 改写，**页面里零手搓播放器**。以后「参考别人」要检查的是「最里层那块有没有真换」，不是「长得像不像」。

顺带把用户说的「书架一直膨胀」拆成两个真因：导入无指纹去重 + 成品写死 `<bookId>.wav` 互相覆盖并被显示成整本。都不是 UI 美化问题，是数据模型问题。

## R19 · 口头结论先落盘，再按 P0→P1→P2 推进

上一轮是纯回答，用户连问六件事（音色归属 / 语速 / 并行 / 预设 / 界面 / 夜间保活）并甩了一句实测「每一个小段音色都不一样」。如果这轮直接改代码，那些结论就只活在聊天记录里 —— 所以先写 `docs/R19_路线图与决策记录.md`，把每条钉成 D1~D6，再排有序队列，然后**只推进 P0 三件**。

两个自我约束：
- **不跳步**：完整的「制作准备页」要改 `Producer.start` 签名，连带任务书签 / 任务页续跑 / 进度重算 / 缓存分桶四处。用户反复点名「改一处联动很多处」，所以这轮只做**零签名改动**的只读「本次配置」卡，把真正那页留到 P1 单独一轮验。
- **不包装**：固定身份锚点 + 身份词剥离只是**收敛**逐段换声，voice_design 每次 synthesize 仍重新采样；代码注释、文档、回复里都写清「不是根治」，不拿文档结论冒充真机效果。

另外订正了自己写过的过时文件：README 还停在 `1.0.0+6`、还教用户点已经删掉的「只想先听声音」小按钮、还写「真机出声没人验过」（其实已有 `PRODUCE_DONE 5.8MB` 证据）。维护项目文件不是补一段新记录，是**把已经不真的话改掉**。

「屏幕常亮」也特意写成只在前台、零权限，并在代码注释里写明不等于保活 —— 免得下一轮又把它当成后台续跑已完成。

## R20 · P1①「制作准备页」：先列 11 处联动表，再动第一行代码

按 R19 队列继续推进，只做 P1 第一项，前台服务 / 段级重做一件没碰 —— 自己写的「各自独立一轮」不能自己破。

这轮最该记的不是做了什么界面，是**先写了 `docs/P1_制作准备页_联动表.md` 才动签名**。用户点名过三次「改一处联动很多处、不验证」，所以把 `start` 的 4 个调用点、书签往返、`restore`、合并器、指纹、分桶、UI 回调逐条列出来，改前勾一遍改后再勾一遍。结果确实靠它挡住了两处容易漏的：进度页续跑与任务页「从头重做」如果不传 `t.config`，用户改了「广播剧」再续跑就会默默退回「叙事」，界面一点错都不报；以及 `INSTRUCT_TEMPLATE_VERSION` 不升 3 的话，旧 WAV 不会被作废，听感断层查不出来。

**「别手搓」这次落到了控件层**：`ExpandGroup` / `OptionRow` 不是自己设计的，是 ProAndroidDev 的 state-hoisted 折叠 + OfflineLLM 的 selectable 档位 + PocketPal 的「恢复默认」三态拼出来的；预设概念抄 index-tts-webui 的 Session 预设、段间静音抄 epub_to_audiobook 的 `break_duration`。联网查这三家不是为了凑引用，是为了确认「生成前预设页」这个形态别人已经趟过，别自己发明一套。

一个刻意不做：**思考档不做本次级覆盖**。它参与 `bucket()`，再搞一份本次级开关就是两个真值源，续跑必串桶。界面只读展示全局值并写明理由，比放一个看着能点、点了会出事的开关诚实。

段间静音也刻意不进任何指纹 —— 它只在合并阶段插零帧，分段 WAV 内容一个字节没变，成品每次都是新 UUID 本来就会重新合并。把「什么该让缓存失效」想清楚，比多写几个开关值钱。

**run 27 编译失败，丢人但值得记**：新文件 KDoc 里手滑写了 `KEY_*/DEFAULT_*`，`*/` 把块注释提前闭合，后面十几行全成了顶层声明，编译器刷了上百条 `Expecting a top level declaration`。本地括号计数、结构自检、往返模拟全绿，就是抓不到这个 —— 说明「静态检查项目」本身也得跟着踩过的坑长。已加一条「块注释内不得出现 `*/` 字面量」到 R19 纪律，run 28 全绿。这类错不该留到 CI 才发现，下次本地脚本先扫。

## R22 · 一手资料进上下文 → 核心链路与日志同版重做

用户两句必须一起接住：「日志里要能看见**标注过后的内容**，我现在不知道它是真标注还是假标注，感觉标了跟没标一样，白费力气」；以及「你不应该去研究回来，应该自己进上下文去搜、读进去，再定核心代码设计」。

**这轮真读了**：官方 `examples__test_model_12hz_voice_design.py` / `custom_voice.py`、`Qwen3-TTS README`（167 / 203 / 288-290 行）、`qwentts-cpp/src__qwen.h`（133-142 / 268-292 / 384-390 行）、`sampling-defaults.h`、`examples__tts.sh`、Antioch `script/annotator.rs`（20-30 / 77-96 / 112-165 行），以及本项目 `tingben_native.cpp` 生成循环与 `Pipeline.kt` 的 gate / fallback。逐条依据写进 `docs/R22_标注链路与双模型协作_设计.md`，**没有一条是二手转述**。

**读出来的根因链**（不是猜）：native 只靠 EOG / `limit` 停 → 模型吐完 `]` 继续废话到预算腰斩 → 四级修复丢尾条 → gate 全有或全无判整块失败 → fallback 全旁白「平稳叙述」 → 音频听不出标注 → 日志只给统计数字，用户无法自证。Antioch 的解法恰好是「**单条失败 `continue`，用 sensible defaults 补齐，不牵连整块**」。

**同版四处改动**：
1. native 加 JSON 数组闭合早停（`NATIVE_JSON_CLOSE`），字符串内 `]` 与转义引号不误判，没见到 `[` 时行为不变
2. `Annotator.reconcile()`：逐条校验 + 确定性补齐，三档 `FULL/PARTIAL/FALLBACK`；**`result` 拼起来仍严格 == 原文**，gate 保留为断言；`PARTIAL` 直接采纳不重试，省一次几分钟 4B 空转
3. 日志摊开内容：`ANN_CONTENT`（逐条 text/speaker/instruct + 来源）、`ANN_DISPOSITION`、`TTS_INPUT`（最终 instruct）、`BlockAudit.annsJson` 落盘、诊断页「查看标注内容 / 复制标注全文」、连「复制诊断摘要」都带最近 3 块全文
4. 双模型协作：`lang` 由 auto → 显式 `chinese`（官方 README 167 行），`INSTRUCT_TEMPLATE_VERSION` 升 4 让旧 WAV 作废、标注保留；确认 `speaker=null` 对 voice_design 正确

**两条自我订正**：`qwen.h:142` 明写 `qt_synthesize ... thread safe` 且 `max_batch>1` 支持并发批处理 —— 我以前「端侧不能并行」的说法错了，改成「引擎支持、CPU 收益待真机量」；「小模型回显平行 JSON 会循环」仍只当 Antioch 的工程假设，不当实测结论。

**联动检查 9 项**（缓存作废 / 旧 jsonl 兼容 / 主日志不被刷爆 / native 缓存 key 自动失效 / 诊断页折叠…）写在设计文档第六节。刻意不动：prompt v2、`Producer.start` 签名、`bucket()`、状态机、前台服务（连续两轮顺延，不合并发版）。

**验证**：本地结构自检 + 17 个 Kotlin 文件括号净值（MainActivity 本轮改动 0 差；`Importer.kt` 的 `Regex("""…""")` 三引号是检查脚本已知假阳性，CI 真编译为准）+ reconcile 6 例 +

## R23 · 把上一轮跑完（复核 + 日志全文 + 交付）

上一轮代码/文档/包已落盘但收尾没做完。本轮按长期记忆纪律开工先读 playbook 与 environment-facts，
再：① 真读本地一手源码（官方两个示例、官方 README 副本、两份 qwen.h、sampling-defaults.h、Antioch annotator.rs）
并列出行号；② 联网核实官方模型卡与 qwentts.cpp 模式规则（GitHub README 直取两次被拦，如实标未取到）；
③ 逐行核对 reconcile / 标注循环 / toSpeak / native 早停 / AuditLog / 诊断页；④ 远端核实 run 30 全绿、
发布仓 APK 17,771,027 字节与本地一致；⑤ 本地拆包硬验 `.so` 内 `NATIVE_JSON_CLOSE` 命中。

**修掉一处真缺口**：主事件流 `ANN_CONTENT` 仍把正文截 70 字、只给前 8 条，用户贴「最近事件」时
依旧看不出标了什么。改为逐条全文 + 16KB 上限，自检加 3 个 R23 符号与「不许退回 take(70)」断言，
并做反向验证（回退版必须 FAIL）。其余契约、prompt v2、native、状态机一律不动。

详见 `docs/R23_本轮复核与交付说明.md`。 早停状态机 6 例 → **run 30 全绿** → 拆包硬验 17,771,027 字节、CRC 全过、7 个 arm64 .so、dex 九个新符号全命中、**`.so` 内 `NATIVE_JSON_CLOSE` 命中**（证明 native 不是旧缓存）。发布 `1.0.0+30`。
