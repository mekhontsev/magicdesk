include_guard(GLOBAL)
include(FetchContent)
FetchContent_Declare(guest_digest
    URL https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-3.6.7/mbedtls-3.6.7.tar.bz2
    URL_HASH SHA256=a7e8bcbec0e6f761b4af24f25677626b35f762f68eef79c08677a363212d11f6
    SOURCE_SUBDIR magicdesk-no-cmake
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
FetchContent_MakeAvailable(guest_digest)
add_library(guest-sha256 STATIC ${guest_digest_SOURCE_DIR}/library/sha256.c
    ${guest_digest_SOURCE_DIR}/library/md.c
    ${guest_digest_SOURCE_DIR}/library/constant_time.c
    ${guest_digest_SOURCE_DIR}/library/platform_util.c)
target_include_directories(guest-sha256 PUBLIC ${guest_digest_SOURCE_DIR}/include
    PRIVATE ${CMAKE_CURRENT_LIST_DIR}/src)
target_compile_definitions(guest-sha256 PUBLIC MBEDTLS_CONFIG_FILE="image_digest_config.h")
target_compile_options(guest-sha256 INTERFACE -iquote ${CMAKE_CURRENT_LIST_DIR}/src)
configure_file(${guest_digest_SOURCE_DIR}/LICENSE ${CMAKE_BINARY_DIR}/licenses/mbedtls.txt COPYONLY)
