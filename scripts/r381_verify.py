#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R381 · 日志 24 小时保留 / 链路埋点 / 震动反馈 / 两个设置页重设计守门。

静态语义判据，沙盒无 Android SDK，静态绿 != 真编译绿。
用法：python3 scripts/r381_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/tingben/app'
FILES = {
    'log': APP / 'diag/EventLog.kt',
    'haptic': APP / 'relay/RelayHaptics.kt',
    'contract': APP / 'relay/RelayContract.kt',
    'service': APP / 'relay/RelayService.kt',
    'relay_ui': APP / 'relay/RelayUi.kt',
    'gw_service': APP / 'gateway/GatewayService.kt',
    'gw_ui': APP / 'gateway/GatewayUi.kt',
}

def sources():
    return {k: v.read_text(encoding='utf-8') for k, v in FILES.items()}

def check(s):
    e = []
    log, haptic, contract, service = s['log'], s['haptic'], s['contract'], s['service']
    relay_ui, gw_service, gw_ui = s['relay_ui'], s['gw_service'], s['gw_ui']

    # ① 日志只留 24 小时：常量、清理函数、三处触发、导出口径
    for k in ['private const val RETENTION_MS = 24L * 60 * 60 * 1000',
              'private const val PRUNE_INTERVAL_MS = 5L * 60 * 1000',
              'fun prune(now: Long = System.currentTimeMillis()): Int',
              'prune()   // R381 · 冷启动先按 24h 清一遍',
              'lastPruneAt = nowMs',
              'prune()   // R381 · 导出的就是 24h 内的全量',
              'prune()   // R381 · 摘要也只取 24h 内的报错与事件',
              'LOG_RETENTION', 'R381 起只保留最近 24 小时']:
        if k not in log: e.append('日志 24h 保留缺: ' + k[:46])
    if 'crash.log 不是逐行事件流，按文件时间整体过期清理' not in log:
        e.append('崩溃栈没按 24h 过期清理')
    if '本机累计、跨版本' in log and '按新口径只收窗口内报错' not in log:
        e.append('旧「跨版本累计」口径仍与新 24h 口径并存')

    # ② 链路埋点：中继 / 网关的节点、错误码、耗时、体积
    for k in ['RELAY_ON', 'RELAY_OFF', 'RELAY_REQ', 'RELAY_REJECT', 'RELAY_BUSY',
              'RELAY_SYNTH', 'RELAY_FAIL', 'RELAY_PUT_DONE', 'RELAY_DEL',
              'RELAY_POLL_ERR', 'RELAY_START_BLOCKED']:
        if ('"' + k + '"') not in service: e.append('中继埋点缺 ' + k)
    for k in ['GATEWAY_ON', 'GATEWAY_OFF', 'GATEWAY_BIND_FAIL', 'GATEWAY_401',
              'GATEWAY_429', 'GATEWAY_FAIL', 'GATEWAY_DONE', 'GATEWAY_JOB']:
        if ('"' + k + '"') not in gw_service: e.append('网关埋点缺 ' + k)
    for k in ['RELAY_CFG', 'GATEWAY_CFG', 'GATEWAY_TOKEN_ROTATE', 'GATEWAY_BIND']:
        if ('"' + k + '"') not in relay_ui + gw_ui: e.append('面板埋点缺 ' + k)
    # 零正文 / 零 Token：埋点里只出现长度与短哈希，不把 text/token 值拼进日志
    if 'h=${sha(text).take(8)}' not in service:
        e.append('中继请求没用短哈希（正文可能落盘）')
    if 'Token=已填（值不落盘）' not in relay_ui:
        e.append('面板日志可能带出 Token')
    if 't.message ?: "网络没通"' in service:
        e.append('轮询异常仍把 message 落盘（可能带 URL）')

    # ③ 震动：完成与开始明显不同，触发点提前，触发/跳过都有日志
    if 'longArrayOf(0, 80, 90, 80, 90, 80, 90, 420)' not in contract:
        e.append('完成震动没改成长收尾节奏')
    if 'HAPTIC_DONE' not in contract or 'HAPTIC_SKIP' not in haptic:
        e.append('震动触发/跳过没记日志')
    i_done, i_put = service.find('RelayHaptics.done(this)'), service.find('client.putDone(')
    if i_done < 0 or i_put < 0 or i_done > i_put:
        e.append('完成震动没在回写之前（反馈会被上传拉断，或触发点被删）')

    # ④ 两页重设计：共用卡片 / 层级令牌 / 一排次按钮 / 折叠 / 悬浮球安全区
    for name, ui in (('中继', relay_ui), ('网关', gw_ui)):
        for k in ['CardBox {', 'HiSectionSp', 'HiCaptionSp', 'HiMicroSp',
                  'horizontalArrangement = Arrangement.spacedBy(8.dp)',
                  'Modifier.weight(1f).height(44.dp)',
                  'Spacer(Modifier.height(72.dp))', 'verticalScroll']:
            if k not in ui: e.append(name + '页缺 ' + k[:34])
        if 'Text("' + ('跨网中继' if name == '中继' else '语音网关') + '", fontSize = 18' in ui:
            e.append(name + '页内又重复了顶栏大标题')
    if '一次复制两段地址' not in relay_ui:
        e.append('旧「一次复制两段」能力被删')
    if 'GatewayPrefs.regenerate(c)' not in gw_ui or 'Switch(checked = lan' not in gw_ui:
        e.append('网关重新生成 Token / 绑定开关能力被删')
    return e

