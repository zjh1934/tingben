# Android 端侧「文本 → 本地 LLM 标注 → 本地 TTS → 音频导出」从零重做调研报告

## 摘要

这份调研的结论是：**不要从“做一个 AI App”开始，而要从一条可验收的生产线开始**——普通用户导入纯文本、点一次“开始制作有声书”、看到逐章进度并继续听或导出。已定稿的两个 GGUF 约 4.83GB，APK 必须 ≤100MB 且不带模型，因此模型只能后导入；离线 `.txt` 主路也因此被强制锁定。推荐主运行时为 **llama.cpp + 自建 JNI + qwentts.cpp + 自建 JNI**：二者都能直接吃标准 GGUF，不需要引入 MNN/ExecuTorch 的额外转换，也不需要服务器进程。现阶段没有在 24117RK2CC、Android 16、目标模型组合上取得真机实测数据，因此 llama.cpp 与 qwentts.cpp 的具体性能、KV cache 上限、热降频、两运行时能否并行都是**推定或待验证**，不能写成规格承诺。Android 16 应将 `compileSdk`/`targetSdk` 设为 36，建议 AGP 8.13、Gradle 8.13、JDK 17、Kotlin 2.2、NDK r28 固定小版本、CMake 3.22.1；更激进的 AGP 9.x 仅作后续评估。CI 只需在 GitHub Actions 上完成，本地环境用于接受 PR、编辑文件即可；但首个 arm64-v8a 原生产物仍应由用户在真机验证。

## 一、先把“唯一主路”冻结为产品契约，工程配置才有判断标准

**唯一主路不是五个功能的集合，而是“导入 → 制作 → 听/导出”三个动作。** 参考成熟产品可以看到同一信息架构：`@Voice Aloud Reader` 以“打开内容、朗读、后台听、书签”为中心；Speechify 强调导入、速度、声音与同步高亮；ebook2audiobook 的价值在于逐章转换并保留章节元数据；Alexandria 则是下载与格式转换工具，不是同一个终端听书场景。对本次重做而言，最值得借鉴的是四件事：**内容来源收敛成一个入口、正在处理的对象始终可见、播放与生成共用同一进度、高级能力藏到二级页面**。不要把“工程调试台”“模型文件名”“GGUF 元数据”“JNI 事件流”做成普通用户主页。

必须现在就有的主路是：`选择 .txt（仅 .txt）→ 校验读取权限并保存可持久访问的文档 URI → 自动检查三个后导入模型 → 一键开始 → 逐段生成标注、逐句合成 24kHz 单声道 PCM → 边编码边写入当前章节 → 失败后从最后完成段落续跑 → 完成页提供继续播放和导出 AAC/M4A`。这里故意把“导入一本书”定义为导入**一个纯文本文件**，而不是读取扫描件、DOCX、EPUB、网页或系统剪贴板。用户确实可以复制粘贴短文本，但那只能放在“快速片段”辅助入口，不应与“有声书”共享同一套章节恢复契约。

**正文逐字不改不是 UI 文案，而是一条强测试规则。** “只加导演指令标注”意味着模型输出中不能替用户纠正标点、不能把半角改全角、不能合并空白、不能在句末补字。唯一允许的处理是把原文切分为可独立 TTS 的片段，并为每个片段附加一条可关闭、可审阅、不混入播放正文的注释。验收方法不是看模型自我声明，而是：任意片段的字符偏移范围必须能在原文中精确匹配；串联全部“正文片段”的字符序列必须与原文完全一致；模型文本中若含正文内容，做归一化比较前应直接判失败。这一规则还会反向约束提示词：不能要求模型“润色”“补全”“整理”，只能要求“识别角色、语气、停顿或旁白并输出结构化指令”。

**第一版先交付“可靠”，不要交付“聪明”。** 可以后面再补的包括：多音色、语速渐进、睡眠定时、云端备份、字幕/SRT、封面、朗读跟随高亮、WAV/FLAC/MP3 导出、自动章节标题识别、批量书籍队列、崩溃自动上报。现在就必须有的约束是：无网络权限、模型文件校验、导入 URI 持久化、原子章节落盘、断点恢复、播放音频焦点、前台可见任务、权限和版本锁定。所谓“最小可行”不是把架构删成无法扩展，而是**把可选体验删掉，把正确性留下**。

## 二、最小工具链与运行时基线：一次锁死，不接受漂移

