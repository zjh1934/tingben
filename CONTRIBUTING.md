# 参与开发

## 开工前

- 读 [docs/00_现行契约.md](docs/00_现行契约.md) 与 [STATUS.md](STATUS.md)；排期真源是 [docs/修复清单_按优先级.md](docs/修复清单_按优先级.md)。
- 版本真值只在根目录 `version.txt`，不在别处另写版本串；每次发版修订号加一。
- 改哪块验哪块：本地跑相关静态自检，真编译由 CI 完成，沙盒没有 Android SDK。

## 守恒清单（改界面尤其注意）

主页输入框 / 抽屉放书 / 右上角 ＋ 与齿轮；一本书一个目录页；正文一字不改；
`commitAll()` 是调音台唯一提交出口；9 项采样真到 native；底部常驻播放条 + 全屏播放器浮层；
小段试听与整章成品两态隔离；无 `INTERNET`、仅 arm64-v8a、模型后导入不进包。
不画物理上不存在的开关，不补假进度、假状态、假单选。

## 发版

`rc_push.py --dry` 看差异 → `--push` 建 rc 分支触发全套守门与 Gradle 真编译 →
`--status` 确认本候选的 run 成功 → `--promote` 快进 main → CI 自动发布 Release 并同步固定直链。
禁 force、禁假链；编译没绿不发包。

## 提交 PR

- 说清改了哪个界面 / 哪条链路、影响面、怎么验的；贴界面改动请附按代码绘制的形态图。
- 不批量替换字符串清理源码，界面文案与日志输出是功能。
- 不在日志里放正文、书名、音色描述、令牌或密钥。
- 使用 [PR 模板](.github/PULL_REQUEST_TEMPLATE.md)，Issue 用对应模板提。

## 许可

主项目 MIT；移植改写与上游模型的版权声明见 [docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md)。
