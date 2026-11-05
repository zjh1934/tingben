# 听本

> 本地离线把一本小说变成能听的音频。与「墨听」彻底切割的**从零重做**项目。

## 下载安装

```
https://raw.githubusercontent.com/zjh1934/tingben-apk/main/app-debug.apk
```
备用（Release 页）：`https://github.com/zjh1934/tingben-android/releases/latest`

- **方向（R31 定，R33 订正）**：App 发力点转到音频模型调参；换模型不做。
  **端侧 4B 标注不撤、不替换、保持现状**——想用就用、不想用就不用，只是把 4B 的调整沉到后面。
  沙盒标注工厂是**可选的第二条输入来源**（格式见 `docs/标注工厂_输出契约.md`），导入通道待接。
- 固定地址**永远是最新版**，版本号跟 CI run 号（如 `1.0.0+51`），当前版本见 `STATUS.md` 头部
- 想知道「模型到底标了什么」：设置页「高级（诊断）→ 查看标注内容」，或复制诊断摘要（已带最近 3 块逐条标注全文）。
  诊断摘要把「模型候选的原始产出」与「最终真正进 TTS 的段」分开写，并给出「未采纳首因」；
  进度页与分段看台也区分「模型真标注」和「模型没采纳按原文直读」；
  看台上方有「语言模型是否生效」判定条，不用贴日志就能判（见 `docs/本次测试_语言模型生效判定卡.md`）
- 浏览器直接打开即可下载；包名 `com.tingben.app`，与旧墨听不同，**可同时安装、不用卸载**
- 固定签名，后续新版直接覆盖安装，模型和已做好的音频都保留
- 源码：`https://github.com/zjh1934/tingben-android`（公开）

## 用起来就四步

1. 装包，左上角 ☰ →「模型」→ 导入三个 GGUF（约 5GB，App 自己不联网）
2. 顶栏右上「＋」导入一本书 → 选 **txt / md / epub**（仓库 `samples/` 里有测试用书）；也可以在微信、文件管理器里对一本书点「打开方式 → 听本」直接加进来；或直接在书架默认展开的输入框里粘贴文字（带粘贴 / 清除 / 建书，可折叠收起）
3. 点书卡的 ▶ 图标 → **制作准备页**，四组折叠，默认只露第一组：
   - **① 做什么 · 仅本次**：制作方式二选一（**默认直接朗读** / 标注后合成＝实验性）、勾章节、预计块数
   - **② 怎么读**：朗读档位（叙事 / 对白 / 广播剧）、语速倾向、情绪强度、角色音色入口
   - **③ 怎么跑**：段间静音（0/300/600ms）、标注失败重做次数、思考档只读展示
   - **④ 高级**：跨书默认值只读 + 「本次恢复默认」
   
   ①②③ 只影响**这一次制作**，不写回设置页；④ 是跨书长期默认值，去设置页改
   （设置页按 ① 语言模型 / ② 音频模型 分组，组标题直接写明「长期默认」；R33 已删掉单独的说明卡）。
   本次预设随任务书签落盘，杀进程回来续跑也不丢。
   然后点「开始制作」
4. 进度页看阶段、分段看台点小格看原文 / 试听 / 重做这一段 →「任务」页管长任务 →「我的音频」里完整播放器试听 / 保存到手机

> 「只想先听声音」那种藏在按钮底下的小字捷径已删，直接朗读是正式选项。

**制作开始后会有一条「听本 · 本地制作中」的常驻通知**：显示书名、阶段、`已完成 X/Y` 进度，
通知里能直接点「停止」，点通知回书架/任务页。这是 Android 前台服务（dataSync），
配合按块 `PARTIAL_WAKE_LOCK`（每块 30 分钟硬超时）让 CPU 在推理期间别睡。
⚠️ 它**不保证不被杀**：系统省电策略、内存紧张、Android 15 起约 6 小时的前台服务超时都可能暂停任务；
回来在「任务」页点「继续制作」即可，已做好的块不会重算。想更稳，去设置页「关于 → 后台运行设置」
把听本设为不受电池优化限制。

## 三个模型（唯一确定、不进包）

