#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R387 · 请求文本留存逻辑模拟器（纯标准库、纯内存、不联网、不碰真机）。

镜像 core/RequestArchive.kt 的行为，用来在上下文里把「写入 → 默认 10 条
→ 展开 → 复制 → 多选删 → 清空 → 上限」整条跑通；不是 Android 代码，
也不代替真编译。只留文本，条目里不允许出现任何音频字段。

用法：python3 scripts/archive_sim.py
"""
import json, uuid, time

SRC_RELAY = "relay"
SRC_GATEWAY = "gateway"
DEFAULT_SHOW = 10
MAX_KEEP = 100
DEDUPE_S = 600          # R404：同一请求 10 分钟内不重复入档


class Archive:
    def __init__(self):
        self.entries = []          # 模拟 filesDir/request_archive.json
        self.log = []              # 模拟 EventLog，只许记来源/字数/条数

    def add(self, source, text, ts=None):
        assert source in (SRC_RELAY, SRC_GATEWAY)
        now = ts if ts is not None else time.time()
        # R404 · 回写 422 会让同一条请求被反复重取，别把 100 条留存灌满。
        if any(e["source"] == source and e["text"] == text and
               now - e["ts"] < DEDUPE_S for e in self.entries):
            return
        self.entries.append({
            "id": uuid.uuid4().hex[:16],
            "source": source,
            "text": text,
            "ts": now,
        })
        for s in (SRC_RELAY, SRC_GATEWAY):
            mine = sorted([e for e in self.entries if e["source"] == s],
                          key=lambda e: e["ts"])
            if len(mine) > MAX_KEEP:
                drop = {e["id"] for e in mine[:len(mine) - MAX_KEEP]}
                self.entries = [e for e in self.entries if e["id"] not in drop]
        self.log.append("ARCHIVE_ADD 来源=%s %d字 共%d条" %
                        (source, len(text), len(self.entries)))
        # 正文一个字都不许进日志（空串在任意串里恒为真，跳过）
        if text:
            assert all(text not in line for line in self.log)

    def list(self, source):
        return sorted([e for e in self.entries if e["source"] == source],
                      key=lambda e: e["ts"], reverse=True)

    def show(self, source, expanded=False):
        all_ = self.list(source)
        return all_ if expanded else all_[:DEFAULT_SHOW]

    def counts(self):
        return (len(self.list(SRC_RELAY)), len(self.list(SRC_GATEWAY)))

    def copy_text(self, entries):
        out = []
        for i, e in enumerate(entries, 1):
            label = "跨网中继" if e["source"] == SRC_RELAY else "语音网关"
            out.append("[%s · %d] %s" % (label, i, e["text"]))
        return "\n\n".join(out)

    def delete(self, ids):
        n = len(self.entries)
        self.entries = [e for e in self.entries if e["id"] not in set(ids)]
        self.log.append("ARCHIVE_DEL 删掉%d条" % (n - len(self.entries)))
        return n - len(self.entries)

    def clear(self):
        n = len(self.entries)
        self.entries = []
        self.log.append("ARCHIVE_CLEAR 清空%d条" % n)
        return n


def run():
    a = Archive()
    ok = 0
    total = 0

    def case(name, cond):
        nonlocal ok, total
        total += 1
        if cond:
            ok += 1
        else:
            print("  FAIL:", name)

    # ① 两个渠道各自写入，互不串列
    for i in range(12):
        a.add(SRC_RELAY, "中继请求第%d句，这是一段要念的台词。" % i, ts=1000 + i)
    for i in range(3):
        a.add(SRC_GATEWAY, "网关请求第%d句，短文本直接回音频。" % i, ts=2000 + i)
    case("中继 12 / 网关 3", a.counts() == (12, 3))
    case("默认每列 10 条", len(a.show(SRC_RELAY)) == DEFAULT_SHOW
         and len(a.show(SRC_GATEWAY)) == 3)
    case("展开到全部", len(a.show(SRC_RELAY, True)) == 12)
    case("两列无交叉", all(e["source"] == SRC_RELAY for e in a.show(SRC_RELAY, True))
         and all(e["source"] == SRC_GATEWAY for e in a.show(SRC_GATEWAY, True)))

    # ② 新→旧，最新一条排第一
    case("倒序", "第11句" in a.show(SRC_RELAY)[0]["text"])

    # ③ 条目只有文本，没有任何音频 / 中间文件字段
    keys = set().union(*[set(e.keys()) for e in a.entries])
    case("字段只有 id/source/text/ts", keys == {"id", "source", "text", "ts"})
    case("零音频字段", not any(k in keys for k in
                            ("audio", "bytes", "format", "wav", "m4a", "path")))

    # ④ 每条一键复制 / 整列复制 / 选中复制
    one = a.copy_text([a.show(SRC_RELAY)[0]])
    case("单条复制带来源", one.startswith("[跨网中继 · 1]") and "第11句" in one)
    col = a.copy_text(a.list(SRC_GATEWAY))
    case("整列复制 3 条", col.count("[语音网关") == 3)
    picked = [e["id"] for e in a.show(SRC_RELAY)[:2]]
    case("选中复制 2 条", a.copy_text([e for e in a.list(SRC_RELAY)
                                    if e["id"] in picked]).count("[跨网中继") == 2)

    # ⑤ 多选删除：只删勾中的，另一列不动
    before = a.counts()
    n = a.delete(picked)
    case("删 2 条", n == 2 and a.counts() == (before[0] - 2, before[1]))

    # ⑥ 上限 100：写超后只留最新 100，两个渠道各自算
    b = Archive()
    for i in range(105):
        b.add(SRC_RELAY, "压力%d" % i, ts=3000 + i)
    for i in range(4):
        b.add(SRC_GATEWAY, "另一边%d" % i, ts=4000 + i)
    case("中继压到 100", b.counts()[0] == MAX_KEEP)
    case("网关不被挤掉", b.counts()[1] == 4)
    case("丢的是最老", "压力104" in b.show(SRC_RELAY, True)[0]["text"]
         and "压力4" not in [e["text"] for e in b.show(SRC_RELAY, True)])

    # ⑦ 一键清空带条数，之后两列都空
    k = b.clear()
    case("清空 104 条", k == 104 and b.counts() == (0, 0))
    case("空列默认 0 条", b.show(SRC_RELAY) == [] and b.show(SRC_GATEWAY) == [])

    # ⑧ 日志全程零正文（R225 迁移后仍成立）
    case("日志条数", len(a.log) + len(b.log) > 0)
    all_log = "\n".join(a.log + b.log)
    case("日志零正文", "要念的台词" not in all_log and "压力" not in all_log)
    case("日志只记来源/字数/条数", all("ARCHIVE_" in l for l in a.log + b.log))

    # ⑨ 空 / 超长文本也留（在校验之前就留，排查才全）
    c = Archive()
    c.add(SRC_RELAY, "")
    c.add(SRC_GATEWAY, "字" * 1501)
    case("空文本也留", c.counts()[0] == 1)
    case("超长文本也留", c.counts()[1] == 1 and len(c.list(SRC_GATEWAY)[0]["text"]) == 1501)

    # ⑩ R404 · 同一请求反复重取不重复入档，但不同文本 / 过了窗口照留
    d = Archive()
    d.add(SRC_RELAY, "同一条被反复重取", ts=5000)
    d.add(SRC_RELAY, "同一条被反复重取", ts=5001)
    case("同一请求不重复入档", d.counts()[0] == 1)
    d.add(SRC_RELAY, "另一条新请求", ts=5002)
    case("不同文本照留", d.counts()[0] == 2)
    case("重复时不记 ARCHIVE_ADD",
         len([l for l in d.log if l.startswith("ARCHIVE_ADD")]) == 2)
    d.add(SRC_RELAY, "同一条被反复重取", ts=5000 + DEDUPE_S + 1)
    case("过了去重窗仍留", d.counts()[0] == 3)

    print("R387 存档模拟器 %d/%d 通过" % (ok, total))
    return 0 if ok == total else 1


if __name__ == "__main__":
    raise SystemExit(run())
