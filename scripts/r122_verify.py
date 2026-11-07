# -*- coding: utf-8 -*-
"""R122 · 本地改码第二批：拼接落盘原子化 / 假成品过滤 / 章状态失败不删小块 / AAC 时间戳。

正向：新形态存在；零残留：旧写法不在；守恒：原有能力没被顺手删掉。
反向注入：把任一关键片段退回旧形态必须被抓到。
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "pipe": APP / "core" / "Pipeline.kt",
    "lib": APP / "core" / "AudioLibrary.kt",
    "ops": APP / "core" / "AudioOps.kt",
    "ref": APP / "core" / "VoiceRef.kt",
    "main": APP / "MainActivity.kt",
    "hub": APP / "ChapterHub.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
}

POS = [
    ("pipe", 'val part = File(out.parentFile, out.name + ".part")'),
    ("pipe", 'java.io.FileOutputStream(part, false).use { os ->'),
    ("pipe", 'os.fd.sync()'),
    ("pipe", 'if (part.length() != 44L + total)'),
    ("pipe", 'if (!part.renameTo(out))'),
    ("pipe", 'catch (_: Throwable) { part.delete(); false }'),
    ("pipe", 'outFile.delete()'),
    ("pipe", 'File(outFile.parentFile, outFile.name + ".part").delete()'),
    ("pipe", 'AtomicJson.write(File(outFile.parentFile,'),
    ("pipe", 'val pending = (0 until toSpeak.size).count { i ->'),
    ("pipe", 'if (f.exists() && f.length() > 44) return@count false'),
    ("pipe", 'val covered = ci >= 0 && doneChapterFiles[ci]'),
    ("lib", 'if (!file.isFile || file.length() <= 44) null else {'),
    ("lib", 'val body = when (val r = AtomicJson.read(f)) {'),
    ("lib", 'is AtomicJson.Read.Ok -> r.text'),
    ("lib", 'it.length() > 44 && it.name.endsWith(".wav")'),
    ("ops", 'val marked = parts.firstOrNull()?.parentFile'),
    ("ops", 'if (!marked)'),
    ("ops", 'kept = parts.size'),
    ("ops", '章状态没记上'),
    ("ops", 'val ptsUs = (offset - n).toLong() * 1_000_000L / (rate.toLong() * 2L)'),
    ("ops", 'val copied = out.use { o ->'),
    ("ops", 'if (copied <= 0L)'),
    ("ops", 'private fun dropUri(c: Context, uri: Uri)'),
    ("ops", 'android.provider.DocumentsContract.deleteDocument'),
    ("ref", 'val part = File(c.filesDir, NAME + ".part")'),
    ("ref", 'if (!part.renameTo(dst))'),
    ("ref", 'File(c.filesDir, NAME + ".part").delete()'),
    ("ref", 'f.inputStream().use { s ->'),
    ("ref", '"|" + head + "|" + Prefs.refText(c)'),
    ("main", 'if (r.reason.isBlank())'),
    ("main", '} else toast(r.reason)'),
    ("hub", 'import android.widget.Toast'),
    ("hub", 'val diskKey = "${!st.running}|${st.stage}"'),
    ("hub", 'val pagePxState = rememberUpdatedState(pagePx)'),
    ("hub", 'val px = pagePxState.value'),
    ("hub", 'if (Producer.isBusy()) {'),
    ("hub", '一次只跑一本'),
    ("ci", 'r122_verify.py'),
]

ZERO = [
    ("pipe", 'out.outputStream().use { os ->'),
    ("pipe", '!(f.exists() && f.length() > 44)'),
    ("pipe", '.writeText(chJson.toString())'),
    ("lib", 'if (!file.exists()) null else {'),
    ("lib", 'val a = JSONArray(f.readText())'),
    ("lib", '?.filter { it.isFile && it.name.endsWith(".wav") && it.name !in known }'),
    ("ops", 'parts.firstOrNull()?.parentFile?.let { AudioTree.markDone(it, chapter, dst) }'),
    ("ops", 'val ptsUs = offset * 1_000_000L / (rate.toLong() * 2L)'),
    ("ref", 'file(c).writeBytes(outBytes)'),
    ("ref", 'sha(file(c).name + "|" + file(c).length() + "|" + Prefs.refText(c))'),
    ("hub", 'val diskKey = "${st.running}|${st.stage}|${st.done}"'),
    ("hub", 'if (!moving && pagePx > 0f) {'),
]

KEEP = [
    ("pipe", 'fun merge(parts: List<File>, out: File'),
    ("pipe", 'os.write(header(total))'),
    ("pipe", 'EventLog.log(EventLog.Kind.PIPELINE, "MERGE_DONE"'),
    ("pipe", 'val ok = WavMerger.merge(wavs, outFile, config.gapMs)'),
    ("pipe", 'AudioLibrary.register(c, book.id'),
    ("ops", 'fun mergeChapter('),
    ("ops", 'AudioTree.markDone(it, chapter, dst)'),
    ("ops", 'val kept = deleteAll(parts)'),
    ("ops", 'AudioLibrary.lastRegisterOk'),
    ("main", 'AudioOps.deleteMerged(c, it)'),
    ("main", '已拼成整章，原来的小块已清理'),
    ("ref", 'fun save(c: Context, bytes: ByteArray)'),
    ("ref", 'fun pcm(c: Context): FloatArray'),
    ("ref", 'if (!exists(c)) return "none"'),
    ("ops", 'fun copyTo(c: Context, src: File, uri: Uri): Boolean'),
    ("hub", 'Producer.start(c, book, selected'),
    ("hub", 'onStarted(book.id, selected)'),
    ("hub", 'LaunchedEffect(pagePx)'),
    ("hub", 'snapshotFlow { Pair(hScroll.isScrollInProgress, hScroll.value) }'),
]


def check(f):
    e = []
    for k, s in POS:
        if s not in f[k]: e.append('缺正向 %s: %s' % (k, s[:70]))
    for k, s in ZERO:
        if s in f[k]: e.append('旧形态残留 %s: %s' % (k, s[:60]))
    for k, s in KEEP:
        if s not in f[k]: e.append('守恒丢失 %s: %s' % (k, s[:60]))
    return e


def txt(p): return p.read_text(encoding='utf-8', errors='replace')


def main():
    f = {k: txt(p) for k, p in FILES.items()}
    errs = check(f)
    if '--selftest' in sys.argv:
        inj = [
            ('拼接又直接写目标文件', lambda t: dict(t, pipe=t['pipe'].replace(
                'val part = File(out.parentFile, out.name + ".part")',
                'val part = out'))),
            ('拼接不校验长度', lambda t: dict(t, pipe=t['pipe'].replace(
                'if (part.length() != 44L + total)', 'if (false)'))),
            ('拼接失败不留临时文件同步', lambda t: dict(t, pipe=t['pipe'].replace(
                'os.fd.sync()', 'Unit'))),
            ('合并失败不清残留', lambda t: dict(t, pipe=t['pipe'].replace(
                'outFile.delete()', 'Unit'))),
            ('章节起点又非原子写', lambda t: dict(t, pipe=t['pipe'].replace(
                'AtomicJson.write(File(outFile.parentFile,', 'File(outFile.parentFile,'))),
            ('索引又收半截文件', lambda t: dict(t, lib=t['lib'].replace(
                'if (!file.isFile || file.length() <= 44) null else {',
                'if (!file.exists()) null else {'))),
            ('磁盘兜底又收半截文件', lambda t: dict(t, lib=t['lib'].replace(
                'it.length() > 44 && it.name.endsWith(".wav")',
                'it.name.endsWith(".wav")'))),
            ('章起点读取又绕过原子层', lambda t: dict(t, lib=t['lib'].replace(
                'val a = JSONArray(body)', 'val a = JSONArray(f.readText())'))),
            ('章状态失败仍删小块', lambda t: dict(t, ops=t['ops'].replace(
                'if (!marked) {', 'if (false) {'))),
            ('AAC 时间戳又用块尾', lambda t: dict(t, ops=t['ops'].replace(
                '(offset - n).toLong()', 'offset.toLong()'))),
            ('界面忽略失败原因', lambda t: dict(t, main=t['main'].replace(
                'if (r.reason.isBlank()) {', 'if (true) {'))),
            ('参考音频又直接覆盖旧文件', lambda t: dict(t, ref=t['ref'].replace(
                'val part = File(c.filesDir, NAME + ".part")', 'val part = dst'))),
            ('参考音频指纹不看内容', lambda t: dict(t, ref=t['ref'].replace(
                '"|" + head + "|" + Prefs.refText(c)', '"|" + Prefs.refText(c)'))),
            ('复制失败不清半截文件', lambda t: dict(t, ops=t['ops'].replace(
                'private fun dropUri(c: Context, uri: Uri) {',
                'private fun dropUriDisabled(c: Context, uri: Uri) {'))),
            ('复制不看写了多少', lambda t: dict(t, ops=t['ops'].replace(
                'if (copied <= 0L)', 'if (false)'))),
            ('合成期又逐段读盘', lambda t: dict(t, hub=t['hub'].replace(
                'val diskKey = "${!st.running}|${st.stage}"',
                'val diskKey = "${st.running}|${st.stage}|${st.done}"'))),
            ('吸附又用旧列宽', lambda t: dict(t, hub=t['hub'].replace(
                'val px = pagePxState.value', 'val px = pagePx'))),
            ('忙时又假装开工', lambda t: dict(t, hub=t['hub'].replace(
                'if (Producer.isBusy()) {', 'if (false) {'))),
            ('空间估算又把已拼章算成待生成', lambda t: dict(t, pipe=t['pipe'].replace(
                'val covered = ci >= 0 && doneChapterFiles[ci]',
                'val covered = false && doneChapterFiles[ci]'))),
            ('CI 漏挂验证', lambda t: dict(t, ci=t['ci'].replace(
                'r122_verify.py', 'r122_missing.py'))),
        ]
        bad = 0
        for name, mutate in inj:
            if not check(mutate(f)):
                bad += 1; errs.append('反向注入漏放: ' + name)
        print('反向注入 %d/%d 全拦下' % (len(inj) - bad, len(inj)))
    if errs:
        print('R122 FAIL', len(errs))
        for x in errs: print(' -', x)
        return 1
    print('R122 落盘与状态闭环 正向 %d 条 / 零残留 %d 条 / 守恒 %d 条：PASS'
          % (len(POS), len(ZERO), len(KEEP)))
    return 0


if __name__ == '__main__': sys.exit(main())
