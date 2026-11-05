# 听本手机端中文无限制语言模型选型调研

## 摘要

最值得优先实测的是 **Huihui AI 的 Qwen3-4B-Instruct-2507 abliterated Q4_K_M GGUF**，因为它保留了 2507 非思考版的 ChatML 模板，不会像 Thinking-2507 那样在答案前输出 `<think>` 推理块；文件约 **2.50 GB**、原生上下文 262,144 token，且已有可直接被 llama.cpp 使用的 GGUF [4][5]。第二选择是 **未改版的 Qwen3-4B-Instruct-2507 Q4_K_M**，其官方能力宣称最完整：指令遵循、工具使用、256K 长上下文和多语能力，适合作为 JSON 格式遵循的基线；它也有 Unsloth 的 Q4_K_M，约 2.50 GB [3][4]。Qwen3-4B-Thinking-2507 abliterated 不建议用于“逐句直接输出 JSON”，因为它只支持思考模式，默认模板会自动包含结束标记，输出合同天然需要后处理 [2][6]。公开资料没有找到任何候选在“小说 JSON 标注 + 中文导演指令”上的统一通过率基准；因此“更会写 JSON”目前只能由模板、工具使用和指令遵循证据支撑，必须先在听本真实语料上做 A/B 验证。所有 4B 级 Q4 权重可装入约 8 GB 设备，但 256K 上下文并非可实际用于手机的推理窗口：KV cache 会随长度增长，纯 CPU 长上下文还会显著拖慢 prefill [4][16]。

## 一、候选不是“参数越大越好”，而是四个约束的交集

本报告只保留满足“**中文理解、端侧 GGUF、约 4B 级、存在 abliterated/uncensored 上游**”的候选，并把 Qwen2.5/GLM 等其他中文家族作为降级或备用候选。手机是 arm64 纯 CPU 这一条件改变了排序：一个在 API 上 JSON 很强、但没有完整 GGUF 与适配模板的模型，不能替代当前 mlabonne/Qwen3-4B-abliterated；同样，一个能在 NVIDIA GPU 上跑得更快的 14B 模型也不能计入手机推荐。

当前基线 mlabonne/Qwen3-4B-abliterated 来自 Qwen3 初版混合检查点：先由 Qwen/Qwen3-4B-Base 到 Qwen/Qwen3-4B，再由 mlabonne 做 abliteration。其作者明确说这是实验性项目，并给出推荐采样参数 `temperature=0.6、top_k=20、top_p=0.95、min_p=0` [1]。需要特别指出，Qwen3 初版是“一个检查点、两类响应模式”的混合模型，而 2507 系列把 Instruct 和 Thinking 拆成两个独立检查点；这意味着不能把初版软开关的直觉直接套到 2507。

| 候选 | 原始/基础模型 | abliterated/uncensored 上游 | Q4_K_M GGUF 证据 | 手机端首选定位 |
|---|---|---|---|---|
| Qwen3-4B-Instruct-2507 abliterated | Qwen/Qwen3-4B-Instruct-2507 | huihui-ai/Huihui-Qwen3-4B-Instruct-2507-abliterated | mradermacher 仓库列有 Q4_K_M，2.5 GB [5] | **第一推荐**：无 `<think>`，可直接输出纯 JSON |
| Qwen3-4B-Instruct-2507 | Qwen/Qwen3-4B-Instruct-2507 | 无需去拒答时，可暂用官方 instruct | Unsloth 等仓库有 Q4_K_M，2.5 GB [4] | **第二推荐/对照基线**：能力证据最硬，格式最易测 |
| Qwen3-4B-Thinking-2507 abliterated | Qwen/Qwen3-4B-Thinking-2507 | huihui-ai/Huihui-Qwen3-4B-Thinking-2507-abliterated | mradermacher 仓库列有 Q4_K_M，2.5 GB [6] | **有条件可用**：仅适合可容忍 `<think>` 的后处理管道 |
| Qwen2.5-3B-Instruct-abliterated | Qwen/Qwen2.5-3B-Instruct | huihui-ai/Qwen2.5-3B-Instruct-abliterated | 上游存在；本轮未找到可核对的 Q4_K_M GGUF 文件清单 | 未找到充分证据，不作手机首选 |
| Qwen2.5-7B-Instruct（未 abliterate） | Qwen/Qwen2.5-7B-Instruct | 未找到可信、带证据链的 abliterated 上游 | Unsloth 等常见；本报告未逐项核对其 Q4_K_M | JSON/中文候选，但不满足“无限制”硬要求 |
| GLM-4-9B-0414-abliterated | THUDM/glm-4-9b-0414 系列 | huihui-ai/GLM-4-9B-0414-abliterated | 有二次转换 GGUF；本轮未确认 Q4_K_M 与模板适配 | 证据不足，体积更大，不作推荐 |
| Yi / DeepSeek 蒸馏 | 多个可能来源 | 本轮**未找到**可核验的 abliterated/uncensored GGUF | 未找到证据 | 排除于“已可替代手机模型”清单 |

