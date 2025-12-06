cmake_minimum_required(VERSION 3.22.1)
project("llama")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Disable some llama.cpp features for Android
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_ACCELERATE OFF CACHE BOOL "" FORCE)
set(LLAMA_BLAS OFF CACHE BOOL "" FORCE)
set(LLAMA_CUBLAS OFF CACHE BOOL "" FORCE)
set(LLAMA_CLBLAST OFF CACHE BOOL "" FORCE)
set(LLAMA_METAL OFF CACHE BOOL "" FORCE)
set(LLAMA_MPI OFF CACHE BOOL "" FORCE)
set(LLAMA_KOMPUTE OFF CACHE BOOL "" FORCE)
set(LLAMA_VULKAN OFF CACHE BOOL "" FORCE)
set(LLAMA_SYCL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENBLAS OFF CACHE BOOL "" FORCE)
set(LLAMA_GGML_USE_CUBLAS OFF CACHE BOOL "" FORCE)
set(LLAMA_GGML_USE_CLBLAST OFF CACHE BOOL "" FORCE)
set(LLAMA_GGML_USE_METAL OFF CACHE BOOL "" FORCE)
set(LLAMA_GGML_USE_MPI OFF CACHE BOOL "" FORCE)
set(LLAMA_GGML_USE_KOMPUTE OFF CACHE BOOL "" FORCE)
set(LLAMA_GGML_USE_SYCL OFF CACHE BOOL "" FORCE)

# Build llama.cpp as a static library
add_subdirectory(llama.cpp)

# JNI bridge library
add_library(
    llama
    SHARED
    llama_jni.cpp
)

# Find required libraries
find_library(
    log-lib
    log
)

# Include directories
target_include_directories(
    llama
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp
)

# Link libraries
target_link_libraries(
    llama
    PRIVATE
    ${log-lib}
    llama
)

# Compiler flags for Android
target_compile_options(llama PRIVATE -O3 -DNDEBUG)
