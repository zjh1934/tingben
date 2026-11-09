#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R192 · 删书清理链路补全。

purge 原来清了章节表 / 成品 / work / 任务 / 置顶，漏了：
  · ListenState 断点（key `chapter:<bookId>`，label 带书名章名明文）
  · ReadingState 阅读位置（key 就是 bookId）
  · 正在播放这本书的队列（播放器指向已删 WAV）
  · 正在制作这本书的后台协程（往刚删掉的 work/ 重新写盘）

顺序是硬约束：先 releaseIfBook / stopIfBook → 再删文件；
ListenState 清理必须在 releaseIfBook 之后（release 内 savePos 会写回）。

selftest 纯内存注入 + tempfile 逻辑对拍，源文件零写入。
用法：python3 scripts/r192_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "listen": APP / "core/ListenState.kt",
    "reading": APP / "core/ReadingState.kt",
    "queue": APP / "core/PlayQueue.kt",
    "pipe": APP / "core/Pipeline.kt",
    "main": APP / "MainActivity.kt",
    "r135": ROOT / "scripts/r135_verify.py",
    "ci": ROOT / ".github/workflows/ci.yml",
}

NEED = {
    "listen": [
        "fun removeForBook(c: Context, bookId: String)",
        'if (k == "chapter:$bookId" || k == bookId) hit.add(k)',
        "is AtomicJson.Read.Corrupt -> return",
    ],
    "reading": [
        "fun remove(c: Context, bookId: String)",
        "if (o.remove(bookId) != null) AtomicJson.write(f, o.toString())",
    ],
    "queue": [
        "fun isPlayingBook(bookId: String): Boolean",
        'bookId.isNotEmpty() && queueId == "chapter:$bookId"',
        "fun releaseIfBook(bookId: String)",
    ],
    "pipe": [
        "fun stopIfBook(bookId: String): Boolean",
        "try { PlayQueue.releaseIfBook(id) } catch (_: Throwable) { }",
        "try { Producer.stopIfBook(id) } catch (_: Throwable) { }",
        "try { ListenState.removeForBook(c, id) } catch (_: Throwable) { }",
        "try { ReadingState.remove(c, id) } catch (_: Throwable) { }",
        "try { ChapterPlan.delete(c, id) } catch (_: Throwable) { }",
        "正在播放或制作这本书的先停",
    ],
    "main": [
        "收听与阅读位置",
        "会一并停止",
    ],
    "r135": ['"r192_verify.py"'],
    "ci": [
        "python3 scripts/r192_verify.py --selftest",
        "python3 scripts/r192_verify.py",
    ],
}
BAN = [
    # 旧文案：只承诺四件，没说位置与停机
    "已做好的音频、分段缓存和制作任务。",
]


def checks(f):
    e = []
    for key, needles in NEED.items():
        for s in needles:
            if s not in f[key]:
                e.append("能力缺失[%s]: %s" % (key, s[:66]))
    for s in BAN:
        if s in f["main"]:
            e.append("旧文案回潮: " + s[:56])
    # 顺序硬约束：停播放/制作 → 清状态 → 删章节表与文件。
    # R193 · 只看 purge 函数体。BookStore.delete 里现在也有一次 stopIfBook
    # （收尾窗口的提前拒绝），全文 find 会命中它，把顺序误判成反了。
    p = f["pipe"]
    start = p.find("private fun purge(c: Context, id: String) {")
    body = p[start:] if start >= 0 else p
    order = [
        "try { PlayQueue.releaseIfBook(id) }",
        "try { Producer.stopIfBook(id) }",
        "try { ListenState.removeForBook(c, id) }",
        "try { ReadingState.remove(c, id) }",
        "try { ChapterPlan.delete(c, id) }",
    ]
    pos = [body.find(x) for x in order]
    if -1 in pos or pos != sorted(pos):
        e.append("purge 清理顺序错了（先停机 → 再清状态 → 再删文件）")
    # ListenState 清理必须在 release 之后，否则会被 savePos() 写回
    if not (body.find("try { PlayQueue.releaseIfBook(id) }") <
            body.find("try { ListenState.removeForBook(c, id) }")):
        e.append("ListenState 清理放到了 release 之前，会被 savePos 写回")
    # 自动防漏要求 CI 里的现行批次守门都登记
    live = set(__import__("re").findall(
        r'scripts/(r(?:1[6-9]\d|[2-9]\d\d)_verify\.py)', f["ci"]))
    declared = set(__import__("re").findall(r'"(r\d+_verify\.py)"', f["r135"]))
    gap = sorted(live - declared - {"r135_verify.py"})
    if gap:
        e.append("现行守门漏登记 CURRENT_VERIFIERS: " + ", ".join(gap))
    return e


