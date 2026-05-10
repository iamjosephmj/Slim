cmake_minimum_required(VERSION 3.22.1)
project(nktrampoline LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(nktrampoline SHARED trampoline.cpp)

target_compile_options(nktrampoline PRIVATE
    -fvisibility=hidden
    -Wall
    -Wextra
)

# Strip everything except the JNI entry points. Reduces .so size and avoids
# leaking helper symbols to consumers that load other native libs.
#
# `-Wl,-z,max-page-size=16384` aligns the .so's load segments to 16 KB so
# the library is loadable on Android devices using 16 KB page size
# (default on Android 15+ on certain SoCs; required for Play Store apps
# targeting API 35+ from Nov 2025). The legacy 4 KB page-size devices
# still load this without issue — the alignment is a max, not a fixed.
target_link_options(nktrampoline PRIVATE
    -Wl,--gc-sections
    -Wl,--strip-all
    -Wl,-z,max-page-size=16384
)

find_library(log-lib log)
target_link_libraries(nktrampoline PRIVATE ${log-lib})
