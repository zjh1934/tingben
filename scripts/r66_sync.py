# -*- coding: utf-8 -*-
"""R66 · 订正仍适用的旧断言、归档已删形态的验证脚本、同步 CI。"""
import os, re, shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
S = ROOT / 'scripts'
CI = ROOT / '.github/workflows/ci.yml'

def rw(p, fn):
    t = p.read_text(encoding='utf-8')
    n = fn(t)
    if n != t: p.write_text(n, encoding='utf-8'); print('订正', p.name)
    else: print('无需改', p.name)

# ① 基础结构自检：Screen 10 值、抽屉 7 项、旧模块不许回潮
def cs(t):
    t = t.replace('"SETTINGS", "MODELS", "CAST", "MIXER", "LLM", "CHAPTERS"}',
                  '"SETTINGS", "MODELS", "CAST", "MIXER", "CHAPTERS"}')
    t = t.replace('    if body2.count(" to ") != 5 or "调音台" not in body2 or "语言模型" not in body2:\n'
                  '        errs.append("U1 回退：抽屉设置域不是 5 项或调音台/语言模型不是独立项")',
                  '    if body2.count(" to ") != 4 or "调音台" not in body2:\n'
                  '        errs.append("U1 回退：抽屉设置域不是 4 项或调音台不是独立项")\n'
                  '    if "语言模型" in body2 or "文本处理" in body2:\n'
                  '        errs.append("U1 回退：抽屉仍含已删模块")')
    return t
rw(S / 'check_structure.py', cs)

# ② R46 抽屉壳：枚举白名单去掉 LLM
def r46(t):
    return t.replace('"SETTINGS", "MODELS", "CAST", "MIXER", "LLM", "CHAPTERS"}',
                     '"SETTINGS", "MODELS", "CAST", "MIXER", "CHAPTERS"}')
rw(S / 'r46_verify.py', r46)

# ③ R47 抽屉收窄：分区顺序断言换成「旧模块入口必须消失」
def r47(t):
    return t.replace('    # P0 · 设置页改 v8 双页；外观与显示须排在「更多」之前（入口不倒置）。\n'
                     '    ck(main.find(\'SettingsSection("外观与显示"\') <\n'
                     '       main.find(\'SettingsSection("更多"\'), "设置页分区顺序被顺手挪动")',
                     '    # R66 · 设置页只管软件：旧模块入口与「更多」分区都不许回来。\n'
                     '    ck(\'SettingsSection("更多"\' not in main and "语言模型设置" not in main,\n'
                     '       "设置页仍留已删模块入口")\n'
                     '    ck(\'SettingsSection("外观与显示"\' in main, "设置页软件分区被顺手挪动")')
rw(S / 'r47_verify.py', r47)

# ③b check_structure：删掉随旧功能一起退场的历史字面量断言
def cs2(t):
    # 这些字面量随 1.1.0 删除的功能一起退场，从历史断言清单里逐项摘掉
    t = t.replace('"查看标注内容", ', '')
    t = t.replace('"复制标注全文"]', '"contentText"]')
    t = t.replace('"查看模型原始产出", ', '')
    t = t.replace('"零标注 ⚠️ 白跑", ', '')
    t = t.replace(', "标注后合成（实验性）"', '')
    t = t.replace('if not re.search(r\'\\b\\w+\\.src == "MODEL"\', main_src):\n'
                  '    errs.append("R27 回退：看台没有按真实来源着色")',
                  'if "sg.hasAudio -> Good" not in main_src:\n'
                  '    errs.append("R66 回退：看台没按已出声/合成中/未开始着色")')
    return t
rw(S / 'check_structure.py', cs2)

# ③c r47：注释里提到旧页名不算入口，只认代码形态
def r47b(t):
    return t.replace('    ck(\'SettingsSection("更多"\' not in main and "语言模型设置" not in main,',
                     '    ck(\'SettingsSection("更多"\' not in main and \'TextButton(onClick = onLlm\' not in main,')
rw(S / 'r47_verify.py', r47b)

# ④ 归档：这些脚本验证的目标形态本轮已删除，留着只会假红
ARCHIVE = ['r42_verify.py', 'r45_verify.py', 'r54_verify.py',
           'r60_verify.py', 'r61_verify.py', 'r65_verify.py',
           'r60_apk_audit.py', 'r61_apk_audit.py', 'r65_apk_audit.py']
dest = S / 'archive'; dest.mkdir(exist_ok=True)
for f in ARCHIVE:
    src = S / f
    if src.exists(): shutil.move(str(src), str(dest / f)); print('归档', f)

# ⑤ CI：删掉归档脚本的 step；确保 r66_verify 挂上
t = CI.read_text(encoding='utf-8')
for f in ARCHIVE:
    t = re.sub(r'^\s*- name: [^\n]*\n\s*run: python3 scripts/' + re.escape(f) + r'\s*\n',
               '', t, flags=re.M)
if 'python3 scripts/r66_verify.py' not in t:
    t = re.sub(r'(^ {6}- name: 结构自检\n {8}run: python3 scripts/check_structure\.py\n)',
        r'\g<1>      - name: R66 语言模型退场验证（UI 零残留 / 三列四步 / 恒直接朗读 / 反向注入 / 守恒）\n'
        r'        run: python3 scripts/r66_verify.py\n',
        t, flags=re.M)
    if 'python3 scripts/r66_verify.py' not in t:
        print('!! R66 step 没能插入 CI'); raise SystemExit(1)
CI.write_text(t, encoding='utf-8')
print('CI 剩余验证 step:', re.findall(r'run: python3 scripts/([\w\.]+)', t))
