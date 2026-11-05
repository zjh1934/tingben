# 听本

> 本地离线把一本小说变成能听的音频。与旧「墨听」彻底切割的从零重做项目。

## 安装

```
https://raw.githubusercontent.com/zjh1934/ting-ben-apk/main/app-debug.apk
```
备用 Release 页：`https://github.com/zjh1934/tingben/releases/latest`

- 固定地址**永远是最新版**；当前 `1.0.0+68`（跟 CI run 号），字节数 / blob / 验证结果见 `STATUS.md`
- 仓库已改名：源码仓 `tingben-android→tingben`，APK 仓 `tingben-apk→ting-ben-apk`，旧地址自动跳转
- 包名 `com.tingben.app`，固定签名，**新版直接覆盖装**，模型与已做好的音频都保留
- 平台解析器读不了 GB 级二进制会报「资源解析服务请求失败」——不是坏链，用浏览器打开

**现行队列 → `ROADMAP.md`（唯一真源）** · 当前状态 → `STATUS.md` · 历轮复盘 → `ITERATION_LOG.md`

## 用起来四步

1. 左上角 ☰（或从左边缘右滑）开抽屉 →「模型」→ 导入三个 GGUF（约 5GB，App 自己不联网）
2. 顶栏右上「＋」导入 txt / md / epub，或书架默认展开的输入框粘贴文字；也支持在其他 App 里「打开方式 → 听本」
3. 点书卡 ▶ → **制作准备页**选本次预设（默认「直接朗读」；「标注后合成」是实验性）→ 开始制作
4. 进度页看阶段与分段看台 →「任务」管长任务 →「我的音频」完整播放器试听 / 保存

制作开始后有常驻通知「听本 · 本地制作中」（书名 / 阶段 / `X/Y` / 停止），由 `dataSync` 前台服务 +
按块 `PARTIAL_WAKE_LOCK`（30 分钟硬超时）承载。**不保证不被杀**：省电策略、内存紧张、Android 15 起约
6 小时 FGS 超时都可能暂停；回来在「任务」页点「继续制作」，已做好的块不重算。

## 三个模型（后导入、不进包）

| 槽位 | 线上 `+68` 用的文件 | 国内地址 |
|---|---|---|
| 语言模型 | `huihui-qwen3-4b-instruct-2507-abliterated-q4_k_m.gguf` | `https://hf-mirror.com/kuronekkko/Huihui-Qwen3-4B-Instruct-2507-abliterated-Q4_K_M-GGUF/resolve/main/huihui-qwen3-4b-instruct-2507-abliterated-q4_k_m.gguf` |
| 声音主文件 | `qwen-talker-1.7b-voicedesign-Q8_0.gguf` | `https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/main/qwen-talker-1.7b-voicedesign-Q8_0.gguf` |
| 声音配套 | `qwen-tokenizer-12hz-Q8_0.gguf` | `https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/main/qwen-tokenizer-12hz-Q8_0.gguf` |

**代码路径已切换（R60 / `+68`），GGUF 仍要手动导入**：语言模型文件是
`huihui-qwen3-4b-instruct-2507-abliterated-q4_k_m.gguf`（国内地址见上表）。
它是**仅非思考**权重，代码里的「思考过程」开关、`/no_think`、思考档温度 0.6、两套缓存分桶已删；旧文件名保留为导入白名单兜旧数据。
依据 `docs/R57_换模型联动表.md` 的 11 处联动、落地报告 `docs/R60_P0换模型与双页拆分_落地.md`。
导入后必须过 `ROADMAP.md` P0 的 A/B 四项 gate 才算「更会写 JSON」，不达标回退官方 2507 基线。
**别下错成 Thinking-2507 版**（只差一个词）。音频模型权重不换。

## 设置页与调音台

- **目录页（R61 / `+68` 起）**：点一本书进四列横滑目录（原始 / 标注 / 合成 / 可播）；勾选章后底部小条批量制作，
  点章名看原文，成品从软件内悬浮条播放，不做独立播放器页。
- **设置页（R60 / `+68` 起）**：只管软件 —— 外观、数据与日志、后台、关于，加一行「语言模型设置 ›」；
  夜间模式 / 字号在代码里**没有**，界面标「待新增」，不画假开关。
- **语言模型设置页**（抽屉独立目的地）：只读模型卡 + 已移除项说明 + 主层三项 + 高级只读五项 + 诊断评测；
  `MODEL_ID` / `AbGate` 写进诊断摘要。思考开关、`top_k` / `min_p` / YaRN / GPU 层数等物理不存在的项一个都不画。
  项清单 `docs/R58_设置页与语言模型设置页_项清单v2.md`，v8 预览 12 屏已通过并落地。
