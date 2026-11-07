# -*- coding: utf-8 -*-
"""R96 · 阅读位置批量读对拍：8 组数据证明「一次读全部」与「逐本读」结果逐本一致，
且读盘次数从 N 降为 1。纯语义对拍，不代表真机性能。
"""
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
READING = ROOT / "app/src/main/java/com/tingben/app/core/ReadingState.kt"

def decode(v):
    if isinstance(v, bool):
        return None
    if isinstance(v, (int, float)):
        n = int(v)
        return (n, 0, 0) if n >= 0 else None
    if isinstance(v, dict):
        ch = int(v.get("chapter", -1))
        if ch < 0:
            return None
        return (ch, max(0, int(v.get("para", 0))), max(0, int(v.get("offset", 0))))
    return None

class FakeFile:
    """模拟 f.readText()：每次调用计一次磁盘读。"""

    def __init__(self, data):
        self.data = data
        self.reads = 0

    def read_text(self):
        self.reads += 1
        return json.loads(json.dumps(self.data))

def last(f, book_id):
    return decode(f.read_text().get(book_id))

def all_last(f):
    o = f.read_text()
    return {k: p[0] for k, v in o.items() if (p := decode(v)) is not None}

CASES = [
    ("空书架", {}, ["a"]),
    ("旧整数数据", {"a": 3}, ["a", "b"]),
    ("新对象带段内位置", {"b": {"chapter": 7, "para": 12, "offset": 44}}, ["b"]),
    ("新旧混排", {"a": 3, "b": {"chapter": 0}}, ["a", "b", "c"]),
    ("负数与缺章", {"a": -1, "b": {"chapter": -3}, "c": {"para": 5}}, ["a", "b", "c"]),
    ("坏类型", {"a": "x", "b": None, "c": True}, ["a", "b", "c"]),
    ("段内值缺省或负数", {"a": {"chapter": 2, "para": -5, "offset": -9}}, ["a"]),
    ("30 本书", {"id%d" % i: (i if i % 3 else {"chapter": i, "para": i * 2})
               for i in range(30)}, ["id%d" % i for i in range(30)] + ["none"]),
]

def main():
    errors = []
    for name, data, ids in CASES:
        f_all = FakeFile(data)
        got = all_last(f_all)
        for bid in ids:
            f_one = FakeFile(data)
            want = last(f_one, bid)
            want_chapter = None if want is None else want[0]
            if got.get(bid) != want_chapter:
                errors.append("%s / %s: 批量=%r 逐本=%r" %
                              (name, bid, got.get(bid), want_chapter))
        if f_all.reads != 1:
            errors.append("%s: 批量读盘 %d 次（应为 1）" % (name, f_all.reads))
        n = len(ids)
        f_n = FakeFile(data)
        for bid in ids:
            last(f_n, bid)
        if f_n.reads != n:
            errors.append("%s: 逐本读盘 %d 次（应为 %d）" % (name, f_n.reads, n))

    src = READING.read_text(encoding="utf-8")
    if "private fun decode(v: Any?): Pos?" not in src:
        errors.append("Kotlin 侧缺共用 decode")
    if "decode(JSONObject(r.text).opt(bookId))" not in src:
        errors.append("pos() 不再走 decode")
    if "decode(o.opt(k))?.let { out[k] = it.chapter }" not in src:
        errors.append("allLast() 不再走 decode")
    if "fun last(c: Context, bookId: String): Int? = pos(c, bookId)?.chapter" not in src:
        errors.append("last() 单本读被删（目录页仍在用）")

    if errors:
        print("FAIL")
        for e in errors:
            print(" -", e)
        return 1
    print("OK · R96 阅读位置 %d 组对拍：逐本一致 / 批量读盘 1 次 / 共用 decode" % len(CASES))
    return 0

if __name__ == "__main__":
    sys.exit(main())
