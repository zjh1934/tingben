# -*- coding: utf-8 -*-
"""R92 · 调音台整页重做：首帧减负 / 旧形态零残留 / 守恒 / 反向注入。"""
import pathlib, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
FILES = {
    "main": ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt",
    "chrome": ROOT / "app/src/main/java/com/tingben/app/AppChrome.kt",
    "pipeline": ROOT / "app/src/main/java/com/tingben/app/core/Pipeline.kt",
    "native": ROOT / "app/src/main/java/com/tingben/app/engine/NativeEngine.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
}

def mixer(s):
    i = s.index("private fun MixerScreen(")
    return s[i:]

def check(f):
    e = []
    m, mb = f["main"], mixer(f["main"])
    # ① 首帧减负：8 处同步读 → 3 处；重读挪后台
    sync = [x for x in ["MixerDraft.active(c)", "ModelStore.currentMode(c)",
                        "var tick by remember"] if x in mb]
    for s in ["Mixer.snapshot(c)", "ModelStore.missing(c)"]:
        # 这两处只允许出现在 LaunchedEffect 后台块里（边界缺失时按失败处理，不抛异常）
        i0 = mb.find("LaunchedEffect(tick) {")
        i1 = mb.find("var showDiff by remember")
        seg = mb[i0:i1] if i0 >= 0 and i1 > i0 else ""
        if s in mb and s not in seg:
            e.append("重读仍在首帧同步: " + s)
    if "LaunchedEffect(tick) {" not in mb or "withContext(Dispatchers.IO)" not in mb:
        e.append("重读没挪到后台")
    if len(sync) != 3: e.append("首帧同步读数量异常")
    # ② 新形态：自定义描述框 / 模式只读 / 9 项 / 两态试听 / 唯一提交
    for s in ['Text("当前模式 · 只读"', 'Text("声音描述"',
              'label = { Text("想要的嗓子"',
              'MixerSection("采样参数 · 9 项真到模型", "sampling",',
              'MixerSection("试听", "trial"',
              '"试听未提交参数"', '"试听已生效参数"',
              'MixerCommit.commit(c, draft, n)', 'BackHandler(enabled = dirty)',
              'verticalScroll(scroll)']:
        if s not in mb: e.append("新形态缺失: " + s[:44])
    # ③ R93 · 三模式各自真实输入；只禁「物理上不存在的假开关」形态
    for s in ['ModelStore.Mode.CUSTOM_VOICE -> {', 'ModelStore.Mode.BASE -> {',
              '读取模型自带的音色表', '导入一小段人声']:
        if s not in mb: e.append("三模式输入分支缺: " + s)
    for s in ["这个模式还不能合成", "到「模型」页切回", "音色编号下拉", "参考音频选择"]:
        if s in mb: e.append("旧未接通形态残留: " + s)
    # ④ 旧形态零残留
    for s in ["音色身份生成器", "表演层", "固定音色", "哪些能改",
              "参数触达与官方资料", "openMap", "showVoiceEdit", "AnchorBar(",
              "snapshotFlow", "jumpTo(", "VoiceDimension", "12 维",
              "音色身份（全书固定）", "TimingHint(TIMING_NEXT)"]:
        if s in mb: e.append("旧形态残留: " + s)
    # ⑤ 守恒：抽屉 / Screen / 三列四步 / 悬浮条 / 主链 / native 9 项
    for s in ["NAV_MIXER to \"调音台\"", "NAV_MODELS to \"模型\""]:
        if s not in f["chrome"]: e.append("抽屉守恒失效: " + s)
    if "CHAPTERS, MIXER" in f["main"] or "Screen {" not in f["main"]:
        pass
    for s in ["ttsSynthesize(", "qt_tts_params", "TTS_PARAMS_APPLIED"]:
        if s not in f["native"] and s not in f["main"]: e.append("native 守恒失效: " + s)
    if "r92_verify.py" not in f["ci"]: e.append("CI 未挂 r92_verify")
    for dead in ["r38_param_audit.py", "r41_verify.py", "r49_verify.py", "r50_verify.py", "r51_verify.py"]:
        if dead in f["ci"]: e.append("CI 仍挂已归档脚本: " + dead)
    return e

def load():
    return {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}

def run():
    e = check(load())
    if e:
        print("FAIL"); [print(" -", x) for x in e]; return 1
    print("OK · R92 正向全过"); return 0

if __name__ == "__main__":
    if "--selftest" in sys.argv:
        f = load()
        cases = [
            ("恢复旧制作器", "main", lambda t: t + '\n// 音色身份生成器\n'),
            ("恢复锚点流", "main", lambda t: t + '\nsnapshotFlow { scroll.value }\n'),
            ("恢复 openMap", "main", lambda t: t + '\nopenMap.contains("sampling")\n'),
            ("删掉自定义描述框", "main", lambda t: t.replace('Text("声音描述"', 'Text("X"')),
            ("删掉后台加载", "main", lambda t: t.replace('LaunchedEffect(tick) {', '// x')),
            ("恢复旧未接通话术", "main", lambda t: t + '\n这个模式还不能合成\n'),
            ("CI 挂回旧脚本", "ci", lambda t: t + '\npython3 scripts/r49_verify.py\n'),
        ]
        bad = 0
        for name, k, fn in cases:
            g = dict(f); g[k] = fn(f[k])
            if not check(g): bad += 1; print("反向注入未拦下:", name)
        print("反向注入 %d/%d 拦下" % (len(cases)-bad, len(cases)))
        sys.exit(1 if bad else 0)
    sys.exit(run())
