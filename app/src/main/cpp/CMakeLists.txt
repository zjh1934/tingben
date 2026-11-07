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

target_include_directories(tingben_native PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}"
        "${CMAKE_CURRENT_SOURCE_DIR}/upstream_include")

target_link_libraries(tingben_native llama_engine qwen_engine android log ${CMAKE_DL_LIBS})
