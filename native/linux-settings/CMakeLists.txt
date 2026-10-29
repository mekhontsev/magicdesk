cmake_minimum_required(VERSION 3.24)
project(MagicDeskLinuxSettings LANGUAGES C)
include(ExternalProject)
find_program(MESON meson REQUIRED)
find_program(PATCH patch REQUIRED)
set(prefix "${CMAKE_BINARY_DIR}/prefix")
set(compiler "${CMAKE_C_COMPILER}")
if(CMAKE_C_COMPILER_TARGET)
    list(APPEND compiler "--target=${CMAKE_C_COMPILER_TARGET}")
endif()
if(CMAKE_SYSROOT)
    list(APPEND compiler "--sysroot=${CMAKE_SYSROOT}")
endif()
separate_arguments(flags UNIX_COMMAND "${CMAKE_C_FLAGS}")
function(meson_array out)
    set(items "")
    foreach(item IN LISTS ARGN)
        string(REPLACE "\\" "\\\\" item "${item}")
        string(REPLACE "'" "\\'" item "${item}")
        string(APPEND items "'${item}',")
    endforeach()
    set(${out} "[${items}]" PARENT_SCOPE)
endfunction()
meson_array(MD_CC ${compiler})
meson_array(MD_AR "${CMAKE_AR}")
meson_array(MD_FLAGS ${flags} -DANDROID -fPIC)
separate_arguments(link_flags UNIX_COMMAND "${CMAKE_EXE_LINKER_FLAGS}")
# Probe the same static Bionic runtime used by the helper, not a host/shared libc stub.
meson_array(MD_LINK_FLAGS ${link_flags} -static)
configure_file(android.ini.in android.ini @ONLY)
ExternalProject_Add(dbus
    URL https://dbus.freedesktop.org/releases/dbus/dbus-1.16.2.tar.xz
    URL_HASH SHA256=0ba2a1a4b16afe7bceb2c07e9ce99a8c2c3508e5dec290dbb643384bd6beb7e2
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    PATCH_COMMAND ${PATCH} --batch --forward -p1
        -i ${CMAKE_CURRENT_SOURCE_DIR}/dbus-client-only.patch
    CONFIGURE_COMMAND ${CMAKE_COMMAND} -E env --unset=CFLAGS --unset=CPPFLAGS --unset=LDFLAGS
        ${MESON} setup --clearcache --reconfigure <BINARY_DIR> <SOURCE_DIR>
        --cross-file=${CMAKE_CURRENT_BINARY_DIR}/android.ini --prefix=${prefix} --libdir=lib
        "-Dc_link_args=${MD_LINK_FLAGS}"
        --default-library=static --buildtype=release --wrap-mode=nofallback
        -Dauto_features=disabled -Dmessage_bus=false -Dtools=false
        -Dmodular_tests=disabled -Dinstalled_tests=false
    BUILD_COMMAND ${MESON} compile -C <BINARY_DIR> -j 4
    INSTALL_COMMAND ${MESON} install -C <BINARY_DIR>
        COMMAND ${CMAKE_COMMAND} -E make_directory ${prefix}/licenses/dbus
        COMMAND ${CMAKE_COMMAND} -E copy <SOURCE_DIR>/COPYING ${prefix}/licenses/dbus/
        COMMAND ${CMAKE_COMMAND} -E copy_directory <SOURCE_DIR>/LICENSES ${prefix}/licenses/dbus/LICENSES
    BUILD_BYPRODUCTS ${prefix}/lib/libdbus-1.a)
ExternalProject_Add_StepDependencies(dbus configure ${CMAKE_CURRENT_BINARY_DIR}/android.ini)
ExternalProject_Add_StepDependencies(dbus patch ${CMAKE_CURRENT_SOURCE_DIR}/dbus-client-only.patch)
add_executable(magicdesk-linux-settings main.c portal.c bus_agent.c bus_wire.c)
add_dependencies(magicdesk-linux-settings dbus)
target_include_directories(magicdesk-linux-settings PRIVATE ${prefix}/include/dbus-1.0 ${prefix}/lib/dbus-1.0/include)
target_compile_options(magicdesk-linux-settings PRIVATE -std=c17 -O2 -Wall -Wextra -Werror -fno-pie)
target_link_options(magicdesk-linux-settings PRIVATE -static -Wl,-z,noexecstack)
target_link_libraries(magicdesk-linux-settings PRIVATE ${prefix}/lib/libdbus-1.a)
set_target_properties(magicdesk-linux-settings PROPERTIES OUTPUT_NAME libmagicdesk_linux_settings.so)
