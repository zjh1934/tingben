#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R161 · 前台服务生命周期收口验证。

这轮动的是服务生命周期，一处写错就是「任务起不来」或「6 小时后 ANR」，
所以单独一份守门，不并进别的批次。

正向守：
  · Android 15 (API 35) 的 Service.onTimeout(int,int) 必须实现，且几秒内 stopSelf
  · 超时统一走 Producer.onServiceTimeout：写回 stopped、暂停队列、保留断点，
    stage 写成可辨识的「已达时长上限」，绝不被当成崩溃自动续跑
  · ApplicationExitInfo 退出原因进事件流与诊断导出（定性「任务怎么没了」）
  · 可选悬浮进度默认关、停止 / 完成 / 超时 / 销毁一律 removeView
  · 不改成 START_REDELIVER_INTENT：空壳服务重启无意义（理由写进 Manifest）
  · 挂进 CI 与 r135 现行清单

反向注入：故意摘回调、摘 stopSelf、把默认改开、把浮窗留在屏幕上、
删退出原因、删弹窗标签、偷偷换 REDELIVER，都必须红。
"""
import ui_sources
import core_sources
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"

FILES = {
    "service": APP / "ProduceService.kt",
    "pipeline": core_sources.source(APP),
    "overlay": APP / "OverlayProgress.kt",
    "eventlog": APP / "diag/EventLog.kt",
    "app": APP / "TingbenApp.kt",
    "prefs": APP / "core/Prefs.kt",
    "manifest": ROOT / "app/src/main/AndroidManifest.xml",
    "main": ui_sources.source(APP),
    "ci": ROOT / ".github/workflows/ci.yml",
    "r135": ROOT / "scripts/r135_verify.py",
}

ANCHORS = {
    "service": [
        "override fun onTimeout(startId: Int, fgsType: Int)",
        "super.onTimeout(startId, fgsType)",
        '"FGS_TIMEOUT"',
        "Producer.onServiceTimeout(applicationContext, fgsType)",
        "UiErrors.report(why)",
        "stopSelf()",
        "buildNotification(this, lastTitle, lastText,\n                    lastDone, lastTotal, true)",
        "return START_NOT_STICKY",
    ],
    "pipeline": [
        "fun onServiceTimeout(c: Context, fgsType: Int)",
        '"PRODUCE_FGS_TIMEOUT"',
        't.stage == "已达时长上限" -> "已达时长上限"',
        'phase = "stopped", stage = "已达时长上限"',
        "_state.value = p.copy(running = false, stage = \"已达时长上限\"",
    ],
    "overlay": [
        "object OverlayProgress",
        "Prefs.overlayProgress(c) && KeepAlive.overlayGranted(c)",
        "WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY",
        "wm?.removeViewImmediate(v)",
        "fun hide(c: Context)",
        "c.startService(ProduceService.cancelIntent(c))",
    ],
    "eventlog": [
        "getHistoricalProcessExitReasons(null, 0, 8)",
        '"APP_EXIT"',
        "fun logExitReasons(c: Context)",
        "fun exitReasonsBlock(c: Context)",
        "ApplicationExitInfo.REASON_LOW_MEMORY",
        "## 进程退出原因（系统记录）",
    ],
    "app": ["EventLog.logExitReasons(this)"],
    "prefs": [
        "fun overlayProgress(c: Context)",
        "getBoolean(KEY_OVERLAY_PROGRESS, false)",
    ],
    "manifest": ["R161：onTimeout(int,int)", "不做 START_REDELIVER_INTENT"],
    "main": [
        "private fun OverlayProgressRow(c: Context)",
        "OverlayProgressRow(c)",
        "if (!it) OverlayProgress.hide(c)",
    ],
}


def load():
    out = {}
    for k, p in FILES.items():
        try:
            out[k] = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            out[k] = ""
    return out


def check(f=None):
    f = f or load()
    e = []
    for key, needles in ANCHORS.items():
        for n in needles:
            if n not in f[key]:
                e.append("R161 正向锚点丢失[%s]: %s" % (key, n[:70]))

    # 子串包含会误判（object OverlayProgressX 也含「object OverlayProgress」），
    # 这四条用结构/计数守，不靠 in。
    if not re.search(r'^object OverlayProgress\s*\{', f["overlay"], re.M):
        e.append("悬浮控件对象声明被改或摘掉")
    a = f["service"].find("override fun onTimeout(")
    b = f["service"].find("override fun onDestroy()")
    timeout_body = f["service"][a:b] if a >= 0 and b > a else ""
    if "stopSelf()" not in timeout_body:
        e.append("onTimeout 回调里没有 stopSelf()，系统会抛 RemoteServiceException 判 ANR")
    if f["eventlog"].count("getHistoricalProcessExitReasons") < 2:
        e.append("退出原因只写进一处：事件流与诊断导出都拿不到")
    if f["eventlog"].count("## 进程退出原因（系统记录）") < 2:
        e.append("诊断导出缺「进程退出原因」段（exportBundle / exportSummary 都要有）")

    # 浮窗必须在四个出口全被收起，少一个就是留在屏幕上
    svc = f["service"]
    hides = svc.count("OverlayProgress.hide(")
    if hides < 4:
        e.append("悬浮窗收起出口不全：ProduceService 只有 %d 处 hide，应 ≥4"
                 "（stopping/finish/onDestroy/onTimeout）" % hides)
    if "OverlayProgress.update(c, lastBook, stage, done, total)" not in svc:
        e.append("进度更新没同步到悬浮窗")

    # 默认关是硬口径，不许悄悄打开
    if re.search(r'getBoolean\(KEY_OVERLAY_PROGRESS,\s*true\)', f["prefs"]):
        e.append("悬浮进度默认变成了开（用户口径：默认关，停止即移除）")

    # 不做空壳服务重启
    if "START_REDELIVER_INTENT" in svc:
        e.append("改成了 START_REDELIVER_INTENT：协程已死，重启的是空壳服务")
    if "不做 START_REDELIVER_INTENT" not in f["manifest"]:
        e.append("Manifest 没写清不做 REDELIVER 的理由，下轮会翻回去")

    # 超时不能被当成崩溃自动续跑
    if 'phase = "interrupted", stage = "已达时长上限"' in f["pipeline"]:
        e.append("超时被写成 interrupted，会被当作崩溃自动续跑并再次撞上限")

    # 挂 CI 与现行清单
    if "python3 scripts/r161_verify.py --selftest" not in f["ci"]:
        e.append("R161 守门没挂 CI")
    if '"r161_verify.py"' not in f["r135"]:
        e.append("r135 现行清单没登记 r161_verify.py")
    return e


def selftest():
    base = load()
    assert not check(base), "基线自己就红了：\n" + "\n".join(check(base)[:8])
    cases = [
        ("onTimeout 被摘", "service",
         "override fun onTimeout(startId: Int, fgsType: Int)", ""),
        ("super.onTimeout 被摘", "service", "super.onTimeout(startId, fgsType)", ""),
        ("FGS_TIMEOUT 日志被摘", "service", '"FGS_TIMEOUT"', '"FGS_DONE"'),
        ("统一停止出口被摘", "service",
         "Producer.onServiceTimeout(applicationContext, fgsType)",
         "Producer.stop()"),
        ("stopSelf 被摘", "service", "        stopSelf()\n", ""),
        ("超时通知不收尾", "service", "lastDone, lastTotal, true", "lastDone, lastTotal, false"),
        ("Producer 出口被摘", "pipeline", "fun onServiceTimeout(c: Context, fgsType: Int)", ""),
        ("超时写成崩溃中断", "pipeline",
         'phase = "stopped", stage = "已达时长上限"',
         'phase = "interrupted", stage = "已达时长上限"'),
        ("弹窗标签丢辨识", "pipeline",
         't.stage == "已达时长上限" -> "已达时长上限"', ""),
        ("悬浮控件本体被摘", "overlay", "object OverlayProgress", "object OverlayProgressX"),
        ("removeView 被摘", "overlay", "wm?.removeViewImmediate(v)", ""),
        ("权限检查被摘", "overlay",
         "Prefs.overlayProgress(c) && KeepAlive.overlayGranted(c)",
         "true"),
        ("默认偷偷打开", "prefs",
         "getBoolean(KEY_OVERLAY_PROGRESS, false)",
         "getBoolean(KEY_OVERLAY_PROGRESS, true)"),
        ("退出原因采集被摘", "eventlog", "getHistoricalProcessExitReasons(null, 0, 8)", ""),
        ("APP_EXIT 日志被摘", "eventlog", '"APP_EXIT"', '"APP_QUIT"'),
        ("诊断导出不带上", "eventlog", "## 进程退出原因（系统记录）", "## 环境"),
        ("App 入口不读", "app", "EventLog.logExitReasons(this)", ""),
        ("Manifest 理由被摘", "manifest", "不做 START_REDELIVER_INTENT", ""),
        ("偷偷换成 REDELIVER", "service", "return START_NOT_STICKY",
         "return START_REDELIVER_INTENT"),
        ("设置开关被摘", "main", "OverlayProgressRow(c)", ""),
        ("CI 漏挂", "ci", "python3 scripts/r161_verify.py --selftest", ""),
        ("现行清单漏登记", "r135", '"r161_verify.py"', '"r161_OLD.py"'),
    ]
    bad = 0
    for name, key, needle, repl in cases:
        st = dict(base)
        if needle == "":
            st[key] = ""
        else:
            # 全局替换：同一锚点在文件里出现多处时（退出原因、诊断段），
            # 只改一处会留下「假绿」，必须整类摘掉才算真正破坏
            st[key] = base[key].replace(needle, repl)
        if not check(st):
            bad += 1
            print("  反向注入漏放:", name)
    print("反向注入 %d/%d 全拦下" % (len(cases) - bad, len(cases)))
    return bad


def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    e = check()
    if e:
        print("R161 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R161 前台服务生命周期：onTimeout / 统一停止出口 / 退出原因诊断 / "
          "悬浮进度四出口收起 / 默认关 / 不做空壳重启：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