这一矩阵最重要的一点是：候选数量并不等于可选数量。Qwen2.5-3B abliterated 上游存在，却没有本轮能独立验证的 Q4_K_M GGUF 清单；GLM-4 有零散二次转换页，却缺少原始模型卡、模板和量化完整性的闭环。因此，不能因为 Hugging Face 搜索结果里出现“GGUF”字样就判定“手机可替换”。本报告对它们采取保守标记，而不是为了满足清单完整度而把它们写成推荐。

## 二、Qwen3-2507 的两个检查点不是开关，而是两种输出合同

**Qwen3-4B-Instruct-2507 的 Q4 权重约 2.5 GB，但原生 262,144 token 绝不能等同于手机可实际跑满 256K。** Unsloth 的 GGUF 文件元数据直接显示架构为 `qwen3`、36 层、8 个 KV 头、原生上下文 262,144，文件 2.5 GB [4]。这个数字只说明权重编码后的磁盘体积和模型自报的原生训练长度；它没有告诉应用实际有多少空闲 RAM 可用于 KV cache。普通 8 GB Android 设备还要运行系统、App、系统提示、小说上下文和生成文本，因此推理窗口应由设备实测决定，而不是照抄模型卡。

![三款4B级候选Q4_K_M权重均约2.5GB](/data/workspace/dr:R56/听本/docs/fig_q4_size.png)

*图 1：三款约 4B 候选的 Q4_K_M 权重文件均约 2.5 GB；它只代表可下载的权重，不代表可同时展开完整上下文。数据来源：Unsloth、Huihui 等 GGUF 仓库文件元数据 [4][5][6]*

**Instruct-2507 是“纯非思考”，Thinking-2507 是“只思考”；二者不能用 /think、/no_think 互相切换。** 官方 Instruct-2507 仓库明确写“只支持非思考模式，不生成 `</think>` 块，也不再需要指定 `enable_thinking=False`”；Thinking-2507 则写“只支持思考模式，也不再需要指定 `enable_thinking=True`”，并且默认聊天模板会自动包含 `</think>` [2][3]。对于初版 Qwen3，用户可以在运行时软切换；2507 系列则把两个行为做成不同权重。因而在 2507 的 Huihui Thinking abliterated 输出中追加“/no_think”，不能严谨地称其为“关闭思考”：最多是试探性提示，不能构成工程保证。

| 维度 | Qwen3-4B-Instruct-2507 | Qwen3-4B-Thinking-2507 |
|---|---|---|
| 官方输出模式 | 仅非思考 | 仅思考 |
| `<think>`/`</think>` | 不输出 | 会输出；官方说默认模板自动带结束标记 |
| 原生上下文 | 262,144 | 262,144 |
| 官方强调 | 指令遵循、工具使用、256K 理解 | 推理深度、复杂任务；官方建议增加思考长度 |
| 4B JSON 标注适配 | 高：输出合同直接 | 低至中：必须切分思考与答案 |
| 推荐量化 | Q4_K_M 作起点 | Q4_K_M 可作起点，但应首选更高量化以压低格式漂移 |

