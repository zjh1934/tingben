# 听本 · 标注提示词重设计方案（R16）

> 本轮定位：**研究 + 设计 + 最小闭环落地**。不是把 `prompt_zh.txt` 换一段文案，而是把 prompt 当成一条跨模块契约来重定，并把「改了之后架构哪里会跟着变」逐条列清。
> 研究底稿见 `标注提示词_多源研究报告.md`；旧模板完整保留在 `app/src/main/assets/prompt_zh_v1.txt`，可随时回滚 / A/B。

## 0. 这轮做了什么、没做什么

**做了**

- `prompt_zh.txt` 重写为 **v2**（旧版另存 `prompt_zh_v1.txt`）
- `Annotator.buildPrompt` 增加 `contextBefore` 分区、近邻取完整句（24 → 40 字）、**占位符泄漏自检**
- `Producer.run` 调用点传入上一块末尾 120 字作「紧邻前因」；开工写 `PROMPT_VERSION` 事件（带模板 hash 与字数），哪版 prompt 出的标注可追溯
- 本地静态自检 + CI 编译验证 + 拆包核对

**没做（有意为之，见 §3）**

- 确定性切分（路线 B）、GBNF 硬约束（路线 C）
- **prompt hash 进缓存主键**：`bucket()` 签名牵动 `TaskResume` / `realProgress` / `Producer` 三处，上一轮音色模块已因同类联动吃过亏，不塞进这一轮
- 不预设「角色漂」要修 —— 你还没听过成品，我不替你判断哪里坏

---

## 1. 为什么要改（证据，不是感觉）

| 现象 | 出处 |
|---|---|
| 4B 吐到 `outChars=1676` 仍不收敛、无 EOG | +13 真机事件流 |
| 思考档 `limit=1274 outChars=1676`，推理吃光预算 → JSON 拦腰截断 → `GATE_FALLBACK` | +13 真机事件流 |
| prefill 单次 63~115 秒、解码约 9 秒/token | +10 / +13 真机事件流 |

**多吐一个字都是分钟级代价**，所以任何「让模型多解释一遍、多想一遍、整块 review 一遍」的云端写法都不能照搬。

上一轮扒到的一手源码更直指根子：

- `reference/精选/antioch/annotator.rs` 注释原句：*"One call per entry: avoids batch-output repetition loops that small models fall into when asked to echo a parallel JSON array."* 它用确定性代码做切分与说话人归属，**LLM 只写 5~8 词的导演指令**，一次一行，还带 echo 检测（复述输入 ≥30 字就丢弃）。
- 我们 v1 恰好踩在它避开的坑上：让 4B 一次性吐「整块原文逐字复现 + 切分 + 归属 + 写指令」四件事，输出一个平行 JSON 数组。
- `reference/精选/alexandria/default_prompts.txt` 提供的是**结构**借鉴：角色设定 → `Output ONLY valid JSON arrays` → 4 行完整 few-shot → FIELDS → 标了 *"most important rule"* 的 RULES → 结尾重申。但**规则大半不能抄**：它明写 `Drop attribution tags`、`"Dr." → "Doctor"`、`& → and`，是「把小说改编成剧本」；我们逐字 gate 一个字都不许改。官方 README 也明说默认提示词为英文书写的，中文书要自己适配引号等约定。

v1 具体缺的四样：**无 one-shot、无 WRONG/RIGHT 反例、无主次标注、无目标段之前的原文前因**（只有「最近 3 条标注」，不是原文），也没有输出条数上界。

---

## 2. v2 逐条怎么设计

