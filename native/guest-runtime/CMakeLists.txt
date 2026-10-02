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
set(mounts ${src}/fs_mounts.c ${src}/fs_mount_path.c ${src}/fs_mount_operations.c ${src}/fs_mount_table.c)
set(inodes ${src}/inode_store.c ${src}/inode_db.c ${src}/inode_events.c ${src}/inode_watch.c ${src}/watch_queue.c ${src}/inode_path.c
    ${src}/inode_directory.c ${src}/inode_import.c ${src}/inode_socket.c ${src}/inode_backing.c ${src}/inode_snapshot.c ${src}/inode_fifo.c ${src}/file_open.c
    ${src}/guest_identity.c ${src}/credential_registry.c ${src}/inode_metadata.c ${src}/inode_capability.c ${src}/inode_acl.c ${src}/posix_acl.c)
set(namespace_engine ${src}/fs_engine.c ${src}/ipc_credentials.c)

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
    ${src}/interception.c ${src}/interception_entry.S ${src}/watch_calls.c ${src}/shm_calls.c
    ${src}/file_calls.c ${src}/fs.c ${src}/proc_paths.c ${src}/proc_image.c ${src}/program_files.c
    ${src}/namespace.c ${src}/namespace_proc.c ${src}/socket_calls.c ${src}/socket_namespace.c ${src}/socket_identity.c ${src}/socket_ancillary.c ${src}/socket_routes.c ${src}/fd_metadata.c
    ${src}/fs_client.c ${src}/file_open.c ${wire} ${src}/raw.c ${src}/raw.S)
guest_freestanding(bootstrap)
target_compile_options(bootstrap PRIVATE -mgeneral-regs-only)
target_link_options(bootstrap PRIVATE -Wl,--image-base=0x2000000000)

guest_executable(supervisor ${src}/supervisor.c ${src}/process_owner.c ${src}/interception_stacks.c ${src}/guest_domain.c
    ${src}/watch_activation.c ${src}/sysv_shm.c ${src}/guest_debugger.c ${src}/process_image_view.c
    ${src}/guest_identity.c ${src}/guest_accounts.c ${src}/elf_admission.c ${src}/elf.c ${src}/fs_client.c ${wire} ${raw}
    ${src}/fs_worker.c ${src}/fs_service.c ${namespace_engine} ${src}/image_catalogue.c ${inodes} ${mounts}
    ${src}/namespace_broker.c ${src}/watch_broker.c ${src}/proc_paths.c)
target_compile_definitions(supervisor PRIVATE MD_NO_START MD_USE_LIBC)
target_link_libraries(supervisor PRIVATE guest-sqlite m)

guest_executable(run ${src}/namespace_run.c ${src}/process_owner.c ${src}/launch_environment.c ${src}/socket_routes.c ${src}/event_wait.c ${raw})
guest_freestanding(run)

guest_executable(service ${src}/service_main.c ${src}/fs_service.c ${namespace_engine} ${src}/image_catalogue.c
    ${src}/elf_admission.c ${src}/guest_identity.c ${src}/elf.c ${src}/fs_client.c ${inodes} ${wire} ${raw} ${mounts})
target_compile_definitions(service PRIVATE MD_NO_START MD_USE_LIBC)
target_link_libraries(service PRIVATE guest-sqlite m)

include(image-dependencies.cmake)
guest_executable(image ${src}/image_main.c ${src}/image_io.c ${src}/image_json.c
    ${src}/image_oci.c ${src}/image_layer.c ${src}/image_pool.c ${src}/image_backup.c ${src}/image_maintenance.c
    ${src}/image_acl.c ${src}/image_publish.c ${src}/image_launch.c ${src}/image_prepare.c ${src}/image_export.c ${src}/guest_accounts.c ${src}/socket_routes.c ${inodes}
    ${namespace_engine} ${mounts} ${src}/image_catalogue.c ${src}/elf_admission.c ${src}/guest_identity.c ${src}/proc_paths.c
    ${src}/elf.c ${src}/fs_client.c ${wire} ${raw})
target_compile_definitions(image PRIVATE MD_NO_START MD_USE_LIBC)
target_compile_options(image PRIVATE -iquote ${src})
target_include_directories(image PRIVATE ${guest_archive_SOURCE_DIR}/libarchive)
target_link_libraries(image PRIVATE guest-sqlite guest-sha256 archive_static m)

