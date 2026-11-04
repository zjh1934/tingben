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

## 3. ensemble-reads / SegmentMerger

- 来源：`reference/播放与看台现成源码/ensemble-reads__app__src__main__java__com__ensemblereads__app__player__SegmentMerger.kt`
- 用途：`app/src/main/java/com/tingben/app/core/TextSplitter.kt`
  （相邻单元合并到目标长度、**超长单段保留原样不切碎语义**、`MAX_SEG_CHARS` 常量思路）
- 许可证：Apache-2.0（见上游仓库）
- 说明：算法思路与常量移植，实现为 Kotlin 重写，未复制源文件。

## 4. ReaderVoice-Mobile / SemanticSegmenter

- 来源：`reference/ReaderVoice-Mobile/data-room/src/main/kotlin/com/readervoice/data/semantic/SemanticSegmenter.kt`
- 用途：`app/src/main/java/com/tingben/app/core/TextSplitter.kt`
  （引号栈、source-exact 区间切片：拼回去逐字无损，不在引号中间断）
- 许可证：未核实（上游仓库未随精选包提供 LICENSE；此处只借鉴算法思路，未复制源码文件）
- 说明：若后续要引入其源码文件，先回上游仓库核实许可证再补本节。

## 5. Antioch / chunker.rs（断句部分）

- 来源：`reference/antioch-audiobook/src/script/chunker.rs`
- 用途：`app/src/main/java/com/tingben/app/core/TextSplitter.kt`
  （`split_into_segments` 先段落再句子、`is_heading_like` 短标题单独成段、
  `split_sentences` 的边界判据；中文标点集与引号栈为本地适配）
- 许可证：MIT License，Copyright (c) 2026 Xydane（同第 2 节全文）

## 未采用但调研过的现成方案（登记，避免下轮又去搜）

- **llama.cpp GBNF / JSON Schema 约束输出**：官方支持 `llama_sampler_init_grammar`，
  能强制 JSON 语法壳，但不保证逐字回显原文，字符级 gate 仍不可替代。
  接入需动 native 与生成循环，按路线图排在后面单独一轮。
- **json_repair (mangiucugna, Python)**：功能与 Alexandria 的 repair 高度重叠，
  但我们是 Kotlin、且不能引 Python 运行时，故不采用；其「截断补闭合」思路已被 Alexandria 版覆盖。
- **Antioch 的确定性结构切分**（`split_dialogue_paragraph`）：会改写/剥离归属语，
  与听本当前「原文一字不改」契约冲突，只采纳其清洗与容错，不采纳其切分。
