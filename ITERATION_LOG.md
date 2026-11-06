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

## R24 · 不要手搓：三处核心逻辑换成现成项目移植件

用户插话定纪律：「不要什么都手搓，去看别人怎么做的，拿过来改」。这轮不新增功能，
只把上一轮我自己写的三处逻辑换成本地 `reference/` 里早就有、而且更完整的开源实现：

1. `parseWithMeta` 的四级 JSON 修复 → **Alexandria** `clean_json_string` / `repair_json_array`，
   落地 `core/JsonSalvage.kt`（括号计数、字符串内控制字符、裸串过滤、字段顺序无关）
2. `reconcile` 的逐条采纳 → **Antioch** `annotator.rs` 采纳管线，落地 `core/Adoption.kt`
   （echo 检测、标签剥离、首句截断、去重、单条 sensible defaults，逐条丢弃原因进日志）
3. 日志只留头尾截取 → **Alexandria** `llm_responses.log`，落地 `diag/RawDump.kt`
   （每次 attempt 原始产出全文按 run 落盘、只留最近 3 次、诊断页可查看/复制）

两个 MIT 许可证与版权方写进 `docs/THIRD_PARTY_NOTICES.md`，自检强制检查。

**验证**：结构自检 24 项全绿；本轮 5 个 Kotlin 文件括号 0 异常；拿 Alexandria 原 Python
函数当基准做 12 组对拍 → 12 一致 / 0 不一致；反向验证（注入旧实现字面量必须 FAIL）通过。
**如实订正**：原以为括号计数能在「正文含方括号」时多救回条目，实测截断场景两者都 0 条；
真实可观测收益是「字符串内裸换行：旧版 0 条 / 移植件 1 条」。注释已按实测改，不夸大。

**一次自检自摆乌龙**：我把旧实现的字面量写进了新注释，回退断言立刻报红。
按 playbook「文本级检查先做词法屏蔽」的教训，同时改了注释措辞与断言精度
（改成匹配赋值形态），不是简单放宽检查。

同步方式：`git push` 实测 403，改走 `scripts/sync_remote.py` 的 Git Trees API 增量提交，
带 `base_tree` 只增不删、逐文件核对 blob sha；本轮只传了 7 个变化文件。
详见 `docs/R24_拿来主义移植清单.md`。


## R25 · 断句移植件 / 设置分层 / 日志不骗人

用户贴 `+32` 真机日志定案：标注档 643 秒 0 条标注，日志还写「通过」；直接朗读档段尾从半句腰斩。

- 拿三份现成源码拼 `TextSplitter.kt`（Antioch chunker / ReaderVoice SemanticSegmenter /
  ensemble-reads SegmentMerger），不再手搓 `buf.length >= 110`。
- 设置页按 ① 语言模型 / ② 音频模型 分组 + 作用域卡；准备页四组标「仅本次 / 跨书默认」。
- `ANN_BLOCK_END` 零产出改 WARN；新增 `SPLIT_STATS`、`ANN_PHASE_HINT`；MAIN 冷启动不再误报分享失败。
- 新书默认直接朗读，标注档标实验性。

踩坑：反向验证时两条自检断言写太松（只查符号字符串、删定义仍通过；回退断言把注释里的
旧 bug 字面量也拦了），已改成匹配定义/代码形态并重跑 5/5 通过。

文档：`docs/R25_断句与设置分层_联动表.md`；第三方声明补 3 节。


## R26 · 项目文件维护 + P1② 前台服务（按队列，不再顺延）

用户：「维护一下你的项目文件再开始干活」「按你的节奏继续推进」。

**先维护**：`docs/ITERATION_LOG.md` 曾与根目录并存且停在 R21 → 收成单一指针；README 版本举例
`+29` → `+33`；STATUS 顺延次数改「四轮」；第三方声明里 ReaderVoice 的「许可证未核实」经本地
`LICENSE` 核实为 MIT，已订正；另外抓到 **R25 文档说「推理参数卡挪回①」但代码还挂在②**，本轮真挪了。

**再干活**：只做 P1②，先写 `docs/P1②_前台服务_联动表.md`（12 处联动 + 4 项验证），然后落地：

- 不手搓：`ProduceService.kt` 形状移植 ReaderVoice-Mobile `ChapterDirectorService.kt` /
  `VoiceDesignService.kt`（MIT）—— FGS dataSync、通知渠道、打开 + 停止两个 PendingIntent、
  `START_NOT_STICKY`、结束 `STOP_FOREGROUND_DETACH`。
- manifest 4 权限 + service 声明；`Producer.start` 从可见界面起服务并申请 POST_NOTIFICATIONS，
  `finally` 统一收服务；`emit` 刷通知进度；`stop` 切「正在停止」；两个循环按块 `touch()`
  带 30 分钟硬超时的 PARTIAL_WAKE_LOCK。
- `TaskResume` / `bucket()` / `start` 签名 / native / prompt v2 **一行未动**。
- 任务页说明、设置页「后台运行设置」入口、诊断摘要加 FGS 状态行。

**验证**：结构自检 25 项全过；Kotlin 22 文件括号状态机全 0（第一版词法屏蔽把
`Importer.kt` 的三引号误判成 -4，改进屏蔽后复核为 0，该文件本轮未改）；
**反向验证 3/3**：注入违禁权限 / 删 `finish` 调用 / 去唤醒锁超时，全部被自检拦下，还原后基线通过。
**CI run 34 全绿**（22 步，native 缓存命中未重编）；拆包硬验 CRC / 7 个 .so / 无 INTERNET /
`1.0.0+34` / 4 权限 / dex 符号全过。APK 17,787,616 字节。详见 STATUS 的 R26 发布记录。

## R27 · 真机日志定案：模型标了，但一条没进合成

用户给两张真机截图（`1.0.0+32` 进度页 / 诊断页）问：是模型真没产出、产出没介入下一步、还是显示原因。
按长期记忆手册开工读 playbook + environment-facts，然后逐行读本地源码定位。

**定案**：A 否（896 字原始产出 / 877 字有效 JSON / 9 条规则 7 过 2 不过，模型规规矩矩吐了 JSON 数组）；
B 是（主因）；C 也是。根因链：`Adoption.adopt` 与 `ruleChecks` 都要求第一条 text 严格等于原文第 0 字，
模型把「第一章 试音」这类标题/空行当元信息跳过 → `NOT_PREFIX_AT_0` → kept=0 → FALLBACK →
`directSegments` 整块覆盖 → 全「旁白 / 平稳叙述」进 TTS。失败码旧名 `CONCAT_LEN_MISMATCH` 名不副实。
三处显示口径混用：进度页按跑完块数写「已标注」、看台按 `ann_*.json` 存在写「已标注」、
诊断把「模型候选」与「最终直读段」的数字并排 → 出现「差 +0」却「逐字保真 0/1」。

**改了 6 个 Kotlin 文件 + 自检脚本**：严格前缀 → 有序子串 + 缺口补齐（向 Antioch「结构归程序」靠拢，
逐字契约一字未放宽）；`srcModel` 逐条来源标记；规则判定与中文名拆清两种对象；
进度文案改「模型真标注 a / 直读 b / 续上 c」；`ann_%04d.src` flag 让看台蓝/黄/灰如实着色；
审计摘要拆「模型候选 / 最终进 TTS」两段 + 「未采纳首因」+ `adoptFirstDrop` / `finalVerbatim` 字段。
没动 prompt v2 / native / `Producer.start` 签名 / `bucket()` 缓存分桶 / 前台服务。

**验证**：结构自检 25 项全过，新增 R27 正向 12 项 + 5 条反向断言（只认代码形态）；
Python 等价实现对拍 4/4（跳标题旧 FALLBACK → 新 PARTIAL 且逐字相等、完整覆盖 FULL、
**改写正文仍 FALLBACK 不假装修好**、重复 break 后补齐）；反向验证 1/1
（注入 `original.regionMatches(cursor` → 自检 FAIL，还原 → OK）。
期间验证脚本自身一条断言写错（把重复预期成 DUPLICATE，实际先命中 NOT_FOUND_AFTER），
按实测改正，未当产品失败。

文档：`docs/R27_真机日志定案_标注为何没进合成.md`。
真机仍零证据：+35 要跑试音短篇看「未采纳首因」是 `-` 还是 `NOT_FOUND_AFTER_0`。


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

**CI 复盘（如实记）**：run 36 / 37 真编译失败，报错 `Unresolved reference 'segOrigin'`
（CI 把日志回写到 `logs/build-36.md` 才拿到）—— 本地静态自检括号 / 符号全过也抓不到
**变量作用域**：`segOrigin` 被我定义在 `else`（标注分支）里，而写 `segmap.json` 的合成阶段在分支外。
已提到 `if (direct)` 之前、补一条反向断言，再推 run 38/39。
**这条印证纪律**：静态自检 ≠ 编译过，必须有一次 CI 真编译；本地脚本全绿不能当发版依据。

**发布（最终）**：CI **run 38 全绿** · `1.0.0+38` · APK **17,820,388 字节** ·
拆包硬验 CRC 全过 / 7 个 arm64 .so / 权重 0 / 无 INTERNET /
dex 含 `SegPlan` `LLM_VERDICT` `segmap.json` `redoSynthesize` `redoBlock` /
binary dex 读到 `1.0.0+38`。run 36/37 因 `segOrigin` 作用域真编译失败（日志回写后定位、已修）；
run 39 为 push+dispatch 重复触发，已取消，固定直锁 `+38`。
拆包脚本首次把 `durationMs` / `preview` 词面当「旧字段残留」误报过一次 —— 这两个词在音频页 /
诊断页本就合法存在，已改成由源码层断言负责，不拿 dex 词面判字段。


---

## R31 · 方向变更 + 调音台（run 40 全绿）

**用户定的方向**：4B 端侧标注效率太低、标得也不好 → 标注挪到元宝沙盒逐块做；
App 这一侧「这一年就干这个事情」——把音频模型能调的参数调好、控制台显示出来。
换模型这条路不走。

**干了什么**：
- 把本地一手源码真读进上下文：`qwen.h:303-320` / `sampling-defaults.h` / qwentts README 采样链 /
  ReaderVoice `096b_personas.py` 中文 12 维网格 / 官方 VoiceDesign 多属性示例；联网核了
  Alexandria `VOICE_REFERENCE.md` 的 40 组 Anatomy-First 实验、AutoShorts 12 维预设、
  ebook2audiobook 参数面板、Qwen3-TTS-X 稳定档推荐、long-form TTS 不可变 profile 实践。
- 新建 `core/Mixer.kt`：12 维音色定义、10 条现成网格、4 组采样预设、只读生效快照、全参数日志。
  全部从既有 `Prefs` / `ProduceConfig` 读，**不存第二份真值源**。
- `MainActivity.kt` 加独立 `MixerScreen`（当前生效 / 音色身份 / 采样 / 边界只读 / A/B 试听），
  设置页顶部醒目入口、底栏「设置」高亮扩到 MIXER。7 处联动先列表再动。
- `docs/标注工厂_输出契约.md` + `听本_标注工厂示例.json`：沙盒骨架化标注的 schema/4 道校验/
  字段纪律/作用域划分；示例 27 段、3 说话人、逐字相等、区间连续。**只定契约不接管线**。

**7 处联动**：Screen 枚举与 when 分支 / SettingsScreen 签名 / 底栏高亮 / 设置页入口 /
Prefs 写入走既有入口 / 缓存作废复用 R14 / 日志新增两事件。
`Producer.start` 签名、`bucket()`、TaskResume、native、prompt v2 一行没动。

**验证**：`check_structure.py` 追加 R31 符号 38 项 + 反向断言（不许第二份真值源、
不许 A/B 写回 Prefs、不许没接导航），本地 25 项全过；新增段字符串模板栈扫描平衡；
**CI run 40 全绿**；拆包 CRC 全过 / 7 个 arm64 .so / 权重 0 / 无 INTERNET /
binary Manifest `1.0.0+40` / dex 23 个 R31 字面量全在。
另 3 个（`Mixer.snapshot`、`Screen.MIXER`、`Anatomy-First`）是源码级引用或注释，编译后不进 dex
字符串池，属预期，由源码层自检覆盖，不当作缺失。

