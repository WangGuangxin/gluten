set(VCPKG_BUILD_TYPE release)
set(VCPKG_TARGET_ARCHITECTURE arm64)
set(VCPKG_CRT_LINKAGE dynamic)
set(VCPKG_LIBRARY_LINKAGE static)

set(VCPKG_CMAKE_SYSTEM_NAME Linux)

set(VCPKG_C_FLAGS "-march=armv8-a+crc")
set(VCPKG_CXX_FLAGS "-march=armv8-a+crc -std=c++20")
set(VCPKG_LINKER_FLAGS "-static-libstdc++ -static-libgcc")

# Turn off hardening that some distributions' compilers enable by default (Ubuntu/Debian GCC), as
# Velox's get_distro_hardening_off_flags does; no-ops for compilers without such defaults. GCC >= 8 and
# Clang >= 11 accept these flags.
if(NOT "$ENV{VELOX_KEEP_DISTRO_HARDENING}" STREQUAL "ON")
    string(APPEND VCPKG_C_FLAGS " -fno-stack-protector -fno-stack-clash-protection -U_FORTIFY_SOURCE")
    string(APPEND VCPKG_CXX_FLAGS " -fno-stack-protector -fno-stack-clash-protection -U_FORTIFY_SOURCE")
endif()

if("${PORT}" STREQUAL "openssl" AND "$ENV{VCPKG_DYNAMIC_OPENSSL}" STREQUAL "ON")
    set(VCPKG_LIBRARY_LINKAGE dynamic)
endif()
