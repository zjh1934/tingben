#!/usr/bin/env python3
"""R378 · 发版同步不得清空公开仓：订阅源 / Pages 静态页必须活过每次发版。"""
import pathlib, sys
ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = (ROOT / "scripts/publish_apk.py").read_text(encoding="utf-8")

def check(s):
    e = []
    if '"base_tree": None' in s or "'base_tree': None" in s:
        e.append("publish_apk 又把 base_tree 写死 None（会清空订阅源与静态页）")
    if '"/git/commits/" + parent' not in s:
        e.append("没从父提交读远端 tree sha")
    if '"base_tree": base_tree' not in s:
        e.append("建 tree 没继承远端 base_tree")
    if "updates.atom" not in s:
        e.append("发版没维护订阅源 updates.atom（下载器会永远停在旧版本）")
    if 'rel="enclosure"' not in s:
        e.append("订阅源缺 enclosure，下载器会拿到 HTML 而不是 APK")
    if "docs/updates.atom" not in s:
        e.append("Pages 备用订阅源没同步更新")
    return e

def main():
    if "--selftest" in sys.argv:
        cases = [
            ("回潮 base_tree=None", '"base_tree": base_tree', '"base_tree": None'),
            ("不读父提交", 'c, head_commit = api("GET", "/git/commits/" + parent)', 'head_commit = {}'),
            ("不继承 base_tree", '"base_tree": base_tree', '"base_tree": ""'),
            ("发版不更订阅源", 'updates.atom', 'nothing.atom'),
            ("丢掉 enclosure", 'rel="enclosure"', 'rel="alternate-dup"'),
        ]
        bad = 0
        for name, old, new in cases:
            s = SRC.replace(old, new)  # 全量替换，避免同串出现多次导致漏放
            if not check(s):
                bad += 1; print("  漏放", name)
            else:
                print("  拦下", name)
        print("R378 反向注入 %d/%d 全拦" % (len(cases)-bad, len(cases)))
        return 1 if bad else 0
    e = check(SRC)
    print("R378 %s · 发版同步继承远端 tree / 订阅源不再被清空" % ("FAIL: " + "; ".join(e) if e else "PASS"))
    return 1 if e else 0

if __name__ == "__main__": sys.exit(main())