**发布**：`1.0.0+40` · **17,869,546 字节** · 固定直链同步 · 直接覆盖装。

⚠️ 真机仍是零证据：A/B 能不能在 24117RK2CC 上听出差别、稳定档是否真减少电音感，都要跑一次。
控制台解决的是「看得见、调得动、能复盘」，不是「声音已经变好」。

**队列（方向变更后重排）**：① 标注工厂导入通道（接契约进 App，跳过 4B）→ ② 调音台显示当前任务真实
ProduceConfig → ③ A/B 结果落盘成可复用预设 → ④ 端侧 4B 标注标为实验性/已撤主路。


---

## U1 · 全局壳（R32 落地）

- 新增 `AppChrome.kt`：顶层目的地常量 / `ChromeSpec` / `AppTopBar` / `AppBottomBar` / `RefreshStrip`，抄 Now in Android `TopLevelDestination` 形状，零业务逻辑、颜色由调用方传入。
- `MainActivity.kt`：顶栏删产品名+定位+文字刷新按钮；底栏 4 Tab → 3 Tab；设置移左上角 ☰；新增 `chromeOf(Screen)` 单一映射（顺手修 CAST 从不亮 Tab）；下拉手势抽成可复用件；补 `BookStore.delete` 后的 `onChanged()`。
- **守恒**：Screen 9 值 / when 9 分支 / refresh 计数器 / BackHandler / modelsFrom / 外部 intent 落地 / ProduceService / 前台服务 / 4B 与 prompt / native / Manifest / Gradle 全部零 diff。
- 验证：CI 自检加 U1 守恒断言；`scripts/r32_verify.py` 反向注入 11/11 拦下，并抓出 4 个假绿（注释骗过字符串断言、`mask_strings` 令 R20 KDoc 检查恒不触发等），已全部修掉。
- 本地无 gradle-wrapper.jar / Android SDK，**真编译由 CI 承担**。
- 未做：Material3 PullToRefreshBox 与 4 页下拉、书架页细节（U2）、其余页文案（U3）、调音台一级入口（U4）。


### U1 验证补记（CI run 41）

- git push 被网关 403 → 改走 GitHub Git Data API 单次提交（`b36242fc`），触发 **run 41，conclusion=success，JOB build success**（真 Kotlin 编译，不是静态自检）。
- 产物 `app-debug.apk` **17,869,552 字节**，从 `tingben-apk` 仓库 main 经 blob API 取回（Release asset 的 302 下载也被网关挡了）。
- 拆包硬验：`CRC_BAD=None` / 7 个 arm64-v8a .so / 无 .gguf / binary Manifest `1.0.0+41` / 无 INTERNET、FOREGROUND_SERVICE + POST_NOTIFICATIONS + WAKE_LOCK 均在 / 7 个 dex。
- dex 新符号全命中：`AppTopBar / AppBottomBar / RefreshStrip / ChromeSpec / chromeOf / TAB_LIBRARY / TAB_NONE / ☰ / 下拉刷新 / 调音台 / MIXER_PROFILE`；旧符号零残留：`⟳ 刷新 / TabButton / 下拉刷新 · 或点右上角 / 顶栏产品名与定位`。
- 一处如实说明：`BookStore.delete(...); onChanged()` 这种**源码级**串不会原样出现在 dex 里（编译成 lambda 类），由 `check_structure.py` 的源码断言负责，不拿 dex 字符串当证据。
- ⚠️ **真机仍是零证据**：本轮没在 24117RK2CC 上点过任何一屏。重点验「左上角 ☰ 能否进设置」「设置域 4 页返回路径」「书架下拉刷新是否仍触发」「删书后列表是否立刻消失」。


---

## R33 · U1 返工：状态栏安全区 + 全局清注释式文案（`1.0.0+43`）

- 用户点名做脏：☰/＋ 仍顶进状态栏、下拉刷新还带小字、大标题与「书架只放原书」等注释式文案到处都是。本轮只改表现层。
- 落地：外层 `Column` 加 `statusBarsPadding()`；`RefreshStrip` 零文字零 Toast（手势阈值 `acc > 55f` 原样）；☰ 改 `DropdownMenu` 一级导航并顺手把调音台提成一级入口；书架大标题/副标题/重复任务卡删；粘贴框默认展开带粘贴/清除/建书；书卡图标化 + 删除 `AlertDialog` + 删完 `onChanged()`；常驻任务条改上扒展开/下扒收起、点整卡不再跳页；其余 7 页页面级大标题与教程说明一并清理。
- **一行未动**：`Screen` 9 值、`when` 9 分支、`refresh` 计数器、`BackHandler`、`chromeOf`、`modelsFrom`、外部 intent 落地、`Producer.start`、`ProduceService`、native / prompt v2 / `Prefs` / 缓存分桶 / 任务书签 / 4B 设置。
- **run 42 失败**：真编译抓到两处静态括号计数抓不到的连带错误（悬空 `if`、任务页多一个 `} else {`），已修并在清理脚本加通用检测。**run 43 全绿**（真 Gradle 编译，17 步全 success）。
- 验证：结构自检 25 项；`r33_verify.py` 反向注入 6/6 全拦下、基线全绿、文件逐字节还原；`r33_apk_audit.py` 拆包 CRC / 7 .so / 权重 0 / 无 INTERNET / `1.0.0+43` / 14 新符号 / 9 旧文案零残留。收尾复核订正两处校验陷阱：binary AXML 权限需按 UTF-16LE 搜；「把小说变成」前缀误报实为通知渠道 description，改精确串并登记 U2。
- **发布**：`1.0.0+43` · **17,869,544 字节** · 固定直链同步 · 直接覆盖装，模型与音频保留。
- ⚠️ 真机仍是零证据。下一轮：真机验 → U2 其余 3 页下拉刷新 + 通知渠道描述清理 → 语言模型设置沉页尾 → 沙盒标注导入通道（各自独立一轮）。


---

## R36 · 调音台重设计：折叠分组 / 自定义预设 / 全参数覆盖（`1.0.0+47`）

- 本轮把工作区里已应用、未验证未提交的 R36 半成品跑完：验证 → 提交 → 构建 → 拆包 → 直链核对。
- 调音台从 5 张平铺卡改成 8 个折叠组（默认展开 2）；预设可存可删、同名覆盖、`.part` 原子落盘；12 维音色逐维可编辑；9 项采样含 `max_new_tokens`；表演层 5 项标「仅本次」；4 张内置角色卡；参数覆盖核对表；A/B 不写回长期默认。
- 三处验证脚本订正：正向断言查错对象、对拍没模拟 `save/fromJson`、KDoc 行级规则误伤合法行尾收尾符；反向注入 7/7、KDoc 1/1、对拍 6 组，还原基线 PASS。
- 一行未动：4B / prompt v2 / `Producer.start` / `TaskResume` / `bucket()` / native / Manifest / Gradle 依赖；续跑仍读 `TaskInfo.config`。
- **run 47**（Actions 列表状态滞后显示 queued，以 job steps 与直链产物为准）→ `1.0.0+47` · **17,902,303 字节**；拆包硬验全过、直链 blob `016824ae…` 与本地逐字节一致。
- ⚠️ 真机仍是零证据。下一轮：① 真机验 → ② **V1 抽屉壳**（R34/R35 定案）→ ③ 标注工厂 JSON 导入通道 → ④ 调音台显示当前任务真实 `ProduceConfig` → ⑤ 4B 设置沉页尾。


---

## R37 · 项目文件维护（纯文档轮，不发版）

- 用户指令「去维护一下项目文件」。**零业务代码、零 CI、零发包**；版本仍是 `1.0.0+47`（本地 APK 17,902,303 字节）。
- 维护前扫描到的失真，全部订正：
  - `STATUS.md` 头部「未验 / 未做」还写「装 +35 验证」「P1③ 段级重做未做」——R27/R28 早已落地；改历史留痕，并把「下轮队列」从 R27 时期换成与文末 R36 一致的现行队列（真机验 → V1 抽屉壳 → V2/V2b/V3 → 导入通道 → 真实 ProduceConfig → 4B 沉页尾 → U2）。
  - `STATUS.md` APK 行「见文末 R27 发布记录」→ R36。
  - `README.md` 版本举例 `+43 → +47`；调音台段从 R31 旧版（5 卡 / 4 预设）改成 R36（8 折叠组 / 自定义预设库 / 12 维逐维 / 9 项含 max_new_tokens / 表演层仅本次 / 4 角色卡）；代码地图 Mixer 描述与 scripts 清单同步。
  - `docs/R19_路线图与决策记录.md` 头部追加 R37 订正：R31「4B 撤主路、标注改沙盒做」作废，正确口径是「发力点转音频调参；4B 原样保留；沙盒是可选第二输入」；该文件自此只作历史记录。
  - `docs/听本_项目导图.html` ⑥ 段 P1②/③ 标已落地、④ 性能页标删除；追加 ⑩ 段（R33~R36 落地 + 现行队列 + 历史面板「下一轮」仅留痕）；footer 更新。
  - `docs/ITERATION_LOG.md` 指针去掉过期的「本轮联动表：P1②」，改指向 STATUS 现行队列 / R34 / R35 / R36。
- `STATUS.md` 文末同步追加本条维护记录；两份迭代日志仍遵守「根目录是唯一真源」。
- 纪律不新增、只重申：交付前核直链 blob sha；静态自检 ≠ CI 真编译；「收敛」不写成「根治」；方向表述以 R33 订正为准。
- **队列不变**：下一轮第一件仍是 **V1 抽屉壳**，单独一轮。


---

## R38 · 调音台重做：首屏音色身份 / 官方字段一一对应 / 触达证据（`1.0.0+48`）

- 用户：「音色身份输入框怎么没了」「控制台要一一对应官方发布页资料，我去看」「参数有没有真正触达」「这是给人用的」。
- 调查：输入框没删，埋在默认收起的 12 维折叠组里、标签「补充描述」；`voiceDraft` 缺 `key(tick)` 导致显示≠生效；
  12 维只在内存不落盘、选完不点应用不生效；预设存旧生效值。**功能在，人找不到。**
- 落地：首屏固定卡（当前生效只读 / 草稿 / 保存 / 清空回默认）；9 项采样逐项标官方字段名 + 默认 + 说明 +
  触达状态 + 精确值 + 恢复默认（默认展开）；触达三态矩阵 + 官方资料索引 O1~O7；12 维只「写入草稿」；
  试音 `lang=auto→chinese`；`snapshot()` 标签改「跨书默认（新任务）」。
- 双证据：`TTS_PARAMS`（Kotlin 传出，三路径统一）+ `TTS_PARAMS_APPLIED`（JNI 覆盖 `qt_tts_params` 后打印实收）。
  `r38_param_audit.py` 基线 PASS / 9 项逐字段 / 5 例反向注入全拦下；`check_structure.py` 28 项过。
- **零 diff**：4B / prompt v2 / `Producer.start` / `bucket()` / `TaskResume` / 缓存分桶 / JNI 签名与顺序 /
  采样链 / Manifest / Gradle / 权限。
- **run 48 completed success**（17 步全 success）→ `1.0.0+48` · **17,919,089 字节** ·
  直链 blob `61c1366e…` 与本地逐字节一致（raw 直链网关超时，走 blob API 取回 + git blob sha 自校验）；
  `r38_apk_verify.py` 拆包全过：CRC / 7 .so / 权重 0 / 无 INTERNET / 3 权限 / binary Manifest `+48`（UTF-16LE）/
  `.so` 里 `TTS_PARAMS_APPLIED` / 29 新符号 / 10 旧文案零残留。
