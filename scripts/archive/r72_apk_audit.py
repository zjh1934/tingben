# -*- coding: utf-8 -*-
"""R72 · 1.1.0+72 拆包硬验：CRC / 7 个 arm64 .so / 权重 0 / 无 INTERNET /
版本号 / 提示词资产退场 / 新符号在 / R68 已删类零残留 / native 日志在 / 直链逐字节一致。
用法：python3 scripts/r72_apk_audit.py [--selftest]
"""
import base64, hashlib, io, json, os, re, sys, urllib.request, zipfile
from pathlib import Path

APK = Path('/data/workspace/听本_app-debug.apk')
EXPECT_VER = '1.1.0+72'
NEW = ['合成音频', '调音 ›', '送进声音模型的正文', '章节', '可播', '直接朗读']
BAN = ['语言模型', '数据标注', '文本模型', '标注', 'Huihui', 'no_think',
       'A/B gate', 'MODEL_ID', 'm2507', 'prompt_zh', 'Qwen3-4B',
       'AbGate', 'AUDIT_RUN_BEGIN', 'AUDIT_SUMMARY', 'PROMPT_VERSION',
       'ENGINE_INFO', 'KEY_THINKING', 'AnnotatedPane', 'writeAuditSummary',
       'Adoption', 'JsonSalvage', 'RawDump', 'AuditLog']
WEIGHT_EXT = ('.gguf', '.safetensors', '.onnx', '.pth', '.tflite')


def git_blob_sha(b):
    return hashlib.sha1(b'blob ' + str(len(b)).encode() + b'\0' + b).hexdigest()


def remote_blob_sha():
    url = os.popen('git -C %s remote get-url origin' % Path(__file__).resolve().parents[1]).read().strip()
    tok = re.search(r'https://([^@]+)@github\.com/', url).group(1)
    H = {'Authorization': 'token ' + tok, 'Accept': 'application/vnd.github+json',
         'User-Agent': 'tingben-apk-audit'}

    def get(p):
        return json.loads(urllib.request.urlopen(
            urllib.request.Request('https://api.github.com/repos/zjh1934/ting-ben-apk' + p,
                                   headers=H), timeout=90).read())

    ref = get('/git/ref/heads/main')
    cm = get('/git/commits/' + ref['object']['sha'])
    tree = get('/git/trees/' + cm['tree']['sha'])
    ent = next(e for e in tree['tree'] if e['path'] == 'app-debug.apk')
    blob = get('/git/blobs/' + ent['sha'])
    return ent['sha'], base64.b64decode(blob['content'])


def audit(raw, *, check_remote=False):
    e = []
    z = zipfile.ZipFile(io.BytesIO(raw))
    names = z.namelist()
    if z.testzip() is not None:
        e.append('ZIP CRC 损坏')
    so = [n for n in names if n.startswith('lib/arm64-v8a/') and n.endswith('.so')]
    if len(so) != 7:
        e.append('arm64 .so 不是 7 个: %d' % len(so))
    if any(n.startswith('lib/') and not n.startswith('lib/arm64-v8a/') for n in names):
        e.append('出现非 arm64-v8a 的 .so')
    weights = [n for n in names if n.lower().endswith(WEIGHT_EXT) or
               (n.lower().endswith('.bin') and z.getinfo(n).file_size > 1024 * 1024)]
    if weights:
        e.append('包里混入权重: ' + str(weights[:3]))

    mani = z.read('AndroidManifest.xml')
    mani16 = mani.decode('utf-16le', errors='ignore')
    if 'android.permission.INTERNET' in mani.decode('utf-8', errors='ignore') or \
       'android.permission.INTERNET' in mani16:
        e.append('AndroidManifest 出现 INTERNET 权限')
    if EXPECT_VER not in mani16 and EXPECT_VER.encode() not in mani:
        e.append('版本号不是 ' + EXPECT_VER)
    if any('prompt_zh' in n for n in names):
        e.append('提示词资产仍在包里')

    dex = b''.join(z.read(n) for n in names if re.match(r'classes\d*\.dex$', n))
    if not dex:
        e.append('找不到 dex')
    for s in NEW:
        if s.encode() not in dex:
            e.append('dex 缺新符号: ' + s)
    for s in BAN:
        if s.encode() in dex:
            e.append('dex 旧形态残留: ' + s)
    native = b''.join(z.read(n) for n in so)
    if b'TTS_PARAMS_APPLIED' not in native:
        e.append('native 缺 TTS_PARAMS_APPLIED')

    if check_remote:
        sha, remote_raw = remote_blob_sha()
        if sha != git_blob_sha(raw):
            e.append('直链 blob 与本地 git-blob sha 不一致')
        if remote_raw != raw:
            e.append('直链内容与本地 APK 不是逐字节一致')
    return e, {'bytes': len(raw), 'so': len(so), 'dex': len(dex), 'weights': len(weights)}


def selftest():
    buf = io.BytesIO()
    mani = ('android.permission.INTERNET 1.1.0+71').encode('utf-16le')
    with zipfile.ZipFile(buf, 'w') as z:
        z.writestr('AndroidManifest.xml', mani)
        z.writestr('classes.dex', '语言模型 标注 RawDump AUDIT_RUN_BEGIN'.encode())
        z.writestr('assets/prompt_zh.txt', b'x')
    errs, _ = audit(buf.getvalue())
    must = ['arm64 .so', 'INTERNET', '版本号', '提示词资产', '旧形态残留']
    return all(any(m in x for x in errs) for m in must), errs


def main():
    raw = APK.read_bytes()
    errs, info = audit(raw, check_remote=True)
    if errs:
        print('R72 拆包 FAIL:')
        for x in errs:
            print(' -', x)
        return 1
    print('R72 拆包 PASS · %d 字节 / %d 个 arm64 .so / dex %d 字节 / 权重 %d / 无 INTERNET / %s / 直链逐字节一致'
          % (info['bytes'], info['so'], info['dex'], info['weights'], EXPECT_VER))
    ok, inj = selftest()
    print('反向自检（坏包必须拦下）:', 'PASS' if ok else 'FAIL')
    if not ok:
        print(inj)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(0 if '--selftest' in sys.argv and selftest()[0] else main())
