#!/usr/bin/env bash
# R30 · 在 GitHub Actions 里交叉编译两个现成推理引擎，产出 arm64 .so 打进 APK。
#
# 【真机事故】R29 版在骁龙 8Gen3 上：
#   dlopen failed: cannot locate symbol "ggml_top_k_mask" referenced by "libllama.so"
# 根因：两个上游各产一套 libggml*.so，全部拷进同一个 jniLibs → **同名互相覆盖**，
#   libllama.so 依赖的新版 ggml 被 qwentts.cpp 的旧 ggml 顶掉。
#
# 【第一次尝试的教训】R30 初版想用 -DGGML_SHARED=OFF 让 ggml 静态进各自 .so，
#   CI 证明 llama.cpp 这个版本不认该选项（仍产出 libggml-base.so / -cpu.so / .so 三个 SHARED），
#   不拷 ggml 就留下 268 / 116 个 UND ggml_ 符号，守门正确降级，没发坏包。
#
# 【最终方案：只保留一份 ggml，两个引擎共用】
#   - 拷 llama.cpp 的 libggml*.so（较新，含 ggml_top_k_mask）
#   - qwentts.cpp 只拷 libqwen.so，它的 ggml 一律不拷
#   - **差集自检**：两个引擎的 UND ggml_ 符号，必须全部能在这份 ggml 里找到；
#     DT_NEEDED 里出现的 libggml* 也必须都在 jniLibs 里。
#   这才是真机 dlopen 的等价检查，比「编译成功」可信。
#
# 抄官方构建方式，不手搓推理、不改上游源码 / CMakeLists。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CPP="$ROOT/app/src/main/cpp"
JNILIBS="$ROOT/app/src/main/jniLibs/arm64-v8a"
WORK="${TINGBEN_NATIVE_WORK:-/tmp/tingben-native-build}"

ANDROID_HOME="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
NDK="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
if [ -z "$NDK" ] || [ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]; then
  echo "::error::找不到 Android NDK（ANDROID_HOME=$ANDROID_HOME）"; exit 1
fi
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
echo "NDK: $NDK"

NM="$(find "$NDK" -type f \( -name 'llvm-nm' -o -name 'aarch64-linux-android-nm' \) 2>/dev/null | head -1 || true)"
READELF="$(find "$NDK" -type f \( -name 'llvm-readelf' -o -name 'aarch64-linux-android-readelf' \) 2>/dev/null | head -1 || true)"
echo "nm=$NM  readelf=$READELF"

# 干净起点：清掉旧产物与校验标记
rm -rf "$JNILIBS"
mkdir -p "$JNILIBS" "$CPP/upstream_include" "$WORK"

COMMON_ARGS=(
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN"
  -DANDROID_ABI=arm64-v8a
  -DANDROID_PLATFORM=android-26
  -DCMAKE_BUILD_TYPE=Release
  -DGGML_NATIVE=OFF
  -DGGML_OPENMP=OFF
  -DGGML_LLAMAFILE=OFF
  -DLLAMA_OPENSSL=OFF
  -DBUILD_SHARED_LIBS=ON
  -DGGML_BUILD_TESTS=OFF
  -DGGML_BUILD_EXAMPLES=OFF
)

# ── 1. llama.cpp（这一份 ggml 是唯一保留的）───────────────────
echo "===== [1/2] llama.cpp ====="
LLAMA_SRC="$WORK/llama.cpp"
if [ ! -d "$LLAMA_SRC/.git" ]; then
  git clone --depth 1 --recurse-submodules --shallow-submodules \
    https://github.com/ggml-org/llama.cpp.git "$LLAMA_SRC"
fi
echo "llama.cpp commit: $(git -C "$LLAMA_SRC" rev-parse --short HEAD)"

cp "$LLAMA_SRC/include/llama.h" "$CPP/llama.h"
if [ -d "$LLAMA_SRC/ggml/include" ]; then
  cp -r "$LLAMA_SRC/ggml/include/." "$CPP/upstream_include/"
fi

cmake -S "$LLAMA_SRC" -B "$WORK/llama-build" "${COMMON_ARGS[@]}"
cmake --build "$WORK/llama-build" --target llama -j"$(nproc)"