- ⚠️ 真机仍零证据：首屏输入框、诊断 `TTS_PARAMS` 对照、A/B 可听差别都要点一次；交付的是「看得见、查得到、有触达证据」。
- **队列不变**：① 真机验 `+48` → ② **V1 抽屉壳**（单独一轮）→ ③ V2 目录+绿勾 → ④ V2b 看书页 →
  ⑤ V3 迷你条 → ⑥ 标注工厂导入通道 → ⑦ U2 顺延项。


## R39 · 调音台评审：只出联动表与分轮（纯协调轮，无包）

- 用户给 17 条评审 + 页面骨架 + 6 条验收；定调「生效状态被拆成 4 套语义、唯一全貌埋底」。
- 实查是 **5 个写入口**（身份保存 / 九项设定 / 12 维套用 / 表演层长期默认 / 预设应用）；
  定 `MixerDraft` 唯一编辑态 + `MixerCommit.commit()` 唯一出口，17 条分 R40/R41/R42 三轮。
- 零代码、零 CI、零发包；`docs/R39_调音台统一提交_联动表与分轮.md`。

## R40 · 调音台统一提交 P0（`1.0.0+49`）

- 用户「去做吧」→ 按 R39 队列只做 P0 六条，P1/P2 不夹带。
- 落地：吸顶状态条 / 差异弹层（全貌 + 逐项旧→新）/ ② 只留「填入草稿」+ 可编辑预览框 /
  ① 已生效 vs 草稿对照 + diff 高亮 / ⑦ 降级导出诊断 / ⑤ 角色 chip + 未绑定提示 + 删除确认；
  ⑥ 预设改「载入为待提交」；`TimingHint` 统一时机；离开有未提交先问。
- **5 个写入口 → 1 个**：界面里 `Prefs.set*` 六个入口清零，唯一出口只写既有 5 个 Prefs。
- 验证：本地 diff 对拍 6 组 / 6 例反向注入 / 13 项守恒全过；`check_structure` 28 项、
  `r38_param_audit` 6 例反向注入、`r40_apk_verify` 31 新符号 + 13 条旧动词零残留；
  **CI run 49 completed success**（真 Kotlin 编译）。
- 验证脚本自身写错 3 次（put() 定义被计数、base() 不可达 return、注释骗过符号断言）均修正，如实记录。
- `1.0.0+49` · **17,935,462 字节** · 直链 blob `ffbe20d556…` / Release `v1.0.0-49` / 本地三者一致。
- ⚠️ 真机仍零证据；交付的是「状态不再错乱」，不是声音变好、也不是界面全改好。
- 队列：真机验 → R41 P1 → R42 P2 → **V1 抽屉壳**（连续顺延）→ V2/V2b/V3 → 导入通道。

## R41a · 调音台试听改版（插队轮，`1.0.0+51`）

- 用户「A/B 试听我不懂」→ 只要两个试听按钮：未提交参数 / 已生效参数，放在「提交变更」附近同一模块；末尾补「返回」。
- **先协调再动代码**：`docs/R41a_试听改版_联动表.md` 实查 6 项现状（⑨ 折叠区在页底、6 步操作、A=active / B=预设、
  instruct 两边共用 `Prefs.voiceDescription` 导致 A vs A），列 11 处联动 + 守恒 + 6 条验收。
- 落地：底部 ⑨ 整块删除；吸顶 `CardBox` 内加两按钮 + 单行试听文本 + 缺模型提示；「‹ 返回」也挪进该块。
  两态各用整套 `MixerDraft` 经 `VoiceProfile.build()` 拼 instruct，一步合成 + 自动播放；播放复用 `PlayQueue`。
  日志 `MIXER_AB/FAIL` → `MIXER_TRIAL/FAIL`，写来源 + 9 参数 + hash + voiceChars + instruct 全文。
- **同步改验证脚本**（改一处连着一堆）：`check_structure.py`（CI 必跑）R31 正向断言 + 旧 A/B 零残留反向断言；
  `r36_verify.py` 把 R38/R40 之后已过期的 9 个 `SettingOption` / `MIXER_PERF` 断言同步到当前形态；
  `r40_apk_verify.py` 新符号清单；新增 `r41a_verify.py`（正向 / 两态对拍 / 6 例反向注入 / 守恒）并进 CI step。
- 本地：5 套脚本全绿（结构 28 项、R38 矩阵 6 例反向、R40 6 组对拍 + 6 例反向、R36 7/7 + 6 组对拍、
  R41a 3 组对拍 + 6/6 反向）；守恒 13 项与 `git HEAD` 逐字节一致，`MainActivity` 前 1880 行未动。
- 验证脚本自身写错 2 次（裸 `MIXER_TRIAL` 被 `MIXER_TRIAL_FAIL` 子串骗过假绿、新增文件与 HEAD 比对报「被改动」），
  均按「反向验证必须真注入真 FAIL」修好，如实记录。
- ⚠️ 真机仍零证据；试听期间离开页面可能只写出 wav 不自动播放（协程被取消），已在联动表写明。
- 队列：真机验 → **R41(P1)** → **R42(P2)** → **V1 抽屉壳**（连续顺延）→ V2/V2b/V3 → 导入通道 → U2。

### run 50 真编译失败复盘（静态自检全绿，CI 抓到两处）

- `Unresolved reference 'label合成'`：`"正在用$label合成…"` 里中文紧跟 `$变量` 被 Kotlin 连成一个标识符，
  必须写 `${label}`。→ 补「字符串模板后紧跟中文」硬断言 + 反向注入用例。
- `Unresolved reference 'play'`：`trial()` 调用后面才定义的局部函数 `play()`，
  Kotlin 局部函数不许前向引用。→ `play` 移到 `trial` 之前，补「play 必须在 trial 前」断言。
- 日志由 workflow 的「编译失败 → 日志回写」步骤写回仓库 `logs/build-50.md` 才定位到；
  **再次印证「自检全绿 ≠ 编译过」**。run 51 重编通过。

### 最终交付

- **run 51 `completed success`**（20 步，native 缓存命中）；APK `17,935,474` 字节，直链 blob `68e6b159dd63…` 与本地逐字节一致。
- 拆包硬验：CRC / 7 个 arm64 .so / 权重 0 / 无 INTERNET / `1.0.0+51` / 新符号全在 / 旧 A/B 字面量零残留 /
  `.so` 里 `TTS_PARAMS_APPLIED` 仍在。


## R41b · P1 收尾（run 52 / 1.0.0+52）

- **补齐上一轮三处未完**：CI 挂 `r41_verify.py`（联动表第 80 条）、项目文件头部刷到 `+52`、APK 取回核验。
  本轮零业务代码改动，`MainActivity.kt` 与 run 52 构建产物一致。
- **验证脚本自身订正**（不是放水）：`r36_verify.py` 4 条过期字面量换成 R41 等价断言 + 反向注入 9/9；
  `r40_apk_verify.py` 资料区标题订正、补 12 新符号 / 7 条旧编号零残留 + 反向注入 2/2。
  新增按 run 号参数化的 `r41b_fetch_apk.py`，每轮交付前核直链 blob，不再写死版本号。
- **CI run 52 `completed success`**（26 步，native 缓存命中）；拆包 CRC / 7 .so / 权重 0 / 无 INTERNET /
  `1.0.0+52` / 新符号全在 / 旧编号零残留 / `TTS_PARAMS_APPLIED` 在；直链 blob `e32c2e90…` 与本地
  17,968,245 字节逐字节一致。
- 队列：真机验 → R42(P2) → V1 抽屉壳 → V2/V2b/V3 → 导入通道 → U2。


## R42 · P2 文案 / 响应式 / 视觉（run 53 / 1.0.0+53）

- **评审 P2 五条落地**：13 开发者信息全收 `AdvancedDrawer` 默认折叠（吸顶条与提交提示去指纹）；
  14 12 维与表演层统一 `ChipRow`（官方 `FlowRow + FilterChip`）+ `@Preview(widthDp=375)`；
  15 `SwitchRow` 开/关文字 + `HelpNote` info；16 说明降级 ⓘ、分区与弹层改细分割线、主色收口；
  17 `TimingHint` 补到 8 处、删「不会自己生效」第二套说法。**只改调音台 UI，主链一行不动。**
- **联动 11 处 + 守恒 12 文件**：`MixerDraft` / `ParamSpec` / `Mixer` / `Prefs` / native / Manifest / Gradle / prompt 逐字节一致。
- **验证脚本自身订正 3 处**（不是放水）：r42 基线误传 `Mixer.kt` 查官方字段 → 改传 `ParamSpec.kt` 并纳入 errs；
  `ChipRow` 出现数 18 → 9（1 定义 + 2 Preview + 1 循环 + 5 表演）；`r36_verify.py` 表演层断言从
  `SettingOption` 同步到 `ChipRow` 新形态 + 补「回退」反向用例（10/10 真拦下）。
- **本地 7 套脚本全绿**；CI **run 53 全绿**（27 步，新增 R42 验证 step）；
  拆包 CRC / 7 .so / 权重 0 / 无 INTERNET / `1.0.0+53` / R42 新符号 / 旧文案零残留 /
  `TTS_PARAMS_APPLIED` 在；直链 blob `f984cc977abbee2e233d18d96b264f8f6ede3aab` 与本地 17,968,240 字节逐字节一致。
- 队列：真机验 → V1 抽屉壳 → V2/V2b/V3 → 导入通道 → U2。


## R45 · 调音台 v4 落地（run 55 / 1.0.0+55）

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
- **拆包硬验 PASS**：CRC / 7 个 arm64 .so / 权重 0 / 无 INTERNET / `1.0.0+55` / 41 个新符号 / 18 条旧符号零残留 /
  `TTS_PARAMS_APPLIED` 在；直链 blob `e5a126d4d66a62f4933d671dc5c81847b5f9061e` 与本地 17,984,619 字节逐字节一致。
- ⚠️ **真机仍零证据**。队列：真机验 → V1 抽屉壳 → V2/V2b/V3 → 导入通道 → U2（采样 Slider 双模待用户点头）。


## R46 · V1 抽屉壳落地（run 56 / 1.0.0+56）

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
  CI **run 56 `completed success`**（新增 R46 验证 step）；拆包 CRC / 7 .so / 权重 0 / 无 INTERNET / `1.0.0+56` /
  抽屉新符号全在 / 旧壳零残留 / `TTS_PARAMS_APPLIED` 在；直链 blob `533dcb1c…` 与本地 17,984,627 字节逐字节一致。
- **验证脚本自身订正 3 处**：`check_structure.py` 的 U1/R33/R36 旧壳断言同步到 V1 形态、`when(screen)` 分发块改带上下文锚点
  （新增的两个 when 曾让分支计数误报 7）；`r45_common.canon()` 剔除 App() 壳区域；`r45_verify.py` Prefs 守恒 marker 笔误（HEAD 里找不到旧注释，切片恒空）。
- ⚠️ **真机仍零证据**。队列：真机验 → V2 目录页+绿勾 → V2b 看书页 → V3 迷你条 → 导入通道 → U2。


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
  `TTS_PARAMS_APPLIED` 在；`--selftest` 反向注入正确拦下；直链 blob `8f96fc26…` 与本地 17,984,624 字节逐字节一致。
- **验证脚本自身订正 5 处（不是放水）**：`r41a_verify` 旧断言要求两态按钮在吸顶 `CardBox` 内、
  `r42_verify` 断言吸顶大卡存在、`r45_common.canon` 的 AnchorBar 剥离 marker 在 R49 被替换掉、
  `r47_verify` 滑块直写 Prefs 注入按旧缩进、`r49_verify` 初版参数解析没处理跨行 / HEAD 落后一轮的守恒口径。
  首轮本地复跑真实抓到 5 处失败，逐一定位修完才推；CI 没当偶放。
