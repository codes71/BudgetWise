cmake_minimum_required(VERSION 3.22)
project(raj_host_tests CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

enable_testing()

add_library(raj_text STATIC ../src/main/cpp/raj_text.cpp)
target_include_directories(raj_text PUBLIC ../src/main/cpp)

add_executable(raj_text_test tests/raj_text_test.cpp)
target_link_libraries(raj_text_test PRIVATE raj_text)
target_compile_definitions(raj_text_test PRIVATE RAJ_FIXTURE_DIR="${CMAKE_CURRENT_SOURCE_DIR}/tests")

add_test(NAME raj_text COMMAND raj_text_test)