**JDK、AGP、Gradle、Kotlin 必须放在版本目录和 CI 镜像约束里同时锁定。** Android 16 官方要求 `compileSdk=36`、`targetSdk=36`，并建议 Android Studio Meerkat（2024.3.1）或更新版本；Android Studio 与 SDK 是本地可选，但 CI 必须等价安装 SDK Platform 36、Build-Tools 36.x 和对应平台工具。基于 AGP 版本兼容表，下表给出“现在开工”的推荐组合；“保守”与“激进”不是质量分级，而是变更风险与未来兼容性的交换。

| 项目 | 现在建议 | 可以后面再补/换 | 理由与风险 |
|---|---:|---:|---|
| JDK | JDK 17 | 不主动升到 JDK 21/25 | AGP 8.x 稳定，工具链成熟；更高 JDK 可能要求更高 Gradle/AGP |
| AGP | 8.13.x | 9.x 在首个版本稳定后评估 | 8.13 最高支持 API 36.x，迁移面小于 9.x |
| Gradle | 8.13.x | 跟随 AGP 升级表 | AGP 与 Gradle 必须成对验证 |
| Kotlin / KGP | 2.2.x | 3.x 留待 AGP 支持后 | 避免旧 `kotlin-android` 插件与 AGP 9 DSL 冲突 |
| compileSdk / targetSdk | 36 | Android 17 稳定后再升 | Android 16 硬前提 |
| minSdk | 28 | 暂不降 23，也不因 API 29 后限制再抬 | API 29 带来 Scoped Storage/W^X；API 31 带来音频焦点变化；目标机是 Android 16，28 已足够覆盖大量仍可用设备，但最终覆盖率需统计 |
| Build Tools | 36.0.0 | 仅随稳定 patch 升级 | AGP 与官方版本表建议 |
| NDK | r28 固定完整版本号 | r29 先验证 16KB 与链接兼容 | 官方当前文档默认推荐 r28，主版本升级应单独验证 |
| CMake | 3.22.1 | 3.31 需验证 llama.cpp/qwentts.cpp 生成器 | 稳定基线优先；两个上游项目均须重新生成 |
| ABI | arm64-v8a | 不首发 x86_64 | 真机唯一目标；可减少 NDK/CMake 缓存与测试矩阵 |

JDK、AGP、Gradle、Kotlin 的版本约束来自 Android 官方兼容表；Android 16 的 36/36 配置来自官方设置文档；NDK r28 为当前官方历史所列稳定基线。表中“Kotlin 2.2.x”是结合 AGP 8.13 的**选择建议，不是官方明确支持声明**，实际以干净 CI 全量构建和原生测试为准。[1][2][3]

**签名必须离线生成、长期保存，但密钥文件不能进仓库。** 使用一次性的 `keytool` 生成发布 keystore，store/key alias/password 分别存入密码管理器与 GitHub Actions Secrets；CI 只负责用该密钥签名 release APK。调试密钥由 Actions 临时生成即可，绝不能与发布密钥混用。若更换签名密钥，旧用户将无法覆盖安装，因此密钥恢复演练是发布前必须项，而不是运维善后。

**`largeHeap` 不能当作“我有 512MB”的许可证。** 用户真机 heap 约 256MB、largeHeap 512MB，只是 Java 堆上限的线索；模型权重 mmap、JNI 临时缓冲、ggml 计算工作区、KV cache、AudioTrack 与 MediaCodec 缓冲区、系统图形/服务开销均不属于 Java 堆。当前证据只能支持“两个模型加载本身就可能占用约 4.83GB”，无法支持“512MB heap 够用”或“8 Gen 3 的物理 RAM 一定够并行加载”。首版应明确禁止同时把 LLM 与 TTS 全量常驻，采用**标注阶段只加载 LLM、合成阶段只加载 TTS、交接时先释放再加载**的策略。KV cache 属于推理运行时内存，不能预分配成固定“500MB”；它应按上下文长度、层数、头数、量化与并发请求测算，且必须在真机用 `Debug.getMemoryInfo()`、native PSS、fd 数和 OOM 日志实测。

## 三、LLM 推理选型：llama.cpp 直连是主路径，替代方案按代价排序

**标准 GGUF 是本项目最重要的兼容性资产。** llama.cpp 官方定位就是直接运行预训练 GGUF，自动探测 CPU/GPU 特性、上下文、量化内核并维护 KV cache；其 Android NDK 交叉编译文档明确给出 `ANDROID_ABI=arm64-v8a`、`GGML_NATIVE=OFF`、`GGML_OPENMP=OFF`、`GGML_LLAMAFILE=OFF`、`LLAMA_OPENSSL=OFF`，并警告不要把宿主机 CPU 特性误当作目标 CPU。[4] 因此，`mlabonne_Qwen3-4B-abliterated-Q4_K_M.gguf` 只要确属 Qwen3 初版 GGUF、架构元数据能被 llama.cpp 识别，就无需二次转换；这同时避免 MNN/ExecuTorch 转换造成的量化漂移和版本门槛。

