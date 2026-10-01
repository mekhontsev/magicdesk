# Archive and digest code belongs only to the offline image tool, never the syscall adapter.
include(FetchContent)
FetchContent_Declare(guest_zlib
    URL https://github.com/madler/zlib/releases/download/v1.3.2/zlib-1.3.2.tar.gz
    URL_HASH SHA256=bb329a0a2cd0274d05519d61c667c062e06990d72e125ee2dfa8de64f0119d16
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
set(ZLIB_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(ZLIB_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(ZLIB_BUILD_STATIC ON CACHE BOOL "" FORCE)
set(ZLIB_INSTALL OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(guest_zlib)
set(ZLIB_INCLUDE_DIR "${guest_zlib_SOURCE_DIR};${guest_zlib_BINARY_DIR}" CACHE STRING "" FORCE)
set(ZLIB_LIBRARY zlibstatic CACHE STRING "" FORCE)

FetchContent_Declare(guest_zstd
    URL https://github.com/facebook/zstd/releases/download/v1.5.7/zstd-1.5.7.tar.gz
    URL_HASH SHA256=eb33e51f49a15e023950cd7825ca74a4a2b43db8354825ac24fc1b7ee09e6fa3
    SOURCE_SUBDIR build/cmake
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
set(ZSTD_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_STATIC ON CACHE BOOL "" FORCE)
set(ZSTD_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(ZSTD_MULTITHREAD_SUPPORT OFF CACHE BOOL "" FORCE)
set(ZSTD_LEGACY_SUPPORT OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(guest_zstd)
set(ZSTD_INCLUDE_DIR "${guest_zstd_SOURCE_DIR}/lib" CACHE PATH "" FORCE)
set(ZSTD_LIBRARY libzstd_static CACHE STRING "" FORCE)

FetchContent_Declare(guest_archive
    URL https://github.com/libarchive/libarchive/releases/download/v3.8.9/libarchive-3.8.9.tar.xz
    URL_HASH SHA256=888c934f9d95648ecb9163dc8e23ab80a476ecb81a8f1154704a227b5b676dde
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
foreach(feature MBEDTLS NETTLE OPENSSL LIBB2 LZ4 LZO LZMA BZip2 LIBXML2 EXPAT WIN32_XMLLITE
        PCREPOSIX PCRE2POSIX CNG TAR CPIO CAT UNZIP TEST COVERAGE INSTALL ICONV ACL XATTR LIBGCC)
    set(ENABLE_${feature} OFF CACHE BOOL "" FORCE)
endforeach()
set(ENABLE_ZLIB ON CACHE BOOL "" FORCE)
set(ENABLE_ZSTD ON CACHE BOOL "" FORCE)
set(LIBMD_FOUND FALSE CACHE INTERNAL "No host crypto dependency" FORCE)
# These symbols are provided by the pinned source target. Configure-time link
# probes cannot link a build-tree target before it has been built.
set(HAVE_LIBZSTD 1 CACHE INTERNAL "Bundled zstd streaming decompressor" FORCE)
set(HAVE_ZSTD_compressStream 1 CACHE INTERNAL "Bundled zstd streaming compressor" FORCE)
set(HAVE_ZSTD_minCLevel 1 CACHE INTERNAL "Bundled zstd compression bounds" FORCE)
FetchContent_MakeAvailable(guest_archive)

FetchContent_Declare(guest_digest
    URL https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-3.6.7/mbedtls-3.6.7.tar.bz2
    URL_HASH SHA256=a7e8bcbec0e6f761b4af24f25677626b35f762f68eef79c08677a363212d11f6
    SOURCE_SUBDIR magicdesk-no-cmake
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
FetchContent_MakeAvailable(guest_digest)
add_library(guest-sha256 STATIC ${guest_digest_SOURCE_DIR}/library/sha256.c
    ${guest_digest_SOURCE_DIR}/library/platform_util.c)
target_include_directories(guest-sha256 PUBLIC ${guest_digest_SOURCE_DIR}/include
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
target_compile_definitions(guest-sha256 PUBLIC MBEDTLS_CONFIG_FILE="image_digest_config.h")

configure_file(${guest_zlib_SOURCE_DIR}/LICENSE ${CMAKE_CURRENT_BINARY_DIR}/licenses/zlib.txt COPYONLY)
configure_file(${guest_zstd_SOURCE_DIR}/LICENSE ${CMAKE_CURRENT_BINARY_DIR}/licenses/zstd.txt COPYONLY)
configure_file(${guest_archive_SOURCE_DIR}/COPYING ${CMAKE_CURRENT_BINARY_DIR}/licenses/libarchive.txt COPYONLY)
configure_file(${guest_digest_SOURCE_DIR}/LICENSE ${CMAKE_CURRENT_BINARY_DIR}/licenses/mbedtls.txt COPYONLY)
