#!/usr/bin/env sh
set -eu
if [ "$#" -ne 1 ]; then
    printf 'Usage: %s PREPARED_DIRECTORY\n' "$0" >&2
    exit 2
fi
src=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
runtime="$src/../guest-runtime/src"
app="$src/../../app/src/main/java/io/github/mekhontsev/magicdesk"
work=$(CDPATH= cd -- "$1" && pwd)
sysroot=$work/sysroot
cc=${CC:-clang}
node "$src/test_signature.mjs" "$work"
mkdir -p "$work/bundle/rootfs" "$work/path-test"
"$cc" -iquote "$runtime" -std=c17 -O2 -Wall -Wextra -Werror -UNDEBUG \
    "$src/test_interception_stacks.c" "$runtime/interception_stacks.c" -o "$work/test-interception-stacks"
"$work/test-interception-stacks"
"$cc" -iquote "$runtime" -std=c17 -O2 -Wall -Wextra -Werror -UNDEBUG -fno-builtin -DMD_NO_START \
    "$src/test_socket_routes.c" "$runtime/socket_routes.c" "$runtime/raw.c" "$runtime/memory.c" "$runtime/raw.S" -o "$work/test-socket-routes"
"$work/test-socket-routes"
javac -d "$work/recipe-classes" "$src/GraphicalRecipe.java" "$app/LinuxGraphicalEnvironment.java" \
    "$app/GuestGraphicalConnection.java" "$app/GuestLaunchPlan.java" "$app/GuestEnvironment.java" \
    "$app/GraphicalProtocol.java" "$app/ShellCommandLine.java"
"$cc" --target=aarch64-linux-android34 -fno-termux-rpath -static \
    -iquote "$runtime" -std=c17 -O2 -Wall -Wextra -Werror -DMD_NO_START -DMD_USE_LIBC \
    "$src/test_completion.c" "$runtime/event_wait.c" "$runtime/raw.c" "$runtime/memory.c" "$runtime/raw.S" -o "$work/bundle/md-await-exit"
"$cc" -iquote "$runtime" -std=c17 -O2 -g -Wall -Wextra -Werror -UNDEBUG -fno-builtin -DMD_NO_START \
    "$src/test_paths.c" "$runtime/fs.c" "$runtime/proc_paths.c" "$runtime/raw.c" "$runtime/memory.c" "$runtime/raw.S" -o "$work/test-paths"
testroot=$(mktemp -d "$work/path-test/run.XXXXXX")
"$work/test-paths" "$testroot"
"$cc" -iquote "$runtime" -std=c17 -O2 -Wall -Wextra -Werror -UNDEBUG -fno-builtin -DMD_NO_START \
    "$src/test_elf.c" "$runtime/elf.c" "$runtime/raw.c" "$runtime/memory.c" "$runtime/raw.S" -o "$work/test-elf"
"$work/test-elf" "$testroot/elf"
"$cc" -iquote "$runtime" -std=c17 -O2 -g -Wall -Wextra -Werror -DMD_INODE_TESTING \
    "$runtime/inode_store.c" "$runtime/inode_db.c" "$runtime/inode_path.c" "$runtime/inode_directory.c" "$src/test_inodes.c" -lsqlite3 -o "$work/test-inodes"
inoderoot=$(mktemp -d "$work/path-test/inodes.XXXXXX")
timeout 45 "$work/test-inodes" "$inoderoot/store"
"$cc" -iquote "$runtime" -std=c17 -O2 -g -Wall -Wextra -Werror -DMD_INODE_TESTING \
    "$runtime/inode_store.c" "$runtime/inode_db.c" "$runtime/inode_path.c" "$runtime/inode_import.c" \
    "$src/test_import.c" -lsqlite3 -o "$work/test-import"
