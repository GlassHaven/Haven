cmake_minimum_required(VERSION 3.22.1)
project(pty_bridge)

add_library(pty_bridge SHARED pty_bridge.c)
target_link_libraries(pty_bridge log)

# Launcher for the UML guest transport: execs passt and the UML kernel
# wired over a socketpair. The kernel, stub and passt binaries are
# fetched (fetch-uml.sh); only the launcher is built here.
# The output name must stay libuml-net.so (hyphen) — NativeFeatures,
# buildLaunchCommand and the terminal-flavour jniLibs exclude all use it.
# It must be a statically linked executable (same pattern as libproot.so),
# not a SHARED library: PtyBridge execve's the file, and exec of an
# ET_DYN with no entry point dies with SIGILL on the first instruction.
add_executable(uml-net uml_net.c)
# -fno-emulated-tls: the Android triple defaults clang to emutls, which
# drops the TLS pad out of PT_TLS and trips bionic's static-init check
# ("TLS segment is underaligned") at exec time — same recipe as the
# prototype's probe build. uml_net.c itself uses no TLS; libc.a's does.
target_compile_options(uml-net PRIVATE -fno-emulated-tls)
target_link_options(uml-net PRIVATE -static -fno-emulated-tls)
set_target_properties(uml-net PROPERTIES PREFIX "lib" OUTPUT_NAME "uml-net" SUFFIX ".so")