- **明确不做（本轮）**：固定音色不做独立 `Screen`（要动枚举/chromeOf/抽屉/通知回跳）；
  不做老用户展开态一次性清空（R41 要求「记住上次展开」，新装/清数据默认全折叠）；
  v4 ② 设置页语言模型沉底、v4 ⑫⑬ 底部三层分区专图仍属既有差额，单列下一轮。
- ⚠️ **真机仍零证据**。队列：真机验 → V2 目录页+绿勾 → V2b 看书页 → V3 迷你条 → 设置页语言模型沉底 → 导入通道 → U2。

## R50 · 调音台真机三处修补（run 60 / `1.0.0+60`）

**输入**：`+58` 真机两张截图 + 三问（采样行滑块/输入框要调整、表演层要有自定义输入块、
往下扒时锚点标签为什么不跟着变）。联动表先列 3 处根因 / 守恒 / 6 条验收，再动代码。

- **锚点为什么不变（实查）**：`activeAnchor` 只在 `jumpTo()` 与「固定音色是什么」里赋值，
  `AnchorBar.selected` 只读它，`scroll.value` 没有任何回写，注释还写「不从滚动位置猜」。
  补 `snapshotFlow { scroll.value to anchors.toMap() }` 反算当前区；`jumpLock` + `finally` 防点击抖动；
  未列进栏的区（试听/开关/诊断）归并到前一个可见锚点；固定音色入口统一走 `jumpTo`。
- **采样行**：带 `label=精确值` 的 M3 输入框最小高 56dp 把整行撑到约 180px。改「名字 78dp ·
  滑块 weight(1f)/28dp · 无标签 72×38dp 数字框 · ⟳ · ▾」同一中线，`Modifier.height` 强约束压掉
  M3 默认 56dp 内高，范围/官方字段/任意合法值说明进展开 ⓘ；`ParamSpec` 9 项与 onInput 链路零改动。
- **表演层自定义（真生效，不是假开关）**：`ChipRow` 自定义框统一 140×40dp 小框（12 维同形态）；
  5 项全开。三枚短语框 → `ProduceConfig.packExtra/unpackExtra` → `extraInstruct` →
  `styleInstruct()` 拼 instruct、`audioStamp()` 进指纹、`toJson/fromJson` 存读、schema 2 宽容迁移；
  两枚数值框写 `gapMs`/`gateRetries`，**修掉旧 `fromJson` 白名单静默重置自定义值**的真 bug
  （改 `0..5000ms` / `0..10` 范围校验）。`MixerCommit.diff` 补「附加语气」，
  界面态 `perfCustom` 在提交 / 丢弃 / 载预设三处 `syncPerfCustom()` 同步。
- **守恒**：唯一提交出口、唯一生效动词、两态试听、9 项参数表、4B / prompt v2 / 主链 /
  `bucket()` / 续跑 / FGS / native / `qt_tts_params` / Screen / 抽屉 / Manifest / Gradle 全不动。
- **验证**：`r50_verify.py` 正向 50 + 数据对拍 11 + 反向注入 10/10 全拦下；本地 11 套 `CI=true` 全绿。
  首轮复跑抓到 5 处**旧脚本断言按上一版形态写死**（diff 字段数 9→10、已删 label、84/74/30dp 旧尺寸、
  旧注入目标、r50 初版 `re.S` 跨行误命中），逐一定位订正并保留等价新断言，不是放水。
- **CI run 59 真编译失败（已修）**：按新版 M3 想当然给两个小框加了 `contentPadding` 参数，
  BOM `2024.12.01` 的 `OutlinedTextField` 无此参数 → `None of the following candidates is applicable`。
  静态自检抓不到「API 不存在」；改 `Modifier.height` 强约束 + 新增禁止该参数的反向注入，run 60 复验。
- **CI run 60 复验全绿**：`completed success`（30/32 步 ok，2 步缓存 skipped），真 Kotlin 编译通过，
  产物 `1.0.0+60`；Release `v1.0.0-60` + 直链双通道发布。
- **拆包硬验**：新增 `scripts/r50_apk_verify.py`（含 `--selftest` 反向注入）—— CRC 全过 / 7 个 arm64 .so /
  权重 0 / 无 INTERNET / binary Manifest `1.0.0+60` / dex 33 个新符号全在 / 15 条旧形态零残留 /
  `TTS_PARAMS_APPLIED` 在。同步订正 `r49_apk_verify.py`：R49 的「精确值」正向断言在 R50 已属旧形态，
  移到零残留侧，避免误跑假红。
- **直链核对**：18,001,010 字节，blob `7f4297dc965a00a13269ab053249aed57d8056fa`（按 git blob 算法复算）
  与本地 APK 逐字节一致；文档用 API 增量同步、带 `[skip ci]` 未触发多余构建。
- ⚠️ **真机仍零证据**。队列：真机验 R50 → V2 目录页+绿勾 → V2b 看书页 → V3 迷你条 → 设置页沉底 → 导入通道 → U2。


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

## R57 / R58 · 换模型联动表 + 双页项清单（纯设计轮，App 仍 1.0.0+65）

- **背景**：用户决定把端侧语言模型换成 `Huihui-Qwen3-4B-Instruct-2507-abliterated Q4_K_M`（已在手机下载），并要求「设置页只管软件，语言模型另开独立板块」「不是去干活，是把代码摸清楚、说清两页该有哪些项、怎么设计」。
- **最硬实查**：新权重官方明写「仅非思考、不生成 `<think>` 块、不需要 enable_thinking=False」，而 `Pipeline.kt:287-288` 仍在 user 末尾追加 `/think` / `/no_think`，`Pipeline.kt:819-823` 用 `thinkOn` 决定 temp 0.6 与缓存目录，`MainActivity.kt:1538` 摆着「思考过程」开关 → 换模型后这些全变假开关。据此判定 **R55 项清单 v1 有一半作废**，必须按权重重排。
- **R57**：11 处联动逐文件逐行（manifest / 下载直链 / ModelStore 白名单 / prompt 装配 / ChatML / 生成参数 / 缓存分桶 / Prefs 旧键 / extractThinking / 设置页拆分 / 诊断加 MODEL_ID），附官方采样口径对照（2507 推荐 0.7/0.8/20/0，native 只实现温度 + 固定 top_p 0.9 + seed 42）、A/B 四项 gate 与回退方案、守恒清单。
- **R58**：设置页只留外观（夜间模式/字号标「待新增」，不画假开关）、数据与日志、后台与关于、一行「语言模型设置 ›」；语言模型页按新权重给只读模型卡 + 已移除说明 + 主层三项 + 高级五项 + 诊断评测，三态徽标与调音台同套语义，`top_k` / `min_p` / 思考开关等 12 类明确不画。
- **v8 预览 12 屏**：① 设置首屏 ② 外观 ③ 数据日志 ④ 后台关于 ⑤ 语言模型首屏 ⑥ 高级 ⑦ 提示词只读 ⑧ 换模型项清单对照 ⑨ 三态总览 ⑩ 两页全展开长图 ⑪ 375px + 手势/三键 ⑫ 11 处联动与 A/B 流程。脚本 `mixer_preview/make_settings_v8.py` 自带断言：14/14 无横向溢出、设置页无调音台入口/提交/圈码、「思考/深度」不许渲染成开关行、全展开四组齐全。逐张看图后自己又改掉 7 处评审腔与技术黑话（`enable_thinking=False`、`R33 已修`、`native 只实现…`、`不画假开关` 等）。
- **零业务代码、零 CI、零发包**，APK 仍是 `1.0.0+65`；项目状态文件仅追加本条，头部版本未动。⚠️ 新权重仍在下载，未导入、未加载、未跑过一块，「更会写 JSON」需过 A/B gate 才算数。
- **队列**：预览通过 → 下一轮按 R57 的 11 处联动落代码并单独发版 → 真机验 → V2 目录页+绿勾；性能二期、夜间模式实现不夹带。

## R59 · 项目文件维护（纯文档轮，不发版 / 不装包）

- **用户指令**：「维护一下项目文件，过时的没用的该删就删；后面要怎么做你也要想好了」。
  **零业务代码、零 CI、零发包**，APK 仍是 `1.0.0+65`（18,001,017 字节 / blob `52bf35a2…`）。
- **失真实查**：`STATUS.md` 头部方向仍写「换模型不做」（与用户已决定换语言模型冲突）、
  「未验/未做」停在 R41b、队列停在 R50；`README.md` 逐轮堆到 R51、模型表仍是旧权重、
  设置页描述与源码不符（源码里「思考过程」开关还在）；`docs/ITERATION_LOG.md` 指针停在 `+47`；
  `R19` 头部三条互相覆盖的订正；导图每个卡片都写「下一轮 → V2」，全是当时口径。
- **清理**：`docs/` 从 74 份收敛到 **30 份现行依据 + 1 份历史索引**，44 份一次性联动表 / 落地报告 /
  早期调研移出仓库；`scripts/` 从 59 份收敛到 **17 份**（CI 依赖 16 项逐项校验全在），42 份一次性
  apply / docs / finalize / fetch / 拆包脚本移出。原文打进 `听本_R59_历史文档.zip` /
  `听本_R59_历史脚本.zip`，位置索引 `docs/HISTORY_INDEX.md`，**历史复盘仍以本文件为唯一真源**。
- **三份真值源收敛**：`STATUS.md` 重写成纯「现在是什么」（不再追加逐轮复盘、不再写第二份队列）；
  新建 `ROADMAP.md` 作为**唯一**现行队列；`README.md` 精简并修正方向 / 模型 / 设置页描述；
  导图加「历史快照」横幅；`R19` 头部三条订正收成一句；`docs/ITERATION_LOG.md` 指针更新。
- **路线重排**：P0 换语言模型 + 双页设置拆分（R57 11 处联动 + R58 v2 清单 + A/B 四项 gate 与回退）
  → P1 V2 目录页 + 按章绿勾 → P2 看书页 → P3 迷你条 → P4 沙盒标注导入 → P5 性能二期
  → P6 软件设置补齐；真机验贯穿每轮，不单独占一轮。明确「音频模型不换 / 系统 TTS 现在不加 /
  性能页删除 / 4B 不撤」四条台账，以及「换模型不做」「4B 撤主路」两条作废口径。
- ⚠️ **新权重仍在用户手机下载**，未导入、未加载、未跑过一块；本轮未动 manifest / 下载直链 /
  `ModelStore` / prompt / native / 设置页代码。维护报告见 `docs/R59_项目文件维护报告.md`。


## R60 · P0 换语言模型 + 设置页/语言模型设置页拆分（1.0.0+66 / run 66）

- **用户指令**：v8 预览 12 屏「通过」；新权重 `huihui-qwen3-4b-instruct-2507-abliterated-q4_k_m.gguf` 已下载；「去做吧」→「继续」。
  上一轮只做到代码与本地自检，**没发包、项目记录未回填**，本轮补齐收尾。
- **换权重 11 处联动**（R57 逐项）：manifest 换新名/新 repo/国内直链/`仅非思考` 口径并登记官方 2507 回退基线、
  **实测字节数与 sha 留空不写假值**；模型页下载地址换 `kuronekkko`；`ModelStore.LLM` 换名 + `LLM_LEGACY` 旧名白名单；
  `buildPrompt` 删 `/think` `/no_think`、按 GGUF 自带 chat_template 对齐手写 ChatML；温度统一 `Prefs.temperature(c)`、
  删思考档 0.6 覆盖；缓存统一 `m2507/` 前缀一次作废旧两套；`KEY_THINKING` 留兼容读、界面不再写；
  `extractThinking` 降级为防御；抽屉加 `NAV_LLM` + `Screen.LLM -> LlmSettingsScreen`；诊断加 `MODEL_ID` 与 `AbGate` 四项统计。
- **双页拆分**（v8 / R58 v2）：设置页只留外观 / 数据与日志 / 后台 / 关于 + 一行「语言模型设置 ›」；
  语言模型页给只读模型卡、已移除项说明、主层三项、高级只读五项、诊断与 A/B 评测。
  思考开关、`top_k` / `min_p` / YaRN / GPU 层数、可编辑提示词等物理不存在的项**一个都不画**；
  制作准备页「思考过程」行改为只读「不适用 · 新权重仅非思考」。
