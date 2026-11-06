# -*- coding: utf-8 -*-
"""R93 · 三模式真正接通：字段链路 / 参考音频 / mode_stamp / 守恒 / 反向注入。"""
import pathlib, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
FILES = {
    "cpp": ROOT / "app/src/main/cpp/tingben_native.cpp",
    "ne": ROOT / "app/src/main/java/com/tingben/app/engine/NativeEngine.kt",
    "voice": ROOT / "app/src/main/java/com/tingben/app/core/VoiceRef.kt",
    "synth": ROOT / "app/src/main/java/com/tingben/app/core/SynthInput.kt",
    "prefs": ROOT / "app/src/main/java/com/tingben/app/core/Prefs.kt",
    "draft": ROOT / "app/src/main/java/com/tingben/app/core/MixerDraft.kt",
    "pipe": ROOT / "app/src/main/java/com/tingben/app/core/Pipeline.kt",
    "main": ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt",
    "chrome": ROOT / "app/src/main/java/com/tingben/app/AppChrome.kt",
    "manifest": ROOT / "contracts/model-manifest.json",
    "ci": ROOT / ".github/workflows/ci.yml",
}

def mixer(s):
    return s[s.index("private fun MixerScreen("):]

def check(f):
    e = []
    cpp, ne, voice, synth = f["cpp"], f["ne"], f["voice"], f["synth"]
    prefs, draft, pipe, mb = f["prefs"], f["draft"], f["pipe"], mixer(f["main"])
    for s in ['qt_model_type', 'tp.ref_audio_24k', 'tp.ref_text', 'tp.ref_n_samples',
              'ttsSetVoiceRef', 'ttsClearVoiceRef', 'g_refs.erase(q)',
              'refLock.owns_lock()', 'refLock.lock()', 'std::map<qt_context *, VoiceRefState>']:
        if s not in cpp: e.append("native 缺: " + s)
    if 'mode=%s' not in cpp: e.append("native 日志未记模式")
    for s in ['external fun ttsSetVoiceRef(', 'external fun ttsClearVoiceRef(']:
        if s not in ne: e.append("JNI 声明缺: " + s)
    for s in ['24kHz', 'channels != 1', 'rate != RATE', 'bits != 16',
              'fun pcm(c: Context): FloatArray', 'fun fingerprint(c: Context)']:
        if s not in voice: e.append("VoiceRef 缺: " + s)
    for s in ['fun prepare(c: Context, handle: Long)', 'fun params(c: Context',
              'ModelStore.Mode.VOICE_DESIGN -> Params(instructVD, null)',
              'ModelStore.Mode.BASE -> Params(null, null)', 'ttsSetVoiceRef(handle, pcm, txt)']:
        if s not in synth: e.append("SynthInput 缺: " + s)
    for s in ['KEY_SPEAKER', 'KEY_SPEAKER_NAMES', 'KEY_REF_TEXT', 'fun speakerName(', 'fun refText(']:
        if s not in prefs: e.append("Prefs 缺: " + s)
    for s in ['val speaker: String = ""', 'val refText: String = ""',
              'Prefs.setSpeakerName(c, d.speaker)', 'Prefs.setRefText(c, d.refText)']:
        if s not in draft: e.append("草稿缺: " + s)
    for s in ['modeFile(c, ModelStore.currentMode(c))', 'mode_stamp.txt', 'MODE_INVALIDATE',
              'SynthInput.prepare(c, ttsHandle)', 'val v = SynthInput.params(',
              'ttsSynthesize(ttsHandle, a.text, v.instruct, v.speaker, "chinese"',
              '"mode" to ModelStore.currentMode(c).id']:
        if s not in pipe: e.append("主链缺: " + s)
    for s in ['先在调音台挑一个预设音色，再合成。', '先在调音台导入一小段参考音频，再合成。']:
        if s not in pipe: e.append("拦截文案缺: " + s)
    if '还不能用来合成音频' in pipe: e.append("旧一刀切拦截仍在")
    for s in ['ModelStore.Mode.CUSTOM_VOICE -> {', 'ModelStore.Mode.BASE -> {',
              '读取模型自带的音色表', '导入一小段人声', '念法要求 · 可留空',
              '这段录音念的是什么 · 可留空', 'SynthInput.prepare(c, h)', 'val pickRef =']:
        if s not in mb: e.append("调音台缺: " + s)
    for s in ['还不能合成', '到「模型」页切回', '切回「描述造音色」即可继续']:
        if s in mb: e.append("调音台仍有旧拦截话术: " + s)
    if '"voice_design", "custom_voice", "base"' not in f["manifest"]:
        e.append("manifest 未标记三模式接通")
    if 'r93_verify.py' not in f["ci"]: e.append("CI 未挂 r93_verify")
    for s in ['NAV_MIXER to "调音台"', 'NAV_MODELS to "模型"']:
        if s not in f["chrome"]: e.append("抽屉守恒失效: " + s)
    if 'SETTINGS, MODELS, MIXER, CHAPTERS }' not in f["main"]: e.append("Screen 9 值失效")
    for s in ['ttsSynthesize(', 'commitAll()', 'verticalScroll(scroll)']:
        if s not in f["main"]: e.append("主链守恒失效: " + s)
    return e

def load():
    return {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}

def run():
    e = check(load())
    if e:
        print("FAIL"); [print(" -", x) for x in e]; return 1
    print("OK · R93 正向全过"); return 0

if __name__ == "__main__":
    if "--selftest" in sys.argv:
        f = load()
        cases = [
            ("删 ref 通路", "cpp", lambda t: t.replace('tp.ref_audio_24k = it->second.pcm.data();', '// x')),
            ("恢复恒 null speaker", "pipe", lambda t: t.replace('v.instruct, v.speaker, "chinese"', 'v.instruct, null, "chinese"')),
            ("删 mode_stamp", "pipe", lambda t: t.replace('mode_stamp.txt', 'x.txt')),
            ("恢复一刀切拦截", "pipe", lambda t: t + '\n// 还不能用来合成音频\n'),
            ("调音台恢复旧话术", "main", lambda t: t + '\n// 还不能合成\n'),
            ("删参考音频格式校验", "voice", lambda t: t.replace('rate != RATE', 'false')),
            ("删唯一装配点", "synth", lambda t: t.replace('fun prepare(c: Context, handle: Long)', 'fun nope(')),
            ("草稿绕开唯一提交", "draft", lambda t: t.replace('Prefs.setSpeakerName(c, d.speaker)', '// x')),
            ("manifest 退回单模式", "manifest", lambda t: t.replace('"voice_design", "custom_voice", "base"', '"voice_design"')),
            ("CI 摘掉 r93", "ci", lambda t: t.replace('r93_verify.py', 'x.py')),
        ]
        bad = 0
        for name, k, fn in cases:
            g = dict(f); g[k] = fn(f[k])
            if not check(g): bad += 1; print("反向注入未拦下:", name)
        print("反向注入 %d/%d 拦下" % (len(cases)-bad, len(cases)))
        sys.exit(1 if bad else 0)
    sys.exit(run())
