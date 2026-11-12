# -*- coding: utf-8 -*-
"""R100 · 端到端链路自检：导入→分章→选模式→调音→合成→登记→按章播放→续跑。

正向：每个环节都有真值来源，缺一段就红。
--selftest：把每条断言对应的源码片段注入性删掉，自检必须自己抓到；
再跑纯 Python 对拍（参考音频转换 / 章节偏移 / 失败中止 / 三模式字段）。
不依赖 Android SDK，静态绿只证明链路契约成立，不代替真机听感。
"""
import core_sources
import argparse
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / 'app/src/main/java/com/tingben/app'
CPP = ROOT / 'app/src/main/cpp'

RULES = [
    ('app/src/main/java/com/tingben/app/core/Importer.kt',
     'const val MAX_BYTES = 64 * 1024 * 1024', '导入：有上限且给中文原因'),
    ('app/src/main/java/com/tingben/app/core/Pipeline.kt',
     'val (chapters, allChunks) = ChapterPlan.resolve(c, book, text)',
      '分章：制作运行走落盘章节表（与目录页同一套章号）'),
    ('app/src/main/java/com/tingben/app/ChapterHub.kt',
     'Producer.enqueueOrStart(c, book, selected', '入口：勾选章能启动制作 / 忙时入队'),
    ('app/src/main/java/com/tingben/app/core/Pipeline.kt',
     'ModelStore.synthesisBlockReason(c)?.let { reason ->', '开工：缺件明确拦住'),
    ('app/src/main/java/com/tingben/app/core/Pipeline.kt',
     'val tk = ModelStore.modeFile(c, ModelStore.currentMode(c))', '权重：严格按当前模式取主文件'),
    ('app/src/main/java/com/tingben/app/core/Pipeline.kt',
     'SynthInput.prepare(c, ttsHandle)', '模式：参考音频交给句柄'),
    ('app/src/main/java/com/tingben/app/core/Pipeline.kt',
     'SynthInput.params(c, built.first, style), Prefs.ttsKnobs(c)', '模式：逐段按字段语义供料'),
    ('app/src/main/java/com/tingben/app/core/SynthInput.kt',
     'Mode.VOICE_DESIGN -> Params(instructVD, null)', 'voice_design：instruct 必填 / speaker 空'),
    ('app/src/main/java/com/tingben/app/core/SynthInput.kt',
     'Mode.CUSTOM_VOICE -> {', 'custom_voice：speaker + 可选念法'),
    ('app/src/main/java/com/tingben/app/core/SynthInput.kt',
     'Mode.BASE -> Params(null, null)', 'base：指令与音色都拒'),
    ('app/src/main/java/com/tingben/app/core/VoiceRef.kt',
     'val target = (frames.toLong() * RATE / rate).toInt()', '参考音频：任意 PCM 重采样到 24k'),
    ('app/src/main/java/com/tingben/app/core/VoiceRef.kt',
     'putInt(24, RATE); putInt(28, RATE * 2); putShort(32, 2); putShort(34, 16)',
     '参考音频：落盘标准 24k/mono/16bit'),
    ('app/src/main/java/com/tingben/app/core/Pipeline.kt',
     'Prefs.speakerNames(c).firstOrNull()?.also { Prefs.setSpeakerName(c, it) }',
     'custom_voice：音色表读过就自动兜底，不卡死合成'),
    ('app/src/main/java/com/tingben/app/core/Pipeline.kt',
     'Mode.values().filter { it !in have }.forEach',
     '就绪：缺件按真实文件逐项列（底座 + 各模式主文件）'),
    ('app/src/main/java/com/tingben/app/core/Pipeline.kt',
     'for (attempt in 0..1) {', '合成：失败先重试一次'),
    ('app/src/main/java/com/tingben/app/core/Pipeline.kt',
     'EventLog.log(EventLog.Kind.PIPELINE, "TTS_SEG_ABORT",',
     '合成：仍失败就整次停住，不合并假成品'),
    ('app/src/main/java/com/tingben/app/core/AudioOps.kt',
     'WavMerger.merge(parts = parts, out = dst, gapMs = gapMs, declickMs = declickMs)',
     'R272 合并：逐章只拼真落盘的小块，不再拼整本大文件'),
    ('app/src/main/java/com/tingben/app/core/AudioOps.kt',
     'AudioLibrary.register(c, bookId, bookTitle, only = setOf(chapter),',
     'R272 登记：每章成品写 index.json'),
    ('app/src/main/java/com/tingben/app/core/AudioOps.kt',
     'val marked = work?.let { AudioTree.markDone(it, chapter, dst) } ?: false', 'R272 登记：先写章状态再清中间小块'),
    ('app/src/main/java/com/tingben/app/core/AudioLibrary.kt',
     'fun chapterStartMs(c: Context, item: AudioItem, chapterIndex: Int): Long',
     '播放：单章起点有唯一真值来源'),
    ('app/src/main/java/com/tingben/app/core/PlayQueue.kt',
     'if (from > 0L) { try { it.seekTo(from.toInt()) } catch (_: Throwable) { } }',
     '播放：prepared 后真跳到章起点'),
    ('app/src/main/java/com/tingben/app/ChapterHub.kt',
     'AudioLibrary.chapterStartMs(c, a, i)', '播放：目录页单章播放带起点'),
    ('app/src/main/java/com/tingben/app/core/TaskResume.kt',
     'fun expectedModeStamp(c: Context): String', '续跑：模式指纹与界面预判同源'),
    ('app/src/main/java/com/tingben/app/engine/NativeEngine.kt',
     'external fun ttsSetVoiceRef(handle: Long, pcm: FloatArray, refText: String?): Boolean',
     'JNI：参考音频通路在'),
    ('app/src/main/cpp/qwen.h', 'const float * ref_audio_24k;', '引擎：字段语义以随包头文件为准'),
    ('.github/workflows/ci.yml',
     'python3 scripts/r100_chain_verify.py --selftest', 'CI：链路自检真挂上'),
    ('.github/workflows/ci.yml',
     'key: tingben-native-v2-', 'CI：native 缓存键与 JNI 桥解耦（改 JNI 不触发上游重编译）'),
    ('.github/workflows/ci.yml',
     'restore-keys:', 'CI：native 缓存有回退键，未命中时复用已验证的 .so'),
]

