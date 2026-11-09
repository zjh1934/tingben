# -*- coding: utf-8 -*-
"""R137 · 批次 A「发布与安全」守门。

三项落地：
1) 签名材料移出仓库：keystore 路径 / store 密码 / key 密码 / alias 全由
   CI Secret 注入，build.gradle.kts 零明文；.gitignore 拦 jks；
   APK 闸门用 apksigner 比对证书 SHA-256，防默认 debug 签名包混出去。
2) 原生引擎缺件不再只 warning 降级发包：引擎守门与 APK 闸门都直接 fail。
3) 版本严格递增：check_version_bump.py 在发版前比对线上 latest Release。

注意（如实边界）：签名保持不变，只做「隔离」不做「轮换」。
换 keystore 会让所有旧包无法覆盖安装、私有目录里的模型与音频全丢，
是不可逆操作，需单独确认；git 历史里的旧 keystore 也需另行清理。

用法：python3 scripts/r137_verify.py [--selftest]
"""
import pathlib
import subprocess
import sys
import os

ROOT = pathlib.Path(__file__).resolve().parents[1]
CI = ROOT / ".github/workflows/ci.yml"
GRADLE = ROOT / "app/build.gradle.kts"
IGNORE = ROOT / ".gitignore"
BUMP = ROOT / "scripts/check_version_bump.py"

CI_ANCHORS = [
    "R137 版本必须严格递增",
    "python3 scripts/check_version_bump.py",
    "R137 注入签名密钥",
    "TINGBEN_KEYSTORE_B64",
    "TINGBEN_STORE_PASSWORD=$KS_STORE",
    "TINGBEN_KEY_PASSWORD=$KS_KEY",
    "TINGBEN_KEY_ALIAS=$KS_ALIAS",
    "::error::签名 Secret 没配齐",
    "引擎守门（缺件直接失败，不降级发包）",
    "::error::原生引擎缓存/编译未通过",
    "::error::APK 没有原生库",
    "EXPECT_CERT: ${{ secrets.TINGBEN_SIGN_SHA256 }}",
    "找不到 apksigner",
    "签名证书不匹配",
    "python3 scripts/r137_verify.py --selftest",
]

GRADLE_ANCHORS = [
    "System.getenv(\"TINGBEN_KEYSTORE\")",
    "System.getenv(\"TINGBEN_STORE_PASSWORD\")",
    "System.getenv(\"TINGBEN_KEY_PASSWORD\")",
    "System.getenv(\"TINGBEN_KEY_ALIAS\")",
    "val signReady",
    "if (signReady) create(\"tingben\")",
    "ifBlank { error(\"version.txt 为空",
]
SECRET_LITERALS = ["motingdebug", "keyAlias = \"moting\"", "storePassword = \"",
                   "ifBlank { \"1.1."]

BUMP_ANCHORS = [
    "def parse(v)", "def online_prev()", "TINGBEN_PREV_VERSION",
    "if new <= old", "releases/latest",
]


def tracked():
    return subprocess.check_output(["git", "ls-files"], cwd=ROOT, text=True).splitlines()


def check(sources=None):
    # R191 · 允许 selftest 传内存变体，不再原地覆写源码。
    # 502 砍在 finally 之前会留下 signReady=true 这类危险残留。
    e = []
    src = sources or {}
    ci = src.get("ci", CI.read_text(encoding="utf-8"))
    g = src.get("gradle", GRADLE.read_text(encoding="utf-8"))
    ig = src.get("ignore", IGNORE.read_text(encoding="utf-8"))
    b = src.get("bump", BUMP.read_text(encoding="utf-8") if BUMP.exists() else "")

    for a in CI_ANCHORS:
        if a not in ci:
            e.append("CI 守门缺失: " + a[:64])
    for a in GRADLE_ANCHORS:
        if a not in g:
            e.append("Gradle 签名隔离缺失: " + a[:64])
    for a in BUMP_ANCHORS:
        if a not in b:
            e.append("版本递增脚本缺失: " + a[:64])
    # 明文密码零残留
    for secret in SECRET_LITERALS:
        if secret in g:
            e.append("build.gradle.kts 仍有明文/硬编码材料: " + secret)
    if "*.jks" not in ig:
        e.append(".gitignore 没拦 *.jks")
    # keystore 不能再被版本管理
    if any(p.endswith("keystore.jks") or p.endswith(".jks") for p in tracked()):
        e.append("keystore 仍在 git 索引里")
    # 旧的降级发包写法不能回来
    if "APK 降级为纯 Kotlin 包" in ci or "::warning::APK 没有原生库" in ci:
        e.append("降级发包的老写法回潮")
    return e