importroot=$(mktemp -d "$work/path-test/import.XXXXXX")
timeout 45 "$work/test-import" "$importroot/tests"
"$cc" -iquote "$runtime" -std=c17 -O2 -Wall -Wextra -Werror -Wframe-larger-than=16384 \
    -ffreestanding -fno-builtin -fno-stack-protector -DMD_NO_START -nostdlib -r \
    "$runtime/fs_client.c" "$runtime/fs_wire.c" "$runtime/event_wait.c" "$runtime/raw.c" "$runtime/memory.c" "$runtime/raw.S" \
    -o "$work/fs-client-freestanding.o"
if [ -n "$(llvm-nm -u "$work/fs-client-freestanding.o")" ]; then
    printf 'Filesystem RPC client has unresolved runtime dependencies\n' >&2
    exit 1
fi
printf 'PASS freestanding RPC client: no unresolved libc/SQLite/runtime dependencies\n'
"$cc" -iquote "$runtime" -std=c17 -O2 -g -Wall -Wextra -Werror -fno-builtin -DMD_NO_START \
    -DMD_INODE_TESTING -DMD_FS_TESTING "$src/test_rpc.c" "$runtime/fs_client.c" \
    "$runtime/image_catalogue.c" "$runtime/elf_admission.c" "$runtime/guest_identity.c" "$runtime/elf.c" \
    "$runtime/fs_wire.c" "$runtime/event_wait.c" "$runtime/fs_service.c" "$runtime/fs_engine.c" "$runtime/inode_store.c" "$runtime/inode_db.c" \
    "$runtime/inode_path.c" "$runtime/inode_directory.c" "$runtime/inode_socket.c" "$runtime/raw.c" "$runtime/memory.c" "$runtime/raw.S" -lsqlite3 -o "$work/test-rpc"
rpcroot=$(mktemp -d "$work/path-test/rpc.XXXXXX")
timeout 60 "$work/test-rpc" "$rpcroot/store"
"$cc" -iquote "$runtime" -std=c17 -O2 -g -Wall -Wextra -Werror -UNDEBUG -fno-builtin -DMD_NO_START \
    "$src/test_xattrs.c" "$runtime/fd_metadata.c" "$runtime/raw.c" "$runtime/memory.c" "$runtime/raw.S" -o "$work/test-xattrs"
xattrroot=$(mktemp -d "$work/path-test/xattrs.XXXXXX")
timeout 20 "$work/test-xattrs" "$xattrroot/files" native
spawn_lib=
case "$($cc -dumpmachine)" in *android*) spawn_lib=-landroid-spawn ;; esac
"$cc" -iquote "$runtime" -std=c17 -O2 -g -Wall -Wextra -Werror -UNDEBUG "$src/test_proc.c" $spawn_lib -o "$work/test-proc"
procroot=$(mktemp -d "$work/path-test/proc.XXXXXX")
timeout 30 "$work/test-proc" "$procroot/files" native
adapter_fixture() {
"$cc" -iquote "$runtime" -std=c17 -O2 -g -Wall -Wextra -Werror -UNDEBUG -fno-builtin -DMD_NO_START "$@" \
    "$runtime/socket_calls.c" "$runtime/socket_namespace.c" "$runtime/socket_routes.c" "$runtime/file_calls.c" "$runtime/fs.c" "$runtime/proc_paths.c" \
    "$runtime/namespace.c" "$runtime/namespace_proc.c" "$runtime/proc_image.c" "$runtime/fd_metadata.c" "$runtime/fs_client.c" "$runtime/fs_wire.c" \
    "$runtime/event_wait.c" "$runtime/raw.c" "$runtime/memory.c" "$runtime/raw.S"
}
adapter_fixture -DMD_SOCKET_DRIVER "$src/test_sockets.c" -o "$work/bundle/md-sockets-test"
adapter_fixture --target=aarch64-linux-android34 -fno-termux-rpath -static -DMD_USE_LIBC \
    "$src/test_command.c" "$runtime/exec.c" "$runtime/elf.c" "$runtime/program_files.c" -o "$work/bundle/md-command-test"
