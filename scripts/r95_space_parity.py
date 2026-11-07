# -*- coding: utf-8 -*-
"""R95 · 落盘预检公式对拍：Python 复刻 Pipeline.kt 的估算，8 组场景 + 常量一致性。

只验「够不够拦」的判断逻辑，不冒充真机磁盘行为。
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
PIPE = ROOT / "app/src/main/java/com/tingben/app/core/Pipeline.kt"

SEG_FALLBACK = 24_000 * 2 * 60
FACTOR_NUM, FACTOR_DEN = 3, 2
HEADROOM = 16 * 1024 * 1024

def estimate(nseg, existing, work_free, out_free):
    """existing: {段号: 字节}; 返回 (need, free, blocked, pending, seg)。"""
    pending = sum(1 for i in range(nseg) if existing.get(i) is None)
    sample = [v for v in existing.values() if v is not None]
    seg = int(sum(sample) / len(sample)) if sample else SEG_FALLBACK
    merge = nseg * seg
    need = pending * seg * FACTOR_NUM // FACTOR_DEN + merge + HEADROOM
    free = min(work_free, out_free)
    return need, free, need > free, pending, seg

def merge_need(parts, out_free):
    need = sum(parts) + HEADROOM
    return need, need > out_free

def main():
    errs = []
    src = PIPE.read_text(encoding="utf-8")
    for s in ["24_000L * 2L * 60L", "* 3L / 2L + mergeBytes + 16L * 1024 * 1024",
              "16L * 1024 * 1024", "if (needBytes > freeBytes)",
              "if (finalBytes > outFile.parentFile!!.usableSpace)",
              "minOf(work.usableSpace, AudioLibrary.dir(c).usableSpace)"]:
        if s not in src:
            errs.append("实现公式漂移: " + s)
    if "if (needBytes >= freeBytes)" in src:
        errs.append("边界被改成 >=，刚好够会被误拦")

    MB = 1024 * 1024
    cases = [
        ("新书 100 段 / 空间足", 100, {}, 800 * MB, 800 * MB, False, 100),
        ("新书 100 段 / 只剩 1MB", 100, {}, 1 * MB, 1 * MB, True, 100),
        ("续跑已落 80 段 / 中等空间", 100,
         {i: 2 * MB for i in range(80)}, 400 * MB, 400 * MB, False, 20),
        ("续跑已落 80 段 / 只剩 10MB", 100,
         {i: 2 * MB for i in range(80)}, 10 * MB, 10 * MB, True, 20),
        ("全部已落盘 / 只差合并", 10, {i: 3 * MB for i in range(10)},
         60 * MB, 60 * MB, False, 0),
        ("零段不除零", 0, {}, 32 * MB, 32 * MB, False, 0),
        ("边界：free == need 不拦", 100, {}, 0, 0, None, 100),
        ("边界：free == need-1 拦", 100, {}, 0, 0, None, 100),
    ]
    for name, nseg, ex, wf, of, want, pending in cases:
        need, free, blocked, pend, seg = estimate(nseg, ex, wf, of)
        if pend != pending:
            errs.append("%s: pending %d != %d" % (name, pend, pending))
        if want is None:
            if "边界：free == need 不拦" in name:
                f2 = need
                _, _, b2, _, _ = estimate(nseg, ex, f2, f2)
                if b2:
                    errs.append("刚好够被误拦")
            else:
                f2 = need - 1
                _, _, b2, _, _ = estimate(nseg, ex, f2, f2)
                if not b2:
                    errs.append("差 1 字节没拦住")
        elif blocked != want:
            errs.append("%s: blocked=%s 期望 %s (need=%d free=%d)" %
                        (name, blocked, want, need, free))
        print("  %-28s need=%4dMB free=%4dMB 拦=%s pending=%d seg=%dMB" %
              (name, need // MB, free // MB, blocked, pend, seg // MB))

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
    print("OK · R95 落盘预检对拍 %d 组 + 合并边界 2 组 + 公式常量一致" % len(cases))
    return 0

if __name__ == "__main__":
    sys.exit(main())
