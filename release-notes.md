## 听本 1.1.67

本地离线把小说变成能听的音频 · 端侧 TTS · 网络能力默认关闭（仅用于你手动开启的网关 / 跨网中继）· 模型后导入。

### 本版更新

### R414 · 1.1.66 真机日志综合修复

来源 `tingben_diag_20261128_114207.md`（3919 行）。6 条真问题归成 3 个根因一起收口，小本本见 `听本_日志问题小本本_R414.md`。

**A 开局没有单一真值源**
- 4/4 次启动 `OVERLAY_PCT bottom=-100`（共 22 帧）：`emit` 的 -1f 哨兵原样传进日志。`emit` 下球改取「最靠前未完成章」，空表给 0f / 全完成给 1f；`OverlayProgress` 再 clamp 一遍并禁止 `total<=0` 写日志，双保险。
- 点「继续」后 `203→0/0`、19 秒才补回：`start()` 从恢复态带出 `seedDone/seedTotal/chapterProgress`，`ProduceService.begin` 透传，开局通知与浮窗直接是「已续上 203/26678」。
- `OPEN_BOOK |  · book=…` 空 detail 前导分隔符：`EventLog.log` 先 join 再决定加不加前缀，非空 detail 输出逐字不变。

**B 章进度两种口径混算（R413 回归，日志实证，认）**
- `11:39:54 bottom=0 done=203` → `11:40:43 bottom=100 done=204`，而第 4 章实际约 81%。根因是 `restoredChapter` 生成全表 338 章、`emit` 取章号最大章；`perChapterDone` 又把章音频覆盖的段灌进增量。
- 修：`sealedChapters` 隔离已固化章（基线直接 1f、不进增量计数），`emit` 与 UI 共用「当前章 = 最小未完成章」一个定义。旧口径的 `bottom=0`、`0→100` 都做成 fixture 反向对照。

**C 长耗时阶段没有心跳契约**
- 章转码 3 次各 4~5 分钟零日志：`AudioOps.encodeChapter` 加 `CHAPTER_ENCODE_BEGIN` / 每 30s `CHAPTER_ENCODE_PULSE` / `CHAPTER_ENCODE_END`，借 `stopCheck` 回调节流、不动 MediaCodec 主链、不动 done/total。

**核实后排除不硬找**：`UPDATE_CHECK v1.1.64`（查时 1.1.65 还没发）、26678 段整本（删书后主动重导）、APP_EXIT 去重、RELAY 退避、60 条远端闭环、存档 100 条上限、MIXER 渠道标签、让路心跳回落、停止丢弃当前段。

**守门**：新增 `r414_verify.py` 挂现行清单与 CI 正反向——4 次启动开局帧 fixture、纯内存复刻新旧两种口径（旧 bug 必须能复现）、零权限、功能守恒。6 个历史判据 `r139/r214/r217/r231/r369/r400` 按新形态改准、语义一条没放宽。

### 升级

固定签名，直接覆盖安装即可；已导入的模型与做好的音频都保留。**无需额外操作**。

### 下载

- 本版安装包：https://github.com/zjh1934/tingben/releases/download/v1.1.67/tingben-v1.1.67.apk
- 镜像直链：https://cdn.jsdelivr.net/gh/zjh1934/ting-ben-apk@main/tingben-v1.1.67.apk
- 兼容旧直链（同内容，仅给没刷新的老链接兜底）：https://cdn.jsdelivr.net/gh/zjh1934/ting-ben-apk@main/app-debug.apk

完整记录：[CHANGELOG.md](https://github.com/zjh1934/tingben/blob/main/CHANGELOG.md) · 使用说明：[使用指南](https://github.com/zjh1934/tingben/blob/main/docs/使用指南.md)