- **调音台**（☰ 直达独立页）：`MixerDraft` 唯一编辑态 + `commitAll()` 唯一生效出口，
  **提交不需要先试听**；「试听未提交参数 / 试听已生效参数」点一下 = 合成 + 自动播放。
  9 项采样（中文名 · 滑块 · 小数字框 · ⟳）真到 `qt_tts_params`，逐项对应官方字段与默认值；
  12 维音色生成器、表演层、段间静音是**上层**（模型只看得到文字或合并结果）；
  `speed/pitch`、并发、量化档、音色编号等**结构上不可调，一个开关都不画**。
  每次改动与试听都写 `MIXER_PROFILE` / `MIXER_TRIAL` / `TTS_PARAMS`，JNI 实收另打 `TTS_PARAMS_APPLIED`。
  依据：`docs/R38_调音台重做_联动表与参数触达矩阵.md`、`docs/R49_调音台v5_落地.md`、`docs/R50_真机三处修补_落地.md`。

## 硬约束

- 无 `INTERNET` 权限、仅 `arm64-v8a`、APK < 100MB、模型后导入不进包
- **正文一个字不改**：模型只加导演指令，字符级 gate 校验，不过就降级直读
- 两个模型串行加载、不同时常驻（同时驻留会 OOM）；首版只导出 WAV
- 导入 txt / md / epub（epub 按 OPF spine 分章、编码自适应）；PDF / OCR / DRM 不做
- 书架只放原书 · 任务页只放制作记录 · 我的音频只放成品，三层互不增长
- 长任务逐块落盘，退出或被杀不丢进度，重开点「继续制作」从停下的块接着跑
- 不申请无障碍、不弹浮窗、不模拟点击、不自动续跑伪装

## 架构：四层移植，不是整包微调

| 层 | 抄谁 | 拿什么 |
|---|---|---|
| 离线底座 / JNI | OfflineLLM (Apache-2.0) | Manifest、SAF 导入、llama.cpp JNI 生命周期 |
| 标注契约 | Alexandria (MIT) | speaker/text/instruct、500 字分块、角色表、JSON 四级修复 |
| 引擎组合 / 断点 / 合并 | Antioch (MIT) | llama.cpp + qwentts.cpp 同栈、逐块 WAV、WAV 拼接 |
| 产品不变量 | ReaderVoice-Mobile (MIT) | 原文不可改、UNKNOWN 合法、逐块落盘、USER_LOCKED |
| 播放器 / 折叠看台 | AlexExoPlayerMP3 · ensemble-reads | 队列内核、状态快照、折叠控件 |
| 前台服务 | ReaderVoice-Mobile (MIT) | dataSync FGS、通知渠道、按块唤醒锁 |

自己写的是：中文 prompt、字符级逐字 gate、Kotlin 编排、CI、界面。第三方许可见 `docs/THIRD_PARTY_NOTICES.md`，
历史文档与一次性脚本位置见 `docs/HISTORY_INDEX.md`。

## 代码地图

```
app/src/main/java/com/tingben/app/
  MainActivity.kt          页面主体与编排（书架/制作/进度/任务/音频/设置/语言模型设置/模型/角色/调音台）
  AppChrome.kt            全局壳：顶栏 ☰/＋、左侧抽屉、零文案下拉刷新、chromeOf、状态栏安全区
  ProduceService.kt       dataSync 前台服务、进度通知、按块唤醒锁
  ProduceOptions.kt       折叠分组与档位胶囊
  core/ProduceConfig.kt   本次制作预设 + 宽容 JSON 迁移 + 指纹
  core/Mixer.kt / MixerDraft.kt / ParamSpec.kt   调音台：快照 / 唯一编辑态 / 9 项官方元数据
  core/Pipeline.kt        书架、模型槽位、分块、标注 gate、WAV 合并、任务书签
  core/AudioLibrary.kt / PlayQueue.kt / SegPlan.kt / Workbench.kt
  core/VoiceProfile.kt / VoiceCast.kt / Prefs.kt / NonVerbal.kt / Importer.kt
  engine/NativeEngine.kt  JNI 声明
  diag/EventLog.kt / AuditLog.kt
app/src/main/cpp/         tingben_native.cpp + CMakeLists + 上游公开头
app/src/main/assets/      prompt_zh.txt (v2) + prompt_zh_v1.txt（可回滚）
contracts/                模型清单、中文 prompt、流水线规格
samples/                  试音短篇 / 旧巷钟声 / 解析测试 epub
scripts/                  CI 自检与验证脚本、build_native.sh、publish_apk.py
.github/workflows/ci.yml  自检 → 原生缓存 → 编译 → APK 闸门 → Release + raw 直链
```

## 如实说

- **出声已有一次完整链路证据**（`PRODUCE_DONE 5,802,284 字节`，约 121 秒 WAV）；但换权重后的标注质量、
  每段音色稳定、角色不飘、整本体验、界面顺手程度**仍是零证据**。沙盒没有 NDK / kotlinc / 真机，
  CI 全绿只证明打进了包且符合断言，**不等于好用**。
- voicedesign 是单音色权重；`speaker` 目前只是文字标签，通过全局 / 角色描述影响 instruct，
  **不是按角色切声线**。固定 seed + 身份前缀能收敛「逐段换声」，不能根治。
- 精确行内标签（`[gasp]` 等）属下一代权重，本模型不解析；原文写到哭笑叹气时由程序追加导演指令，正文不动。
- 历史坑（留作纪律）：直链曾滞留旧版、静态自检抓不到作用域 / API 不存在、binary AXML 权限是 UTF-16LE。
  每轮交付前核直链 blob，不靠「发过版」的印象。
