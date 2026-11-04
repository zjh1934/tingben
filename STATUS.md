# 听本 · 当前状态

**2026-11-04 · 首版已发**

| 项 | 值 |
|---|---|
| 版本 | **1.0.0+17 (117)** · 17,603,743 字节 · 已发布 |
| APK | 17,487,576 字节 / 16.7MB / arm64-v8a |
| 直链 | `https://raw.githubusercontent.com/zjh1934/tingben-apk/main/app-debug.apk` |
| CI | **run 17 全绿**（原生缓存命中、编到成品、APK 闸门、Release、raw 直链同步） |
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

### 发布记录（R7）

- `https://raw.githubusercontent.com/zjh1934/tingben-apk/main/app-debug.apk`
- 17,553,080 字节 / CRC 全过 / `versionName=1.0.0+13` / 签名不变，**直接覆盖安装**
- 7 个 arm64 .so、无 .gguf、无 INTERNET
- run 12 编译失败：`AnnCache.read` 写成 `= try { ... return null }`，表达式体禁止 return，改块体后 run 13 全绿

### 待用户复验（按这个顺序）

1. 设置页「试合成一句」→ 出 `probe.wav` 能试听（验 TTS JNI）
2. 制作页「只想先听声音？直接朗读」→ 几分钟内出音频（验整条出声链）
3. 点「开始制作」→ 进度页点「停止」→ 再点「继续制作」，看是不是从停下的块接着跑而不是重头

## R8 · 第一次真机产出音频 + 两个被日志钉死的 bug

### ✅ 里程碑

`05:24:01 MERGE_DONE 2 块 / 5802240 字节` → `PRODUCE_DONE 5802284 字节`。
5.8MB WAV ≈ **121 秒人声**。从零重写以来第一次「导入 → 标注/降级 → TTS → 拼接 → 成品」整链在真机跑通。
之前所有「编译通过」都不算，这条才算。

### ① 第二次 generate 必崩（主 bug，已修）

```
04:49:01 NATIVE_GENERATE_DONE outChars=1676 limit=1274
04:49:01 GATE_RETRY 块 1 第 0 次
04:49:01 NATIVE_PREFILL_FAIL tokens=592 nBatch=4096
04:49:01 GATE_FALLBACK 块 1 降级直读
```

根因不是上下文不够（592 << 4096），是 **同一个 `llama_context` 连续 generate 没清 KV cache**：
第一次生成后位置已累加，第二次 prefill 接着往后排，直接溢出。
`llmGenerate` 开头补 `llama_get_memory` + `llama_memory_clear(mem, true)`。
每次标块都是独立补全，跨块连贯靠 Kotlin 把角色表/最近 3 条拼进 prompt，不需要 native 保留状态。

⚠️ 这个 bug 意味着 **+13 及以前凡是 gate 第一次没过、走重试的块，全都白重试并降级**。

### ② 降级直读把 500 字整块喂 TTS（已修）

`fallback()` 原来返回 `listOf(Ann(chunk.text, ...))`，一整块 500 字一次合成。
日志里合成阶段 `04:49 → 05:24` **35 分钟**才做完 2 块（≈17 倍实时）。
改成和「直接朗读」一样按句号切 ≤110 字短段。总音频时长不变，但避免单次超长卡死/OOM。

### ③ 思考档：这台机器上开着 = 标注全废（如实告知，不假装能修）

`limit=1274 outChars=1676`，模型被预算拦腰截断，`` 都没闭合 → JSON 不完整 → gate 必失败。
预算全花在推理上，给到 2048 则每块十几分钟，整本不可等。
改动：检测到思考未闭合即记 `THINK_TRUNCATED` 并跳过重试（省一次几分钟空转）；
设置页文案改成直白警告「正式做书请关掉，只适合试一小块看推理」；
诊断摘要温度显示实际生效的 0.6（思考档禁用贪心），不再显示用户设的 0.0 误导。

### ④ 语音速度的物理上限

121 秒音频 / 35 分钟合成 ≈ **RTF 17**。这是 1.7B voicedesign 在手机 CPU 上的硬速度，
调参救不了。首版现实用法：只做一两章、或直接朗读短文本。

### 待用户复验

1. 「我的音频」里点试听，确认 5.8MB 那本能听（这是首要的）
2. 关思考再点「开始制作」，看是否不再出现 `NATIVE_PREFILL_FAIL`、块能真正标出角色

### 发布记录（R8）

- `https://raw.githubusercontent.com/zjh1934/tingben-apk/main/app-debug.apk`
- 17,553,352 字节 / CRC 全过 / `versionName=1.0.0+14` / 签名不变，**直接覆盖安装**
- 7 个 arm64 .so、无 .gguf、无 INTERNET

## R9 · 用户插话：点停止一点用都没有 / 外部打开方式 / epub

### ① 停止彻底失效（主 bug，日志钉死）

```
07:22:17 NATIVE_PREFILL_BEGIN tokens=684
07:22:50 PRODUCE_STOP  （用户开始狂点，共 10 次）
07:23:53 NATIVE_PREFILL_DONE   ← 63 秒里完全不响应
07:23:53 NATIVE_GENERATE_DONE outChars=0
07:23:53 GATE_RETRY 块 0 第 0 次   ← 竟然又重新开始
07:23:53 NATIVE_PREFILL_BEGIN ...
```

四处叠加：

1. **prefill 是单次 `llama_decode`，63 秒内不检查取消**。→ 注册 `llama_set_abort_callback(ctx, llmAbortCb)`，让 ggml 内部中断；返回非 0 时若 `g_cancel` 为真走 `NATIVE_PREFILL_ABORT`，不再误报「上下文溢出」。
2. **每次 `llmGenerate` 开头 `g_cancel.store(false)`** —— 停止后 gate 重试又调一次 generate，把停止标志清零，于是死循环。用户点 10 次都没用。→ 复位改到 `Producer.start()` 开头，新增 `nativeCancelReset()`。
3. **取消检查只在块边界，gate 重试在块内部绕过它**。→ 每个 attempt 前后都查，拿到空结果立刻 `halted()` 退出整次制作。
4. **UI 能连点 10 次**。→ 按钮点一次即变「正在停止…」并禁用。

### ② 外部「打开方式 / 分享」

