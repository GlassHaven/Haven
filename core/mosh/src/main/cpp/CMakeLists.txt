cmake_minimum_required(VERSION 3.22)
project(mosh-pty C)

add_library(mosh-pty SHARED pty_jni.c)
target_link_libraries(mosh-pty log)

# 16KB page size alignment for Android 15+ (API 35+)
# Disable build-id for reproducible builds
target_link_options(mosh-pty PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--build-id=none")
