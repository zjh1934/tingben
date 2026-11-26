## 听本 1.1.64

本地离线把小说变成能听的音频 · 端侧 TTS · 网络能力默认关闭（仅用于你手动开启的网关 / 跨网中继）· 模型后导入。

### 本版更新

### R409 · 真机第一张关于页截图，抓出两个只在真机上才成立的 bug

用户把 1.1.63 装上真机（Xiaomi 24117RK2CC / Android 17 / SDK 37），发回关于页截图与复制出来的诊断信息。**静态 33/33、两次真编译全绿，不等于好用**——截图里「检查更新」显示「更新信息格式异常，可去项目主页查看」，而且卡片里冒出两个「再查一次」。

**① 检查更新必报格式异常（P0）**：线上 `updates.atom` 首条 entry 的标题是 `听本 v1.1.63`，不是纯版本号。R408 的 `parse()` 把整串 `<title>` 当 `Info.version`，`compare()` 里 `听本 v1` 转不成 Int，返回 `-2`，于是每次检查都落到「格式异常」。守门当时只喂 `1.1.64` / `v1.1.62` 这种干净串，没覆盖线上真实形态——这是测试数据被洗干净的假绿。现在用 `VERSION_RE` 只抽 `v?数字(.数字)+`，抽不到才留原文走格式异常兜底；tag 采集补 `inEntry` 守卫（feed 级 `<title>`/`<updated>` 不许污染 entry），读到第一个 `</entry>` 即返回，不再扫 feed 尾部。

**② 失败态画出两个「再查一次」**：卡片底部本来就常驻一个，失败态分支又自己画了一个主按钮，截图里一浅紫一黑字叠着，说明行被挤到悬浮球后面。现在失败态只给中文原因，按钮统一由底部那一个承担；`AboutAction` 加 `enabled`，「正在检查…」期间置灰不可点，避免并发请求。

**真机已验通过的部分（截图实证）**：版本信息卡全对（v1.1.63 / 400 · debug · git:63bdb3e / 构建期时间 / Android 17 SDK 37 / 设备型号 / arm64-v8a），构建期字段确实固定而非运行时钟；「复制诊断信息」粘贴出来只有白名单五行，不含书名、文件、朗读正文、Token 与设备标识；Snackbar「已复制，可粘贴到 Issue」正常；连点彩蛋提示在位。**真机基线由 1.1.57 推进到 1.1.63**。

**仍未真机验**：无浏览器机型点项目主页的兜底面板、飞行模式读随包发版说明、发现新版时的弹窗与「跳过此版本」。这四条继续排在欠账里，不假装做了。

**守门**：新增 `contracts/update_atom_live.xml` 作为真机 atom 形态 fixture（带中文前缀 / feed 级 title / 多行 content / enclosure），不许被洗成干净版本号；`r408_verify.py` 补 9 组真机回归（中文标题归一、1.1.9/1.1.64 判定、无版本号兜底、legacy bug 复现、enclosure 字节数与直链、**发版说明裸尖括号经 `html.escape` 后仍是合法 atom**）与 6 条反向注入，**41/41 全拦**；顺手把发版侧两处 `html.escape` 也钉死——本段 CHANGELOG 里就有 `<title>` / `</entry>`，不转义会把 atom 写成非法 XML，等于换个姿势重演同一个 bug。`aoci` 同步登记与刷新基线。老 1.1.63 包已发出、无法远端修复，只能升级到 1.1.64。

### 升级

固定签名，直接覆盖安装即可；已导入的模型与做好的音频都保留。**无需额外操作**。

### 下载

- 本版安装包：https://github.com/zjh1934/tingben/releases/download/v1.1.64/tingben-v1.1.64.apk
- 镜像直链：https://cdn.jsdelivr.net/gh/zjh1934/ting-ben-apk@main/tingben-v1.1.64.apk
- 兼容旧直链（同内容，仅给没刷新的老链接兜底）：https://cdn.jsdelivr.net/gh/zjh1934/ting-ben-apk@main/app-debug.apk

完整记录：[CHANGELOG.md](https://github.com/zjh1934/tingben/blob/main/CHANGELOG.md) · 使用说明：[使用指南](https://github.com/zjh1934/tingben/blob/main/docs/使用指南.md)