之前只有 App 内 SAF 主动选文件。现在 manifest 加 `ACTION_VIEW / EDIT / SEND`，mime 覆盖
`text/plain`、`text/markdown`、`text/*`、`application/epub+zip`、`application/octet-stream`，
scheme `content` + `file`。在微信、文件管理器、浏览器里点「打开方式 → 听本」直接加进书架；
`SEND` 也接纯文字分享。用 `rememberSaveable` 保证旋转屏幕不重复加书。

### ③ epub 解析：之前确实没做，这轮补了基础版

`Importer.kt` 纯标准库实现：`ZipInputStream` 读全部 entry → `container.xml` 找 OPF →
manifest 的 id→href + spine 的 idref **按顺序**拼（避免章节乱序）→ 去 script/style/标签、
解 HTML 实体、标题行保留（ChapterSplitter 靠「第X章」认章）→ 纯文本。
txt/md 走 UTF-8，乱码比例高时自动换 GBK。主动导入也改走同一个 Importer，选 epub 不再乱码。

⚠️ 基础版：不处理加密 DRM、不读内嵌目录跳转、复杂嵌套 OPF 可能乱序；CSS/脚注/图片一律忽略。
复杂 epub 若章节乱，先用 txt。

### 待用户复验

1. 点「开始制作」→ 立刻点「停止」→ **几秒内**应变成「已停止」，不再又跑起来
2. 从文件管理器/微信对一本 txt 点「打开方式」→ 能看到「听本」→ 选了自动回书架
3. 对 epub 做同样操作 → 能加进书架且章节不乱

### 发布记录（R9）

- `https://raw.githubusercontent.com/zjh1934/tingben-apk/main/app-debug.apk`
- 17,554,312 字节 / CRC 全过 / `versionName=1.0.0+15` / 签名不变，**直接覆盖安装**
- 7 个 arm64 .so、无 .gguf、无 INTERNET
- `nm -D` 复核 **10 个 JNI 符号**（新增 `nativeCancelReset`）；dex 内含 `Importer` 与 epub 解析逻辑

## R10 · 用户实测：导入按钮点不动 / epub 解析不了 / 按章拆分有没有

三问都成立，而且第二个是**架构错**，不是解析细节没调好。

### ① 导入按钮点不动

`pickBook.launch(arrayOf("text/plain", "*/*"))` 混传具体 + 通配 mime，在小米/MIUI 的
DocumentsUI 上会静默返回（表现就是点了没反应），且回调里 `uri == null` 直接吞掉，用户看不到任何反馈。

已改：
- 只传 `arrayOf("*/*")`
- 抽 `importBook(uri)` 局部函数，主选择器与**兜底选择器**共用；`OpenDocument` contract 抛异常时
  用显式 `ACTION_OPEN_DOCUMENT` Intent（`StartActivityForResult`）再开一次
- 点击、取消、失败、导入失败**全写事件日志**并给 Toast，不再静默
- 导入失败提示里带真实原因（之前一律「换个文件试试」，epub 失败看不出是乱码还是缺 OPF）

### ② epub：之前是「拼成一坨再猜标题」，难怪解析不了

四个硬伤：

1. **章节靠正则猜「第X章」** —— 中文 epub 大量用「楔子 / 序 / Chapter 1 / 一、初遇」，
   正则全漏 → 整本只剩 1 章 → **按章制作等于不存在**。
2. **硬解 UTF-8** —— 老中文 epub 的 XHTML 常是 GB2312/GBK，直接满屏乱码。
3. **`<head>` 整段删掉** —— 标题常在 `<title>` 里，被一起丢了。
4. **为探测 zip 头二次 `openInputStream`** —— ACTION_SEND 的一次性 URI 第二次开常常失败，
   无扩展名 epub 被误判成纯文本 → 乱码。

已重写 `Importer`：

- **只读一次流**，读成 bytes 后按 `PK` 头判 zip；`ZipInputStream(ByteArrayInputStream)` 稳定读全部 entry
- **按 XML 声明 / meta charset 解码**，UTF-8 / GB18030 / Big5 自适应
- **一个 spine item = 一章**（顺序由 OPF 保证，绝不乱序）；标题优先 `toc.ncx` → `nav.xhtml`
  → `<h1~h3>` → `<title>` → 文件名 → 「第 N 节」
- 输出用私有区字符 `\uE000` 打**显式章节边界**；`ChapterSplitter` 见到就按它切，
  不再依赖任何正则；txt 仍走原正则（并扩展了楔子/序/Chapter N 等常见标题）
- `<title>` 内容不再混进正文被朗读；spine 里的 `nav.xhtml/toc.ncx` 跳过，不生成「目录」章

### ③ 按章拆分：现在真的有了

制作页原来写死 `chapters.size in 2..40` 才显示勾选列表，长书根本没法选单章。
改成默认列出 20 章 + 「显示全部 N 章」，几百章也不刷屏；列表项带序号和标题。
导入 Toast 会显示 `epub · N 章`，一眼能确认拆对了没。

### 本地验证（不是空想）

`samples/解析测试.epub` 故意混排 UTF-8 + GBK、标题用「楔子 / Chapter 2 / 尾声」、
其中一章没有 `<h>` 只能靠 toc。等价算法跑通：
**spine 顺序正确、3 章、乱码 0、书名未混进正文、GBK 章中文正常、toc 标题全部命中**。

⚠️ 仍是基础版：不处理 DRM、脚注、图片、CSS、加密字体；复杂嵌套 OPF 可能个别章缺失。

### 待用户复验

1. 书架右上「＋ 导入一本书」点了是否弹出系统文件选择器（不弹会有 Toast 说明原因）
2. 选 epub → Toast 显示「epub · N 章」→ 进制作页看章节列表标题是否正确、能不能只勾 1 章
3. 从别的文件 App 对同一 epub 点「打开方式 → 听本」也能加进书架

### 发布记录（R10）

- `https://raw.githubusercontent.com/zjh1934/tingben-apk/main/app-debug.apk`
- 17,570,704 字节 / CRC 全过 / `versionName=1.0.0+16` / 签名不变，**直接覆盖安装**
- 7 个 arm64 .so、无 .gguf、无 INTERNET
- dex 抽查命中 `BOOK_PICKER_LAUNCH` / `CHAPTER_SEP` / `GB18030` / `toc.ncx`，确认新逻辑进包
- 回归样本 `samples/解析测试.epub` 已入仓库（1.9KB，混排 GBK+UTF-8，3 章）

