#!/usr/bin/env python3
"""听本 · CI 自检：必备文件 / 权重泄漏 / 无网络权限 / 包名。只依赖标准库。"""
from pathlib import Path
import os, sys
ROOT = Path(__file__).resolve().parent.parent
errs=[]
REQUIRED = ["README.md",".gitignore","settings.gradle.kts","build.gradle.kts",
 "gradle/libs.versions.toml","gradle.properties","app/build.gradle.kts",
 "app/src/main/AndroidManifest.xml","app/src/main/assets/prompt_zh.txt",
 "app/src/main/cpp/CMakeLists.txt","app/src/main/cpp/tingben_native.cpp",
 "app/src/main/java/com/tingben/app/MainActivity.kt",
 "scripts/build_native.sh","scripts/check_structure.py",".github/workflows/ci.yml","keystore.jks",
 "contracts/model-manifest.json","contracts/pipeline_spec.md"]
for f in REQUIRED:
    if not (ROOT/f).exists(): errs.append("缺少必备文件: "+f)
SKIP={".git",".gradle","build",".idea","__pycache__","reference"}
for root,dirs,files in os.walk(ROOT):
    dirs[:]=[d for d in dirs if d not in SKIP]
    for f in files:
        if f.lower().endswith((".gguf",".safetensors",".pth",".onnx")):
            errs.append("模型权重泄漏进源码树: "+str(Path(root)/f))
m=(ROOT/"app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
if "android.permission.INTERNET" in m: errs.append("Manifest 含 INTERNET 权限")
if 'applicationId = "com.tingben.app"' not in (ROOT/"app/build.gradle.kts").read_text(encoding="utf-8"):
    errs.append("包名不是 com.tingben.app")
if errs:
    print("\n".join("ERROR "+e for e in errs)); sys.exit(1)
print("自检通过: %d 项必备文件 / 权重泄漏 0 / 无 INTERNET / 包名正确" % len(REQUIRED))
