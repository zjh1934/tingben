# -*- coding: utf-8 -*-
"""R95 · 落盘预检公式对拍：Python 复刻 Pipeline.kt 的新估算，8 组场景 + 常量一致性。

R148 重写。旧版复刻的是「need > free 就整本拒绝」，1.1.3 真机日志实证它有两处硬伤：
  · free 取 minOf(work.usableSpace, audio.usableSpace)，work 目录若没建出来返回 0
    → 明明有 273GB 也判定「存储不够」，103 章全本直接被拒
  · need 把「分段」和「合并」双算、单段按 60 秒兜底 → 9137 段估出 61GB
现在改成「按预算能做多少做多少」：算出 budgetSegs，跑到上限才停，绝不拒绝制作。
本脚本继续守新公式，不是放水：常量漂移、边界、查不到空间放行都要对拍。
"""
import core_sources
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
PIPE = core_sources.source(ROOT)

SEG_FALLBACK = 24_000 * 2 * 12          # R148：12 秒兜底，原 60 秒虚高 5 倍
HEADROOM = 16 * 1024 * 1024


def budget(nseg, existing, free):
    """复刻 Kotlin：返回 (seg, estimate, budgetSegs, willStart, willStopEarly)。"""
    pending = sum(1 for i in range(nseg) if existing.get(i) is None)
    sample = [v for v in existing.values() if v is not None]
    seg = int(sum(sample) / len(sample)) if sample else SEG_FALLBACK
    estimate = pending * seg + HEADROOM
    if free <= 0:                        # 查不到可用空间 → 放行，绝不因「查不到」拒绝
        budget_bytes = None
        budget_segs = None               # Kotlin 里是 Int.MAX_VALUE
    else:
        budget_bytes = max(0, free - HEADROOM)
        budget_segs = budget_bytes // max(1, seg)
    will_start = True                    # 关键：任何情况都不再 return 拒绝
    stop_early = budget_segs is not None and pending > budget_segs
    return seg, estimate, budget_segs, will_start, stop_early


def merge_need(parts, out_free):
    need = sum(parts) + HEADROOM
    return need, need > out_free


def main():
    errs = []
    src = PIPE.read_text(encoding="utf-8")
    # ① 新公式常量必须都在
    for s in ["24_000L * 2L * 12L", "c.filesDir.usableSpace",
              "val budgetSegs =", "var spaceStopped = false",
              "STORAGE_PARTIAL", "STORAGE_PARTIAL_STOP",
              "空间用完了，已做好", "if (newlyWritten >= budgetSegs)",
              "var newlyWritten = 0", "newlyWritten++",
              "AudioOps.ensureChapterAudios("]:
        if s not in src:
            errs.append("新公式锚点缺失: " + s)
    # ② 旧的一刀切不许回潮
    for s in ["24_000L * 2L * 60L", "* 3L / 2L + mergeBytes + 16L * 1024 * 1024",
              "if (needBytes > freeBytes)",
              "minOf(work.usableSpace, AudioLibrary.dir(c).usableSpace)",
              "存储空间不够，这次大约要"]:
        if s in src:
            errs.append("旧一刀切公式回潮: " + s)

    MB = 1024 * 1024
    cases = [
        # name, nseg, existing, free, want_pending_budget_ge, want_stop_early
        ("新书 100 段 / 空间足", 100, {}, 800 * MB, True, False),
        ("新书 100 段 / 只剩 1MB", 100, {}, 1 * MB, False, True),
        ("续跑已落 80 段 / 中等空间", 100,
         {i: 2 * MB for i in range(80)}, 400 * MB, True, False),
        ("续跑已落 80 段 / 只剩 10MB", 100,
         {i: 2 * MB for i in range(80)}, 10 * MB, False, True),
        ("全部已落盘 / 只差合并", 10, {i: 3 * MB for i in range(10)}, 60 * MB, True, False),
        ("零段不除零", 0, {}, 32 * MB, True, False),
        ("查不到空间（0）→ 放行", 100, {}, 0, True, False),
        ("极小空间 1 字节 → 仍开工", 100, {}, 1, False, True),
    ]
    for name, nseg, ex, free, want_ge, want_stop in cases:
        seg, estimate, bsegs, start, stop = budget(nseg, ex, free)
        pend = sum(1 for i in range(nseg) if ex.get(i) is None)
        ge = bsegs is None or bsegs >= pend
        if ge != want_ge:
            errs.append("%s: 预算能否做完 %s 期望 %s (budgetSegs=%s pending=%d)" %
                        (name, ge, want_ge, bsegs, pend))
        if stop != want_stop:
            errs.append("%s: 提前停 %s 期望 %s" % (name, stop, want_stop))
        if not start:
            errs.append("%s: 竟然拒绝制作（R148 禁止一刀切）" % name)
        print("  %-28s seg=%2dMB 预算=%s 做完=%s 提前停=%s" %
              (name, seg // MB, "无限" if bsegs is None else bsegs, ge, stop))

    # 查不到空间时 budgetSegs 必须是 None（Kotlin Int.MAX_VALUE），不能算成 0
    if budget(100, {}, 0)[2] is not None:
        errs.append("free<=0 没走放行分支")

    parts = [3 * MB] * 20
    mn, mb = merge_need(parts, 70 * MB)
    if not mb:
        errs.append("合并第二道没拦住")
    mn2, mb2 = merge_need(parts, 200 * MB)
    if mb2:
        errs.append("合并第二道误拦")
    print("  %-28s need=%4dMB 70MB拦=%s / 200MB拦=%s" %
          ("合并第二道", mn // MB, mb, mb2))

    if errs:
        print("FAIL")
        for x in errs:
            print(" -", x)
        return 1
    print("OK · R95 落盘预检对拍 %d 组 + 合并边界 2 组 + 新公式常量一致 / 旧一刀切零回潮" % len(cases))
    return 0


if __name__ == "__main__":
    sys.exit(main())
