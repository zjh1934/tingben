# 流水线规格 · 现行直接朗读版

> 现行加工链，不是历史移植清单。只有一条路径：原文整段送声音模型。
> 字段边界以 `app/src/main/cpp/qwen.h` 为准，文件结构见 `contracts/model-manifest.json`。

## A · 分块

- A1 章标题正则识别成章；章内按字符累加，在句末标点 / 换行处切块，上限 500 字（优先 500 / 350 字后的句子边界）。
- A2 每块记 `[start,end)` 字符偏移，必须能在原文精确回取。**正文一个字不改。**
- A3 块归属到章（`segChapter`），逐章进度只统计落盘成功的段。

## B · 模型槽位

- 共用底座 `qwen-tokenizer-12hz-Q8_0.gguf` 一份，三模式共用。
- 当前启用的一份 talker 主文件；`ModelStore.pickFor()` 按 `-voicedesign-` / `-customvoice-` / `-base-` 独有关键词精确识别。
- `missing()` / `ready()` 口径 = 「底座 + 至少 1 份模式主文件都在」。

## C · 合成请求

```
NativeEngine.ttsSynthesize(
  text    = 块原文（一字不动）,
  instruct= 身份描述 + 风格 / 附加语气 / 拟声 拼成的一条字符串,
  speaker = 当前 voice_design 下恒 null,
  lang    = "chinese",
  knobs   = 9 项采样数值
)
```
- `instruct` voice_design 必填 / custom_voice 可选 / base 拒绝。
- `speaker` 只有 custom_voice 可用；`ref_audio*` 只有 base 可用且与 `speaker` 互斥。
- 9 项采样：`seed / max_new_tokens / temperature / top_k / top_p / repetition_penalty /
  subtalker_temperature / subtalker_top_k / subtalker_top_p`，JNI 逐字段覆盖，默认值见 `sampling-defaults.h`。
- 当前只有 voice_design 按上述实现接通；另两种模式选中即被 `synthesisBlockReason()` 拦住并给一键切回。

## D · 落盘与合并

- 逐块 WAV 落盘，断点续传；失败段不计入完成数，沿用上一帧快照。
- 合并按块顺序，成品 WAV 与逐段文件分开管理；清理只删制作过程的分段 WAV，不碰原书与模型。

## E · 状态与兼容

- 声音指纹按「音色描述 + 采样 + instruct 模板」判旧 WAV 是否作废；`legacyVoiceStamp` 双指纹比对保证升级不误删。
- **当前启用模式不进声音指纹**，切模式不作废已合成音频。
- 任务书签、阅读位置（章索引）各自独立文件；旧书 / 旧 WAV / 旧任务记录保持可读。
- 制作运行期间禁止切模式。

## F · 不做

逐段改写 / 角色切分 / 逐段 `{speaker, instruct}`、联网下载模型、PDF / OCR / DRM、
M4A/m4b 章节轨（首版只 WAV）、放宽逐字契约。
