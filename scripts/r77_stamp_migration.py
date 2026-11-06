# -*- coding: utf-8 -*-
"""R77 · 旧 WAV 指纹迁移对拍。

Python 复刻 Kotlin `Prefs.sha`（SHA-256 前 8 字节 hex）与两版指纹算法，
验证「升级后不误删用户已做好的音频」。源码算法结构由 r77_verify.py 断言，
这里只对拍数值。运行：python3 scripts/r77_stamp_migration.py
"""
import hashlib, pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
VERSION = "4"
DEFAULT_KNOBS = "42|2048|0.9|50|1.0|1.05|0.9|50|1.0"

def sha(s: str) -> str:
    return hashlib.sha256(s.encode("utf-8")).digest()[:8].hex()

def new_stamp(desc: str, knobs=DEFAULT_KNOBS) -> str:
    return sha("||".join([VERSION, desc, sha(knobs)]))

def legacy_stamp(desc: str, cast_hash: str, knobs=DEFAULT_KNOBS) -> str:
    # castEnabled=false 时旧算法写 "off"
    return sha("||".join([VERSION, desc, cast_hash, sha(knobs)]))

def changed(old: str, cur: str, legacy: str, exists=True) -> bool:
    """复刻 Pipeline 的双指纹比对"""
    return exists and old != cur and old != legacy

CASES = []

def case(name, *, old, cur, legacy, exists=True, expect_changed, note):
    got = changed(old, cur, legacy, exists)
    ok = got == expect_changed
    CASES.append((name, ok, got, expect_changed, note))
    return ok

# ① 旧手机：cast.json 存在、castEnabled 默认开、配置未变
cast = sha('{"entries":[]}')
case("① 旧手机（角色表在，配置未变）",
     old=legacy_stamp("清晰自然的中文普通话叙述声", cast),
     cur=new_stamp("清晰自然的中文普通话叙述声"),
     legacy=legacy_stamp("清晰自然的中文普通话叙述声", cast),
     expect_changed=False, note="命中 legacy → 不作废，只改写 stamp 文件")

# ② 旧手机：castEnabled 曾被关掉，旧算法写 "off"
case("② 旧手机（角色映射关）",
     old=legacy_stamp("清晰自然的中文普通话叙述声", "off"),
     cur=new_stamp("清晰自然的中文普通话叙述声"),
     legacy=legacy_stamp("清晰自然的中文普通话叙述声", "off"),
     expect_changed=False, note="off 分支同样命中")

# ③ 旧手机 + 用户真的改了音色描述
case("③ 旧手机 + 改了音色描述",
     old=legacy_stamp("旧描述", cast),
     cur=new_stamp("新描述"),
     legacy=legacy_stamp("新描述", cast),
     expect_changed=True, note="两版都用当前描述算，旧值都不等 → 照常作废")

# ④ 旧手机 + 用户改了采样（seed）
knobs2 = "43|2048|0.9|50|1.0|1.05|0.9|50|1.0"
case("④ 旧手机 + 改了采样",
     old=legacy_stamp("清晰自然的中文普通话叙述声", cast),
     cur=new_stamp("清晰自然的中文普通话叙述声", knobs2),
     legacy=legacy_stamp("清晰自然的中文普通话叙述声", cast, knobs2),
     expect_changed=True, note="采样变化不能被兼容逻辑吃掉")

# ⑤ 新装 / 无 stamp 文件
case("⑤ 新装（无 stamp 文件）",
     old="", cur=new_stamp("清晰自然的中文普通话叙述声"), legacy="x",
     exists=False, expect_changed=False, note="首次写新值，不删任何东西")

# ⑥ 已迁移：stamp 已是新值（幂等）
case("⑥ 已迁移（幂等）",
     old=new_stamp("清晰自然的中文普通话叙述声"),
     cur=new_stamp("清晰自然的中文普通话叙述声"),
     legacy=legacy_stamp("清晰自然的中文普通话叙述声", cast),
     expect_changed=False, note="第二次进入不重复迁移、不作废")

# ⑦ cast.json 内容变了，新指纹不受影响
cast2 = sha('{"entries":[{"id":"x"}]}')
case("⑦ 角色表内容变化不影响新指纹",
     old=new_stamp("清晰自然的中文普通话叙述声"),
     cur=new_stamp("清晰自然的中文普通话叙述声"),
     legacy=legacy_stamp("清晰自然的中文普通话叙述声", cast2),
     expect_changed=False,
     note="新指纹只认描述 + 采样；legacy 用当前 cast 算不影响结论")

# ⑧ 新旧算法取值必须真的不同（否则迁移逻辑毫无意义）
diff_ok = new_stamp("d") != legacy_stamp("d", cast)
CASES.append(("⑧ 新旧指纹确实不同", diff_ok, diff_ok, True,
              "若相等说明 voiceStamp 没真的收窄"))

print("R77 指纹迁移对拍")
fail = 0
for name, ok, got, exp, note in CASES:
    print(("  PASS " if ok else "  FAIL ") + name + f" · changed={got} 期望={exp} · {note}")
    if not ok: fail += 1
print(f"合计 {len(CASES)} 组，失败 {fail}")
sys.exit(1 if fail else 0)