socketroot=$(mktemp -d "$work/path-test/sockets.XXXXXX")
timeout 45 "$work/bundle/md-sockets-test" native "$socketroot"
timeout 45 "$work/bundle/md-sockets-test" adapter "$socketroot"
adapter_fixture "$src/test_openat2.c" -o "$work/test-openat2"
openroot=$(mktemp -d "$work/path-test/openat2.XXXXXX")
timeout 20 "$work/test-openat2" "$openroot"
adapter_fixture "$src/test_process_context.c" -o "$work/test-process-context"
contextroot=$(mktemp -d "$work/path-test/context.XXXXXX")
timeout 20 "$work/test-process-context" "$contextroot/files"
guest_cc() {
    "$cc" -iquote "$runtime" --target=aarch64-linux-gnu --sysroot="$sysroot" -isystem "$sysroot/usr/include/aarch64-linux-gnu" \
        -fuse-ld=lld -std=c17 -O2 -g -Wall -Wextra -Werror -fPIC -nostdlib "$@" \
        -L"$sysroot/lib/aarch64-linux-gnu" -l:libc.so.6
}
cmake -S "$src/../guest-runtime" -B "$work/native-runtime" -G Ninja \
    -DCMAKE_C_COMPILER="$cc" -DCMAKE_ASM_COMPILER="$cc" \
    -DCMAKE_C_FLAGS=--target=aarch64-linux-android34 \
    -DCMAKE_ASM_FLAGS=--target=aarch64-linux-android34 \
    -DCMAKE_EXE_LINKER_FLAGS=-fno-termux-rpath
cmake --build "$work/native-runtime" --parallel 2
cp "$work/native-runtime"/libmagicdesk_guest_*.so "$work/bundle/"
"$cc" -iquote "$runtime" --target=aarch64-linux-android34 -fno-termux-rpath -static \
    -std=c17 -O2 -Wall -Wextra -Werror -UNDEBUG "$src/test_capabilities.c" -o "$work/bundle/md-capabilities-test"
"$cc" --target=aarch64-linux-android34 -fno-termux-rpath -static \
    -std=c17 -O2 -Wall -Wextra -Werror "$src/test_notification.c" -o "$work/bundle/md-notification-test"
"$cc" --target=aarch64-linux-android34 -fno-termux-rpath -static -DMD_NO_START -DMD_USE_LIBC \
    -std=c17 -O2 -Wall -Wextra -Werror "$src/test_notification_lifecycle.c" \
    "$runtime/event_wait.c" "$runtime/raw.c" "$runtime/memory.c" "$runtime/raw.S" -o "$work/bundle/md-notification-lifecycle-test"
"$cc" --target=aarch64-linux-android34 -fno-termux-rpath -static \
    -std=c17 -O2 -Wall -Wextra -Werror "$src/test_exec_policy.c" -o "$work/bundle/md-exec-policy-test"
sh "$src/build-libc-fixtures.sh" glibc "$sysroot" "$work"
"$cc" --target=aarch64-linux-gnu -fuse-ld=lld -nostdlib -static \
    -Wl,-T,"$src/freestanding.ld" "$src/test_freestanding.S" -o "$work/md-freestanding-fixture"
"$cc" --target=aarch64-linux-android34 -fno-termux-rpath -static \
    -std=c17 -O2 -Wall -Wextra -Werror -UNDEBUG "$src/test_ipc_launch.c" -o "$work/bundle/md-ipc-launch"
"$cc" --target=aarch64-linux-android34 -fno-termux-rpath -static \
    -std=c17 -O2 -Wall -Wextra -Werror "$src/trace_fault.c" -o "$work/bundle/md-trace-fault"
guest_cc -pie "$sysroot/usr/lib/aarch64-linux-gnu/Scrt1.o" "$sysroot/usr/lib/aarch64-linux-gnu/crti.o" \
    "$src/test_sockets.c" "$sysroot/usr/lib/aarch64-linux-gnu/crtn.o" \
    -Wl,--dynamic-linker=/lib/ld-linux-aarch64.so.1 -o "$work/md-socket-fixture"