## R11 · 一次改六处 + 打包前联动验证

用户原话：「你跑一轮改完东西不做验证、不去检查，有些问题是改一处联动很多处的」「打包慢就每次多改一点再打包」。
本轮按此执行：**六组改动一次性落地 → 60 项联动检查全过 → 才推一次 CI**。
C++ 未改，走原生缓存，run 16 起已验证缓存命中，不再重复 4~7 分钟交叉编译。

### ① 外部「打开方式 / 分享」加不进书架（真 bug，四处叠加）

| 根因 | 修法 |
|---|---|
| App 已在后台时，MIUI/文件管理器把新 intent 投到 `onNewIntent`，旧代码只读 `onCreate` | `launchMode=singleTop` + 重写 `onNewIntent`，Activity 持 `MutableStateFlow`，onCreate / onNewIntent 都推 |
| 微信/QQ 分享常只给 `clipData`，旧代码只查 `data` / `EXTRA_STREAM` | 三来源全兜：`data` → `EXTRA_STREAM` → `clipData` |
| 外部 content:// 未 `takePersistableUriPermission`，读流时被系统拒 | 统一导入函数里补持久授权 |
| 连续两次分享同一文件，`StateFlow` / `mutableStateOf` 因「值相同」吞掉第二次 | 推流时加时间戳，消费时剥离 |
| 没拿到 Uri 时静默无反馈，用户分不清「没点到」和「没给文件」 | 三态日志 `EXTERNAL_INTENT / EXTERNAL_IMPORT_EMPTY / BOOK_IMPORT_FAIL` + 明确 Toast |
| `file://` 分享在部分 ContentResolver 上打不开 | `Importer.read` 直接读文件兜底 |

主动选择器、兜底选择器、外部打开方式**共用一条 `importBook()`**，不再三套逻辑各写一遍。

### ② 哭声 / 笑声 / 叹气（联网调研，见 `docs/NONVERBAL_RESEARCH.md`）

**结论：当前 1.7B VoiceDesign 做不出「独立一段笑声/哭声音效」，但能做「哭腔、气声笑、先叹一口气再说话」。**

- 它吃自然语言 instruct（官方示例就有「带着明显哭腔」），**不解析** `[laughter]` 等行内标签，写了会被当字念出来
- Qwen-Audio-**3.0** 才有 86 个细粒度标签，**是另一代模型**，不是手上这个 GGUF
- 别人三条路：Alexandria 写可发音拟声词（效果最好，但会往成品加原文没有的字，**和我们逐字契约冲突**）；CosyVoice3 原生标签（要换引擎、重下模型）；音效库拼接（自然度差，最后备选）

本版落地 `core/NonVerbal.kt`：扫描**原文已有**的哭/笑/叹/抽泣/咳/喘/颤描写 → 只在合成前给 `instruct` 追加一句导演指令，并明确「不要另加字词」。**`text` 一个字不动，逐字 gate 完全不受影响**。设置页开关（默认开）、诊断摘要带状态、音频调试新增「试哭笑叹气」按钮可**不跑整本直接试听**。

⚠️ 期望管理：更可能是语气贴近，不是凭空多出一段独立录音；真机效果待听。二期才考虑拟声词或换引擎。

### ③ 书架刷新
下拉刷新条（与列表滚动隔离）+ 顶栏「⟳ 刷新」按钮，双保险；刷新重读 `BookStore.list` 与模型就绪状态。

### ④ 进度页下滑收敛 / 上滑打开
核心进度（大号百分比、阶段、心跳、停止按钮）永远在；阶段条、思考过程等细节整块收起。手势条独立 + 「收起 ↑ / 展开 ↓」按钮兜底。

### ⑤ 视觉现代化
冷灰底 + 靛蓝主色 + 明确层级；卡片 20dp 圆角 + 轻阴影；底部导航选中胶囊；顶栏产品名 + 定位副标题；页面主标题统一 23sp。去掉旧版淡紫调试台观感。

### ⑥ 打包前联动验证（本轮新增，60 项全过）

按用户要求，改完不直接发包，先跑本地检查：

1. **Kotlin 括号状态机**：8 个 .kt 文件，三引号/字符串模板/注释/字符字面量正确处理，大括号与圆括号净值均为 0（第一版正则检查误报，已换成状态机复核）
2. **外部导入链路 13 项**：singleTop → onNewIntent → 三来源 Uri → 时间戳 → 消费剥离 → 三态分支 → importBook → 持久授权 → Importer → BookStore.add → refresh++ → file:// 兜底
3. **非语言表达链路 13 项**：apply 不改 text → Pipeline map → toSpeak 循环 → Prefs 开关 → NONVERBAL 日志 → prompt 禁标签/禁补字 → 设置页开关 → 试音按钮 → 试听跟随文件名
4. **刷新/折叠 7 项**、**现代化与全局约束 6 项**（无 INTERNET、包名一致、无旧写法残留）
5. **Importer 内部一致性 6 项**：一次流、plainToText 签名与调用、epubToText 返回 Pair、ByteArrayInputStream、编码自适应、CHAPTER_SEP 边界

另：包装后还会做 `unzip -t` CRC / versionName / 7 个 arm64 .so / 无 .gguf / dex 字符串抽查（发布后补记）。

### 待用户复验
1. 从文件管理器/微信对 txt 或 epub 点「打开方式 → 听本」→ 应自动回书架并 Toast「已加入书架」
2. 设置页「音频调试 → 试哭笑叹气」→ 点试听，判断是「语气贴近」还是「没差别」
3. 书架下拉 / 点 ⟳ 能刷新；进度页下滑收起、上滑展开

## R12 · 长任务单独成区 + 退出/被杀后找得回来

用户补充：「跑长任务应该有单独的一个区域管理任务」「自动退出、被后台杀掉、我退出以后，之前跑的任务就没有了，找不到了」。

### 之前为什么「没了」

`Producer._state` / `busy` 全在内存里。进程一被回收，界面回到初始状态；书架只显示书，没有任何「上次跑到哪」的入口。
**但块文件其实一直在 `work/` 里**——数据没丢，是界面把用户挡在门外了。而且标注 JSON 是直接 `writeText` 的，写入途中被杀会留下半截文件，重启后会被当成「已完成块」跳过。

### 改了什么