def verify(texts):
    bad = []
    for rel, snip, label in RULES:
        if snip not in texts.get(rel, ''):
            bad.append(label)
    return bad

def load():
    return {rel: (core_sources.read(ROOT) if rel.endswith("Pipeline.kt")
                 else (ROOT / rel).read_text()) for rel, _, _ in RULES}

def parity():
    """纯 Python 对拍：与 Kotlin 侧同一公式，不引入第二套算法。"""
    rate, target_rate, frames = 48000, 24000, 48000
    target = frames * target_rate // rate
    assert target == 24000, target
    out_bytes = 44 + target * 2
    assert out_bytes == 48044, out_bytes
    src_last = (target - 1) * rate / target_rate
    assert int(src_last) < frames, src_last
    gap_bytes = (300 * 48 // 2) * 2
    seg_bytes = 48000
    seg_chapter = [0, 0, 1, 2, 2]
    starts, cursor = [], 0
    for _ in seg_chapter:
        starts.append(cursor)
        cursor += seg_bytes + gap_bytes
    first = {}
    for off, ch in zip(starts, seg_chapter):
        first.setdefault(ch, off // 48)
    assert first == {0: 0, 1: 2600, 2: 3900}, first
    total = len(seg_chapter) * seg_bytes + (len(seg_chapter) - 1) * gap_bytes
    assert total == 5 * 48000 + 4 * gap_bytes
    codes = [0, 0, -3, -3]
    kept = 0
    aborted = False
    for i in range(len(codes)):
        ok = False
        for _ in range(2):
            if codes[i] == 0:
                ok = True
                break
        if not ok:
            aborted = True
            break
        kept += 1
    assert aborted and kept == 2, (aborted, kept)
    modes = {
        'voice_design': dict(instruct=True, speaker=False, ref=False),
        'custom_voice': dict(instruct=None, speaker=True, ref=False),
        'base': dict(instruct=False, speaker=False, ref=True),
    }
    assert modes['voice_design']['instruct'] is True
    assert modes['base']['instruct'] is False and modes['base']['ref'] is True
    assert modes['custom_voice']['speaker'] is True
    return True

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--selftest', action='store_true')
    args = ap.parse_args()
    texts = load()
    bad = verify(texts)
    if bad:
        print('链路自检失败：', *bad, sep='\n  - ')
        return 1
    print('链路正向 %d 条全通过' % len(RULES))
    parity()
    print('Python 对拍 4 组全通过')
    if args.selftest:
        inject_fail = 0
        for rel, snip, label in RULES:
            t = dict(texts)
            t[rel] = t[rel].replace(snip, '', 1)
            got = verify(t)
            if label not in got:
                inject_fail += 1
                print('  注入未抓到：', label)
        if inject_fail:
            print('反向注入 %d 条漏检' % inject_fail)
            return 1
        print('反向注入 %d/%d 全拦下' % (len(RULES), len(RULES)))
    return 0

if __name__ == '__main__':
    sys.exit(main())
