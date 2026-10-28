cmake_minimum_required(VERSION 3.22)
project(syntra_llm C CXX)

include(FetchContent)
# Pinned llama.cpp master commit with LFM3 architecture support (2026-10-28).
FetchContent_Declare(llama
    URL https://github.com/ggml-org/llama.cpp/archive/8642541b255bfb5dc39cd351a178be09013a55d6.tar.gz)

set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# Dotprod + fp16 + int8 matmul: present on Dimensity 7200/8000-class and newer phones.
# shortcut: SIGILL on pre-2021 ARMv8.0 phones; build GGML_CPU_ALL_VARIANTS if those must be supported.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16+i8mm" CACHE STRING "" FORCE)
FetchContent_MakeAvailable(llama)

add_library(syntra_llm SHARED syntra_llm.cpp)
target_link_libraries(syntra_llm llama mtmd log)