install(TARGETS bootstrap supervisor run service image RUNTIME DESTINATION lib)

option(MAGICDESK_GUEST_FIXTURES "Build standalone native fixtures" OFF)
if(MAGICDESK_GUEST_FIXTURES)
    target_compile_definitions(image PRIVATE MD_INODE_TESTING)
    foreach(name inodes import rpc snapshot mounts credentials)
        guest_executable(test_${name} ../guest-exec-lab/test_${name}.c ${inodes})
        target_compile_options(test_${name} PRIVATE -iquote ${src} -UNDEBUG)
        target_compile_definitions(test_${name} PRIVATE MD_INODE_TESTING)
        target_link_libraries(test_${name} PRIVATE guest-sqlite m)
    endforeach()
    target_sources(test_credentials PRIVATE ${src}/guest_accounts.c ${namespace_engine} ${mounts}
        ${src}/image_catalogue.c ${src}/elf_admission.c ${src}/elf.c ${src}/fs_client.c ${wire} ${raw})
    target_compile_definitions(test_credentials PRIVATE MD_NO_START MD_USE_LIBC)
    target_sources(test_rpc PRIVATE ${src}/fs_client.c ${src}/fs_service.c ${namespace_engine} ${mounts}
        ${src}/image_catalogue.c ${src}/elf_admission.c ${src}/guest_identity.c ${src}/elf.c ${wire} ${raw})
    target_compile_definitions(test_rpc PRIVATE MD_FS_TESTING MD_NO_START MD_USE_LIBC)
    target_sources(test_mounts PRIVATE ${namespace_engine} ${mounts} ${src}/image_catalogue.c
        ${src}/elf_admission.c ${src}/guest_identity.c ${src}/elf.c ${src}/fs_client.c ${wire} ${raw})
    target_compile_definitions(test_mounts PRIVATE MD_NO_START MD_USE_LIBC)
    guest_executable(watch_guest ../guest-exec-lab/test_watch_guest.c)
    guest_executable(credentials_guest ../guest-exec-lab/test_credentials_guest.c)
    guest_executable(guest_interfaces ../guest-exec-lab/test_guest_interfaces.c)
    guest_executable(service_runtime ../guest-exec-lab/test_service_runtime.c)
    target_compile_options(service_runtime PRIVATE -UNDEBUG)
    guest_executable(ipc_credentials_guest ../guest-exec-lab/test_ipc_credentials_guest.c)
    guest_executable(ipc_credentials_launch ../guest-exec-lab/test_ipc_credentials_launch.c)
    target_compile_options(watch_guest PRIVATE -UNDEBUG)
    guest_executable(watch_launch ../guest-exec-lab/test_watch_launch.c)
    guest_executable(watch_store ../guest-exec-lab/test_watch_store.c ${inodes})
    target_compile_options(watch_store PRIVATE -iquote ${src})
    target_compile_definitions(watch_store PRIVATE MD_INODE_TESTING)
    target_link_libraries(watch_store PRIVATE guest-sqlite m)
    guest_executable(watch_queue ../guest-exec-lab/test_watch_queue.c ${src}/watch_queue.c)
    guest_executable(watch_read ../guest-exec-lab/test_watch_read.c
        ../guest-exec-lab/test_watch_read.S ${src}/watch_queue.c ${src}/event_wait.c ${raw})
    target_compile_definitions(watch_read PRIVATE MD_NO_START MD_USE_LIBC)
    guest_executable(watch_activation ../guest-exec-lab/test_watch_activation.c ${src}/event_wait.c ${raw})
    target_compile_definitions(watch_activation PRIVATE MD_NO_START MD_USE_LIBC)
    guest_executable(benchmark_namespace ../guest-exec-lab/benchmark_namespace.c
        ${namespace_engine} ${src}/image_catalogue.c ${src}/elf_admission.c ${mounts}
        ${src}/guest_identity.c ${src}/elf.c ${src}/fs_client.c ${inodes} ${wire} ${raw})
    target_compile_options(benchmark_namespace PRIVATE -iquote ${src})
    target_compile_definitions(benchmark_namespace PRIVATE MD_NO_START MD_USE_LIBC)
    target_compile_options(benchmark_namespace PRIVATE -UNDEBUG)
    target_link_libraries(benchmark_namespace PRIVATE guest-sqlite m)
endif()