| 设计 | 依据 |
|---|---|
| **硬契约前置**：先定义「text 是原文连续子串、按序拼回等于原文」，再列负面清单 | 可验证契约 → 禁令的顺序，比单写「不要改写」更可映射到 gate 报错 |
| **引号本身也是原文**，外层对白引号必须留在 text | 逐字红线与 Alexandria「只剥最外层引号」冲突时的明确取舍 |
| **归属语归旁白、仍是原文不能删**（`他说`/`她问`） | Alexandria 的 NARRATOR/CHARACTER 规则，但去掉其「改写成人名」的允许项 |
| **一个 one-shot，形态与目标块完全相同**（2 句原文 → 3 条 JSON） | 小模型最有效的是展示「同形输出」而非文采；示例长了会学到「复述输入」 |
| **只留 2 条 WRONG/RIGHT**：加字、改写/漏句 | 反例能压缩错误空间，但每条都占 4096 预算，多了反噬 |
| **结尾只封口**（「只输出…不要解释」），不重复整段长规则 | 解码到数百 token 后模型已无力回头改前文，长重申只增 decode 预算 |
| **条数上界** = 句末标点数 + 2 | 给「别无限吐」一个可感知的界，缓解不收敛（不指望 4B 精确计数） |
| **XML 分区，顺序 `active_roles → context_before → recent → target`** | 相关信息放开头/结尾优于中间；目标块紧邻输出位。前因是**原文**，不是标注摘要 |
| **最近 3 条给完整句（40 字）** | v1 的 24 字常被截在半句里，反而误导归属 |
| **instruct 四槽**：意图/情绪 + 强度 + 节奏或重音 + 发声状态，8~15 字 | 维度框架替代开放创作；受控词表才不会漂成散文 |
| **禁写音色身份 / 禁方括号标签 / 情绪每行写全不写「仍然愤怒」** | 与已落地的 `VoiceCast` 全局身份分层一致；本地 GGUF 不解析 `[gasp]` 这类云端 qwen-audio-3.x 行内标签，写进正文会被念出来 |
| **不进主 prompt**：目标段之后的整段原文、第二轮整块 review | 端侧 prefill 太贵；post-cue 只配做「引号跨块/归属不确定」时的条件化二级 pass |

`/no_think` 软开关与手写 ChatML 模板保持原样，没动 chat 模板（动它会连带思考解析与采样链）。

---

## 3. 改 prompt 会牵动什么（这才是「不是简单替换」）

| # | 部件 | 为什么会被牵动 | 本轮 |
|---|---|---|---|
| 1 | 切块策略 | 新增前因/字段会改边界与最大长度 | 未动（B 才动） |
| 2 | 标注缓存格式 | schema、字符偏移、角色 id、prompt hash 都要进记录 | 未动 |
| 3 | 断点续传分桶 | 应按原文内容哈希寻址，不能只按章节/块号 | **未动，见下** |
| 4 | gate 校验 | prompt 若改空白/引号表示，校验器必须同步 | 未动（仍逐字 `==`） |
| 5 | TTS 输入 | 新增 emotion/prosody 字段会改 VoiceDesign 调用 | 未动 |
| 6 | 角色→音色映射 | speaker 从显示名改规范名/id 时路由要迁移 | 未动 |
| 7 | 事件日志 | 失败原因、截断位置、prompt 版本可追溯 | **已动**：`PROMPT_VERSION` + `PROMPT_PLACEHOLDER_LEAK` |
| 8 | 评测集 | 换了 prompt，旧通过率不能与新通过率混用 | 未动（§7 列指标） |
| 9 | 用户体验 | 缓存重建影响进度、发热、电量 | 如实写在 §6 |

**为什么 #2/#3 这轮忍住没动**：`AnnCache.dirFor(...)` 的分桶签名被 `Producer`、`TaskCenter` 的续跑、`realProgress` 三处共用。上一轮音色模块为了做 `voice_stamp` 刻意**没有**动 `bucket()` 签名，就是怕牵一发动三处。prompt hash 进主键是必须做的事，但应该和路线 C 一起、单独一轮连回归测试做，而不是夹在改文案的轮次里。

**本轮的闭环边界**：模板 → 装配 → 调用点 → 事件，四处同步；`native` 一行没碰，状态机一行没碰。

---

## 4. 三条路线，一次只走一条

### A · 最小闭环（本轮已做）
换模板 + 装配层 + 可观测。零 native 改动、零状态机风险、可一键回滚（`prompt_zh_v1.txt`）。**降低失败率，不改变「4B 仍要逐字吐整块」这个成本结构。**

### B · 架构级（Antioch 路线，最省时间但要动半条链）
确定性代码按中文句末标点、引号边界、最大长度切出候选行并做对白/旁白判定 → 4B 只对每行输出 `speaker` + 5~8 词 `instruct` → **`text` 由程序直接引用原文切片，物理上不存在抄错** → 输出行数固定、decode 长度从几百字压到短标签。

联动：新增 `LineSegments` 与字符偏移；`Ann` 加 id/start/end；`Annotator.parse/gate` 改为按行对齐；`AnnCache` 格式加版本号；`fallback/directSegments` 复用同一切句器；TTS 逐行调用；古文、半文白、意识流、无标点、网文密集抢话会系统性退化，必须保留「整块 4B 预测」作低置信度回退并单独打标。

代价说清：确定性代码不懂反讽、旁白模仿角色、心理活动与对白混合，也做不出十行连续同一角色的平滑情绪曲线。