| 层 | 做法 |
|---|---|
| 任务书签 `TaskResume.kt` | 记录 bookId / 书名 / 选章 / direct / **think 档位** / total / done / phase / output / 时间；原子 `.part → rename` |
| 每块落盘 | `AnnCache.write` 改原子落位；开工先 `cleanup()` 清 `.part`；`emit()` 每完成一块刷新书签 |
| 状态如实 | running → stopped（用户点停止）/ interrupted（异常或进程重启，绝不假装还在跑）/ done |
| 启动恢复 | `TingbenApp.onCreate → Producer.restore()`：把 running 改成 interrupted，以**磁盘实况**重算进度，推给界面 |
| 独立任务页 | 底栏第 4 个 Tab「任务」：一本书一张卡，进度条、状态徽标、最后更新时间；继续制作 / 从头重做 / 放弃 / 查看实时进度 / 去试听 |
| 全端入口 | 书架「有没做完的任务」卡 + 跨页常驻条「《书名》有没做完的任务 · 去任务」 |
| 档位一致 | 续跑传 `thinkOverride=t.think`，否则 bucket 不同会读空缓存、整本重跑；书被删时明确提示不能续 |

### 诚实边界

Android 不允许 App 在后台长时间占 CPU 跑模型，锁屏/切走久了进程会被系统回收，**这是系统行为**。
本版不做「后台自动接着算」（前台服务也扛不住 2.5GB 模型 + 数小时解码，且会被杀），做的是：
**任务不丢、随时找得到、点一次从停下的那块续跑，前面几小时的活不白干**。任务页里把这句话直接写给用户看。

### 验证

9 个 .kt 括号状态机 + 60 项联动（书签写读/恢复/清、档位透传、4 Tab、上一轮外部导入与拟声链路回归）全过。
C++ 未改，走原生缓存。

### 待用户复验
1. 开始制作 → 跑几块 → 直接划掉 App / 强制停止 → 重开：书架卡 + 常驻条 + 「任务」页都应看到《书名》和进度
2. 点「继续制作」→ 应跳过已做块（日志里出现 `TASK_RESTORE` 和「N 块直接续上」）

### 发布（run 17）

```
https://raw.githubusercontent.com/zjh1934/tingben-apk/main/app-debug.apk
```

本地复核：`PK` 头、zip CRC 全检 OK、无 .gguf、7 个 arm64-v8a .so、153 个条目、17,603,743 字节。
C++ 未改，「交叉编译两个推理引擎」被跳过 → 这轮没有重复 4~7 分钟原生编译，正是用户要的「多改一点再打包」。

## R13 · 音色模块调研：不手搓，先扒别人的实现

用户口径：「不希望你纯手搓代码，要去搜别人是怎么写的，手搓的不是不好，是不够好」。

**本轮一行业务代码没动、没发包**，固定地址仍是 `1.0.0+17`。

### 交付

- `docs/音色模块_开源实现调研.md`：300 行多源交叉，含 4 个模块的可抄文件、许可证、改动点、联动风险、未核验项标注
- `docs/音色模块_移植清单.md`：把每个改动点钉到具体源码文件 + 8 项联动检查 + 5 步下轮顺序
- `reference/`：新抓 4 个上游仓库 16 个源码文件 + `精选_Kotlin现成件/` 11 个本地现成件

### 关键更正

调研报告曾写「Android 端没找到可直接编译的现成 Kotlin 文件、组件只能自己写」——**被本地已有资料推翻**：
`reference/OfflineLLM/` 有 Kotlin Compose 的 `SettingsRepository.kt`（采样参数常量集中）+ `SettingsScreen.kt`；
`reference/ReaderVoice-Mobile/` 有 `CharacterProfileStore.kt`（角色档案 JSON 原子落盘 + USER_LOCKED）、
`CosyVoiceVoiceProfile.kt`（schemaVersion/profileHash）、`CharacterManagerActivity.kt`（角色管理页）。
⇒ 下轮这四块**全部有现成 Kotlin 可抄**，Alexandria 只补别名算法，PocketPal 只补配置版本迁移。

### 已核实的硬事实

- `qwentts.cpp/src/sampling-defaults.h`：9 个默认值（temp 0.9 / top_k 50 / top_p 1.0 / rep 1.05 / max_new 2048 / subtalker 同 0.9,50,1.0）
- 四个示例脚本实锤：voice_design 用 `--instruct`、custom_voice 用 `--speaker vivian`、base/clone 用 `--ref-spk/--ref-rvq/--ref-text`
- `generate_personas.py` 里 `alias_of` / `_token_jaccard` / `_resolve_to_canonical` / `_NARRATOR_LABELS` 全部存在

### 下轮队列

按移植清单 5 步走：配置中心 → JNI 扩参 → 固定音色描述 → 角色表 → 两步克隆只登记。
每步跑 8 项联动检查（缓存 key 失效、断点分桶不动、模式非法编译期挡住、JNI 三处签名同步、逐字 gate、诊断日志、APK 闸门、USER_LOCKED）。

## R14 · 音色模块落地（照抄现成件，非手搓）+ 后台保活调研

### 本轮代码（5 步清单里的 1~4 步）

| 步 | 抄谁 | 落地 |
|---|---|---|
| 1 配置中心 | OfflineLLM `SettingsRepository.kt` + qwentts.cpp `sampling-defaults.h` + PocketPal 版本化 | `Prefs.kt` 重写：`TtsKnobs` 9 参数、常量集中、`SCHEMA_VERSION=2` 宽容迁移、`voiceStamp()` |
| 2 JNI 扩参 | 上游 `qt_tts_default_params → 逐字段覆盖` + ReaderVoice `VoiceDesignEngine.kt` 封装形状 | `NativeEngine.ttsSynthesize(handle,…,knobs)` 包装 → `ttsSynthesizeRaw`；`tingben_native.cpp` 覆盖 9 字段 |
| 3 instruct 分层 | Qwen3-TTS 官方 `voice_design.py` / `examples/tts.sh` | 新增 `VoiceProfile.kt`（身份层 + 本段情绪层）、`prompt_zh.txt` 禁止 4B 写音色身份 |
| 4 角色表 | ReaderVoice `CharacterProfileStore.kt`（USER_LOCKED）+ `CosyVoiceVoiceProfile`（hash）+ Alexandria `generate_personas.py`（别名） | 新增 `VoiceCast.kt`、`CastScreen`；canonical/alias/锁定/合并/自动别名检测 |
| 5 两步克隆 | Qwen3-TTS README | **只登记不实现**（要同时持 VoiceDesign + Base 两套权重） |

