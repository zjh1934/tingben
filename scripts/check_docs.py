#!/usr/bin/env python3
"""项目文件一致性自检（本地维护工具，不挂 CI）。

检查：版本口径一致 / 现行文档不堆积 / 旧方向词零残留 / 引用不指已删文件 /
CI 依赖脚本齐全 / 真机截图索引与文件对齐 / 模型清单是 1 底座 + 3 模式。
支持 --selftest 反向注入。
"""
import json, pathlib, re, shutil, sys, tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
ACTIVE_DOCS = {
    '00_现行契约.md', '01_音频模型调教逻辑.md', '02_模型文件结构.md',
    '03_调音台延迟与一级目录现状.md', 'THIRD_PARTY_NOTICES.md',
}
# 方向变更后不应再出现在「现行」文件里的词：旧方向实体 + 历轮补丁话术
NOISE = [
    '语言模型', '标注', '4B', 'Huihui', 'no_think', 'A/B gate',
    '待新增', '已作废', '订正', '已退场', '两份声音', '权重不换',
    '零业务代码', '不用重装', '本轮性质', 'Rxx 订正',
]


def read(p):
    return pathlib.Path(p).read_text(encoding='utf-8')


def current_version(text):
    m = re.search(r'\| 版本 \| `([^`]+)`', text)
    return m.group(1) if m else ''


def checks(base=ROOT):
    errs, notes = [], []
    status = base / 'STATUS.md'
    if not status.exists():
        return ['缺 STATUS.md'], notes
    ver = current_version(read(status))
    if not ver:
        errs.append('STATUS.md 缺少版本表口径')
    required = [
        'STATUS.md', 'README.md', 'ROADMAP.md',
        'contracts/model-manifest.json', 'contracts/pipeline_spec.md',
        'contracts/sampling-defaults.h', '真机截图/INDEX.md',
    ] + ['docs/' + n for n in sorted(ACTIVE_DOCS)]
    for n in required:
        if not (base / n).exists():
            errs.append('缺现行文件 %s' % n)
    if errs:
        return errs, notes

    # 1. 版本口径一致
    for n in ('STATUS.md', 'README.md', 'ROADMAP.md'):
        if ver and ver not in read(base / n):
            errs.append('%s 未写当前版本 %s' % (n, ver))

    # 2. 现行文档不堆积
    actual = {p.name for p in (base / 'docs').glob('*') if p.is_file()}
    extra = actual - ACTIVE_DOCS
    if extra:
        errs.append('docs/ 又堆进了非现行文件：%s' % ', '.join(sorted(extra)))
    notes.append('现行 docs %d 份' % len(actual))

    # 3. 旧方向词与补丁话术零残留
    scanned = ['STATUS.md', 'README.md', 'ROADMAP.md',
               'contracts/pipeline_spec.md'] + ['docs/' + n for n in sorted(ACTIVE_DOCS - {'THIRD_PARTY_NOTICES.md'})]
    for n in scanned:
        t = read(base / n)
        hit = [w for w in NOISE if w in t]
        if hit:
            errs.append('%s 残留噪声词：%s' % (n, ', '.join(hit)))

    # 4. 现行引用不指已删文件
    for n in ('STATUS.md', 'README.md', 'ROADMAP.md') + tuple('docs/' + x for x in sorted(ACTIVE_DOCS - {'THIRD_PARTY_NOTICES.md'})):
        for ref in re.findall(r'docs/([\w\u4e00-\u9fff\-_.]+?\.(?:md|png|html))', read(base / n)):
            if not (base / 'docs' / ref).exists():
                errs.append('%s 引用了不存在的 docs/%s' % (n, ref))

    # 5. 队列唯一真源
    for n in ('STATUS.md', 'README.md'):
        if 'ROADMAP.md' not in read(base / n):
            errs.append('%s 未指向唯一队列 ROADMAP.md' % n)
    if '### P0' not in read(base / 'ROADMAP.md'):
        errs.append('ROADMAP.md 缺 P0 队列')

    # 6. CI 依赖脚本齐全
    ci = read(base / '.github/workflows/ci.yml')
    deps = set(re.findall(r'python3 scripts/([\w\.]+)', ci)) | set(re.findall(r'\./scripts/([\w\.]+)', ci))
    deps |= {'build_native.sh', 'r45_common.py', 'check_structure.py', 'publish_apk.py',
             'check_docs.py', 'sync_remote.py'}
    for d in deps:
        if not (base / 'scripts' / d).exists():
            errs.append('CI 依赖脚本缺失：scripts/%s' % d)
    notes.append('CI 依赖 %d 项全在' % len(deps))

    # 7. 真机截图索引与文件对齐
    idx = read(base / '真机截图/INDEX.md')
    listed = set(re.findall(r'`(CMP_[\w\.]+\.jpeg)`', idx))
    on_disk = {p.name for p in (base / '真机截图').glob('*.jpeg')}
    for f in sorted(listed - on_disk):
        errs.append('截图索引登记了不存在的文件 %s' % f)
    for f in sorted(on_disk - listed):
        errs.append('截图目录有未登记的孤儿文件 %s' % f)
    notes.append('真机截图 %d 张，索引与文件对齐' % len(on_disk))

    # 8. 模型清单 = 1 底座 + 3 模式
    try:
        m = json.loads(read(base / 'contracts/model-manifest.json'))
        files = [x['file'] for x in m['models']]
        if len(m['models']) != 4:
            errs.append('model-manifest 不是 4 个文件')
        for key in ('qwen-tokenizer-12hz-Q8_0.gguf', 'voicedesign', 'customvoice', 'base'):
            if not any(key in f for f in files):
                errs.append('model-manifest 缺 %s' % key)
        if m.get('current_mode') != 'voice_design' or m.get('synthesis_ready_modes') != ['voice_design']:
            errs.append('model-manifest 的当前模式 / 接通模式与现状不符')
    except Exception as e:
        errs.append('model-manifest 解析失败：%s' % e)
    return errs, notes


