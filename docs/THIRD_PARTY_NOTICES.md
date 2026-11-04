# 第三方代码声明

听本的部分实现来自以下开源项目，按各自许可证保留版权声明。均为**移植改写**，不是原样复制：
算法思路照搬，语言/JSON 库/字符集/契约按 Android 端侧场景改过。

## 1. Alexandria / novel-voice-studio

- 来源：`reference/精选/alexandria/generate_script.py`
- 用途：`app/src/main/java/com/tingben/app/core/JsonSalvage.kt`
  （`clean_json_string` / `repair_json_array` / `salvage_json_entries`）
- 用途：`app/src/main/java/com/tingben/app/diag/RawDump.kt`
  （`llm_responses.log` 逐块原始产出全文落盘）
- 许可证：MIT License
- Copyright (c) 2026 Finrandojin

> MIT License
>
> Permission is hereby granted, free of charge, to any person obtaining a copy
> of this software and associated documentation files (the "Software"), to deal
> in the Software without restriction, including without limitation the rights
> to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all
> copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
> FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
> AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
> LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
> SOFTWARE.

## 2. Antioch

- 来源：`reference/精选/antioch/annotator.rs`
- 用途：`app/src/main/java/com/tingben/app/core/Adoption.kt`
  （`annotate` / `generate_instructs` 的 echo 检测、标签剥离、首句截断、去重、sensible defaults）
- 许可证：MIT License
- Copyright (c) 2026 Xydane

> MIT License
>
> Permission is hereby granted, free of charge, to any person obtaining a copy
> of this software and associated documentation files (the "Software"), to deal
> in the Software without restriction, including without limitation the rights
> to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all
> copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
> FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
> AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
> LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
> SOFTWARE.

## 未采用但调研过的现成方案（登记，避免下轮又去搜）

- **llama.cpp GBNF / JSON Schema 约束输出**：官方支持 `llama_sampler_init_grammar`，
  能强制 JSON 语法壳，但不保证逐字回显原文，字符级 gate 仍不可替代。
  接入需动 native 与生成循环，按路线图排在后面单独一轮。
- **json_repair (mangiucugna, Python)**：功能与 Alexandria 的 repair 高度重叠，
  但我们是 Kotlin、且不能引 Python 运行时，故不采用；其「截断补闭合」思路已被 Alexandria 版覆盖。
- **Antioch 的确定性结构切分**（`split_dialogue_paragraph`）：会改写/剥离归属语，
  与听本当前「原文一字不改」契约冲突，只采纳其清洗与容错，不采纳其切分。
