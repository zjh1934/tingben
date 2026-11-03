# 听本 · 当前状态

**2026-11-04 · 首版已发**

| 项 | 值 |
|---|---|
| 版本 | 1.0.0+11 (111) → **1.0.0+12 待发**（停止 / 断点续传） |
| APK | 17,487,576 字节 / 16.7MB / arm64-v8a |
| 直链 | `https://raw.githubusercontent.com/zjh1934/tingben-apk/main/app-debug.apk` |
| CI | run 12 编译中（纯 Kotlin，走原生缓存，约 1~2 分钟） |
| 源码仓 | `zjh1934/tingben-android`（公开，commit 见 releases） |
| APK 仓 | `zjh1934/tingben-apk`（公开） |

## 已落地

- 四层移植：OfflineLLM 底座 / Alexandria 标注 / Antioch 引擎与合并 / ReaderVoice 不变量
- 5 页 UI：书架、制作（可选章）、进度、我的音频、设置（高级折叠）
- 一键制作：分章 → 逐块标注 → 字符级 gate → 串行合成 → 逐块 WAV → 拼接 → 可试听/保存
- 模型槽位导入、缺文件提示、复制下载地址、删除重试
- 原生引擎进包（libllama / libqwen / libggml* / libtingben_native 共 7 个 .so）
- 固定签名、无 INTERNET、<100MB 闸门、CI 缓存加速、失败日志回写

## 未验 / 未做

- **真机出声零证据**：装包、导模型、点「开始制作」只有用户能做
- 首版只 WAV；无后台续跑、无常驻通知、无倍速跳章
- 块间静音插入、checkpoint.json、章节起止样本数：规格写了但首版未实现（见 `contracts/pipeline_spec.md` 的「实现差距」）
- 单一音色（voicedesign 物理限制）
- 只 txt 导入

## 下轮队列

1. 等真机反馈：能不能加载两个模型、能不能出声、耗时
2. 若 LLM 不收敛 → 加 JSON 闭合即停 + 单块超时降级
3. 补 C3/C4（静音与断点）、后台续跑

## R3 真机反馈（已修，待复验）

- 导入 2.5GB 模型时闪退：主线程同步复制导致 ANR 被杀 → 已改后台 IO + 进度 + 异常兜底
- 导入完不知道下一步：模型页缺跳转按钮 → 齐了自动回书架 + 三处主按钮
- 诊断日志现在会带「最近一次崩溃」完整堆栈
- **仍待用户复验**：装新版（旧模型文件会保留，不用重导）→ 书架导入一本书 → 点开始制作 → 看是否出声

### 发布记录（R3）

- `https://raw.githubusercontent.com/zjh1934/tingben-apk/main/app-debug.apk`
- 17,503,968 字节 / CRC 全过 / 签名 `CN=MoTing Debug` 不变，**直接覆盖安装**
- APK 内 `versionName=1.0.0+8` 已从 binary Manifest 读出确认
- 7 个 arm64 .so、无 .gguf、无 INTERNET

## R4 · 没有现成书 → 补测试用书 + 兑现「粘贴文字」

用户手上没有 txt，要求写一本书用来测。

- 新增 `samples/试音短篇.txt`（545 字 / 1 章 / 2 块）与 `samples/旧巷钟声.txt`（4430 字 / 6 章 / 15 块），原创、章节标题命中切章正则、含旁白与三个角色的对白和情绪起伏
- **修一个自己埋的假承诺**：书架空态写着「也可以粘贴文字」，但代码里根本没有输入框。现已补上可折叠粘贴区（书名 + 正文 + 粘贴按钮 + 建书），默认收起不占首屏主按钮
- 底部 3 个 Tab 加 `navigationBarsPadding()`：截图里系统三键导航键和 Tab 文字叠在一起
- 建议先用短篇验证出声，再勾 1~2 章跑全本，别一上来整本 15 块

### 发布记录（R4）

