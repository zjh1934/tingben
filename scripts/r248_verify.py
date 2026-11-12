#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R248 · 播放通知借鉴成熟播放器：平台内嵌 MediaSession 接锁屏/线控/蓝牙，
去掉「正在播放/已暂停」状态废话改收听位置 + 进度条。零第三方依赖。

用法：python3 scripts/r248_verify.py [--selftest]
"""
import pathlib, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
CORE = ROOT / "app/src/main/java/com/tingben/app/core"

def files():
    def rd(p): return p.read_text(encoding="utf-8", errors="replace")
    return {
        "session": rd(CORE / "PlaySession.kt"),
        "notif": rd(CORE / "PlayNotif.kt"),
        "queue": rd(CORE / "PlayQueue.kt"),
        "ci": rd(ROOT / ".github/workflows/ci.yml"),
        "r135": rd(ROOT / "scripts/r135_verify.py"),
        "config": rd(ROOT / ".aoci/config.json"),
    }

def check(f):
    e = []
    s, n, q = f["session"], f["notif"], f["queue"]
    # ① 平台 MediaSession：会话、回调、五动作、状态镜像、收起
    for k in ["object PlaySession {", "val token: MediaSession.Token?",
              "MediaSession(c.applicationContext", "s.setCallback(",
              "override fun onPlay()", "override fun onPause()",
              "override fun onSkipToNext()", "override fun onSkipToPrevious()",
              "override fun onStop()", "FLAG_HANDLES_MEDIA_BUTTONS",
              "import android.media.session.PlaybackState as SysPlaybackState",
              "MediaMetadata.Builder()", "METADATA_KEY_DURATION",
              "ms.setPlaybackState(", "SysPlaybackState.Builder()",
              "SysPlaybackState.ACTION_PLAY_PAUSE", "fun deactivate()",
              "SysPlaybackState.STATE_NONE"]:
        if k not in s: e.append("PlaySession 缺: " + k[:52])
    # ② 播放通知：锁控接上、进度条、废话不回潮
    for k in ["PlaySession.ensure(c)", "style.setMediaSession(it)",
              ".setProgress(", "private fun mmss(",
              'mmss(s.positionMs) + " / " + mmss(s.durationMs)']:
        if k not in n: e.append("PlayNotif 缺: " + k[:52])
    # sync 与 deactivate 各有一处；只摘一处时下面这条就红，避免注入漏放。
    if s.count("ms.setPlaybackState(") < 2:
        e.append("PlaybackState 镜像缺一处（sync / deactivate 都要有）")
    if '"正在播放"' in n or '"已暂停"' in n:
        e.append("状态废话回潮（R248 已改成收听位置/时长）")
    # ③ 队列三处接线：主线程建会话、逐状态同步、收起会话
    for k in ["PlaySession.ensure(ctx)", "PlaySession.sync(it, s)",
              "PlaySession.deactivate()"]:
        if k not in q: e.append("PlayQueue 缺接线: " + k[:52])
    # ④ 登记
    for name, src, needle in [("CI", f["ci"], "python3 scripts/r248_verify.py"),
                              ("清单", f["r135"], '"r248_verify.py"'),
                              ("受管根", f["config"], '"r248_verify.py"')]:
        if name == "受管根":
            from r45_common import scope_indexed
            if not scope_indexed(f["config"], "scripts/r248_verify.py"):
                e.append(name + " 漏登记 r248")
        elif needle not in src: e.append(name + " 漏登记 r248")
    if f["ci"].count("r248_verify.py") < 2:
        e.append("CI 正向/反向没挂全")
    return e

def selftest():
    f = files()
    assert not check(f), "基线自己就红了: " + "; ".join(check(f))
    cuts = [
        ("会话被删", "session", "object PlaySession {", ""),
        ("构造被摘", "session", "MediaSession(c.applicationContext", ""),
        ("回调被摘", "session", "s.setCallback(", ""),
        ("线控 flag 被摘", "session", "FLAG_HANDLES_MEDIA_BUTTONS", ""),
        ("切下一段被摘", "session", "override fun onSkipToNext()", ""),
        ("别名 import 被摘", "session",
         "import android.media.session.PlaybackState as SysPlaybackState", ""),
        ("PlaybackState 被摘", "session", "ms.setPlaybackState(", ""),
        ("动作位被摘", "session", "SysPlaybackState.ACTION_PLAY_PAUSE", ""),
        ("deactivate 被摘", "session", "fun deactivate()", ""),
        ("token 被摘", "session", "val token: MediaSession.Token?", ""),
        ("锁控没接通知", "notif", "style.setMediaSession(it)", ""),
        ("进度条被摘", "notif", ".setProgress(", ""),
        ("状态废话回潮", "notif",
         'mmss(s.positionMs) + " / " + mmss(s.durationMs)',
         'if (s.isPlaying) "正在播放" else "已暂停"'),
        ("mmss 被摘", "notif", "private fun mmss(", ""),
        ("notifyNow 不 sync", "queue", "PlaySession.sync(it, s)", ""),
        ("clearNotif 不收起", "queue", "PlaySession.deactivate()", ""),
        ("attach 不建会话", "queue", "PlaySession.ensure(ctx)", ""),
        ("CI 漏挂", "ci", "python3 scripts/r248_verify.py\n", ""),
        ("清单漏登记", "r135", '"r248_verify.py"', ""),
        ("受管范围兜底被摘", "config", '"pattern": "**"', '"pattern": "none/**"'),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        g = dict(f); g[key] = f[key].replace(needle, repl, 1)
        if check(g): print("  反向注入 %-22s 拦下" % name)
        else: print("  反向注入漏放:", name); bad += 1
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad

if __name__ == "__main__":
    if "--selftest" in sys.argv: sys.exit(1 if selftest() else 0)
    e = check(files())
    if e:
        print("R248 FAIL", len(e))
        for x in e: print(" -", x)
        sys.exit(1)
    print("R248 播放通知锁控/进度条/去状态废话：PASS")
