cmake_minimum_required(VERSION 3.22)
project(raj_host_tests C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if (NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

enable_testing()

add_library(raj_text STATIC ../src/main/cpp/raj_text.cpp)
target_include_directories(raj_text PUBLIC ../src/main/cpp)

add_executable(raj_text_test tests/raj_text_test.cpp)
target_link_libraries(raj_text_test PRIVATE raj_text)
target_compile_definitions(raj_text_test PRIVATE RAJ_FIXTURE_DIR="${CMAKE_CURRENT_SOURCE_DIR}/tests")

add_test(NAME raj_text COMMAND raj_text_test)

# llama.cpp with the Android option set (Task 3), static and CPU-only like the phone:
# no Metal, no BLAS, no llamafile sgemm. Unlike Android: native CPU flags, no KleidiAI.
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../third_party/llama.cpp)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
add_subdirectory(${LLAMA_SRC} build-llama EXCLUDE_FROM_ALL)

execute_process(
    COMMAND git -C ${LLAMA_SRC} rev-parse HEAD
    OUTPUT_VARIABLE RAJ_LLAMA_CPP_SHA
    OUTPUT_STRIP_TRAILING_WHITESPACE
    RESULT_VARIABLE RAJ_LLAMA_CPP_SHA_RC)
if (NOT RAJ_LLAMA_CPP_SHA_RC EQUAL 0)
    set(RAJ_LLAMA_CPP_SHA unknown)
endif()

add_library(raj_engine STATIC ../src/main/cpp/raj_engine.cpp)
target_include_directories(raj_engine PUBLIC ../src/main/cpp)
target_link_libraries(raj_engine PUBLIC raj_text llama mtmd ggml)

add_executable(raj-host raj_host_main.cpp)
target_link_libraries(raj-host PRIVATE raj_engine vendor::nlohmann)
target_compile_definitions(raj-host PRIVATE RAJ_LLAMA_CPP_SHA="${RAJ_LLAMA_CPP_SHA}")

# Needs the Raj files under ~/.cache/budgetwise-eval (or RAJ_MODEL / RAJ_MMPROJ / RAJ_WAV); skips without them.
add_executable(raj_engine_test tests/raj_engine_test.cpp)
target_link_libraries(raj_engine_test PRIVATE raj_engine)

add_test(NAME raj_engine COMMAND raj_engine_test)
set_tests_properties(raj_engine PROPERTIES SKIP_RETURN_CODE 77)
