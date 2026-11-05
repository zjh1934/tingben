#!/usr/bin/env python3
"""R42 收尾：把取包后的真实数字回填进项目文件，查占位符残留，打包文档。

用法: python3 scripts/r42_finalize.py <run> <steps> <apk_bytes> <blob_sha> <sha256>
"""
import pathlib, sys, zipfile

if len(sys.argv) != 6:
    sys.exit("用法: r42_finalize.py <run> <steps> <apk_bytes> <blob_sha> <sha256>")
RUN, STEPS, BYTES, BLOB, SHA = sys.argv[1:6]
ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT.parent / "听本_R42_项目文件.zip"

FILES = ["STATUS.md", "README.md", "ITERATION_LOG.md",
         "docs/听本_项目导图.html", "docs/R42_P2_落地.md",
         "docs/R42_P2_文案响应式视觉_联动表.md"]

vals = {
    "{{RUN}}": RUN,
    "{{STEPS}}": STEPS,
    "{{APK_BYTES}}": format(int(BYTES), ","),
    "{{BLOB}}": BLOB,
    "{{SHA256}}": SHA,
}
for rel in FILES:
    p = ROOT / rel
    s = p.read_text(encoding="utf-8")
    s = s.replace("{{BLOB}}…", "{{BLOB}}")
    for k, v in vals.items():
        s = s.replace(k, v)
    if "{{" in s:
        sys.exit("占位符残留: " + rel)
    p.write_text(s, encoding="utf-8")
    print("回填", rel)

with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
    for rel in FILES:
        z.write(ROOT / rel, rel)
print("已打包", OUT, OUT.stat().st_size, "字节")
