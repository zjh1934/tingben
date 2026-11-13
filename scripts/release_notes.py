#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 CHANGELOG.md 抽出当前版本段落，生成结构化 Release 说明到 stdout。"""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
v = (ROOT / "version.txt").read_text(encoding="utf-8").strip()
t = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
m = re.search(r"^## \[" + re.escape(v) + r"\].*?$(.*?)(?=^## \[|\Z)",
              t, re.M | re.S)
section = m.group(1).strip() if m else "本版改动见仓库 CHANGELOG.md。"
print("## 听本 " + v)
print()
print("本地离线把小说变成能听的音频 · 端侧 TTS · 无网络权限 · 模型后导入。")
print()
print("### 本版更新")
print()
print(section)
print()
print("### 升级")
print()
print("固定签名，直接覆盖安装即可；已导入的模型与做好的音频都保留。**无需额外操作**。")
print()
print("### 下载")
print()
print("- 固定最新版直链：https://raw.githubusercontent.com/zjh1934/ting-ben-apk/main/app-debug.apk")
print("- 本版 APK：https://github.com/zjh1934/tingben/releases/download/v" + v + "/app-debug.apk")
print()
print("完整记录：[CHANGELOG.md](https://github.com/zjh1934/tingben/blob/main/CHANGELOG.md) · "
      "使用说明：[使用指南](https://github.com/zjh1934/tingben/blob/main/docs/使用指南.md)")
