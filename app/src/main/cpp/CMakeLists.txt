cmake_minimum_required(VERSION 3.18.1)
project(chorlame C)

# LAME 3.99.5 (LGPL), built without autotools config.h
file(GLOB LAME_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/lame/*.c)

add_library(chorlame SHARED lame_jni.c ${LAME_SOURCES})
target_include_directories(chorlame PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/lame)
target_compile_definitions(chorlame PRIVATE STDC_HEADERS=1 HAVE_STDINT_H=1 HAVE_LIMITS_H=1 HAVE_STRING_H=1)
target_compile_options(chorlame PRIVATE -O2 -w -fno-strict-aliasing)
target_link_libraries(chorlame m)