### 8 项联动检查（本地静态全绿）

- **缓存 key 失效**：不改 `AnnCache.bucket` 签名（避免牵动 TaskResume / realProgress），改用 `work/voice_stamp.txt`：`voiceDesc + castHash + knobsHash + templateVersion` 变了就删旧 WAV、保留标注。第一次写指纹不删（默认配置下 instruct 与旧版完全一致，续跑不白费）。
- 断点分桶 / `TaskInfo` / `Producer.start(thinkOverride)` **一行没动**
- 模式非法：voice_design 恒传 `speaker=null`（传了会被 `QT_STATUS_MODE_INVALID` 拒）
- JNI 三处签名同步：Kotlin external 15 参 ↔ C++ 17 参（含 env/obj）已脚本核对
- 逐字 gate 未动：`anns.joinToString("") { it.text } == original`
- 诊断：新增 `VOICE_MODE / VOICE_INVALIDATE / VOICE_UNMAPPED / VOICE_DESC / VOICE_CAST / VOICE_ALIAS`
- 无 INTERNET / 无 .gguf / 闸门照旧；结构自检 18 项通过
- USER_LOCKED：角色页「保存并锁定」后 `reconcile()` 不覆盖

### 有意偏离清单（如实登记）

清单写「未映射角色暂停并提示确认」。实装改为**不暂停长任务**：未映射角色沿用全局音色，记 `VOICE_UNMAPPED` 并在角色页可确认。理由：暂停会牵动 Producer 状态机、任务书签、断点恢复三处，风险大于收益；「不静默」由事件日志 + 角色页保证。

### 后台保活 / 浮窗（只调研，未写一行功能代码）

`docs/后台保活与浮窗_调研.md`。**总判断：两个想法都不成立**——无障碍不是保活 API（官方仅用于辅助残障用户，非辅助用途有 Play 下架与 Android 13+ 受限设置风险）；`SYSTEM_ALERT_WINDOW` 浮窗不提升 `oom_adj`、不免 Doze、不替代 FGS 通知。
正确路线：FGS（`dataSync`，Android 15 起约 6h 超时）+ 可见通知 + 分块 `PARTIAL_WAKE_LOCK` + 电池优化白名单引导 + 既有逐块落盘/原子书签。浮窗只能做「用户主动打开的观察面板」：能拿 App 内存、整机 CPU、温度；**GPU 利用率无稳定公开 API**。小米 24117RK2CC/HyperOS 具体冻结行为标「待核」，需真机实验。

### 状态

本机静态自检全绿；代码推 `main` 后由 CI 编译（改了 `tingben_native.cpp`，原生缓存 key 会 miss，本轮会全量重编一次，之后稳定）。固定地址在新 run 绿之前仍是 `1.0.0+17`。

### R14 验证结果（CI + 拆包硬验证）

- **CI run 19 全绿**：结构自检 → SDK → 原生缓存/完整性 → 交叉编译（77s，只重编 glue）→ 引擎守门 → assembleDebug（75s）→ APK 闸门 → Release + 直链同步，17 步全部 success。
- **APK 拆包硬验证**（不是只看 CI 绿灯）：`v1.0.0-19` / 17,653,117 字节 / 7 个 arm64 .so / 无 .gguf / 无 INTERNET。
  - `libtingben_native.so` 里**确实含** `Java_com_tingben_app_engine_NativeEngine_ttsSynthesizeRaw` —— 排除旧项目那种「编译过了但 JNI 找不到实现」的假绿。
  - dex 里含 `VoiceCast / VoiceProfile / TtsKnobs / voice_stamp / VOICE_INVALIDATE / VOICE_UNMAPPED`。
  - `assets/prompt_zh.txt` 含「不要描述音色身份」新约束。
- 直链 `raw.githubusercontent.com` 在本环境返回 502（网关限速），改走 GitHub API 取回同一个包做拆包验证；用户浏览器直下不受影响。

### 真机待验（零证据，如实说）

试合成一句 → 填一个全局音色听差别 → 角色页给某个角色写描述 → 跑一小块看 `VOICE_MODE` / `VOICE_INVALIDATE`。

## R15 · 「只跑第一段 / 上下文分配」代码核查

用户怀疑逐块喂模型时第二段接不上、上下文分配有问题。**逐行读代码后的结论**：循环本身没有「只跑第一段」的写法，但确实有 4 个会让人（和日志）看成接不上的真问题。

### 循环本身（没问题，但说明清楚）

`Producer.run()` 里 `for ((i, chunk) in chunks.withIndex())`：每块独立 `buildPrompt → llmGenerate → parse → gate → AnnCache.write → emit(i+1/N)`；`roster` / `recent` 逐块累积并拼进下一块 prompt；续跑时 `AnnCache.read(work, i)` 命中就直接读盘，照样喂上下文。**不是只跑第一段**。

### 真问题 1 · prefill 期间零反馈（用户感知的根因）

`llmGenerate` 的 prefill 是**单次 `llama_decode`**，真机实测 63~115 秒；这期间 native 只有 `NATIVE_PREFILL_BEGIN`，没有 DONE；Kotlin 又只在整块完成后才 `emit(i+1/N)`。所以界面长时间停在「已标注 1/N」或纹丝不动 → 看起来像卡在第一段。
**修**：加纯 Kotlin 心跳 `ANN_WAIT`（每 5s 一条，只写事件流不碰状态机）、每块 `ANN_BLOCK_BEGIN / ANN_BLOCK_END`（带 i/N、原文/ prompt 字数、预算、nCtx、角色表、近邻数、是否续上）。以后日志一眼能看出第二块到底有没有开跑。

### 真问题 2 · 思考截断保护是个永远进不去的分支

`if (thinkOn && raw.contains("<think") && !raw.contains(""))` —— **`contains("")` 恒真**，取反恒假。思考被预算截断后仍会 `GATE_RETRY` 重跑同样 prompt，白烧几分钟。
**修**：`Annotator.THINK_CLOSE = "</think>"` 常量，判断改为「没有闭标签 = 截断」。第一次写常量时又差点误写成空串，已用脚本断言 `THINK_CLOSE == "</think>"` 拦下。