### C · 硬约束（本地确实可行）
锁版本 `app/src/main/cpp/llama.h` **第 1527~1534 行确有 `llama_sampler_init_grammar`（GBNF）声明**，还有 `grammar_lazy_patterns`。可以把「只输出 JSON 数组、字段顺序固定、speaker 取角色枚举」从软禁令变成采样期硬约束，减少 parse 四级修复和字段漂移。

联动：`NativeEngine.llmGenerate` JNI 加 grammar 参数 → C++ 采样链在 greedy/temp 之后挂 grammar → Kotlin 按 roster 动态生成 grammar 字符串；注意 lazy grammar 的触发条件与每 token 候选集计算开销。**GBNF 只守语法壳，绝不保证 `text` 逐字等于原文**，字符级 gate 仍是最终裁判；也不等于云端的 `response_format: json_schema / strict`，后者是服务端能力，本地 GGUF 没有同名 API 自动解析任意 Schema。

推荐顺序：**A → C → B**。C 不动切块、风险中等、收益直接；B 收益最大但联动最宽，等 A 的真机数据出来再决定值不值。

---

## 5. 缓存作废与版本表（必须补，别漏）

**主键应改为内容寻址**：原文规范化哈希 + 块起止偏移 + 切分规则版本 + 角色表快照哈希 + **prompt 全文哈希** + 模型/量化哈希 + 推理后端版本 + 采样参数 + thinking 模式 + grammar 版本 + TTS profile 哈希。

**作废分级**，不是「改一个字全量重标」：

- 改了归一化、原文切片、JSON 字段语义、gate 规则 → 旧标注全废
- 角色表只加别名、规范 id 不变 → 可续，后台跑归属回归
- 只改措辞、输出契约未变 → 小批 A/B 后再定
- 换权重 / 量化 / llama.cpp commit / thinking / grammar → 默认全废
- 只改 voice profile → 标注缓存不动，**音频缓存必须废**

**续跑要区分「已生成」与「已验证」**：只跳过 `schema_valid && !text_loss` 的块，不能按「文件存在」判断。

**版本表是工程交付物**，每版至少记：prompt 全文 hash、变更原因、适用模型、允许 schema、示例数/反例数、前因窗口、是否允许 post-cue、thinking、max_tokens、重试规则、gate 规则、盲听结果、手机平均耗时。没有它，就无法判断「新 prompt 更会演戏」是不是拿逐字保真换来的。

---

## 6. 代价（如实）

- v2 的 system 实测 **795 → 968 字，净增 173 字**（已压缩过一轮：最初写到 1246 字、净增 512 字，主动砍掉冗余解释与重复禁令）。按中文约 1~1.5 token/字估，每块多约 200~260 token 的 prefill；**每块都要重复付一次**（KV 每块清，无法缓存），真机上预计单块 prefill 多 20~40 秒，具体按 tokenizer 与实测为准。
- 9 秒/token 的物理速度不变。要真「快」只能靠 **B（少吐字）** 或 **C（少吐废话）**，A 只是让它更可能一次吐对。
- **收益未知**：没有真机 A/B 之前，不宣称 v2 更会演戏、更快出 JSON、或角色更稳。
- one-shot 与反例是为「逐字保真 + JSON 收敛」写的，不是为「表现力」写的；表现力那层归 `instruct` 与音色模块，别混为一谈。

---

## 7. 下一轮真机验证指标

`PROMPT_PLACEHOLDER_LEAK` 必须为 0；事件流出现 `PROMPT_VERSION v2 sha=…`；逐块记录 JSON 一次通过率、`gate` 通过率 / `GATE_FALLBACK` 降级率、平均每块 decode token 数、prefill 秒数、单块总耗时。对照方式：同一本 `听本_试音短篇.txt`，v1 与 v2 各跑一次（回滚只需换回 `prompt_zh_v1.txt`），只看这四个数，不做主观「听着更好」的结论。

跨块 speaker 一致性**只作为观测量登记**，不预设它「漂」、也不预设要修。

---

## 8. 边界

- 研究底稿里「未找到 Alexandria 一手实现」是检索范围所限，本地 `reference/精选/alexandria/` 已有原文，已在归档文件头部补正。
- Alexandria five-fix 的完整实现、Qwen3-TTS 1.7B VoiceDesign 的逐行指令继承与本地 GGUF 参数、GBNF 在本机每 token 的实际开销，均**未获一手确认 / 未实测**。
- 「位置效应」「reasoning tax」等结论来自通用研究与第三方小样本实验，只用于支撑结构设计，不当作本项目已量化的准确率结论。
- 本轮改了 Kotlin，CI 编译与拆包核对过；**真机出声与 v2 效果仍是零证据**。
