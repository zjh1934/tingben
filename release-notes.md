## 听本 1.1.66

本地离线把小说变成能听的音频 · 端侧 TTS · 网络能力默认关闭（仅用于你手动开启的网关 / 跨网中继）· 模型后导入。

### 本版更新

### R413 · 续跑转圈跟真实章走 + 低电保护能关了

**① 转圈定位（真机回执）**：用户把软件关掉或更新后再续跑，第一章明明已经合成完、拼接完、转码完，转圈却还钉在第一章第一个灰色音频小块上；第二章其实已经做了一半（点开看到绿灰相间），但完全看不出有没有在合成。

根因是 `chapterProgress`（逐章进度）**只是内存态**：`Producer.start()` 开跑时把它重置成空表，续跑开局那条 `emit` 也没传；`ChapterHub.makingChapter` 遍历空表就兜底到第 0 章 → 展开第一章 → `segBusyIndex` 把转圈画在第一章第一个没音频的格子上。已固化章里残留的 redo 标记还会再画一个。

**三处一起修**：
- `Producer` 续跑开局在算 `pending` 的同一次扫盘里按 `segChapter` 归出「逐章已完成段数」，作为 `emit` 的初始 `chapterProgress`，并作为 `perChapterDone` 的起点——后续 `chapterSnapshot` 不会把已完成章从表里抹掉；旧 `count{}` 改显式 `for`，判 pending 的语义一字未改。
- `ChapterHub.makingChapter` 改成**磁盘真值优先**：已经拼成章音频的章一律算 1f（完成），内存态只用来分辨没固化章里谁做了多少；`remember` 加 `ui` 依赖，磁盘快照一刷新就重算。
- `chapterSegs` 与 `segBusyIndex` 各加一道 `chapterDone`：已固化章既不认残留 redo 转圈，也不画 busyIndex 转圈，格子只作留档显示。

进度卡另给一句明账 **「正在制作第 N 章 · 已做 X/Y 段」**，不用展开格子就知道现在在哪一章。

**② 低电保护加了开关**：放在 **设置 → 运行与通知**，和「打开软件自动续跑」同组（都是「软件在后台怎么跑」的行为，不塞进主题 / 调音台 / 权限）。开关叫「低电量保护」，副标题写清「低于 20% 暂缓电子书制作 · 远端 / 试听 / 单段不受影响」。**默认开**，沿用 R412 你要的行为；关掉后 `BatteryGate` 连电池都不读，零开销放行。仍严格 `<20`、不看充电、只拦新发起的电子书请求。

**守门**：新增 `r413_verify.py` 挂现行清单与 CI 正反向——验续跑逐章基线、磁盘真值优先、已固化章零转圈、进度卡明账、开关默认开/落盘/尊重/入口、零权限、功能守恒；纯内存逻辑复刻「第一章固化 + 空表」必须落到第二章，并把 R412 的空表兜底 bug 复现出来做对照。7 个历史判据（`r95` / `r150` / `r151` / `r219` / `r233` / `r235` / `r362`）按新形态改准，语义一条没放宽。

### 升级

固定签名，直接覆盖安装即可；已导入的模型与做好的音频都保留。**无需额外操作**。

### 下载

- 本版安装包：https://github.com/zjh1934/tingben/releases/download/v1.1.66/tingben-v1.1.66.apk
- 镜像直链：https://cdn.jsdelivr.net/gh/zjh1934/ting-ben-apk@main/tingben-v1.1.66.apk
- 兼容旧直链（同内容，仅给没刷新的老链接兜底）：https://cdn.jsdelivr.net/gh/zjh1934/ting-ben-apk@main/app-debug.apk

完整记录：[CHANGELOG.md](https://github.com/zjh1934/tingben/blob/main/CHANGELOG.md) · 使用说明：[使用指南](https://github.com/zjh1934/tingben/blob/main/docs/使用指南.md)
