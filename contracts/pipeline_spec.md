# 流水线规格 · 四层移植后的可实现版本

> 来源：Alexandria（标注契约）+ Antioch（引擎组合/断点/合并）+ ReaderVoice（不变量）+ OfflineLLM（Android 底座）。
> 这是**加工后的产物**，不是原文摘录。Kotlin 实现逐条对齐这里的编号。

## A. 分块（Alexandria `split_into_chunks` / Antioch `chunker.rs`）
- A1 先按 `\n\n` 分段，段内按句末标点断句；章节标题单独成条（`@@CHAPTER:` 概念，但不改原文，只记偏移）。
- A2 单块上限 **500 字**，在段落/句子边界切，不切半个词。
- A3 每块记 `[start,end)` 字符偏移；偏移必须能在原文精确回取。

## B. 标注（Alexandria `generate_script.py`）
- B1 prompt 见 `prompt_zh.txt`，双段结构 + `---SEPARATOR---`。
- B2 **跨块上下文注入**：角色表（去重、全书累积）+ 最近 3 条标注，放在 user prompt 头部，不进正文。
- B3 **JSON 修复四级**（抄 Alexandria `repair_json_array`）：直接 parse → 补 `},{` → 正则捞单条 → 截到最后一个完整 `},` 补 `]`。
- B4 **字符级 gate（我们加的，Alexandria 没有）**：把结果里所有 `text` 顺序拼接，必须 `== 本块原文`。任何不等 → 整块重试 1 次 → 仍不过 → **降级直读**：原文整块交给 TTS，speaker=旁白，instruct=平稳叙述，绝不静音、绝不跳过。
- B5 复核 pass 默认不开（Alexandria 的 review 会改写文本，撞红线）。

## C. 合成（Antioch `tts/cpp.rs`）
- C1 两个模型**串行**：标注阶段只加载 LLM，合成阶段先 `llmFree` 再 `ttsLoad`，不同时常驻。
- C2 每块一次 `ttsSynthesize`，产出 24kHz 单声道 PCM；**逐块写 `chunk_NNNN.wav`**，写完立即落盘。
- C3 块间按 `instruct` 里的停顿提示插入静音（默认 250ms，句末 400ms）。
- C4 **断点**：`checkpoint.json` 记已完成块索引；重跑时跳过已有 WAV（抄 Antioch `.antioch_/` 语义）。

## D. 合并与导出（Antioch `merger.rs`）
- D1 按块序号读 WAV，跳过 44 字节头，拼接 PCM，重写一个 WAV 头（24kHz / mono / 16-bit）。
- D2 首版**只出 WAV**；M4A/章节 M4B 是二期（MediaCodec 能力需真机查询）。
- D3 章节信息另写 `chapters.json`（起止样本数），不伪造内嵌轨。

## E. 不变量（ReaderVoice `AGENTS.md`）
- E1 原文永不修改，只建 source map。
- E2 UNKNOWN 是合法结果，禁止强造角色名。
- E3 播放窗口优先于未来生成（首版简化为：先做完当前章再进下一章）。
- E4 「能跑」不等于「通过」：最终必须有可听 PCM（finite / 非全零 / 时长 > 0）。
- E5 进程被杀只丢当前批，已落盘块不重算。

## F. Android 底座（OfflineLLM）
- F1 无 `INTERNET`、仅 `arm64-v8a`、`largeHeap`、SAF 导入任意 `.gguf`、JNI 直连 llama.cpp。
- F2 版本组合采用**旧项目已在 CI 与真机验证过的一套**（AGP 8.x / Gradle 8.9 / JDK 17 / compileSdk 35 / NDK r28），不追 OfflineLLM 的 AGP 9.5 新组合，避免无本地编译环境时踩坑。
- F3 两个 ggml 只保留一份（llama.cpp 版），`libqwen.so` 共用；CI 做 UND 符号差集自检——这条是旧项目真机 `dlopen failed` 换来的，必须带走。

## G. 界面（新写，不抄任何一屏）
- G1 首版 5 页：书架 / 制作 / 进度 / 我的音频 / 设置。
- G2 用户第一次听到声音前，不出现任何 `.gguf` 文件名、SHA、token 数、调试页。
- G3 一屏一个主按钮；做完前不摆结果区。
- G4 日志 L0 人话 / L3 两步确认脱敏。

## 许可证
Alexandria MIT、Antioch MIT、OfflineLLM Apache-2.0：移植改编需保留版权声明，见 `reference/精选/*/LICENSE*`。