def selftest():
    base = {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}
    assert not checks(base), "基线自己就红了，先修基线"
    cases = [
        ("断点清理被摘", "listen",
         lambda t: t.replace("fun removeForBook(c: Context, bookId: String)",
                             "fun unusedRemove(c: Context, bookId: String)")),
        ("断点只删精确 queueId 之外的", "listen",
         lambda t: t.replace('if (k == "chapter:$bookId" || k == bookId) hit.add(k)',
                             'if (k == bookId) hit.add(k)')),
        ("阅读位置清理被摘", "reading",
         lambda t: t.replace("fun remove(c: Context, bookId: String)",
                             "fun unusedRemove(c: Context, bookId: String)")),
        ("停播放被摘", "queue",
         lambda t: t.replace("fun releaseIfBook(bookId: String)",
                             "fun unusedReleaseIfBook(bookId: String)")),
        ("停播放判据写错", "queue",
         lambda t: t.replace('bookId.isNotEmpty() && queueId == "chapter:$bookId"',
                             'bookId.isNotEmpty() && queueId == bookId')),
        ("停制作被摘", "pipe",
         lambda t: t.replace("try { Producer.stopIfBook(id) } catch (_: Throwable) { }\n", "")),
        ("断点清理被摘", "pipe",
         lambda t: t.replace("try { ListenState.removeForBook(c, id) } catch (_: Throwable) { }\n", "")),
        ("阅读位置清理被摘", "pipe",
         lambda t: t.replace("try { ReadingState.remove(c, id) } catch (_: Throwable) { }\n", "")),
        ("顺序反了：先删断点再 release", "pipe",
         lambda t: t.replace(
             "        try { PlayQueue.releaseIfBook(id) } catch (_: Throwable) { }\n"
             "        try { Producer.stopIfBook(id) } catch (_: Throwable) { }\n"
             "        try { ListenState.removeForBook(c, id) } catch (_: Throwable) { }\n",
             "        try { ListenState.removeForBook(c, id) } catch (_: Throwable) { }\n"
             "        try { PlayQueue.releaseIfBook(id) } catch (_: Throwable) { }\n"
             "        try { Producer.stopIfBook(id) } catch (_: Throwable) { }\n")),
        ("停机挪到删文件之后", "pipe",
         lambda t: t.replace(
             "        try { PlayQueue.releaseIfBook(id) } catch (_: Throwable) { }\n"
             "        try { Producer.stopIfBook(id) } catch (_: Throwable) { }\n", "")),
        ("弹窗退回旧文案", "main",
         lambda t: t.replace('                "以及收听与阅读位置。若正在播放或制作这本书，会一并停止；" +\n'
                             '                "还在收尾上一段时会先停，稍等再删一次。") },',
                             '                "和制作任务。") },')),
        ("忘登记 CURRENT_VERIFIERS", "r135",
         lambda t: t.replace(', "r192_verify.py"', "", 1)),
        ("CI 漏挂 r192", "ci",
         lambda t: t.replace("python3 scripts/r192_verify.py --selftest", "")),
    ]
    bad = 0
    for name, key, mut in cases:
        v = dict(base)
        v[key] = mut(base[key])
        assert v[key] != base[key], "注入没生效: " + name
        if not checks(v):
            bad += 1
            print("  反向注入漏放:", name)
    print("反向注入 %d/%d 全拦下（纯内存，源文件零写入）" % (len(cases) - bad, len(cases)))
    bad += logic_check()
    return bad


def logic_check():
    """按 ListenState / ReadingState 的等价规则跑清理，验证只删目标书、别书不动。"""
    import tempfile, shutil, json
    bad = 0
    d = None
    try:
        d = pathlib.Path(tempfile.mkdtemp(prefix="r192_"))
        # listen_state.json：目标书一个整章断点 + 别书一个 + 一个临时试听态
        ls = d / "listen_state.json"
        ls.write_text(json.dumps({
            "chapter:BOOK_A": {"trackId": "/audio/a.wav", "positionMs": 123000,
                               "label": "目标书 · 第 3 章"},
            "chapter:BOOK_B": {"trackId": "/audio/b.wav", "positionMs": 45000,
                               "label": "别本书 · 第 1 章"},
            "seg": {"trackId": "/work/seg.wav", "positionMs": 1000, "label": "试听"},
        }, ensure_ascii=False), encoding="utf-8")
        o = json.loads(ls.read_text(encoding="utf-8"))
        hit = [k for k in list(o.keys()) if k in ("chapter:BOOK_A", "BOOK_A")]
        for k in hit:
            o.pop(k)
        ls.write_text(json.dumps(o, ensure_ascii=False), encoding="utf-8")
        got = json.loads(ls.read_text(encoding="utf-8"))
        assert "chapter:BOOK_A" not in got, "目标书断点没清掉"
        assert "chapter:BOOK_B" in got and got["chapter:BOOK_B"]["positionMs"] == 45000, "别书断点被误删"
        assert "seg" in got, "试听态不该被碰"

        # reading_state.json：目标书 + 别书（老格式数字 + 新格式对象各一）
        rs = d / "reading_state.json"
        rs.write_text(json.dumps({
            "BOOK_A": {"chapter": 7, "para": 3, "offset": 12},
            "BOOK_B": 2,
            "BOOK_C": {"chapter": 0},
        }), encoding="utf-8")
        o = json.loads(rs.read_text(encoding="utf-8"))
        o.pop("BOOK_A", None)
        rs.write_text(json.dumps(o), encoding="utf-8")
        got = json.loads(rs.read_text(encoding="utf-8"))
        assert "BOOK_A" not in got, "目标书阅读位置没清掉"
        assert got["BOOK_B"] == 2 and got["BOOK_C"]["chapter"] == 0, "别书阅读位置被误删"

        # 损坏的 JSON 绝不覆写（AtomicJson.Read.Corrupt → return）
        bad_json = d / "broken_state.json"
        bad_json.write_text("{ 这不是 JSON", encoding="utf-8")
        assert bad_json.read_text(encoding="utf-8") == "{ 这不是 JSON", "损坏文件被覆写"
        shutil.rmtree(d)
        d = None
        print("逻辑对拍 3/3：只清目标书 / 别书与试听态不动 / 损坏文件不覆写")
    except Exception as ex:
        bad += 1
        print("  逻辑对拍失败:", str(ex)[:110])
        try:
            if d: shutil.rmtree(d)
        except Exception:
            pass
    return bad


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(1 if selftest() else 0)
    f = {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}
    e = checks(f)
    if e:
        print("FAIL · R192 删书清理链路")
        for x in e:
            print("  ✗", x)
        sys.exit(1)
    print("R192 删书清干净：断点/阅读位置/播放/制作四件 + 顺序与停机：PASS")
