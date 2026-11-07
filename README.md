# 听本

> 本地离线把一本小说变成能听的音频。App 不联网，模型文件由用户自己导入、不进安装包。

**当前状态 → `STATUS.md`** · **后续队列 → `ROADMAP.md`（唯一真源）** · 现行依据 → `docs/`

## 安装

```
https://raw.githubusercontent.com/zjh1934/ting-ben-apk/main/app-debug.apk
```
备用 Release 页：`https://github.com/zjh1934/tingben/releases/latest`

- 固定地址**永远是最新版**；当前 `1.1.0+113`（R115 给模式卡留出 12sp 三行高度；R114 收了字号阶梯、制作准备页去折叠、音频焦点让位与系统栏反色），字节数 / blob / 验证结果见 `STATUS.md`
- 源码仓 `zjh1934/tingben`、APK 仓 `zjh1934/ting-ben-apk`（旧名自动跳转）
- 包名 `com.tingben.app`，固定签名，**新版直接覆盖装**，模型与已做好的音频都保留
- 平台解析器读不了 GB 级二进制会报「资源解析服务请求失败」——不是坏链，用浏览器打开

## 模型文件（后导入，不进包）

**1 份共用底座 + 3 份模式主文件**，各自单独点导入、单选启用：

| 文件 | 角色 |
|---|---|
| `qwen-tokenizer-12hz-Q8_0.gguf` | 共用底座，三种模式都要用，导入一次就够 |
| `qwen-talker-1.7b-voicedesign-Q8_0.gguf` | 自然语言描述造音色，`instruct` 必填 |
| `qwen-talker-1.7b-customvoice-Q8_0.gguf` | 预设音色编号 + 语气指令 |
| `qwen-talker-1.7b-base-Q8_0.gguf` | 一小段参考音频克隆 |

上游仓库 `Serveurperso/Qwen3-TTS-GGUF`（国内镜像把域名换成 `hf-mirror.com` 即可）。
`1.1.0+80` 真机截图显示四份均已导入。自 `1.1.0+87` 起三种模式都能合成：描述造音色用一段文字，
预设音色从模型自带的音色表里挑一个，参考音频导入一小段人声 WAV（App 内部下混、重采样成 24kHz 单声道）。
每种模式只画它真实存在的输入，模式由引擎从 GGUF 读出，不靠界面开关决定。
模型能调什么以随包 `app/src/main/cpp/qwen.h` 为准，详见 `docs/01_音频模型调教逻辑.md`。

## 用起来

1. ☰（或从左边缘右滑）→「模型」→ 导入共用底座 + 至少 1 份模式主文件
2. 右上角「＋」导入 txt / md / epub，或在书架粘贴文字建书；也支持别的 App 里「打开方式 → 听本」
3. 点书进**三列横滑目录**（章节 / 合成 / 可播）→ 勾选章 → 底部「调音 ›」调声音（**在点合成之前**）→「合成音频」
4. 进度页看四步与分段看台 →「任务」管长任务 →「我的音频」试听 / 保存

制作开始后有常驻通知，前台服务按系统版本选类型（Android 14+ `mediaProcessing`、低版本 `dataSync`）+ 按块唤醒锁承载。**不保证不被杀**：省电策略、内存紧张、系统对前台服务的时长约束都可能暂停；回来在「任务」页点「继续制作」，已做好的块不重算。

## 页面

- **设置页**：只管软件 —— 主题三档 / 正文字号 / 本机数据 / 诊断与试音 / 后台与关于
- **进度页**：顶部一个纯图标 ⏸/▶ + 细进度条；四步横排小色块；分段看台常驻 8 格，点一下读这一段、再点一下停（格子本身就是开关，没有独立播放键，也不展开正文）
- **目录页**：点章名看原文，成品从软件内悬浮条播放，不做独立播放器页
- **调音台**：`MixerDraft` 唯一编辑态 + `commitAll()` 唯一生效出口，提交不需要先试听；三张模式卡并列点即切，9 项采样真到 `qt_tts_params`，但**只在「描述造音色」铺开**，另两种模式只留一行「沿用全局设置」
- **制作准备页**：三个真实分区常驻、不折叠；字号统一在 `12/13/14/15/16/18/20 sp` 阶梯内
- **播放让位**：来电话、别的 App 出声、拔耳机都会暂停；深浅色切换时状态栏与导航栏图标一起反色

导航：无底栏，☰ / 左边缘右滑开左侧抽屉（**6 个目的地**：一级 4 项含调音台 + 更多 2 项），展开时右侧工作页一起推走。

## 硬约束

- 无 `INTERNET` 权限、仅 `arm64-v8a`、APK < 100MB、模型后导入不进包
- **正文一个字不改**：直接朗读，不做逐段改写 / 角色切分
- 首版只导出 WAV；导入 txt / md / epub（epub 按 OPF spine 分章、编码自适应），PDF / OCR / DRM 不做
- 书架只放原书 · 任务页只放制作记录 · 我的音频只放成品，三层互不增长
- 长任务逐块落盘，退出或被杀不丢进度
- 不申请无障碍、不弹浮窗、不模拟点击、不自动续跑伪装

## 现行源码地图

```
app/src/main/java/com/tingben/app/
  MainActivity.kt          页面主体与编排
  AppChrome.kt            全局壳：顶栏 / 左侧抽屉 / 下拉刷新 / 状态栏安全区
  ChapterHub.kt           点书后的三列目录页
  Theme.kt                两套色板 + 主题状态 + 系统栏图标反色
  ProduceService.kt       前台服务（按系统版本选类型）、进度通知、按块唤醒锁
  ProduceOptions.kt       本次制作的可选项（三个常驻分区）
  core/Pipeline.kt        书架、模型槽位、分块、直接朗读、WAV 合并、任务书签
  core/Mixer*.kt / ParamSpec.kt / VoiceProfile.kt   调音台快照 / 编辑态 / 9 项官方元数据 / 音色描述
  core/AudioLibrary.kt / PlayQueue.kt / ReadingState.kt / TaskResume.kt
  core/AtomicJson.kt      四类 JSON 记录的统一原子落盘与安全读取
  core/Prefs.kt / ProduceConfig.kt / TextSplitter.kt / SegPlan.kt / Workbench.kt / NonVerbal.kt
  engine/NativeEngine.kt  JNI 声明
  diag/EventLog.kt        事件日志
app/src/main/cpp/         tingben_native.cpp + CMakeLists + qwen.h / llama.h
contracts/                模型清单、流水线规格、采样默认值
scripts/                  CI 自检与验证脚本、构建与发布工具
.github/workflows/ci.yml  自检 → 原生缓存 → 编译 → APK 闸门 → Release + raw 直链
```

第三方许可见 `docs/THIRD_PARTY_NOTICES.md`。

## 如实说

- 真机截图证明 `1.1.0+80` 能启动、模型页四文件就绪、设置页可见；**直接朗读链路的出声效果、
  三列滑动手感、调音台重做后的体感、音频焦点与系统栏反色仍是零证据**。CI 全绿只证明打进了包且符合断言，不等于好用。
- 按既定规矩不向用户索取装机验证；拿不准的界面自己画预览、自己看、自己改。
- voicedesign 是单音色权重，全书声音由调音台统一决定；固定 seed + 身份描述能收敛「逐段换声」，不能根治。
