# 听本 · 当前状态

**2026-11-04 · 首版已发**

| 项 | 值 |
|---|---|
| 版本 | 1.0.0+6 (106) |
| APK | 17,487,576 字节 / 16.7MB / arm64-v8a |
| 直链 | `https://raw.githubusercontent.com/zjh1934/tingben-apk/main/app-debug.apk` |
| CI | run 41694800538 全绿（自检→原生缓存命中→assembleDebug→APK 闸门→Release→raw 同步） |
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
