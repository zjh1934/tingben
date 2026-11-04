# R20 · 模型底座：JNI 桥。
#
# 【不手搓推理】两个引擎的 .so 由 scripts/build_native.sh 在 CI 里按各自官方
# 构建方式交叉编译后放到 app/src/main/jniLibs/<abi>/：
#   libllama.so  ← llama.cpp 官方 docs/android.md 的 NDK 命令
#   libqwen.so   ← qwentts.cpp 自带 CMake 的 `qwen` SHARED target（extern "C" qt_* API）
# R30：两个上游共用 **唯一一份** llama.cpp 的 ggml（见 scripts/build_native.sh），
# 不再各拷一套互相覆盖；这里只做 IMPORTED 链接。
#
# 本项目只写薄 JNI 胶水（tingben_native.cpp），不复制上游源码、不改上游 CMake。
cmake_minimum_required(VERSION 3.22.1)
project("tingben_native" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ENGINE_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

add_library(llama_engine SHARED IMPORTED)
set_target_properties(llama_engine PROPERTIES
        IMPORTED_LOCATION "${ENGINE_LIB_DIR}/libllama.so")

add_library(qwen_engine SHARED IMPORTED)
set_target_properties(qwen_engine PROPERTIES
        IMPORTED_LOCATION "${ENGINE_LIB_DIR}/libqwen.so")

add_library(tingben_native SHARED tingben_native.cpp)

# llama.h 依赖 upstream_include/ 下的 ggml.h / gguf.h 等公开头，
# 由 scripts/build_native.sh 从 llama.cpp 仓库拷贝（只拷头，不拷源码）
target_include_directories(tingben_native PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}"
        "${CMAKE_CURRENT_SOURCE_DIR}/upstream_include")

# 只依赖两个上游公开 C API；android/log 打日志，不引第三方
target_link_libraries(tingben_native llama_engine qwen_engine android log)