- `https://raw.githubusercontent.com/zjh1934/tingben-apk/main/app-debug.apk`
- 17,503,971 字节 / CRC 全过 / `versionName=1.0.0+9` / 签名 `CN=MoTing Debug` 不变，**直接覆盖安装**
- 7 个 arm64 .so、无 .gguf、无 INTERNET
- `samples/` 两本测试书已进仓库；短篇建议先跑（2 块），全本 15 块别一上来整本

## R5 · 真机第二轮反馈：进度页找不到 + 一直不动 + 三项没做

用户日志实锤：`03:24:43 NATIVE_PREFILL_BEGIN` → `03:25:53 PREFILL_DONE`，**一批 512 token 算了 70 秒**，期间块进度一直 0/N，界面纹丝不动。用户按返回后进度页再也找不到；并点名「思考外显开关、模型设置、音频调试」都没做。

### 根因

1. **导航**：进度页只在 `ProduceScreen` 里藏了一个「查看进度」小文字按钮；底部「书架」Tab 一律回书架；**系统返回键没接管**，按一次直接退 Activity，重开 `screen` 重置为书架，后台任务还在跑却没地方看。
2. **不动**：不是死机，是 CPU 在 prefill。但 UI 只在「一整块做完」才 `emit`，70 秒空窗零反馈。
3. 三项设置确实在从零重写时没带过来。

### 已改

- `BackHandler`：任何二级页按系统返回统一回书架，不再退出 App
- **跨页常驻任务条**：只要 `running` 或有产物，底栏上方一条可点，任何屏直达进度/成品；书架顶部也加「正在制作 → 查看进度」卡
- **进度心跳**：`EventLog` 缓存最近一条引擎事件 + 时间戳，进度页每秒刷新，人话显示「正在把这一段读进模型，手机上这一步可能要一两分钟」+ 不确定进度动画；native 解码事件从每 32 token 改成每 8 token
- 修阶段条：「正在分章」原来匹配不上「拆章」，第一步永远不亮
- **思考开关**：`Prefs` + `/think` `/no_think` 软开关；开时 temp=0.6（贪心会无限重复）、预算放到 2048、推理全文按块落盘，进度页可展开逐块看
- **模型设置**：上下文 4096/6144/8192、线程 2/4/6、随机度 0/0.3/0.6，真接进 `llmLoad` / `llmGenerate`，诊断信息带当前配置
- **音频调试**：设置页「试合成一句」真调 `ttsLoad → ttsSynthesize → ttsFree`，出 `probe.wav` 能试听；制作中禁用，避免两模型抢内存

### 如实说

- 8192 档在 8Gen3 上有 OOM 风险，界面已写明「不确定就别动」
- 思考档每块先吐几百字推理，**整本明显更慢**，长块可能超预算走降级直读
- prefill 70 秒是手机 CPU 物理速度，改代码改不掉；这轮只解决「看不出它还在算」

### 发布记录（R5）

- `https://raw.githubusercontent.com/zjh1934/tingben-apk/main/app-debug.apk`
- 17,536,726 字节 / CRC 全过 / `versionName=1.0.0+10` / 签名不变，**直接覆盖安装**
- 7 个 arm64 .so、无 .gguf、无 INTERNET
- 原生引擎因 `tingben_native.cpp` 改动重编（心跳 32→8），缓存 key 已正确命中变化

### 待用户复验

1. 点开始制作 → 按系统返回 → 底栏上方是否出现常驻任务条、能否点回进度页
2. 进度页是否每秒显示「正在把这一段读进模型」而不是纹丝不动
3. 设置页三项：思考开关、模型参数、试合成一句

## R6 · 真机第三轮：TTS 试音报「库没加载」+ 4B 慢到不可用

### ① TTS_PROBE_FAIL（确定性 bug，已修）

`No implementation found for ... ttsLoad ... is the library loaded, e.g. System.loadLibrary?`

`nm -D` 查过 APK 里的 `libtingben_native.so`，**9 个 JNI 符号全在**，包括 `ttsLoad`。
所以不是符号缺失，是 **`System.loadLibrary` 压根没执行**：`ensure()` 只在
「复制诊断信息」和「开始制作」里被调到；用户装完 +10 直接进设置页点「试合成一句」，
进程从头到尾没加载过库。诊断日志头部显示引擎可用，是因为他**事后**才点复制诊断信息。