| 槽位 | 文件 | 国内地址 |
|---|---|---|
| 语言模型 | `mlabonne_Qwen3-4B-abliterated-Q4_K_M.gguf` | `https://hf-mirror.com/bartowski/mlabonne_Qwen3-4B-abliterated-GGUF/resolve/main/mlabonne_Qwen3-4B-abliterated-Q4_K_M.gguf` |
| 声音主文件 | `qwen-talker-1.7b-voicedesign-Q8_0.gguf` | `https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/main/qwen-talker-1.7b-voicedesign-Q8_0.gguf` |
| 声音配套 | `qwen-tokenizer-12hz-Q8_0.gguf` | `https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/main/qwen-tokenizer-12hz-Q8_0.gguf` |

平台解析器读不了 GB 级二进制会报「资源解析服务请求失败」——**不是坏链，用浏览器打开**。

## 设置里能改什么

设置页分组折叠，默认不挡主路：模型 / 思考过程 / 模型设置 / 音频调试 / 声音 / 角色音色 / 关于 / 高级（诊断）。

- **制作方式 / 本次预设**：都在**制作准备页**选，不在设置页。设置页只放跨书默认值（模型、思考、上下文、线程、全局音色、采样参数、角色表）
- **思考过程**：默认关（直接出标注、最快）。打开后每块先想再标，进度页能逐块展开推理全文；代价是明显更慢、更吃上下文
- **模型设置**：上下文 4096/6144/8192、线程 2/4/6、随机度 0/0.3/0.6。8192 在部分手机上会内存不足
- **音频调试**：「试合成一句」不跑整本，只让声音模型读一句话并试听
- **声音**：全局音色描述（留空用默认身份锚点）、9 个 TTS 采样参数（默认值逐字抄 `qwentts.cpp/sampling-defaults.h`）
- **角色音色**：角色→音色映射表，`USER_LOCKED` 人工修正优先
- **调音台（R31 建，R33 提一级，R36/R38 重设计，R40 统一提交，R41a 试听改版）**：左上角 ☰ →「调音台」直达独立页（设置页也保留入口）——
  **吸顶三段式状态条**：「已生效 v4 · 指纹 ｜ 未提交 N 项 ｜ 查看差异 / 提交变更」常驻不滚走；
  所有编辑先进 `MixerDraft` 草稿，**全页只有「提交变更」一个生效动作**，差异弹层逐项给旧→新。
  **首屏固定「音色身份」卡**：已生效全文只读回显 + diff 高亮 + 多行草稿 + 清空回默认（待提交）；
  12 维音色生成器（含可编辑预览框）与 10 条现成中文网格都只「填入草稿」，不偷偷改音色。
  **9 项采样逐项对应官方**：中文名 · `qt_tts_params` 官方字段名 · 官方默认值 · 当前值 · 一行说明 ·
  触达状态 · 常用档位 · 精确值输入 · 单项恢复官方默认（R40 起默认收起，身份生成器默认展开）。
  另有参数触达矩阵（到引擎 / 上层 / 结构三态，**不画假开关**）、官方资料索引 O1~O7（按名称自行查，
  App 无联网权限不摆假链接）、自定义预设库（`.part` 原子落盘）、4 张内置角色卡。
  **试听在吸顶状态条那一块（R41a）**：「试听未提交参数」「试听已生效参数」两个按钮，点一下合成完自动播放；
  各用整套 `MixerDraft`（身份 / 风格 / 9 项采样），不写回长期默认；「‹ 返回」也常驻这一块。
  每次改动与试听都把完整参数写进日志（`MIXER_PROFILE` / `MIXER_TRIAL` / `TTS_PARAMS`），
  JNI 实收值另打 `TTS_PARAMS_APPLIED`。⚠️ 12 维网格是辅助拼装器，不是官方 API 字段。
  角色行显示已绑定音色 chip、未绑定给橙色提示；删除有二次确认；离开有未提交变更先问一次。
  维度体系见 `docs/R31_调音台_维度体系与联动表.md`，触达矩阵见 `docs/R38_调音台重做_联动表与参数触达矩阵.md`，
  分轮依据见 `docs/R39_调音台统一提交_联动表与分轮.md`，落地见 `docs/R40_调音台统一提交_落地.md`；
  试听改版见 `docs/R41a_试听改版_联动表.md`。
- **高级（诊断）**：静态快照、本次标注审计汇总、9 条规则生效统计、逐块明细、产出截取片段；
  两级复制（摘要 / 完整日志）。跑完却没标出条目会直接告警

