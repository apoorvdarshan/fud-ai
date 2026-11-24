cmake_minimum_required(VERSION 3.22)
project(fud_offline_voice LANGUAGES C CXX)

# Only real arm64 phones expose Offline Voice; keep other Android app ABIs.
if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    return()
endif()

include(FetchContent)
# Debug installs still need optimized inference kernels. Keep debug symbols.
string(APPEND CMAKE_C_FLAGS_DEBUG " -O2")
string(APPEND CMAKE_CXX_FLAGS_DEBUG " -O2")
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)
FetchContent_Declare(llama_cpp
    URL https://codeload.github.com/ggml-org/llama.cpp/tar.gz/8642541b255bfb5dc39cd351a178be09013a55d6
    URL_HASH SHA256=1688a167a8edd9499cdd0c19b8bd044f0fa184b466981977bbf2222d884953a4
)
FetchContent_MakeAvailable(llama_cpp)

set(BRIDGE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../shared/offline-voice")
add_library(fud_offline_voice SHARED OfflineVoiceJni.cpp "${BRIDGE}/OfflineVoice.cpp")
target_compile_features(fud_offline_voice PRIVATE cxx_std_17)
target_include_directories(fud_offline_voice PRIVATE "${BRIDGE}")
target_link_libraries(fud_offline_voice PRIVATE llama mtmd android log)
# Do not expose or interpose this private ggml copy on the app's other native runtimes.
target_link_options(fud_offline_voice PRIVATE "-Wl,--exclude-libs,ALL")