修：`TingbenApp.onCreate()` 启动即 `isAvailable()` 预热并记 `ENGINE_PRELOAD`；
音频调试按钮内也补一道 `isAvailable()` 检查。`TTS_PROBE_FAIL` 从 CRASH 改记 ENGINE（它不是崩溃）。

### ② 速度：真机实测 9.6 秒/token

`03:27:48 token=0` → `03:32:56 token=32`，32 个 token 用了 308 秒。
4B Q4_K_M 在 8Gen3 CPU 上不该这么慢。另注意 prefill 分两批：
第一批 512 token **70 秒**，第二批 195 token **115 秒** —— 更少 token 反而更慢，
说明每次 `llama_decode` 有巨大固定开销，分批是反优化。

改：`n_batch = n_ctx`、`n_ubatch = 512`、`n_threads_batch = n_threads`，
**一次提交完整 prompt**，由 llama.cpp 内部分片；删掉手工分批循环。
设置页线程补 8 核选项。

⚠️ 不宣称已解决：若一次 prefill 后 decode 仍是秒级/token，那就是这台机器的
内存带宽/热降频物理上限，调参救不了，得靠下面的路径绕开。

### ③ 新增「直接朗读（跳过标注）」

真机 4B 逐块标注 500 字要几十分钟到小时级，用户至今**一声都没听到**。
制作页加一个次要入口：不加载 4B、不标注，原文按句号切成 ≤110 字短段，
全旁白直接交给 TTS → 拼接 → 试听。几分钟内能出声，先把
「TTS 加载 → 合成 → WAV 合并 → 试听」整条链验证掉，再回头打磨标注质量。
`direct_XXXX.wav` 与完整制作的 `chunk_XXXX.wav` 分开存，缓存不串味。

### 待用户复验

1. 重启 App → 设置页「试合成一句」应出 `probe.wav` 并能试听
2. 制作页点「只想先听声音？直接朗读」→ 我的音频里能听到人声
3. 再点「开始制作」→ 进度页看 prefill 是否比 70s+115s 短、decode 是否不再是 9.6s/token

### 发布记录（R6）

- `https://raw.githubusercontent.com/zjh1934/tingben-apk/main/app-debug.apk`
- 17,536,704 字节 / CRC 全过 / `versionName=1.0.0+11` / 签名不变，**直接覆盖安装**
- 7 个 arm64 .so、无 .gguf、无 INTERNET；`nm -D` 复核 9 个 JNI 符号齐全（含 `ttsLoad`）

## R7 · 用户插话：一跑起来就不停了

真缺口，不是取舍：之前只有 `nativeCancel()` 一个 C++ 原子位，**UI 没有停止按钮**，
合成阶段虽有「wav 存在就跳过」但**标注结果从不落盘**，重开 App 点制作等于全本重跑 4B。

### 已改

- **停止**：进度页红色「停止」按钮 → `Producer.stop()` 置标志 + `nativeCancel()`；
  decode 循环下一 token 自退，块边界写「已停止」并释放模型。**不删已做好的块**。
- **断点续传（真正的部分）**：`AnnCache` 把每块标注 JSON 落盘；重跑时直接读盘跳过 4B，
  同时照样喂回角色表 / 最近 3 条上下文，保证后续块连贯。合成阶段已有 WAV 也跳过。
- **分桶隔离**：work 目录按「模式（fast/think/direct）+ 选中章节」分桶，
  换选章、切思考档不会串味；「从头重做」按钮只清当前桶。
- **入口**：制作页有缓存时主按钮变「继续制作」；进度页停止后给「继续制作」一键续跑；
  跨 App 重启也能续（从磁盘读，不依赖内存状态）。
- 制作中断异常兜底写 `PRODUCE_EXCEPTION`，不再静默卡在 running。

⚠️ 一次 prefill 是单次 `llama_decode`，期间不响应停止，最多等这一批算完才退出。