| 路径 | 是否直接吃标准 GGUF | 转换/封装成本 | 内存与运行时维护 | 推荐度 |
|---|---|---|---|---|
| llama.cpp + 自建 JNI | 能 | 低：需包 JNI、模型加载、流式 token、取消与资源生命周期 | 中：单运行时、mmap、KV cache 可控 | **首选主路径** |
| llama.cpp 官方 `examples/llama.android` | 能 | 较低：可参考官方示例工程 | 中：仍要裁剪到产品需求 | 可作为首次骨架/对照 |
| MNN-LLM | GGUF 可经 `gguf2mnn.py` 转 `.mnn` | 中：转换、敏感层精度策略、Android build | 中低：MNN 运行时统一 | 有 MNN 技术栈时备选 |
| ExecuTorch | 不能可靠吃 GGUF | 高：导出 `.pte`、XNNPACK/Vulkan 适配 | 中：静态内存规划 | 不做，除非已有 PyTorch 导出链 |
| MediaPipe LLM Inference | 通常限于有限官方模型 | 高/不确定 | 低至中 | 仅快速验证窄模型，不适合两模型流水线 |
| 本机 Termux + llama-server | 能 | 低至中：启动外部进程 | 高：跨进程生命周期、端口绑定、JNI 之外多一层 | 仅开发验证 |
| 应用内捆绑 llama-server | 能 | 中：常驻 HTTP、流式解析 | 高：双进程、双份运行时负担 | 不推荐生产 |

MNN 官方文档确实提供从 GGUF 读取权重以及 `gguf2mnn.py` 转换，并支持 Android 编译开关；这说明它能承载 Qwen，但“能吃”不等于“不用转换”。ExecuTorch 的公开 FAQ 明确回答不能可靠使用 GGUF，需转换为 `.pte`；对只有两个定稿 GGUF、且强调离线无转换的项目，这是不可接受的工程溢价。[5][6]

**qwentts.cpp 不是普通 Android AAR，而是第二个 ggml-native 项目。** Hugging Face 模型卡显示 `qwen-talker-1.7b-voicedesign-Q8_0.gguf` 与 `qwen-tokenizer-12hz-Q8_0.gguf` 必须一起加载，输出 24kHz 单声道、10+ 语言；README 明确 Q8_0 是推荐默认。模型页标称 talker 2.04GB、tokenizer 291MB，但公开信息对 talker 文件大小存在约 2.1GB/2.04GB 两种口径，应以 SHA-256 校验后的实际下载文件为准，不要用模型名中的“1.7B”替代磁盘占用测算。该仓库是 C++17/GGML 项目，从现有公开资料不能证明它提供官方 Android 示例或稳定 JNI 接口，因此不能把“能编译 Linux/CPU”等同于“能直接塞进 App”；它应与 llama.cpp 一样作为 Git 子模块/锁版本 CMake 依赖，由产品代码维护 JNI。

**两个 ggml 运行时若都编译成共享库，符号冲突不是理论风险。** 规避优先级如下：

1. **首选命名空间隔离**：两个上游分别静态链入各自 JNI 模块 `libllm.so`、`libtts.so`，不暴露重复全局符号。通过版本脚本（`.lds`/`-fvisibility=hidden`）仅导出 `Java_` 符号，并对内部 C++/ggml 符号限制可见性。
2. **次选统一单一 ggml 提交**：只有当 qwentts.cpp 的 `GGML_SOURCE_DIR` 能稳定替换成 llama.cpp 的同一 ggml，且 ABI、allocator、线程池与线程局部行为一致时才启用；这属于**高风险优化**，不能用“都是 ggml”推定兼容。
3. **最后考虑 dlopen 隔离**：按手柄加载到不同命名空间，可缓解冲突但增加生命周期复杂度，且 Android 10 以后不得从可写应用目录执行可写代码。
4. **不要链接两个都导出全部 ggml 符号的共享库**：失败模式包括启动崩溃、静默覆盖、不同版本结构体布局不一致。

验收标准是：分别加载单模块、同时加载双模块、连续热切换 20 次均不出现 `EagerThrowable`、地址冲突、JNI 找不到符号或 KV/tensor 状态串扰。任何一次失败都应回退到静态隔离方案。

## 四、TTS、音频编码与断点恢复：以文件安全换“后台继续”的价值

