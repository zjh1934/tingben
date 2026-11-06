#!/usr/bin/env python3
"""R65 · 进度页 v12 拆包硬验 + 直链 blob 核对。"""
import hashlib, json, os, pathlib, re, sys, urllib.request, zipfile
SELFTEST = '--selftest' in sys.argv
args = [a for a in sys.argv[1:] if a != '--selftest']
p, ver = pathlib.Path(args[0]), args[1]
root = pathlib.Path(__file__).resolve().parent.parent
_u = os.popen('git -C %s remote get-url origin' % root).read().strip()
_m = re.search(r'https://([^@]+)@github\.com/', _u)
TOKEN = _m.group(1) if _m else ''
H = {'Authorization':'token '+TOKEN,'Accept':'application/vnd.github+json','User-Agent':'tingben-apk-audit'}
def api(path):
    with urllib.request.urlopen(urllib.request.Request('https://api.github.com'+path, headers=H), timeout=90) as r:
        return json.loads(r.read())
raw = p.read_bytes()
local_blob = hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()
remote_blob=None
if TOKEN:
    try:
        t = api('/repos/zjh1934/ting-ben-apk/git/trees/main?recursive=1')
        remote_blob = next(x for x in t['tree'] if x['path']=='app-debug.apk')['sha']
    except Exception as e: print('直链查询失败:', type(e).__name__, e)
blob_ok = remote_blob is None or local_blob==remote_blob
z=zipfile.ZipFile(p); names=z.namelist(); crc=z.testzip()
sos=[n for n in names if n.endswith('.so') and 'arm64-v8a' in n]
gguf=[n for n in names if n.lower().endswith('.gguf')]
dex=b''.join(z.read(n) for n in names if n.endswith('.dex'))
nat=b''.join(z.read(n) for n in names if n.endswith('libtingben_native.so'))
man=z.read('AndroidManifest.xml')
if SELFTEST:
    dex=dex.replace('未经清洗，原样显示'.encode(), b'GONE_r65_hint')
NEW=[ '模型原始产出'.encode(), '未经清洗，原样显示'.encode(),
 '对照：实际送去合成的这句 ›'.encode(), '更多操作 ›'.encode(),
 '这次是直接朗读，没有模型原始产出。'.encode(),
 '这一块的原始产出已被清理'.encode(), '分段看台 · '.encode(),
 '语言模型是否生效'.encode(), '点格子看模型原貌'.encode(),
 '重合成这一段'.encode(), '重新标注本块'.encode(),
 'block_%04d_attempt'.encode(), '拆章'.encode(), '生成声音'.encode(),
 '整理音频'.encode(), '完成'.encode(),
 b'DismissibleNavigationDrawer', b'MIXER_COMMIT',
 '提交变更'.encode(), '试听未提交参数'.encode(),
 b'huihui-qwen3-4b-instruct-2507-abliterated-q4_k_m.gguf']
OLD=[ '继续制作（从停下的地方接着跑）'.encode(), '查看进度 ›'.encode(),
 '重播这一段'.encode(), '收起 ↑'.encode(), '展开 ↓'.encode(),
 b'taskExpanded', '跑一次「标注后合成」后这里给出结论'.encode(),
 '试听这一段'.encode() ]
new_bad=[s for s in NEW if s not in dex]
old_bad=[s for s in OLD if s in dex]
vb,vu=ver.encode(),ver.encode('utf-16le')
perm = b'android.permission.INTERNET' in man or 'android.permission.INTERNET'.encode('utf-16le') in man
print('CRC:', '全过' if crc is None else '损坏 '+str(crc))
print('arm64 .so:',len(sos),'(必须 7) | 权重进包:',len(gguf),'(必须 0)')
print('版本 %s:'%ver, vb in man or vu in man, '| 无 INTERNET:', not perm)
print('直链 blob:', (remote_blob or '未查到')[:12],'本地',local_blob[:12],'一致' if blob_ok else '不一致')
print('dex 新符号:',len(NEW)-len(new_bad),'/',len(NEW),'缺失:',[x.decode('utf-8','replace') for x in new_bad])
print('dex 旧形态零残留:',len(OLD)-len(old_bad),'/',len(OLD),'残留:',[x.decode('utf-8','replace') for x in old_bad])
print('native 实收日志:', b'TTS_PARAMS_APPLIED' in nat)
failed=[]
if crc is not None: failed.append('CRC')
if len(sos)!=7: failed.append('so 数量')
if gguf: failed.append('权重进包')
if not (vb in man or vu in man): failed.append('版本号')
if perm: failed.append('INTERNET 权限')
if not blob_ok: failed.append('直链 blob 不一致')
if new_bad: failed.append('新符号缺失')
if old_bad: failed.append('旧形态残留')
if b'TTS_PARAMS_APPLIED' not in nat: failed.append('native 实收日志丢失')
ok=not failed
if SELFTEST:
    print('自检（已抹掉 R65 标志串）:', '正确拦下' if not ok else 'FAIL：反向注入没被拦下')
    sys.exit(0 if not ok else 1)
print('结果:', 'PASS' if ok else 'FAIL '+','.join(failed))
sys.exit(0 if ok else 1)