LLAMA_SO="$(find "$WORK/llama-build" -name 'libllama.so' -type f | head -1 || true)"
if [ -z "$LLAMA_SO" ]; then echo "::error::没编出 libllama.so"; exit 1; fi
cp "$LLAMA_SO" "$JNILIBS/libllama.so"
# ★ 只拷 llama.cpp 这一套 ggml
find "$WORK/llama-build" -name 'libggml*.so' -type f -exec cp {} "$JNILIBS/" \;
echo "libllama.so: $(stat -c%s "$JNILIBS/libllama.so") 字节"
echo "保留的 ggml："
ls -1 "$JNILIBS"/libggml*.so

# ── 2. qwentts.cpp（只拿 libqwen.so，ggml 一律不拷）───────────
echo "===== [2/2] qwentts.cpp ====="
TTS_SRC="$WORK/qwentts.cpp"
if [ ! -d "$TTS_SRC/.git" ]; then
  git clone --depth 1 --recurse-submodules --shallow-submodules \
    https://github.com/ServeurpersoCom/qwentts.cpp.git "$TTS_SRC"
fi
echo "qwentts.cpp commit: $(git -C "$TTS_SRC" rev-parse --short HEAD)"

cp "$TTS_SRC/src/qwen.h" "$CPP/qwen.h"

cmake -S "$TTS_SRC" -B "$WORK/qwentts-build" "${COMMON_ARGS[@]}" \
  -DQWEN_SHARED=ON -DGGML_BLAS=OFF
cmake --build "$WORK/qwentts-build" --target qwen -j"$(nproc)"

QWEN_SO="$(find "$WORK/qwentts-build" -name 'libqwen.so' -type f | head -1 || true)"
if [ -z "$QWEN_SO" ]; then echo "::error::没编出 libqwen.so"; exit 1; fi
cp "$QWEN_SO" "$JNILIBS/libqwen.so"
echo "libqwen.so: $(stat -c%s "$JNILIBS/libqwen.so") 字节（共用上面的 ggml，不拷自己的）"

# ── 3. ★ 真机等价自检 ────────────────────────────────────────
echo "===== [3/3] 符号自检（真机 dlopen 等价）====="
# ★ 没做检查就不许盖章：run 26 因为 llvm-readelf 找不到而整段跳过、
#   却 touch 了 .verified，是「假绿」。这里必须严格。
if [ -z "$NM" ]; then
  echo "::error::找不到 llvm-nm，无法做 ggml 符号差集自检 → 拒绝盖章，APK 降级"
  exit 1
fi
if [ -z "$READELF" ]; then
  echo "::warning::找不到 llvm-readelf，跳过 DT_NEEDED 检查（差集自检照做）"
fi
if [ -n "$NM" ]; then
  # a) 这一份 ggml 提供的全部已定义符号
  DEFINED="$(mktemp)"
  for g in "$JNILIBS"/libggml*.so; do
    "$NM" -D --defined-only "$g" 2>/dev/null | awk '{print $NF}' || true
  done | sort -u > "$DEFINED"
  echo "ggml 提供符号数: $(wc -l < "$DEFINED")"

  FAILED=0
  for so in libllama.so libqwen.so; do
    UND="$(mktemp)"
    "$NM" -D --undefined-only "$JNILIBS/$so" 2>/dev/null \
      | awk '{print $NF}' | grep '^ggml_' | sort -u > "$UND" || true
    MISSING="$(comm -23 "$UND" "$DEFINED" | grep -v '^$' || true)"
    echo "$so 需要 ggml 符号 $(wc -l < "$UND") 个，缺 $(echo "$MISSING" | grep -c . || echo 0) 个"
    if [ -n "$MISSING" ]; then
      echo "::error::$so 缺以下 ggml 符号（真机会 dlopen 失败）："
      echo "$MISSING" | head -20
      FAILED=1
    fi
    # b) DT_NEEDED 里引用的 libggml* 必须都在 jniLibs
    if [ -n "$READELF" ]; then
      for need in $("$READELF" -d "$JNILIBS/$so" 2>/dev/null | grep -o 'libggml[^]]*\.so' | sort -u || true); do
        if [ ! -f "$JNILIBS/$need" ]; then
          echo "::error::$so 需要 $need，但 jniLibs 里没有"
          FAILED=1
        fi
      done
    fi
  done
  if [ "$FAILED" -ne 0 ]; then exit 1; fi
fi

touch "$JNILIBS/.verified"
echo "===== 完成：两个引擎 + 唯一一份 ggml，符号自检通过 ====="
ls -la "$JNILIBS"