**24kHz 单声道 PCM 是引擎输出格式，不等于用户最终音频格式。** 真机输出建议先用 PCM 驱动 AudioTrack 播放，同时把同一个 PCM 交给编码管道；导出阶段从 24kHz/mono/16-bit 上采样到 48kHz/mono，再由 MediaCodec AAC-LC/HE-AAC 编码。官方支持格式文档确认 AAC 支持 8–48kHz、建议 16-bit，单声道输出无需强行立体声；xHE-AAC 官方示例则显示软件编码器的采样率/声道/码率受设备能力限制。因此 AAC 编码器必须按设备能力查询，不能假定每台 Android 16 设备都支持 24kHz AAC 输入。[7][8]

**“章节内嵌”只能承诺标准 MP4 章节，不能承诺所有播放器都能读。** Android 框架 `MediaMuxer` 支持 MP4、元数据轨和时间戳；Media3 `Mp4Muxer` 的 `Chapter` 元数据条目与 `addTrackReference` 可用于把章节元数据关联音频轨。正确顺序是：先 `addTrack` 音频轨和元数据轨，写样本，最后建立章节到音频轨的引用并关闭 muxer。即使文件结构合法，第三方播放器对 QuickTime/iTunes `chap`、Nero chapter 与 Media3 `Chapter` 的支持并不一致；若发现兼容性断裂，则改为“每章一个 M4A + 一个统一 NFO/章节清单”，而不是继续猜测播放器实现。

| 任务 | 现在必须有的做法 | 可以后面再补 | 关键验收 |
|---|---|---|---|
| 实时听 | AudioTrack 直放 24kHz 单声道 PCM | 音效、均衡器 | 无爆音、欠载、异常后暂停位置可恢复 |
| 长书合成 | PCM→MediaCodec→MP4/M4A | WAV/FLAC/MP3 | 时长、采样率、章节数正确 |
| 章节结构 | 先建 metadata track，写 PCM 帧边界 | ID3/封面 | 每章时间戳可由文件独立复算 |
| 断点恢复 | 章节+段落双索引 | 句子级恢复 | 强杀后重启从最后完成段继续，正文不重不漏 |
| 文件安全 | 临时文件 + fsync + 原子重命名 | 增量云同步 | 断电后旧章节完整，新章节不完整可删 |

**长任务不能只靠前台服务“续命”。** Android 15 起，`dataSync` 与 `mediaProcessing` 前台服务在任意 24 小时内分别限 6 小时，超时后系统调用 `Service.onTimeout()`，必须在几秒内 `stopSelf()`；Android 16 又规定前台服务派生的 JobScheduler/WorkManager/DownloadManager 作业也受配额。适合本项目的不是后台偷偷跑完，而是：**用户在前台点开始后绑定 foreground service + 持续通知，状态写入持久数据库；进程被杀后，下一次用户打开从已完成索引恢复；跨夜运行则允许在 6 小时附近暂停并提示用户点继续。**[9]

后台被杀后的“断点”必须存在 App 私有数据库/检查点中，至少包含源文件 URI、文件指纹、章节顺序、每个章节的起始/结束字符偏移、已完成段落索引、当前音频样本数、TTS 模型与参数、输出文件句柄。SAF 返回的 URI 是非稳定字符串，不能作为文件路径拼接；应在用户选择后用 `ContentResolver.openFileDescriptor()` 建立流，并调用 `takePersistableUriPermission()`，否则进程重启或设备重启后访问失效。长文本也不能一次性读进普通 Java `String`，应采用内存映射或分块扫描加偏移索引。

**音频焦点和播放通知是同一用户任务的两个侧面。** Android 官方建议朗读语音使用 `USAGE_MEDIA` 与 `CONTENT_TYPE_SPEECH`，并请求音频焦点；Android 12+ 的淡出规则对 `CONTENT_TYPE_SPEECH` 有例外，但产品仍要处理其他应用获取焦点、来电、耳机拔出、通知音频路由。前台服务应同时发出媒体样式通知，允许用户暂停/恢复/停止；单纯持有 WakeLock 而不通知用户，会把长任务做成“黑盒耗电”。

## 五、Android 16 与 8 Gen 3 的现实约束：权限最小化本身就是架构

**无网模型导入必须走 SAF，不能依赖硬编码 `/storage/emulated/0`。** 当前信息不足以证明所有文件管理器都返回可持久读取的 URI，因此导入界面应明确写“使用系统文件选择器导入 `.txt`”，并把持久权限、读取失败、文件消失分别提示。若用户选择模型或文本后重命名、移动文件，不要静默猜测；先校验文件大小和 SHA-256，再提示重新选择。模型存放 App 私有目录的理由是：它们不是用户共享媒体、不能被其他 App 打开、不应被媒体扫描，也不应进入备份。APK 则把 `android:extractNativeLibs`、压缩配置与 `*.gguf` 排除规则设为固定项，避免 Gradle 在发行包内再压 4.83GB。

