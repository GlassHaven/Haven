# Standalone CMake recipe for OpenVPN3 Core's ClientAPI, targeting Android.
#
# This is Haven's own build recipe, separate from the checksum-verified
# upstream sources prepared in openvpn3-core/.deps/sources/openvpn3/.
#
# It exists because upstream OpenVPN/openvpn3 has no maintained Android build
# path as of this writing: javacli/android/ is a single 40-line CPU-usage JNI
# helper (not a build system), CMakePresets.json has zero Android presets,
# and client/CMakeLists.txt only defines a SWIG/Python target gated behind
# BUILD_SWIG_LIB.
#
# What *is* real and self-contained: openvpn::ClientAPI::OpenVPNClient in
# client/ovpncli.hpp/.cpp — documented upstream as the intended extension
# point ("can be wrapped by Swig to create bindings for other languages").
# add_core_dependencies() in cmake/findcoredeps.cmake (upstream) confirms
# the only two translation units needed are ovpncli.cpp and
# openvpn/crypto/data_epoch.cpp; everything else OpenVPN3 Core needs is
# header-only (aside from Windows/macOS-only agent daemons under
# openvpn/omi and openvpn/ovpnagent, which are irrelevant on Android).
#
# Four dependencies are required and are supplied by build-android.sh via
# the cache variables below (ASIO_DIR/FMT_DIR are header-only checkouts,
# MBEDTLS_INSTALL/LZ4_INSTALL are per-ABI cross-compiled install prefixes):
#   CORE_DIR          - path to the prepared OpenVPN3 sources
#   ASIO_DIR           - path to a checkout of chriskohlhoff/asio, patched
#                        with the pinned OpenVPN3 archive's own
#                        deps/vcpkg-ports/asio/*.patch (asio isn't usable
#                        un-patched against this codebase)
#   FMT_DIR             - path to a checkout of fmtlib/fmt (header-only mode,
#                          -DFMT_HEADER_ONLY; only string.hpp uses fmt::format)
#   MBEDTLS_INSTALL     - install prefix of a per-ABI cross-compiled mbedtls
#                         3.6.x build (include/ + lib/{libmbedtls,libmbedx509,
#                         libmbedcrypto}.a)
#   LZ4_INSTALL         - install prefix of a per-ABI cross-compiled lz4
#                         build (include/ + lib/liblz4.a)
#   OUTPUT_NAME         - optional; base name (without lib/.a) for the
#                         produced static library, defaults to
#                         openvpn3_android

