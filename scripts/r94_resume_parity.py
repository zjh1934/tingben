# -*- coding: utf-8 -*-
"""R94 · 续跑对拍：界面显示的「已做好 X 段」必须等于点继续后真正会跳过的段数。

纯 Python 复刻两处真值来源（TaskResume.realProgress / Producer.run 的指纹作废），
不依赖 Android SDK。8 组场景覆盖首次、设置变更、双指纹兼容、坏 WAV、
不同选章分桶、作废后重写 stamp 再恢复。
"""
import hashlib, sys

def h(s):
    return hashlib.sha256(s.encode()).hexdigest()[:16]

def expected_mode(cfg):
    return h("||".join([cfg["mode"], cfg["speaker"], cfg["ref"]]))

def expected_voice(cfg):
    return h(cfg["voice"])

def invalidation(work, cfg):
    """复刻 TaskResume.invalidationReason：返回 None / 'mode' / 'voice'。"""
    m = work.get("mode_stamp.txt")
    if m is not None and m != expected_mode(cfg):
        return "mode"
    v = work.get("voice_stamp.txt")
    if v is not None and v != expected_voice(cfg) and v != cfg["legacy_voice"]:
        return "voice"
    return None

def wavs(work):
    return [k for k, v in work.items()
            if (k.startswith("chunk_") or k.startswith("direct_"))
            and k.endswith(".wav") and v > 44]

def real_progress(work, cfg, selected):
    """复刻 TaskResume.realProgress（R94 先对拍）。"""
    if invalidation(work, cfg) is not None:
        return 0
    return min(selected, len(wavs(work)))

def continue_run(work, cfg):
    """复刻 Producer.run 的两处作废：删 WAV、写新 stamp；文本分段不删。"""
    w = dict(work)
    mode_file = w.get("mode_stamp.txt")
    if mode_file is not None and mode_file != expected_mode(cfg):
        for k in wavs(w): del w[k]
        w["mode_stamp.txt"] = expected_mode(cfg)
    voice_file = w.get("voice_stamp.txt")
    if voice_file is not None and voice_file != expected_voice(cfg) and voice_file != cfg["legacy_voice"]:
        for k in wavs(w): del w[k]
        w["voice_stamp.txt"] = expected_voice(cfg)
    return w

def wav_files(n, start=0):
    return {"chunk_%04d.wav" % (start + i): 48000 for i in range(n)}

BASE = dict(mode="voice_design", speaker="", ref="none", voice="描述A",
            legacy_voice=h("旧版含角色表"))

def case(name, work, cfg, selected, expect_reason=None):
    shown = real_progress(work, cfg, selected)
    after = continue_run(work, cfg)
    kept = len(wavs(after))
    reason = invalidation(work, cfg)
    ok = shown == kept and shown <= selected and reason == expect_reason
    # 文本分段必须留着：作废只删 WAV
    nonwav = [k for k in work if not k.endswith(".wav")]
    if any(k not in after for k in nonwav): ok = False
    print("  %-34s 界面=%d 继续后保留=%d 作废=%-5s %s"
          % (name, shown, kept, reason or "无", "OK" if ok else "FAIL"))
    return ok, shown, kept

def main():
    W = wav_files(3)
    cases = []

    # 1 首次开工，两枚 stamp 都还没写
    cases.append(case("首次：3 段 WAV，无 stamp", dict(W), dict(BASE), 3, None))

    # 2 两枚 stamp 都与当前设置一致
    w2 = dict(W); w2["voice_stamp.txt"] = expected_voice(BASE)
    w2["mode_stamp.txt"] = expected_mode(BASE)
    cases.append(case("设置没变：3 段照旧跳过", w2, dict(BASE), 3, None))

    # 3 换模式 / 换音色输入 → 点继续会全删
    cfg3 = dict(BASE, mode="custom_voice", speaker="Vivian")
    w3 = dict(W); w3["voice_stamp.txt"] = expected_voice(BASE)
    w3["mode_stamp.txt"] = expected_mode(BASE)
    cases.append(case("换模式：界面不许说不用重算", w3, cfg3, 3, "mode"))

    # 4 调音台描述改了 → voice_stamp 不一致
    cfg4 = dict(BASE, voice="描述B")
    w4 = dict(W); w4["voice_stamp.txt"] = expected_voice(BASE)
    w4["mode_stamp.txt"] = expected_mode(BASE)
    cases.append(case("改调音台：界面不许说不用重算", w4, cfg4, 3, "voice"))

    # 5 旧版指纹命中 legacy 兼容 → 不作废
    w5 = dict(W); w5["voice_stamp.txt"] = BASE["legacy_voice"]
    w5["mode_stamp.txt"] = expected_mode(BASE)
    cases.append(case("旧版 voice 指纹：升级不误删", w5, dict(BASE), 3, None))

    # 6 坏 WAV（<=44 字节）不算完成
    w6 = dict(W); w6["chunk_0002.wav"] = 30; w6["ann_0001.json"] = 120
    w6["voice_stamp.txt"] = expected_voice(BASE)
    w6["mode_stamp.txt"] = expected_mode(BASE)
    cases.append(case("坏 WAV + 分段文件：只数 2", w6, dict(BASE), 3, None))

    # 7 不同选章分桶互不干扰
    wA = dict(W); wA["voice_stamp.txt"] = expected_voice(BASE)
    wA["mode_stamp.txt"] = expected_mode(BASE)
    cfgA = dict(BASE, mode="base", ref="ref.wav|1234")
    okA, _, _ = case("only=A 换模式：A 目录归零", wA, cfgA, 3, "mode")
    wB = wav_files(2); wB["voice_stamp.txt"] = expected_voice(BASE)
    wB["mode_stamp.txt"] = expected_mode(BASE)
    okB, _, _ = case("only=B 没变：B 目录仍 2", wB, dict(BASE), 2, None)
    cases.append((okA and okB))

    # 8 作废后 stamp 已重写，再恢复一次不该再误判
    cfg8 = dict(BASE, mode="custom_voice", speaker="Vivian")
    w8 = dict(W); w8["voice_stamp.txt"] = expected_voice(BASE)
    w8["mode_stamp.txt"] = expected_mode(BASE)
    after8 = continue_run(w8, cfg8)
    after8.update(wav_files(2, start=10))
    cases.append(case("作废重写 stamp 后再恢复", after8, cfg8, 2, None))

    bad = sum(1 for c in cases if not (c[0] if isinstance(c, tuple) else c))
    print("\nR94 续跑对拍 %d/%d 通过" % (len(cases) - bad, len(cases)))
    return 1 if bad else 0

if __name__ == "__main__":
    sys.exit(main())
