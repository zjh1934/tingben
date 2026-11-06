# 原生引擎 run 86
提交 126df290e9e5b5a6b2bec49d785500a82b48e5b9

## 关键行
```
5:llama.cpp commit: 18ab62a
51:-- ggml commit:  18ab62a
140:libllama.so: 39834408 字节
150:qwentts.cpp commit: de2ad83
197:-- ggml commit:  2101389
247:libqwen.so: 3677992 字节（共用上面的 ggml，不拷自己的）
249:::warning::找不到 llvm-readelf，跳过 DT_NEEDED 检查（差集自检照做）
251:libllama.so 需要 ggml 符号 267 个，缺 0
253:libqwen.so 需要 ggml 符号 116 个，缺 0
```

## 结尾 100 行
```
-- Looking for pthread_create in pthreads
-- Looking for pthread_create in pthreads - not found
-- Looking for pthread_create in pthread
-- Looking for pthread_create in pthread - not found
-- Check if compiler accepts -pthread
-- Check if compiler accepts -pthread - yes
-- Found Threads: TRUE
-- The ASM compiler identification is Clang with GNU-like command-line
-- Found assembler: /usr/local/lib/android/sdk/ndk/30.0.16248370/toolchains/llvm/prebuilt/linux-x86_64/bin/clang
-- Setting GGML_NATIVE_DEFAULT to OFF
-- Warning: ccache not found - consider installing it for faster compilation or disable this warning with GGML_CCACHE=OFF
-- CMAKE_SYSTEM_PROCESSOR: aarch64
-- GGML_SYSTEM_ARCH: ARM
-- Including CPU backend
-- ARM detected
-- Performing Test GGML_COMPILER_SUPPORTS_FP16_FORMAT_I3E
-- Performing Test GGML_COMPILER_SUPPORTS_FP16_FORMAT_I3E - Failed
-- Checking for ARM features using flags:
-- Performing Test HAVE_DOTPROD
-- Performing Test HAVE_DOTPROD - Failed
-- Performing Test HAVE_SVE
-- Performing Test HAVE_SVE - Failed
-- Performing Test HAVE_MATMUL_INT8
-- Performing Test HAVE_MATMUL_INT8 - Failed
-- Performing Test HAVE_FMA
-- Performing Test HAVE_FMA - Success
-- Performing Test HAVE_FP16_VECTOR_ARITHMETIC
-- Performing Test HAVE_FP16_VECTOR_ARITHMETIC - Failed
-- Performing Test HAVE_SME
-- Performing Test HAVE_SME - Failed
-- Adding CPU backend variant ggml-cpu:  
-- ggml version: 0.29.0
-- ggml commit:  2101389
-- Configuring done (0.9s)
-- Generating done (0.0s)
CMake Warning:
  Manually-specified variables were not used by the project:

    LLAMA_OPENSSL


-- Build files have been written to: /tmp/tingben-native-build/qwentts-build
[  2%] Checking git version
[  5%] Building C object ggml/src/CMakeFiles/ggml-base.dir/ggml.c.o
[ 11%] Building CXX object ggml/src/CMakeFiles/ggml-base.dir/ggml.cpp.o
[ 11%] Building C object ggml/src/CMakeFiles/ggml-base.dir/ggml-alloc.c.o
[ 11%] Built target version
[ 14%] Building CXX object ggml/src/CMakeFiles/ggml-base.dir/ggml-backend.cpp.o
[ 17%] Building CXX object ggml/src/CMakeFiles/ggml-base.dir/ggml-backend-meta.cpp.o
[ 20%] Building CXX object ggml/src/CMakeFiles/ggml-base.dir/ggml-opt.cpp.o
[ 22%] Building CXX object ggml/src/CMakeFiles/ggml-base.dir/ggml-threading.cpp.o
[ 25%] Building C object ggml/src/CMakeFiles/ggml-base.dir/ggml-quants.c.o
[ 28%] Building CXX object ggml/src/CMakeFiles/ggml-base.dir/gguf.cpp.o
[ 31%] Linking CXX shared library ../../libggml-base.so
[ 31%] Built target ggml-base
[ 34%] Building C object ggml/src/CMakeFiles/ggml-cpu.dir/ggml-cpu/ggml-cpu.c.o
[ 37%] Building CXX object ggml/src/CMakeFiles/ggml-cpu.dir/ggml-cpu/ggml-cpu.cpp.o
[ 40%] Building CXX object ggml/src/CMakeFiles/ggml-cpu.dir/ggml-cpu/repack.cpp.o
[ 42%] Building CXX object ggml/src/CMakeFiles/ggml-cpu.dir/ggml-cpu/hbm.cpp.o
[ 45%] Building C object ggml/src/CMakeFiles/ggml-cpu.dir/ggml-cpu/quants.c.o
[ 48%] Building CXX object ggml/src/CMakeFiles/ggml-cpu.dir/ggml-cpu/traits.cpp.o
[ 51%] Building CXX object ggml/src/CMakeFiles/ggml-cpu.dir/ggml-cpu/amx/amx.cpp.o
[ 54%] Building CXX object ggml/src/CMakeFiles/ggml-cpu.dir/ggml-cpu/amx/mmq.cpp.o
[ 57%] Building CXX object ggml/src/CMakeFiles/ggml-cpu.dir/ggml-cpu/tiled/tiled.cpp.o
[ 60%] Building CXX object ggml/src/CMakeFiles/ggml-cpu.dir/ggml-cpu/tiled/tiled-kernel.cpp.o
[ 62%] Building CXX object ggml/src/CMakeFiles/ggml-cpu.dir/ggml-cpu/binary-ops.cpp.o
[ 65%] Building CXX object ggml/src/CMakeFiles/ggml-cpu.dir/ggml-cpu/unary-ops.cpp.o
[ 68%] Building CXX object ggml/src/CMakeFiles/ggml-cpu.dir/ggml-cpu/vec.cpp.o
[ 71%] Building CXX object ggml/src/CMakeFiles/ggml-cpu.dir/ggml-cpu/ops.cpp.o
[ 74%] Building C object ggml/src/CMakeFiles/ggml-cpu.dir/ggml-cpu/arch/arm/quants.c.o
[ 77%] Building CXX object ggml/src/CMakeFiles/ggml-cpu.dir/ggml-cpu/arch/arm/repack.cpp.o
[ 80%] Linking CXX shared library ../../libggml-cpu.so
[ 80%] Built target ggml-cpu
[ 82%] Building CXX object ggml/src/CMakeFiles/ggml.dir/ggml-backend-dl.cpp.o
[ 85%] Building CXX object ggml/src/CMakeFiles/ggml.dir/ggml-backend-reg.cpp.o
[ 88%] Linking CXX shared library ../../libggml.so
[ 88%] Built target ggml
[ 91%] Building CXX object CMakeFiles/qwen.dir/src/qwen.cpp.o
[ 94%] Building CXX object CMakeFiles/qwen.dir/src/pipeline-tts.cpp.o
[ 97%] Building CXX object CMakeFiles/qwen.dir/src/pipeline-codec.cpp.o
[100%] Linking CXX shared library libqwen.so
[100%] Built target qwen
libqwen.so: 3677992 字节（共用上面的 ggml，不拷自己的）
===== [3/3] 符号自检（真机 dlopen 等价）=====
::warning::找不到 llvm-readelf，跳过 DT_NEEDED 检查（差集自检照做）
ggml 提供符号数: 3413
libllama.so 需要 ggml 符号 267 个，缺 0
0 个
libqwen.so 需要 ggml 符号 116 个，缺 0
0 个
===== 完成：两个引擎 + 唯一一份 ggml，符号自检通过 =====
total 58724
drwxr-xr-x 2 runner runner     4096 Nov  6 11:14 .
drwxr-xr-x 3 runner runner     4096 Nov  6 11:12 ..
-rw-r--r-- 1 runner runner        0 Nov  6 11:14 .verified
-rwxr-xr-x 1 runner runner  7138904 Nov  6 11:14 libggml-base.so
-rwxr-xr-x 1 runner runner  4334576 Nov  6 11:14 libggml-cpu.so
-rwxr-xr-x 1 runner runner  5129736 Nov  6 11:14 libggml.so
-rwxr-xr-x 1 runner runner 39834408 Nov  6 11:14 libllama.so
-rwxr-xr-x 1 runner runner  3677992 Nov  6 11:14 libqwen.so
```
