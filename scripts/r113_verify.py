# -*- coding: utf-8 -*-
"""R113 · 清单收尾：F-20 / F-13 / F-17 / F-23 正向、守恒、反向注入。"""
import pathlib, shutil, sys, tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
REL = {
    'hub': 'app/src/main/java/com/tingben/app/ChapterHub.kt',
    'pipe': 'app/src/main/java/com/tingben/app/core/Pipeline.kt',
    'resume': 'app/src/main/java/com/tingben/app/core/TaskResume.kt',
    'bench': 'app/src/main/java/com/tingben/app/core/Workbench.kt',
    'main': 'app/src/main/java/com/tingben/app/MainActivity.kt',
    'svc': 'app/src/main/java/com/tingben/app/ProduceService.kt',
    'manifest': 'app/src/main/AndroidManifest.xml',
    'cpp': 'app/src/main/cpp/tingben_native.cpp',
    'cmake': 'app/src/main/cpp/CMakeLists.txt',
    'ci': '.github/workflows/ci.yml',
}

def txt(root, key):
    p = root / REL[key]
    return p.read_text(encoding='utf-8') if p.exists() else ''

def check(root):
    e = []
    def ck(ok, msg):
        if not ok: e.append(msg)
    hub, pipe, resume, bench, main, svc = (txt(root, k) for k in
        ('hub', 'pipe', 'resume', 'bench', 'main', 'svc'))
    mf, cpp, cmake, ci = (txt(root, k) for k in ('manifest', 'cpp', 'cmake', 'ci'))

    ck('val stalled: Boolean = false' in hub, 'F-20 缺终态字段')
    ck('st.stage in listOf("没完成", "已停止", "出错了")' in hub, 'F-20 缺终态派生')
    ck('stalled = stalled' in hub, 'F-20 终态没传进每章')
    ck('Text("没做完 · 已做好的都留着"' in hub, 'F-20 停滞态文案丢了')
    ck('Text("这本书做好了"' in hub, 'F-20 完成态文案缺')

    ck('fun bucket(only: Set<Int>?)' in pipe, 'F-13 分桶仍带旧三态参数')
    ck('"v11/direct/"' in pipe, 'F-13 分桶路径变了，旧 WAV 会找不到')
    ck('fun dirFor(c: Context, bookId: String, only: Set<Int>?)' in pipe, 'F-13 dirFor 旧参数没清')
    ck('fun clearProgress(c: Context, bookId: String, only: Set<Int>?)' in pipe, 'F-13 clearProgress 旧参数没清')
    ck(pipe.count('fun hasProgress(c: Context, bookId: String, only: Set<Int>?)') == 2,
       'F-13 AnnCache / Producer 两处签名没同步')
    for tag, s in (('Pipeline', pipe), ('TaskResume', resume), ('Workbench', bench), ('Main', main)):
        ck('dirFor(c, t.bookId, t.direct' not in s and 'dirFor(c, bookId, direct' not in s,
           'F-13 %s 仍传旧三态' % tag)
    ck('oldVoice != Prefs.legacyVoiceStamp(c)' in resume, 'F-13 旧 WAV 指纹兼容读被误删')
    ck('voiceFile.writeText(currentVoice)' in resume, 'F-13 读时迁移写回缺')
    ck('VOICE_STAMP_MIGRATE' in resume, 'F-13 迁移事件缺')
    ck('directMode' not in main and 'val thinkOn = false' not in main, 'F-13 界面恒值旧档位残留')

    ck('FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING' in svc, 'F-17 34+ 未切 mediaProcessing')
    ck('FOREGROUND_SERVICE_TYPE_DATA_SYNC' in svc, 'F-17 低版本 dataSync 分支被删')
    ck('fgsTypeLabel()' in svc, 'F-17 类型标签没随版本走')
    ck('FOREGROUND_SERVICE_MEDIA_PROCESSING' in mf, 'F-17 权限没声明')
    ck('dataSync|mediaProcessing' in mf, 'F-17服务类型 没声明双值')

    ck('dlsym(RTLD_DEFAULT' in cpp, 'F-23 缺弱符号回退')
    ck('qt_extract_voice_ref' in cpp and 'qt_voice_ref_free' in cpp, 'F-23 缺 latent 接口')
    ck('releaseRefLocked' in cpp, 'F-23 latent 没释放')
    ck('tp.ref_spk_emb = st.latent.ref_spk_emb' in cpp, 'F-23 合成没用缓存 latent')
    ck('fallback-raw' in cpp, 'F-23 提取失败没回退原始 PCM')
    ck('${CMAKE_DL_LIBS}' in cmake, 'F-23 未链接 dl')

    ck('android.permission.INTERNET' not in mf, '守恒：Manifest 出现 INTERNET')
    ck('scripts/r113_verify.py' in ci, 'CI 未挂 R113')
    return e

INJECT = [
    ('F-20 终态字段被改', 'hub', 'val stalled: Boolean = false', 'val stalled: Boolean = true'),
    ('F-13 分桶路径改掉', 'pipe', '"v11/direct/"', '"v12/annotated/"'),
    ('F-13 dirFor 恢复旧参数', 'pipe',
     'fun dirFor(c: Context, bookId: String, only: Set<Int>?)',
     'fun dirFor(c: Context, bookId: String, direct: Boolean, think: Boolean, only: Set<Int>?)'),
    ('F-13 删迁移写回', 'resume', 'voiceFile.writeText(currentVoice)', ''),
    ('F-13 删旧指纹兼容读', 'resume', 'oldVoice != Prefs.legacyVoiceStamp(c)', ''),
    ('F-17 退回仅 dataSync', 'svc', 'ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING',
     'ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC'),
    ('F-17 删权限', 'manifest', 'android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING', ''),
    ('F-23 去掉 latent 复用', 'cpp', 'tp.ref_spk_emb = st.latent.ref_spk_emb', ''),
    ('F-23 去掉弱符号', 'cpp', 'dlsym(RTLD_DEFAULT', ''),
    ('F-23 去掉释放', 'cpp', 'releaseRefLocked', ''),
    ('CI 摘掉 R113', 'ci', 'scripts/r113_verify.py', ''),
]

def selftest():
    errs = []
    for name, key, old, new in INJECT:
        with tempfile.TemporaryDirectory() as d:
            r = pathlib.Path(d)
            for rel in REL.values():
                p = ROOT / rel
                if not p.exists(): continue
                q = r / rel; q.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(p, q)
            s = (r / REL[key]).read_text(encoding='utf-8')
            if old not in s:
                errs.append('注入锚点不存在：' + name); continue
            (r / REL[key]).write_text(s.replace(old, new), encoding='utf-8')
            if not check(r): errs.append('没拦下：' + name)
    return errs

if __name__ == '__main__':
    if '--selftest' in sys.argv:
        bad = selftest()
        print('反向注入 %d 例，漏拦 %d' % (len(INJECT), len(bad)))
        for x in bad: print('  ', x)
        sys.exit(1 if bad else 0)
    e = check(ROOT)
    print('R113 正向 %d 条失败' % len(e))
    for x in e: print('  ', x)
    sys.exit(1 if e else 0)