def main():
    s = sources()
    if '--selftest' in sys.argv:
        cases = [
            ('回潮成 7 天', 'RETENTION_MS = 24L * 60 * 60 * 1000', 'RETENTION_MS = 7L * 24 * 60 * 60 * 1000'),
            ('删掉冷启动清理', 'prune()   // R381 · 冷启动先按 24h 清一遍', ''),
            ('删掉导出前清理', 'prune()   // R381 · 导出的就是 24h 内的全量', ''),
            ('日志落正文', 'h=${sha(text).take(8)}', 'text=$text'),
            ('完成震动退回 150ms', 'longArrayOf(0, 80, 90, 80, 90, 80, 90, 420)', 'longArrayOf(0, 70, 60, 70, 60, 150)'),
            ('完成震动又排到回写之后', 'RelayHaptics.done(this)', '/* moved */'),
            ('中继重复大标题', 'CardBox {', 'Text("跨网中继", fontSize = 18.sp)\n        CardBox {'),
            ('去掉悬浮球安全区', 'Spacer(Modifier.height(72.dp))', 'Spacer(Modifier.height(0.dp))'),
            ('删掉一次复制两段', '一次复制两段地址', ''),
            ('删掉网关绑定开关', 'Switch(checked = lan', 'Switch(checked = false'),
            ('删掉中继请求埋点', 'RELAY_REQ', 'RELAY_NOPE'),
            ('删掉网关 401 埋点', 'GATEWAY_401', 'GATEWAY_401_NONE'),
        ]
        bad = 0
        for name, old, new in cases:
            s2 = dict(s)
            # 注入到出现 old 的第一个文件，全量替换避免同串多次出现漏放
            for k in list(s2):
                if old in s2[k]:
                    s2[k] = s2[k].replace(old, new)
                    break
            if not check(s2):
                bad += 1; print('  漏放', name)
            else:
                print('  拦下', name)
        print('R381 反向注入 %d/%d 全拦' % (len(cases) - bad, len(cases)))
        return 1 if bad else 0
    e = check(s)
    print('R381 %s · 24h 日志 / 链路埋点 / 震动节奏 / 两页重设计' %
          ('FAIL: ' + '; '.join(e) if e else 'PASS'))
    return 1 if e else 0

if __name__ == '__main__':
    sys.exit(main())