### 真问题 3 · 位置是否真的归零，原来靠猜（上下文分配的核心）

`llmGenerate` 开头 `llama_memory_clear(mem, true)` 清 KV，但 prefill/decode 都用 `llama_batch_get_one` —— `llama.h` 明写它的位置「由 llama_decode 自动追踪」且标注 **avoid using it**。清 memory 是否连带重置内部位置计数，代码里无法保证；若不归零，第二块 prefill 的 pos 会从第一块末尾累加，直接撑爆 4096 窗口（+13 真机日志 `04:49:01 tokens=592 立刻 NATIVE_PREFILL_FAIL` 正是这个形态）。
**修**：抄 OfflineLLM `LLMInference.cpp` 的 `batchAdd`（显式 `batch.pos[i] = pos`），prefill 每个 token 位置从 0 递增，decode 每 token 位置 = `promptTokens + i`，**完全不依赖内部状态**；并在 clear 后立刻 `llama_memory_seq_pos_max(mem, 0)` 写一条 `NATIVE_KV_RESET posMax=?`，让归零与否成为可查的硬证据（-1 才是真归零）。

### 真问题 4 · 全选章节会开两套缓存桶

「全选」传的是全量 `Set<Int>`，而「不勾选」传 `null`，`AnnCache.bucket` 因此算出 `0_1_2...` 和 `all` 两个桶 —— 同一本书整本跑过一次后点全选，读不到缓存，从 1/N 重跑，看起来又「接不上」。
**修**：`selected.size == chapters.size` 一律归一化为 `null`（4 处：hasCached / 开始制作 / 直接朗读 / 缓存查询）。

### 上下文分配：够用，但「接得薄」（设计取舍，不是 bug）

- `n_ctx=4096`、`n_batch=n_ctx`、`n_ubatch=512`；native 每次算 `avail = nCtx - promptTokens - 8`，`limit = min(budget, avail)`，并把 `avail/limit` 写进 `NATIVE_PREFILL_BEGIN`。真机 prompt 约 600~700 token、快速档预算 ≤1024，合计约 1700，余量充足。
- **但每次生成都清 KV，模型看不到前面任何一块的原文**。跨块只靠「角色名表 + 最近 3 条标注」拼进 prompt，而且这 3 条是**上一块末尾**的 3 句，不是 3 块各一条。所以角色归属跨长段容易漂 —— 这是 Alexandria 的分块取舍，不是崩溃；要补得改 prompt（如「最近 3 块各取首尾 + 别名表」），会改变 4B 输出，留到下一轮单独做，别和这次 native 改动混在一起验。
- 4B 输出仍受字符级 gate 约束，逐字红线未动。

### 顺手

`EventLog.log` 加 `@Synchronized`：心跳协程与 JNI `nativeNote` 并发写同一个文件，原来 `appendText` 可能交错出半行。

### 状态

本地静态自检全绿（括号 / THINK_CLOSE 断言 / 11 个 Kotlin / JNI 符号 / 4 处归一化 / 逐字 gate / 无 INTERNET）；本轮改了 `tingben_native.cpp`，CI 会全量重编原生引擎。真机待验：跑两小块，看事件流里是否连续出现 `ANN_BLOCK_BEGIN 1/2 → ANN_BLOCK_END 1/2 → ANN_BLOCK_BEGIN 2/2`，以及 `NATIVE_KV_RESET posMax=-1`。

### R15 验证结果

- **CI run 20 全绿**，`v1.0.0-20` / 17,654,062 字节。
- **拆包硬验证**：`libtingben_native.so` 含 `NATIVE_KV_RESET` / `posMax` / `llama_batch_init` / `llama_batch_free`，**不含** `llama_batch_get_one` 符号引用（确认新 pos 路径真的编进去了，不是注释改改）；dex 含 `ANN_BLOCK_BEGIN / ANN_BLOCK_END / ANN_WAIT / THINK_CLOSE / THINK_TRUNCATED`；7 个 arm64 .so、无权重、无 INTERNET。
- 真机待验（零证据）：跑两小块，事件流应连续出现 `ANN_BLOCK_BEGIN 1/2 → ANN_BLOCK_END 1/2 → ANN_BLOCK_BEGIN 2/2`，且 `NATIVE_KV_RESET posMax=-1`。若 posMax≠-1，说明位置没归零，但显式 pos 已让第二块不再依赖它。

## R16 · 标注提示词重设计（不等真机成品，直接推进）

用户明确：不再等成品验证，直接改 prompt 方案；且指出「不是简单替换，架构也会跟着变」，要求先研究清楚设计。本轮 = 多源研究 + 完整设计 + 最小闭环落地。

### 研究结论（底稿 `docs/标注提示词_多源研究报告.md`）

- 逐字保真只能由程序做字符级 gate，prompt 只负责降低失败率；**契约定义前置 → 负面清单**优于单写「不要改写」。
- 一个**同形 one-shot** 对 4B 最有效；WRONG/RIGHT 只留 2 条最高频反例；结尾只封口、不长篇重申（解码到几百 token 后模型无力回头改前文）。
- 小模型回显平行 JSON 数组会循环（`antioch/annotator.rs` 原注释），根子在「让 4B 同时复现原文 + 切分 + 归属 + 写指令」。
- 上下文按「活跃角色 → 紧邻前因 → 最近 3 条 → 目标块」摆放；**post-cue 只做条件化二级 pass**，不免费占 prefill。
- 本地 `llama.h:1527` 确有 `llama_sampler_init_grammar`（GBNF）；但 GBNF 只守语法壳，不保证 text 逐字等于原文，也不等于云端 `json_schema/strict`。
- 两端侧硬事实：默认非思考（思考吃预算致 JSON 截断）；第二轮整块 review 在 9s/token 下通常不划算，程序化 check_text_loss 才是必需的那第二遍。

### v2 模板（`app/src/main/assets/prompt_zh.txt`，旧版存 `prompt_zh_v1.txt`）

硬契约前置 → 输出格式（固定字段顺序 + 条数上界）→ instruct 四槽（意图/强度/节奏/发声，禁音色身份、禁方括号、每行情绪写全）→ 2 条反例 → 1 个 one-shot；user 改 XML 四分区。system 795 → 968 字（净增 173，已从初稿 1246 字压下来）。

### 联动落地（本轮只动四处，native / 状态机零改动）

