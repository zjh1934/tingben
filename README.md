# 听本

> 本地离线把一本小说变成能听的音频。App 不联网，模型文件由用户自己导入、不进安装包。

**当前版本 `1.1.1`** · 现状 → `STATUS.md` · 后续队列 → `ROADMAP.md`（唯一真源）· 动代码前必读 → `docs/00_现行契约.md`

## 安装

```
https://github.com/zjh1934/tingben/releases/download/v1.1.1/app-debug.apk
```

固定直链（永远是最新版）：

```
https://raw.githubusercontent.com/zjh1934/ting-ben-apk/main/app-debug.apk
```

- 源码仓 `zjh1934/tingben`，安装包仓 `zjh1934/ting-ben-apk`
- 包名 `com.tingben.app`，固定签名，**新版直接覆盖装**，模型与已做好的音频都保留
- 平台解析器读不了 GB 级二进制会报「资源解析服务请求失败」，不是坏链，用浏览器打开

## 版本规则

版本号只写在根目录 `version.txt` 一处。`app/build.gradle.kts`、CI 的 Release 标签与名称、构建报告都从它读；
`STATUS.md` / `ROADMAP.md` / 本文件一起对齐。**每打一次包，把 `version.txt` 的修订号加一**（`1.1.1` → `1.1.2` …），
所有口径随这次打包同步更新，不在别处另写版本串。

## 模型文件（后导入，不进包）

**1 份共用底座 + 3 份模式主文件**，各自单独点导入、单选启用，模型页可一次勾选 4 个文件：

| 文件 | 角色 |
|---|---|
| `qwen-tokenizer-12hz-Q8_0.gguf` | 共用底座，三种模式都要用 |
| `qwen-talker-1.7b-voicedesign-Q8_0.gguf` | 自然语言描述造音色，`instruct` 必填 |
| `qwen-talker-1.7b-customvoice-Q8_0.gguf` | 预设音色编号 + 语气指令 |
| `qwen-talker-1.7b-base-Q8_0.gguf` | 一小段参考音频克隆 |

上游仓库 `Serveurperso/Qwen3-TTS-GGUF`（国内镜像把域名换成 `hf-mirror.com`）。模式能调什么以随包
`app/src/main/cpp/qwen.h` 为准，详见 `docs/01_音频模型调教逻辑.md`。

## 用起来

1. ☰（或从左边缘右滑）→「模型」→ 导入共用底座 + 至少 1 份模式主文件
2. 右上角「＋」导入 txt / md / epub，或在抽屉「输入朗读」粘贴文字建书；也支持别的 App「打开方式 → 听本」
3. 点书进三列横滑目录（章节 / 合成 / 可播）→ 勾选章 → 底部「调音 ›」调声音 →「合成音频」
4. 「任务」看每本书 → 点书看章节状态 → 点做过的章看本章分段进度；「我的音频」按书 → 章 → 小格子试听

## 1.1.1 做了什么

- **不再被系统的旋转建议打扰**：主界面锁竖屏。App 本来就不读陀螺仪，那个弹窗是系统的自动旋转提示
- **LOGO**：靛蓝渐变底 +「翻开的书化作声波」，五档 mipmap + 自适应图标，制作通知也换成自己的图标
- **调音台双试听**：「试听 · 已保存」和「试听 · 当前」改成并列等宽胶囊按钮，一实一浅两档色底
- **模型页一屏**：底座卡压缩、三张模式卡改两列紧凑排布，常规手机不需下拉
- **通知栏**：制作进度通知保留并升级；新增播放控制通知（上一段 / 播放暂停 / 下一段 / 关闭），锁屏可操作
- **悬浮播放条**：圆角药丸 + 细进度条 + 强调播放键。它是应用内的底部播放条，不是系统悬浮窗
- **设置页**：新增「通知与权限」引导，一键进系统通知设置；不提供悬浮窗开关
- **书架页**：书卡加色条、字号层级、「上次读到」胶囊标签
- **投喂说明**：制作准备页写明「按章切分 → 逐段送模型 → 每段落盘 → 拼成整章」，正文不改、失败即停、续跑不重算

## 页面与硬约束

- 设置页只管软件（主题三档 / 本机数据 / 通知权限）；制作准备页三个真实分区常驻不折叠
- 调音台 `MixerDraft` 唯一编辑态 + `commitAll()` 唯一生效出口；9 项采样真到 `qt_tts_params`
- 进度页四步横排 + 分段看台 8 格（格子即播停）；播放走软件内悬浮条，不做独立播放器页
- 无 `INTERNET` 权限、仅 `arm64-v8a`、APK < 100MB、模型后导入不进包
- 正文一个字不改；首版只导出 WAV；导入 txt / md / epub，PDF / OCR / DRM 不做
- 书架只放原书 · 任务页只放制作记录 · 我的音频只放成品，三层互不增长
- 不申请无障碍、不弹系统浮窗、不模拟点击

## 现行源码地图

```
app/src/main/java/com/tingben/app/
  MainActivity.kt          页面主体与编排
  AppChrome.kt            全局壳：顶栏 / 左侧抽屉 / 状态栏安全区
  ChapterHub.kt           三列目录页 + 应用内悬浮播放条
  ProduceService.kt       前台服务、制作进度通知
  ProduceOptions.kt       制作准备页三个分区
  core/Pipeline.kt        书架、模型槽位、分块、逐段合成、WAV 合并、任务书签
  core/PlayQueue.kt       播放队列与音频焦点
  core/PlayNotif.kt       播放控制通知（1.1.1 新增）
  core/PlaybackActions.kt 通知按钮广播接收（1.1.1 新增）
  core/AudioLibrary.kt / AudioTree.kt / AtomicJson.kt   成品索引 / 三层树 / 原子落盘
app/src/main/cpp/         tingben_native.cpp + CMakeLists + qwen.h / llama.h
contracts/                模型清单、流水线规格、采样默认值
scripts/                  CI 自检与验证脚本、构建与发布工具
.github/workflows/ci.yml  自检 → 原生缓存 → 编译 → APK 闸门 → Release + 固定直链
```

第三方许可见 `docs/THIRD_PARTY_NOTICES.md`。

## 如实说

- 静态自检绿 ≠ 编译过 ≠ 好用；沙盒无 Android SDK，真编译由 CI 完成。
- 1.1.1 的新界面、通知栏、锁竖屏、图标在真机上都是零证据，不替手感打包票。
- 按既定规矩不向用户索取装机验证；也不拿静态自检冒充体验结论。
