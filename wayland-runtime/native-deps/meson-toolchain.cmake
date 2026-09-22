# Meson and Autoconf consume the same Android compiler selected by CMake.
find_program(MDW_PKG_CONFIG pkg-config REQUIRED)
find_program(MDW_HOST_CC NAMES cc gcc clang NO_CMAKE_FIND_ROOT_PATH REQUIRED)
set(MDW_HOST_PREFIX "${CMAKE_BINARY_DIR}/host")
set(MDW_WAYLAND_SCANNER "${MDW_HOST_PREFIX}/bin/wayland-scanner")
set(target_compiler "${CMAKE_C_COMPILER}")
if(CMAKE_C_COMPILER_TARGET)
    list(APPEND target_compiler "--target=${CMAKE_C_COMPILER_TARGET}")
endif()
if(CMAKE_SYSROOT)
    list(APPEND target_compiler "--sysroot=${CMAKE_SYSROOT}")
endif()
separate_arguments(target_cflags UNIX_COMMAND "${CMAKE_C_FLAGS}")
separate_arguments(target_ldflags UNIX_COMMAND "${CMAKE_EXE_LINKER_FLAGS}")
list(APPEND target_cflags -DANDROID -fPIC)

function(meson_array output)
    set(items "")
    foreach(item IN LISTS ARGN)
        string(REPLACE "\\" "\\\\" item "${item}")
        string(REPLACE "'" "\\'" item "${item}")
        string(APPEND items "'${item}', ")
    endforeach()
    set(${output} "[${items}]" PARENT_SCOPE)
endfunction()
meson_array(MDW_MESON_CC ${target_compiler})
meson_array(MDW_MESON_AR "${CMAKE_AR}")
meson_array(MDW_MESON_STRIP "${CMAKE_STRIP}")
meson_array(MDW_MESON_PKG_CONFIG "${MDW_PKG_CONFIG}")
meson_array(MDW_MESON_CFLAGS ${target_cflags})
meson_array(MDW_MESON_LDFLAGS ${target_ldflags})
meson_array(MDW_MESON_PKG_DIRS "${MDW_DEPENDENCY_PREFIX}/lib/pkgconfig"
    "${MDW_DEPENDENCY_PREFIX}/share/pkgconfig")
configure_file(android.ini.in android.ini @ONLY)

list(JOIN target_compiler " " autoconf_cc)
set(build_environment ${CMAKE_COMMAND} -E env
    --unset=CPPFLAGS --unset=PKG_CONFIG_SYSROOT_DIR
    "CC=${autoconf_cc}" "AR=${CMAKE_AR}" "RANLIB=${CMAKE_RANLIB}" "STRIP=${CMAKE_STRIP}"
    "CFLAGS=${CMAKE_C_FLAGS} -DANDROID -fPIC" "LDFLAGS=${CMAKE_EXE_LINKER_FLAGS}"
    "PKG_CONFIG_PATH=" "PKG_CONFIG_PATH_FOR_BUILD=${MDW_HOST_PREFIX}/lib/pkgconfig"
    "PKG_CONFIG_LIBDIR=${MDW_DEPENDENCY_PREFIX}/lib/pkgconfig:${MDW_DEPENDENCY_PREFIX}/share/pkgconfig")
set(host_environment ${CMAKE_COMMAND} -E env
    --unset=CFLAGS --unset=CPPFLAGS --unset=LDFLAGS --unset=CC --unset=AR
    --unset=PKG_CONFIG_LIBDIR --unset=PKG_CONFIG_PATH --unset=PKG_CONFIG_SYSROOT_DIR
    "CC=${MDW_HOST_CC}")
set(meson_cross --cross-file=${CMAKE_CURRENT_BINARY_DIR}/android.ini)