cmake_minimum_required(VERSION 3.16)
project(openvpn3_android CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

foreach(var CORE_DIR ASIO_DIR FMT_DIR MBEDTLS_INSTALL LZ4_INSTALL)
    if(NOT DEFINED ${var})
        message(FATAL_ERROR "${var} must be set, e.g. -D${var}=/path/to/thing")
    endif()
endforeach()

if(NOT DEFINED OUTPUT_NAME)
    set(OUTPUT_NAME openvpn3_android)
endif()

add_library(ovpncli_static STATIC
    ${CORE_DIR}/client/ovpncli.cpp
    ${CORE_DIR}/openvpn/crypto/data_epoch.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/adapter.cpp
)

set_target_properties(ovpncli_static PROPERTIES
    OUTPUT_NAME ${OUTPUT_NAME}
    ARCHIVE_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib
)

target_include_directories(ovpncli_static PRIVATE
    ${CORE_DIR}
    ${CORE_DIR}/client
    ${ASIO_DIR}/include
    ${FMT_DIR}/include
    ${MBEDTLS_INSTALL}/include
    ${LZ4_INSTALL}/include
    # adapter.cpp's own directory (for #include <client/ovpncli.hpp> to
    # resolve consistently) plus go-openvpn's vendored C bridge headers
    # (library.h/tunsetup.h/user_data.h) that adapter.cpp implements.
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/../rclone-android/go/ovpnbridge/openvpn3/bridge
)

target_compile_definitions(ovpncli_static PRIVATE
    -DASIO_STANDALONE
    -DUSE_ASIO
    -DHAVE_LZ4
    -DUSE_MBEDTLS
    -DFMT_HEADER_ONLY
)
# NOTE: deliberately NOT defining OPENVPN_FORCE_TUN_NULL here (even as =0).
# client/ovpncli.cpp checks it with #ifdef/!defined(), not by value:
#   #if (OPENVPN_PLATFORM_ANDROID || OPENVPN_PLATFORM_IPHONE)
#         && !defined(OPENVPN_FORCE_TUN_NULL) && !defined(OPENVPN_EXTERNAL_TUN_FACTORY)
#   #define USE_TUN_BUILDER
# A previous "-DOPENVPN_FORCE_TUN_NULL=0" here (from before adapter.cpp
# existed) *defined* the macro, which satisfies defined(OPENVPN_FORCE_TUN_NULL)
# regardless of its value -- silently disabling USE_TUN_BUILDER and making
# client/cliopt.hpp fall through to openvpn/tun/client/tunnull.hpp (a no-op
# null tun device) instead of openvpn/tun/builder/client.hpp, the code path
# that actually calls the tun_builder_* virtuals adapter.cpp overrides.
# That bug was invisible before this file existed (tunnull.hpp compiles and
# links fine standalone -- nothing exercised the TunBuilderBase virtuals),
# but would have made every tun_builder_* override in adapter.cpp dead code.
# Leaving the macro fully undefined lets ovpncli.cpp's own #ifdef select
# USE_TUN_BUILDER correctly on Android (OPENVPN_PLATFORM_ANDROID is
# auto-detected from __ANDROID__ by openvpn/common/platform.hpp).

target_link_libraries(ovpncli_static PRIVATE
    ${MBEDTLS_INSTALL}/lib/libmbedtls.a
    ${MBEDTLS_INSTALL}/lib/libmbedx509.a
    ${MBEDTLS_INSTALL}/lib/libmbedcrypto.a
    ${LZ4_INSTALL}/lib/liblz4.a
)

# Merge mbedtls+lz4 object code directly into the output archive. CMake's
# target_link_libraries(... PRIVATE ...) above only records these as a
# transitive *usage requirement* on ovpncli_static's own CMake target --
# it does NOT merge their object code into libopenvpn3_android_<abi>.a.
# That's invisible to any consumer using CMake's target graph (an
# executable/shared-lib target that also links ovpncli_static via CMake
# would get mbedtls/lz4 pulled in automatically), but our real consumer is
# Go's cgo via a raw `-L <dir> -lopenvpn3_android_<abi>` directive in
# rclone-android/go/ovpnbridge/openvpn3/session.go, which has no visibility
# into CMake's target graph at all -- without this step, a real Go program
# linking against this .a fails with undefined mbedtls_*/LZ4_* symbols.
#
# ar -M (MRI script) merges member objects from multiple archives without
# the filename-collision risk of a naive extract-and-recombine approach
# (ADDLIB copies member objects by binary content into the new archive,
# not via a shared extraction directory where two archives' same-named
# .o files could silently overwrite each other).
find_program(AR_TOOL NAMES llvm-ar ar REQUIRED)
add_custom_command(TARGET ovpncli_static POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy
        $<TARGET_FILE:ovpncli_static>
        $<TARGET_FILE:ovpncli_static>.orig.a
    COMMAND ${CMAKE_COMMAND} -E echo
        "CREATE $<TARGET_FILE:ovpncli_static>" > ${CMAKE_BINARY_DIR}/merge.mri
    COMMAND ${CMAKE_COMMAND} -E echo
        "ADDLIB $<TARGET_FILE:ovpncli_static>.orig.a" >> ${CMAKE_BINARY_DIR}/merge.mri
    COMMAND ${CMAKE_COMMAND} -E echo
        "ADDLIB ${MBEDTLS_INSTALL}/lib/libmbedtls.a" >> ${CMAKE_BINARY_DIR}/merge.mri
    COMMAND ${CMAKE_COMMAND} -E echo
        "ADDLIB ${MBEDTLS_INSTALL}/lib/libmbedx509.a" >> ${CMAKE_BINARY_DIR}/merge.mri
    COMMAND ${CMAKE_COMMAND} -E echo
        "ADDLIB ${MBEDTLS_INSTALL}/lib/libmbedcrypto.a" >> ${CMAKE_BINARY_DIR}/merge.mri
    COMMAND ${CMAKE_COMMAND} -E echo
        "ADDLIB ${LZ4_INSTALL}/lib/liblz4.a" >> ${CMAKE_BINARY_DIR}/merge.mri
    COMMAND ${CMAKE_COMMAND} -E echo "SAVE" >> ${CMAKE_BINARY_DIR}/merge.mri
    COMMAND ${CMAKE_COMMAND} -E echo "END" >> ${CMAKE_BINARY_DIR}/merge.mri
    COMMAND ${CMAKE_COMMAND} -E remove $<TARGET_FILE:ovpncli_static>
    COMMAND ${AR_TOOL} -M < ${CMAKE_BINARY_DIR}/merge.mri
    COMMENT "Merging mbedtls+lz4 object code into ${OUTPUT_NAME}.a"
    VERBATIM
)
