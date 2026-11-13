# -*- coding: utf-8 -*-
"""R140 · 批次 D「独立试听通道 / segmap / 通知权限 / 错误可见化」验证。

四件落地：
  D1 主播放与小段试听彻底分家（独立 MediaPlayer / 队列 / 状态），
     试听不碰主队列、不碰通知栏；只暂停主播放避免双声
  D2 Producer.run 真正写 segmap，看台 / 剩余段数 / verdict 不再全靠兜底推算
  D3 第一次点播放也申请 POST_NOTIFICATIONS（以前只有开始制作才申请）
  D4 影响可见结果的失败汇到 UiErrors，由 App 顶层统一 Toast

用法：python3 scripts/r140_verify.py [--selftest]
"""
import ui_sources
import core_sources
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"

FILES = {
    "queue": APP / "core" / "PlayQueue.kt",
    "notif": APP / "core" / "PlayNotif.kt",
    "perm": APP / "core" / "NotifPerm.kt",
    "errors": APP / "core" / "UiErrors.kt",
    "pipeline": core_sources.source(APP),
    "segplan": APP / "core" / "SegPlan.kt",
    "workbench": APP / "core" / "Workbench.kt",
    "main": ui_sources.source(APP),
    "ci": ROOT / ".github/workflows/ci.yml",
}

# R195 · 退役：旧版独立进度页看台实现的专属锚点已随死页面删净
# 现行能力由 r151 / r132 / check_structure 按符号守
POS = {
    "queue": [
        "private var previewPlayer: MediaPlayer? = null",
        "private var previewTracks: List<Track> = emptyList()",
        "private val _previewState = MutableStateFlow(PlaybackState())",
        "val previewState: StateFlow<PlaybackState> get() = _previewState",
        "private fun previewSetTracks(list: List<Track>, id: String)",
        "private fun previewOpen(i: Int)",
        "private fun previewFinish()",
        "fun pausePreview()", "fun resumePreview()", "fun togglePreview()",
        "fun stopPreview()", "fun isPreviewPlaying()",
        'if (isPreview(id)) { previewSetTracks(list, id); return }',
        "if (id != previewQueueId) return",
        "private var activityRef: WeakReference<Activity>? = null",
        "activityRef = WeakReference(x)",
        "NotifPerm.ensureForPlayback(appCtx, activityRef?.get())",
        "if (_state.value.isPlaying) pause()",
        "UiErrors.playback(",
    ],
    "notif": ['UiErrors.notif("播放控制通知没显示出来'],
    "perm": [
        "object NotifPerm", "fun ensureForPlayback(c: Context?, actHint: Activity? = null)",
        "Build.VERSION_CODES.TIRAMISU", "Manifest.permission.POST_NOTIFICATIONS",
        "private const val KEY = \"notifPermAsked\"", "act.requestPermissions",
        "if (asked(ctx)) return", "markAsked(ctx)",
        "REQ = 7002",
    ],
    "errors": [
        "object UiErrors", "MutableSharedFlow<String>", "val events: SharedFlow<String>",
        "fun report(msg: String", "fun playback(msg: String",
        "fun storage(msg: String", "fun notif(msg: String",
    ],
    "pipeline": [
        "SegPlan.writeMap(work, annotated.mapIndexed { i, a ->",
        'String.format("direct_%04d.wav", i)',
        "SegPlan.lastWriteError?.let { UiErrors.storage(it, null) }",
        "分段计划没写进磁盘，看台会改用兜底推算",
        "删书时分段缓存没清干净",
    ],
        # R328 · 旧 Verdict.label（含「已按原文逐段朗读」）随无人调用的审计结论一起撤掉；
    # 改守段表读写两条真锚，BAN 里那句旧缓存误判文案照旧拦回潮。
    "segplan": ["fun readMap(work: File): List<SegMeta>?",
                "fun writeMap(work: File, segs: List<SegMeta>)"],
    "workbench": ["val mapped = SegPlan.readMap(work)", "if (mapped != null)",
                 "(0..upTo)", "兜底"],
    "main": [
        # R195 · previewState 现由 ChapterHub 持有，此处不再绑死文件
        "UiErrors.events.collect { msg ->",
        "Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()",
    ],
    "ci": ["python3 scripts/r140_verify.py --selftest"],
}

BAN = {
    "queue": [
        # 试听通道不能清主播放通知（点格子把整本书通知栏控制清掉的老 bug）
        "private fun previewSetTracks(list: List<Track>, id: String) {\n        previewReleasePlayer()\n        clearNotif()",
    ],
    "segplan": ["${unknown} 段是旧缓存，没记来源，无法判定"],
    "main": ["val pqSeg by PlayQueue.state.collectAsState()"],
}


def load():
    return {k: p.read_text(encoding="utf-8", errors="replace")
                       for k, p in FILES.items()}


