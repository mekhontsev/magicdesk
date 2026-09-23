cmake_minimum_required(VERSION 3.24)
project(MagicDeskWaylandDependencies LANGUAGES C)

include(ExternalProject)
find_program(MESON meson REQUIRED)
find_program(MAKE make REQUIRED)
set(MDW_DEPENDENCY_PREFIX "${CMAKE_BINARY_DIR}/prefix" CACHE PATH "Private dependency installation")
include(meson-toolchain.cmake)

set(wayland_url https://gitlab.freedesktop.org/wayland/wayland/-/releases/1.25.0/downloads/wayland-1.25.0.tar.xz)
set(wayland_hash c065f040afdff3177680600f249727e41a1afc22fccf27222f15f5306faa1f03)
ExternalProject_Add(scanner
    URL ${wayland_url} URL_HASH SHA256=${wayland_hash} DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    CONFIGURE_COMMAND ${host_environment} ${MESON} setup --clearcache --reconfigure <BINARY_DIR> <SOURCE_DIR>
        --prefix=${MDW_HOST_PREFIX} --libdir=lib --buildtype=release --wrap-mode=nofallback
        -Dlibraries=false -Dscanner=true -Dtests=false -Ddocumentation=false -Ddtd_validation=false
    BUILD_COMMAND ${MESON} compile -C <BINARY_DIR> -j 4
    INSTALL_COMMAND ${MESON} install -C <BINARY_DIR>)

function(mdw_meson name url hash)
    cmake_parse_arguments(DEP "" "" "DEPENDS;OPTIONS;TARGETS" ${ARGN})
    ExternalProject_Add(${name}
        URL ${url} URL_HASH SHA256=${hash} DOWNLOAD_EXTRACT_TIMESTAMP TRUE
        DEPENDS scanner ${DEP_DEPENDS}
        CONFIGURE_COMMAND ${build_environment} ${MESON} setup --clearcache --reconfigure <BINARY_DIR> <SOURCE_DIR>
            ${meson_cross}
            --prefix=${MDW_DEPENDENCY_PREFIX} --libdir=lib --default-library=static
            --buildtype=release --wrap-mode=nofallback -Dauto_features=disabled ${DEP_OPTIONS}
        BUILD_COMMAND ${MESON} compile -C <BINARY_DIR> -j 4 ${DEP_TARGETS}
        INSTALL_COMMAND ${MESON} install -C <BINARY_DIR> --no-rebuild
            COMMAND ${CMAKE_COMMAND} -DNAME=${name} -DSOURCE=<SOURCE_DIR>
                -DDESTINATION=${MDW_DEPENDENCY_PREFIX}/licenses/${name}
                -P ${CMAKE_CURRENT_SOURCE_DIR}/collect-license.cmake)
endfunction()

ExternalProject_Add(ffi
    URL https://github.com/libffi/libffi/releases/download/v3.8.0/libffi-3.8.0.tar.gz
    URL_HASH SHA256=7da3e2d9a171eb0a038f592ecad3ff2bb2550f3496d87b3b29ad0cf4430c0db4
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    CONFIGURE_COMMAND ${build_environment} "CFLAGS=${CMAKE_C_FLAGS} -fPIC -DFFI_MMAP_EXEC_WRIT=1"
        sh <SOURCE_DIR>/configure --prefix=${MDW_DEPENDENCY_PREFIX}
        --host=aarch64-linux-android
        --disable-shared --enable-static --with-pic --disable-multi-os-directory --disable-docs
    BUILD_COMMAND ${MAKE} -j4
    INSTALL_COMMAND ${MAKE} install
        COMMAND ${CMAKE_COMMAND} -DNAME=ffi -DSOURCE=<SOURCE_DIR>
            -DDESTINATION=${MDW_DEPENDENCY_PREFIX}/licenses/ffi
            -P ${CMAKE_CURRENT_SOURCE_DIR}/collect-license.cmake)

mdw_meson(wayland ${wayland_url} ${wayland_hash}
    DEPENDS ffi OPTIONS -Dscanner=false -Dtests=false -Ddocumentation=false -Ddtd_validation=false)
mdw_meson(protocols
    https://gitlab.freedesktop.org/wayland/wayland-protocols/-/releases/1.49/downloads/wayland-protocols-1.49.tar.xz
    ec4c8f74942d6dff7ace8b4ce4764f0ef9ff618a935d974ea77edee2ad240b14
    OPTIONS -Dtests=false)
mdw_meson(pixman
    https://cairographics.org/releases/pixman-0.46.4.tar.gz
    d09c44ebc3bd5bee7021c79f922fe8fb2fb57f7320f55e97ff9914d2346a591c
    OPTIONS -Dlibpng=disabled -Dtests=disabled -Ddemos=disabled)
mdw_meson(drm
    https://dri.freedesktop.org/libdrm/libdrm-2.4.134.tar.xz
    ac5e74d157830eb8bee44c6a6bf3ad49774ef0dd2a72bdad74a8f20308b52a95
    OPTIONS -Dtests=false)
mdw_meson(xkbcommon
    https://github.com/xkbcommon/libxkbcommon/archive/refs/tags/xkbcommon-1.13.2.tar.gz
    acc4d5f7c3cbba5f9f8d08d8bdbeede84ecede46792f47929aa9321873385528
    TARGETS xkbcommon
    OPTIONS -Denable-x11=false -Denable-wayland=false -Denable-docs=false
        -Denable-tools=false -Denable-xkbregistry=false -Dxkb-config-root=/magicdesk/xkb)

ExternalProject_Add(wlroots
    URL https://gitlab.freedesktop.org/wlroots/wlroots/-/archive/0.18.2/wlroots-0.18.2.tar.bz2
    URL_HASH SHA256=a28061f7778f28f7be377fd4d6f0ebd58cb2a63b52460e9fde28e2ab43e80cab
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    DEPENDS wayland protocols pixman drm xkbcommon
    CONFIGURE_COMMAND ${build_environment}
        ${MESON} setup --clearcache --reconfigure <BINARY_DIR> <SOURCE_DIR> --prefix=${MDW_DEPENDENCY_PREFIX}
        ${meson_cross}
        --libdir=lib --default-library=static --buildtype=release --wrap-mode=nofallback
        -Dauto_features=disabled -Dbackends=[] -Drenderers=[] -Dxwayland=disabled
        -Dsession=disabled -Dexamples=false
    BUILD_COMMAND ${MESON} compile -C <BINARY_DIR> -j 4
    INSTALL_COMMAND ${MESON} install -C <BINARY_DIR>
        COMMAND ${CMAKE_COMMAND} -DNAME=wlroots -DSOURCE=<SOURCE_DIR>
            -DDESTINATION=${MDW_DEPENDENCY_PREFIX}/licenses/wlroots
            -P ${CMAKE_CURRENT_SOURCE_DIR}/collect-license.cmake)

ExternalProject_Add_Step(wlroots android_shm
    COMMAND ${CMAKE_COMMAND} -DSOURCE=<SOURCE_DIR>
        -DPATCH=${CMAKE_CURRENT_SOURCE_DIR}/wlroots-android-libs.patch
        -P ${CMAKE_CURRENT_SOURCE_DIR}/apply-patch.cmake
    COMMAND ${CMAKE_COMMAND} -DSOURCE=<SOURCE_DIR>
        -DPATCH=${CMAKE_CURRENT_SOURCE_DIR}/wlroots-android-shm.patch
        -P ${CMAKE_CURRENT_SOURCE_DIR}/apply-patch.cmake
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        ${CMAKE_CURRENT_SOURCE_DIR}/android-shm.c <SOURCE_DIR>/util/android-shm.c
    DEPENDEES patch
    DEPENDERS configure
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/android-shm.c
        ${CMAKE_CURRENT_SOURCE_DIR}/wlroots-android-libs.patch
        ${CMAKE_CURRENT_SOURCE_DIR}/wlroots-android-shm.patch
        ${CMAKE_CURRENT_SOURCE_DIR}/apply-patch.cmake)

ExternalProject_Get_Property(wlroots SOURCE_DIR)
enable_testing()
add_executable(wayland-shm-test ../tests/native/shm.c android-shm.c)
target_include_directories(wayland-shm-test PRIVATE ${SOURCE_DIR}/include)
target_compile_options(wayland-shm-test PRIVATE -UNDEBUG -Wall -Wextra -Werror)
add_dependencies(wayland-shm-test wlroots)
add_test(NAME wayland-android-shm COMMAND wayland-shm-test)

ExternalProject_Add(executor
    SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../src/main/cpp
    DOWNLOAD_COMMAND ""
    DEPENDS wlroots
    CMAKE_ARGS -DCMAKE_BUILD_TYPE=Release -DMDW_BUILD_JNI=ON
        -DCMAKE_C_COMPILER=${CMAKE_C_COMPILER} -DCMAKE_C_FLAGS=${CMAKE_C_FLAGS}
        -DCMAKE_EXE_LINKER_FLAGS=${CMAKE_EXE_LINKER_FLAGS}
        -DCMAKE_SHARED_LINKER_FLAGS=${CMAKE_EXE_LINKER_FLAGS}
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DANDROID_ABI=${ANDROID_ABI} -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DWAYLAND_SCANNER=${MDW_WAYLAND_SCANNER}
        -DPKG_CONFIG_EXECUTABLE=${MDW_PKG_CONFIG}
        -DMDW_DEPENDENCY_PREFIX=${MDW_DEPENDENCY_PREFIX}
        -DMDW_WLR_PROTOCOL_DIR=${SOURCE_DIR}/protocol
    BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --target magicdesk_wayland_executor wayland-client --parallel 4
    BUILD_ALWAYS TRUE
    INSTALL_COMMAND ${CMAKE_COMMAND} -E copy_if_different
        <BINARY_DIR>/libmagicdesk_wayland_executor.so ${MDW_DEPENDENCY_PREFIX}/lib/
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
            <BINARY_DIR>/libmagicdesk_wayland_client.so ${MDW_DEPENDENCY_PREFIX}/lib/
        COMMAND ${CMAKE_COMMAND} -DLIBRARY=${MDW_DEPENDENCY_PREFIX}/lib/libmagicdesk_wayland_client.so
            -DREADELF=${CMAKE_READELF}
            -P ${CMAKE_CURRENT_SOURCE_DIR}/verify-runtime.cmake
        COMMAND ${CMAKE_COMMAND} -DLIBRARY=${MDW_DEPENDENCY_PREFIX}/lib/libmagicdesk_wayland_executor.so
            -DREADELF=${CMAKE_READELF}
            -P ${CMAKE_CURRENT_SOURCE_DIR}/verify-runtime.cmake)
add_test(NAME wayland-runtime-dependencies COMMAND ${CMAKE_COMMAND}
    -DLIBRARY=${MDW_DEPENDENCY_PREFIX}/lib/libmagicdesk_wayland_executor.so
    -DREADELF=${CMAKE_READELF}
    -P ${CMAKE_CURRENT_SOURCE_DIR}/verify-runtime.cmake)
