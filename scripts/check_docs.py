#!/usr/bin/env python3
"""R59 起：项目文件一致性自检（本地维护工具，不挂 CI）。

检查：三份真值源互不抄队列 / 版本口径一致 / 现行引用不指已删文件 /
CI 依赖脚本齐全 / 归档包完整 / 过时口径零残留。支持 --selftest 反向注入。
"""
import os, re, sys, pathlib, zipfile, tempfile, shutil

ROOT = pathlib.Path(__file__).resolve().parent.parent
DOCS = ROOT / 'docs'
BAD_WORDS = ['换模型不做', '4B 撤主路', '下轮队列', '下一轮第一件']
# R61 · 版本不再写死：从 STATUS.md 表格读当前版本，避免每轮发版都要改自检脚本
_m = re.search(r'\| 版本 \| `([^`]+)`', (ROOT / 'STATUS.md').read_text(encoding='utf-8'))
CURRENT_VER = _m.group(1) if _m else '1.0.0+0'


def read(p):
    return pathlib.Path(p).read_text(encoding='utf-8')


def checks(base=ROOT):
    errs, notes = [], []
    paths = {n: base / n for n in
             ('STATUS.md', 'README.md', 'ROADMAP.md', 'ITERATION_LOG.md',
              'docs/ITERATION_LOG.md', 'docs/HISTORY_INDEX.md')}
    for n, p in paths.items():
        if not p.exists():
            errs.append('缺现行文件 %s' % n)
    if errs:
        return errs, notes
    texts = {n: read(p) for n, p in paths.items()}

    # 1. 版本口径
    for n in ('STATUS.md', 'README.md', 'ROADMAP.md'):
        if CURRENT_VER not in texts[n]:
            errs.append('%s 未写当前版本 %s' % (n, CURRENT_VER))
    if '1.0.0+47' in texts['STATUS.md'] or '1.0.0+47' in texts['README.md']:
        errs.append('STATUS/README 仍把 +47 当当前版本')

    # 2. 队列唯一真源
    for n in ('STATUS.md', 'README.md', 'docs/ITERATION_LOG.md'):
        if 'ROADMAP.md' not in texts[n]:
            errs.append('%s 未指向现行队列 ROADMAP.md' % n)
    for w in BAD_WORDS:
        if w in texts['STATUS.md'] or w in texts['README.md']:
            errs.append('过时口径仍在 STATUS/README：%s' % w)

    # 3. 现行文件引用 docs/ 下的文件必须存在（ITERATION_LOG 是历史日志，豁免）
    arch = set()
    hi = texts['docs/HISTORY_INDEX.md']
    arch |= set(re.findall(r'^- `([^`]+\.(?:md|png|html|py|sh|txt))`$', hi, re.M))
    for n in ('STATUS.md', 'README.md', 'ROADMAP.md', 'docs/ITERATION_LOG.md'):
        for ref in re.findall(r'docs/([\w\u4e00-\u9fff\-_.]+?\.(?:md|png|html))', texts[n]):
            if not (DOCS / ref).exists():
                errs.append('%s 引用了不存在的 docs/%s' % (n, ref))
    notes.append('归档清单登记 %d 个文件' % len(arch))

    # 4. 三份真值源分工
    if '## 现行队列' not in texts['STATUS.md']:
        errs.append('STATUS 缺「现行队列」指向段')
    if '## 2. 分轮队列' not in texts['ROADMAP.md']:
        errs.append('ROADMAP 缺分轮队列')
    if 'P0' not in texts['ROADMAP.md'] or '语言模型退场' not in texts['ROADMAP.md']:
        errs.append('ROADMAP 未把语言模型退场列为 P0')

    # 5. CI 依赖脚本
    ci = read(base / '.github/workflows/ci.yml')
    deps = set(re.findall(r'python3 scripts/([\w\.]+)', ci)) | {'build_native.sh', 'r45_common.py'}
    for d in deps:
        if not (base / 'scripts' / d).exists():
            errs.append('CI 依赖脚本缺失：scripts/%s' % d)
    notes.append('CI 依赖 %d 项全在' % len(deps))

    # 6. 导图横幅
    g = read(DOCS / '听本_项目导图.html')
    if '历史快照' not in g or 'ROADMAP.md' not in g:
        errs.append('导图未标注历史快照 / 未指向 ROADMAP.md')

    # 7. 归档包完整（不在仓库里查，查工作区 zip）
    ws = pathlib.Path('/data/workspace')
    for z in ('听本_R59_历史文档.zip', '听本_R59_历史脚本.zip'):
        if not (ws / z).exists():
            errs.append('归档包缺失 %s' % z)
        else:
            with zipfile.ZipFile(ws / z) as f:
                notes.append('%s 含 %d 份' % (z, len(f.namelist())))
    return errs, notes


def selftest():
    """反向注入：复制一份树，故意破坏 4 处，确认检查真会响。"""
    tmp = pathlib.Path(tempfile.mkdtemp(prefix='r59_selftest_'))
    try:
        for n in ('STATUS.md', 'README.md', 'ROADMAP.md', 'ITERATION_LOG.md',
                  '.github/workflows/ci.yml', 'scripts'):
            src, dst = ROOT / n, tmp / n
            dst.parent.mkdir(parents=True, exist_ok=True)
            if src.is_dir():
                shutil.copytree(src, dst)
            else:
                shutil.copy2(src, dst)
        (tmp / 'docs').mkdir(exist_ok=True)
        for n in ('ITERATION_LOG.md', 'HISTORY_INDEX.md', '听本_项目导图.html'):
            shutil.copy2(DOCS / n, tmp / 'docs' / n)
        # 让 ROADMAP 的 docs 引用必须存在的检查也能跑：补空壳
        for ref in set(re.findall(r'docs/([\w\u4e00-\u9fff\-_]+?\.md)',
                                  read(tmp / 'STATUS.md') + read(tmp / 'README.md') +
                                  read(tmp / 'ROADMAP.md') + read(tmp / 'docs/ITERATION_LOG.md'))):
            p = tmp / 'docs' / ref
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text('stub', encoding='utf-8')

        cases = [
            ('ROADMAP.md', lambda t: t.replace('## 2. 分轮队列', '## 二、随便写写')),
            ('STATUS.md', lambda t: t.replace(CURRENT_VER, '1.0.0+47')),
            ('README.md', lambda t: t.replace('ROADMAP.md', '不存在的文件.md')),
            ('STATUS.md', lambda t: t.replace('## 现行队列', '## 下轮队列')),
        ]
        for name, mut in cases:
            p = tmp / name
            old = read(p)
            p.write_text(mut(old), encoding='utf-8')
            e, _ = checks(tmp)
            p.write_text(old, encoding='utf-8')
            if not e:
                return False, '反向注入未拦下：%s' % name
        return True, '4/4 反向注入均被拦下'
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
