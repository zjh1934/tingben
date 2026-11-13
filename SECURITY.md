# 安全与隐私

## 这个 App 收集什么

- 没有 `INTERNET` 权限，不能联网；没有账号、广告或统计 SDK。
- 书、模型、音频都在本机；日志不落正文、书名、音色描述，只留长度、短哈希与 id。
- 令牌与签名密钥不进仓库；CI 的发布凭据只在 Actions secrets 里。

## 报告问题

请不要在公开 Issue 里贴密钥、正文或含个人信息的日志。涉及隐私或安全的问题，
优先通过 GitHub 私信仓库维护者，或提一个不含敏感内容的 [Issue](https://github.com/zjh1934/tingben/issues) 说明复现方式。
纯数值诊断日志可以贴，正文不要贴。

## 依赖边界

模型文件由用户自行导入，不随 APK 分发；第三方组件的许可与出处在
[docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md)。