**Android 10 的 W^X 规则会直接杀死“下载 so、复制到数据目录、dlopen”的偷懒方案。** 官方规定目标 API 29 起，从应用主目录执行文件违反 W^X，无法 `execve()` 该目录文件，也无法在可写文件描述符映射的 `.so` 上改可执行页。正确做法是：模型权重从可写目录读取，但推理库作为安装包原生库或经合法只读路径加载；CI 固定 NDK 与编译器，所有 `.so` 随版本发布。任何“本地回环 llama-server”方案若需要下载可执行文件或动态可执行 so，也违反该前提；应用内打包的静态二进制仍是额外进程、额外攻击面与额外内存，不应成为默认路径。

**8 Gen 3 的硬件能力要验证后才暴露给用户。** 官方材料确认该机为 Android 16、arm64-v8a、骁龙 8 Gen 3、单应用 heap 约 256MB、largeHeap 512MB、用户可用存储约 265GB。由此可以推出“存储足以放后导入模型”和“API 应只编 arm64-v8a”，却不能推出“GPU/Vulkan/NPU 一定加速”或“两模型可并发”。建议发布机型兼容表：第一版仅承诺目标机；通过真机验收后增加同 SoC/相近 Android 版本设备；不能只因品牌或 CPU 架构相同就扩大支持。

**minSdk 的选择是功能边界，不是市场口号。** API 28 是建议起点：API 29 的 Scoped Storage/W^X 对文件导入和原生加载有实质影响，API 31 改变音频焦点行为，API 34 要求前台服务类型与权限，API 35 增加 6 小时前台限制，API 36 改变 edge-to-edge、预测返回和任务调度。降低 minSdk 前，要为 SAF、通知、前台服务、音频焦点各准备条件分支；在首个版本人力有限时，不值得为了设备数量引入兼容矩阵。

## 六、无本地编译环境的 CI/CD：让 Actions 成为唯一可信构建入口

**“沙盒不能编译”只意味着开发者不维护 Android SDK，不意味着项目没有构建环境。** 最小 CI 矩阵只需一个 `ubuntu-latest` 作业，输出 arm64-v8a APK；Kotlin lint/单元测试可以与原生构建并行，但签名、APK 大小检查、artifact 上传必须串行。开发者机器只需：Git、编辑器、GitHub CLI、只读依赖锁文件；如果需要本地日志分析，也只安装 `adb` 平台工具，不需要 SDK/NDK/Gradle 全量环境。

建议的单仓库结构：

```text
.github/workflows/ci.yml
gradle/libs.versions.toml
build-logic/
app/
  build.gradle.kts
  src/main/AndroidManifest.xml
  src/main/jniLibs/.gitkeep
native/
  llama.cpp/                # pinned Git submodule or vendored source
  qwentts.cpp/              # pinned Git submodule or vendored source
  llm-jni/
  tts-jni/
scripts/
  prepare-models.sh         # 只生成期望清单、SHA-256、允许/禁止规则
docs/
  model-manifest.json
```

`.gitmodules`、上游 commit、CMake 选项、JNI ABI、输出文件名都应纳入缓存键；CI 在构建前打印 `git rev-parse HEAD`、上游 submodule HEAD、NDK/CMake 版本、编译器标识和原生库 ELF 大小。这样任何“本地编译成功、CI 失败”都能量化差异，而不是比较两个黑盒环境。

**缓存键必须覆盖配置与输入，`.so` 缓存不能只缓存 `.so`。** 正确的恢复语义是：缓存命中时，已恢复内容必须等价于“重新配置 + 重新编译”。上游头文件、CMake 脚本、JNI 源码、submodule commit 都是配置输入；若只缓存 `app/build/intermediates` 或 `.so`，后续 build 可能在缺少头文件时误报成功，或在 CMake 重新生成时产生混合状态。推荐两套缓存：

| 缓存 | 缓存路径 | 主键 | 用途 |
|---|---|---|---|
| 原生完整输出 | `app/build/intermediates/cmake`、`app/.cxx`、`native/**/build-android` | `native-${{ hashFiles('native/**', '.gitmodules', 'gradle/libs.versions.toml') }}` | 增量重建、跨 job 恢复 |
| Gradle/工具链 | `~/.gradle/caches`、`~/.gradle/wrapper`、`$ANDROID_HOME` 安装目录 | `tools-${{ runner.os }}-${{ hashFiles('build.gradle.kts', 'gradle/**', 'scripts/**') }}` | SDK/NDK/CMake 下载加速 |