听本需要的是每个文本块返回 `{"text","speaker","instruct"}`，其中 `instruct` 是中文导演指令。这个任务不是“先做几万 token 证明再给答案”的复杂数学证明，而是大量短、重复、格式严格的结构化生成。Thinking-2507 可能在歧义判断上有理论优势，却把短答案包装进思考合同，增加截断、解析和延迟风险；在用户已明确“prefill 极慢”的前提下，它是最不符合生产诉求的 2507 变体。

## 三、Huihui 改版解决了“拒答”，但没有证明“JSON 更会写”

**Qwen3-4B-Instruct-2507 abliterated 应成为第一候选，原因是它与当前管线最兼容，而不是因为公开证据已证明 abliteration 提升了格式遵循。** huihui-ai 上游自述是基于 Qwen3-4B-Instruct-2507、用 abliteration 创建“uncensored version”；GGUF 仓库则明确列出 Q4_K_M 为 2.5 GB，并注明它是 Huihui 上游的静态量化 [5][7]。因此，它是本轮证据链最完整的 Instruct 系 abliterated GGUF。

但“无拒答”与“更会写 JSON”是两个不同命题。Huihui 模型卡说明的重点是移除拒答方向，不是给出结构化输出基准。相反，abliteration 的机制是在残差流里识别并正交化拒答方向；mlabonne 的 Qwen3 改版也明确说没有“一刀切”策略，而且混合检查点里推理模式会干扰非推理拒答，改版仍是实验性的 [1]。这意味着：改版可能让模型更少拒绝敏感文本，却也可能改变其服从系统约束的稳定性。当前项目“更会写 JSON”的强证据尚未出现。

**最佳比较不是“abliterated vs 官方”一次跑分，而是固定模型、量化、提示词、采样与输出长度，测 JSON 有效率和字段完整率。** 官方 Instruct-2507 可作为零改版基线；Huihui abliterated 作为同代改版测试组。评测指标应至少包括：完整可解析 JSON 率、三个必填字段齐备率、中文 `instruct` 语义有效率和 gate 重试率。不能只比较“回答质量”或“看起来自然”，因为前者允许错格式、后者可能被拒答消失造成错觉。

公开资料对 abliterated 格式退化的说法强度不一。一个社区分析文章提出“指令服从和格式遵循可能先于知识能力退化”，属于生产观察而非论文；它建议把格式合规率独立打分，并对 ablated 模型偏向 Q6_K/Q8_0 [8]。这一建议与听本目标一致，但证据等级只能是“社区实测/技术评论”，不能把它写成已证明事实。较稳妥的工程结论仍是：先测 Q4_K_M，若失败率不达标，再做同模型 Q5_K_M/Q6_K 的受控对照；不要先假定 Q8 一定解决服从问题。

## 四、Thinking-2507 的 Q4 GGUF 确实存在，但“手机能跑”不代表“适合流水线”

**Thinking-2507 abliterated 的 GGUF 证据比许多其他中文替代模型更完整，因此不应因风险而误判成“不存在”；真正的问题是输出合同。** mradermacher 仓库把 huihui-ai 上游列为基础模型，并列出 Q4_K_M 2.5 GB；其文件元数据还显示架构 `qwen3`、36 层、262,144 上下文，以及带有工具/推理处理的聊天模板 [6]。这说明它能被 llama.cpp 识别，也符合 Qwen3 架构。

![Thinking-2507-abliterated的Q4_K_M为2.5GB](/data/workspace/dr:R56/听本/docs/fig_thinking_quants.png)

*图 2：Huihui-Qwen3-4B-Thinking-2507-abliterated 从 Q2_K 到 F16 的 GGUF 体积；Q4_K_M 为 2.5 GB。数据来源：mradermacher 聚合 GGUF 仓库 [6]*

**如果仍要试验 Thinking 版，解析器必须容忍“答案在思考块之后”，而且停止规则要随模板变化。** 官方说默认模板自动包含 `</think>`，并允许输出只出现结束标记而无显式开始标记 [2]。这意味着不应硬编码 `<think>` 起始字符串；更稳健的方法是按官方模板先分离 `reasoning_content`，再取最终 assistant 内容。其代价是：每个小说块都要先产生可能很长的推理，再复制、切分、丢弃这部分文本；在纯 CPU 手机上，prefill 和 decode 都会更慢。

