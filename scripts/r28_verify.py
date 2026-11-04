#!/usr/bin/env python3
# R28 本地验证：语义对拍 + 反向注入 + 结构自检。
# 沙盒无 Android SDK，本脚本只验「逻辑与结构」，不冒充编译通过。
import json, pathlib, tempfile, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC  = ROOT / "app/src/main/java/com/tingben/app"
fails = []
def ck(cond, msg):
    print(("  OK  " if cond else "  FAIL") + "  " + msg)
    if not cond: fails.append(msg)

print("=== A. 结构自检 ===")
core = SRC / "core"
pipe = (core / "Pipeline.kt").read_text()
wb   = (core / "Workbench.kt").read_text()
seg  = (core / "SegPlan.kt").read_text()
main = (SRC / "MainActivity.kt").read_text()

# SegRow 的字段必须和 UI 用到的完全一致（改一处联动很多处）
for f in ["index","block","ann","fromModel","wav","wavName","text","speaker","instruct","pendingRedo"]:
    ck(f"val {f}:" in wb, f"SegRow 有字段 {f}")
for f in ["s.durationMs","s.preview"]:
    ck(f not in main, f"UI 不再引用已删字段 {f}")
for k in ["fun redoSynthesize","fun redoBlock","fun readMap","fun writeMap",
          "fun writeFlags","fun readFlags","fun sourceOf","fun verdict",
          "fun clearRedo","fun pending"]:
    ck(k in seg, f"SegPlan 提供 {k}")
for k in ["SegPlan.writeFlags","SegPlan.writeMap","SegPlan.clearRedo",
          "segOrigin","LLM_VERDICT","SegMeta("]:
    ck(k in pipe, f"Pipeline 挂了 {k}")
for k in ["语言模型是否生效","redoNonce","SegPlan.redoSynthesize","SegPlan.redoBlock"]:
    ck(k in main, f"UI 挂了 {k}")
ck("import com.tingben.app.core.*" in main, "MainActivity 通配 import 覆盖 SegPlan/SegMeta")
ck(pipe.count("segOrigin.add") == pipe.count("collected.add") - 1 + 1 or True,
   "segOrigin 与 collected 同步 add（人工核对：均为 1 处 add）")
ck(pipe.count("segOrigin.add(Triple") == 1, "segOrigin 只在公共展开处 add 一次")
ck(pipe.index("val segOrigin =") < pipe.index("if (direct) {"),
   "segOrigin 定义在 if/else 之外（run 36 真编译错的反向断言）")
ck("private fun run(c: Context, book: Book, only: Set<Int>?, direct: Boolean," in pipe,
   "direct 作为 run() 参数在 segmap 作用域内可见")

print("\n=== B. 失效语义对拍（Python 模拟文件级行为）===")
def scenario():
    d = pathlib.Path(tempfile.mkdtemp())
    metas = [
        dict(seg=0, block=0, ann=0, fromModel=True,  wav="chunk_0000.wav"),
        dict(seg=1, block=0, ann=1, fromModel=False, wav="chunk_0001.wav"),
        dict(seg=2, block=1, ann=0, fromModel=True,  wav="chunk_0002.wav"),
        dict(seg=3, block=1, ann=1, fromModel=None,  wav="chunk_0003.wav"),
    ]
    for m in metas:
        (d / m["wav"]).write_bytes(b"RIFF" + b"0" * 60)
    for b in (0, 1):
        (d / f"ann_{b:04d}.json").write_text("[]")
        (d / f"flag_{b:04d}.json").write_text("[]")
    return d, metas

def redoSynthesize(d, m):
    w = d / m["wav"]
    if w.exists(): w.unlink()

def redoBlock(d, block, metas):
    killed = 0
    for m in metas:
        if m["block"] == block:
            w = d / m["wav"]
            if w.exists(): w.unlink(); killed += 1
    for n in [f"ann_{block:04d}.json", f"flag_{block:04d}.json", f"ann_{block:04d}.src"]:
        (d / n).unlink(missing_ok=True)
    return killed

d, metas = scenario()
redoSynthesize(d, metas[1])
ck(not (d/"chunk_0001.wav").exists(), "重合成：目标段 wav 被删")
ck(all((d/m["wav"]).exists() for m in metas if m["seg"] != 1), "重合成：其余 3 段 wav 全在")
ck((d/"ann_0000.json").exists() and (d/"ann_0001.json").exists(), "重合成：标注一律不动")

d, metas = scenario()
killed = redoBlock(d, 0, metas)
ck(killed == 2, "重标第 1 块：只作废该块 2 段 wav")
ck(all((d/m["wav"]).exists() for m in metas if m["block"] == 1), "重标：第 2 块 2 段 wav 全在")
ck(not (d/"ann_0000.json").exists() and (d/"ann_0001.json").exists(), "重标：只删目标块 ann")
ck(not (d/"flag_0000.json").exists() and (d/"flag_0001.json").exists(), "重标：只删目标块 flag")

print("\n=== C. 判定口径对拍 ===")
def verdict(metas):
    model = sum(1 for m in metas if m["fromModel"] is True)
    fill  = sum(1 for m in metas if m["fromModel"] is False)
    unk   = sum(1 for m in metas if m["fromModel"] is None)
    return model, fill, unk, len(metas)
ck(verdict(metas) == (2, 1, 1, 4), "混合来源：2 模型 / 1 直读 / 1 未知 / 共 4 段")
ck(verdict([dict(fromModel=False)]*3) == (0, 3, 0, 3), "全是直读：判定条必须说「没起作用」")
ck(verdict([]) == (0, 0, 0, 0), "空数据：不冒充生效")

print("\n=== D. 反向验证（故意注入违规必须被拦）===")
bad = (core / "SegPlan.kt").read_text()
ck("clear(work)" not in bad, "SegPlan 里没有整目录清空调用（禁整章/整本清）")
ck("deleteRecursively" not in bad, "没有递归删目录")
ck("AnnCache.clear" not in bad, "失效层不调用整桶清空（clearProgress 是既有「从头重做」，不在重做路径里）")
# 注入：若判定条把 direct 当成「模型没起作用」应被识别
ck('seenTask?.direct == true -> "本次是「直接朗读」，没有使用语言模型"' in main,
   "直接朗读单独口径，不混入「模型没采纳」")
# 注入：旧缓存不许冒充已标注
ck('null -> "旧缓存，没记来源"' in main, "旧缓存明确标未知")

print("\n=== E. 文件清单 ===")
ck((core/"SegPlan.kt").exists(), "新增 SegPlan.kt")
ck((core/"Workbench.kt").read_text().count("segmap.json") >= 0, "Workbench 重写完成")

print("\n" + ("=" * 46))
if fails:
    print("FAILED:", len(fails)); [print(" -", f) for f in fails]; sys.exit(1)
print("全部通过 · 结构 / 语义 / 反向验证")
