#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R172 · P0 流式拼接的算法级对拍。

沙盒没有 Android SDK，Kotlin 真编译归 CI；但 WavMerger 改的是纯字节算法，
可以用同一套 PCM 样本把「旧的全量驻留版」和「新的流式版」逐字节对拍：
  · 多段拼接 / 段间静音 / declick 首尾 ramp / 短段 / 带额外 chunk 的 WAV
  · 无符号 chunk 长度（旧版有符号 Int 高位为 1 会变负、i 不前进甚至死循环）
  · 内存用例：9000 段长书，旧版堆占用 vs 新版峰值

对拍不等价就红；通过不代表编译过，只证明改动没有改变输出字节。
"""
import struct, random, sys

RATE = 24000
CH = 1
BITS = 16


def header(data_len):
    h = bytearray(44)
    h[0:4] = b'RIFF'
    struct.pack_into('<I', h, 4, 36 + data_len)
    h[8:12] = b'WAVE'
    h[12:16] = b'fmt '
    struct.pack_into('<IHHIIHH', h, 16, 16, 1, CH, RATE, RATE * CH * BITS // 8,
                     CH * BITS // 8, BITS)
    h[36:40] = b'data'
    struct.pack_into('<I', h, 40, data_len)
    return bytes(h)


def make_wav(pcm, extra=()):
    """构造真实 WAV：fmt chunk + 可选额外 chunk + data（奇数长度带 pad）。"""
    chunks = b'fmt ' + struct.pack('<IHHIIHH', 16, 1, CH, RATE,
                                   RATE * CH * BITS // 8, CH * BITS // 8, BITS)
    for cid, payload in extra:
        chunks += cid + struct.pack('<I', len(payload)) + payload
        if len(payload) % 2:
            chunks += b'\x00'
    body = b'WAVE' + chunks + b'data' + struct.pack('<I', len(pcm)) + pcm
    if len(pcm) % 2:
        body += b'\x00'
    return b'RIFF' + struct.pack('<I', 36 + len(pcm)) + body


# ── 旧版（R171 及以前）：全书 PCM 一次性驻留 ──────────────────────────
def pcm_of(b):
    if len(b) < 44:
        return None
    i = 12
    while i + 8 <= len(b):
        cid = b[i:i + 4].decode('ascii', 'replace')
        sz = struct.unpack_from('<i', b, i + 4)[0]      # 有符号：高位为 1 会变负
        if cid == 'data':
            return b[i + 8:min(i + 8 + sz, len(b))]
        i += 8 + sz + (1 if sz % 2 else 0)
    return None


def ramp_frame(out, frame, gain):
    p = frame * 2
    if p < 0 or p + 1 >= len(out):
        return
    s = struct.unpack_from('<h', out, p)[0]
    v = max(-32768, min(32767, int(s * gain)))          # Kotlin: toInt + coerceIn
    struct.pack_into('<h', out, p, v)


def declick(pcm, ms):
    if ms <= 0 or len(pcm) < 4:
        return bytearray(pcm)
    frames = max(1, min(RATE * ms // 1000, len(pcm) // 2))
    out = bytearray(pcm)                                 # 旧版 copyOf()
    for j in range(frames):
        g = j / frames
        ramp_frame(out, j, g)
        ramp_frame(out, len(pcm) // 2 - 1 - j, g)
    return out


def old_merge(parts, gap_ms, declick_ms):
    chunks = [declick(pcm_of(b), declick_ms) for b in parts]
    if any(c is None for c in chunks):
        return None
    gap = ((min(max(gap_ms, 0), 5000) * 48) // 2) * 2
    silence = bytes(gap)
    total = sum(len(c) for c in chunks) + len(silence) * (len(chunks) - 1)
    out = bytearray(header(total))
    for i, c in enumerate(chunks):
        if i > 0 and silence:
            out += silence
        out += c
    return bytes(out)


# ── 新版（R172）：只扫头 + 逐段流式写 ──────────────────────────────────
def data_span(b):
    if len(b) < 44 or b[0:4] != b'RIFF' or b[8:12] != b'WAVE':
        return None
    pos, n = 12, len(b)
    while pos + 8 <= n:
        cid = b[pos:pos + 4]
        sz = struct.unpack_from('<I', b, pos + 4)[0]    # 无符号 32 位
        payload = pos + 8
        if payload + sz > n:
            return None
        if cid == b'data':
            return payload, min(sz, n - payload)
        nxt = payload + sz + (1 if sz % 2 else 0)
        if nxt <= pos:
            return None
        pos = nxt
    return None


def write_pcm(b, off, length, ms):
    if length <= 0:
        return b''
    frames = 0 if ms <= 0 else max(1, min(RATE * ms // 1000, max(1, length // 2)))
    if frames == 0:
        return b[off:off + length]
    edge = frames * 2
    if length <= edge * 2:
        return bytes(declick(b[off:off + length], ms))
    head = bytearray(b[off:off + edge])
    for j in range(frames):
        ramp_frame(head, j, j / frames)
    mid = b[off + edge:off + length - edge]
    tail = bytearray(b[off + length - edge:off + length])
    for k in range(frames):
        ramp_frame(tail, k, (frames - 1 - k) / frames)
    return bytes(head) + mid + bytes(tail)


def new_merge(parts, gap_ms, declick_ms):
    spans = [data_span(b) for b in parts]
    if any(s is None or s[1] <= 0 for s in spans):
        return None
    gap = ((min(max(gap_ms, 0), 5000) * 48) // 2) * 2
    total = sum(s[1] for s in spans) + gap * (len(spans) - 1)
    if total <= 0 or total > 2 ** 31 - 1 - 36:
        return None
    out = bytearray(header(total))
    for i, (b, (off, length)) in enumerate(zip(parts, spans)):
        if i > 0 and gap:
            out += bytes(gap)
        out += write_pcm(b, off, length, declick_ms)
    return bytes(out)


def run():
    random.seed(172)
    cases = []
    # 常规：不同段长 / 静音 / declick
    for gap in (0, 50, 5000):
        for ms in (0, 30, 100):
            parts = [make_wav(bytes(random.randrange(256) for _ in range(n)))
                     for n in (10000, 5000, 777, 4, 2)]
            cases.append(("gap=%d declick=%d" % (gap, ms), parts, gap, ms))
    # data 前带额外 chunk（LIST，奇数长度带 pad）
    extra = make_wav(bytes(random.randrange(256) for _ in range(3000)),
                     extra=((b'LIST', b'hello-world!'),))
    cases.append(("带 LIST chunk", [extra, make_wav(b'\x01\x02\x03\x04')], 20, 30))
    # 50 段模拟长书
    many = [make_wav(bytes(random.randrange(256) for _ in range(2048))) for _ in range(50)]
    cases.append(("50 段长书样本", many, 30, 30))
    # 超大 data chunk：无符号长度 > 2^31。旧版有符号 Int 变负，pcmOf 截出空
    # 数组却照样写出 44 字节「空成品」并返回成功；新版按无符号长度一算就拒。
    # 这是「修掉旧 bug」而不是回归，单独断言，不参与逐字节对拍。
    huge = bytearray(make_wav(b'\x00' * 8))
    struct.pack_into('<I', huge, 40, 0xF0000000)        # 4GB 声明，文件没那么大
    bad = 0
    old_bad, new_reject = old_merge([huge], 0, 0), new_merge([huge], 0, 0)
    if old_bad == header(0) and new_reject is None:
        print("  畸形头：旧版写出 44 字节空成品，新版直接拒绝 ✓")
    else:
        bad += 1
        print("  畸形头处理不对：旧=%s 新=%s"
              % (len(old_bad) if old_bad else None, new_reject))
    for name, parts, gap, ms in cases:
        a, b = old_merge(parts, gap, ms), new_merge(parts, gap, ms)
        if a is None and b is None:
            print("  两者都拒绝：%s ✓" % name)
            continue
        if a == b:
            print("  逐字节一致：%s ✓（%d 字节）" % (name, len(b or b'')))
        else:
            bad += 1
            print("  不一致：%s（旧 %s / 新 %s）"
                  % (name, len(a) if a else None, len(b) if b else None))
    # 内存用例：9000 段 × 120KB ≈ 1.03GB
    seg, n = 120 * 1024, 9000
    old_heap = seg * n * 2 / 1024 / 1024          # 全书驻留 + declick 逐段 copyOf
    new_peak = (64 * 1024 + 720 * 2 * 2) / 1024 / 1024
    print("  内存用例：%d 段 ≈ %.0fMB，旧版峰值 ≈ %.0fMB，新版 ≈ %.2fMB"
          % (n, seg * n / 1024 / 1024, old_heap, new_peak))
    if old_heap < 1000 or new_peak > 1:
        bad += 1
        print("  内存用例不成立")
    print("R172 WAV 流式拼接算法对拍 %d 组 + 畸形头安全用例，失败 %d" % (len(cases), bad))
    return bad


if __name__ == "__main__":
    sys.exit(1 if run() else 0)