**Thinking-2507 的高工具使用指标不能自动兑换为“导演指令 JSON”高分。** 官方 Thinking-2507 仓库声称它在推理、指令遵循、工具使用、文本生成与 256K 长上下文方面增强；官方表格里其 BFCL-v3 为 71.2，而 Instruct-2507 为 61.9 [2]。这能支持“Thinking 版具有较强通用工具/代理能力”，却不能证明它在短中文导演指令 JSON 上更稳，因为 BFCL 不是听本这个中文、生成式、无函数 schema 的字段任务。若 App 当前最痛的是 JSON 格式与延迟，而不是复杂情节推理，Thinking 版不应优先。

## 五、JSON 能力应按“官方宣称、公开评测、App 实测”分三级，而不能混写

**Qwen3-4B-Instruct-2507 是目前唯一具有足够一手能力宣称、又能拿到 Q4 GGUF 的推荐对象。** 官方模型卡列出 256K 长上下文、指令遵循、工具使用、文本理解、编码和多语言能力；官方表格还给出 IFEval 83.4、BFCL-v3 61.9、MultiIF 69.0、MMLU-Pro 69.6 等指标 [3]。这些数字是官方自选评测，不能等价为中文小说标注通过率，但它们比“改版作者称更强”更适合作为选择依据。

**官方明确推荐的采样参数是 `temperature=0.7、top_p=0.8、top_k=20、min_p=0`，而不是当前 mlabonne 改版的 `temperature=0.6、top_p=0.95、top_k=20`。** Qwen 2507 官方 README 给出上述值，并建议常用输出长度 16,384 token；结构化任务的公开实践通常会进一步降低温度，但 Qwen 官方并未在本轮查到的 2507 README 中承诺某个“JSON 模式”或给出固定的 JSON 专用采样值 [3]。因此，App 可以在正式评测中比较两组参数，却不能声称官方已经指定“JSON 必用 greedy”。

**“中文能力”和“中文 director instruct”是两个评测对象。** Instruct-2507 的多语/中文相关公开指标有 MultiIF 69.0、MMLU-ProX 61.6、INCLUDE 60.1 [3]。这些指标可以支撑其多语和指令能力，却不能证明它能稳定生成“压着怒气”“句尾下沉”“低声耳语”等导演指令。后者涉及术语一致性、角色归属、强度与动作是否冲突，应抽取真实小说片段构造回归集，而不是使用通用聊天满意度评价。

**abliterated 是否真提升格式遵循，公开证据不足以下肯定结论。** 本轮未找到覆盖 Qwen3-4B、Q4_K_M、JSON-only、中文 instruct 的统一横评。能确认的是：abliteration 的目标是移除拒答，不是训练 JSON 服从；Huihui 与 mlabonne 页面也没有 JSON 结构化通过率 [1][7]。因此，报告不对任何 abliterated 版给出“JSON 更强”的断言；可成立的判断是“它更可能不拒答，但服从性与格式仍需验证”。

## 六、llama.cpp 工程兼容的关键是模板和上下文预算，不是只把文件塞进手机

**三款 Qwen3 候选都可用 Qwen3 ChatML 模板，但“手写 ChatML”必须从当前初版格式升级到文件自带的正式模板。** Unsloth 的 Q4 文件包含 `tokenizer.chat_template`，其无工具分支使用 `<|im_start|>{role}\n{content}<|im_end|>\n`，并在末尾生成 `<|im_start|>assistant\n` [4]。因此，若 App 目前是自己拼 `<｜im_start｜>user…` 之类字符串，应让 llama.cpp 的聊天模板接口生成提示，而不是长期维护手工模板；尤其要避免给 Thinking 版错误加入 `</think>` 开始符。

**建议的系统消息应同时声明字段、顺序、禁止文本、示例和停止条件，而不是只说“输出 JSON”。** 可用形式如：