guest_cc -pie "$sysroot/usr/lib/aarch64-linux-gnu/Scrt1.o" "$sysroot/usr/lib/aarch64-linux-gnu/crti.o" \
    "$src/test_proc.c" "$sysroot/usr/lib/aarch64-linux-gnu/crtn.o" \
    -Wl,--dynamic-linker=/lib/ld-linux-aarch64.so.1 -o "$work/md-proc-fixture"
"$cc" -iquote "$runtime" -std=c17 -O2 -g -Wall -Wextra -Werror -UNDEBUG -fno-builtin -DMD_NO_START \
    "$src/test_lifecycle.c" "$runtime/process_owner.c" "$runtime/event_wait.c" "$runtime/raw.c" "$runtime/memory.c" "$runtime/raw.S" \
    -o "$work/bundle/md-lifecycle-test"
guest_cc -pie -fno-builtin -DMD_NO_START -DMD_GUEST_LIFECYCLE \
    "$sysroot/usr/lib/aarch64-linux-gnu/Scrt1.o" "$sysroot/usr/lib/aarch64-linux-gnu/crti.o" \
    "$src/test_lifecycle.c" "$runtime/event_wait.c" "$runtime/raw.c" "$runtime/memory.c" "$runtime/raw.S" \
    "$sysroot/usr/lib/aarch64-linux-gnu/crtn.o" -Wl,--dynamic-linker=/lib/ld-linux-aarch64.so.1 \
    -o "$work/md-lifecycle-fixture"
guest_cc -pie -fno-builtin -DMD_NO_START "$sysroot/usr/lib/aarch64-linux-gnu/Scrt1.o" \
    "$sysroot/usr/lib/aarch64-linux-gnu/crti.o" "$src/test_xattrs.c" "$runtime/fd_metadata.c" "$runtime/raw.c" "$runtime/memory.c" "$runtime/raw.S" \
    "$sysroot/usr/lib/aarch64-linux-gnu/crtn.o" -Wl,--dynamic-linker=/lib/ld-linux-aarch64.so.1 -o "$work/md-xattrs-fixture"
guest_cc -pie "$sysroot/usr/lib/aarch64-linux-gnu/Scrt1.o" "$sysroot/usr/lib/aarch64-linux-gnu/crti.o" \
    "$src/test_files.c" "$sysroot/usr/lib/aarch64-linux-gnu/crtn.o" \
    -Wl,--dynamic-linker=/lib/ld-linux-aarch64.so.1 -o "$work/md-files-fixture"
guest_cc -pie "$sysroot/usr/lib/aarch64-linux-gnu/Scrt1.o" "$sysroot/usr/lib/aarch64-linux-gnu/crti.o" \
    "$src/test_namespace.c" "$sysroot/usr/lib/aarch64-linux-gnu/crtn.o" \
    -Wl,--dynamic-linker=/lib/ld-linux-aarch64.so.1 -o "$work/md-namespace-fixture"
guest_cc -pie -DMD_INODE_TESTING "$sysroot/usr/lib/aarch64-linux-gnu/Scrt1.o" \
    "$sysroot/usr/lib/aarch64-linux-gnu/crti.o" "$runtime/inode_store.c" "$runtime/inode_db.c" \
    "$runtime/inode_path.c" "$runtime/inode_directory.c" "$src/test_inodes.c" \
    "$sysroot/usr/lib/aarch64-linux-gnu/crtn.o" -Wl,--dynamic-linker=/lib/ld-linux-aarch64.so.1 \
    -L"$sysroot/usr/lib/aarch64-linux-gnu" -l:libsqlite3.so.0 -l:libm.so.6 \
    -o "$work/md-inodes-fixture"
guest_cc -pie -DMD_INODE_TESTING "$sysroot/usr/lib/aarch64-linux-gnu/Scrt1.o" \
    "$sysroot/usr/lib/aarch64-linux-gnu/crti.o" "$runtime/inode_store.c" "$runtime/inode_db.c" \
    "$runtime/inode_path.c" "$runtime/inode_import.c" "$src/test_import.c" \
    "$sysroot/usr/lib/aarch64-linux-gnu/crtn.o" -Wl,--dynamic-linker=/lib/ld-linux-aarch64.so.1 \
    -L"$sysroot/usr/lib/aarch64-linux-gnu" -l:libsqlite3.so.0 -l:libm.so.6 \
    -o "$work/md-import-fixture"