- **守恒**：`prompt_zh.txt` v2、`Adoption` 逐字 gate、`TextSplitter`、两条制作路径、`tingben_native.cpp` 采样链、
  `MixerDraft.commitAll()` 与两态试听、`ProduceService`、Manifest、Gradle 依赖一行未动。
- **验证**：本地 15 套 `CI=true` 全绿；`r60_verify.py` 11 处逐项 / 旧形态零残留 / 守恒 / 反向注入 10/10 拦下；
  **CI run 66 `completed success`**（真 Kotlin 编译）；新增 `scripts/r60_apk_audit.py` 拆包硬验：
  CRC 全过 / 7 个 arm64 .so / 权重 0 / 无 INTERNET（ASCII + UTF-16LE 双查）/ `1.0.0+66` /
  20 新符号全在 / 7 条旧形态零残留 / `TTS_PARAMS_APPLIED` 在 / 直链 blob 与本地逐字节一致，`--selftest` 正确拦下。
  旧权重文件名仍应在（`LLM_LEGACY` 白名单），脚本列为必在项而非残留。
- **发布**：远端 API 单次提交 `826183697b`（base_tree 只增不删）触发 run 66；raw 域名 502，
  改从 `git/blobs` base64 取回 APK，**18,017,394 字节 / blob `4ed33b068173…`**，与直链仓库 tree 一致；
  项目文件回填后带 `[skip ci]` 同步。落地报告 `docs/R60_P0换模型与双页拆分_落地.md`。
- ⚠️ **新权重已下载但仍在 App 外**：未导入、未加载、未跑过一块；CI 全绿只证明代码路径切换，不等于模型跑起来。
  四项 A/B gate 全不劣于基线才替换线上，否则回退官方 2507 Q4_K_M 基线。真机仍是零证据。
- **队列**（`ROADMAP.md` 唯一真源）：真机验 → P1 V2 目录页 + 按章绿勾 → P2 看书页 → P3 迷你条 →
  P4 标注导入 → P5 性能二期 → P6 软件设置补齐；P0 代码侧已落地，剩余是用户侧导入与 A/B。


## R61 · 目录页四列与悬浮条（1.0.0+68 / run 68）

- **用户指令**：「很不错，说明你是听懂了，咱这一页就这么做吧」= 批准 v9 全 8 屏预览。此前用户把四列、
  勾选框、底部三条、半透明进度条、悬浮条逐条想完，并让我把「没想好的五处」补齐（直接合成章落第三列、
  悬浮条目录弹层、第四列三控制、顶部标签、标注结果页只读）—— 全部按 `docs/R61_目录页四列与悬浮条_补齐决策.md` 落。
- **硬前提先解决**：`AudioItem` 只有 `scope` 文字，章节索引在 `register()` 当场丢掉；`.chapters.json` 侧车是全书章名。
  本轮给 `index.json` 加 `chapterIndices` + `chaptersKnown`，旧「整本 / 选中 N 章 / 旧版」一律 `known=false` 不点亮单章。
  阶段 A 6 组往返对拍全过，才进界面层，不与界面混做。
- **13 处联动**：新 `ChapterHub.kt`（四列 / 勾选 / 小条 / 看书页 / 标注结果页 / 悬浮条）、`AudioLibrary` 四个查询入口、
  `Pipeline` register 调用点带 `chapters.size`、`Screen` 只加 `CHAPTERS` 一个值、`chromeOf` / `drawerSelected` / `when` 各一条、
  点书 `PRODUCE → CHAPTERS`、CI 挂 `r61_verify.py`、项目文件回填。
- **诚实差额**：引擎只有 DIRECT / ANNOTATE 两档，`去标注` 与 `标注并合成音频` 落同一档，界面明写「这一档跑完会一并出音频」，
  **没有伪造第三条管线**；进行中进度只标「整本进度」，不做逐章假拆分；旧成品不点亮单章；阅读位置持久化未做。
- **守恒**：prompt v2、逐字 gate、两条制作路径、native 采样链、调音台唯一提交与两态试听、4B 设置、
  `DismissibleNavigationDrawer`、240dp 抽屉、`TaskBar`、`PlayQueue.kt`、Manifest、Gradle 依赖一行未动。
- **验证**：本地 16 套 `CI=true` 全绿；`r61_verify.py` 阶段 A 9 组对拍 / 阶段 B 38 条正向 + 7 条零残留 + 6 个守恒 /
  反向注入 10/10 拦下。**订正两个旧验证脚本**：`check_structure.py` / `r46_verify.py` 把 Screen 写死 10 值，
  新增 `CHAPTERS` 后假红，改为白名单推算并加「chromeOf 集合与枚举相等」的实质检查，比原来更严。
- **CI**：run 67 失败 —— 前一次同步把 `ci.yml`（已挂 R61 step）传上去却没带 `r61_verify.py`，CI 找不到脚本；
  补传后 **run 68 `completed success`**（真 Kotlin 编译）。版本号跟 run 号走，故为 `+68`。
- **发布**：远端 API 提交 `7d13d5bf66` 触发 run 68；从直链仓 `git/blobs` 取回 **18,099,324 字节 /
  blob `bc568bb74a979…`** 与本地逐字节一致。`r61_apk_audit.py`：CRC / 7 个 arm64 .so / 权重 0 /
  无 INTERNET / `1.0.0+68` / 25 个新符号 / 5 条旧形态零残留 / `TTS_PARAMS_APPLIED` 在，`--selftest` 正确拦下。
- ⚠️ **真机仍是零证据**：四列滑动、左边缘手势与抽屉抢不抢、悬浮条叠放、375px 溢出都要装一次点过。
  新语言模型权重仍在 App 外，四项 A/B gate 未测。
- **队列**（`ROADMAP.md` 唯一真源）：真机验 → 只标注管线 / 逐章进度 / 阅读位置 → 设置页 v7 → 性能二期 →
  P4 标注导入 → P6 软件设置补齐。


## R62–R64 · 进度页三轮预览纠偏（零代码 / 零发包，App 仍 `1.0.0+68`）

- **R62**：用户要求「先用现有代码画出来自己看一眼」。按 `MainActivity.kt:893–1163 / 468–540` 1:1 还原 6 屏，
  **v10 整份作废**：差点把抽屉「任务」改名成「进度」把任务中心整页删掉；「收起 ↑」其实是 975–1140 整块的唯一开关；
  进度页根本没有「开始」按钮；阶段是 5 步不是 4 段；看台正文（`s.text`）代码里早就有，只是藏在点格详情后面。
- **R63**：用户不再逐控件给指令，只给「空间分配不合理」+「底部任务卡不要了」。量化出同一屏 768dp 里
  真正该看的正文占 **0dp**，大字/竖排五步/整宽按钮/返回收起/贴底任务条占 **324dp ≈ 42%**；
  v11 把正文给到 491dp、控制类压到 133dp。8 处待拍板由我定，9 屏全摊开自查。
- **R64**：用户点明「开始停止就一个三角/两竖图标」「12345678 小块点一下展开正文」
  「弹出的是语言模型吐出来的文本的原貌」。实读发现 `MainActivity.kt:1068` 注释自己写着
  「这里显示的是最终进 TTS 的那一条，不是模型原始产出」，而原貌早按 `block_%04d_attempt%d.txt` 落盘，
  只是 `RawDump.latest()` 没有按块读的接口；**segmap 的 block 是 0-based、文件名是 1-based**，差一必须 +1。
  v11 两处理解错（正文常驻铺满 / 把 `s.text` 当原貌）全部撤回，v12 全 10 屏重画，硬自检 29/29。
- 文档：`docs/R62_进度页现状实读与理解偏差对照.md`、`docs/R62_进度页重设计_代码实读与定案.md`、
  `docs/R63_进度页空间重分配v11_预览定案.md`、`docs/R64_进度页看台原貌与小块交互_代码实读.md`。


## R65 · 进度页 v12 落地（1.0.0+69 / run 69）

- **用户指令**：「通过」= 批准 v12 全 10 屏预览。按纪律**先列 13 处联动表，再动代码**，单独发版不夹带。
- **13 处联动**：删 `TaskBar` 调用与整个 composable；删「← 书架」「收起 ↑」`expanded` 与 22dp 手势条；
  34sp 大字 → 11sp、整宽按钮 → 42×34dp 纯图标放进度条左边（⏸/▶ 两态、无「开始」）；
  五步竖排 → 横排 3dp 色块（正在做只加粗）；语言模型折一行删「跑一次…」尾巴；
  分段看台常驻 `segs.chunked(8)` 格子 + ▶ 角标；点格展开**模型原始产出**；
  `RawDump.forBlock(c, sg.block + 1)` 新增按块读（唯一新增函数，纯读文件约 20 行）；
  音频 ▶/⏸ 两态 `PlayQueue.pause/resume/playFrom(0)`；`s.text` 降级为收起的「对照」；
  段级重做 / 关掉预览收进「更多操作」；三类空态如实写。
- **守恒**：`Workbench` / `SegPlan` / `SegRow` 数据结构、`Producer.state`、`st.stage`、Prompt v2、逐字 gate、
  两条制作路径、native/JNI、`PlayQueue` 单实例、调音台唯一提交与两态试听、R61 四列目录页与悬浮条、
  抽屉 8 项与推走联动、任务中心、Manifest、Gradle 依赖一行未动。
- **验证脚本订正 3 处（假红，不是放水）**：`check_structure.py` 把 `s.src` / `taskExpanded` 写死 → 改 `\w+\.src` 与存活符号；
  `r51_verify.py` 4 条 TaskBar 叶子断言 → 改「任务条零残留 + 进度页自己订阅」并把反向注入改成「回潮必须拦下」；
  `r65_verify.py` 首版把「← 书架」全局查（制作页仍有合法同文案）、一条 CI 反向注入是空探针 → 限定片段、换真探针。
- **验证**：本地 17 套 `CI=true` 全绿；`r65_verify.py` 正向 20 符号 / 旧形态 13 条零残留 / 差一对拍 4 组 /
  反向注入 10 例 / 守恒全过。**CI run 69 `completed success`**（真 Kotlin 编译；`r65_verify.py` 与 ci.yml 同批上传，没重演 run 67）。
  `r65_apk_audit.py`：CRC / 7 个 arm64 .so / 权重 0 / 无 INTERNET / `1.0.0+69` / 21 新符号 / 8 条旧形态零残留 /
  `TTS_PARAMS_APPLIED` 在 / 直链一致，`--selftest` 正确拦下。
- **发布**：远端 API 提交 `703650668e`（base_tree 只增不删，11 文件）触发 run 69；从直链仓 `git/blobs` 取回
  **18,099,315 字节 / blob `04cd9edd2027…`** 与本地逐字节一致；项目文件回填带 `[skip ci]`。
- ⚠️ **真机仍是零证据**：点格展开、attempt 切换、音频两态跟手、旧 run 空态、375px 不溢出都要装机验。
  展开的是**块级原文**（一段没有独立模型产出，多段共享同一块），界面写明「第 N 块」，不伪造逐段独立原文。
  新语言模型权重仍在 App 外，四项 A/B gate 未测。
- **队列**（`ROADMAP.md` 唯一真源）：真机验 → 只标注管线 / 逐章进度 / 阅读位置 → 设置页 v7 →
  P4 标注导入 → P5 性能二期 → P6 软件设置补齐。本轮是临时插入轮，不占位次。


---

## R66 · `1.1.0` 语言模型整套退场（run 70 / `1.1.0+70`）

