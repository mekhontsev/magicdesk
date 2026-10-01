#define _GNU_SOURCE
#include "raw.h"
#include "event_wait.h"
#include "process_owner.h"
#include "launch_environment.h"
#include "launch_identity.h"
#include "socket_routes.h"
#include "fs_mounts.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/limits.h>
#include <signal.h>
#include <sys/wait.h>

static struct md_socket_routes md_connections;

void md_boot(uintptr_t *stack) {
    size_t argc = *stack;
    char **argv = (char **)(stack + 1), **env = argv + argc + 1;
    if (!md_launch_identity(RAW0(getuid), RAW0(geteuid), RAW0(getgid), RAW0(getegid)))
        md_die("guest runtime requires the selected shell or root identity", -EACCES);
    if (argc < 5 || argc > 1000)
        md_die("usage: guest-run --store HOST_PATH [--cwd GUEST_PATH] -- PROGRAM [ARGS]", -EINVAL);
    const char *store = NULL, *cwd = "/", *home = "/tmp", *admit = NULL, *run_deadline = NULL;
    int diagnostics = 0, statistics = 0;
    const char *user = NULL;
    const char *groups = NULL;
    char *overrides[128]; unsigned override_count = 0;
    struct md_fs_attachment attachments[MD_FS_MOUNTS_MAX]; unsigned attachment_count = 0;
    size_t program = 1;
    while (program < argc && !md_equal(argv[program], "--")) {
        if (md_equal(argv[program], "--diagnostics")) { diagnostics = 1; program++; continue; }
        if (md_equal(argv[program], "--statistics")) { statistics = 1; program++; continue; }
        if (program + 1 >= argc) md_die("missing launch option value", -EINVAL);
        if (md_equal(argv[program], "--bind") || md_equal(argv[program], "--bind-ro")) {
            if (program+2 >= argc || attachment_count == MD_FS_MOUNTS_MAX) md_die("invalid directory attachment", -EINVAL);
            attachments[attachment_count++] = (struct md_fs_attachment){.source=argv[program+1],
                .target=argv[program+2], .readonly=md_equal(argv[program], "--bind-ro")};
            program += 3; continue;
        }
        if (md_equal(argv[program], "--socket-path") || md_equal(argv[program], "--socket-abstract")) {
            if (program + 2 >= argc) md_die("missing socket route", -EINVAL);
            long r = md_socket_route_add(&md_connections, argv[program], argv[program + 1], argv[program + 2]);
            if (r < 0) md_die("invalid socket route", r);
            program += 3;
            continue;
        }
        if (md_equal(argv[program], "--store") && !store) store = argv[program + 1];
        else if (md_equal(argv[program], "--cwd")) cwd = argv[program + 1];
        else if (md_equal(argv[program], "--home")) home = argv[program + 1];
        else if (md_equal(argv[program], "--user") && !user) {
            user = argv[program + 1];
            if (!*user || md_length(user) > 1024) md_die("invalid guest user", -EINVAL);
        }
        else if (md_equal(argv[program], "--groups") && !groups) groups = argv[program + 1];
        else if (md_equal(argv[program], "--env")) {
            if (override_count == sizeof(overrides)/sizeof(*overrides)) md_die("too many environment entries", -E2BIG);
            overrides[override_count++] = argv[program + 1];
        }
        else if (md_equal(argv[program], "--admit-elf") && !admit) admit = argv[program + 1];
        else if (md_equal(argv[program], "--deadline-seconds") && !run_deadline) run_deadline = argv[program + 1];
        else md_die("unknown launch option", -EINVAL);
        program += 2;
    }
    if (++program >= argc || !store || store[0] != '/' || cwd[0] != '/' || home[0] != '/')
        md_die("invalid guest launch plan", -EINVAL);
    if (groups && !user) md_die("guest groups require an explicit guest user", -EINVAL);
    struct md_launch_environment guest_environment;
    long environment_result = md_launch_environment(&guest_environment, home, env);
    if (environment_result < 0) md_die("guest environment", environment_result);
    if (user && (environment_result = md_launch_environment_user(&guest_environment, user)) < 0)
        md_die("guest user environment", environment_result);
    for (unsigned i = 0; i < override_count; ++i) {
        environment_result = md_launch_environment_set(&guest_environment, overrides[i]);
        if (environment_result < 0) md_die("invalid guest environment override", environment_result);
    }
    if (admit && admit[0] != '/') md_die("admitted ELF must be absolute", -EINVAL);
    char bootstrap[PATH_MAX], supervisor[PATH_MAX], endpoint[96] = "md-namespace-";
    long executable = RAW4(readlinkat, AT_FDCWD, "/proc/self/exe", bootstrap, sizeof(bootstrap) - 1);
    if (executable <= 0 || executable == sizeof(bootstrap) - 1) md_die("runner identity", -EIO);
    bootstrap[executable] = 0;
    while (executable && bootstrap[executable - 1] != '/') --executable;
    bootstrap[executable] = 0;
    if (!executable || md_copy(supervisor, sizeof(supervisor), bootstrap) ||
        md_append(supervisor, sizeof(supervisor), "libmagicdesk_guest_supervisor.so") ||
        md_append(bootstrap, sizeof(bootstrap), "libmagicdesk_guest_bootstrap.so"))
        md_die("runtime path", -ENAMETOOLONG);
    md_decimal(endpoint + md_length(endpoint), (unsigned long)RAW0(getpid));
    struct md_process_signals signals;
    if (md_process_signals_open(&signals) < 0) md_die("supervisor signals", -EIO);
    long owner = RAW0(getpid);
    long guard = RAW5(clone, SIGCHLD, 0, 0, 0, 0);
    if (guard < 0) md_die("start guest guardian", guard);
    if (!guard) {
        char *args[1044 + MD_SOCKET_ROUTES_MAX * 3 + MD_FS_MOUNTS_MAX * 3] = {supervisor};
        unsigned n = 1;
        if (diagnostics) args[n++] = "--diagnostics";
        if (statistics) args[n++] = "--statistics";
        if (run_deadline) { args[n++] = "--deadline-seconds"; args[n++] = (char *)run_deadline; }
        if (admit) { args[n++] = "--admit-elf"; args[n++] = (char *)admit; }
        if (user) { args[n++] = "--user"; args[n++] = (char *)user; }
        if (groups) { args[n++] = "--groups"; args[n++] = (char *)groups; }
        for (unsigned i = 0; i < attachment_count; ++i) {
            args[n++] = attachments[i].readonly ? "--bind-ro" : "--bind";
            args[n++] = (char *)attachments[i].source; args[n++] = (char *)attachments[i].target;
        }
        args[n++] = "--store"; args[n++] = (char *)store;
        args[n++] = "--endpoint"; args[n++] = endpoint;
        args[n++] = bootstrap;
        args[n++] = "--cwd"; args[n++] = (char *)cwd;
        for (unsigned i = 0; i < md_connections.count; i++) {
            struct md_socket_route *route = &md_connections.entries[i];
            args[n++] = route->abstract ? "--socket-abstract" : "--socket-path";
            args[n++] = route->source; args[n++] = route->destination;
        }
        args[n++] = "--namespace"; args[n++] = endpoint;
        for (size_t i = program; i <= argc; ++i) args[n + i - program] = argv[i];
        long status = md_process_guard(owner, supervisor, args, guest_environment.values, &signals);
        RAW1(exit_group, status);
    }
    long guard_fd = RAW2(pidfd_open, guard, 0);
    if (guard_fd < 0) { RAW2(kill, guard, SIGTERM); md_die("guardian pidfd", guard_fd); }
    int keep[] = {(int)guard_fd, signals.fd};
    if (md_process_close_fds(keep, 2) < 0) md_process_signal(guard_fd, SIGTERM);
    int result = 125;
    for (;;) {
        int status;
        long pid = RAW4(wait4, guard, &status, WNOHANG, 0);
        if (pid == guard) { result = md_process_status(status); break; }
        if (pid < 0 && pid != -EINTR) break;
        long cancel = md_process_signals_read(signals.fd);
        if (cancel > 0) md_process_signal(guard_fd, cancel);
        struct pollfd events[] = {{signals.fd, POLLIN, 0}, {guard_fd, POLLIN, 0}};
        /* EVENT_WAIT: guardian exit or cancellation. Guardian owns cleanup bounds. */
        if (md_event_wait(events, 2, INT64_MAX) < 0) break;
    }
    RAW1(close, guard_fd);
    RAW1(exit_group, result);
}