> 你是一个小说文本标注器。只输出一个严格 JSON 对象，禁止 Markdown、代码块、解释、问候与前后文本。对象必须依次包含 text、speaker、instruct。text 是原文片段；speaker 是说话者或“旁白”；instruct 是中文导演指令，可描述语气、情绪、节奏和发声方式。若无法确定说话者，输出“旁白”。

若对话接口不支持结构化约束，应将 stop token 设为 `<|im_end|>`，并只截取 assistant 区段。对于 Thinking 版，还需按模板拆出 `reasoning_content`，不能直接把整段原始输出喂给 JSON 解析器。

**KV cache 预算应成为设备分级参数，而不是把 262,144 当作默认上下文。** 4K 上下文下的 Q4 总内存约 3.8 GB，32K 约 7.1 GB，128K 约 18.7 GB；这些是 Qwen3-4B 的第三方估算，不是官方承诺，也不是听本实测值 [16]。结合 Unsloth 元数据中的原生 262,144 长度，可得出工程结论：文件体积不是唯一约束；若手机只有 8 GB RAM，应先用短 prefill 和小上下文回归测试，再决定是否扩展到章节级标注。第三方估算还提示，长上下文会显著拖慢生成，这与用户当前 prefill 瓶颈一致 [16]。

| 项目 | Qwen3-4B-Instruct-2507 abliterated | Qwen3-4B-Instruct-2507 原版 | Qwen3-4B-Thinking-2507 abliterated |
|---|---|---|---|
| llama.cpp 架构识别 | qwen3 | qwen3 | qwen3 |
| Q4_K_M 文件 | 约 2.5 GB | 约 2.5 GB | 约 2.5 GB |
| 输出模板 | 普通 ChatML，无思考块 | 普通 ChatML，无思考块 | ChatML + `</think>` 思考合同 |
| JSON 管线改动 | 小：解析 assistant 内容 | 最小：作为基线 | 大：拆推理与答案，设更高超时 |
| 上下文策略 | 从短窗口起步实测 | 从短窗口起步实测 | 不推荐为纯 CPU 手机默认 |
| 采样 | 先官方 2507 建议再对照 mlabonne 参数 | temperature 0.7、top_p 0.8、top_k 20、min_p 0 | 先官方建议；绝对不要依赖 /no_think |

## 七、Qwen2.5、GLM、Yi、DeepSeek 只能作为待验证候补，不能因中文名气进入推荐

**Qwen2.5-3B-Instruct-abliterated 有上游但缺少可核验的手机端 GGUF 链条。** huihui-ai/Qwen2.5-3B-Instruct-abliterated 明确说它是 Qwen2.5-3B-Instruct 的 uncensored 版本，并给出了基于 Transformers 的使用方式 [9]。然而本轮没有在可信 GGUF 仓库找到同一改版的 Q4_K_M 文件清单、量化元数据或 ChatML 模板核验。它的优势是 3B 更小、中文基础来自 Qwen2.5 多语系；缺点是能力、工具使用和 JSON 证据都不如 4B 2507，且去拒答也不是听本的 JSON 目标。因此结论是“上游存在，GGUF 证据未找到”，而非推荐。

**Qwen2.5-7B-Instruct 是中文和工具使用的合理备胎，但“未 abliterate”使它与用户硬要求不符。** Qwen2.5 官方技术资料强调多语言、指令遵循、长上下文和工具调用，Qwen2.5-7B-Instruct 的 GGUF 在社区生态中也很常见。但本次没有找到可核验的 Qwen2.5-7B-Instruct abliterated 上游页及对应 Q4_K_M GGUF；只有零散转换页不能证明完整性与模板正确。7B Q4 还会显著大于 4B，纯 CPU 下不能因为“参数更多”就推定 JSON 更好。建议：若项目允许“不要求 abliterated”，才把 7B 官方版纳入 A/B；若必须 uncensored，则当前证据不支持它。