- **方向变更**：用户拍板「下一个小版本把语言模型整套拿掉」，「语言模型 / 数据标注 / 文本模型 / 标注」等说法一律放弃；音频模型、首页/书架、左侧抽屉保留。上一轮 R56~R60 的换权重、双页拆分、A/B gate 全部作废；用户已下载的 Huihui GGUF 不再导入。
- **新纪律**：不再要求用户真机验证；拿不准的真机样子自己画预览、自己看、自己改。本轮 7 屏自审，修掉模型页混进章节小条、三键导航压 dock 两处。
- **13 处联动**：抽屉 8→7 项、`Screen` 11→10 值、`LlmSettingsScreen` 整页删；进度页删判定卡 + 五步改四步 + 看台改看送进声音模型的正文；`ChapterHub` 四列→三列（章节/合成/可播），底部小条收敛成「调音 › / 合成音频」，调音在点合成之前；模型清单只留 `tts_talker` / `tts_tokenizer`；3 个提示词资产删除；`Producer.start` 恒 `direct`；`versionName` 升 `1.1.0`；CI 挂 `r66_verify`；旧断言订正；三份真值源方向改写。
- **守恒**：Qwen3-TTS 两份 GGUF、native/JNI 7 个 arm64 .so、`MixerDraft` 唯一提交出口（提交不依赖试听）、两态试听、9 项采样 `ParamSpec`、`PlayQueue` 单实例、悬浮条、`ProduceService`、抽屉推走联动、`chapterIndices` 旧数据兼容、无 INTERNET / 仅 arm64 / <100MB / 首版只 WAV。
- **收尾查出并修 6 处**：`check_structure.py` 4 条旧字面量假红（换成零残留 + 注入自检）；`r50_verify.py` 读已删 `assets/prompt_zh.txt` 崩溃（守恒换 `sampling-defaults.h`）；ci.yml 漏挂 `r66_verify.py` 且构建报告仍写 `1.0.0+`；3 个提示词资产没真删；`sync_remote.py` 只增不删（补 `sha:null` 删除 + 回读核对）；`r66_apk_audit.py` 把 `DebugProbesKt.bin` 当权重、把 `AbGate` 类名当 UI 残留（两处自身误判订正，不放水）。
- **验证**：本地 `CI=true` 11 步全绿；`r66_verify` 反向注入 10/10 拦下；三个 `--selftest` 全过；远端 tree 127 个候选文件 blob 全一致、12 条删除生效；run 70 `completed success`（真 Kotlin 编译）；拆包硬验全 PASS；直链 blob `0c6720b1710f…` 与本地 **18,063,117 字节**逐字节一致，Release `v1.1.0-70` 同字节数。
- **如实限定**：`AbGate` / `Adoption` / `Annotator` 等类名仍在 dex（R66 明确保留的不可达死代码）；主流程仍留语言模型时代的审计落盘前置调用（不加载权重、不进标注分支），物理清理列 P1；真机零证据，不向用户索取装机验证；夜间模式 / 字号仍标「待新增」。
- **队列**（`ROADMAP.md` 唯一真源）：P1 死代码物理清理 → P2 软件设置补齐 → P3 逐章进度 / 阅读位置 → P4 性能二期，各自单独发版。


---

## R67 · P1a 死代码物理清理（run 71 / `1.1.0+71`）

- **入口**：`ROADMAP.md` P1，R66 为避免编译连锁保留的不可达类型与主流程审计前置调用。单独一轮，不夹带。
- **先实查可达性，纠正项目文件自己的失真**：`STATUS.md` 写「保留但不可达」，逐函数统计后发现 `VoiceCast` 23 处 / `AuditLog` 26 处 / `SegPlan` 18 处 / `Workbench` 4 处 / `RawDump` 7 处仍被角色音色页、诊断区、分段看台、看书页引用。一刀切删会编译断链并弄没诊断与看台，故按代码实况重划 P1a / P1b。
- **真删 8 类**：审计落盘前置调用整块（`beginRun` / `RawDump.begin` / `AUDIT_RUN_BEGIN`）、`AbGate` 对象与 `record`、`writeAuditSummary` 定义与两处调用、`PROMPT_VERSION`、`ENGINE_INFO` / `llmFile` / 第二份权重缺失拦截、`KEY_THINKING` 兼容读（3 处改 false）、`ChapterHub.AnnotatedPane`（零调用）、`EventLog` 的 `AuditLog.crashTail()`。`sys/usr`、`llm`、`AnnCache.dirFor`、`chapterIndices`、`TaskResume.load` 全保留，旧成品仍能读。
- **验证脚本订正 3 处（假红 / 误报，不是放水）**：`check_structure.py` 把已删的 `AUDIT_RUN_BEGIN` / `AUDIT_SUMMARY` 列为「必须存在」→ 改反向断言并补 5 项零残留；`r41_verify.py` 守恒项 `fun thinking(...)` → 换 `SCHEMA_VERSION`；自审图脚本把清理对照信息图当界面查禁用词 → 只查前三屏真界面。首轮自检挂 2 处，订正后 13 套 `CI=true` 全绿。
- **新增** `scripts/r67_verify.py`（13 项零残留 + 6 项旧数据兼容 + 8 项守恒 + 反向注入 10/10），`ci.yml` 挂新 step。
- **自绘自审 4 屏**：三列 / 四步 / 抽屉 7 项 / 底部安全区逐屏比对一致，零横向溢出、界面零禁用词；按 R66 新纪律不向用户索取真机验证。
- **发布**：源码仓 API 提交 `8ac3cb8fa0`（15 文件）触发 **run 71 `completed success`**；Release `v1.1.0-71`；
  从 APK 仓 `git/blobs` 取回 **18,046,744 字节 / blob `63c9230d52b3…`** 与本地逐字节一致，raw 直链前 64KB 比对一致；
  `r67_apk_audit.py` 全 PASS 且坏包自检拦下；项目文件回填带 `[skip ci]`
- **如实限定**：P1 未做完——旧合成分支（约 300 行）及 `Adoption` / `JsonSalvage` / `RawDump.write` / `AuditLog.write` 仍在，分支外代码引用分支内变量，硬删会断链，单列 P1b；`AbGate` 类名已消失，其余旧类名因仍有活引用而在包里；真机零证据。
- **队列**（`ROADMAP.md` 唯一真源）：P1b 剩余死代码 → P2 软件设置补齐 → P3 逐章进度 / 阅读位置 → P4 性能二期，各自单独发版。


---

## R68 · P1b 单一路径重构（源码层；未发新包）

- **入口**：用户「继续找活干」。按 `ROADMAP.md` P1b 单独一轮，先列 `docs/R68_P1b_单一路径重构_联动表.md` 再动代码，不夹带 P2/P3/P4。
- **可达性实查（改动前）**：`Annotator` 20 处引用全在 `Pipeline.kt` 旧 else 分支；`ThinkingStore` 0 引用；`AnnCache.read/write` 仅旧分支；`Adoption` / `JsonSalvage` / `RawDump` / `AuditLog` 只被旧分支 + `MainActivity.DiagnosticsBlock` 7 处引用。而 `SegPlan` / `VoiceCast` / `NonVerbal` / `Workbench` / `EventLog` / `PlayQueue` / `AnnCache.dirFor` / `AudioLibrary.chapterIndices` 仍活，**一个没删**。
- **11 处联动全签**：`Producer.run` 删 `if (direct)/else` 只留直接朗读主体；删 `ModelStore.llm` / `Annotator.templates` / `nCtx,threads,temp` 读取；删 `segOrigin` / `modelHits` / `resumed` 声明；删 `if (!direct && segOrigin…)` segmap 块；WAV 命名统一 `direct_%04d.wav`；`Annotator` 瘦到只剩 `directWithStats` / `directSegments`；删 `ThinkingStore` / `AnnCache.read,write`；诊断区换精简版（静态快照 + 两次复制 + 清理确认弹层），删 3 秒定时器、6 处旧读取、3 个旧折叠按钮、原始产出复制按钮与 `buildDiag(full)` 里的审计截取；删 2 行 import；**四文件物理删除**；`check_structure.py` 旧「必须存在」断言全部改成零残留反向断言；`ci.yml` 挂 `r68_verify.py`。
- **一处取舍（如实记）**：`gateFails` 保留为 `val gateFails = 0`，没有把合成/合并段 6 处 `gateFails + synthFails` 算式改写——恒 0 常量会被内联，改写反而增加断链风险。联动表写的是「替换算式」，实际按此取舍执行。
- **验证**：`python3 -m py_compile scripts/*.py` 通过；17 套 `CI=true` 自检**全绿**（含 `r66_apk_audit` 的 `+70` 硬编码假红修成按版本前缀、`r67_apk_audit` 注释订正）；新增 `r68_verify.py` 正向 6 组 + 反向注入 **14/14 全拦下**；`--selftest` 通过；Kotlin 源码括号平衡 0、类型定义全在、无悬空 import、旧符号全项目引用归零。
- **自绘自审 4 屏**（`draw_r68_preview.py`）：新版诊断区 / 旧新对照 / 影响面总览 / 375px 三键安全区，横向溢出 0、界面内零禁用词。自己看图改了 3 处：预览画了代码里没有的「夜间模式 / 正文字号」（按 `SettingInfoRow` 真实文案改成「主题 / 字号 · 待新增」）、影响面写「16 套」实际 17 套、③ 是信息卡不是 `.phone` 导致溢出检查索引越界。按 R66 纪律不向用户索取真机验证。
- **如实限定**：沙盒无 Android SDK / gradle wrapper，`git ls-remote origin` 返回 **403**，**本轮未触发远端 CI、未做真 Kotlin 编译、未出新 APK、未核直链**；`1.1.0+71` 仍是唯一可装包，源码已领先它一轮。CI 脚本已挂好，网络恢复后 push 一次即可补齐「真编译 + 拆包 + 发版」。旧书 / 已做好 WAV / 续跑书签的读取路径全留；真机零证据；诊断区折叠层级仍归 P2。
- **队列**（`ROADMAP.md` 唯一真源）：网络恢复后补 R68 出包 → P2 软件设置补齐 → P3 逐章进度 / 阅读位置 → P4 性能二期，各自单独发版。

## R72 · R68 出包回执（2026-11-06）

- **卡点定位**：`api.github.com` 通（HTTP 200），但 `git ls-remote origin` 403 —— 不是断网，是源码仓已改名 `zjh1934/tingben`，本地 remote 仍指向旧名 `tingben-android`；API 跟随 301 后确认 token 有 `push/admin`。沙盒仍无 Android SDK / gradle wrapper，**本地编不了，必须走远端 CI**。
- **提交方式**：改用 GitHub Git Data API（blobs → trees → commits → PATCH ref）提交 `42cbc6041839b7ceabbafb29615040d87ed87c56`。按远端 head tree 的 blob sha 逐文件比对，只提交 **15 改 + 4 删**，自动跳过 **135 条本地 HEAD 落后造成的历史假差异**，没误删任何远端文件。首轮 `POST /git/trees` 返回 422 `Must supply a valid tree.mode`，定位为删除条目缺 `mode`，补 `mode/type/sha:null` 后通过。
- **CI**：run **72** `completed success`（真 Kotlin 编译 + `check_structure` / `r66` / `r67` / `r68_verify` 等自检 step + APK 闸门 + Release `v1.1.0-72` + `publish_apk.py` 同步直链）。这次把 R68 那轮「只有静态自检」的缺口真正补上了。
- **三方一致**：直链 blob `5981a878a75f38457513a429fe2d977f470d2fbc` / **17,981,194 字节**；`sha1("blob "+len+"\0"+内容)` 自算等于该 blob sha；Release `v1.1.0-72` 资产 17,981,194 字节；本地 `/data/workspace/听本_app-debug.apk` 同字节（旧的 18,046,744 字节 `+71` 已被覆盖）。
- **拆包硬验**：新增 `scripts/r72_apk_audit.py`（修正 R67 脚本里直链仓名 `tingben-apk` → `ting-ben-apk`，BAN 表补 `Adoption` / `JsonSalvage` / `RawDump` / `AuditLog`）。结果：ZIP CRC 通过 / 7 个 arm64 .so / 权重 0 / 无 INTERNET / 版本号 `1.1.0+72` / 提示词资产零 / 6 个新符号在 / 22 条旧形态零残留 / `TTS_PARAMS_APPLIED` 在 / 直链逐字节一致；坏包反向自检拦下。
- **文档回填**：`STATUS.md` 头部与证据边界、`ROADMAP.md` 头部与 P1b 段、本日志同步到 `+72`；`docs/R68_P1b_单一路径重构_落地.md` 保留原「未出包」记录作为历史，不回改（历轮如实限定不抹掉）。
- **如实限定**：真机仍是零证据，按 R66 纪律不向用户索取装机验证；CI 全绿只证明编译 + 断言成立，不证明好用。`VoiceCast` / `NonVerbal` / `SegPlan` 的界面语义报废、诊断区折叠层级仍归 P2。
- **队列**：P2 软件设置补齐（夜间模式 / 字号 / 诊断区折叠）→ P3 逐章进度 / 阅读位置 → P4 性能二期，各自单独发版。


