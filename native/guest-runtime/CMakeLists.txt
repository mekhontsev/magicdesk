cmake_minimum_required(VERSION 3.24)
project(MagicDeskGuestRuntime LANGUAGES C ASM)

include(FetchContent)
FetchContent_Declare(sqlite
    URL https://www.sqlite.org/2025/sqlite-amalgamation-3500400.zip
    URL_HASH SHA256=1d3049dd0f830a025a53105fc79fd2ab9431aea99e137809d064d8ee8356b032
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
FetchContent_MakeAvailable(sqlite)
add_library(guest-sqlite STATIC ${sqlite_SOURCE_DIR}/sqlite3.c)
target_include_directories(guest-sqlite PUBLIC ${sqlite_SOURCE_DIR})
target_compile_definitions(guest-sqlite PRIVATE SQLITE_THREADSAFE=0 SQLITE_OMIT_LOAD_EXTENSION
    SQLITE_DQS=0 SQLITE_DEFAULT_MEMSTATUS=0 SQLITE_OMIT_DEPRECATED)
target_compile_options(guest-sqlite PRIVATE -O2 -fno-pie)

set(src ${CMAKE_CURRENT_SOURCE_DIR}/src)
set(raw ${src}/raw.c ${src}/memory.c ${src}/raw.S)
set(wire ${src}/fs_wire.c ${src}/event_wait.c)
set(inodes ${src}/inode_store.c ${src}/inode_db.c ${src}/inode_path.c
    ${src}/inode_directory.c ${src}/inode_import.c ${src}/inode_socket.c)

function(guest_executable name)
    add_executable(${name} ${ARGN})
    set_target_properties(${name} PROPERTIES OUTPUT_NAME libmagicdesk_guest_${name}.so)
    target_compile_options(${name} PRIVATE -std=c17 -O2 -Wall -Wextra -Werror -fno-pie -fno-builtin)
    target_link_options(${name} PRIVATE -static -Wl,-z,noexecstack -Wl,-z,max-page-size=16384)
endfunction()
function(guest_freestanding name)
    target_compile_options(${name} PRIVATE -ffreestanding -fno-stack-protector -fvisibility=hidden)
    target_link_options(${name} PRIVATE -nostdlib -Wl,-z,defs)
endfunction()

guest_executable(bootstrap ${src}/bootstrap.c ${src}/elf.c ${src}/exec.c
    ${src}/interception.c ${src}/interception_entry.S
    ${src}/file_calls.c ${src}/fs.c ${src}/proc_paths.c ${src}/proc_image.c ${src}/program_files.c
    ${src}/namespace.c ${src}/namespace_proc.c ${src}/socket_calls.c ${src}/socket_namespace.c ${src}/socket_routes.c ${src}/fd_metadata.c
    ${src}/fs_client.c ${wire} ${src}/raw.c ${src}/raw.S)
guest_freestanding(bootstrap)
target_compile_options(bootstrap PRIVATE -mgeneral-regs-only)
target_link_options(bootstrap PRIVATE -Wl,--image-base=0x2000000000)

guest_executable(supervisor ${src}/supervisor.c ${src}/process_owner.c ${src}/interception_stacks.c ${src}/guest_domain.c
    ${src}/guest_identity.c ${src}/elf_admission.c ${src}/elf.c ${src}/fs_client.c ${wire} ${raw}
    ${src}/fs_worker.c ${src}/fs_service.c ${src}/fs_engine.c ${src}/image_catalogue.c ${inodes}
    ${src}/namespace_broker.c ${src}/proc_paths.c)
target_compile_definitions(supervisor PRIVATE MD_NO_START MD_USE_LIBC)
target_link_libraries(supervisor PRIVATE guest-sqlite m)

guest_executable(run ${src}/namespace_run.c ${src}/process_owner.c ${src}/launch_environment.c ${src}/socket_routes.c ${src}/event_wait.c ${raw})
guest_freestanding(run)

guest_executable(service ${src}/service_main.c ${src}/fs_service.c ${src}/fs_engine.c ${src}/image_catalogue.c
    ${src}/elf_admission.c ${src}/guest_identity.c ${src}/elf.c ${src}/fs_client.c ${inodes} ${wire} ${raw})
target_compile_definitions(service PRIVATE MD_NO_START MD_USE_LIBC)
target_link_libraries(service PRIVATE guest-sqlite m)

install(TARGETS bootstrap supervisor run service RUNTIME DESTINATION lib)

option(MAGICDESK_GUEST_FIXTURES "Build standalone namespace benchmark" OFF)
if(MAGICDESK_GUEST_FIXTURES)
    guest_executable(benchmark_namespace ../guest-exec-lab/benchmark_namespace.c
        ${src}/fs_engine.c ${src}/image_catalogue.c ${src}/elf_admission.c
        ${src}/guest_identity.c ${src}/elf.c ${src}/fs_client.c ${inodes} ${wire} ${raw})
    target_compile_options(benchmark_namespace PRIVATE -iquote ${src})
    target_compile_definitions(benchmark_namespace PRIVATE MD_NO_START MD_USE_LIBC)
    target_compile_options(benchmark_namespace PRIVATE -UNDEBUG)
    target_link_libraries(benchmark_namespace PRIVATE guest-sqlite m)
endif()
