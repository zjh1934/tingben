#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R233 · 用户手工删掉的小块单独记账：不参与合成、不参与拼接。

旧行为是「删完下次续跑按原顺序重新生成」，跟用户意图正好相反。
用法：python3 scripts/r233_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"

def files():
    def rd(p): return p.read_text(encoding="utf-8", errors="replace")
    return {
        "drops": rd(APP / "core/SegDrops.kt"),
        "hub": rd(APP / "ChapterHub.kt"),
        # core 文件直读；ui_sources 只合并 UI 片段，不含 Producer
        "producer": rd(APP / "core/Producer.kt"),
        "ci": rd(ROOT / ".github/workflows/ci.yml"),
        "r135": rd(ROOT / "scripts/r135_verify.py"),
        "config": rd(ROOT / ".aoci/config.json"),
    }

def check(f):
    e = []
    d, h, m = f["drops"], f["hub"], f["producer"]
    # ① 记账本体
    for k in ["object SegDrops", 'File(c.filesDir, "work/$bookId/dropped.json")',
              "MessageDigest.getInstance(\"SHA-256\")", "fun mark(c: Context, bookId: String, files: List<File>): Int",
              "fun snapshot(c: Context, bookId: String): Map<String, String>",
              "fun matches(snap: Map<String, String>, wav: String, text: String): Boolean",
              "SegPlan.readMap(dir)?.associate { it.wavName to it.text }",
              'if (!wavRe.matches(wav)) continue',
              'return h.isBlank() || h == hash(text)',
              "AtomicJson.write(file(c, bookId), a.toString())"]:
        if k not in d: e.append("记账本体缺: " + k[:56])
    # 哈希必须比对同一口径：segmap 的 text 是前 200 字
    if "annotated[i].text.take(200)" not in m:
        e.append("判定没用同一口径的段文本前 200 字")
    # ② 删段 UI
    for k in ["SegDrops.mark(c, book.id, files)",
              'EventLog.log(EventLog.Kind.PIPELINE, "SEG_DROPPED"',
              "删掉的段从此不参与合成与拼接"]:
        if k not in h: e.append("删段 UI 缺: " + k[:56])
    if '重新生成，" +' in h:
        e.append("旧文案回潮：删了又说会重新生成")
    # ③ 合成链路三处排除
    for k in ["val dropSnap = SegDrops.snapshot(c, book.id)",
              "val dropText = { i: Int -> annotated[i].text.take(200) }",
              "val dropWav = { i: Int -> String.format(\"direct_%04d.wav\", i) }",
              "if (SegDrops.matches(dropSnap, wavName, dropText(i))) return@count false",
              '"src" to "dropped"',
              "不参与合成与拼接",
              "if (SegDrops.matches(dropSnap, wavName, dropText(i))) continue"]:
        if k not in m: e.append("合成链路缺: " + k[:56])
    # 拼章收集里的排除必须在 byCh.add 之前
    if m.find("if (SegDrops.matches(dropSnap, wavName, dropText(i))) continue") > m.find(".add(File(work, wavName))"):
        e.append("拼章排除放在 add 之后，等于没排")
    # ④ 登记
    for name, src, needle in [("CI", f["ci"], "python3 scripts/r233_verify.py --selftest"),
                              ("清单", f["r135"], '"r233_verify.py"'),
                              ("受管根", f["config"], '"app/src/main/java"')]:
        if needle not in src: e.append(name + " 漏登记 R233")
    return e

def selftest():
    f = files()
    assert not check(f), "基线自己就红了"
    cuts = [
        ("记账文件被摘", "drops", "object SegDrops", "object NoDrops"),
        ("哈希被摘", "drops", 'return h.isBlank() || h == hash(text)', "return true"),
        ("段号重排也硬跳", "drops", 'if (!wavRe.matches(wav)) continue', ""),
        ("segmap 文本不读", "drops", "SegPlan.readMap(dir)?.associate { it.wavName to it.text }", "emptyMap()"),
        ("落盘被摘", "drops", "AtomicJson.write(file(c, bookId), a.toString())", ""),
        ("删段不记账", "hub", "SegDrops.mark(c, book.id, files)", ""),
        ("旧文案回潮", "hub", "删掉的段从此不参与合成与拼接", "下次续跑会按原顺序重新生成"),
        ("记账事件被摘", "hub", '"SEG_DROPPED"', ""),
        ("一次读入被摘", "producer", "val dropSnap = SegDrops.snapshot(c, book.id)", ""),
        ("文本口径被改", "producer", "annotated[i].text.take(200)", "a.text.take(200)"),
        ("pending 不排除", "producer", "if (SegDrops.matches(dropSnap, wavName, dropText(i))) return@count false", ""),
        ("循环不跳过", "producer", '"src" to "dropped"', '"src" to "wav"'),
        ("拼章不排除", "producer", "if (SegDrops.matches(dropSnap, wavName, dropText(i))) continue", ""),
        ("CI 漏挂", "ci", "python3 scripts/r233_verify.py --selftest", ""),
        ("清单漏登记", "r135", '"r233_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        g = dict(f); g[key] = f[key].replace(needle, repl, 1)
        if check(g): print("  反向注入 %-20s 拦下" % name)
        else: print("  反向注入漏放:", name); bad += 1
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad

if __name__ == "__main__":
    if "--selftest" in sys.argv: sys.exit(1 if selftest() else 0)
    e = check(files())
    if e:
        print("R233 FAIL", len(e))
        for x in e: print(" -", x)
        sys.exit(1)
    print("R233 删段记账 / 不参与合成与拼接：PASS")