## R73 · P2 软件设置补齐（2026-11-06）

- **队列位置**：R72 补完 R68 出包后，按 `ROADMAP.md` 单开一轮做 P2，不夹带 P3 / P4。
- **预览先行**：`draw_r73_preview.py` 出 6 屏（浅色 / 深色设置页、深色书架、字号对照、诊断折叠对照、全展开长图 + 三键安全区）。自己逐张看过改掉 3 处：组标题 hint 与状态文字挤在一起、诊断对照误把整个「数据与日志」组折叠（设计只折二级块）、三条只读信息行没常驻。另订正自审断言一条：把图注里的「待新增」误判成界面残留，改成只查 `.phone` 内文字。终版横向溢出 0、界面内零禁用词。
- **技术方案（不是想当然）**：全项目 22 个颜色常量原本是 file-private 硬编码 `val`，改成**普通 getter 读顶层 snapshot state**；Compose 组合期跟踪这次读取，切主题即重组，**所有调用点一行不动**，不依赖 `@Composable get()` 编译器特性。新增 `Theme.kt` 两套色板 + `TingbenTheme` 包 `MaterialTheme`，让未显式着色的 M3 组件也跟随。
- **11 处联动全签**：`Theme.kt` 新增；`setContent` 包裹 + `onCreate` 读主题；MainActivity 8 色 getter + 硬编码正文色；设置页三档按钮 / A± 按钮 / 三个状态 / 诊断二级折叠 / 数据组默认展开；制作准备页删「思考过程」行、④ 只读组换成软件信息；ChapterHub 9 色 getter + 字号读写 Prefs；ProduceOptions 5 色 getter；AppChrome 分割线；Prefs 两个键 + getter/setter + 诊断快照去旧词；`values-night/themes.xml`；`ci.yml` 挂 `r73_verify.py`。
- **顺手清的旧词**：`Prefs.summary()` 原本仍写「仅非思考（权重决定）· 上下文 · 线程 · 温度」，制作准备页 ④ 高级仍列「上下文窗口 / 推理线程 / 随机度」，都是语言模型时代残留 —— 换成主题 / 字号 / 声音身份 / 采样种子 / 软件版本。
- **验证脚本自己错的两处，都订正了**：基线自检 3 红里，`check_docs.py` 是真红（README 还写 `+70`，已回填）；`r66_apply.py` 是一次性执行器、`r67_apk_audit.py` 是绑定 `+71` 的版本脚本，两者不该混进常规自检，已归档 `scripts/archive/`。另修了 apply 脚本 4 处非幂等替换（重跑会重复插入状态声明 / CI step），改为带上下文锚点 + 目标符号保护，现重跑全 skip。
- **验证**：本地 18 套 `CI=true` 自检全绿（新增 `r73_verify` 正向 31 项 / 零残留 13 项 / 守恒 11 项 / 颜色 getter 22 个 / 反向注入 10/10）；Git Data API 提交 `297c4fc5` → **run 73 `completed success`**（真 Kotlin 编译 + 18 套自检 step + APK 闸门 + Release `v1.1.0-73`）；`r73_apk_audit.py` 拆包：CRC / 7 个 arm64 .so / 权重 0 / 无 INTERNET / `1.1.0+73` / 9 个新符号在 / 30 条旧形态零残留 / `TTS_PARAMS_APPLIED` 在 / 直链 blob `a667b5523550…` 与本地 **17,997,761 字节逐字节一致**，坏包反向自检拦下。
- **如实限定**：① 状态栏图标只做了 `values-night`（覆盖「跟随系统且系统夜间」）；用户**强制深色但系统仍浅色**时图标颜色未运行时修正，属已知瑕疵，没宣称完美。② `MaterialTheme` 包裹后未显式着色的 M3 组件与自定义色板可能有轻微色差，是观感不是功能。③ `MainActivity.kt:1311` 与 `Prefs.kt:209` 两条历史注释仍带「待新增 / 仅非思考」字样，注释不进 dex、不进界面，拆包已证零残留；为避免再触发一轮 CI 改版本号，本轮没动它们。④ 真机零证据，按 R66 纪律不向用户索取装机验证；CI 全绿只证明编译与断言成立。
- **队列**：P3 逐章进度 / 阅读位置 → P4 性能二期（开工前先出取舍结论）→ P6 其余软件设置，各自单独发版。


## R74 · P3 逐章进度 / 阅读位置（2026-11-06）

- **队列位置**：R73 之后按 `ROADMAP.md` 单开一轮；用户这轮明确「继续做软件」，并说拆包比对那套规矩他自己手动处理 —— 本轮**没把它当交付门槛、没动规矩文件、没翻旧账**，只推 P3，未夹带 P4。
- **先列 13 处联动表再动代码**，只改 `Pipeline.kt` / `ChapterHub.kt` + 新增 `core/ReadingState.kt`。
- **对着代码查出的三件真问题**：① `chapterStates` 拿整本 `done/total` 摊给每章，`Chunk.chapter` 在 `flatMap` 成段后丢失，逐章进度本来是假的；② 三列分发 `repeat(3)` 却写 `coerceIn(0, 3)`，滑到「可播」高亮跑进不存在的第四列；③ R66/R68/R73 都声称旧说法清零，但 `ChapterHub.kt` 里死函数 `AnnColumn` 与唯一可见旧词「已分段」、`annotated` / `unannotatedAudio` 两个死字段全还在。
- **真值链**：`Chunk.chapter` → 切段按块补 `segChapter` → 每章分母 `perChapterTotal` → 每段 WAV 落盘成功（含续跑跳过）才 `perChapterDone[ci]++` → `chapterSnapshot` 随 `emit` 进 `Progress.chapterProgress` → 界面只认 `chapterProgress[i]`。失败段沿用上帧快照，不推进也不回退成 0；没数据的章 `null` → 灰 +「等待中」。
- **阅读位置**：`filesDir/reading_state.json` 存 `{bookId: chapterIndex}`，打开看书页与上下章都写；目录页一条细提示「上次读到 第 N 章 · 章名 / 继续 ›」，制作中不抢位置。纯 UI 状态，不进声音指纹 / diff / 任务书签，写失败静默。
- **自绘预览 6 屏**，逐张看过改掉 4 处：☰ 与状态栏图标字体缺字成方框（改自绘三横线 + 纯文本）、「已选 2 章」却没一枚勾选框被勾（补两枚选中态）、合成列底部混进两行评审腔注解（删）、对照图五条副标题写同一句且主文案截断（逐条配独立说明并缩短）；另删了看书页「位置写入 reading_state.json」这种内部实现提示、给数据流图补了自绘三键。终版 6 屏尺寸一致、右侧越界像素 0。
- **验证**：`r74_verify` 正向 24 条 / 反向 8 类旧形态零残留 / `--selftest` 注入 32/32 全拦下；`check_structure` / `check_docs` / `r66` / `r67` / `r68` / `r73_verify` 回归全绿；`ci.yml` 挂 `r74_verify.py`。
- **如实限定**：**没出新包**，直链仍是 `1.1.0+73`，源码领先一轮 —— 沙盒无 Android SDK / gradle wrapper，CI 真编译与直链核对本轮没跑，静态绿 ≠ 编译过，不编造回执。逐章进度只覆盖合成阶段；阅读位置只记章索引不记段内偏移；其它文件 KDoc 里仍有旧词，单列 P1c。真机零证据，按 R66 纪律不向用户索取装机验证。
- **队列**：补 R74 出包 → P4 性能二期（先出 `LazyColumn` 与锚点坐标取舍结论）→ P1c 注释清零，各自单独发版。


## R75 · 真机截图归档与信息对齐（2026-11-06）

- **用户这轮的要求**：给 7 张真机截图，「丢到沙盒里，需要用的时候拿来用」，后面怎么做自己安排，并说「软件还差得很多」。**没出包、没跑 CI、没动结构**。
- **归档**：`真机截图/` 7 张原图 + `INDEX.md`（逐屏内容表 + 复用方式）。真机信息：Android 16 / arm64-v8a，截图内版本 `1.1.0+72`；现行源码是 R73 已出包 `+73`、R74 未出包 —— **手机落后两轮**，这是「还差很多」的一部分，不全是设计问题。
- **对着图自查出的四类问题**：① 抽屉第 7 项「角色音色」是语言模型标注时代的幽灵页（`出场约 0 段` / 12 维大框 / 保存并锁定 / 并入其他角色），R66 已退场却仍常驻，且「返回设置」与 `onBack` 实际去向不符；② 书架空态时 110dp 粘贴框默认展开占半屏、主操作「导入一本书」被挤下去，缺件卡不写缺哪两个，模型页只给「复制下载地址」不给文件名，我的音频空态整屏空白没引导；③ 内部话术漏进界面（调音台「已生效 v4」、评审腔标签、重复时机说明、设置页首屏诊断行）；④ 抽屉 240dp 在窄屏占约 64%、下半屏留白——只记为观察，不擅改用户已定的推走形状。
- **本轮只做 6 处信息对齐**（零结构改动）：`pasteOpen` 初值 `true→false`；书架缺件卡取 `ModelStore.missing` 写明两个文件；模型页补 `需要：xxx.gguf`；音频空态补一句下一步引导；顶部状态条去掉 `v4`（诊断区技术版本保留）；角色页「返回设置」→「‹ 返回」。抽屉 7 项 / `Screen` / `chromeOf` / `when` / Prefs / 调音台提交链 / 三列目录 / 四步进度 / native / 前台服务全守恒。
- **验证**：`CI=true` 全量 16 套脚本回归全绿（含 `check_structure` / `check_docs` / `r38`~`r74` 各 `verify`），无一处旧文案断言假红；静态绿 ≠ 编译过，不编 CI 回执。
- **如实限定**：**没出包**，可装包仍 `1.1.0+73`；幽灵页、评审腔标签、书架首屏重排**这轮没做**，按「别一次改废」单列 P1c。真机证据已由用户截图提供，按 R66 新纪律不向他索取装机验证。
- **自排队列**：R73+R74+R75 合一次 CI 出 `1.1.0+74` → P1c 幽灵页与话术清理 → 书架首屏重排 → P4 性能二期，各自单独发版。

| R76 | 幽灵页与无意义项清理：抽屉 7→6 项、Screen 9 值、调音台角色 UI 与设置页话术全删；主链/voiceStamp 为旧 WAV 兼容保留；18 套 CI=true 全绿、反向 8/8、4 屏自绘预览自审 |
| R76-出包 | run 74 `completed success`；`1.1.0+74` / 17,964,995 字节 / 直链 blob `401cfec9dfce`；源码 commit `6fe4a97a`（29 上传 + 13 删除）；不做拆包比对，只核直链逐字节一致 |


## R77 · P1d 角色表底层退场（2026-11-06）

