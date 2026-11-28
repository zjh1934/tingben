## 听本 1.1.68

本地离线把小说变成能听的音频 · 端侧 TTS · 网络能力默认关闭（仅用于你手动开启的网关 / 跨网中继）· 模型后导入。

### 本版更新

### R415 · R414 自己的漏网：开局占位帧仍归零

1.1.67 发完后顺着代码过链路，发现 R414 只修了三帧里的两帧：

- `11:39:35.472 done=0 total=0`（FGS_READY）→ R414 已修（`start()` 带 seed）
- **`11:39:45.668 done=0 total=26678` → 漏网**：`run()` 第 741 行 `emit("正在生成声音", 0, annotated.size, …)` 硬写 0 且不带逐章进度，`total>0` 所以 R414 的「占位帧不画」闸门不拦它。用户眼里仍是「点继续 → 归零 → 9 秒后补回」。
- `11:39:54.511 done=203 bottom=0` → R414 已修（R413 扫盘口径）

**为什么不把 R413 扫盘提前**：`VoiceCache.switchTo()` 会搬动散段文件（真机日志「散段认领 27 份」），扫盘必须在它之后读盘，提前会让 `pending` / 认领份数算错。这是真风险，不碰。改成让 741 占位帧自己从 `start()` 留下的恢复态带真值。

**改动**：
- 741 行改成 `seedDone = _state.value.done.clamp(0, total)`，并传 `chapterProgress = seedState.chapterProgress`；三帧口径一致，开局一路是「已续上 N/M 段」。
- `emit` 空表不再装作「这章 0%」，恢复 `-1f` 单球哨兵；`OverlayProgress` 用**原始** `rawPct` 判断下球可见性、`coerceIn` 只用于画与记日志，日志补 `rings=1/2` 让诊断能分辨单球与双球。顺带修掉 R414 clamp 引入的回归：片段合成 / 调音台预览凭空多画一个 0% 下球。
- `r414_verify` 扩成 34/34 反向注入：纯内存复刻「741 硬 0」必须能复现、4 次启动 fixture 里 `total>0` 的开局帧 `done` 必须等于恢复值；`rings` 标识、单球哨兵、seedDone 全有反向对照。
- 5 个历史判据按新形态改准、语义不放宽：`r161` / `r214` / `r217` / `r345`；两个锚点精确化到「下球判断整串」，避免 `rings` 同字符串造成假绿（这轮真抓出 2 个漏放，已修）。

### 升级

固定签名，直接覆盖安装即可；已导入的模型与做好的音频都保留。**无需额外操作**。

### 下载

- 本版安装包：https://github.com/zjh1934/tingben/releases/download/v1.1.68/tingben-v1.1.68.apk
- 镜像直链：https://cdn.jsdelivr.net/gh/zjh1934/ting-ben-apk@main/tingben-v1.1.68.apk
- 兼容旧直链（同内容，仅给没刷新的老链接兜底）：https://cdn.jsdelivr.net/gh/zjh1934/ting-ben-apk@main/app-debug.apk

完整记录：[CHANGELOG.md](https://github.com/zjh1934/tingben/blob/main/CHANGELOG.md) · 使用说明：[使用指南](https://github.com/zjh1934/tingben/blob/main/docs/使用指南.md)
