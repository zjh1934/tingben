#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R397 · 带手机外框的页面预览（用户点名要「带外框的那种」）。

不是把 118 张再画一遍，而是给 16 个代表性状态各套一层真实感的机身：
金属中框 / 大 R 角 / 侧键 / 听筒 / 屏幕内圆角裁剪 / 透明小白条（沿用 R396
已画在屏幕里的挖孔与手势条）/ 落影 / 机身外页面名。浅深两套 + 各一张总览。
屏幕内容直接取 R396 重画后的 720×1440 PNG，不重画内容，避免两套口径。
"""
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import pathlib

SRC = pathlib.Path('/data/workspace/听本全页面图集')
OUT = pathlib.Path('/data/workspace/听本_带机身边框_预览')
(OUT / 'light').mkdir(parents=True, exist_ok=True)
(OUT / 'dark').mkdir(parents=True, exist_ok=True)

W, H = 900, 1700          # 整张画布
SX, SY, SW, SH = 90, 110, 720, 1440   # 屏幕内容区（与原 PNG 同尺寸）
BX, BY, BW, BH = 62, 82, 776, 1496    # 机身外框

STATES = [
 '01_主页_空槽位', '02_主页_有片段三态', '05_主页_输入朗读_已粘贴正文',
 '11_抽屉_展开', '13_目录_未勾选三态', '17_目录_拼接转码蒙层',
 '22_任务_合成中', '29_播放器_上滑展开', '33_模型_缺4份',
 '39_调音台_采样参数9项', '44_设置首页_九入口',
 '53_请求文本_跨网中继', '55_请求文本_多选态',
 '56_请求文本_清空确认',
 # R398 · 本轮新修的 5 个状态，套上机身再看一眼
 '09_书架_长按菜单', '15_目录_长按章名菜单', '34_模型_全齐详情展开',
 '51_设置_语音网关', '52_设置_跨网中继',
]

FONT = '/usr/share/fonts/truetype/wqy/wqy-microhei.ttc'
def f(sz):
    try: return ImageFont.truetype(FONT, sz)
    except Exception: return ImageFont.load_default()

def rgb(h): return tuple(int(h[i:i+2],16) for i in (1,3,5))
def mix(a,b,t): return tuple(int(a[i]*(1-t)+b[i]*t) for i in range(3))

def rounded_mask(size, r):
    m = Image.new('L', size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0,0,size[0]-1,size[1]-1), r, fill=255)
    return m

def device_frame(screen_path, name, dark, out_path):
    if dark:
        canvas = rgb('#0A0C11'); frame_out = rgb('#3A4150'); frame_mid = rgb('#20252F')
        rail = rgb('#5A6272'); bezel = rgb('#080A0E'); label = rgb('#E8ECF4')
    else:
        canvas = rgb('#E9ECF2'); frame_out = rgb('#B9C0CD'); frame_mid = rgb('#E3E7EF')
        rail = rgb('#8B94A6'); bezel = rgb('#101319'); label = rgb('#232A38')
    im = Image.new('RGB', (W,H), canvas)
    d = ImageDraw.Draw(im)

    # 落影（机身下方一团软影）
    sh = Image.new('RGBA',(W,H),(0,0,0,0))
    sd = ImageDraw.Draw(sh)
    sd.rounded_rectangle((BX+18,BY+34,BX+BW+18,BY+BH+34), 74, fill=(0,0,0,105))
    sh = sh.filter(ImageFilter.GaussianBlur(34))
    im.paste(sh.convert('RGB'), (0,0), sh.getchannel('A').point(lambda v:int(v*0.55)))
    d = ImageDraw.Draw(im)

    # 侧键（先画，压在机身后面）
    for (x,y0,y1) in [(BX-9, 430, 560), (BX-9, 590, 690), (BX+BW-9, 470, 620)]:
        d.rounded_rectangle((x,y0,x+10,y1), 5, fill=rail, outline=frame_out, width=2)

    # 机身：外金属框 → 中层 → 黑边 → 屏幕
    d.rounded_rectangle((BX,BY,BX+BW,BY+BH), 74, fill=frame_out,
                        outline=mix(frame_out, bezel, .45), width=3)
    d.rounded_rectangle((BX+9,BY+9,BX+BW-9,BY+BH-9), 67, fill=frame_mid)
    d.rounded_rectangle((BX+16,BY+16,BX+BW-16,BY+BH-16), 61, fill=bezel)
    # 左上高光
    d.arc((BX+22,BY+22,BX+180,BY+180), 180, 268,
          fill=mix(frame_mid, rgb('#FFFFFF'), .7) if not dark else mix(frame_mid, rgb('#8B95AA'), .55),
          width=4)

    # 屏幕：圆角裁剪贴原图
    screen = Image.open(screen_path).convert('RGB')
    mask = rounded_mask((SW,SH), 54)
    im.paste(screen, (SX,SY), mask)
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((SX,SY,SX+SW,SY+SH), 54, outline=mix(bezel, frame_mid, .35), width=3)
    # 听筒细缝（在挖孔右侧，不与屏幕内挖孔重叠）
    d.rounded_rectangle((SX+430, SY+22, SX+490, SY+27), 3,
                        fill=mix(bezel, rgb('#FFFFFF'), .22) if dark else mix(bezel, rgb('#FFFFFF'), .12))
    # 机身底部页面名（机身外，不属于界面）
    d.text((W//2, BY+BH+30), name, font=f(26), fill=label, anchor='ma')
    d.text((W//2, BY+BH+68), '听本 · 带机身边框预览 | 深色' if dark else '听本 · 带机身边框预览 | 浅色',
           font=f(18), fill=mix(label, canvas, .42), anchor='ma')
    im.save(out_path)

def overview(sub, dark):
    import math
    cells = 4; tw, th = 380, 760
    rows = math.ceil(len(STATES)/cells)
    sheet = Image.new('RGB',(cells*tw, rows*th+80), rgb('#0A0C11') if dark else rgb('#E9ECF2'))
    dd = ImageDraw.Draw(sheet)
    for i,n in enumerate(STATES):
        p = OUT/sub/(n+'.png')
        t = Image.open(p).convert('RGB').resize((360,680))
        x=(i%cells)*tw+10; y=(i//cells)*th+10
        sheet.paste(t,(x,y))
        dd.text((x+180,y+692), n[:16], font=f(17),
                fill=rgb('#E8ECF4') if dark else rgb('#232A38'), anchor='ma')
    dd.text((cells*tw//2, rows*th+40),
            ('听本 · %d 个代表性状态 · 带机身边框总览（深色）'%len(STATES)) if dark
            else ('听本 · %d 个代表性状态 · 带机身边框总览（浅色）'%len(STATES)),
            font=f(24), fill=rgb('#E8ECF4') if dark else rgb('#232A38'), anchor='ma')
    sheet.save(OUT/('总览_%s.png'%('深色' if dark else '浅色')))

def main():
    n=0
    for name in STATES:
        for sub,dark in (('light',False),('dark',True)):
            sp = SRC/sub/(name+'.png')
            assert sp.exists(), sp
            device_frame(sp, name.split('_',1)[1], dark, OUT/sub/(name+'.png'))
            n+=1
    overview('light',False); overview('dark',True)
    print('完成 %d 张带机身边框预览 + 2 张总览 -> %s'%(n,OUT))
    missing = [x for x in STATES if not ((OUT/'light'/(x+'.png')).exists() and (OUT/'dark'/(x+'.png')).exists())]
    print('浅深一一对应:', 'OK' if not missing else missing)

if __name__ == '__main__': main()
