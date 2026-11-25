#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""听本 · 全页面图集（按代码文案绘制，非真机截屏）

覆盖：6 个一级页 + 9 个设置子页 + 空态/有内容态/多选/展开/弹窗/播放器各形态
      + 抽屉 / 悬浮进度球 / 恢复弹窗 / 描述预设弹窗。
每个页面状态都出**浅色 + 深色**两张，命名一一对应。
配色严格取 Theme.kt 的 LightPal / DarkPal；字号令牌取 R370 的
「页面标题 18 / 分区 14 / 正文 14 / 注释 12 / 微字 11」（画布按 2x dp）。

产物：/data/workspace/听本全页面图集/light/ 与 dark/，外加索引 md。
"""
from PIL import Image, ImageDraw, ImageFont
import pathlib, math

W, H = 720, 1440
FONT = "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc"
OUT = pathlib.Path("/data/workspace/听本全页面图集")

# Theme.kt · LightPal / DarkPal
LIGHT = dict(bg="#F4F6FB", card="#FFFFFF", primary="#4338CA", soft="#EEF0FE",
             btnSecondary="#EEF0FE", onBtnSecondary="#4338CA",
             btnTertiary="#FFFFFF", onBtnTertiary="#4338CA", btnTertiaryLine="#C7CEE2",
             sub="#6B7280", good="#0E9F6E", line="#E7EAF2", warn="#B45309",
             text="#1B2030", onPrimary="#FFFFFF", grey="#B9BFCB",
             onWarn="#FFFFFF", warnBg="#FEF3C7", track="#E9ECF5",
             shade=(15, 18, 28, 190))
DARK = dict(bg="#0E1117", card="#222936", primary="#A5B4FC", soft="#313C63",
            btnSecondary="#3D4A72", onBtnSecondary="#FFFFFF",
            btnTertiary="#222936", onBtnTertiary="#FFFFFF", btnTertiaryLine="#5C6A85",
            sub="#FFFFFF", good="#34D399", line="#3B455A", warn="#FBBF24",
            text="#FFFFFF", onPrimary="#10131A", grey="#7E8AA0",
            onWarn="#1A1206", warnBg="#3A2E12", track="#3B455A",
            shade=(0, 0, 0, 205))

TITLE, SECTION, BODY, CAP, MICRO = 32, 25, 23, 20, 18
_fc = {}
def f(sz, bold=False):
    key = (sz, bold)
    if key not in _fc:
        _fc[key] = ImageFont.truetype(FONT, sz)
    return _fc[key]

def rgb(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i+2], 16) for i in (0, 2, 4))

def C(P, k): return rgb(P[k])

def tw(d, s, font): return d.textlength(s, font=font)

def txt(d, xy, s, sz, P, key='text', bold=False, anchor=None):
    font = f(sz, bold)
    d.text(xy, s, font=font, fill=C(P, key), anchor=anchor)
    if bold:  # 轻微加粗：横向 1px 重描
        d.text((xy[0] + 1, xy[1]), s, font=font, fill=C(P, key), anchor=anchor)

def wrap(d, s, font, maxw):
    out, cur = [], ''
    for ch in s:
        if tw(d, cur + ch, font) > maxw and cur:
            out.append(cur); cur = ch
        else:
            cur += ch
    if cur: out.append(cur)
    return out

def para(d, xy, s, sz, P, key='sub', maxw=560, gap=6, bold=False):
    font = f(sz, bold)
    y = xy[1]
    for line in wrap(d, s, font, maxw):
        d.text((xy[0], y), line, font=font, fill=C(P, key))
        y += sz + gap
    return y

def rr(d, box, r, fill, outline=None, w=2):
    d.rounded_rectangle(box, radius=r, fill=fill, outline=outline, width=w)

def card(d, P, box, r=18):
    rr(d, box, r, C(P, 'card'), C(P, 'line'), 2)

def hline(d, P, x1, x2, y): d.line((x1, y, x2, y), fill=C(P, 'line'), width=2)

def arrow(d, P, x, y, s=12, color='primary', direction='right'):
    c = C(P, color)
    if direction == 'right':
        d.line((x - s//2, y - s, x + s//2, y), fill=c, width=4)
        d.line((x + s//2, y, x - s//2, y + s), fill=c, width=4)
    else:
        d.line((x + s//2, y - s, x - s//2, y), fill=c, width=4)
        d.line((x - s//2, y, x + s//2, y + s), fill=c, width=4)

def back_arrow(d, P, x=54, y=104):
    d.line((x + 16, y - 16, x - 12, y), fill=C(P, 'primary'), width=6)
    d.line((x - 12, y, x + 16, y + 16), fill=C(P, 'primary'), width=6)

def music_note(d, cx, cy, s, color):
    """经典音符：矢量画的（字体 ♫ 无字形会渲染成方框）。"""
    d.ellipse((cx - s*0.56, cy + s*0.10, cx - s*0.04, cy + s*0.52), fill=color)
    d.line((cx - s*0.07, cy + s*0.32, cx - s*0.07, cy - s*0.78),
           fill=color, width=max(3, int(s*0.09)))
    d.polygon([(cx - s*0.07, cy - s*0.78), (cx + s*0.56, cy - s*0.50),
               (cx + s*0.34, cy - s*0.18), (cx - s*0.07, cy - s*0.38)], fill=color)

def hamburger(d, P, x=676, y=104):
    for i in range(3):
        d.line((x - 18, y - 10 + i*10, x + 18, y - 10 + i*10),
               fill=C(P, 'primary'), width=5)

def mix(a, b, t):
    return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3))


# R398 · 页面状态单一真值源：状态卡 / 互斥动作 / 开关态一律由这张表派生，
# 绘制函数里不再写死「默认关」「未置顶」「置顶+取消置顶同时摆」这类常量。
UI_STATE = {
    'gateway': dict(enabled=False),
    'relay':   dict(enabled=True),
    'model':   dict(all_ready=True, current='描述造音色'),
    'library_menu': dict(pinned=False),
    'chapter_menu': dict(chapter='第二章 · 渡口', done=8, total=14),
}

def switch_card(key):
    """与 UiSpec.UiSwitchState 同口径：状态卡文案必须等于开关真值。"""
    on = UI_STATE[key]['enabled']
    return ('已开启' if on else '默认关',
            '开＝手机主动轮询 queue/' if on else '默认关 · 手动开启')

def pin_status(pinned):
    """与 UiSpec 同口径：状态标签由 pinned 派生。"""
    return '已置顶' if pinned else '未置顶'

def pin_action(pinned):
    """与 UiSpec.exclusiveAction 同口径：互斥动作按状态只留一个。"""
    return '取消置顶' if pinned else '置顶'

def device_shell(d, P, dark):
    """R396 · 设备层：金标联盟 ITGSA 形态 —— 小白条透明浮在内容之上，
    不是让出一块灰底；顶部摄像头 挖孔 由 status_bar 画；四向 INSET 与
    最小点击框单独出「设备层标注图」，设计说明不画进界面。"""
    # 小白条：与背景混合出半透效果，浮在最上层
    bar = mix(C(P, 'bg'), C(P, 'text'), 0.62 if not dark else 0.72)
    d.rounded_rectangle((W // 2 - 54, H - 12, W // 2 + 54, H - 7), 3, fill=bar)
    # 大 R 角屏幕的左右圆角暗示（极淡，不占内容）
    arc = mix(C(P, 'bg'), C(P, 'line'), 0.35)
    d.arc((-30, -30, 30, 30), 180, 270, fill=arc, width=3)
    d.arc((W - 30, -30, W + 30, 30), 270, 360, fill=arc, width=3)


def status_bar(d, P):
    txt(d, (40, 18), "9:41", 22, P, 'text', True)
    # R396 · 摄像头 / 挖孔：状态栏区中置，图标色随浅深（text 角色）
    d.ellipse((330, 10, 392, 54), fill=C(P, 'bg'), outline=C(P, 'line'), width=3)
    d.ellipse((348, 20, 374, 46), fill=mix(C(P, 'bg'), C(P, 'text'), 0.80))
    d.ellipse((354, 24, 362, 32), fill=mix(C(P, 'bg'), C(P, 'line'), 0.45))
    # 信号 / Wi-Fi / 电池
    for i, h in enumerate((8, 12, 16, 20)):
        d.rectangle((590 + i*12, 44 - h, 598 + i*12, 44), fill=C(P, 'text'))
    d.arc((646, 20, 672, 46), 200, 340, fill=C(P, 'text'), width=4)
    d.rounded_rectangle((678, 24, 706, 44), 4, None, C(P, 'text'), 3)
    d.rectangle((681, 27, 703, 41), fill=C(P, 'text'))

def topbar(d, P, title, back=True, trailing=None):
    status_bar(d, P)
    hline(d, P, 0, W, 76)
    if back: back_arrow(d, P)
    txt(d, (96 if back else 40, 88), title, TITLE, P, 'text', True)
    if trailing == "☰":
        hamburger(d, P)
    elif trailing == "⋯":
        for i in range(3):
            d.ellipse((W - 84 + i*20, 98, W - 74 + i*20, 108), fill=C(P, 'primary'))
    elif trailing:
        txt(d, (W - 40, 92), trailing, CAP, P, 'primary', True, anchor='ra')
    hline(d, P, 0, W, 150)

def btn(d, P, box, label, kind='primary', sz=CAP):
    if kind == 'primary':
        fill, fg, ol = C(P, 'primary'), C(P, 'onPrimary'), None
    elif kind == 'warn':
        fill, fg, ol = C(P, 'warn'), C(P, 'onWarn'), None
    elif kind == 'ghost':
        # R396 · SECONDARY 角色：深色下提亮底 + 纯白字，不再像禁用
        fill, fg, ol = C(P, 'btnSecondary'), C(P, 'onBtnSecondary'), None
    elif kind == 'tertiary':
        # R397 · 常态批量动作不用警示实心：card 底 + 语义字 + 提亮描边
        fill, fg, ol = C(P, 'btnTertiary'), C(P, 'onBtnTertiary'), C(P, 'btnTertiaryLine')
    else:
        fill, fg, ol = C(P, 'card'), C(P, 'primary'), C(P, 'line')
    rr(d, box, (box[3]-box[1])//2, fill, ol, 2 if ol else 1)
    cx = (box[0] + box[2]) // 2
    d.text((cx, (box[1]+box[3])//2), label, font=f(sz, True), fill=fg, anchor='mm')

def chip(d, P, box, label, sel=False, sz=CAP):
    rr(d, box, (box[3]-box[1])//2,
       C(P, 'btnSecondary') if sel else C(P, 'card'),
       C(P, 'primary') if sel else C(P, 'line'), 2)
    d.text(((box[0]+box[2])//2, (box[1]+box[3])//2), label,
           font=f(sz, sel), fill=C(P, 'onBtnSecondary' if sel else 'sub'), anchor='mm')
    if sel:
        d.rounded_rectangle(((box[0]+box[2])//2-22, box[3]-9,
                             (box[0]+box[2])//2+22, box[3]-6), 2, fill=C(P, 'primary'))

def switch(d, P, x, y, on=True):
    w, h = 74, 40
    rr(d, (x, y, x+w, y+h), h//2, C(P, 'primary' if on else 'track'))
    cx = x + w - h//2 if on else x + h//2
    d.ellipse((cx-16, y+4, cx+16, y+h-4), fill=C(P, 'onPrimary' if on else 'card'))

def checkbox(d, P, x, y, on=True):
    r = 15
    d.rounded_rectangle((x-r, y-r, x+r, y+r), 5,
                        C(P, 'primary') if on else C(P, 'card'), C(P, 'primary'), 3)
    if on:
        d.line((x-7, y, x-1, y+7), fill=C(P, 'onPrimary'), width=4)
        d.line((x-1, y+7, x+9, y-8), fill=C(P, 'onPrimary'), width=4)

def progress(d, P, x, y, w, pct, label, color='primary', sub=None):
    txt(d, (x, y - 34), label, CAP, P, 'sub')
    txt(d, (x + w, y - 34), "%d%%" % pct, CAP, P, 'text', True, anchor='ra')
    rr(d, (x, y, x + w, y + 14), 7, C(P, 'track'))
    rr(d, (x, y, x + int(w * pct / 100), y + 14), 7, C(P, color))
    if sub: txt(d, (x, y + 22), sub, MICRO, P, 'sub')

def section(d, P, y, name, count=None):
    txt(d, (40, y), name, SECTION, P, 'text', True)
    if count: txt(d, (W - 40, y + 3), count, CAP, P, 'sub', anchor='ra')
    return y + 42

def row(d, P, box, title, sub=None, right='arrow', on=True, value=None, bold=False):
    card(d, P, box, 16)
    x1, y1, x2, y2 = box
    cy = (y1 + y2) // 2
    if right == 'check': checkbox(d, P, x1 + 38, cy, on)
    lx = x1 + 72 if right == 'check' else x1 + 26
    txt(d, (lx, y1 + 18), title, BODY, P, 'text', bold)
    if sub: txt(d, (lx, y1 + 52), sub, 19 if P['bg'] == '#0E1117' else CAP, P, 'sub')
    if right == 'arrow': arrow(d, P, x2 - 38, cy)
    elif right == 'switch': switch(d, P, x2 - 100, cy - 20, on)
    elif right == 'value': txt(d, (x2 - 26, cy), value or '', CAP, P, 'primary', True, anchor='rm')
    elif right == 'chip':
        rr(d, (x2 - 132, cy - 20, x2 - 26, cy + 20), 20, C(P, 'soft'))
        txt(d, (x2 - 79, cy), value or '已开启', MICRO, P, 'primary', True, anchor='mm')

def note(d, P, lines, y=1320):
    for i, s in enumerate(lines):
        txt(d, (40, y + i * 28), s, MICRO, P, 'sub')

def safe_balls(d, P, dark, whole=38, chap=72, mini=False, dy=0):
    """右下角悬浮进度球（不透明，白底深底都看得清）。dy 上移避开底部操作条。"""
    for (cx, cy, pct, label, color) in [
        (W - 96, H - 250 + dy, whole, "整本", 'primary'),
        (W - 96, H - 150 + dy, chap, "本章", 'good')]:
        r = 52
        d.ellipse((cx-r, cy-r, cx+r, cy+r), fill=C(P, 'card'), outline=C(P, color), width=5)
        d.arc((cx-r+8, cy-r+8, cx+r-8, cy+r-8), -90, -90 + 360*pct/100,
              fill=C(P, color), width=7)
        txt(d, (cx, cy - 16), "%d%%" % pct, CAP, P, 'text', True, anchor='mm')
        txt(d, (cx, cy + 14), label, MICRO, P, 'sub', anchor='mm')

def dialog_overlay(im, P, dark, title, lines, buttons, warn=False, y0=430, h=380,
                   dim_actions=True):
    """半透明遮罩 + 居中弹窗；返回合成后的新图。"""
    ov = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    od = ImageDraw.Draw(ov)
    od.rectangle((0, 0, W, H), fill=P['shade'])
    # R397 · 模态协议：弹窗期间底部操作条额外降到 0.20 并停命中（视觉示意）
    if dim_actions:
        # 用暗层再压一次（不是提亮），底部操作条视觉降到约 0.20 且不再抢眼
        od.rectangle((0, 1180, W, 1360), fill=(8, 10, 14, 95))
    box = (56, y0, W - 56, y0 + h)
    od.rounded_rectangle(box, 26, fill=C(P, 'card') + (255,), outline=C(P, 'line') + (255,), width=2)
    # 警示三角
    if warn:
        cx, cy = 110, y0 + 66
        od.polygon([(cx, cy-24), (cx+26, cy+20), (cx-26, cy+20)],
                   fill=C(P, 'warn') + (255,))
        od.text((cx, cy-2), "!", font=f(26, True), fill=C(P, 'onWarn') + (255,), anchor='mm')
    tx = 168 if warn else 88
    od.text((tx, y0 + 44), title, font=f(28, True), fill=C(P, 'text') + (255,))
    yy = y0 + 102
    for s in lines:
        for ln in wrap(od, s, f(21), W - 220):
            od.text((88, yy), ln, font=f(21), fill=C(P, 'sub') + (255,))
            yy += 30
        yy += 6
    by = box[3] - 74
    n = len(buttons)
    bw = (W - 112 - 40 - (n-1)*16) // n
    for i, (lab, kind) in enumerate(buttons):
        bx = 76 + i * (bw + 16)
        if kind == 'primary': fill, fg = C(P, 'primary'), C(P, 'onPrimary')
        elif kind == 'warn': fill, fg = C(P, 'warn'), C(P, 'onWarn')
        else: fill, fg = C(P, 'soft'), C(P, 'primary')
        od.rounded_rectangle((bx, by, bx + bw, by + 56), 28, fill=fill + (255,))
        od.text((bx + bw//2, by + 28), lab, font=f(21, True), fill=fg + (255,), anchor='mm')
    return Image.alpha_composite(im.convert('RGBA'), ov).convert('RGB')

def frame(im, d, P, title, back=True, trailing=None):
    d.rectangle((0, 0, W, H), fill=C(P, 'bg'))
    topbar(d, P, title, back, trailing)
    return 176

# ---------------- 通用页面模板 ----------------
def list_page(d, im, P, dark, title, sections, trailing=None, back=True,
              topcard=None, buttons=None, foot=None, tabs=None, tab_sel=0,
              compact=False):
    y = frame(im, d, P, title, back, trailing)
    if tabs:
        bw = (W - 80 - 16) // len(tabs)
        for i, t in enumerate(tabs):
            chip(d, P, (40 + i*(bw+16), y, 40 + i*(bw+16) + bw, y + 56), t, i == tab_sel)
        y += 78
    if topcard:
        h = topcard(d, P, y); y = h + 24
    for name, count, rows in sections:
        y = section(d, P, y, name, count)
        for (t, s, right, on, val) in rows:
            hh = (88 if s else 68) if compact else (104 if s else 76)
            row(d, P, (40, y, W - 40, y + hh), t, s, right, on, val)
            y += hh + (8 if compact else 12)
        y += 16
    if buttons:
        n = len(buttons); bw = (W - 80 - (n-1)*14) // n
        for i, (lab, kind) in enumerate(buttons):
            btn(d, P, (40 + i*(bw+14), y, 40 + i*(bw+14)+bw, y + 64), lab, kind)
        y += 84
    # R397 · 说明跟内容流走：去掉 1250 硬下限，短页面不再空半屏
    if foot: note(d, P, foot, min(1344, y + 20))
    return y

def status_card(d, P, y, lines, accent='primary', h=140):
    card(d, P, (40, y, W - 40, y + h), 20)
    d.rounded_rectangle((40, y, 50, y + h), 5, fill=C(P, accent))
    yy = y + 24
    for s, sz, key, bold in lines:
        txt(d, (72, yy), s, sz, P, key, bold)
        yy += sz + 12
    return y + h

# ---------------- 各页面 ----------------
def p_home(d, im, P, dark, state='empty'):
    y = frame(im, d, P, "听本", False, "☰")
    # 输入朗读区
    card(d, P, (40, y, W - 40, y + 250), 20)
    txt(d, (64, y + 20), "输入朗读", SECTION, P, 'text', True)
    txt(d, (W - 64, y + 26), "粘贴 · 清除", CAP, P, 'primary', True, anchor='ra')
    rr(d, (64, y + 70, W - 64, y + 158), 12, C(P, 'soft'))
    if state == 'typed':
        txt(d, (84, y + 84), "　　那条河从山脚绕过来，水色是极淡的青。", CAP, P, 'text')
        txt(d, (84, y + 116), "渡口的老船横在岸边，缆绳被日头晒得发白…", CAP, P, 'text')
        txt(d, (W - 84, y + 138), "已粘贴 128 字", MICRO, P, 'primary', True, anchor='ra')
    else:
        txt(d, (84, y + 92), "粘贴或输入要念的正文", CAP, P, 'sub')
    btn(d, P, (64, y + 176, W - 64, y + 226), "＋ 念一段", 'primary')
    y += 274
    y = section(d, P, y, "合成好的音频", "0 条" if state == 'empty' else "3 条" if state == 'multi' else "3 条")
    if state == 'empty':
        for i in range(3):
            box = (40, y, W - 40, y + 116)
            card(d, P, box, 16)
            checkbox(d, P, 78, y + 58, False)
            rr(d, (110, y + 26, 420, y + 48), 8, C(P, 'track'))
            rr(d, (110, y + 62, 300, y + 80), 7, C(P, 'track'))
            music_note(d, W - 96, y + 56, 34, C(P, 'line'))
            y += 128
        note(d, P, ["空槽位与真实条目同构；不写「还没有片段」。"], 1280)
    else:
        items = [("第 1 段 · 开头", "合成中 · 还有 2 段在合成", 'doing'),
                 ("第 2 段 · 巷口", "失败 · 点整行可重做", 'fail'),
                 ("第 3 段 · 灯下", "已完成 · 0:42", 'done')]
        for t, s, st in items:
            card(d, P, (40, y, W - 40, y + 116), 16)
            if state == 'multi': checkbox(d, P, 78, y + 58, True)  # R396 三条全勾＝已选 3
            txt(d, (110 if state == 'multi' else 66, y + 20), t, BODY, P, 'text', True)
            key = 'good' if st == 'done' else 'warn' if st == 'fail' else 'primary'
            txt(d, (110 if state == 'multi' else 66, y + 58), s, CAP, P, key)
            if st == 'fail': btn(d, P, (W - 180, y + 56, W - 66, y + 98), "重做", 'warn', MICRO)
            elif st == 'doing':
                d.arc((W - 108, y + 36, W - 68, y + 76), 0, 270, fill=C(P, 'primary'), width=5)
            else: txt(d, (W - 66, y + 58), "▶", 26, P, 'primary', anchor='ra')
            y += 128
    if state == 'multi':
        rr(d, (40, 1240, W - 40, 1310), 16, C(P, 'card'), C(P, 'line'), 2)
        txt(d, (64, 1256), "已选 3", BODY, P, 'primary', True)
        btn(d, P, (300, 1250, 470, 1300), "重做选中", 'primary', MICRO)
        btn(d, P, (486, 1250, W - 56, 1300), "删除选中", 'warn', MICRO)
    if state != 'empty':  # R397 空态不该还挂 38%/72%
        safe_balls(d, P, dark, dy=-230)

def p_home_menu(d, im, P, dark):
    p_home(d, im, P, dark, 'done')
    rr(d, (250, 470, W - 50, 760), 20, C(P, 'card'), C(P, 'line'), 2)
    txt(d, (276, 488), "第 3 段 · 灯下", BODY, P, 'text', True)
    txt(d, (276, 524), "已完成 · 0:42", CAP, P, 'good')
    for i, (lab, key) in enumerate([("播放", 'primary'), ("重做", 'text'),
                                    ("导出", 'text'), ("删除", 'warn')]):
        yy = 574 + i * 46
        txt(d, (276, yy), lab, BODY, P, key, lab == "播放" or lab == "删除")
    return None

def p_library(d, im, P, dark, state='empty'):
    return list_page(d, im, P, dark, "书架", [
        ("我的书", "0 本" if state == 'empty' else "3 本",
         [] if state == 'empty' else [
             ("《城南旧事》", "已完成 · 12 章音频 · 绿", 'arrow', True, None),
             ("《边城》", "制作中 · 第 2 章 72% · 紫", 'arrow', True, None),
             ("《呼兰河传》", "未开始 · 灰", 'arrow', True, None)])],
        trailing="＋ 导入一本书", back=False, foot=["长按书名可置顶或删除"])
    # 空态骨架 / 进度条由下面补画

def p_library_draw(d, im, P, dark, state='empty'):
    y = frame(im, d, P, "书架", False, "＋ 导入一本书")
    y = section(d, P, y, "我的书", "0 本" if state == 'empty' else "3 本")
    if state == 'empty':
        for i in range(3):
            card(d, P, (40, y, W - 40, y + 130), 16)
            rr(d, (66, y + 24, 380, y + 48), 8, C(P, 'track'))
            rr(d, (66, y + 66, 240, y + 84), 7, C(P, 'track'))
            rr(d, (66, y + 100, W - 66, y + 116), 6, C(P, 'track'))
            y += 142
        note(d, P, ["空态用与主页片段同构的槽位骨架，不写废话。"])
    else:
        books = [("《城南旧事》", "已完成 · 12 章音频", 'good', 100),
                 ("《边城》", "制作中 · 第 2 章 72%", 'primary', 72),
                 ("《呼兰河传》", "未开始", 'grey', 0)]
        for name, sub, color, pct in books:
            card(d, P, (40, y, W - 40, y + 140), 16)
            txt(d, (66, y + 18), name, BODY, P, 'text', True)
            txt(d, (66, y + 56), sub, CAP, P, 'sub')
            rr(d, (66, y + 100, W - 66, y + 116), 8, C(P, 'track'))
            if pct: rr(d, (66, y + 100, 66 + int((W - 132) * pct / 100), y + 116), 8, C(P, color))
            y += 152
    safe_balls(d, P, dark)

def p_library_dialog(d, im, P, dark, kind='menu'):
    p_library_draw(d, im, P, dark, 'full')
    if kind == 'menu':
        # R398 · 就地菜单锚定被长按的《边城》卡片（第二本 y≈370），
        # 不再浮在中间横切两章；「置顶 / 取消置顶」按状态只留一个。
        # 就地菜单也是模态：底层压一层轻遮罩，空白不再像没画完。
        layer = Image.new('RGBA', (W, H), (0, 0, 0, 0))
        ld = ImageDraw.Draw(layer)
        ld.rectangle((0, 176, W, H), fill=(8, 10, 14, 52) if not dark else (0, 0, 0, 70))
        im.paste(Image.alpha_composite(im.convert('RGBA'), layer).convert('RGB'), (0, 0))
        # 只描边、不填充：保留被操作卡片的书名与进度，别把锚点盖成空白框
        d.rounded_rectangle((40, 370, W - 40, 510), 16,
                            outline=C(P, 'primary'), width=3)
        # 下拉菜单从被操作卡片下沿展开（标准 DropdownMenu 行为），锚点卡完整可见
        rr(d, (150, 516, W - 46, 796), 20, C(P, 'card'), C(P, 'line'), 2)
        txt(d, (178, 534), "《边城》", BODY, P, 'text', True)
        txt(d, (178, 570), pin_status(UI_STATE['library_menu']['pinned']),
            CAP, P, 'sub')
        for i, (lab, key) in enumerate([
                (pin_action(UI_STATE['library_menu']['pinned']), 'text'),
                ("删除", 'warn')]):
            txt(d, (178, 634 + i*54), lab, BODY, P, key, lab == '删除')
        txt(d, (178, 754), "删除会连已做好的音频一起删掉", MICRO, P, 'warn')
        return None
    return dialog_overlay(im, P, dark, "删除《边城》？",
        ["只删这一本：音频、章节表、制作进度一起删。", "已导出的文件不在这里，不受影响。"],
        [("取消", 'ghost'), ("删除", 'warn')], warn=True)

def p_drawer(d, im, P, dark):
    frame(im, d, P, "听本", False, "☰")
    ov = Image.new('RGBA', (W, H), (0, 0, 0, 0)); od = ImageDraw.Draw(ov)
    od.rectangle((0, 0, W, H), fill=P['shade'])
    od.rectangle((0, 0, 500, H), fill=C(P, 'card') + (255,))
    od.line((500, 0, 500, H), fill=C(P, 'line') + (255,), width=2)
    od.text((40, 110), "听本", font=f(34, True), fill=C(P, 'text') + (255,))
    od.text((40, 166), "本地有声书 · 1.1.54", font=f(20), fill=C(P, 'sub') + (255,))
    for i, (lab, hi) in enumerate([("《城南旧事》", False), ("《边城》", True),
                                   ("《呼兰河传》", False)]):
        yy = 250 + i * 92
        if hi: od.rounded_rectangle((28, yy-12, 470, yy+64), 14, fill=C(P, 'soft') + (255,))
        od.text((52, yy + 8), lab, font=f(23, hi), fill=C(P, 'primary' if hi else 'text') + (255,))
        if hi: od.text((440, yy + 12), "正在看", font=f(18), fill=C(P, 'primary') + (255,), anchor='ra')
    od.line((28, 1140, 470, 1140), fill=C(P, 'line') + (255,), width=2)
    od.rounded_rectangle((28, 1180, 470, 1270), 16, fill=C(P, 'soft') + (255,))
    od.text((52, 1204), "＋ 导入一本书", font=f(23, True), fill=C(P, 'primary') + (255,))
    od.text((52, 1248), "设置", font=f(20), fill=C(P, 'primary') + (255,))
    od.text((446, 1248), "v1.1.54", font=f(18), fill=C(P, 'sub') + (255,), anchor='ra')
    return Image.alpha_composite(im.convert('RGBA'), ov).convert('RGB')

def p_resume(d, im, P, dark):
    frame(im, d, P, "听本", False, "☰")
    return dialog_overlay(im, P, dark, "上次没做完",
        ["《边城》第 2 章 · 未完成", "接着做会把没做完的段补上，已做好的不重跑。"],
        [("稍后", 'ghost'), ("查看任务", 'primary')], y0=470, h=340)

def p_chapters(d, im, P, dark, state='idle'):
    y = frame(im, d, P, "《边城》· 目录", True, "⋯")
    card(d, P, (40, y, W - 40, y + 170), 20)
    txt(d, (64, y + 18), "整本制作", CAP, P, 'sub')
    progress(d, P, 64, y + 76, W - 128, 38, "", 'primary')
    txt(d, (64, y + 116), "已出章音频", CAP, P, 'sub')
    progress(d, P, 64, y + 152, W - 128, 24, "", 'good') if False else None
    rr(d, (64, y + 146, W - 64, y + 160), 7, C(P, 'track'))
    rr(d, (64, y + 146, 64 + int((W-128)*0.24), y + 160), 7, C(P, 'good'))
    y += 194
    y = section(d, P, y, "章节", "3 章 · 已勾 0" if state == 'idle' else "3 章 · 已勾 3")
    for i, (name, st) in enumerate([("第一章 · 初到", 'done'), ("第二章 · 渡口", 'doing'), ("第三章 · 尾声", 'todo')]):
        box = (40, y, W - 40, y + 122)
        card(d, P, box, 16)
        checkbox(d, P, 78, y + 60, state == 'all')
        color = 'good' if st == 'done' else 'primary' if st == 'doing' else 'grey'
        d.rounded_rectangle((40, y + 16, 48, y + 106), 4, fill=C(P, color))
        txt(d, (112, y + 20), name, BODY, P, 'text', st == 'doing')
        txt(d, (112, y + 60), "已完成 · 12:04" if st == 'done' else
            "制作中 · 已生成 8 / 14 段" if st == 'doing' else "未开始",
            CAP, P, 'sub')
        y += 134
    if state == 'all':
        rr(d, (40, 1230, W - 40, 1310), 16, C(P, 'card'), C(P, 'line'), 2)
        txt(d, (60, 1248), "取消全选", CAP, P, 'primary', True)
        btn(d, P, (230, 1242, 400, 1298), "拼成整章", 'primary', MICRO)
        btn(d, P, (412, 1242, 552, 1298), "导出与另存", 'ghost', MICRO)
        btn(d, P, (564, 1242, W - 52, 1298), "删除选中", 'warn', MICRO)
    else:
        btn(d, P, (40, 1240, 300, 1300), "接着听", 'primary')
        btn(d, P, (316, 1240, W - 40, 1300), "按顺序播放", 'ghost')
    safe_balls(d, P, dark, dy=-330)

def p_chapter_menu(d, im, P, dark, kind='long'):
    p_chapters(d, im, P, dark, 'idle')
    if kind == 'long':
        # R398 · 锚定被长按的第二章卡片（y≈546），不再横切第一、二章
        layer = Image.new('RGBA', (W, H), (0, 0, 0, 0))
        ImageDraw.Draw(layer).rectangle((0, 176, W, H),
            fill=(8, 10, 14, 52) if not dark else (0, 0, 0, 70))
        im.paste(Image.alpha_composite(im.convert('RGBA'), layer).convert('RGB'), (0, 0))
        cm = UI_STATE['chapter_menu']
        d.rounded_rectangle((40, 546, W - 40, 668), 16,
                            outline=C(P, 'primary'), width=3)
        rr(d, (170, 566, W - 46, 836), 18, C(P, 'card'), C(P, 'line'), 2)
        txt(d, (196, 584), cm['chapter'], BODY, P, 'text', True)
        txt(d, (196, 620), "制作中 · %d / %d 段" % (cm['done'], cm['total']),
            CAP, P, 'primary')
        for i, lab in enumerate(["试听本章", "整章重做", "全选已生成", "删除选中"]):
            txt(d, (196, 670 + i*44), lab, BODY, P, 'warn' if lab == '删除选中' else 'text')
        return None
    return dialog_overlay(im, P, dark, "重新分章",
        ["当前 3 章 → 识别 338 章", "预览与确认用的是同一份章表；只列开头和结尾。",
         "分段音频作废，要重新做；已拼好的章音频、原书文字不动。"],
        [("取消", 'ghost'), ("确认重新分章", 'warn')], warn=True, y0=380, h=430)

def p_mask(d, im, P, dark):
    p_chapters(d, im, P, dark, 'idle')
    ov = Image.new('RGBA', (W, H), (0, 0, 0, 0)); od = ImageDraw.Draw(ov)
    od.rectangle((40, 560, W - 40, 720), fill=P['shade'])
    od.rounded_rectangle((70, 578, W - 70, 706), 16, fill=C(P, 'card') + (255,))
    od.text((96, 596), "第二章 · 渡口", font=f(23, True), fill=C(P, 'text') + (255,))
    od.text((96, 640), "拼接中 · 38%", font=f(21), fill=C(P, 'primary') + (255,))
    od.arc((W - 150, 620, W - 108, 662), 0, 270, fill=C(P, 'primary') + (255,), width=5)
    return Image.alpha_composite(im.convert('RGBA'), ov).convert('RGB')

def p_blocks(d, im, P, dark, multi=False):
    y = frame(im, d, P, "《边城》· 制作", True)
    card(d, P, (40, y, W - 40, y + 116), 18)
    txt(d, (64, y + 18), "第二章 · 渡口", BODY, P, 'text', True)
    txt(d, (64, y + 58), "已生成 8 / 14 段 · 正在往下做", CAP, P, 'primary')
    y += 140
    y = section(d, P, y, "小块格子", "14 段")
    cols, cw, ch = 5, 118, 104
    for i in range(14):  # R396 标题 14 段＝画 14 格
        x = 40 + (i % cols) * (cw + 10)
        yy = y + (i // cols) * (ch + 10)
        st = 'done' if i < 8 else 'doing' if i == 8 else 'todo'
        fill = C(P, 'good') if st == 'done' else C(P, 'soft') if st == 'doing' else C(P, 'track')
        rr(d, (x, yy, x + cw, yy + ch), 12, fill, C(P, 'line'))
        if multi and st == 'done': checkbox(d, P, x + 26, yy + 26, True)
        txt(d, (x + cw//2, yy + 30), "%02d" % (i+1), BODY, P, 'text' if st != 'todo' else 'grey', True, anchor='mm')
        txt(d, (x + cw//2, yy + 68), "已生成" if st == 'done' else "合成中" if st == 'doing' else "未生成",
            MICRO, P, 'onPrimary' if st == 'done' else 'sub', anchor='mm')
    y += 3 * (ch + 10) + 20
    note(d, P, ["绿＝已生成 · 柔底＝合成中 · 灰＝未生成；同一时刻只画一个活跃章。"], y)
    if multi:
        rr(d, (40, 1240, W - 40, 1310), 16, C(P, 'card'), C(P, 'line'), 2)
        txt(d, (64, 1256), "已选 8", BODY, P, 'primary', True)  # R396 与 8 个勾一致
        btn(d, P, (280, 1250, 460, 1300), "重做选中", 'primary', MICRO)
        btn(d, P, (476, 1250, W - 56, 1300), "删除选中", 'warn', MICRO)

def p_reading(d, im, P, dark):
    y = frame(im, d, P, "阅读 · 第 2 / 3 章", True, "A-  A+")
    card(d, P, (40, y, W - 40, y + 960), 18)
    for i, line in enumerate([
        "第二章 · 渡口", "", "　　那条河从山脚绕过来，水色是极淡的青。",
        "渡口的老船横在岸边，缆绳被日头晒得发白。", "　　他站在石阶上，等了半晌，",
        "才听见对岸有人唤了一声。声音贴着水面过来，", "比人先到。", "",
        "　　风把芦苇压下去，又松开来，像谁轻轻翻了一页书。"]):
        txt(d, (72, y + 30 + i * 74), line, BODY, P, 'text', i == 0)
    rr(d, (40, 1240, W - 40, 1310), 16, C(P, 'card'), C(P, 'line'), 2)
    btn(d, P, (60, 1250, 220, 1300), "‹ 上一章", 'ghost', MICRO)
    txt(d, (W//2, 1272), "字号 16", CAP, P, 'sub', anchor='mm')
    btn(d, P, (W - 220, 1250, W - 60, 1300), "下一章 ›", 'ghost', MICRO)

def p_produce(d, im, P, dark):
    def tc(d, P, y):
        return status_card(d, P, y, [("参数在设置页 · 勾章在目录页", CAP, 'sub', False),
                                     ("做好了：去听", BODY, 'good', True)], 'good', 120)
    return list_page(d, im, P, dark, "制作设置", [
        ("这本书", None, [
            ("每段约 1–3 分钟", "块越多等得越久", 'none', True, None),
            ("重新分章 ›", "章号会重排，已做好的分段要重来", 'arrow', True, None),
            ("打开设置 ›", "怎么念 / 怎么跑 / 怎么拼，改一次对所有书生效", 'arrow', True, None)]),
        ("做好了以后", None, [
            ("从头重做（清掉已做的块）", "只清制作进度，成品不动", 'arrow', True, None)])],
        trailing="← 目录", topcard=tc, buttons=[("返回目录", 'ghost')],
        foot=["点停止后要等当前段跑完才收尾。"])

def p_tasks(d, im, P, dark, state='doing'):
    def tc(d, P, y):
        return status_card(d, P, y, [("第 2 章 · 正在拼接中 · 38%", BODY, 'text', True),
                                     ("《边城》 · 整本 38%", CAP, 'sub', False)], 'primary', 120)
    tabs = ["合成中", "队列中", "已完成"]
    sel = {'doing': 0, 'doing_empty': 0, 'queue': 1, 'done': 2, 'done_empty': 2}[state]
    y = frame(im, d, P, "任务", False, "☰")
    bw = (W - 80 - 32) // 3
    for i, t in enumerate(tabs): chip(d, P, (40 + i*(bw+16), y, 40 + i*(bw+16)+bw, y+56), t, i == sel)
    y += 80
    if state in ('doing_empty', 'done_empty'):
        for i in range(2):
            card(d, P, (40, y, W - 40, y + 130), 16)
            rr(d, (66, y + 28, 360, y + 50), 8, C(P, 'track'))
            rr(d, (66, y + 68, 240, y + 86), 7, C(P, 'track'))
            y += 142
        btn(d, P, (40, y + 20, 330, y + 84), "去挑一本 ›", 'primary')
        btn(d, P, (346, y + 20, W - 40, y + 84), "看队列 ›", 'ghost')
        note(d, P, ["现在没有在做的音频" if state == 'doing_empty' else "还没有已完成的音频"], 660)
        return
    if state == 'doing':
        card(d, P, (40, y, W - 40, y + 190), 18)
        txt(d, (64, y + 20), "《边城》 · 第 2 章", BODY, P, 'text', True)
        progress(d, P, 64, y + 96, W - 128, 38, "正在拼接中", 'primary', "已做好 8 / 14 段")
        for i, lab in enumerate(["暂停", "看队列", "删除"]):
            btn(d, P, (64 + i*198, y + 134, 64 + i*198 + 182, y + 176), lab,
                'ghost' if i < 2 else 'warn', MICRO)
        y += 214
        # R399 · 按 R352/TaskUi.kt 真实形态：合成中 / 队列 / 已完成同页常驻，
        # 不再切成互斥页；补这两段后下半屏不再空一大片。
        y = section(d, P, y + 10, "队列", "1 份")
        card(d, P, (40, y, W - 40, y + 116), 16)
        txt(d, (66, y + 18), "《城南旧事》 · 全本", BODY, P, 'text', True)
        txt(d, (66, y + 58), "排队中 · 前面还有 1 份", CAP, P, 'sub')
        chip(d, P, (W - 220, y + 34, W - 66, y + 82), "排队中", True, MICRO)
        y += 132
        y = section(d, P, y + 10, "已完成", "2 份")
        for i, (name, size) in enumerate([("《边城》 · 第 1 章", "6.4 MB · M4A"),
                                          ("《呼兰河传》 · 第 3 章", "5.1 MB · M4A")]):
            card(d, P, (40, y, W - 40, y + 116), 16)
            txt(d, (66, y + 18), name, BODY, P, 'text', True)
            txt(d, (66, y + 58), size, CAP, P, 'sub')
            txt(d, (W - 66, y + 52), "▶", 26, P, 'primary', anchor='ra')
            y += 128
    elif state == 'queue':
        for i, (lab, st) in enumerate([("一份音频", "排队中 · 前面还有 1 份"),
                                       ("一份音频", "已暂停 · 点条形回第 2 章"),
                                       ("一份音频", "需重新确认 · 旧任务需重新选章")]):
            card(d, P, (40, y, W - 40, y + 130), 16)
            txt(d, (64, y + 18), lab, BODY, P, 'text', True)
            chip(d, P, (64, y + 62, 260, y + 106), st.split(' · ')[0], True, MICRO)
            txt(d, (280, y + 70), st.split(' · ')[1], CAP, P, 'sub')
            y += 142
    else:
        for i, (name, size) in enumerate([("《城南旧事》· 全本", "38.2 MB · M4A"),
                                          ("《边城》· 第 1 章", "6.4 MB · M4A")]):
            card(d, P, (40, y, W - 40, y + 130), 16)
            txt(d, (64, y + 18), name, BODY, P, 'text', True)
            txt(d, (64, y + 62), size, CAP, P, 'sub')
            btn(d, P, (W - 250, y + 40, W - 160, y + 96), "播放 ›", 'ghost', MICRO)
            txt(d, (W - 50, y + 58), "删除音频", CAP, P, 'warn', True, anchor='ra')
            y += 142
    note(d, P, ["一条＝一份音频；做好的音频和待续任务都在下面。"])

def p_task_del(d, im, P, dark):
    p_tasks(d, im, P, dark, 'done')
    return dialog_overlay(im, P, dark, "删掉这条音频？",
        ["只删这一份做好的音频，书和原文字不动。"],
        [("取消", 'ghost'), ("删除", 'warn')], warn=True)

def mini_player(d, P):
    box = (24, H - 168, W - 24, H - 24)
    rr(d, box, 22, C(P, 'card'), C(P, 'line'), 2)
    d.rounded_rectangle((W // 2 - 30, H - 164, W // 2 + 30, H - 160), 2,
                        fill=C(P, 'line'))  # R396 顶部把手
    d.rounded_rectangle((40, H - 152, 128, H - 40), 14, fill=C(P, 'soft'))
    music_note(d, 84, H - 96, 44, C(P, 'primary'))
    txt(d, (150, H - 140), "《边城》· 第 2 章", BODY, P, 'text', True)
    txt(d, (150, H - 100), "正在播放 · 05:03 / 12:04", CAP, P, 'sub')
    rr(d, (150, H - 66, W - 60, H - 58), 4, C(P, 'track'))
    rr(d, (150, H - 66, 150 + int((W - 210) * 0.42), H - 58), 4, C(P, 'primary'))
    txt(d, (W - 56, H - 100), "▶", 30, P, 'primary', anchor='rm')

def p_player(d, im, P, dark, state='mini'):
    y = frame(im, d, P, "听本", False, "☰")
    txt(d, (40, y + 10), "书架", SECTION, P, 'text', True)
    if state == 'mini':
        for i in range(3):
            card(d, P, (40, y + 60 + i*130, W - 40, y + 170 + i*130), 16)
        mini_player(d, P)
        # R396 · 设计说明移到索引，不画进界面（收起条把手由 mini_player 画）
    elif state == 'half':
        for i in range(2):
            card(d, P, (40, y + 60 + i*130, W - 40, y + 170 + i*130), 16)
        rr(d, (24, H - 320, W - 24, H - 24), 22, C(P, 'card'), C(P, 'line'), 2)
        d.rounded_rectangle((W//2 - 30, H - 308, W//2 + 30, H - 302), 3, fill=C(P, 'line'))
        d.rounded_rectangle((40, H - 270, 190, H - 70), 16, fill=C(P, 'soft'))
        music_note(d, 115, H - 170, 64, C(P, 'primary'))
        txt(d, (220, H - 250), "《边城》· 第 2 章", BODY, P, 'text', True)
        txt(d, (220, H - 206), "正在播放 · 第 2/4 条 · 12:04", CAP, P, 'sub')
        safe_balls(d, P, dark, mini=True)
    else:
        ov = Image.new('RGBA', (W, H), (0, 0, 0, 0)); od = ImageDraw.Draw(ov)
        od.rectangle((0, 150, W, H), fill=C(P, 'card') + (255,))
        od.rounded_rectangle((W//2-30, 164, W//2+30, 170), 3, fill=C(P, 'line') + (255,))
        od.text((40, 190), "停止", font=f(20), fill=C(P, 'warn') + (255,))
        od.text((W-40, 190), "×", font=f(28, True), fill=C(P, 'sub') + (255,), anchor='ra')
        od.rounded_rectangle((120, 250, 600, 730), 28, fill=C(P, 'soft') + (255,))
        music_note(od, 360, 490, 150, C(P, 'primary') + (255,))
        od.text((360, 770), "《边城》· 第 2 章", font=f(28, True), fill=C(P, 'text') + (255,), anchor='mm')
        od.text((360, 812), "正在播放 · 第 2/4 条 · 12:04", font=f(20), fill=C(P, 'sub') + (255,), anchor='mm')
        od.rounded_rectangle((60, 880, 660, 892), 6, fill=C(P, 'track') + (255,))
        od.rounded_rectangle((60, 880, 60 + int(600*0.42), 892), 6, fill=C(P, 'primary') + (255,))
        od.text((60, 906), "05:03", font=f(18), fill=C(P, 'sub') + (255,))
        od.text((660, 906), "12:04", font=f(18), fill=C(P, 'sub') + (255,), anchor='ra')
        for i, lab in enumerate(["◀◀", "▶", "▶▶", "1.0×"]):
            x = 110 + i * 170
            od.rounded_rectangle((x - 46, 980, x + 46, 1050), 35, fill=C(P, 'soft') + (255,))
            od.text((x, 1015), lab, font=f(22, True), fill=C(P, 'primary') + (255,), anchor='mm')
        if state == 'list':
            od.rounded_rectangle((40, 1100, W - 40, 1360), 18,
                                 fill=C(P, 'bg') + (255,), outline=C(P, 'line') + (255,), width=2)
            od.text((64, 1116), "播放队列 · 4 条", font=f(22, True), fill=C(P, 'text') + (255,))
            for i in range(4):  # R396 标题「4 条」＝列 4 条
                yy = 1160 + i * 48
                od.text((64, yy), "%d. 《边城》· 第 %d 章" % (i+1, i+1), font=f(20),
                        fill=C(P, 'primary' if i == 1 else 'text') + (255,))
                if i == 1: od.text((640, yy), "正在播", font=f(18), fill=C(P, 'good') + (255,), anchor='ra')
        else:
            for i, lab in enumerate(["顺序", "单曲", "随机"]):
                x = 140 + i * 220
                od.rounded_rectangle((x-80, 1120, x+80, 1176), 28,
                    fill=C(P, 'soft') + (255,) if i == 0 else C(P, 'card') + (255,))
                od.text((x, 1148), lab, font=f(20, i == 0), fill=C(P, 'primary') + (255,), anchor='mm')
        return Image.alpha_composite(im.convert('RGBA'), ov).convert('RGB')

def p_overlay(d, im, P, dark, drag=False):
    frame(im, d, P, "听本", False, "☰")
    for i in range(4):
        card(d, P, (40, 200 + i*130, W - 40, 310 + i*130), 16)
    if drag:
        for i in range(9):
            x = 250 + i * 42
            d.ellipse((x, H - 520 + i*24, x + 12, H - 508 + i*24), fill=C(P, 'line'))
        arrow(d, P, W - 140, H - 320, 14, 'primary')
        pass  # R396 设计说明移到索引，不画进界面
    else:
        pass  # R396 设计说明移到索引，不画进界面
    safe_balls(d, P, dark, 38, 72)

def p_models(d, im, P, dark, state='missing'):
    def tc(d, P, y):
        return status_card(d, P, y, [
            ("4 份已就绪" if state == 'ready' else "缺 4 份", BODY,
             'good' if state == 'ready' else 'warn', True),
            ("当前 · 「%s」" % UI_STATE['model']['current'] if state == 'ready' else "共用底座 · 模式主文件装一份就能用",
             CAP, 'sub', False)], 'good' if state == 'ready' else 'warn', 130)
    rows = [("① 共用底座", "12Hz 码 ↔ 24kHz 波形 · 装一份就够",
             'value', True, "未导入" if state == 'missing' else "已就绪"),
            ("② 描述造音色主文件", "当前模式" if state == 'ready' else "导入这份 ›",
             'value', True, "已启用" if state == 'ready' else "缺"),
            ("③ 预设音色主文件", "九种音色", 'value', True,
             "已就绪" if state == 'ready' else "缺"),
            ("④ 参考音频主文件", "3~10 秒干净人声", 'value', True,
             "已就绪" if state == 'ready' else "缺")]
    list_page(d, im, P, dark, "模型", [("逐份清单", "4 份", rows)],
              trailing="关闭", topcard=tc,
              buttons=[("导入模型文件", 'primary')] if state == 'missing' else
                      ([] if UI_STATE['model']['current'] == '描述造音色'
                        else [("改用「描述造音色」", 'ghost')]),
              foot=["模型自行导入，不进安装包；正在制作，跑完前不能切。"])
    if state == 'ready':
        # R398 · 与 ModelUi.kt 同口径：当前模式不再给互斥的「改用」按钮，
        # 改以一行说明讲清「合成就用这一模式，不用切回」，不空着也不硬塞动作。
        txt(d, (40, 940), "合成就用这一模式，不用切回。", CAP, P, 'sub')
        rr(d, (40, 980, W - 40, 1180), 18, C(P, 'card'), C(P, 'line'), 2)
        txt(d, (64, 998), "详情 · 只在非当前模式给「启用 ›」", CAP, P, 'sub')
        for i, lab in enumerate(["描述造音色 · 当前模式", "预设音色 · 启用 ›", "参考音频 · 启用 ›"]):
            txt(d, (64, 1040 + i*44), lab, BODY, P, 'primary' if i else 'text', i == 0)

def p_model_uninstall(d, im, P, dark):
    p_models(d, im, P, dark, 'ready')
    return dialog_overlay(im, P, dark, "卸载已导入的模型？",
        ["只卸这一份；已做好的音频不受影响。"],
        [("取消", 'ghost'), ("卸载", 'warn')], warn=True)

def p_mixer(d, im, P, dark, mode=1):
    y = frame(im, d, P, "调音台", True, "保存设置")
    txt(d, (40, y), "模式全局生效 · 保存设置不改模式", MICRO, P, 'sub')
    y += 44
    for i, lab in enumerate(["描述音色", "预设音色", "参考音频"]):
        chip(d, P, (40 + i*226, y, 40 + i*226 + 210, y + 56), lab, i == mode-1)
    y += 80
    if mode == 1:
        card(d, P, (40, y, W - 40, y + 220), 18)
        txt(d, (64, y + 20), "描述想要的音色", BODY, P, 'text', True)
        rr(d, (64, y + 66, W - 64, y + 150), 12, C(P, 'soft'))
        txt(d, (84, y + 86), "留空就用原来的中性声；填了才会变", CAP, P, 'sub')
        btn(d, P, (64, y + 166, 300, y + 206), "试听 · 已存配置", 'ghost', MICRO)
        btn(d, P, (316, y + 166, W - 64, y + 206), "试听 · 当前改动", 'ghost', MICRO)
        y += 244
        y = section(d, P, y, "采样参数 · 9 项", "9 项")
        card(d, P, (40, y, W - 40, y + 96), 16)
        txt(d, (64, y + 20), "九项中文标签 · 真实默认值 / 范围 / 单位", CAP, P, 'sub')
        txt(d, (64, y + 56), "↺ 复原 · 全部恢复默认", CAP, P, 'primary')
    elif mode == 2:
        y = section(d, P, y, "中文九种音色", "已选：温和女声")
        groups = [("女声", ["温和女声", "清亮女声", "沉稳女声"]),
                  ("男声", ["醇厚男声", "清朗男声", "低沉男声"]),
                  ("少年音", ["少年音", "少女音", "中性声"])]
        for g, items in groups:
            txt(d, (40, y), g, CAP, P, 'sub'); y += 38
            for i, lab in enumerate(items):
                x = 40 + i * 218
                chip(d, P, (x, y, x + 202, y + 62), lab, lab == "温和女声", CAP)
            y += 80
        card(d, P, (40, y, W - 40, y + 110), 16)
        txt(d, (64, y + 18), "官方音色描述：", CAP, P, 'sub')
        txt(d, (64, y + 54), "风格补充（可留空）", BODY, P, 'text')
    else:
        card(d, P, (40, y, W - 40, y + 300), 18)
        txt(d, (64, y + 20), "参考音频", BODY, P, 'text', True)
        btn(d, P, (64, y + 76, W - 64, y + 136), "导入一段人声并裁剪 ›", 'primary')
        rr(d, (64, y + 156, W - 64, y + 226), 12, C(P, 'soft'))
        txt(d, (84, y + 176), "选 3~10 秒干净人声", CAP, P, 'sub')
        txt(d, (84, y + 202), "这段录音念的是什么（可留空）", MICRO, P, 'sub')
        btn(d, P, (W - 200, y + 244, W - 64, y + 284), "删掉", 'warn', MICRO)
    safe_balls(d, P, dark)

def p_mixer_params(d, im, P, dark):
    y = frame(im, d, P, "调音台", True, "保存设置")
    chip(d, P, (40, y, 250, y + 56), "描述音色", True)
    chip(d, P, (266, y, 476, y + 56), "预设音色", False)
    chip(d, P, (492, y, W - 40, y + 56), "参考音频", False)
    y += 80
    y = section(d, P, y, "采样参数 · 9 项", "全部恢复默认")
    items = [("温度", "0.7", "0.1 – 1.2"), ("top_p", "0.9", "0.5 – 1.0"),
             ("重复惩罚", "1.05", "1.0 – 1.5"), ("语速", "1.0×", "0.5 – 2.0"),
             ("段间静音", "300 ms", "0 – 1200"), ("采样步数", "24", "8 – 64"),
             ("随机种子", "随机", "固定 / 随机"), ("音高", "0", "-6 – +6"),
             ("能量", "1.0", "0.5 – 1.5")]
    for i, (name, val, rng) in enumerate(items):
        box = (40, y, W - 40, y + 92)
        card(d, P, box, 14)
        txt(d, (64, y + 16), name, BODY, P, 'text', True)
        txt(d, (64, y + 52), rng, MICRO, P, 'sub')
        txt(d, (W - 190, y + 28), val, BODY, P, 'primary', True, anchor='ra')
        txt(d, (W - 66, y + 30), "↺", CAP, P, 'primary', anchor='ra')
        y += 100
    note(d, P, ["九项只在「描述造音色」模式露出；切到另两种模式整块收起。"], 1120)

def p_mixer_dialog(d, im, P, dark, kind):
    p_mixer(d, im, P, dark, 1)
    if kind == 'select':
        return dialog_overlay(im, P, dark, "选择预设",
            ["列表里只显示简短描述；点一下把详细描述填进输入框。",
             "温和女声 · 在用 / 清朗少年 / 深夜电台"],
            [("关闭", 'ghost'), ("＋添加预设 ›", 'primary')], y0=380, h=400)
    if kind == 'add':
        return dialog_overlay(im, P, dark, "＋添加预设",
            ["简短描述（列表里显示） / 详细描述（真正喂给模型）",
             "两份都要填：简短的给你认，详细的才送进模型。"],
            [("取消", 'ghost'), ("保存", 'primary')], y0=380, h=400)
    if kind == 'crop':
        return dialog_overlay(im, P, dark, "裁一段人声",
            ["按住波形两端拖，选一小段干净人声", "选 3~10 秒最稳",
             "已选 5.4 秒 / 共 12.0 秒"],
            [("取消", 'ghost'), ("用这一段", 'primary')], y0=380, h=400)
    return dialog_overlay(im, P, dark, "改动还没保存",
        ["离开会丢掉这次改动。", "逐项：描述文本 / 风格补充 / 采样参数 3 项"],
        [("丢弃并离开", 'warn'), ("继续编辑", 'ghost'), ("保存并离开", 'primary')],
        y0=380, h=420)

def p_settings(d, im, P, dark):
    def tc(d, P, y):
        return status_card(d, P, y, [("4 份已就绪", BODY, 'good', True),
                                     ("软件版本 1.1.54 · 默认零联网", CAP, 'sub', False)], 'good', 130)
    entries = [("外观设置", "主题 · 悬浮进度 · 图例"),
               ("运行与通知", "打开软件自动续跑"),
               ("制作设置", "段间静音 · 自动拼章 · 接缝"),
               ("权限管理", "通知 · 电池优化 · 悬浮窗"),
               ("音频文件", "浏览 · 批量导出 · 删除"),
               ("语音网关", "本机接口 · 音色只读本机"),
               ("跨网中继", "GitHub 免费仓库 · 零付费 · 压缩音频回传"),
               ("请求文本", "两渠道切换 · 各 10 条 · 复制 / 多选删 / 清空"),
               ("开发者工具", "诊断摘要 · 完整日志")]
    rows = [(a, b, 'arrow', True, None) for a, b in entries]
    return list_page(d, im, P, dark, "设置", [("设置分类", "全部", rows)],
                     trailing="", topcard=tc, compact=True,
                     foot=["顶栏只有一行：返回箭头 + 当前页名。"])

def p_settings_sub(d, im, P, dark, page):
    titles = {"appearance": "外观设置", "runtime": "运行与通知", "produce": "制作设置",
              "perms": "权限管理", "files": "音频文件", "dev": "开发者工具"}
    title = titles[page]
    if page == 'appearance':
        return list_page(d, im, P, dark, title, [
            ("外观与显示", "软件", [
                ("主题", "三段选择器 · 跟随系统 / 浅色 / 深色", 'value', True, "跟随系统"),
                ("悬浮进度", "不透明圆球 · 整本 / 本章", 'switch', True, None),
                ("图例", "制作中小块格子的五色说明", 'arrow', True, None)]),
            ("字号与层级", None, [
                ("页面标题 18 / 分区 14", "正文 14 · 注释 12 · 微字 11", 'none', True, None)])],
            buttons=[("恢复默认", 'ghost')], foot=["改动对所有书生效。"])
    if page == 'runtime':
        return list_page(d, im, P, dark, title, [
            ("运行", None, [
                ("打开软件自动续跑", "做完的不重跑", 'switch', True, None),
                ("通知", "前台服务与完成提醒", 'arrow', True, None),
                ("电池优化", "被系统清掉时给一行提示", 'arrow', True, None)])],
            foot=["通知「点这里关闭」随时停；不强拉活。"])
    if page == 'produce':
        return list_page(d, im, P, dark, title, [
            ("怎么念", None, [("音色与采样", "在调音台设置", 'arrow', True, None)]),
            ("怎么跑", None, [
                ("段间静音", "300 ms", 'value', True, "300 ms"),
                ("自动拼章", "一章的小块做完就拼", 'switch', True, None),
                ("拼好后删除中间小块", "关＝留着且能导出 / 删除", 'switch', True, None)]),
            ("怎么拼", None, [("接缝", "校验通过才删内部 WAV", 'value', True, "M4A 64k")])],
            buttons=[("保存设置", 'primary')], foot=["参数改一次对所有书生效；不再有「合成音频」大按钮。"])
    if page == 'perms':
        return list_page(d, im, P, dark, title, [
            ("权限", "未开 2 项", [
                ("通知", "前台服务与完成提醒", 'value', True, "去允许 ›"),
                ("电池优化", "别被系统清掉", 'value', True, "去允许 ›"),
                ("悬浮窗", "悬浮进度球", 'value', True, "已开启"),
                ("应用详情", "不是权限，是入口", 'value', True, "打开设置 ›")])],
            foot=["未开态四枚按钮统一主色；副标题压短说用途。"])
    if page == 'files':
        return list_page(d, im, P, dark, title, [
            ("本机音频 · 按书分组", "3 份", [
                ("《城南旧事》· 全本", "12 章 · 38.2 MB", 'arrow', True, None),
                ("《边城》· 第 1 章", "6.4 MB · M4A", 'arrow', True, None)])],
            buttons=[("浏览", 'primary'), ("反选", 'ghost')],
            foot=["导出与删除都在浏览窗口；内部路径不摆给用户。"])
    return list_page(d, im, P, dark, title, [
        ("开发日志", None, [
            ("一键复制摘要", "只记诊断信号，不记书名与正文", 'arrow', True, None),
            ("导出完整 .md", "最近 24 小时 · 过期自动删", 'arrow', True, None)]),
        ("诊断", None, [("软件版本", "1.1.54", 'value', True, "查看 ›")])],
        foot=["日志不落原文；令牌在仓库外。"])

def p_gateway(d, im, P, dark):
    gw_label, gw_hint = switch_card('gateway')
    def tc(d, P, y):
        return status_card(d, P, y, [("语音网关 · " + gw_label, BODY, 'text', True),
                                     ("127.0.0.1:8765 · 只本机 / 可选局域网", CAP, 'sub', False)], 'primary', 130)
    return list_page(d, im, P, dark, "语音网关", [
        ("接口", None, [
            ("开启本机网关", gw_hint, 'switch', UI_STATE['gateway']['enabled'], None),
            ("允许局域网", "默认只绑 127.0.0.1", 'switch', False, None),
            ("接口地址", "http://127.0.0.1:8765/v1/speak", 'value', True, "复制地址与 Token")]),
        ("安全", None, [
            ("Token", "只存本机，不进安装包、不进日志", 'arrow', True, None),
            ("怎么调用", "错误一律 JSON，不回 HTML；跨网请用「跨网中继」页", 'arrow', True, None)]),
        ("请求文本", None, [
            ("查看请求文本 ›", "只留文字，不留音频", 'arrow', True, None)])],
        topcard=tc, buttons=[("重新生成 Token", 'ghost')],
        foot=["调用方改不了音色 · 九项采样取本机调音台预设",
              "短文本直接回音频 · 长文本走任务 · 回传前压 M4A"])

def p_relay(d, im, P, dark):
    rl_label, rl_hint = switch_card('relay')
    def tc(d, P, y):
        return status_card(d, P, y, [("跨网中继 · " + rl_label, BODY, 'text', True),
                                     ("GitHub 免费私有仓库 · 0 元 / 0 服务器", CAP, 'sub', False)], 'primary', 130)
    return list_page(d, im, P, dark, "跨网中继", [
        ("连接", None, [
            ("账号", "zjh1934", 'value', True, "复制仓库地址"),
            ("仓库", "tingben-relay（私有）", 'value', True, "复制网页地址"),
            ("GitHub Token", "只存在本机，不进安装包、不进日志、不进诊断摘要", 'none', True, None),
            ("开启跨网中继", rl_hint, 'switch', UI_STATE['relay']['enabled'], None)]),
        ("怎么工作", None, [
            ("3 步闭环", "开始两下，完成是近 1 秒长收尾", 'none', True, None),
            ("查看请求文本 ›", "只留文字，不留音频", 'arrow', True, None)])],
        topcard=tc, buttons=[("一次复制两段地址", 'primary')],
        foot=["文字会经过 GitHub 私有仓库，合成完立刻删请求文件；",
              "Token 只给这一个仓库；异步，几秒到十几秒。"])

def p_archive(d, im, P, dark, state='relay'):
    y = frame(im, d, P, "请求文本", True)
    for i, lab in enumerate(["跨网中继 · 13", "语音网关 · 0"]):
        chip(d, P, (40 + i*330, y, 40 + i*330 + 314, y + 60), lab, i == (0 if state == 'relay' else 1))
    y += 84
    y = section(d, P, y, "跨网中继" if state == 'relay' else "语音网关",
                "共 13 条" if state == 'relay' else "共 0 条")
    if state == 'gateway':
        card(d, P, (40, y, W - 40, y + 150), 18)
        txt(d, (64, y + 24), "还没有请求", BODY, P, 'text', True)
        txt(d, (64, y + 66), "这个渠道收到合成请求后，文字会留在这里。", CAP, P, 'sub')
    else:
        items = ["诶你又盯着我腿看～这回可别躲了。",
                 "把灯调暗一点，我念给你听。",
                 "风从窗缝里进来，带着一点潮气。"]
        for i, s in enumerate(items):
            card(d, P, (40, y, W - 40, y + 116), 16)
            if state == 'multi': checkbox(d, P, 76, y + 58, True)  # R396 三条全勾＝已选 3
            lx = 110 if state == 'multi' else 64
            txt(d, (lx, y + 18), s[:16] + ("…" if len(s) > 16 else ""), BODY, P, 'text', True)
            txt(d, (lx, y + 58), "11-25 09:%02d · %d 字" % (10 + i, len(s)), MICRO, P, 'sub')
            txt(d, (W - 66, y + 24), "复制", CAP, P, 'primary', True, anchor='ra')
            y += 128
        btn(d, P, (40, y + 8, 300, y + 64), "收起 · 看 10 条", 'ghost', MICRO)
        txt(d, (W - 40, y + 26), "展开全部 13 条 ›", MICRO, P, 'primary', True, anchor='ra')
        y += 90
    card(d, P, (40, y, W - 40, y + 110), 18)
    if state == 'multi':
        txt(d, (64, y + 16), "已选 3", BODY, P, 'primary', True)
        btn(d, P, (220, y + 22, 400, y + 82), "复制选中", 'primary', MICRO)
        btn(d, P, (416, y + 22, 570, y + 82), "删除选中", 'tertiary', MICRO)
        btn(d, P, (586, y + 22, W - 56, y + 82), "退出多选", 'ghost', MICRO)
    else:
        btn(d, P, (64, y + 22, 250, y + 82), "一键复制全部", 'primary', MICRO)
        btn(d, P, (266, y + 22, 420, y + 82), "多选", 'ghost', MICRO)
        btn(d, P, (436, y + 22, W - 64, y + 82), "一键清空全部文本", 'tertiary', MICRO)
    note(d, P, ["只留文字，不留音频；本机私有区，不进日志与诊断导出。",
                "说明不折叠、不藏小问号，降级成底部两行微字。"])
    if state == 'expanded':
        rr(d, (56, 430, W - 56, 760), 20, C(P, 'card'), C(P, 'line'), 2)
        txt(d, (84, 450), "诶你又盯着我腿看～这回可别躲了。", BODY, P, 'text', True)
        txt(d, (84, 494), "跨网中继 · 11-25 09:10 · 15 字", CAP, P, 'sub')
        hline(d, P, 84, W - 84, 536)
        para(d, (84, 556), "这条请求只留文字，合成后的音频即用即删；点「复制」把整段文本带走。",
             BODY, P, 'sub', maxw=W - 168)
        btn(d, P, (84, 676, W - 84, 730), "复制这条", 'primary')
    return None

def p_archive_clear(d, im, P, dark):
    p_archive(d, im, P, dark, 'relay')
    return dialog_overlay(im, P, dark, "清空全部请求文本？",
        ["跨网中继和语音网关一起清掉，共 13 条。", "只清文字；音频本来就没留。"],
        [("取消", 'ghost'), ("确认清空", 'warn')], warn=True, dim_actions=True)

def p_desc(d, im, P, dark, kind='select'):
    p_mixer(d, im, P, dark, 1)
    if kind == 'select':
        return dialog_overlay(im, P, dark, "选择预设",
            ["列表里只显示简短描述；点一下把详细描述填进输入框。",
             "还没有预设，先存一个 → ＋添加预设 ›"],
            [("关闭", 'ghost'), ("＋添加", 'primary')], y0=380, h=400)
    return dialog_overlay(im, P, dark, "＋添加预设",
        ["简短描述（列表里显示） / 详细描述（真正喂给模型）",
         "两份都要填：简短的给你认，详细的才送进模型。"],
        [("取消", 'ghost'), ("保存", 'primary')], y0=380, h=400)

# ---------------- 页面清单 ----------------
PAGES = [
    ("01_主页_空槽位", lambda d, im, P, k: p_home(d, im, P, k, 'empty')),
    ("02_主页_有片段三态", lambda d, im, P, k: p_home(d, im, P, k, 'done')),
    ("03_主页_片段多选", lambda d, im, P, k: p_home(d, im, P, k, 'multi')),
    ("04_主页_片段长按菜单", p_home_menu),
    ("05_主页_输入朗读_已粘贴正文", lambda d, im, P, k: p_home(d, im, P, k, 'typed')),
    ("06_主页_悬浮球拖动贴边", lambda d, im, P, k: p_overlay(d, im, P, k, True)),
    ("07_书架_空态", lambda d, im, P, k: p_library_draw(d, im, P, k, 'empty')),
    ("08_书架_有书进度", lambda d, im, P, k: p_library_draw(d, im, P, k, 'full')),
    ("09_书架_长按菜单", lambda d, im, P, k: p_library_dialog(d, im, P, k, 'menu')),
    ("10_书架_删除确认", lambda d, im, P, k: p_library_dialog(d, im, P, k, 'delete')),
    ("11_抽屉_展开", p_drawer),
    ("12_恢复弹窗_上次没做完", p_resume),
    ("13_目录_未勾选三态", lambda d, im, P, k: p_chapters(d, im, P, k, 'idle')),
    ("14_目录_全选态", lambda d, im, P, k: p_chapters(d, im, P, k, 'all')),
    ("15_目录_长按章名菜单", lambda d, im, P, k: p_chapter_menu(d, im, P, k, 'long')),
    ("16_目录_重新分章弹窗", lambda d, im, P, k: p_chapter_menu(d, im, P, k, 'resplit')),
    ("17_目录_拼接转码蒙层", p_mask),
    ("18_目录_制作中小块格子", p_blocks),
    ("19_目录_小块多选", lambda d, im, P, k: p_blocks(d, im, P, k, True)),
    ("20_目录_阅读页", p_reading),
    ("21_制作设置页", p_produce),
    ("22_任务_合成中", lambda d, im, P, k: p_tasks(d, im, P, k, 'doing')),
    ("23_任务_合成中空态", lambda d, im, P, k: p_tasks(d, im, P, k, 'doing_empty')),
    ("24_任务_队列中", lambda d, im, P, k: p_tasks(d, im, P, k, 'queue')),
    ("25_任务_已完成", lambda d, im, P, k: p_tasks(d, im, P, k, 'done')),
    ("26_任务_已完成空态", lambda d, im, P, k: p_tasks(d, im, P, k, 'done_empty')),
    ("27_任务_删除确认", p_task_del),
    ("28_播放器_收起条", lambda d, im, P, k: p_player(d, im, P, k, 'mini')),
    ("29_播放器_上滑展开", lambda d, im, P, k: p_player(d, im, P, k, 'full')),
    ("30_播放器_列表展开", lambda d, im, P, k: p_player(d, im, P, k, 'list')),
    ("31_播放器_下滑半收起", lambda d, im, P, k: p_player(d, im, P, k, 'half')),
    ("32_悬浮窗_两球不透明", p_overlay),
    ("33_模型_缺4份", lambda d, im, P, k: p_models(d, im, P, k, 'missing')),
    ("34_模型_全齐详情展开", lambda d, im, P, k: p_models(d, im, P, k, 'ready')),
    ("35_模型_卸载确认", p_model_uninstall),
    ("36_调音台_描述音色", lambda d, im, P, k: p_mixer(d, im, P, k, 1)),
    ("37_调音台_九音色平铺", lambda d, im, P, k: p_mixer(d, im, P, k, 2)),
    ("38_调音台_参考音频", lambda d, im, P, k: p_mixer(d, im, P, k, 3)),
    ("39_调音台_采样参数9项", p_mixer_params),
    ("40_调音台_选择预设弹窗", lambda d, im, P, k: p_mixer_dialog(d, im, P, k, 'select')),
    ("41_调音台_添加预设弹窗", lambda d, im, P, k: p_mixer_dialog(d, im, P, k, 'add')),
    ("42_调音台_裁剪音频弹窗", lambda d, im, P, k: p_mixer_dialog(d, im, P, k, 'crop')),
    ("43_调音台_未保存离开弹窗", lambda d, im, P, k: p_mixer_dialog(d, im, P, k, 'leave')),
    ("44_设置首页_九入口", p_settings),
    ("45_设置_外观设置", lambda d, im, P, k: p_settings_sub(d, im, P, k, 'appearance')),
    ("46_设置_运行与通知", lambda d, im, P, k: p_settings_sub(d, im, P, k, 'runtime')),
    ("47_设置_制作设置", lambda d, im, P, k: p_settings_sub(d, im, P, k, 'produce')),
    ("48_设置_权限管理未开", lambda d, im, P, k: p_settings_sub(d, im, P, k, 'perms')),
    ("49_设置_音频文件", lambda d, im, P, k: p_settings_sub(d, im, P, k, 'files')),
    ("50_设置_开发者工具", lambda d, im, P, k: p_settings_sub(d, im, P, k, 'dev')),
    ("51_设置_语音网关", p_gateway),
    ("52_设置_跨网中继", p_relay),
    ("53_请求文本_跨网中继", lambda d, im, P, k: p_archive(d, im, P, k, 'relay')),
    ("54_请求文本_语音网关空态", lambda d, im, P, k: p_archive(d, im, P, k, 'gateway')),
    ("55_请求文本_多选态", lambda d, im, P, k: p_archive(d, im, P, k, 'multi')),
    ("56_请求文本_清空确认", p_archive_clear),
    ("57_请求文本_单条展开", lambda d, im, P, k: p_archive(d, im, P, k, 'expanded')),
    ("58_描述预设_选择弹窗", lambda d, im, P, k: p_desc(d, im, P, k, 'select')),
    ("59_描述预设_添加弹窗", lambda d, im, P, k: p_desc(d, im, P, k, 'add')),
]

def main():
    for sub in ('light', 'dark'):
        (OUT / sub).mkdir(parents=True, exist_ok=True)
    index = ["# 听本 · 全页面图集索引", "",
             "> 按代码文案绘制，非真机截屏；每个页面状态都有**浅色 / 深色**两张，命名一一对应。",
             "> 配色取 Theme.kt 的 LightPal / DarkPal；字号取 R370 全站层级令牌。", "",
             "| # | 页面 / 状态 | 浅色 | 深色 |", "|---|---|---|---|"]
    for name, fn in PAGES:
        row = []
        for sub, P, dark in (('light', LIGHT, False), ('dark', DARK, True)):
            im = Image.new('RGB', (W, H), C(P, 'bg'))
            d = ImageDraw.Draw(im)
            try:
                res = fn(d, im, P, dark)
                if isinstance(res, Image.Image):
                    im = res
            except Exception as e:
                print('绘制异常', name, sub, type(e).__name__, e)
                raise
            device_shell(ImageDraw.Draw(im), P, dark)
            im.save(OUT / sub / (name + '.png'))
            row.append(sub + '/' + name + '.png')
        index.append("| %s | %s | %s | %s |" %
                     (name[:2], name[3:].replace('_', ' · '), row[0], row[1]))
        print('OK', name)
    index += ["", "共 **%d** 个页面 / 状态 × 2 套 = **%d** 张。" % (len(PAGES), len(PAGES)*2),
              "",
              "## 设备层（R396 补）",
              "",
              "118 张全部补：状态栏图标随浅深切换、摄像头**挖孔**、透明**小白条**浮在内容之上",
              "（金标联盟 ITGSA 形态，不让出灰底）；另有 `设备层标注_浅色.png` / `_深色.png`",
              "标出 top/right/bottom/left 四向 INSET、内容起始线、44×44 最小点击框、三键栏对照。",
              "设计说明一律写在索引，不画进界面。"]
    (OUT / "00_索引.md").write_text('\n'.join(index) + '\n', encoding='utf-8')
    # R396 · 设备层标注图（浅深各一张）：四向 INSET / 内容起始线 / 最小点击框
    for sub, P, dark in (('light', LIGHT, False), ('dark', DARK, True)):
        im = Image.new('RGB', (W, H), C(P, 'bg'))
        d = ImageDraw.Draw(im)
        frame(im, d, P, "设备层标注 · " + ("深色" if dark else "浅色"), False)
        tint = mix(C(P, 'bg'), C(P, 'primary'), 0.13)
        # 顶部 inset
        d.rectangle((0, 76, W, 150), fill=tint)
        txt(d, (40, 100), "顶部 INSET · 状态栏 / 摄像头挖孔", CAP, P, 'primary', True)
        # 底部 inset
        d.rectangle((0, H-96, W, H), fill=tint)
        txt(d, (40, H-84), "底部 INSET · 小白条 24dp / 三键栏", CAP, P, 'primary', True)
        # 左右 inset
        d.rectangle((0, 150, 24, H-96), fill=tint)
        d.rectangle((W-24, 150, W, H-96), fill=tint)
        # 内容起始线
        hline(d, P, 40, W-40, 210)
        txt(d, (40, 220), "内容起始线（safeDrawing）", CAP, P, 'sub')
        # 最小点击框 44×44（2x = 88px）
        rr(d, (60, 300, 148, 388), 12, C(P, 'btnSecondary'),
           C(P, 'btnTertiaryLine'), 3)
        txt(d, (60, 400), "44×44 最小点击框", MICRO, P, 'primary')
        txt(d, (60, 430), "对比 ≥4.5:1 · 字体 200% 不破版", MICRO, P, 'sub')
        # 四向标注
        for i, lab in enumerate(["top", "right", "bottom", "left"]):
            txt(d, (40, 500 + i*40), "INSET " + lab, BODY, P, 'text', True)
        # 三键栏对照
        txt(d, (40, 700), "三键栏（对照）", BODY, P, 'text', True)
        for x, draw in ((90, 'tri'), (230, 'circle'), (370, 'square')):
            if draw == 'tri': d.polygon([(x,780),(x-22,810),(x+22,810)], fill=C(P,'primary'))
            elif draw == 'circle': d.ellipse((x-20,770,x+20,810), outline=C(P,'primary'), width=5)
            else: d.rectangle((x-20,770,x+20,810), outline=C(P,'primary'), width=5)
        para(d, (40, 860), "背景延伸到屏幕边缘；内容避让系统栏、挖孔与键盘；"
             "控件落在安全点击边界内。底部条吃 navigationBarsPadding + imePadding，"
             "不再按固定 y 画到 H-24 压小白条。", BODY, P, 'sub', maxw=W-80)
        device_shell(d, P, dark)
        im.save(OUT / ("设备层标注_%s.png" % ("深色" if dark else "浅色")))
    print('完成', len(PAGES)*2, '张 + 2 张设备层标注 ->', OUT)

if __name__ == '__main__':
    main()
