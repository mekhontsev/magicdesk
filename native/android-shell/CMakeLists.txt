cmake_minimum_required(VERSION 3.24)
project(MagicDeskAndroidShell LANGUAGES C)

include(FetchContent)
# Debian mirrors the byte-identical upstream release archive, without Debian patches.
FetchContent_Declare(mksh
    URL https://deb.debian.org/debian/pool/main/m/mksh/mksh_59c.orig.tar.gz
    URL_HASH SHA256=77ae1665a337f1c48c61d6b961db3e52119b38e58884d1c89684af31f87bc506
    SOURCE_SUBDIR magicdesk-no-cmake
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
FetchContent_MakeAvailable(mksh)

# Use upstream's generator, not a second implementation of its option tables.
if(DEFINED ENV{MAGICDESK_BUILD_SHELL} AND NOT "$ENV{MAGICDESK_BUILD_SHELL}" STREQUAL "")
    file(TO_CMAKE_PATH "$ENV{MAGICDESK_BUILD_SHELL}" MKSH_BUILD_SHELL)
    if(NOT IS_ABSOLUTE "${MKSH_BUILD_SHELL}" OR NOT EXISTS "${MKSH_BUILD_SHELL}"
            OR IS_DIRECTORY "${MKSH_BUILD_SHELL}")
        message(FATAL_ERROR "MAGICDESK_BUILD_SHELL must name an existing absolute host shell path")
    endif()
endif()
find_program(MKSH_BUILD_SHELL NAMES sh bash REQUIRED)
foreach(table sh_flags rlimits ulimits)
    add_custom_command(OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/${table}.gen"
        COMMAND "${CMAKE_COMMAND}" "-DSHELL=${MKSH_BUILD_SHELL}"
            "-DSOURCE=${mksh_SOURCE_DIR}" "-DOUTPUT=${CMAKE_CURRENT_BINARY_DIR}"
            "-DTABLE=${table}" -P "${CMAKE_CURRENT_SOURCE_DIR}/generate-table.cmake"
        DEPENDS "${mksh_SOURCE_DIR}/Build.sh" "${mksh_SOURCE_DIR}/${table}.opt"
            "${CMAKE_CURRENT_SOURCE_DIR}/generate-table.cmake"
        WORKING_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}"
        VERBATIM)
    list(APPEND generated_tables "${CMAKE_CURRENT_BINARY_DIR}/${table}.gen")
endforeach()

set(source_names lalloc.c edit.c eval.c exec.c expr.c funcs.c histrap.c jobs.c
    lex.c main.c misc.c shf.c syn.c tree.c ulimit.c var.c)
foreach(name IN LISTS source_names)
    list(APPEND sources "${mksh_SOURCE_DIR}/${name}")
endforeach()
add_executable(android_shell ${sources} ${generated_tables})
set_target_properties(android_shell PROPERTIES OUTPUT_NAME libmagicdesk_mksh.so)
target_include_directories(android_shell PRIVATE "${mksh_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}")
target_compile_options(android_shell PRIVATE -Os -fPIE -fwrapv -fno-strict-aliasing
    -fno-asynchronous-unwind-tables -Wall -Wno-deprecated-declarations)
target_link_options(android_shell PRIVATE -pie -Wl,-z,noexecstack -Wl,-z,max-page-size=16384 -Wl,-s)

