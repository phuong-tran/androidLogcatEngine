cmake_minimum_required(VERSION 3.22.1)
project("logcat_capture")

# Force C++17 for specialized features: std::string_view, std::atomic<std::shared_ptr>
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Optimization: Identical Code Folding (ICF) reduces binary size by merging identical functions
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--icf=all")

set(SRC_FILES
        LogEngine.hpp
        LogEngine.cpp
        LogEngine_jni.cpp
)

add_library(logcat_capture SHARED ${SRC_FILES})

# Compiler flags for maximum throughput and pipeline optimization
target_compile_options(logcat_capture PRIVATE
        -fPIC
        -O3                         # Aggressive speed optimization
        -ffunction-sections         # Prepare sections for dead-code elimination (GC)
        -fdata-sections
        -fvisibility=hidden         # Conceal internal symbols for faster JNI lookup and security
        -D_FORTIFY_SOURCE=2         # Runtime buffer overflow protection

        # --- Advanced Hardware & Linker Hints ---
        -flto                       # Link-Time Optimization: enables cross-module inlining
        -fvectorize                 # Explicitly hint the compiler to use SIMD (NEON for ARM)
        -fomit-frame-pointer        # Frees up a register for data processing instead of stack tracking
)

# Find the Android NDK logging library
find_library(log-lib log)

target_link_libraries(logcat_capture
        android                     # Required for native window/asset/buffer management
        ${log-lib}
        -Wl,--gc-sections           # Linker Garbage Collection: strips unreachable code
        -Wl,--no-as-needed          # Prevent linker from dropping essential symbols
        -Wl,--hash-style=both       # Compatibility with older and newer Android dynamic linkers
)

if (ANDROID)
    target_link_options(logcat_capture PRIVATE
            # Support for 16KB page size (Mandatory for high-end Android 15+ devices)
            "-Wl,-z,max-page-size=16384"
            "-Wl,--no-rosegment"

            # Global Link-Time Optimization (re-calculates CPU pipeline across all objects)
            "-flto"

            # Aggressive stripping: removes all non-essential debug symbols from the .so
            "-Wl,--strip-all"

            # Additional safety: treat undefined symbols as errors during linking
            "-Wl,--no-undefined"
    )
endif ()
