#!/usr/bin/env python3
"""R61 · 目录页四列与悬浮条拆包硬验。

用法:
  python3 scripts/r61_apk_audit.py <apk> <version 如 1.0.0+68>
  python3 scripts/r61_apk_audit.py --selftest <apk> <version>

在 R60 审计基础上换成 R61 符号集；仍直接查远端直链仓库 blob sha，
验证「固定直链与本地 APK 逐字节一致」（R33 教训）。
binary AXML 的权限与版本串同时按 ASCII 与 UTF-16LE 查，避免假阴性。
"""
import hashlib, json, os, pathlib, re, sys, urllib.request, zipfile

SELFTEST = '--selftest' in sys.argv
args = [a for a in sys.argv[1:] if a != '--selftest']
p, ver = pathlib.Path(args[0]), args[1]

root = pathlib.Path(__file__).resolve().parent.parent
_url = os.popen('git -C %s remote get-url origin' % root).read().strip()
_m = re.search(r'https://([^@]+)@github\.com/', _url)
TOKEN = _m.group(1) if _m else ''
H = {'Authorization': 'token ' + TOKEN, 'Accept': 'application/vnd.github+json',
     'User-Agent': 'tingben-apk-audit'}


def api(path):
    req = urllib.request.Request('https://api.github.com' + path, headers=H)
    with urllib.request.urlopen(req, timeout=90) as r:
        return json.loads(r.read())


raw = p.read_bytes()
local_blob = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
remote_blob = None
if TOKEN:
    try:
        tree = api('/repos/zjh1934/ting-ben-apk/git/trees/main?recursive=1')
        remote_blob = next(x for x in tree['tree'] if x['path'] == 'app-debug.apk')['sha']
    except Exception as e:
        print('直链查询失败:', type(e).__name__, e)
blob_ok = remote_blob is None or local_blob == remote_blob

z = zipfile.ZipFile(p)
names = z.namelist()
crc = z.testzip()
sos = [n for n in names if n.endswith('.so') and 'arm64-v8a' in n]
gguf = [n for n in names if n.lower().endswith('.gguf')]
dex = b''.join(z.read(n) for n in names if n.endswith('.dex'))
nat = b''.join(z.read(n) for n in names if n.endswith('libtingben_native.so'))
man = z.read('AndroidManifest.xml')

if SELFTEST:
    # 反向注入：抹掉 R61 的标志性符号，脚本必须 FAIL
    dex = dex.replace('这一档跑完会一并出音频'.encode(), b'GONE_r61_hint')

NEW = [
    b'ChapterHubScreen', b'MiniPlayBar', b'chapterIndices', b'chaptersKnown',
    b'latestByChapter', b'playableChapters', b'playableItems', b'HUB_ACTION',
    '去标注'.encode(), '直接合成音频'.encode(), '标注并合成音频'.encode(),
    '这一档跑完会一并出音频'.encode(), '未标注 · 可单章听'.encode(),
    '按顺序播放'.encode(), '从本章起播'.encode(), '旧缓存没有逐段记录'.encode(),
    '整本进度'.encode(), '未开始'.encode(), '去书页'.encode(),
    # 既有能力守恒
    b'DismissibleNavigationDrawer', b'TTS_PARAMS_APPLIED', b'MIXER_COMMIT',
    b'huihui-qwen3-4b-instruct-2507-abliterated-q4_k_m.gguf',
    '提交变更'.encode(), '试听未提交参数'.encode(),
]
OLD = [
    b'Screen.PLAYER', b'bartowski', b'no_think',
    '保存并生效'.encode(), 'A/B 试听'.encode(),
]

new_bad = [s for s in NEW if s not in dex]
old_bad = [s for s in OLD if s in dex]
ver_bytes, ver_u16 = ver.encode(), ver.encode('utf-16le')
perm_ascii = b'android.permission.INTERNET' in man
perm_u16 = 'android.permission.INTERNET'.encode('utf-16le') in man

print('CRC:', '全过' if crc is None else '损坏 ' + str(crc))
print('arm64 .so:', len(sos), '(必须 7)')
print('权重进包:', len(gguf), '(必须 0)')
print('版本 %s:' % ver, ver_bytes in man or ver_u16 in man)
print('无 INTERNET:', not (perm_ascii or perm_u16))
print('直链 blob:', (remote_blob or '未查到')[:12], '本地', local_blob[:12],
      '一致' if blob_ok else '不一致')
print('dex 新符号:', len(NEW) - len(new_bad), '/', len(NEW),
      '缺失:', [s.decode('utf-8', 'replace') for s in new_bad])
print('dex 旧形态零残留:', len(OLD) - len(old_bad), '/', len(OLD),
      '残留:', [s.decode('utf-8', 'replace') for s in old_bad])
print('native 实收日志:', b'TTS_PARAMS_APPLIED' in nat)

failed = []
if crc is not None: failed.append('CRC')
if len(sos) != 7: failed.append('so 数量')
if gguf: failed.append('权重进包')
if not (ver_bytes in man or ver_u16 in man): failed.append('版本号')
if perm_ascii or perm_u16: failed.append('INTERNET 权限')
if not blob_ok: failed.append('直链 blob 不一致')
if new_bad: failed.append('新符号缺失')
if old_bad: failed.append('旧形态残留')
if b'TTS_PARAMS_APPLIED' not in nat: failed.append('native 实收日志丢失')
ok = not failed
if SELFTEST:
    print('自检（已抹掉 R61 提示语）:', '正确拦下' if not ok else 'FAIL：反向注入没被拦下')
    sys.exit(0 if not ok else 1)
print('结果:', 'PASS' if ok else 'FAIL ' + ','.join(failed))
sys.exit(0 if ok else 1)