**GLM-4-9B-0414 abliterated 有搜索可见的 GGUF 镜像，但原始能力与模板证据不完整。** 可检索到 huihui-ai/GLM-4-9B-0414-abliterated 的存在，以及第三方仓库列出的 `GLM-4-9B-0414-abliterated-Q4_K_S-GGUF` [10]。但这不足以确认 Q4_K_M、llama.cpp 的 GLM4 模板、中文工具使用在当前二进制版本中的表现，也没有 JSON 实测。更重要的是，9B 在手机 CPU 上既慢又吃内存，应只作为大内存设备候选，或放在 PC/K80 离线标注工厂。

**本轮未找到可信的 Yi 1.5/2 abliterated GGUF 与 DeepSeek 蒸馏版 abliterated GGUF 闭环。** 搜索结果出现 GLM、Qwen2.5 等候选，但没有形成“官方原始模型 + abliterated 上游 + GGUF 文件元数据”的证据。DeepSeek 常见的蒸馏模型还与 R1 推理模板、长 CoT 密切相关，对短 JSON 任务的风险与 Thinking-2507 相似。应如实标记为“未找到证据”，不能为了覆盖更多品牌而把 API 模型或未经证实的第三方转换列入手机推荐。

## 八、最终推荐：优先 Instruct abliterated，保留官方版做对照，Thinking 只作实验

### 1. 首选：Huihui AI Qwen3-4B-Instruct-2507 abliterated，Q4_K_M

推荐理由是“输出合同与基线最接近”，不是“已证明 JSON 更强”。它有上游改版页、Q4_K_M GGUF、Qwen3 架构、普通 ChatML、约 2.5 GB 权重和 262,144 原生上下文的完整证据 [2][5][7]。建议用它替换 mlabonne 的初版 abliterated，但不要假定速度会改善；prefill 慢主要可能来自 CPU、上下文长度、线程/批处理配置，而不是模型品牌。验证集至少应覆盖：多说话者对话、旁白与对白混杂、粗口或敏感内容、人物别名、嵌套引号、长句和角色突然换场。

### 2. 第二：Qwen/Qwen3-4B-Instruct-2507 官方版 Q4_K_M

官方版最适合当对照基线。其官方能力、模板和量化元数据证据最完整 [3][4]。若官方版的 JSON 通过率已经满足产品阈值，就不必承担 abliterated 改版的服从性风险；若官方版对少量敏感内容拒答影响离线体验，再把 Huihui 版作为补充或回退。推荐采样可从官方 `temperature=0.7、top_p=0.8、top_k=20、min_p=0` 起步，在固定测试集上比较 JSON 有效率，而不是凭单次输出判断 [3]。

### 3. 有条件第三：Huihui AI Qwen3-4B-Thinking-2507 abliterated，Q4_K_M

只建议在“内容歧义确实影响标注质量、且能接受更长推理和更高 CPU 成本”时测试。它的官方基础模型指标和 GGUF 文件证据都很清楚，也能被 llama.cpp 加载 [2][6]。但默认思考合同会污染短 JSON 输出，也无法通过 /no_think 严谨关闭。若要采用，必须在服务端或后台解析器里稳定剥离 `</think>` 至结尾的内容，并设置 thinking 与 final-answer 的双段超时；不应作为首版替换。

## 九、明确排除与上线门槛

**排除 1：不能把 Qwen3-4B-Thinking-2507 当“加 /no_think 就能直接输出的更快模型”。** 官方明确只支持思考模式，默认模板自动带 `</think>` [2]。即使输出有时只有结束标记，它仍不符合“assistant 直接给出 JSON”的合同。

**排除 2：不能把 14B/32B 或需要 GPU 的模型当手机候选。** 它们只能作为 PC/K80 离线标注工厂方案，在入库前批量标注小说，再由手机读取结果；或在手机端出现 OOM/超时时把工作卸载到离线服务器。这个边界不是对能力评价，而是对任务约束的尊重。

**排除 3：不能把“Hugging Face 上有 GGUF 链接”自动理解为“已验证可跑”。** 本轮对 Qwen2.5-7B abliterated、GLM-4-9B-0414 等模型都没有形成完整证据链；Yi、DeepSeek 蒸馏的 abliterated GGUF 也没有找到可信证据。它们应列入后续搜索清单，而不是立即下载替换。

