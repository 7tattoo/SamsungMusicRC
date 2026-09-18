cmake_minimum_required(VERSION 3.22.1)
project("samsung_audio" CXX)

add_library(samsung_audio SHARED native-lib.cpp)

find_library(aa-lib aaudio)
find_library(log-lib log)

target_link_libraries(samsung_audio
    ${aa-lib}
    ${log-lib})

target_compile_features(samsung_audio PRIVATE cxx_std_17)
target_compile_options(samsung_audio PRIVATE -O2 -Wall)
