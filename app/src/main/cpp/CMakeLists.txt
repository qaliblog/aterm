cmake_minimum_required(VERSION 3.22.1)
project("llama")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Check if llama.cpp submodule exists, if not use FetchContent
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt")
    # Submodule exists, use it
    set(LLAMA_CPP_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
else()
    # Submodule not available, use FetchContent to download it
    include(FetchContent)
    FetchContent_Declare(
        llama_cpp
        GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
        GIT_TAG master
    )
    FetchContent_MakeAvailable(llama_cpp)
    set(LLAMA_CPP_SOURCE_DIR "${llama_cpp_SOURCE_DIR}")
endif()

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
add_subdirectory(${LLAMA_CPP_SOURCE_DIR} llama_cpp_build)

# JNI bridge library (renamed to avoid conflict with llama.cpp target)
add_library(
    llama_jni
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
    llama_jni
    PRIVATE
    ${LLAMA_CPP_SOURCE_DIR}
    ${LLAMA_CPP_SOURCE_DIR}/include
)

# Link libraries
# llama.cpp creates a static library target named "llama"
target_link_libraries(
    llama_jni
    PRIVATE
    ${log-lib}
    llama
)

# Compiler flags for Android
target_compile_options(llama_jni PRIVATE -O3 -DNDEBUG)
