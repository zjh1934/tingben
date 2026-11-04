# 哭声 / 笑声 / 叹气：端侧本地方案调研（R11）

> 结论先行：**当前这套模型做不出「独立的一段笑声/哭声音效」，但能做出「带着哭腔、气声笑、先叹一口气再说话」的语气。**
> 本版不换模型、不重下权重、不破「正文一字不改」的契约，用导演指令把这条能力接上。

---

## 1. 手上这个模型到底行不行

`Qwen3-TTS-12Hz-1.7B-VoiceDesign` 吃的是**自然语言 instruct**，不是行内标签。

- **能**：官方 Qwen3-TTS 发布示例里明确有「以极度悲伤、带着明显哭腔的语气，用较小的音量缓缓诉说……声音颤抖而压抑」，说明哭腔、颤音、气声属于可导演的表现范围 [citation:10]。
- **不能**：`[laughter]` / `[sigh]` / `[cry]` 这类方括号标签，本模型**不解析**。Voicebox 的引擎对照文档点名 Qwen3-TTS「read them literally as text」——写进去会被当成字念出来 [citation:6]。所以直接照抄 CosyVoice3 的标签语法，结果是朗读出「 laughter 」这几个字。
- Qwen-Audio-**3.0**-TTS（新一代、16 语言、2 个变体）才有 **86 个细粒度行内标签**，覆盖 laughs / breaths / cough / gasp / sighing，能精确到词级插局部情绪 [citation:5][citation:14]。**它是另一代模型，不是我们手上这个 1.7B GGUF**，目前也没有对应的 qwentts.cpp 端侧 GGUF 路线。

一句话：**别把「新一代的能力」算到「手上这个权重」头上**。

---

## 2. 别人是怎么做的（可抄的几条路）

### ① Alexandria：拟声词，不换模型，效果最自然 —— 但和我们契约冲突

Alexandria 不让模型读标签，而是让 **LLM 把非语言表达写成可发音的文本**：

> gasps / laughter / sighs / **crying** / heavy breathing → `"Ah!"` `"Haha!"` `"Sniff…"`，
> 再给这条配一句短的 instruct，由 Qwen3-TTS 当真实台词演出来 [citation:8][citation:13]。

它自己写明这是「real pronounceable vocalizations … not robotic tags」，而且用的就是我们同款 Qwen3-TTS [citation:13]。

**代价**：成品里会多出原文没有的字。我们的硬契约是「所有 text 拼起来必须逐字等于原文」，所以**这条不能直接用**。除非将来把「拟声词」做成独立可开关、并明确告诉用户「成品会比原文多出几处人声」。

### ② CosyVoice3：引擎原生标签，最理想 —— 但要换引擎

CosyVoice3 内置副语言标签，模型生成时就融合进去，不是后期拼接 [citation:5]：

- 插入类：`[breath]` `[laughter]` `[cough]` `[sigh]` `[gasp]`
- 包裹类：`<laughter> text </laughter>` 让这句话「笑着说完」
- 还有 `happy / sad / angry / whispers / laughs / calm / surprised / serious` 等情绪指令 [citation:9]
- 中文社区已经有人做了「漫剧配音 WebUI」一键插 `[laughter] [breath] [sigh]`、12 个情绪预设 [citation:7]

**代价**：我们走的是 `qwentts.cpp` GGUF + JNI，换成 CosyVoice3 等于重做引擎层、重下模型、重新验证手机 CPU 速度（现在 RTF 已经 17）。**首版不做**。

### ③ 通用兜底：`[pause Nms]` 与参考音频表演

VoiceStudio 的引擎对照文档给了一个所有引擎都吃的便宜招：`[pause 500ms]` / `[pause 1.5s]` 是真拼接静音，标点、省略号、破折号也能显著改变节奏 [citation:11]。另外**参考音频本身就是表演指令**——零样本克隆会连「怎么说」一起复刻，不只是音色 [citation:11]。

我们的块间静音、降级切短句就属于这一类；voicedesign 没有参考音频，所以后者用不上。

### ④ 音效库拼接 / 后期处理

Voicebox 走 `pedalboard` 做 8 种后处理（变调、混响、压缩等），还有 timeline 多轨编辑 [citation:6]；Polyphon Audio 甚至能生成音效音乐 [citation:15]。都是**桌面端**能力。
最土的是内置几个 `laugh.wav` / `sigh.wav` 在拼接阶段按标注插入——版权、自然度、拼接痕迹都不理想，**列为最后备选，不进首版**。

---

## 3. 本版落地：导演指令增强（不换模型）

`core/NonVerbal.kt`：

1. 扫描**原文里已经存在**的非语言描写（笑 / 哭 / 抽泣 / 叹气 / 咳嗽 / 喘 / 哽咽 / 发颤…），按优先级命中一条导演短语；
2. **只在合成前**给 `instruct` 追加一句，例如「带出压抑的哭腔，声音微微发颤，气声偏重，句尾下沉」；
3. 明确加约束「除原文外不要另加任何字词，不要把括号或说明念出来」，防止模型自作主张补拟声词；
4. **`text` / `speaker` 一个字不动** → 逐字 gate 完全不受影响，`pipeline_spec` 的核心契约不变；
5. 命中数写进 `NONVERBAL` 事件日志，能判断有没有真触发。

配套：

- `assets/prompt_zh.txt` 补了 instruct 写法，**明令禁止** `[laughter]` 一类方括号标签和往 text 里补「哈哈哈」「呜……」；
- 设置页「哭声、笑声、叹气」开关（默认开）；
- 设置页音频调试新增「试哭笑叹气」按钮，一句话里同时含笑、叹气、哭腔，**不跑整本就能直接听效果**；
- 诊断摘要加「拟声表达 开/关」。

### 诚实的期望管理

- 得到的更可能是**语气贴近**（颤、气声、停顿、句尾下沉），不是「凭空多出一段独立的哭录音」。
- 命中与否、听不听得出来，**取决于权重和具体文本**，沙盒里无法验证，必须真机试听。
- 若试下来觉得不够，二期才考虑：Alexandria 拟声词（需用户接受成品多出人声）或换 CosyVoice3（需重做引擎）。

---

## 4. 参考来源

- Qwen3-TTS 官方博客（哭腔示例、VoiceDesign）[citation:10]
- Qwen-Audio-3.0-TTS 技术解读（86 标签、12.5Hz、3 分钟长文本）[citation:5][citation:14]
- Voicebox（Qwen3-TTS 把标签当文字念；Chatterbox Turbo 的 `[laugh] [sigh]`；后处理）[citation:6]
- Alexandria 官方文档（non-verbal sounds、脚本结构、Qwen3-TTS 本地引擎）[citation:8][citation:12][citation:13]
- CosyVoice3 副语言标签指南 [citation:5]、Soniqo 情绪标签 [citation:9]、漫剧 WebUI 实践 [citation:7]
- VoiceStudio 引擎对照（pause、参考音频即表演、哪些标签哪引擎才吃）[citation:11]
- Polyphon Audio（本地多角色 + 音效生成，桌面端）[citation:15]
- CSDN Qwen3-TTS 语音导演教程（括号指令、标点节奏）[citation:16]
