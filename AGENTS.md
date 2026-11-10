# 听本 · 仓库规则（给 AI Agent）

> 新会话第一件事：按下面的顺序读，不要先翻历史，不要先读归档。

## 每轮对话必做（用户硬规则）

项目文件只有两份：仓库内**地图**（`aoci.txt` / `aoci.meta.txt` / `aoci.code.txt` / `AGENTS.md`）与仓库外**任务看板** `../任务看板.md`。其余 md 已归档，不读、不作认知证据、不写回执。

- **进门**：先读地图，再读看板（看板按「已做 / 在做 / 待做」给状态），然后才动源码。
- **收尾两处都维护**：
  1. 看板改写三段——前面做了什么、现在在做什么、后面要做什么；保持 500 字上下（±100），不写流水账、不新建文件。
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
- 真机证据不足，不写「好用」「体验没问题」。静态自检全绿 ≠ 编译过 ≠ 手机上好用。
- 删除独立页之前，先把它的能力搬干净；破坏性操作先预览再执行。
- **验收阶段节奏（R217）**：用户明确本阶段改为「每轮提问题 → 处理完 → 每轮出安装包供真机验收」。覆盖 R204 的攒批不发：本阶段每轮对话都要升一个修订号、走 rc 真编译 → `--promote` → 发版，用户自己装包验收；仍不做逐步同步中间结果。
- **调音台与缓存口径（R220）**：内容完整、成本优先，调音台/模式改动**绝不丢已做好的段与整章成品**。换套时旧套复制留档、根目录已生成段原样认领为新套起点，点继续只把剩下的段按新参数合成；TaskResume 不得把调音台改动算任务失效或把进度归零。切回用过的设置仍逐字节还原。小段显示编号每章从 1 起（文件段号仍整本连续）；没做过也没在做的章点章名不展开；展开区顶部零留白。
- **默认音色身份（R223）**：采样参数不决定性别/年龄（只管抽签随机度与长度上限），网上分享的「青年女声」是 instruct 描述词。现有 9 项采样默认 （0.9/50/1.0/1.05/0.9/50/1.0/2048/42）已是官方评测与社区常用起点，**锁死不动**。默认身份改为 `Prefs.DEFAULT_VOICE` 青年女声（适合长篇有声书）。`voiceDescription` 用 `contains` 区分「从没设置」与「显式清空」：清空 → 回落 `DEFAULT_IDENTITY` 中性串、`voiceStamp` 回旧值 → VoiceCache 认领回升级前那套，**旧进度不丢**。换默认音色会使旧套归档（不删），调音台点「清空」即可切回。
- **采样参数认知（R222）**：9 项（seed / maxNewTokens / temperature / topK / topP / repetitionPenalty / 副 talker 三项）**不进 prompt**，走 `qt_tts_params` 数值字段：UI → `Prefs.setTtsKnobs` → `NativeEngine.ttsSynthesize` 拆 9 个 JNI 参 → C++ `qt_synthesize(q,&tp,&audio)`，每段写 `TTS_PARAMS_APPLIED` 日志。三模式都生效；任务 start 时读一次，运行中改不影响正在跑的任务；试听同。改 knobs → `voiceStamp` 变 → 音色套切换（旧套归档不删、当前套从头算）。调音台仅 VOICE_DESIGN 显示 9 项，另两模式「沿用全局设置」= 同一份全局值，不是失效。
- **「怎么念」已砍（R221）**：制作设置页只剩 ①做什么 / ②怎么跑；朗读档位 / 语速 / 情绪三档写死预设彻底不再进 `instruct`，风格层只认自填 `extraInstruct`。三个枚举仅为读旧任务 JSON 保留、不生效。**`audioStamp()` 公式锁死不许动** —— 指纹一变，旧套分段会归档、已做进度归零。调音台 diff / 内置预设 / 快照 / 参数目录同步零回潮。
- **切分与目录页口径（R219）**：内容完整优先于快，段切分维持细切 110/260，不许回到 240/420 大块；SPLIT_EPOCH 必须进音色缓存键与续跑判据，旧分段绝不按段号复用。目录页顶栏只放停止/继续、任务、制作设置三枚右对齐按钮；两条进度条加高，整本条内写「已完成 X%」、整体百分比在条左，本章只靠条走量；点进度条滚到正在制作的章；没小段的章点章名不展开；章名左边常驻勾（不靠长按）；展开头部非多选态不渲染，不留白。
- **目录页口径（R218）**：顶栏写书名不写「目录」；重新分章收进制作设置页做次要入口，不占顶栏；制作设置页只管「怎么念 / 怎么跑」，不塞拆章勾选与调音台入口，且必须带当前书上下文，不并进全局设置页；章行右侧小眼睛（看原文）+ 小耳机（听成品），点章名展开小段；整本/本章两条进度条常驻，展开用动效；不许再写「共 N 小段 / 已合成 M 小段」。
- **悬浮窗数字口径（R217）**：球上只写百分比数值，0% 写 0、90% 写 90，右侧补小号 % 标明单位；绝不写段号 / 书名 / 解释。开关必须在首页右上角够得到，打开时无系统 overlay 权限要引导授权，不许静默不显示。
- **协作节奏（R204）**：项目由 AI 自主推进，用户不指挥具体实现。用户只在三种情况出现：装 APK 后真机反馈、用着别扭提体验问题、走偏了拉回来。中间改动一律在沙盒本地攒批，**不每改一点就 commit / push / 跑 CI**；本地 commit 只作为「一批活干完」的边界，不向用户同步中间结果。等这批活收口、该出包给用户装或要发版了，才动 GitHub。回复只给结论与看板，不逐步汇报。
- **UI 分家（R206）**：MainActivity.kt 的顶层 `private` 是文件私有，拆出去的片段磁盘上必须是 `internal`；历史守门与 git HEAD 守恒认的是 `ui_sources` 合并视图（仍为 `private`），两者靠双向变换对齐。遍历真实 .kt 文件的脚本必须用 `ui_sources.view_for`，不许直读磁盘判 private 字面量。
- **纯结构轮发包判据（R207）**：老树盘根式的拆文件轮，功能零改动、用户装上看不到差别，中途不发包也不跑 CI；等各组全部搬完、合并视图逐字节一致、本地全套绿，再走一次 rc 真编译，过了才升一个修订号出包，让用户装一次确认「没拆坏」。真机反馈 / 体验别扭 / 走偏拉回三处之外，节奏由 AI 自定。
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
