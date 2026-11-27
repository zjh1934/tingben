## 听本 1.1.65

本地离线把小说变成能听的音频 · 端侧 TTS · 网络能力默认关闭（仅用于你手动开启的网关 / 跨网中继）· 模型后导入。

### 本版更新

### R412 · 电量低于 20% 只拦电子书制作

用户要的是一条很窄的规矩：**手机电量低于 20% 就拒绝本地电子书的合成请求；远端中继、语音网关、调音台试听、首页粘贴文字合成一律不拒绝。**

**落点**：新增 `core/BatteryGate.kt`，用 `ACTION_BATTERY_CHANGED` 的 sticky 广播读电量（`registerReceiver(null, filter)`），**零新增权限、零新增依赖**；判定严格 `pct < 20`，20% 本身放行；读不到电池（模拟器 / 无电池设备）一律放行，不误伤。日志只记 `pct / plugged / status`，不落书名、正文与任何标识。

**四个入口都拦**：`Producer.enqueueOrStart`（目录页开始制作 / 制作选中章节 / 两处单章与选中段重做）、`resumeQueue`（继续队列）、`autoResume`（冷启恢复弹窗点「继续制作」）、以及唯一的兜底 `start()`——所有电子书制作路径最终都过它，队列泵 `pumpQueue` 的自动续跑也被这一道挡住。拦在 `busy = true` **之前**：不入队、不写 `TaskResume.save`、不启前台服务、不碰已做段；低电时排队项被放回并暂停，电够了点「继续队列」即可，断点数据一条不丢。

**不拦的**：`relay/`、`gateway/`、`core/ClipSynth.kt`（首页单段与试听共用）、`InputReadUi` / `MixerUi` / 播放器。守门用「禁止文件清单」逐份扫，这些文件里出现 `BatteryGate` 直接判红。

**UI 不静默**：续跑（`MainActivity.resumeTask`）、队列恢复（`TaskUi`）、两处单章重做（`ChapterHub`）原本会丢掉拒绝原因、甚至弹「已排重做」的假成功提示，现在都给明确的一句「电量 N%（低于 20%），电子书制作先等等；充到 20% 以上再点。远端请求、试听和单段合成不受影响」。

**两处自己定的边界，写清楚**：① 按字面严格 **<20 拦，不看是否在充电**（用户没给充电豁免，不擅自放宽；`plugged` 只进日志，将来一句话就能加）；② **只拦新发起的请求，已经跑起来的整本制作不打断**——中途停会破坏断点续跑与书的推进节奏，这是用户反复强调过的。这两条都登记在 STATUS 欠账里，要改一句话即可。

**守门**：新增 `r412_verify.py`，挂现行清单与 CI 正反向——验阈值、严格小于、读不到放行、四闸门齐全、`start` 兜底在 `busy=true` 前、主循环零渗透、其余链路零误伤、零权限零依赖、4 处 UI 反馈、90 个 Kotlin 文件按钮/回调基线零差异、链路符号与欠账登记。

### 升级

固定签名，直接覆盖安装即可；已导入的模型与做好的音频都保留。**无需额外操作**。

### 下载

- 本版安装包：https://github.com/zjh1934/tingben/releases/download/v1.1.65/tingben-v1.1.65.apk
- 镜像直链：https://cdn.jsdelivr.net/gh/zjh1934/ting-ben-apk@main/tingben-v1.1.65.apk
- 兼容旧直链（同内容，仅给没刷新的老链接兜底）：https://cdn.jsdelivr.net/gh/zjh1934/ting-ben-apk@main/app-debug.apk

完整记录：[CHANGELOG.md](https://github.com/zjh1934/tingben/blob/main/CHANGELOG.md) · 使用说明：[使用指南](https://github.com/zjh1934/tingben/blob/main/docs/使用指南.md)