**上线门槛应是一道硬 gate，而不是目测。** 第一，固定同一批真实小说块、同样输出长度与上下文；第二，分别统计完整 JSON、三字段齐全、中文 `instruct` 语义有效、重试后成功四项；第三，只有当 abliterated 版在不拒答和格式遵循两项均不劣于官方基线时，才替换线上模型；第四，若 abliterated 在任何一项退化，就回退官方版，而不是继续调提示词掩盖 parser 错误。这个策略能同时回答“更会写 JSON 吗”和“去拒答是否值得牺牲格式稳定性”。

## 引用来源

[1] https://huggingface.co/mlabonne/Qwen3-4B-abliterated
> “This is an uncensored version of Qwen/Qwen3-4B created with a new abliteration technique.”

[2] https://huggingface.co/Qwen/Qwen3-4B-Thinking-2507
> “This model supports only thinking mode… the default chat template automatically includes </tool>.”

[3] https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507
> “This model supports only non-thinking mode and does not generate  blocks in its output.”

[4] https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF
> “qwen3.block_count 36 … qwen3.context_length 262144.”

[5] https://huggingface.co/mradermacher/Huihui-Qwen3-4B-Instruct-2507-abliterated-GGUF
> “Provided Quants … Q4_K_M 2.5 fast, recommended.”

[6] https://huggingface.co/mradermacher/Huihui-Qwen3-4B-Thinking-2507-abliterated-GGUF
> “This model isn't deployed by any Inference Provider.”

[7] https://huggingface.co/huihui-ai/Huihui-Qwen3-4B-Instruct-2507-abliterated
> “This is an uncensored version of Qwen/Qwen3-4B-Instruct-2507 created with abliteration.”

[8] https://www.worldprogramming.org/posts/abliterated-models-lose-obedience-before-they-lose-knowledge-eeypay
> “The first thing to go is obedience, not knowledge… respecting the instruct template… JSON schema, tool-call syntax.”

[9] https://huggingface.co/huihui-ai/Qwen2.5-3B-Instruct-abliterated
> “This is an uncensored version of Qwen2.5-3B-Instruct created with abliteration.”

[10] https://huggingface.co/irmma/GLM-4-9B-0414-abliterated-Q4_K_S-GGUF
> “This model was converted to GGUF format from huihui-ai/GLM-4-9B-0414-abliterated using llama.cpp.”

[11] https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507/blob/main/README.md
> “We suggest using Temperature=0.7, TopP=0.8, TopK=20, and MinP=0.”

[12] https://huggingface.co/mradermacher/Huihui-Qwen3-4B-Thinking-2507-abliterated-GGUF/tree/main
> “Q4_K_M 2.5 fast, recommended.”

[13] https://huggingface.co/huihui-ai/Huihui-Qwen3-4B-Thinking-2507-abliterated-w4g128
> “This model’s safety filtering has been significantly reduced…”

[14] https://aihub.qualcomm.com/models/qwen3_4b_instruct_2507?chipsets=qualcomm-snapdragon-x-elite
> “The Qwen3-4B-Instruct-2507 is a… multilingual instruct language model… excelling in language understanding, generation, coding, and mathematics.”

[15] https://qwen.ai/
> “Qwen is an AI assistant for everyone, powered by the Qwen series models.”

[16] https://localmodel.run/model/qwen3-4b
> “4k context ~3.8 GB 32k context ~7.1 GB 128k context ~18.7 GB.”

[17] https://modelscope.cn/models/Voconly/Qwen3-4B-Instruct-2507-Q4_K_M-GGUF
> “量化方法：Q4_K_M…文件大小：~2.5 GB。”

[18] https://www.zhipuai.cn/en
> “GLM-5.3…Built for complex software engineering and long-horizon agent tasks, with a 1M context window.”

[19] https://huggingface.co/jc-builds/Qwen3-4B-Instruct-2507-GGUF
> “Chat format ChatML, non-thinking only.”

[20] https://huggingface.co/huihui-ai/Huihui-Qwen3-4B-Thinking-2507-abliterated-w4g128
> “This is an uncensored Quantized version of Qwen/Qwen3-4B-Thinking-2507 created with abliteration.”