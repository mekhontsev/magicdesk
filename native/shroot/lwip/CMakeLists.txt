cmake_minimum_required(VERSION 3.24)
if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
    project(guest_packet_stack C)
endif()
include(FetchContent)
find_package(Git REQUIRED)
FetchContent_Declare(guest_lwip
    URL https://codeload.github.com/lwip-tcpip/lwip/tar.gz/77dcd25a72509eb83f72b033d219b1d40cd8eb95
    URL_HASH SHA256=85a09ebe68c260d4876e800220588e5a0b624266c994cc25da978210c2813f14
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    SOURCE_SUBDIR magicdesk-no-cmake)
FetchContent_MakeAvailable(guest_lwip)
# Keep the fetched upstream immutable. Reconfiguration applies the reviewed
# patch to a fresh build-tree copy, including when the local patch changes.
set(context_source ${CMAKE_CURRENT_BINARY_DIR}/source)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/context.patch)
file(REMOVE_RECURSE ${context_source})
file(COPY ${guest_lwip_SOURCE_DIR}/src DESTINATION ${context_source})
execute_process(COMMAND ${CMAKE_COMMAND} -E env GIT_CEILING_DIRECTORIES=${CMAKE_CURRENT_BINARY_DIR}
    ${GIT_EXECUTABLE} apply
    ${CMAKE_CURRENT_SOURCE_DIR}/context.patch
    WORKING_DIRECTORY ${context_source}
    RESULT_VARIABLE patch_result ERROR_VARIABLE patch_error)
if(NOT patch_result EQUAL 0)
    message(FATAL_ERROR "lwIP context patch: ${patch_error}")
endif()
set(core ${context_source}/src/core)
add_library(guest-lwip STATIC context.c
    ${core}/init.c ${core}/def.c ${core}/inet_chksum.c ${core}/ip.c
    ${core}/mem.c ${core}/memp.c ${core}/netif.c ${core}/pbuf.c ${core}/raw.c
    ${core}/tcp.c ${core}/tcp_in.c ${core}/tcp_out.c ${core}/timeouts.c ${core}/udp.c
    ${core}/ipv4/etharp.c ${core}/ipv4/icmp.c ${core}/ipv4/igmp.c
    ${core}/ipv4/ip4.c ${core}/ipv4/ip4_addr.c ${core}/ipv4/ip4_frag.c
    ${core}/ipv6/ethip6.c ${core}/ipv6/icmp6.c ${core}/ipv6/inet6.c
    ${core}/ipv6/ip6.c ${core}/ipv6/ip6_addr.c ${core}/ipv6/ip6_frag.c
    ${core}/ipv6/mld6.c ${core}/ipv6/nd6.c ${context_source}/src/netif/ethernet.c)
target_include_directories(guest-lwip PUBLIC ${CMAKE_CURRENT_SOURCE_DIR} ${context_source}/src/include)
target_compile_options(guest-lwip PRIVATE -std=c17 -O2 -Wall -Wextra -Werror -fno-pie)
add_custom_command(TARGET guest-lwip POST_BUILD
    COMMAND ${CMAKE_COMMAND} -DNM=${CMAKE_NM} -DARCHIVE=$<TARGET_FILE:guest-lwip>
        -P ${CMAKE_CURRENT_SOURCE_DIR}/audit.cmake VERBATIM)
if(CMAKE_C_COMPILER_ID MATCHES "Clang")
    target_compile_options(guest-lwip PRIVATE -Wno-tautological-constant-out-of-range-compare)
endif()
configure_file(${guest_lwip_SOURCE_DIR}/COPYING ${CMAKE_CURRENT_BINARY_DIR}/../licenses/lwip.txt COPYONLY)
if(MAGICDESK_GUEST_FIXTURES OR CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
    add_executable(test_lwip_context ../../guest-exec-lab/test_lwip_context.c)
    target_link_libraries(test_lwip_context PRIVATE guest-lwip)
    target_compile_options(test_lwip_context PRIVATE -std=c17 -O2 -Wall -Wextra -Werror -UNDEBUG -fno-pie)
    target_link_options(test_lwip_context PRIVATE -static -Wl,--wrap=sys_now)
endif()