1. `Annotator.buildPrompt` 加 `contextBefore` 分区、近邻 24 → 40 字、**占位符泄漏自检**（新增占位符忘了替换会写 `PROMPT_PLACEHOLDER_LEAK`）
2. `Producer.run` 调用点传上一块末尾 120 字作紧邻前因（续跑读盘的块也照样传，保证重跑/续跑上下文一致）
3. 开工写 `PROMPT_VERSION v2 sha=… sysChars=…`，哪版 prompt 出的标注可追溯
4. 文档：`docs/PROMPT_REDESIGN.md`（9 个联动部件清单、A/B/C 三路线、缓存作废分级与版本表、验证指标、代价）

### 明确没做（有理由）

- **prompt hash 进缓存主键**：`bucket()` 牵动 `Producer` / `TaskCenter` 续跑 / `realProgress` 三处，与路线 C 一起单独一轮做，不夹在改文案的轮次里。
- 路线 B（确定性切分 + LLM 只写短标签）、路线 C（GBNF）：给足蓝图，等 A 的真机数据再拍。
- **不预设「角色漂」**：用户还没听过成品，跨块 speaker 一致性只登记为观测量，不当作待修 bug。

### 状态

本地静态自检全绿（模拟装配无残留占位符、分区顺序正确、11 个 Kotlin 括号平衡、逐字 gate 未动、buildPrompt 定义/调用 2 处）。改了 Kotlin → CI 编译 + 拆包核对。真机出声与 v2 效果仍是零证据。

### R16 验证结果

- **CI run 21 全绿**，`v1.0.0-21` / 17,655,812 字节。
- **拆包硬验证**：`assets/` 里 `prompt_zh.txt`(v2) 与 `prompt_zh_v1.txt`(旧版，可回滚) 都在；v2 内含 WRONG/RIGHT/示例/硬契约，v1 旧铁律完整；dex 含 `PROMPT_VERSION` / `PROMPT_PLACEHOLDER_LEAK` / `CONTEXT_BEFORE` / `v2 sha=`；7 个 arm64 .so、无权重、无 INTERNET。
- 真机待验（零证据）：事件流应出现 `PROMPT_VERSION v2 sha=…` 且 `PROMPT_PLACEHOLDER_LEAK` 为 0；对照 v1/v2 各跑一次试音短篇，只比 JSON 一次通过率、gate 通过率、平均 decode token 数、prefill 秒数。

### R16 追加 · 用户提出的产品点（登记，未动代码）

**制作方式应做成正式三档选择，而不是一个 12.5sp 的调试式小按钮。**
现状核查：`Producer.start(directRead=…)` 与 `MainActivity:687` 的「只想先听声音？直接朗读（跳过标注）」**已有**，`run()` 在 `direct=true` 时连 LLM 都不加载（`Pipeline.kt:636` 只在 `!direct` 时检查语言模型），天然 100% 保真、零 4B 时间；分桶也独立（`direct/`）。缺的是**产品形态**：它藏在「开始制作」下面，措辞像"先验链路"，不像一个可选制作方式。

下轮队列：制作页顶部做显式三档 ——
① **直接朗读**（原文切短句 → TTS，最快、纯保真）／② **标注后合成**（过 4B，保真 + 情绪）／③ **改编剧本**（二期，允许改写/拟声/删归属语，表现力优先，**换契约**）。
三档必须共用同一套任务页 / 断点续跑 / 进度呈现，不能各开一条路径。

**逐字保真的定位**：它是①和②的契约，**不是全产品的天条**。③若做，必须同步改 gate（改成 check_text_loss 防漏句）、缓存主键、作废规则与评测集 —— 见 `docs/PROMPT_REDESIGN.md` §3/§5。最危险的是"允许一点点偏差"这种没有客观边界的中间态：文本能 diff，音频不能。

## R17 · 制作方式做成正式选择（逐字保真按用户要求搁置，未碰）

用户：「逐字保真先搁置，把上一轮说的那个功能做上去。」上一轮用户原话是「制作时可以选择过文本模型，也可以不过、直接合成」—— 即**两档**，不是我顺带写的三档；第三档「改编剧本」属换契约，按"搁置"留在文档不落界面。

### 改了什么（纯 Kotlin / Compose，native、gate、prompt、状态机一行没动）

- 新增 `ProduceMode { ANNOTATE, DIRECT }`；制作页顶部 `CardBox「制作方式」`，两张 RadioButton 卡：
  **标注后合成**（默认，先逐块判断谁说的怎么读再合成）／**直接朗读**（原文照读、不过语言模型、几分钟出声）。
- 删掉原来那个 12.5sp 的「只想先听声音？直接朗读（跳过标注）」小字按钮 —— 它像验链路的调试捷径，不像产品选项。
- 主按钮按模式启动 `Producer.start(..., directRead = directMode)`，文案在「开始制作 / 开始直接朗读 / 继续制作」间切换。
- `Producer.start` 内新增 `PRODUCE_MODE` 事件，覆盖制作页 / 任务页 / 进度页续跑**全部入口**，诊断里能直接看出这一本过没过语言模型。

### 联动检查（用户反复强调「改一处联动很多处、不做验证」）

| 点 | 处理 | 结果 |
|---|---|---|
| 缓存分桶 | `hasCached` 原本写死 `direct=false`，切到直接朗读会误显示「继续制作」并点进标注档旧桶 | 改为按 `directMode` 查 |
| 从头重做 | 原本写死清 `direct=false` 的桶 | 改为按 `directMode` 清 |
| 进页面默认档 | 书正以直接朗读跑着，进制作页却高亮标注档 | 读 `TaskResume.load(c)`，同书且 `direct` 时默认 DIRECT |
| 任务续跑 | 进度页 `t?.direct ?: false`、任务页 `t.direct` | 已核对，沿用原模式，无需改 |
| 任务页显示 | `modeText` 已有「直接朗读 / 标注 · 快速档」 | 已核对，无需改 |
| 思考档 | 标注档仍走 `Prefs.thinking`，与 `hasCached` 的 `thinkOn` 同源 | 一致 |
| 逐字 gate / prompt v2 / JNI / 状态机 | 本轮范围外 | 静态检查确认 gate 表达式未变 |

### 验证