guest_cc -pie -fno-builtin -DMD_NO_START -DMD_INODE_TESTING -DMD_FS_TESTING \
    "$sysroot/usr/lib/aarch64-linux-gnu/Scrt1.o" "$sysroot/usr/lib/aarch64-linux-gnu/crti.o" \
    "$src/test_rpc.c" "$runtime/fs_client.c" "$runtime/fs_wire.c" "$runtime/event_wait.c" "$runtime/fs_service.c" "$runtime/fs_engine.c" \
    "$runtime/image_catalogue.c" "$runtime/elf_admission.c" "$runtime/guest_identity.c" "$runtime/elf.c" \
    "$runtime/inode_store.c" "$runtime/inode_db.c" "$runtime/inode_path.c" "$runtime/inode_directory.c" "$runtime/inode_socket.c" "$runtime/raw.c" "$runtime/memory.c" "$runtime/raw.S" \
    "$sysroot/usr/lib/aarch64-linux-gnu/crtn.o" -Wl,--dynamic-linker=/lib/ld-linux-aarch64.so.1 \
    -L"$sysroot/usr/lib/aarch64-linux-gnu" -l:libsqlite3.so.0 -l:libm.so.6 \
    -o "$work/md-rpc-fixture"
cp -a "$sysroot/lib" "$sysroot/bin" "$work/bundle/rootfs/"
for directory in etc sbin usr/share usr/sbin usr/libexec var; do
    if [ -d "$sysroot/$directory" ]; then
        mkdir -p "$work/bundle/rootfs/$directory"
        cp -a "$sysroot/$directory/." "$work/bundle/rootfs/$directory/"
    fi
done
mkdir -p "$work/bundle/rootfs/usr/lib" "$work/bundle/rootfs/usr/bin" \
    "$work/bundle/rootfs/etc" "$work/bundle/rootfs/tmp" "$work/bundle/rootfs/var/log" \
    "$work/bundle/rootfs/dev/shm"
cp -a "$sysroot/usr/lib/." "$work/bundle/rootfs/usr/lib/"
cp -a "$sysroot/usr/bin/." "$work/bundle/rootfs/usr/bin/"
cp "$work/md-fixture" "$work/bundle/rootfs/usr/bin/"
cp "$work/md-xattrs-fixture" "$work/bundle/rootfs/usr/bin/"
cp "$work/md-lifecycle-fixture" "$work/bundle/rootfs/usr/bin/"
cp "$work/md-proc-fixture" "$work/bundle/rootfs/usr/bin/"
cp "$work/md-socket-fixture" "$work/bundle/rootfs/usr/bin/"
cp "$work/md-exec-fixture" "$work/bundle/rootfs/usr/bin/"
cp "$work/md-execfd-fixture" "$work/bundle/rootfs/usr/bin/"
cp "$work/md-ipc-fixture" "$work/bundle/rootfs/usr/bin/"
cp "$work/md-files-fixture" "$work/bundle/rootfs/usr/bin/"
cp "$work/md-inodes-fixture" "$work/bundle/rootfs/usr/bin/"
cp "$work/md-import-fixture" "$work/bundle/rootfs/usr/bin/"
cp "$work/md-namespace-fixture" "$work/bundle/rootfs/usr/bin/"
cp "$work/md-rpc-fixture" "$work/bundle/rootfs/usr/bin/"
cp "$work/md-fixture.so" "$work/bundle/rootfs/usr/lib/"
if [ -f "$sysroot/usr/include/X11/Xlib.h" ]; then
    guest_cc -pie "$sysroot/usr/lib/aarch64-linux-gnu/Scrt1.o" \
        "$sysroot/usr/lib/aarch64-linux-gnu/crti.o" "$src/test_x11_desktop.c" \
        "$sysroot/usr/lib/aarch64-linux-gnu/crtn.o" -Wl,--dynamic-linker=/lib/ld-linux-aarch64.so.1 \
        -L"$sysroot/usr/lib/aarch64-linux-gnu" -l:libX11.so.6 \
        -o "$work/bundle/rootfs/usr/bin/md-x11-desktop"
