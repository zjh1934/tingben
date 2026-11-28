## 听本 1.1.69

本地离线把小说变成能听的音频 · 端侧 TTS · 网络能力默认关闭（仅用于你手动开启的网关 / 跨网中继）· 模型后导入。

### 本版更新

### R416 · 维护轮：出包 + GitHub 页面 + aoci 索引

用户一句「出包维护GitHub维护oico」，本地自主顺着代码与线上页面过一遍，查到 3 类真问题一起修。

**A · 异常路径把断点清成 0/0（真值断链）**

R414/R415 立了「一个状态只能有一个真值源，每帧都从它取」，但只覆盖了正常启动与续跑。两处异常路径仍硬写 0：

- `run()` 的 catch：`Progress(false, "出错了", 0, 0, 0, …)` → 下一轮 `start()` 的 `prior` 是 0/0，开局又归零；`finally` 还拿这个 0 去写 `FGS_FINISH`。
- `abortBeforeStart()`（R175 防自动续跑循环）：`_state` **和** `TaskResume` 档案都写 `done=0,total=0` 并落盘。续跑时模型临时缺件 / 没可制作文字，会把已做好的 203/26678 从档案里抹掉。

改成与 `halted()` 同源：一律从 `_state.value` 带出 `done/total`。`phase="blocked"` 与 stage 逻辑一字不动，R175 的断链循环照旧防住；**只留真值，不改行为**。

**B · 文档基线自相矛盾**

`STATUS.md` 顶部写「用户真机基线 `1.1.63`」，表格却写 `1.1.57`；`ROADMAP.md` 也停在 1.1.63。而 11-28 11:42:09 诊断日志「环境」块实证是 **1.1.66**。三处统一到 1.1.66，并注明 1.1.67 / 1.1.68 已发但尚无真机回执、不擅自改基线。

**C · 调用页 busy 不显示重试秒数**

`busyStatus()` 一直写 `retry_after_ms`，私有中继仓 README 也写了，但 `docs/pages/relay.html` 只说「稍候」。改成显示约多少秒后自动补做，并把三处 Pages 用 `sync_pages.py` 同步推准。

**核实后排除，不硬找**：`docs/使用指南.md` 用 Contents API 读到 84 字是解析层截断，Blobs API 实读 4398 字节 / 1940 字、blob sha 与本地完全一致；Pages 三处 `--check` 原本就 0 差异；busy 状态确实同时带 `reason` 与 `retry_after_ms`。

**维护**：新增 `r416_verify.py` 挂现行清单与 CI 正反向（纯内存复刻旧 0/0 必须能复现、STATUS 顶部与表格同源、busy 秒数、版本四处对齐、零权限、功能守恒）；aoci 新增 1 条 Entry、补 6 段认知、刷新基线；`VERSION` / README / STATUS / ROADMAP / CHANGELOG / 发版说明一起对齐 1.1.69。

### 升级

固定签名，直接覆盖安装即可；已导入的模型与做好的音频都保留。**无需额外操作**。

### 下载

- 本版安装包：https://github.com/zjh1934/tingben/releases/download/v1.1.69/tingben-v1.1.69.apk
- 镜像直链：https://cdn.jsdelivr.net/gh/zjh1934/ting-ben-apk@main/tingben-v1.1.69.apk
- 兼容旧直链（同内容，仅给没刷新的老链接兜底）：https://cdn.jsdelivr.net/gh/zjh1934/ting-ben-apk@main/app-debug.apk

完整记录：[CHANGELOG.md](https://github.com/zjh1934/tingben/blob/main/CHANGELOG.md) · 使用说明：[使用指南](https://github.com/zjh1934/tingben/blob/main/docs/使用指南.md)