def selftest():
    """反向注入：复制一份树，故意破坏 5 处，确认检查真会响。"""
    tmp = pathlib.Path(tempfile.mkdtemp(prefix='r86_selftest_'))
    try:
        for n in ('STATUS.md', 'README.md', 'ROADMAP.md', 'contracts', 'docs',
                  'scripts', '真机截图', '.github'):
            src, dst = ROOT / n, tmp / n
            dst.parent.mkdir(parents=True, exist_ok=True)
            if src.is_dir():
                shutil.copytree(src, dst, ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
            else:
                shutil.copy2(src, dst)
        cases = [
            ('ROADMAP 删 P0', 'ROADMAP.md', lambda t: t.replace('### P0', '### 随便写写')),
            ('STATUS 版本退回', 'STATUS.md', lambda t: t.replace(current_version(t), '1.0.0+47')),
            ('README 注入旧方向词', 'README.md', lambda t: t + '\n4B 文本模型\n'),
            ('docs 堆一份旧文档', None, None),
            ('截图索引写坏链', '真机截图/INDEX.md', lambda t: t + '\n`CMP_不存在的图.jpeg`\n'),
        ]
        for name, rel, mut in cases:
            if rel is None:
                (tmp / 'docs' / 'R99_过时的落地报告.md').write_text('stub', encoding='utf-8')
                e, _ = checks(tmp)
                (tmp / 'docs' / 'R99_过时的落地报告.md').unlink()
            else:
                p = tmp / rel
                old = read(p)
                p.write_text(mut(old), encoding='utf-8')
                e, _ = checks(tmp)
                p.write_text(old, encoding='utf-8')
            if not e:
                return False, '反向注入未拦下：%s' % name
        return True, '5/5 反向注入均被拦下'
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == '__main__':
    errs, notes = checks()
    if '--selftest' in sys.argv:
        ok, msg = selftest()
        print(('PASS' if ok else 'FAIL') + ' · ' + msg)
        if not ok:
            sys.exit(1)
    for n in notes:
        print('  ·', n)
    if errs:
        print('FAIL · %d 项' % len(errs))
        for e in errs:
            print('  ✗', e)
        sys.exit(1)
    print('PASS · 项目文件一致性自检全过')
