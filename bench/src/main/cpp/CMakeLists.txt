cmake_minimum_required(VERSION 3.22.1)
project(slimbench LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(slimbench SHARED
    slimbench.cpp
    kernels.cpp
)

target_compile_options(slimbench PRIVATE
    -fvisibility=hidden
    -O3
    -march=armv8-a+simd
    -ffast-math
    -fno-exceptions
    -fno-rtti
    -Wall
    -Wextra
)

target_link_options(slimbench PRIVATE
    -Wl,--gc-sections
    -Wl,--strip-all
    -Wl,-z,max-page-size=16384
)

find_library(log-lib log)
target_link_libraries(slimbench PRIVATE ${log-lib})
