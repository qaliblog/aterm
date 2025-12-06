cmake_minimum_required(VERSION 3.22.1)
project("llama")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

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