GitHub 官方明确区分 cache 与 artifact：cache 用于重复依赖/构建产物，artifact 用于工作流结束后仍要查看的 APK、报告、日志；cache 不能替代上传 APK artifact，cache 命中也不等于配置输入一致。[10] 对原生项目，最安全的最小实践是 `key` 使用完整哈希、`restore-keys` 只给同 ABI/配置前缀；若恢复后跳过 CMake configure，必须加入上游 submodule 的二次哈希校验，否则宁可不缓存。

推荐流水线：`检出 → 校验 submodule/pinned source → setup-java JDK17 → 安装 Android SDK/Build-Tools/NDK/CMake 固定版本 → 恢复原生缓存 → Gradle 原生配置与构建 → 单元测试/lint → assembleRelease → 校验无 INTERNET、APK≤100MB、仅有 arm64-v8a、包含固定 .so、无 .gguf → 签名 → 计算 APK SHA-256 → 上传 artifact 与构建报告 → 仅 release tag 创建固定 Release 并上传 APK`。

**构建报告应回写到 PR comment 与 Release 页面，而不是要求用户读 20 万行日志。** 固定公开直链推荐 `https://github.com/<owner>/<repo>/releases/latest`，或给 tag release 使用不变资产名；`latest` 是移动别名，升级时应保留旧版下载位，供签名验证失败或机型不兼容回退。发布 action 的 `upload_url` 可用于追加资产，但在 GitHub Actions 内应优先固定 GITHUB_TOKEN 的最小权限（`contents: write`，其余默认 none），不把仓库 secret 写进日志。签名前必须验证 `versionCode/versionName`、keystore alias 存在、APK 无 `android:debuggable`、无网络权限、无重复原生库；签名后重新计算 SHA-256，并将该值与提交 hash、submodule hash、NDK/CMake 版本、APK 下载 URL 一起落 Release body。

**禁止在 Actions 里下载或提交模型。** CI 可校验模型清单中的 SHA-256、文件名、最小/期望字节数，但 GGUF 不得进源码仓库、不得进 release artifact、不得临时下载进 APK。模型后导入的测试需要真机或受控设备场；若未来做 CI 端侧测试，应使用专门的私有设备 runner，而不是把 4.83GB 模型放进公开缓存。

## 七、AI 助手与用户的分工：AI 做可验证的工件，用户做物理世界裁决

**AI 助手不能拥有“我在真机上验证过了”的语义。** AI 可以设计架构、写配置、生成 Kotlin/JNI/CMake、解释官方文档、设计测试、推断风险、生成诊断提示和变更清单；在没有实机的情况下，它只能说“编译通过、文档支持、风险推定”。用户必须亲自：从官方来源取得模型文件、把模型放进指定私有目录、使用浏览器下载已签名 APK、覆盖安装、在飞行模式下点击主路按钮、观察发热/内存/卡顿、导出音频、用另一播放器抽查章节、复制诊断日志。两者不能用口头“已经好了”互相代替。

| 阶段 | AI 助手全程可做 | 用户必须亲自做 | 可机械验收的标准 |
|---|---|---|---|
| 0. 冻结契约 | 写需求、状态机、提示词规则、验收清单 | 批准“只做 txt、正文不改” | 需求文档与主路流程图一致 |
| 1. 工具链 | 锁版本、写 CI、校验 APK | 生成并离线保管签名密钥 | CI 干净通过；APK 无 INTERNET；签名可验证 |
| 2. LLM | 写 JNI、流式输出、取消、资源释放 | 导入定稿 GGUF、点“生成标注” | 100 条归一化片段拼接等于原文 |
| 3. TTS | 写双模型加载、PCM 播放/编码、章节 muxer | 导入两个 TTS GGUF、真机试听 | 24kHz mono 可听；导出文件可播放 |
| 4. 断点 | 写检查点、原子落盘、恢复 | 杀进程/重启手机后恢复 | 最后完成段无误，进度单调递增 |
| 5. 发布 | 创建 release、报告、hash | 下载并覆盖安装 | APK hash 与发布页一致；覆盖安装不丢签名 |
| 6. 诊断 | 将日志归类成模型/音频/存储/前台问题 | 只粘贴脱敏日志 | 日志不含密钥、正文片段默认可关闭 |

**脱敏边界本身就是产品验收项。** 默认诊断包只允许包含 App 版本、Android 版本、ABI、SoC、构建 hash、模型文件名、文件字节数、阶段、错误码、内存/PSS、fd 数、时间戳和短错误堆栈。默认关闭正文内容、源文件 URI、输出目录、模型绝对路径；开启后必须显式二次确认并展示“将导出以下字段”。日志绝不包含 keystore、签名口令、Wi-Fi、账号、联系人；即便处于无网状态，也要防止用户误把日志贴到第三方聊天或 AI 面板。