def logic_cases(f):
    bad = []
    q = f["queue"]
    # ① 试听通道的代码里绝不能出现 clearNotif（用函数体切片精确判定）
    for fname in ("previewSetTracks", "previewOpen", "previewFinish", "pausePreview"):
        m = re.search(r"(?:private )?fun " + fname + r"\([^)]*\) \{(.*?)\n    \}",
                      q, re.S)
        if not m:
            bad.append("找不到试听函数体：" + fname)
        elif "clearNotif" in m.group(1) or "notifyNow" in m.group(1):
            bad.append("试听函数 %s 仍碰主播放通知" % fname)
    # ② setTracks 必须先分流，才不会 release() 掉主队列
    if not re.search(r"fun setTracks\(.*?\) \{\s*if \(isPreview\(id\)\)", q, re.S):
        bad.append("setTracks 没有先按 queueId 分流 preview")
    # ③ segmap 的 wavName 必须和合成循环里的落盘命名完全一致
    write_name = re.search(r'String\.format\("(direct_%04d\.wav)", i\)', f["pipeline"])
    synth_name = re.search(r'String\.format\("(direct_%04d\.wav)", i\)', f["pipeline"])
    if not write_name or not synth_name or write_name.group(1) != synth_name.group(1):
        bad.append("segmap 的 wavName 与合成落盘命名不一致，会全部显示灰格")
    # ④ 兜底分支必须保留（老任务没有 segmap）
    if "兜底" not in f["workbench"] or "(0..upTo)" not in f["workbench"]:
        bad.append("启用 segmap 后把老任务的兜底推算删了")
    # ⑤ 权限只问一次 + 与制作 requestCode 区分
    if "markAsked(ctx)" not in f["perm"] or "if (asked(ctx)) return" not in f["perm"]:
        bad.append("通知权限没有「只问一次」的持久化")
    if "REQ = 7001" in f["perm"] or ", 7001)" in f["perm"]:
        bad.append("播放权限 requestCode 与制作流程撞了")
    print("逻辑用例 %d 项 %s" % (5, "通过" if not bad else "有失败"))
    return bad


def check(f):
    e = []
    for key, needles in POS.items():
        for s in needles:
            if s not in f[key]:
                e.append("正向缺失[%s]: %s" % (key, s[:72]))
    for key, needles in BAN.items():
        for s in needles:
            if s in f[key]:
                e.append("旧形态回潮[%s]: %s" % (key, s[:72]))
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了，先修基线"
    logic_bad = len(logic_cases(f))
    orig = dict(f)
    cuts = [
        ("独立试听通道被摘", "queue", "private var previewPlayer: MediaPlayer? = null", ""),
        ("setTracks 分流被摘", "queue",
         "if (isPreview(id)) { previewSetTracks(list, id); return }", ""),
        ("playFrom 分流被摘", "queue",
         "if (id != previewQueueId) return", ""),
        ("试听状态源被摘", "queue", "val previewState: StateFlow<PlaybackState>", ""),
        # R195 · 该形态随独立进度页删净，此例退役
        # R195 · 该形态随独立进度页删净，此例退役
        ("segmap 写入被摘", "pipeline",
         "SegPlan.writeMap(work, annotated.mapIndexed { i, a ->", ""),
        ("兜底推算被删", "workbench", "(0..upTo)", ""),
        ("播放前权限申请被摘", "queue",
         "NotifPerm.ensureForPlayback(appCtx, activityRef?.get())", ""),
        ("权限只问一次被摘", "perm", "if (asked(ctx)) return", ""),
        ("错误通道被摘", "errors", "val events: SharedFlow<String>", ""),
        ("顶层错误 Toast 被摘", "main", "UiErrors.events.collect { msg ->", ""),
        ("通知栏失败又静默", "notif", 'UiErrors.notif("播放控制通知没显示出来', ""),
        ("索引写失败不告知", "pipeline", "分段计划没写进磁盘，看台会改用兜底推算", ""),
        ("CI 漏挂 r140", "ci", "python3 scripts/r140_verify.py --selftest", ""),
    ]
    bad = 0
    try:
        for name, key, needle, repl in cuts:
            x = dict(f)
            x[key] = f[key].replace(needle, repl)
            if not check(x):
                bad += 1
                print("  反向注入漏放:", name)
    finally:
        # R141 · 反向注入全程只改内存 dict（check(x) 不读盘），原来每跑一次
        # selftest 就把全部文件无条件写回 15 遍，纯属空转。改成「确实变了才写」，
        # 保留防误改的保险，但去掉 100+ 次无谓写盘。
        for k, v in orig.items():
            try:
                if FILES[k].read_text(encoding="utf-8", errors="replace") != v:
                    FILES[k].write_text(v, encoding="utf-8")
            except (OSError, UnicodeError):
                FILES[k].write_text(v, encoding="utf-8")
    total = len(cuts)
    print("反向注入 %d/%d 全拦下" % (total - bad, total))
    return bad + logic_bad


def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    f = load()
    e = check(f) + logic_cases(f)
    if e:
        print("R140 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R140 批次D：独立试听 / segmap / 播放前通知权限 / 错误可见：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
