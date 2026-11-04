# 听本

> 本地离线把一本小说变成能听的音频。与「墨听」彻底切割的**从零重做**项目。

## 下载安装

```
https://raw.githubusercontent.com/zjh1934/tingben-apk/main/app-debug.apk
```
备用（Release 页）：`https://github.com/zjh1934/tingben-android/releases/latest`

- 固定地址**永远是最新版**，版本号跟 CI run 号（如 `1.0.0+24`），当前版本见 `STATUS.md` 末尾
- 浏览器直接打开即可下载；包名 `com.tingben.app`，与旧墨听不同，**可同时安装、不用卸载**
- 固定签名，后续新版直接覆盖安装，模型和已做好的音频都保留
- 源码：`https://github.com/zjh1934/tingben-android`（公开）

## 用起来就四步

1. 装包，第一次进设置页 →「导入 / 查看模型」→ 导入三个 GGUF（约 5GB，App 自己不联网）
2. 书架右上「＋ 导入一本书」→ 选 **txt / md / epub**（仓库 `samples/` 里有测试用书）；也可以在微信、文件管理器里对一本书点「打开方式 → 听本」直接加进来；或点「没有文件？粘贴一段文字」
3. 点书卡的「制作有声书」→ **制作准备页**，四组折叠，默认只露第一组：
   - **① 做什么**：制作方式二选一（直接朗读 / 标注后合成）、勾章节、预计块数
   - **② 怎么读**：朗读档位（叙事 / 对白 / 广播剧）、语速倾向、情绪强度、角色音色入口
   - **③ 怎么跑**：段间静音（0/300/600ms）、标注失败重做次数、思考档只读展示
   - **④ 高级**：跨书默认值只读 + 「本次恢复默认」
   
   这些只影响**这一次制作**，不写回设置页；随任务书签落盘，杀进程回来续跑也不丢。
   然后点「开始制作」
4. 进度页看阶段、分段看台点小格试听单段 →「任务」页管长任务 →「我的音频」里完整播放器试听 / 保存到手机

> 「只想先听声音」那种藏在按钮底下的小字捷径已删，直接朗读是正式选项。

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
- **书架只放原书 · 任务页只放制作记录 · 我的音频只放成品**，三层互不增长（同内容重复导入有 SHA-256 去重）

## 架构：四层移植，不是整包微调

| 层 | 抄谁 | 拿什么 |
|---|---|---|
| Android 离线底座 / JNI | OfflineLLM (Apache-2.0) | Manifest、SAF 导入、llama.cpp JNI 生命周期、设置常量 |
| 标注契约 | Alexandria (MIT) | speaker/text/instruct、500 字分块、角色表+最近 3 条、JSON 四级修复 |
| 引擎组合 / 断点 / 合并 | Antioch (MIT) | llama.cpp + qwentts.cpp 同栈、逐块 WAV、WAV 拼接 |
| 产品不变量 | ReaderVoice-Mobile | 原文不可改、UNKNOWN 合法、进程被杀只丢当前批、角色档案 USER_LOCKED |
| 播放器 / 折叠看台 | AlexExoPlayerMP3 · SSComposeExpandableListView(MIT) · ensemble-reads(Apache-2.0) | 队列内核、300ms 状态快照、header/child 折叠、控件集 |

自己写的是：中文 prompt、字符级逐字 gate、Kotlin 一键编排、CI、界面。
`docs/听本_项目导图.html` 是完整导图，`docs/R19_路线图与决策记录.md` 是当前有序队列，`docs/*_移植清单.md` 逐块登记了抄哪个文件。

## 代码地图

```
app/src/main/java/com/tingben/app/
  MainActivity.kt          全部 Compose 界面（书架/制作/进度/任务/音频/设置/模型/角色页）
  ProduceOptions.kt        折叠分组 + 档位胶囊控件（改写自现成实现）
  core/ProduceConfig.kt    本次制作预设（单一 data class + 宽容 JSON 迁移 + 指纹）
  core/Pipeline.kt         书架、模型槽位、分块、标注 gate、WAV 合并、一键编排、任务书签
  core/AudioLibrary.kt     成品登记（UUID + index.json，书架不膨胀）
  core/PlayQueue.kt        播放队列单例（音频页与分段看台共用）
  core/Workbench.kt        分段看台数据（扫 work 目录，不造状态）
  core/VoiceProfile.kt     instruct 三层 + 默认身份锚点 + 身份词剥离
  core/VoiceCast.kt        角色→音色映射、别名合并、USER_LOCKED
  core/Prefs.kt            全部用户配置 + 声音指纹（配置变了作废旧 WAV）
  core/NonVerbal.kt        哭笑叹气导演指令（只改 instruct，正文不动）
  core/Importer.kt         txt/md/epub 导入与分章
  engine/NativeEngine.kt   JNI 声明
  diag/EventLog.kt         工程事件落盘（原生层也往这里写）
app/src/main/cpp/          tingben_native.cpp + CMakeLists + 上游公开头
app/src/main/assets/       prompt_zh.txt (v2) + prompt_zh_v1.txt（可回滚）
contracts/                 模型清单、中文 prompt、流水线规格
samples/                   试音短篇.txt / 旧巷钟声.txt / 解析测试.epub
scripts/                    build_native.sh / check_structure.py / publish_apk.py
.github/workflows/ci.yml   自检 → 原生缓存 → 编译 → APK 闸门 → Release + raw 直链
```

## 如实说

- **CI 全绿、APK 闸门全过**（无 INTERNET、无 .gguf、7 个 arm64 .so、CRC 全检、约 17MB）；每版都拆包硬验关键符号真的在 dex / .so 里，不只看编译通过。
- **真机已有一次完整链路证据**：`PRODUCE_DONE 5,802,284 字节`（约 121 秒 WAV）。但「每一段音色稳定、角色不飘、整本体验」**仍是零证据** —— 沙盒没有 NDK/kotlinc/真机，编译通过永远不等于好用。
- **音色**：voicedesign 是**单音色**权重，一个声音演所有情绪；4B 标出的 `speaker` 目前只是文字标签，通过全局/角色描述影响 instruct，**不是按角色切声线**。R19 加了默认身份锚点 + 身份词剥离来收敛「逐段换声」，但权重每次重新采样的固有特性不能靠代码根治。
- **非语言表达**：原文写到哭 / 笑 / 叹气 / 抽泣 / 咳嗽时自动加导演指令，正文一字不改，设置页可关。精确的行内标签（`[gasp]` 等）是下一代权重能力，本模型不解析。详见 `docs/NONVERBAL_RESEARCH.md`。
- **后台**：任务跑着时前台屏幕常亮（`FLAG_KEEP_SCREEN_ON`，零权限）；**这不等于保活**。锁屏/切后台后靠逐块落盘恢复，前台服务 + 唤醒锁在队列 P1。详见 `docs/后台保活与浮窗_调研.md`。
- 本环境 `raw.githubusercontent.com` 被网关策略 403（不是文件坏），所以核验走 GitHub blob API；用户手机浏览器历轮可正常下载安装。