建议采用四层日志级别：L0 用户消息（当前章节、速度、预计剩余、下一步）；L1 产品事件（导入成功/失败原因码）；L2 工程事件（模型加载毫秒、PCM 欠载、编码失败）；L3 严格脱敏的调试快照。普通用户 UI 只显示 L0，长按才展开 L1；“复制诊断信息”需两步确认并预览字段数量。这样不会把“完全不暴露错误”推向另一个极端：用户不必读 `dlopen`，但必须知道“模型文件校验失败”“存储不足”“TTS 加载超时”。

## 八、从零起步最容易踩的 10 个坑及可操作规避办法

**1. 先写界面再定义正文契约。** 规避：先冻结“字符级可回溯标注”的数据结构、负向测试集和允许/禁止模型动作，再画页面。任何不能从原文偏移精确还原正文的功能，第一轮就拒收。

**2. 把模型放进 assets/raw 或误以为 APK ≤100MB 还能附带模型。** 规避：仓库与 Gradle 双规则禁止 `.gguf`；CI 用 `unzip -l` 与自定义检查拒绝模型；模型只允许进入 App 私有目录，APK 只带 `.so` 和版本元数据。

**3. 让 MNN/ExecuTorch 成为“看起来更官方”的默认。** 规避：只有在不支持 GGUF、需要特定硬件委托、且收益超过转换和回归成本时才替换；当前默认是 llama.cpp 直吃 GGUF。

**4. 两个 ggml 上游静态链接成同一 so，或同时动态导出 ggml。** 规避：两个 JNI 模块静态隔离、隐藏内部符号；线程池/backend 初始化由模块私有状态管理；统一 ggml 源码只作为有明确测试闸门的备选。

**5. 把上游 master 当依赖。** 规避：submodule 锁 commit，升级做双 MR；记录上游 commit、构建日期、编译选项、模型 GGUF 哈希；不把 `git pull` 放进 CI。

**6. 把 NDK、CMake 版本写进 README 却未固定 AGP。** 规避：版本目录 + `gradle/libs.versions.toml` + CI 环境约束；升级矩阵为 AGP→Gradle→JDK→Kotlin→NDK→CMake→工具链缓存键。

**7. 只缓存 `.so`。** 规避：缓存键包含 CMake/源码/头文件/submodule commit；每次构建打印关键版本；或采用“完整配置产物缓存 + 二次输入校验”。

**8. 用 GitHub Actions cache 代替 artifact。** 规避：cache 加速，artifact 交付 APK 与报告；公开缓存可被 fork PR 访问，绝不放 token/密钥。

**9. 以为前台服务可以让 30 小时有声书稳定跑完。** 规避：前台任务只承载当前会话；检查点保证被杀可恢复；24 小时 6 小时配额到来前提示继续；不做开机自动无限续跑。

**10. 在 Android 10+ 从可写目录 exec/dlopen 动态 so。** 规避：`.so` 随安装包，模型权重只做数据读取；任何“下载轻量模型/插件 so”功能推迟到完成专用安装与代码签名设计后。

其余常见坑也值得一次防住：把 `largeHeap` 误当物理 RAM；假设 MediaCodec 支持 24kHz AAC；假设 MP4 chapter 被所有播放器识别；用文件路径拼接 SAF URI；缓存只按 ABI 不按编译器版本；并发从两个 JNI 模块访问同一模型文件；TTS 输出浮点 PCM 却被当作 16-bit；输出文件未 fsync 就被报告完成；长时间 AudioTrack 不处理欠载；为追速度在后台同时加载两个模型导致 LMK；提示词泄露原文到第三方遥测；把调试日志默认打开而泄露整本书。

## 九、首个版本的可执行推进顺序

**第一个交付物应是“离线契约证明”，不是整本有声书。** 顺序为：PR-0 冻结需求与状态机；PR-1 建空项目、签名、权限和 CI；PR-2 完成 `.txt` 导入与持久 URI；PR-3 完成 llama.cpp arm64 静态 JNI 与文本校验；PR-4 建模型清单、哈希校验与失败码；PR-5 完成标注输出和正文还原测试；PR-6 完成 qwentts.cpp 单模块与 PCM 播放；PR-7 完成 PCM→AAC→MP4 章节；PR-8 完成检查点与恢复；PR-9 完成 L0/L1 用户界面；PR-10 发布首个内部签名版。每合并一个 PR，CI 产出可下载 APK，但只有在真机通过对应验收后才允许进入下一业务 PR。