fi
if [ -f "$sysroot/usr/include/dbus-1.0/dbus/dbus.h" ]; then
    guest_cc -pie -I"$sysroot/usr/include/dbus-1.0" \
        -I"$sysroot/usr/lib/aarch64-linux-gnu/dbus-1.0/include" \
        "$sysroot/usr/lib/aarch64-linux-gnu/Scrt1.o" "$sysroot/usr/lib/aarch64-linux-gnu/crti.o" \
        "$src/test_xfce_session.c" "$sysroot/usr/lib/aarch64-linux-gnu/crtn.o" \
        -Wl,--dynamic-linker=/lib/ld-linux-aarch64.so.1 \
        -L"$sysroot/usr/lib/aarch64-linux-gnu" -l:libdbus-1.so.3 \
        -o "$work/bundle/rootfs/usr/bin/md-xfce-session"
fi
if [ -f "$sysroot/usr/include/wayland-client.h" ]; then
    protocol="$sysroot/usr/share/wayland-protocols/stable/xdg-shell/xdg-shell.xml"
    wayland-scanner client-header "$protocol" "$work/xdg-shell-client-protocol.h"
    wayland-scanner private-code "$protocol" "$work/xdg-shell-protocol.c"
    guest_cc -pie -I"$work" "$sysroot/usr/lib/aarch64-linux-gnu/Scrt1.o" \
        "$sysroot/usr/lib/aarch64-linux-gnu/crti.o" "$src/test_wayland.c" "$work/xdg-shell-protocol.c" \
        "$sysroot/usr/lib/aarch64-linux-gnu/crtn.o" -Wl,--dynamic-linker=/lib/ld-linux-aarch64.so.1 \
        -L"$sysroot/usr/lib/aarch64-linux-gnu" -l:libwayland-client.so.0 \
        -o "$work/bundle/rootfs/usr/bin/md-wayland-fixture"
fi
cp "$src/fixtures/md-guest-fixture" "$work/bundle/rootfs/etc/"
cp "$src/fixtures/md-script" "$work/bundle/rootfs/usr/bin/"
chmod 755 "$work/bundle/rootfs/usr/bin/md-script"
cp "$src/fixtures/md-prepare-applications" "$work/bundle/rootfs/usr/bin/"
chmod 755 "$work/bundle/rootfs/usr/bin/md-prepare-applications"
if [ -f "$sysroot/usr/bin/dpkg" ]; then
    mkdir -p "$work/package-fixture"
    cp -a "$src/fixtures/package/." "$work/package-fixture/"
    chmod 755 "$work/package-fixture/DEBIAN" "$work/package-fixture/DEBIAN/postinst" "$work/package-fixture/DEBIAN/postrm"
    dpkg-deb --root-owner-group -Zxz --build "$work/package-fixture" \
        "$work/bundle/rootfs/tmp/md-package-fixture.deb"
    mkdir -p "$work/package-update"
    cp -a "$src/fixtures/package-update/." "$work/package-update/"
    cp "$src/fixtures/package/DEBIAN/postrm" "$work/package-update/DEBIAN/"
    chmod 755 "$work/package-update/DEBIAN" "$work/package-update/DEBIAN/postinst" "$work/package-update/DEBIAN/postrm"
    dpkg-deb --root-owner-group -Zxz --build "$work/package-update" \
        "$work/bundle/rootfs/tmp/md-package-update.deb"
    cp "$work"/gzip_*.deb "$work/bundle/rootfs/tmp/md-hardlinks.deb"
fi
cp "$work/manifest.json" "$work/bundle/"
tar -C "$work/bundle" -czf "$work/bundle.tar.gz" .
printf 'Built %s\n' "$work/bundle.tar.gz"