## 硬约束

- 无 `INTERNET` 权限、仅 `arm64-v8a`、APK < 100MB、模型后导入不进包
- **正文一个字不改**：模型只加导演指令，字符级 gate 校验，不过就降级直读（原文照读、不静音）
- 两个模型串行加载、不同时常驻（同时驻留会 OOM）
- 首版只导出 **WAV**；M4A / m4b 内嵌章节轨、MP3 都是二期
- 导入支持 txt / md / epub（epub 按 OPF spine 结构化分章、编码自适应）；PDF、OCR、DRM 明确不做
- 用户第一次听到声音前，主界面不出现 `.gguf` 文件名、SHA、token 数、调试页
- 长任务单独一页管：退出 App 或被系统杀掉后任务不消失，重开后点「继续制作」从停下的块接着跑，逐块落盘不重算
- 制作进行中任何页面底部都有常驻任务条；按系统返回统一回书架，不会退掉 App
- **本次预设不进缓存分桶**：换朗读档位只作废已合成的分段 WAV、标注保留（复用标注重合成），不会重跑几小时的 4B；段间静音只在合并时插入，连 WAV 都不用作废
- 任务跑着时前台屏幕常亮（`FLAG_KEEP_SCREEN_ON`，零权限）；**不等于保活**
- 长任务用 `dataSync` 前台服务 + 可见通知 + 按块唤醒锁承载；**不申请无障碍、不弹浮窗、不模拟点击、不自动续跑伪装**，被杀后在任务页由用户点继续
- **书架只放原书 · 任务页只放制作记录 · 我的音频只放成品**，三层互不增长（同内容重复导入有 SHA-256 去重）

## 架构：四层移植，不是整包微调

| 层 | 抄谁 | 拿什么 |
|---|---|---|
| Android 离线底座 / JNI | OfflineLLM (Apache-2.0) | Manifest、SAF 导入、llama.cpp JNI 生命周期、设置常量 |
| 标注契约 | Alexandria (MIT) | speaker/text/instruct、500 字分块、角色表+最近 3 条、JSON 四级修复 |
| 引擎组合 / 断点 / 合并 | Antioch (MIT) | llama.cpp + qwentts.cpp 同栈、逐块 WAV、WAV 拼接 |
| 产品不变量 | ReaderVoice-Mobile | 原文不可改、UNKNOWN 合法、进程被杀只丢当前批、角色档案 USER_LOCKED |
| 播放器 / 折叠看台 | AlexExoPlayerMP3 · SSComposeExpandableListView(MIT) · ensemble-reads(Apache-2.0) | 队列内核、300ms 状态快照、header/child 折叠、控件集 |
| 长任务前台服务 | ReaderVoice-Mobile (MIT) | dataSync FGS 形状、通知渠道、打开/停止 PendingIntent、按块唤醒锁 |

自己写的是：中文 prompt、字符级逐字 gate、Kotlin 一键编排、CI、界面。
`docs/听本_项目导图.html` 是完整导图，`docs/R19_路线图与决策记录.md` 是当前有序队列，`docs/*_移植清单.md` 逐块登记了抄哪个文件。

## 代码地图

