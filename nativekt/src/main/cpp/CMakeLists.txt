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
target_link_options(nktrampoline PRIVATE
    -Wl,--gc-sections
    -Wl,--strip-all
)

find_library(log-lib log)
target_link_libraries(nktrampoline PRIVATE ${log-lib})
