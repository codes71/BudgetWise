cmake_minimum_required(VERSION 3.31.6)

project(raj_jni VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 16 KB page alignment is the NDK r28+ default; keep it explicit for every target.
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# Same arm64 knobs as llama.cpp's examples/llama.android at the pinned commit.
set(GGML_OPENMP ON)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../third_party/llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(raj_jni SHARED raj_jni.cpp raj_text.cpp raj_engine.cpp)

target_include_directories(raj_jni PRIVATE
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include
    ${LLAMA_SRC}/tools/mtmd)

target_link_libraries(raj_jni PRIVATE llama mtmd ggml android log)
