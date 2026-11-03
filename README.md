# 听本

> 本地离线把一本小说变成能听的音频。与「墨听」彻底切割的**从零重做**项目。

## 下载安装

```
https://raw.githubusercontent.com/zjh1934/tingben-apk/main/app-debug.apk
```
备用（Release 页）：`https://github.com/zjh1934/tingben-android/releases/latest`

- 版本 **1.0.0+6** · **17,487,576 字节 ≈ 16.7MB**
- 浏览器直接打开即可下载；包名 `com.tingben.app`，与旧墨听不同，**可同时安装、不用卸载**
- 固定签名，后续新版直接覆盖安装
- 源码：`https://github.com/zjh1934/tingben-android`（公开）

## 用起来就四步

1. 装包，第一次进设置页 →「导入 / 查看模型」→ 导入三个 GGUF（约 5GB，App 自己不联网）
2. 书架右上「＋ 导入一本书」→ 选一个 txt
3. 点书卡的「制作有声书」→（可选勾几章）→ 点**一次**「开始制作」
4. 进度页看着它做完 →「我的音频」里点「试听」或「保存到手机」

## 三个模型（唯一确定、不进包）

| 槽位 | 文件 | 国内地址 |
|---|---|---|
| 语言模型 | `mlabonne_Qwen3-4B-abliterated-Q4_K_M.gguf` | `https://hf-mirror.com/bartowski/mlabonne_Qwen3-4B-abliterated-GGUF/resolve/main/mlabonne_Qwen3-4B-abliterated-Q4_K_M.gguf` |
| 声音主文件 | `qwen-talker-1.7b-voicedesign-Q8_0.gguf` | `https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/main/qwen-talker-1.7b-voicedesign-Q8_0.gguf` |
| 声音配套 | `qwen-tokenizer-12hz-Q8_0.gguf` | `https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/main/qwen-tokenizer-12hz-Q8_0.gguf` |

平台解析器读不了 GB 级二进制会报「资源解析服务请求失败」——**不是坏链，用浏览器打开**。

## 硬约束

- 无 `INTERNET` 权限、仅 `arm64-v8a`、APK < 100MB、模型后导入不进包
- **正文一个字不改**：模型只加导演指令，字符级 gate 校验，不过就降级直读（原文照读、不静音）
- 两个模型串行加载、不同时常驻
- 首版只导出 WAV；MP3 / M4A / m4b 内嵌章节轨、epub、后台续跑都是二期
- 用户第一次听到声音前，主界面不出现 `.gguf` 文件名、SHA、token 数、调试页

## 架构：四层移植，不是整包微调

| 层 | 抄谁 | 拿什么 |
|---|---|---|
| Android 离线底座 / JNI | OfflineLLM (Apache-2.0) | Manifest、SAF 导入、llama.cpp JNI 生命周期 |
| 标注契约 | Alexandria (MIT) | speaker/text/instruct、500 字分块、角色表+最近 3 条、JSON 四级修复 |
| 引擎组合 / 断点 / 合并 | Antioch (MIT) | llama.cpp + qwentts.cpp 同栈、逐块 WAV、WAV 拼接 |
| 产品不变量 | ReaderVoice-Mobile | 原文不可改、UNKNOWN 合法、进程被杀只丢当前批 |

自己写的是：中文 prompt、字符级逐字 gate、Kotlin 一键编排、CI、5 页 UI。
`reference/精选/` 存了 15 个扒下来的核心文件与许可证；`docs/听本_项目导图.html` 是完整导图。

## 代码地图

```
app/src/main/java/com/tingben/app/
  MainActivity.kt          5 页 UI（书架/制作/进度/我的音频/设置 + 模型页）
  core/Pipeline.kt         书架、模型槽位、分块、标注 gate、WAV 合并、一键编排
  engine/NativeEngine.kt   JNI 声明
  diag/EventLog.kt         工程事件落盘（原生层也往这里写）
app/src/main/cpp/          tingben_native.cpp + CMakeLists + 上游公开头
app/src/main/assets/       prompt_zh.txt
contracts/                 模型清单、中文 prompt、流水线规格
scripts/                    build_native.sh / check_structure.py / publish_apk.py
.github/workflows/ci.yml   自检 → 交叉编译 → 构建 → APK 闸门 → Release + raw 直链
```

## 如实说

- **CI 全绿、APK 闸门全过**（无 INTERNET、无 .gguf、7 个 arm64 .so、CRC 全检、17MB）；但**真机出声还没人验过**——沙盒没有 NDK/kotlinc/真机，「编译通过」不等于「能用」。
- 首版导入只支持 txt；PDF、OCR、epub 明确不做/二期。
- voicedesign 是**单音色**：一个声音演所有情绪，不支持按角色切声线。
