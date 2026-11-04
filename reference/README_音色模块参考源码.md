# 音色模块 · 扒下来的参考源码

本目录是调研阶段从上游仓库**原样下载 / 解包**的源码，用于下轮照抄，不是本项目的代码。

## 两组来源

**A. 本地早就有、这轮重新翻出来的 Kotlin 现成件**（最重要，优先抄）
→ `精选_Kotlin现成件/` 共 11 个文件：

- `SettingsRepository.kt` · 采样参数 `KEY_* / DEFAULT_*` 常量集中 + 持久化
- `SettingsScreen.kt` / `SettingsViewModel.kt` · Compose 采样设置页
- `CharacterProfileStore.kt` · 角色档案 JSON 原子落盘 + `USER_LOCKED` + 用户修正优先合并
- `CharacterManagerActivity.kt` · 角色管理页
- `VoiceDesignEngine.kt` · JNI 封装形状
- `CharacterModel.kt` / `CharacterProfileExtractor.kt` / `CharacterStore.kt` / `SchemaMigrationV2.kt` · 规范角色、别名、身份证据、schema 迁移
- `CosyVoiceVoiceProfile.kt` · `schemaVersion / id / profileHash / toJson / fromJson`

原始工程在 `OfflineLLM/` 与 `ReaderVoice-Mobile/`（立项时解包），体积大、含字体与截图，已挑出上面 11 个关键文件。

**B. 本轮新抓的上游源码**
- `Qwen3-TTS-official/`：QwenLM/Qwen3-TTS（Apache-2.0），3 个 examples + README
- `qwentts-cpp/`：ServeurpersoCom/qwentts.cpp（MIT），`sampling-defaults.h` / `qwen.h` / ARCHITECTURE.md / 4 个示例脚本 / README
- `novel-voice-studio/`：DokeDev/novel-voice-studio（MIT 系），`generate_personas.py` + `project.py`
- `PocketPal/`：a-ghorbani/pocketpal-ai（见仓库 LICENSE），配置版本化与采样设置 UI

## 怎么用

先看 `docs/音色模块_移植清单.md`：每块都写明抄哪个文件、抄哪些函数、落到我们哪个文件、以及 8 项联动检查。
**不要把这些文件直接拷进 `app/src`** —— Python / TS / 别的引擎的 C++ 只作实现参照；Kotlin 现成件也要改包名、去掉 Hilt/Room/加密等我们没有的依赖。

## 注意

- `qwentts-cpp/src__qwen.h` 是上游版本，JNI 结构体一律以 `app/src/main/cpp/qwen.h`（本地，ABI 6）为准。
- `voicedesign_jni.cpp`（ReaderVoice）是 MNN 引擎，跟我们 qwentts.cpp 不同，**不可整段抄**。
- `iris_android` / `ensemble-reads` 未核验到可引用源码，已排除。
- 许可证按各自仓库 LICENSE 保留；移植时保留版权声明。