# Fixed Android/Bionic API-34+ configuration, based on AOSP's mksh Android.bp.
# No configure-time target executables, platform-private libraries or source patches.
# ENOEXEC text scripts re-enter this executable, never a firmware shell.
target_compile_definitions(android_shell PRIVATE
    MKSH_BUILDSH MKSH_BUILD_R=593 _GNU_SOURCE SETUID_CAN_FAIL_WITH_EAGAIN
    DEBUG_LEAKS MKSH_ASSUME_UTF8 MKSH_DONT_EMIT_IDSTRING
    KSH_VERSIONNAME_VENDOR_EXT=" MagicDesk"
    MKSH_DEFAULT_PROFILEDIR="/system/etc" MKSHRC_PATH="~/.mkshrc"
    MKSH_DEFAULT_EXECSHELL="/proc/self/exe" MKSH_DEFAULT_TMPDIR="/data/local/tmp/magicdesk/cache/tmp"
    MKSH_DEFPATH_OVERRIDE="/system/bin:/system/xbin"
    HAVE_STRING_POOLING=2
    HAVE_ATTRIBUTE_BOUNDED=0 HAVE_ATTRIBUTE_FORMAT=1 HAVE_ATTRIBUTE_NORETURN=1
    HAVE_ATTRIBUTE_PURE=1 HAVE_ATTRIBUTE_UNUSED=1 HAVE_ATTRIBUTE_USED=1
    HAVE_SYS_TIME_H=1 HAVE_TIME_H=1 HAVE_BOTH_TIME_H=1 HAVE_SYS_BSDTYPES_H=0
    HAVE_SYS_FILE_H=1 HAVE_SYS_MKDEV_H=0 HAVE_SYS_MMAN_H=1 HAVE_SYS_PARAM_H=1
    HAVE_SYS_RESOURCE_H=1 HAVE_SYS_SELECT_H=1 HAVE_SYS_SYSMACROS_H=1
    HAVE_BSTRING_H=0 HAVE_GRP_H=1 HAVE_IO_H=0 HAVE_LIBGEN_H=1 HAVE_LIBUTIL_H=0
    HAVE_PATHS_H=1 HAVE_STDINT_H=1 HAVE_STRINGS_H=1 HAVE_TERMIOS_H=1
    HAVE_ULIMIT_H=0 HAVE_VALUES_H=0
    HAVE_CAN_INTTYPES=1 HAVE_CAN_UCBINTS=1 HAVE_CAN_INT8TYPE=1 HAVE_CAN_UCBINT8=1
    HAVE_SIG_T=1 HAVE_SYS_ERRLIST=0 HAVE_SYS_SIGNAME=1 HAVE_SYS_SIGLIST=1
    HAVE_FLOCK=1 HAVE_LOCK_FCNTL=1 HAVE_RLIMIT=1 HAVE_RLIM_T=1
    HAVE_GETRUSAGE=1 HAVE_GETSID=1 HAVE_GETTIMEOFDAY=1 HAVE_KILLPG=1 HAVE_MEMMOVE=1
    HAVE_MKNOD=0 HAVE_MMAP=1 HAVE_FTRUNCATE=1 HAVE_NICE=1 HAVE_REVOKE=0
    HAVE_SETLOCALE_CTYPE=1 HAVE_LANGINFO_CODESET=1 HAVE_SELECT=1
    HAVE_SETRESUGID=1 HAVE_SETGROUPS=1 HAVE_STRERROR=1 HAVE_STRSIGNAL=1 HAVE_STRLCPY=1
    HAVE_FLOCK_DECL=1 HAVE_REVOKE_DECL=1 HAVE_SYS_ERRLIST_DECL=0 HAVE_SYS_SIGLIST_DECL=1
    HAVE_ST_MTIM=1 HAVE_ST_MTIMENSEC=0 HAVE_PERSISTENT_HISTORY=1)

# Include embedded notices too (expr.c contains Unicode's notice after its header).
file(GLOB notice_sources "${mksh_SOURCE_DIR}/*.c" "${mksh_SOURCE_DIR}/*.h" "${mksh_SOURCE_DIR}/*.opt")
set(notices "MirBSD Korn Shell R59c, unmodified upstream sources\n\n")
foreach(source IN LISTS notice_sources)
    file(READ "${source}" contents)
    string(REPLACE ";" "\\;" contents "${contents}")
    string(REGEX MATCHALL "/\\*([^*]|\\*+[^*/])*Copyright([^*]|\\*+[^*/])*\\*+/" file_notices "${contents}")
    if(NOT file_notices)
        message(FATAL_ERROR "Missing mksh copyright notice: ${source}")
    endif()
    get_filename_component(name "${source}" NAME)
    string(APPEND notices "=== ${name} ===\n")
    foreach(notice IN LISTS file_notices)
        string(APPEND notices "${notice}\n\n")
    endforeach()
endforeach()
file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/licenses")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/licenses/NOTICE.txt" "${notices}")
