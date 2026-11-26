#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R408 · 从 CHANGELOG.md 生成随包发版说明（关于页离线可读）。

APK 闸门（R142，ci.yml「APK 闸门」步骤）禁止 .md / 脚本 / 日志 / 截图 /
契约文本进安装包，unzip -l 只看文件名不看内容。因此这里不直接拷
CHANGELOG.md，而是输出 `app/src/main/assets/changelog.txt`：

  · 只保留每个 `## [x.y.z]` 版本段的标题 + 前 12 行用户可感知说明；
  · 遇到「守门 / 明确没做 / 验收口径 / 预览」这类开发者复盘段即止，
    不把 rc 编号、反向注入、真机待验清单塞进用户手机；
  · 闸门一条不改、不放宽，关于页读的是 .txt 而不是 .md。

用法：python3 scripts/make_changelog_asset.py（CI 在 assembleDebug 前调）
"""
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / 'CHANGELOG.md'
OUT = ROOT / 'app/src/main/assets/changelog.txt'

DEV_MARKS = ('**守门**', '**明确没做', '**验收口径**', '**预览**', '**本轮明确没做')


def main():
    t = SRC.read_text(encoding='utf-8')
    out = []
    for m in re.finditer(r'^## \[([^\]]+)\].*?\n(.*?)(?=^## \[|\Z)', t, re.S | re.M):
        body = []
        for line in (x.rstrip() for x in m.group(2).splitlines() if x.strip()):
            if line.startswith(DEV_MARKS):
                break
            body.append(line)
            if len(body) >= 12:
                break
        out.append('## [%s]\n%s' % (m.group(1), '\n'.join(body)))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text('\n\n'.join(out) + '\n', encoding='utf-8')
    print('随包发版说明：%d 个版本段，%d 字节 -> %s' %
          (len(out), OUT.stat().st_size, OUT.relative_to(ROOT)))


if __name__ == '__main__':
    main()