建议的首版非功能基线：导入 10MB `.txt` 不 OOM；任意 100 段模型输出可精确还原原文；首 token/标注吞吐只记录真机 P50/P95，不写未测承诺；后台强杀后恢复误差不超过一个已完成片段；导出音频时长误差不超过一个音频帧；飞行模式下全程无网络请求；APK 稳定小于 100MB；覆盖安装签名不变化；连续运行到前台服务限额前能正确暂停；两次模型热加载不崩溃。由于目前没有真机实测，这些是**验收闸门，不是性能规格**。

最终判断是：这个项目的真正难点不是把两个 GGUF“跑起来”，而是让长文本流水线在手机后台、低内存、进程被杀、文件移动、热切换和错误重试中仍保持正文精确与音频文件完整。llama.cpp + qwentts.cpp 自建 JNI 提供了最低格式转换成本，但两个 ggml 运行时和 4.83GB 模型让架构安全与资源生命周期成为首要技术债。首个版本应以 arm64-v8a、Android 16、单模型阶段加载、纯 `.txt`、PCM 直放和 AAC/MP4 章节导出为边界；在目标机取得稳定数据前，不承诺并发模型、低 minSdk、全品牌兼容或精确音频时长。

## 引用来源

[1] https://developer.android.google.cn/about/versions/16/setup-sdk
> “请打开应用的 build.gradle 或 build.gradle.kts 文件，并按如下方式更新 Android 16 的 compileSdk：compileSdk = 36”

[2] https://developer.android.google.cn/studio/projects/install-ndk?hl=el
> “CMake：Android Gradle plugin 创建项目时使用的外部构建工具……使用 CMake 的版本可在模块的 build.gradle 中设置。”

[3] https://developer.android.google.cn/ndk/downloads/revision_history
> “NDK r28（2025 年 2 月）”

[4] https://github.com/ggerganov/llama.cpp
> “Cross-compile CLI using Android NDK”

[5] http://mnn-docs.readthedocs.io/en/latest/transformers/llm.html
> “gguf 转 mnn 使用 gguf2mnn.py 读取 gguf 文件”

[6] https://docs.swmansion.com/react-native-executorch/docs/0.4.x/faq/frequently-asked-questions
> “Can I use GGUF models using the library? No, as of now ExecuTorch runtime doesn't provide a reliable way to use GGUF models.”

[7] https://developer.android.google.cn/media/platform/xhe-aac-encoding
> “采样率：44100 Hz、48000 Hz。比特率范围：12,000 bps 至 400,000 bps。”

[8] https://developer.android.google.cn/media/optimize/audio-focus
> “使用 AudioAttributes 描述应用正在播放的音频类型。例如，对于播放语音的应用，请指定 CONTENT_TYPE_SPEECH。”

[9] https://developer.android.google.cn/develop/background-work/services/fgs/changes
> “如需为响应用户操作而传输数据，请考虑使用由用户发起的数据传输作业。这些作业不受常规作业配额的限制。”

[10] https://docs.github.com/actions/writing-workflows/about/caching-workflow-dependencies
> “Use caching when you want to reuse files that don't change often between jobs or workflow runs.”

[11] https://developer.android.google.cn/develop/background-work/services/fg-service-types
> “本地文件处理”

[12] https://voicealoudreader.cn/manual.html
> “从设备存储打开文件”

[13] https://www.speechify.com/zh-hans-cn/blog/speechify-web-app-and-chrome-extension-fall/
> “一键导入：可直接将 Google Drive、Dropbox、OneDrive 和 Canvas 账户连接到网页应用，轻松访问云端文件。”

[14] https://fdroid-website-ldeso-e5033e3fd9dee0067d61ccf48a277204099f2e2375.gitlab.io/en/packages/io.github.aloussase.booksdownloader/index.html
> “Alexandria is an open-source application for downloading and converting EBooks.”

[15] https://blog.gitcode.com/ad65a425bbcea1a05986f2e2488dbe9d.html
> “逐章/逐句拆分文本、调用 TTS 引擎合成音频，最后合并输出为带元数据的音频文件。”

[16] https://developer.android.google.cn/about/versions/10/behavior-changes-10?hl=sk
> “应用应该仅加载嵌入到应用的 APK 文件中的二进制代码。”

[17] https://developer.android.google.cn/media/platform/improve-audio-playback
> “setUsage(AudioAttributes.USAGE_MEDIA)”

[18] https://github.com/ServeurpersoCom/qwentts.cpp
> “C++17/GGML port of Qwen3-TTS”

[19] https://blog.gitcode.com/79d9ee0293a967c8e9be9a96485e00a5.html
> “将模型权重放入 models/ 目录”

[20] https://www.qwentts.net/
> “49+ AI Voices……Multilingual Support”