- **入口**：R76 删幽灵页后，`voiceStamp` 仍含角色表 hash、合成循环仍逐段 resolve 角色表。按 R77 联动表物理清掉，**界面零改动**。
- **核心风险与方案**：直接删 hash 项会让旧 `voice_stamp.txt` 失配 → 用户已合成 WAV 被一次性删光。改为双指纹：`changed = old != voiceStamp && old != legacyVoiceStamp(c)`；旧手机命中 legacy 不作废、只把 stamp 改写成新值；真改音色/采样仍照常作废。
- **11 处联动**：`CastPresets.kt` / `VoiceCast.kt` 物理删；`Prefs` 删两个键与四个函数、`voiceStamp` 三项、新增 `legacyVoiceStamp` + 私有 legacy 读法；`MixerDraft` 删 `castEnabled`；`Pipeline` 删逐段角色解析、身份层直接用 `globalVoice`、日志去角色数；5 个文件注释旧词清理；`ci.yml` 挂 `r77_verify` / `r77_stamp_migration`。
- **验证**：迁移 8 组对拍全过；`r77_verify` 正向断言 + 反向注入 8/8 拦下；本地 `CI=true` 全套回归全绿；4 屏自绘自审（旧音频不删 / 迁移流程 / 代码层删了什么 / 界面零变化对拍），横向溢出 0。
- **交付**：Git Data API 提交 `c913141d` → **run 75 `completed success`**（真 Kotlin 编译 + APK 闸门 + Release `v1.1.0-75`）；APK **17,948,597 字节**，直链 blob `a8d73027f3b881aad0686025545b74f35c4645cf` 与本地 APK 的 git blob sha1 相等（等价逐字节一致）。按用户新纪律**不做拆包比对**。
- **如实限定**：`cast.json` 作为历史数据不主动删；`legacyVoiceStamp` 是过渡兼容读，未假装一次清干净；真机零证据，不向用户索取装机验证。
- **队列**：P1e 书架首屏重排 → P4 性能二期，各自单独发版。

| R78 | 书架首屏重排：空态主卡 / 有书态整卡可点 + 上次读到 / 缺件与粘贴收成小入口；自绘 5 屏自审 |


## R78 · 书架首屏重排（2026-11-06）

- **入口**：用户「继续」。按 `ROADMAP.md` P1e 单开一轮，不夹带 P4；先补 R77 交付回执再动本轮。
- **基准**：`真机截图/` 7 张（Android 16 / `1.1.0+72`）+ `LibraryScreen` 源码 495–605 行实读。空态三张同构卡（缺件整宽按钮 / 110dp 粘贴框 / 空态整宽按钮）叠着，主操作在第三张；有书态书卡只有标题+字数+✕▶，整卡不能点，R74 的 `ReadingState` 没用上。
- **7 处联动**（只改 `LibraryScreen`）：缺件收成可点细条；空态合并主卡 + 次级粘贴入口；粘贴框逻辑一字不改、只在点开时出现；有书态顶部「共 N 本」；整卡 `clickable`；补「上次读到 第 N 章」；删除改小文字按钮。`RefreshStrip`、`BookStore` / `ModelStore`、剪贴板建书、删除弹层、壳层「+」、抽屉 6 项全守恒。
- **R77 回执补齐**：run 75 `completed success` / Release `v1.1.0-75` / 17,948,597 字节 / 直链 blob `a8d73027f3b8…`；落地报告与 4 屏自审包归档；`STATUS.md` 里「抽屉 7 个目的地」订正为 6。
- **自绘 5 屏自审**：空态首屏 / 粘贴展开 / 有书态 / 旧新对照 / 全展开 + 375px 三键安全区，尺寸一致 750×1624、横向溢出 0。按新纪律不向用户索取装机验证。
- **验证**：`r78_verify.py` 正向 12 / 零残留 6 / 守恒 10 / 反向注入 **28/28 全拦下**；本地 `CI=true` 全套（含 `check_structure` / `check_docs` / `r66`~`r77`）全绿；首轮两个绑定旧版本的一次性拆包脚本（`r72` / `r73_apk_audit`）假红，归档 `scripts/archive/` 后复跑全绿；`README.md` 版本串同步回填。`ci.yml` 挂 `r78_verify --selftest`。
- **交付**：Git Data API 提交 `dcfd4d11`（11 改 2 删）→ **run 76 `completed success`**（真 Kotlin 编译 + APK 闸门 + Release `v1.1.0-76`）；APK **17,964,991 字节**，直链 blob `bfb72b48c75a…` 与本地 APK 的 git blob sha1 相等；`AndroidManifest.xml` 版本串 `1.1.0+76`。**不做拆包比对**（用户新纪律）。
- **如实限定**：① 右上角「+」与空态主按钮重复，但「+」是有书态唯一导入入口，保留；② `RefreshStrip` 下拉刷新条保留，未删手势；③ 「上次读到」只记章索引（R74 定义），不记段内偏移；④ 真机仍是零证据，交付的是代码与断言，不宣称手感变好；⑤ 归档两个旧拆包脚本属于验证脚本整理，不是业务代码。
- **队列**：P4 性能二期（开工前先出 `LazyColumn` 与锚点坐标取舍结论）→ P6 其余软件设置，各自单独发版。

## R79 · P4 性能二期（2026-11-06 · 补记）

> R79 当时已出包、STATUS / ROADMAP / docs 都回填了，**唯独本日志漏了条目**；R80 轮一并补齐，不回改历史。

- **开工前先出取舍结论** `docs/R79_P4性能二期_取舍结论.md`：`LazyColumn` 会破坏 R50 锚点像素语义
  （`scroll.value` vs 绝对 Y），**调音台不换**；只换音频页这一处「无锚点、N 行随数据增长」的页。
- **8 处改动**：`MiniPlayBar` 按 `isPlaying` 门控并作唯一 500ms tick 源；删音频页重复 tick；
  任务中心轮询门控 `st.running`；`AudioLibrary.durationMsOfBytes(bytes)` 让列表行零 stat；
  音频页 `Column→LazyColumn`；进度页四项磁盘读从每秒解耦到每段（跟 `st.done`）；两条 import。
- 订正 `r51_verify.py` 两条过时断言（旧形态写死「音频页自己门控 + 自己 tick」）为更严的新形态，反向注入 6→7 例。
- `r79_verify` 正向 18 / 零残留 8 / 守恒 12 / 反向注入 **40/40**；本地 21 套 `CI=true` 全绿。
- **交付**：commit `825ddc41` → run 77 `completed success`（真 Kotlin 编译 + APK 闸门 + Release `v1.1.0-77`）；
  APK 17,965,001 字节，直链 blob `302ba260ee76…` 与仓库 blob / Release 资产同字节（raw 502 走 Git Blob API 等价通道）。
- 5 屏自绘自审（对拍音频页空态），界面零可见改动；不宣称卡顿归零，真机零证据，不向用户索取装机验证。
- **按用户要求，本轮不做拆包比对**；该条已从 `ROADMAP.md` 交付协议中删除。

## R80 · 我的音频页收敛（2026-11-06）

- **入口**：用户「继续」。P4 出包后队列只剩可选 P4b / 未定义 P6，自排插队做用户点名过、
  7 张真机截图里最薄的一页（空态一张孤卡 + 整屏留白）。先列联动表再动代码，只改 2 个文件。
- **对着代码查出的四件事**：① 空态没有下一步；② 音频页常驻播放大卡与跨页 `MiniPlayBar` 双份曲名/状态；
  ③ 大卡里拖动 seek / ±15s / 上下条 / 倍速 / 关闭这 5 项悬浮条没有，**直接删卡会丢功能**；
  ④ 列表行 `Button` + `FilledTonalButton` 两个实心块抢视线，全页无汇总。
- **终版 7 处联动**：`AudioScreen` 加 `onLibrary` / `onModels` 两个回调；分发处 `modelsFrom = Screen.AUDIO`
  保证模型页返回仍回本页；`controlsOpen` 默认 false；`now_playing` 改成 `if (current != null && controlsOpen)`
  才出；空态补两个可点入口；新增 `audio_summary` 汇总行兼「播放控制 ›」入口；列表行只留一个实心主按钮。
  汇总用 `sumOf { durationMsOfBytes(it.bytes) }`，**零新增磁盘 IO**。
- **自绘 7 屏自审两轮**：第 1 轮抓到「初版仍留一张常驻小卡，和悬浮条重复曲名/状态」→ 改成把控制入口并入
  汇总行、首屏零播放卡；另补展开态漏画汇总行 / 悬浮条、信息卡里的实现术语。
  第 2 轮抓到**自己写错的断言**：按曲名字面量判「双份」会误报（列表当前章本来就该显示章名），
  改成按播放卡数量 + `播放中` 状态文字判断。终版 7 屏横向溢出 0、界面内实现术语 0、默认态播放卡 0。
- **验证**：本地 21 套 `CI=true` 全绿；新增 `r80_verify.py` 正向 22 / 旧形态零残留 6 / 守恒 11 / 函数体结构 /
  `--selftest` 反向注入 **45/45**。首轮注入漏 1 组：`onLibrary = { ... }` 在源码出现两处，注入器只替换第 1 处
  造成假绿 → 改成全替换，不是放水。`r79_verify` 守的形态全部保留，**无需订正**。
  `publish_apk.py` 是发布 step（需 `GH_PAT`），不计入本地自检。
- **交付**：Git Data API 提交 `aa7cd40f36`（5 改 0 删）→ **run 78 `completed success`**（真 Kotlin 编译 +
  21 套自检 step + `r80_verify` + APK 闸门 + Release `v1.1.0-78` + 同步直链）。
  APK **17,964,991 字节**，直链 blob `4917a3faaf9555f1e40d7fa3f5fae09eda4091b9`，自算 git blob sha1 相等，
  与 Release 资产同字节。**不做拆包比对**（用户新纪律）。
- **守恒**：`PlayQueue` 单实例 / `CreateDocument` 导出 / `AudioLibrary.remove`、`LazyColumn` 容器与 key、
  R79 的「本页不自行 tick / 行内用 bytes」、`ChapterHub.kt` 整个 `MiniPlayBar`、抽屉 6 项 / `Screen` 9 值、
  native / 前台服务 / 调音台唯一提交 / 三列目录 / 四步进度 / 主题字号全未动。
- **如实限定**：真机仍是零证据，不向用户索取装机验证；「合计时长」是按 WAV 字节数推算的估算值，不是解码实测；
  展开态仍会与悬浮条同时显示曲名（用户主动点开的详细控制，不算首屏双份）；
  CI 全绿只证明编译与断言成立。
- **队列**（`ROADMAP.md` 唯一真源）：下一轮 **模型页重排**（真机截图里两张同构「还缺」卡 + 整宽导入按钮，
  与 R78 修好的书架空态同类病）→ 之后 P4b 可选 / 阅读位置段内偏移，各自单独发版。


## R81 · 模型页重排（1.1.0+79 / run 79）

- 真机截图基准 `CMP_20261106104613629.jpeg`：两张同构「还缺」卡 + 两个整宽复制入口 + 卡外整宽导入按钮 + 半屏留白。
- 缺件态收成一张主卡：唯一 48dp 主按钮、两行清单写清文件名、「复制地址 ›」降为行内小文字；就绪态换一套内容；返回与状态并成一行；导入失败文案压缩。
- `ModelStore` / 导入白名单 / 两条 huggingface 直链 / `onPick`·`onBack`·`onLibrary` / 抽屉 6 项 / `Screen` 9 值全守恒。
- `scripts/r81_verify.py`（正向 11 / 零残留 6 / 守恒 12 / 结构 5 / 反向注入 3/3）挂 CI；本地 21 套 `CI=true` 全绿。
- 6 屏自绘预览逐张看过两轮，修掉 4 处自审问题；验证脚本两处假红订正。
- 出包：run 79 真编译全绿，APK 17,964,999 字节，直链 blob `a5bf45e9a06d…` 与本地逐字节一致；**不做拆包比对**（用户要求）。
- 如实说：文件名行内单行显示可能截断；体积按字节换算；`1.1.0` 直接朗读链路真机仍是零证据。
