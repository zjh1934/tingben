# -*- coding: utf-8 -*-
"""R84 · 已退役（R135）。

原验证对象：SoundProbeBlock / DiagnosticsBlock。
批次 6 物理删除了从 App() 不可达的死代码后，本脚本不再逐条对旧字符串
（那只会制造假红、拖慢每轮自检）。改由统一守门负责：
旧页符号零回潮 + 现行能力守恒 + 导航 8 值。
"""
import pathlib, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from retired_pages import run

if __name__ == "__main__":
    sys.exit(run("R84"))
