# 参考源码精选 · 四层移植的来源

> 这些是**读来移植的原料**，不是本项目的代码。Kotlin 实现见 `app/src/main/java/`。
> 许可证：Alexandria MIT、Antioch MIT、OfflineLLM Apache-2.0；改编需保留版权声明。

| 目录 | 来源 | 我们拿走的 |
|---|---|---|
| `alexandria/` | Finrandojin/alexandria-audiobook (MIT, PC Python) | `generate_script.py` 的 500 字分块、角色表 + 最近 3 条上下文、JSON 四级修复；`default_prompts.txt` 的双段 `---SEPARATOR---` 结构 |
| `antioch/` | Xydane/antioch-audiobook (MIT, Rust CLI) | `chunker.rs` 结构标题、`merger.rs` WAV 拼接、`tts/cpp.rs` 逐块落盘与断点语义 |
| `offlinellm/` | jegly/OfflineLLM (Apache-2.0, Android) | Manifest 无 INTERNET、SAF 导入 GGUF、版本目录与 JNI 生命周期 |
| `readervoice/` | Nian27/ReaderVoice-Mobile (Android) | `AGENTS.md` 五条不变量：原文不可改、UNKNOWN 合法、播放优先、PCM Gate、断点只丢当前批 |
| `qwentts/` | ServeurpersoCom/qwentts.cpp | `qwen.h` 的 `extern "C" qt_*` API、`ARCHITECTURE.md` 的 24kHz 单声道 PCM 约定 |
| `ebook2audiobook/` | DrewThomasson/ebook2audiobook | 章节/格式处理参考。⚠️ 本包未收录源码（该项目结构差异大，首版不用），留作二期调研 |

**加工产物**（不是摘录）：`contracts/prompt_zh.txt`、`contracts/pipeline_spec.md`。
**没整包 fork 的原因**：四个项目分处 PC Python / Rust CLI / Android 聊天 App / Android 阅读器，没有一条能直接在 8Gen3 上吃这三个 GGUF 跑完整链。