本地静态自检全绿（旧按钮零残留、6 个联动关键字齐、11 个 Kotlin 括号平衡、`Producer.start` 4 处调用 + `clearProgress` 4 处均未漏改、`check_structure` 通过）。CI 编译 + 拆包核对结果见文末追加。真机效果仍是零证据。

### R17 验证 · run 22 失败，已定位并修掉（不是代码错）

run 22 **failure**。没有当成「环境偶发、重跑就好」放过去，拉了回写日志 `logs/build-22.md` 逐行读：

```
Caused by: ... InstallFailedException: Failed to install the following SDK components:
    cmake;3.22.1
Warning: An error occurred while preparing SDK package CMake 3.22.1:
    Error on ZipFile unknown archive
```

根因：workflow 的 SDK 步骤只显式装 `platforms;android-35 / build-tools / platform-tools`，**把 cmake;3.22.1 留给 AGP 在 `configureCMakeDebug` 阶段自己下载**；这次 runner 是全新 SDK（连 NDK 27 都是当场装的），sdkmanager 下到了损坏 zip，Gradle 在配置原生构建时炸，报错长得像编译错，其实跟本轮 Kotlin 一行关系都没有。

修法（`.github/workflows/ci.yml`）：SDK 步骤显式预装 `cmake;3.22.1` → 校验 `$ANDROID_HOME/cmake/3.22.1/bin/cmake --version` 真能跑 → 不行就删包重装一次 → 最后 `test -x` 兜底，让失败发生在「装工具」这一步，而不是伪装成编译失败。AGP 发现已装好就不会再自己下。

Kotlin 侧本地静态自检已全绿；CI 复跑 + 拆包核对结果见下一条追加。

### R17 最终验证

- **CI run 23 全绿**（修完 cmake 预装后复跑），`v1.0.0-23` / 17,655,814 字节。
- **拆包硬验证**：dex 含 `制作方式 / 直接朗读 / 标注后合成 / 开始直接朗读 / ProduceMode / PRODUCE_MODE`；旧「只想先听声音」小按钮**零残留**；`assets/prompt_zh.txt` 仍是 v2、旧 v1 保留；7 个 arm64 .so、无权重、无 INTERNET。
- 真机待验（零证据）：制作页点两档各起一次，事件流应出现 `PRODUCE_MODE 直接朗读（不过语言模型）` / `PRODUCE_MODE 标注后合成`；直接朗读档应跳过 LLM_LOAD 直接进合成。

### 下轮队列（未动）

① prompt hash 进缓存主键 + GBNF（路线 C，需连 `bucket()` 三处调用一起改并跑回归）；② 三档里的「改编剧本」= 换逐字契约，按用户要求搁置；③ 用户贴的四条「资源解析服务请求失败」不是坏链：前两条是 hf-mirror 与 HF 主域的 GB 级二进制解析器扛不住，第四条 `github.com/zjh1934/kongzhishouji/...` 是**另一个项目**的 APK，与听本无关。

---

## R18 · 音频页 / 工作看台 / 书架边界（本轮）

用户一次提了三件事 + 一条纪律：**别手搓，去找现成代码改写**。

### 做了什么

1. **音频页**：从「一列 wav + 试听/保存」改成完整播放器 —— 播放/暂停、上一条/下一条、±15s、进度拖动、时长、倍速、状态、关闭、删除。控件集取自 `MiniPlayingBar` 与 Media3 `PreviousButton/PlayPauseButton/NextButton` 语义。
2. **工作看台**：进度页新增可折叠「分段看台」，一行 8 个小胶囊 `1 2 3 4…`（绿=已有声音 / 蓝=已标注 / 空=未轮到），点一格展开原文预览 + 试听这一段。折叠模型改写自 `SSComposeExpandableListView`（MIT），分列方式参照 `ensemble-reads/ChaptersScreen.kt`。数据由 `Workbench` 扫 work 目录，**不新造状态**。
3. **播放队列内核**：新文件 `core/PlayQueue.kt`，改写自 ReaderVoice `CachedAudioPlayer`（队列 + 播完自动下一段）+ AlexExoPlayerMP3 `PlayerController`（snapshot）+ ensemble-reads `PlaybackController`（playFrom/pause/next/prev/setSpeed）。**音频页与看台共用单例**，页面里已无手搓 MediaPlayer。
4. **书架不再膨胀**（两个真因，详见 `docs/播放与看台_移植清单.md` §2）：
   - `BookStore.add` 增 SHA-256 正文指纹，同名同内容重复导入直接返回旧书（`BOOK_IMPORT_DUP`）；
   - 旧版成品写死 `audio/<bookId>.wav`，做第 1 章和第 3 章互相覆盖、音频页又把文件名当书 Id 显示成整本 → 新增 `AudioLibrary`（UUID + `index.json` 记书名/范围/方式/时间）。**书架只放原书，任务页只放制作记录，我的音频只放成品**，三层互不增长。

### 联动检查（逐条过）

| 点 | 处理 |
|---|---|
| 成品路径 UUID 化 | `AUDIO_REGISTER` 事件、`TaskResume.output` 绝对路径照常可用 |
| 章节 sidecar | `${bookId}.chapters.json` → `${成品id}.chapters.json`，不再被下次覆盖 |
| 「去试听」按钮 | 补 `refresh++`，否则新成品列表读不到 |
| 音频页列表 | 改读 `AudioLibrary.list()`，兼容旧版 `<bookId>.wav` |
| 两套播放器抢焦点 | 统一 `PlayQueue`，看台试听用单元素队列，离开看台回音频页时重新 `setTracks` |
| 看台段索引 | `ann_` 与 `chunk_/direct_` 不一一对应，分开如实列，不假装对齐；只列前 60 段 |
| 逐字 gate / prompt v2 / native / 状态机 | 本轮范围外，静态检查确认未动 |

### 验证

- 本地：`scripts/check_structure.py` 18 项通过；两处 UI 函数括号平衡；`segPlayer / currentId / 手搓 MediaPlayer` 零残留；`PlayQueue` 13 个 API 与调用点逐一对照。
- CI 与拆包结果见下一条追加。**真机仍是零证据**。

### 下轮队列

① 段级「重做这一段」（要把 Workbench 从只读升级成可写，牵动 AnnCache 与 Producer）；② Media3 / 锁屏通知 / 后台播放（会动 Gradle 与前台服务，单独一轮）；③ prompt hash 进缓存主键 + GBNF。
