#define _GNU_SOURCE
#include "bootstrap.h"
#include "elf.h"
#include "namespace.h"
#include "raw.h"
#include "launch_identity.h"
#include "socket_routes.h"
#include "linux_abi.h"
#include "proc_paths.h"
#include "interception.h"
#include <errno.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <signal.h>

struct md_fs md_files;
char md_bootstrap[PATH_MAX];
char md_executable[PATH_MAX];
static struct md_process_image process_image;
static struct md_socket_routes connections;

static int descriptor_number(const char *number) {
    if (!*number) md_die("missing image descriptor", -EINVAL);
    unsigned long value = 0;
    for (; *number; ++number) {
        if (*number < '0' || *number > '9' || value > (2147483647UL - (*number - '0')) / 10)
            md_die("invalid image descriptor", -EINVAL);
        value = value * 10 + (unsigned)(*number - '0');
    }
    return (int)value;
}

void md_boot(uintptr_t *kernel_stack) {
    size_t argc = *kernel_stack;
    char **argv = (char **)(kernel_stack + 1), **env = argv + argc + 1;
    size_t envc = 0; while (env[envc]) ++envc;
    Elf64_auxv_t *aux = (Elf64_auxv_t *)(env + envc + 1);
    size_t auxc = 0;
    while (aux[auxc].a_type) {
        if (aux[auxc].a_type == AT_PAGESZ) md_page_size = aux[auxc].a_un.a_val;
        ++auxc;
    }
    if (md_page_size < 4096 || md_page_size > 65536 || (md_page_size & (md_page_size - 1)))
        md_die("invalid page size", -EINVAL);
    if (!md_launch_identity(RAW0(getuid), RAW0(geteuid), RAW0(getgid), RAW0(getegid)))
        md_die("guest runtime requires the selected shell or root identity", -EACCES);
    if (argc == 2 && md_equal(argv[1], "--probe")) {
        long process = RAW2(pidfd_open, RAW0(getpid), 0);
        if (process < 0) md_die("probe pidfd_open", process);
        long check = RAW4(pidfd_send_signal, process, 0, 0, 0);
        RAW1(close, process);
        if (check < 0) md_die("probe pidfd_send_signal", check);
        check = RAW2(prctl, PR_SET_CHILD_SUBREAPER, 1);
        if (check < 0) md_die("probe child subreaper", check);
        check = RAW4(faccessat2, AT_FDCWD, "/proc/self/exe", 1, MD_AT_EACCESS);
        if (check < 0) md_die("probe faccessat2", check);
        long memory = RAW6(mmap, 0, md_page_size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (memory < 0) md_die("probe memory", memory);
        check = RAW3(mprotect, memory, md_page_size, PROT_READ | PROT_EXEC);
        RAW2(munmap, memory, md_page_size);
        if (check < 0) md_die("probe executable mappings", check);
        check = md_interception_install(0);
        if (check < 0) md_die("probe syscall adapter", check);
        register long number __asm__("x8") = SYS_fstat;
        register long result __asm__("x0") = -1;
        register long output __asm__("x1") = 0;
        __asm__ volatile("svc #0" : "+r"(result) : "r"(number), "r"(output) : "memory", "cc");
        if (result != -EBADF) md_die("probe external syscall dispatch", -EIO);
        const char message[] = "guest-runtime: aarch64 shell syscall adapter ready\n";
        RAW3(write, 1, message, sizeof(message) - 1);
        RAW1(exit_group, 0);
    }
    int inherited = argc > 1 && md_equal(argv[1], "--resume");
    unsigned root_arg = inherited ? 2 : 1;
    int program_fd = -1, interpreter_fd = -1;
    const char *execfn = NULL;
    const char *object = "";
    if (inherited && root_arg + 1 < argc && md_equal(argv[root_arg], "--program-fd")) {
        program_fd = descriptor_number(argv[root_arg + 1]);
        root_arg += 2;
    }
    if (inherited && root_arg + 1 < argc && md_equal(argv[root_arg], "--program-object")) {
        object = argv[root_arg + 1];
        if (md_length(object) != 32) md_die("invalid image identity", -EINVAL);
        for (unsigned i = 0; i < 32; i++)
            if (!((object[i] >= '0' && object[i] <= '9') || (object[i] >= 'a' && object[i] <= 'f')))
                md_die("invalid image identity", -EINVAL);
        root_arg += 2;
    }
    if (inherited && root_arg + 1 < argc && md_equal(argv[root_arg], "--interpreter-fd")) {
        interpreter_fd = descriptor_number(argv[root_arg + 1]);
        root_arg += 2;
    }
    if (inherited && root_arg + 1 < argc && md_equal(argv[root_arg], "--execfn")) {
        execfn = argv[root_arg + 1];
        if (!*execfn) md_die("missing executable invocation", -EINVAL);
        root_arg += 2;
    }
    const char *cwd = NULL;
    if (!inherited && argc > 3 && md_equal(argv[root_arg], "--cwd")) {
        cwd = argv[root_arg + 1];
        root_arg += 2;
        if (cwd[0] != '/') md_die("guest cwd must be absolute", -EINVAL);
    }
    while (root_arg < argc && (md_equal(argv[root_arg], "--socket-path") || md_equal(argv[root_arg], "--socket-abstract"))) {
        if (root_arg + 2 >= argc) md_die("missing socket route", -EINVAL);
        long result = md_socket_route_add(&connections, argv[root_arg], argv[root_arg + 1], argv[root_arg + 2]);
        if (result < 0) md_die("invalid socket route", result);
        root_arg += 3;
    }
    int namespace = argc > root_arg && md_equal(argv[root_arg], "--namespace");
    if (namespace) ++root_arg;
    if (argc < root_arg + (inherited ? 3U : 2U))
        md_die("usage: md-bootstrap ROOTFS | --namespace ENDPOINT PROGRAM [ARGS]", -EINVAL);
    long r = RAW4(readlinkat, AT_FDCWD, "/proc/self/exe", md_bootstrap, sizeof(md_bootstrap) - 1);
    if (r < 0) md_die("read bootstrap identity", r);
    md_bootstrap[r] = 0;
    if (namespace) {
        if (md_copy(md_files.endpoint,sizeof(md_files.endpoint),argv[root_arg]) || !md_files.endpoint[0])
            md_die("invalid namespace endpoint",-EINVAL);
        if (!inherited) {
            long root=md_namespace_open(&md_files,AT_FDCWD,cwd ? cwd : "/",O_PATH|O_DIRECTORY|O_CLOEXEC,0);
            if(root<0) md_die("open namespace root",root);
            r=RAW1(fchdir,root); RAW1(close,root);
            if(r<0) md_die("enter namespace root",r);
        }
    } else {
    if (argv[root_arg][0] != '/' || md_copy(md_files.root, sizeof(md_files.root), argv[root_arg]))
        md_die("rootfs must be an absolute path", -EINVAL);
    size_t root_len = md_length(md_files.root);
    while (root_len > 1 && md_files.root[root_len - 1] == '/') md_files.root[--root_len] = 0;
    if (root_len == 1) md_die("host root is not a guest rootfs", -EINVAL);
    if (!inherited && (r = RAW1(chdir, md_files.root)) < 0) md_die("enter rootfs", r);
    }
    r = md_interception_install(inherited);
    if (r < 0) md_die("install syscall adapter", r);
    struct md_command command;
    if (!execfn) execfn = argv[root_arg + 1];
    if (inherited) {
        if (program_fd < 0 || program_fd == interpreter_fd) md_die("invalid prepared images", -EINVAL);
        if (!!namespace != !!*object) md_die("missing prepared object identity", -EINVAL);
        memset(command.object, 0, sizeof(command.object));
        md_copy(command.object, sizeof(command.object), object);
        command.fd = program_fd; command.interpreter_fd = interpreter_fd;
        command.argc = argc - root_arg - 2;
        if (command.argc >= MD_ARG_MAX) md_die("guest arguments", -E2BIG);
        memcpy(command.argv, argv + root_arg + 2, (command.argc + 1) * sizeof(char *));
        r = md_copy(command.path, sizeof(command.path), argv[root_arg + 1]);
    } else r = md_command_prepare(&md_files, &command, argv[root_arg + 1], argv + root_arg + 1, NULL, 0);
    if (r < 0) md_die("prepare guest program", r);
    r = md_copy(md_executable, sizeof(md_executable), command.path);
    if (r < 0) md_die("guest identity", r);
    const char *name = md_executable;
    for (const char *p = name; *p; ++p) if (*p == '/') name = p + 1;
    r = RAW2(prctl, PR_SET_NAME, name);
    if (r < 0) md_die("guest process name", r);
    long fd = command.fd;
    md_files.image = &process_image;
    md_files.connections = &connections;
    memcpy(process_image.executable_object, command.object, sizeof(command.object));
    process_image.executable_path = md_executable;
    struct md_image program;
    r = md_interception_map_image((int)fd, 0);
    if (!r) r = md_elf_load((int)fd, 0, &program);
    RAW1(close, fd);
    if (r < 0) md_die("map guest program", r);
    struct md_image image = {.entry = program.entry};
    if (command.interpreter_fd >= 0) {
        fd = command.interpreter_fd;
        r = md_interception_map_image((int)fd, 1);
        if (!r) r = md_elf_load((int)fd, 1, &image);
        RAW1(close, fd);
        if (r < 0) md_die("map stock loader", r);
    }

    const size_t stack_size = 8 * 1024 * 1024;
    long stack = RAW6(mmap, 0, stack_size + 2 * md_page_size, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (stack < 0) md_die("reserve guest stack", stack);
    r = RAW3(mprotect, stack + md_page_size, stack_size, PROT_READ | PROT_WRITE);
    if (r < 0) md_die("map guest stack", r);
    size_t guest_argc = command.argc;
    size_t words = 1 + guest_argc + 1 + envc + 1 + 2 * (auxc + 1);
    if (words * sizeof(uintptr_t) > stack_size / 2) md_die("guest arguments", -E2BIG);
    uintptr_t *sp = (uintptr_t *)(((uintptr_t)stack + md_page_size + stack_size - words * sizeof(uintptr_t)) & ~15UL);
    uintptr_t *p = sp;
    *p++ = guest_argc;
    for (unsigned i = 0; i < command.argc; ++i) *p++ = (uintptr_t)command.argv[i];
    *p++ = 0;
    for (size_t i = 0; i < envc; ++i) *p++ = (uintptr_t)env[i];
    *p++ = 0;
    Elf64_auxv_t *guest_aux = (Elf64_auxv_t *)p;
    memcpy(guest_aux, aux, (auxc + 1) * sizeof(*aux));
    for (size_t i = 0; i < auxc; ++i) {
        switch (guest_aux[i].a_type) {
        case AT_PHDR: guest_aux[i].a_un.a_val = program.phdr; break;
        case AT_PHNUM: guest_aux[i].a_un.a_val = program.phnum; break;
        case AT_PHENT: guest_aux[i].a_un.a_val = sizeof(Elf64_Phdr); break;
        case AT_ENTRY: guest_aux[i].a_un.a_val = program.entry; break;
        case AT_BASE: guest_aux[i].a_un.a_val = image.base; break;
        case AT_EXECFN: guest_aux[i].a_un.a_val = (uintptr_t)execfn; break;
        }
    }
    r = md_interception_enter_image(guest_aux, (unsigned)auxc);
    if (r < 0) md_die("enter guest image", r);
    md_proc_image_init(&process_image, command.argc, command.argv, guest_aux, (auxc + 1) * sizeof(*guest_aux));
    md_enter(image.entry, (uintptr_t)sp);
}
