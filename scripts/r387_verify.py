#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R387 · 请求文本留存守门。

用户本轮要求：每次过来合成的请求只把「文本」留下，放在新板块里，默认 10 条，
跨网中继 / 局域网网关按整宽选项卡切换（R390 起），每条一键复制，板块支持多选删除、一键清空。

涉及一处口径迁移（按「只留最新、不堆旧账」办）：R225 的「正文零落盘」原本
一刀切；现在唯一例外是 core/RequestArchive.kt 的本机私有留存区——它只留文本、
用户主动查看，**不进 events.log / 不进诊断导出 / 不备份**。日志侧一个字都没放宽，
两套口径不并存，旧句「正文只在内存里」「正文与 Token 不落盘」不许残留。

静态绿 != 真编译绿（本轮按 A 档不出包）。
用法：python3 scripts/r387_verify.py [--selftest]
"""
import pathlib, sys, re

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/tingben/app'
FILES = {
    'archive': APP / 'core/RequestArchive.kt',
    'ui': APP / 'ArchiveUi.kt',
    'settings': APP / 'SettingsUi.kt',
    'relay': APP / 'relay/RelayService.kt',
    'relayui': APP / 'relay/RelayUi.kt',
    'gateway': APP / 'gateway/GatewayService.kt',
    'gatewayui': APP / 'gateway/GatewayUi.kt',
    'log': APP / 'diag/EventLog.kt',
    'r225': ROOT / 'scripts/r225_verify.py',
}

def sources():
    return {k: v.read_text(encoding='utf-8') for k, v in FILES.items()}

def code_lines(text):
    """滤掉注释再查「禁用串」：修复说明里常提到旧词 / WAV / M4A，那是解释不是回潮。"""
    return '\n'.join(l for l in text.splitlines()
                     if not l.lstrip().startswith(('//', '*', '/*', '*/')))

def check(s):
    e = []
    a, u, st, rs, ru, gs, gu, lg = (s['archive'], s['ui'], s['settings'],
                                    s['relay'], s['relayui'], s['gateway'],
                                    s['gatewayui'], s['log'])
    ac = code_lines(a)

    # ① 存储层：两个渠道、默认 10、上限 100、并发锁、原子写
    for k in ('const val SRC_RELAY = "relay"', 'const val SRC_GATEWAY = "gateway"',
              'const val DEFAULT_SHOW = 10', 'const val MAX_KEEP = 100',
              'synchronized(lock)', 'FILE + ".tmp"', 'renameTo(f)',
              'fun add(', 'fun show(', 'fun delete(', 'fun clear(',
              'fun copyText(', 'fun counts('):
        if k not in a:
            e.append('RequestArchive 缺：' + k)
    # 条目字段只有文本相关，零音频 / 零中间文件
    for bad in ('"audio"', '"bytes"', '"format"', '.m4a', '.wav', 'ByteArray'):
        if bad in ac:
            e.append('留存区出现音频/中间文件字段：' + bad)
    # 留存区自己不写正文进日志。
    # R388 补：EventLog.log 是多行调用，正文字符串在下一行，逐行扫会漏放
    # 「留存区日志带正文」那组注入；改成把整个调用块取出来看。
    # ${text.length} 只记字数、fields 里的 text.length 也是数值，二者都不含
    # 「$text」这个子串（$ 后面跟的是 { 或直接是 t 前的空格）；只有真把正文
    # 插进消息串才会出现 $text。别用整块的 text.length 做豁免，fields 会骗过它。
    for blk in re.findall(r'EventLog\.log\([^)]*\)', ac, re.S):
        if '$text' in blk:
            e.append('ARCHIVE_* 日志带了正文变量（多行调用块里出现 $text）')

    # ② 两个渠道写入点：中继 1 处（取到即留），网关同步 + 异步共 2 处
    if rs.count('RequestArchive.add(this, RequestArchive.SRC_RELAY, text)') != 1:
        e.append('中继循环不是恰好 1 处留存写入')
    if gs.count('RequestArchive.add(this, RequestArchive.SRC_GATEWAY, text)') != 2:
        e.append('网关同步 / 异步不是恰好 2 处留存写入')
    if 'import com.tingben.app.core.RequestArchive' not in rs or \
       'import com.tingben.app.core.RequestArchive' not in gs:
        e.append('两个服务漏了 RequestArchive 的 import（真编译会红）')
    # 中继：留存写在「校验之前」，失败/超长也留
    p_add = rs.find('RequestArchive.add(this, RequestArchive.SRC_RELAY, text)')
    p_check = rs.find('if (text.isBlank() || chars > RelayContract.MAX_CHARS)')
    if p_add < 0 or p_check < 0 or p_add > p_check:
        e.append('中继留存没写在校验之前（被拒的请求会漏）')

    # ③ 诊断侧仍零正文，且导出不碰留存文件
    for bad in ('request_archive', 'RequestArchive'):
        if bad in code_lines(lg):
            e.append('诊断导出引用了留存区：' + bad)
    if '本摘要与完整日志仍零正文' not in lg:
        e.append('诊断摘要抬头没按 R387 改准')
    for bad in ('正文只在内存里', '中继请求正文与 Token 不落盘'):
        if bad in rs + lg + st:
            e.append('旧口径没删净：' + bad)

    # ④ UI（R390 形态）：整宽选项卡切换 + 默认 10 + 每条复制 + 多选删 + 清空确认
    uc = code_lines(u)
    for k in ('fun ArchiveScreen(c: Context)',
              'TabChip(Modifier.weight(1f), "跨网中继"',
              'TabChip(Modifier.weight(1f), "语音网关"',
              'var tab by remember { mutableStateOf(0) }',
              'fun goTab(next: Int)', 'detectDragGestures(',
              'RequestArchive.DEFAULT_SHOW',
              '"一键复制全部"', '"多选"', '"删除选中"', '"一键清空全部文本"',
              'AlertDialog(', '"确认清空"', '"复制"', '72.dp'):
        if k not in u:
            e.append('留存页缺：' + k)
    # R390 · 不许回潮成「中间劈开左右两列」：两个渠道必须整宽切换
    if 'fun ColumnScope.Channel(' in u or 'Channel("跨网中继"' in u:
        e.append('请求文本页回潮成左右并排两列（R390 已改整宽选项卡）')
    # 说明文字降级：不折叠、不藏小问号，且必须在列表之后（页面底部微字）
    if 'showHow' in uc or '"?"' in uc:
        e.append('说明文字又藏进折叠 / 小问号（R390 要放在不显眼位置但不隐藏）')
    if u.find('④ 说明降级') < u.find('② 主区'):
        e.append('说明文字没放到列表之后（主次颠倒）')
    # R383：二级页不许自带滚动，滚动交设置页外壳唯一一层
    for bad in ('verticalScroll(', 'LazyColumn', 'rememberScrollState'):
        if bad in uc:
            e.append('留存页自带滚动 → R383 闪退回潮：' + bad)
    if 'Array' in uc and 'arrangement' not in uc:
        pass

    # ⑤ 设置页入口 / 分支 / 标题，两个功能页底部跳转
    for k in ('UiIcon.BOOK, "请求文本"', 'onPageChange("archive")',
              'if (page == "archive")', 'ArchiveScreen(c)',
              '"archive" -> "请求文本"'):
        if k not in st:
            e.append('SettingsUi 缺：' + k)
    for name, txt in (('网关页', gu), ('中继页', ru)):
        if 'onArchive: () -> Unit = {}' not in txt:
            e.append(name + ' 缺 onArchive 默认参数')
        if '查看请求文本' not in txt:
            e.append(name + ' 缺底部「查看请求文本」入口')
    if st.count('verticalScroll(rememberScrollState())') != 1:
        e.append('设置页不是唯一一层滚动')

    # ⑥ R225 日志脱敏守门本身仍按新口径在（没被整条删掉）
    if 'R387 口径改准' not in s['r225']:
        e.append('r225 守门没按 R387 改准（两套口径并存）')
    return e

def main():
    s = sources()
    if '--selftest' in sys.argv:
        cases = [
            ('摘掉中继留存写入', 'relay',
             'RequestArchive.add(this, RequestArchive.SRC_RELAY, text)', ''),
            ('摘掉网关同步留存', 'gateway',
             'RequestArchive.add(this, RequestArchive.SRC_GATEWAY, text)', ''),
            ('留存改到校验之后', 'relay',
             'RequestArchive.add(this, RequestArchive.SRC_RELAY, text)',
             '// 移到校验后'),
            ('摘掉 RequestArchive import', 'relay',
             'import com.tingben.app.core.RequestArchive', ''),
            ('留存区塞音频字段', 'archive',
             'const val DEFAULT_SHOW = 10',
             'const val DEFAULT_SHOW = 10\n    const val AUDIO = "out.m4a"'),
            ('留存区日志带正文', 'archive',
             '"来源=$source · ${text.length}字 · 共 ${n} 条"',
             '"$text · 共 ${n} 条"'),
            ('诊断摘要又引用留存区', 'log',
             '本摘要与完整日志仍零正文',
             '本摘要与完整日志仍零正文；顺便读一下 RequestArchive'),
            ('诊断导出扫留存文件', 'log',
             'fun exportBundle(c: Context): String = buildString {',
             'fun exportBundle(c: Context): String = buildString {\n        RequestArchive'),
            ('旧口径回潮', 'relay',
             'R387 · 正文口径按用户新要求改准', '正文只在内存里'),
            ('留存页自带滚动', 'ui',
             'fun ArchiveScreen(c: Context) {',
             'fun ArchiveScreen(c: Context) {\n    Column(Modifier.verticalScroll(rememberScrollState())) {}'),
            ('缺一键清空', 'ui', '"一键清空全部文本"', '"清空"'),
            ('缺多选删除', 'ui', '"删除选中"', '"删掉"'),
            ('摘掉语音网关选项卡', 'ui',
             'TabChip(Modifier.weight(1f), "语音网关"',
             'TabChip(Modifier.weight(1f), "网关"'),
            ('设置页没挂入口', 'settings', 'UiIcon.BOOK, "请求文本"', ''),
            ('设置页没接分支', 'settings', 'if (page == "archive")', ''),
            ('网关页没跳转入口', 'gatewayui', '查看请求文本', ''),
        ]
        bad = 0
        for name, key, old, new in cases:
            s2 = dict(s)
            if old not in s2[key]:
                bad += 1; print('  注入锚失效', name); continue
            s2[key] = s2[key].replace(old, new, 1)
            if not check(s2):
                bad += 1; print('  漏放', name)
            else:
                print('  拦下', name)
        print('R387 反向注入 %d/%d 全拦' % (len(cases) - bad, len(cases)))
        return 1 if bad else 0
    # 正跑模拟器（真跑逻辑，不是只看字符串）
    sys.path.insert(0, str(ROOT / 'scripts'))
    import archive_sim
    sim_rc = archive_sim.run()
    e = check(s)
    print('R387 %s · 双渠道留存 / 只留文本 / 诊断零正文 / 整宽选项卡 UI / R383+R225 守恒' %
          ('FAIL: ' + '; '.join(e) if e else 'PASS'))
    return 1 if (e or sim_rc) else 0

if __name__ == '__main__':
    sys.exit(main())
