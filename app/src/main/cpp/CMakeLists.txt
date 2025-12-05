cmake_minimum_required(VERSION 3.22.1)
project("llama")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add llama.cpp source files
# Note: This is a placeholder - actual llama.cpp integration requires:
# 1. Clone llama.cpp repository
# 2. Add llama.cpp source files to this CMakeLists
# 3. Link against required libraries (ggml, etc.)

# For now, just compile the JNI bridge
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

# Link libraries
target_link_libraries(
    llama
    ${log-lib}
)

# Include directories (will be needed for llama.cpp)
# include_directories(
#     ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp
# )