```
app/src/main/java/com/tingben/app/
  MainActivity.kt          页面主体与编排（书架/制作/进度/任务/音频/设置/模型/角色/调音台）
  AppChrome.kt             U1 抽出的全局壳：顶栏 ☰ 菜单 / ＋、底栏 3 Tab、零文案下拉刷新、chromeOf 映射、状态栏安全区
  ProduceService.kt        前台服务 dataSync、进度通知、按块 PARTIAL_WAKE_LOCK（移植 ReaderVoice, MIT）
  ProduceOptions.kt        折叠分组 + 档位胶囊控件（改写自现成实现）
  core/ProduceConfig.kt    本次制作预设（单一 data class + 宽容 JSON 迁移 + 指纹）
  core/Mixer.kt            调音台（R36/R38）：12 维音色 / 9 项采样 / 预设库 / 生效快照 / 触达三态 / 全参数日志
  core/MixerDraft.kt       调音台（R40）：唯一编辑态 + 逐字段 diff + 唯一提交出口 MIXER_COMMIT
  core/ParamSpec.kt        9 项采样的官方字段名 / 默认值 / 说明 / 触达状态（纯元数据，不复制真值源）
  core/Pipeline.kt         书架、模型槽位、分块、标注 gate、WAV 合并、一键编排、任务书签
  core/AudioLibrary.kt     成品登记（UUID + index.json，书架不膨胀）
  core/PlayQueue.kt        播放队列单例（音频页与分段看台共用）
  core/SegPlan.kt          段映射 / 逐条来源 / 单元级失效（移植 ReaderVoice scheduler, MIT）
  core/Workbench.kt        分段看台数据（读 segmap.json，旧缓存降级且标未知）
  core/VoiceProfile.kt     instruct 三层 + 默认身份锚点 + 身份词剥离
  core/VoiceCast.kt        角色→音色映射、别名合并、USER_LOCKED
  core/Prefs.kt            全部用户配置 + 声音指纹（配置变了作废旧 WAV）
  core/NonVerbal.kt        哭笑叹气导演指令（只改 instruct，正文不动）
  core/Importer.kt         txt/md/epub 导入与分章
  engine/NativeEngine.kt   JNI 声明
  diag/EventLog.kt         两级日志：主事件流 + 诊断态详情 + 崩溃环形缓冲
  diag/AuditLog.kt         逐块标注审计：条数/长度/规则/失败码/产出截取
app/src/main/cpp/          tingben_native.cpp + CMakeLists + 上游公开头
app/src/main/assets/       prompt_zh.txt (v2) + prompt_zh_v1.txt（可回滚）
contracts/                 模型清单、中文 prompt、流水线规格
samples/                   试音短篇.txt / 旧巷钟声.txt / 解析测试.epub
scripts/                    build_native.sh / check_structure.py / publish_apk.py /
                            r32_verify.py / r33_ui_clean.py / r33_verify.py / r33_apk_audit.py /
                            r36_apply.py / r36_verify.py / r38_param_audit.py / r38_apk_verify.py / sync_remote.py
.github/workflows/ci.yml   自检 → 原生缓存 → 编译 → APK 闸门 → Release + raw 直链
```

## 如实说

- **CI 全绿、APK 闸门全过**（无 INTERNET、无 .gguf、7 个 arm64 .so、CRC 全检、约 17MB）；每版都拆包硬验关键符号真的在 dex / .so 里，不只看编译通过。
- **真机已有一次完整链路证据**：`PRODUCE_DONE 5,802,284 字节`（约 121 秒 WAV）。但「每一段音色稳定、角色不飘、整本体验」**仍是零证据** —— 沙盒没有 NDK/kotlinc/真机，编译通过永远不等于好用。
- **音色**：voicedesign 是**单音色**权重，一个声音演所有情绪；4B 标出的 `speaker` 目前只是文字标签，通过全局/角色描述影响 instruct，**不是按角色切声线**。R19 加了默认身份锚点 + 身份词剥离来收敛「逐段换声」，但权重每次重新采样的固有特性不能靠代码根治。
- **非语言表达**：原文写到哭 / 笑 / 叹气 / 抽泣 / 咳嗽时自动加导演指令，正文一字不改，设置页可关。精确的行内标签（`[gasp]` 等）是下一代权重能力，本模型不解析。详见 `docs/NONVERBAL_RESEARCH.md`。
- **后台**：任务跑着时前台屏幕常亮（`FLAG_KEEP_SCREEN_ON`，零权限）；**这不等于保活**。前台服务 `dataSync` + 可见通知 + 按块 `PARTIAL_WAKE_LOCK`（R26 已落地），锁屏/切后台后靠逐块原子落盘 + 任务页恢复，目标仍是「到点停 + 回来点继续」。详见 `docs/后台保活与浮窗_调研.md`。
- **标注可观测（R21）**：每块记模型原始产出长度、有效 JSON 长度、标出几条、拼回原文差几个字、
  9 条提示词规则 PASS/VIOLATE、失败码与产出开头结尾截取；「跑半天啥也没标」会变成 `ANN_EMPTY` 告警。
  双模型怎么配合见 `docs/双模型配合_我的想法.md`，日志设计见 `docs/日志重设计_标注审计.md`。
- 本环境 `raw.githubusercontent.com` 被网关策略 403（不是文件坏），所以核验走 GitHub blob API；用户手机浏览器历轮可正常下载安装。