def bump_cases():
    """逻辑用例：用 TINGBEN_PREV_VERSION 离线跑，不依赖网络。"""
    env = dict(os.environ)
    cur = (ROOT / "version.txt").read_text(encoding="utf-8").strip()
    parts = [int(x) for x in cur.split(".")]
    same = cur
    older = "%d.%d.%d" % (parts[0], parts[1], parts[2] - 1)
    newer = "%d.%d.%d" % (parts[0], parts[1], parts[2] + 1)
    # PREV 是「线上的上一版」：相等/线上更新要拦，本次比线上大才放行
    cases = [(same, 1, "版本相等必须拦"), (older, 0, "本次递增必须放行"),
             (newer, 1, "线上更新（本次回退）必须拦"),
             ("1.1", 1, "线上 tag 格式不合法必须拦")]
    bad = 0
    for prev, want, name in cases:
        e2 = dict(env); e2["TINGBEN_PREV_VERSION"] = prev
        e2["GITHUB_REPOSITORY"] = "zjh1934/tingben"
        r = subprocess.run([sys.executable, str(BUMP)], cwd=ROOT, env=e2,
                           capture_output=True, text=True, timeout=60)
        got = r.returncode
        if got != want:
            bad += 1
            print("  版本用例不符:", name, "期望", want, "实得", got,
                  (r.stdout + r.stderr)[-200:])
    print("版本递增逻辑 %d/%d 通过" % (len(cases) - bad, len(cases)))
    return bad


def selftest():
    base = {
        "ci": CI.read_text(encoding="utf-8"),
        "gradle": GRADLE.read_text(encoding="utf-8"),
        "ignore": IGNORE.read_text(encoding="utf-8"),
        "bump": BUMP.read_text(encoding="utf-8") if BUMP.exists() else "",
    }
    assert not check(base), "基线自己就红了，先修基线"
    cases = [
        ("版本守门被摘", "ci",
         lambda s: s.replace("python3 scripts/check_version_bump.py", "")),
        ("注入 Secret 被摘", "ci", lambda s: s.replace("TINGBEN_KEYSTORE_B64", "")),
        ("引擎守门回退成 warning", "ci",
         lambda s: s.replace("::error::原生引擎缓存/编译未通过", "::warning::引擎缺件")),
        ("APK 缺 so 回退成 warning", "ci",
         lambda s: s.replace("::error::APK 没有原生库", "::warning::APK 没有原生库")),
        ("签名证书守门被摘", "ci",
         lambda s: s.replace("签名证书不匹配", "证书校验略过")),
        ("Gradle 明文密码回来", "gradle",
         lambda s: s.replace("val signReady", 'val signReady = true // storePassword = "motingdebug"')),
        (".gitignore 去掉 jks", "ignore", lambda s: s.replace("*.jks", "")),
        ("CI 漏挂 r137", "ci",
         lambda s: s.replace("python3 scripts/r137_verify.py --selftest", "")),
        ("相等版本被放行", "bump", lambda s: s.replace("if new <= old", "if new < old")),
    ]
    bad = 0
    for name, key, mutate in cases:
        v = dict(base)
        v[key] = mutate(base[key])
        assert v[key] != base[key], "注入没生效: " + name
        if not check(v):
            bad += 1
            print("  反向注入漏放:", name)
    total = len(cases)
    print("反向注入 %d/%d 全拦下（纯内存，源文件零写入）" % (total - bad, total))
    bad += bump_cases()
    return bad


def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    e = check()
    if e:
        print("R137 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R137 批次A守门：密钥零明文 / 引擎与签名硬闸门 / 版本递增 / keystore 不在索引：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
