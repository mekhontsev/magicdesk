#define _GNU_SOURCE
#include "raw.h"
#include "event_wait.h"
#include "process_owner.h"
#include "launch_environment.h"
#include "launch_identity.h"
#include "socket_routes.h"
#include "fs_mounts.h"
#include "host_identity.h"
#include "arguments.h"
#include "network_profile.h"
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
    if (argc < 5)
        md_die("usage: guest-run --store HOST_PATH [--cwd GUEST_PATH] -- PROGRAM [ARGS]", -EINVAL);
    const char *store = NULL, *cwd = "/", *home = "/tmp", *admit = NULL, *run_deadline = NULL;
    int diagnostics = 0, statistics = 0, commands = 0;
    const char *user = NULL;
    const char *groups = NULL;
    const char *hostname = NULL;
    struct md_network_profile network = {0};
    const char *gamepad_endpoint = NULL;
    char *overrides[128]; unsigned override_count = 0;
    struct md_fs_attachment attachments[MD_FS_MOUNTS_MAX]; unsigned attachment_count = 0;
    size_t program = 1;
    while (program < argc && !md_equal(argv[program], "--")) {
        if (md_equal(argv[program], "--diagnostics")) { diagnostics = 1; program++; continue; }
        if (md_equal(argv[program], "--statistics")) { statistics = 1; program++; continue; }
        if (md_equal(argv[program], "--magicdesk") && !commands) { commands = 1; program++; continue; }
        if (program + 1 >= argc) md_die("missing launch option value", -EINVAL);
        if (md_equal(argv[program], "--bind") || md_equal(argv[program], "--bind-ro")
                || md_equal(argv[program], "--bind-create")) {
            if (program+2 >= argc || attachment_count == MD_FS_MOUNTS_MAX) md_die("invalid directory attachment", -EINVAL);
            attachments[attachment_count++] = (struct md_fs_attachment){.source=argv[program+1],
                .target=argv[program+2], .flags=md_equal(argv[program], "--bind-ro") ? MD_FS_ATTACHMENT_READONLY
                    : md_equal(argv[program], "--bind-create") ? MD_FS_ATTACHMENT_CREATE : 0};
            program += 3; continue;
        }
        if (md_socket_route_option_valid(argv[program])) {
            if (program + 2 >= argc) md_die("missing socket route", -EINVAL);
            long r = md_socket_route_add(&md_connections, argv[program], argv[program + 1], argv[program + 2]);
            if (r < 0) md_die("invalid socket route", r);
            program += 3;
            continue;
        }
        if (md_equal(argv[program], "--store") && !store) store = argv[program + 1];
        else if (md_equal(argv[program], "--cwd")) cwd = argv[program + 1];
        else if (md_equal(argv[program], "--home")) home = argv[program + 1];
        else if (md_equal(argv[program], "--gamepad-endpoint") && !gamepad_endpoint) gamepad_endpoint=argv[program+1];
        else if (md_network_profile_option(argv[program])) {
            int error=md_network_profile_add(&network,argv[program],argv[program+1]);
            if (error) md_die("invalid network option",error);
        }
        else if (md_equal(argv[program], "--hostname") && !hostname) {
            hostname=argv[program+1];
            if (!md_hostname_valid(hostname)) md_die("invalid guest hostname",-EINVAL);
        }
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
    int network_error=md_network_profile_validate(&network);
    if (network_error) md_die("incompatible network options",network_error);
    struct md_launch_environment guest_environment;
    long environment_result = md_launch_environment(&guest_environment, home, env);
    if (environment_result < 0) md_die("guest environment", environment_result);
    if (user && (environment_result = md_launch_environment_user(&guest_environment, user)) < 0)
        md_die("guest user environment", environment_result);
    for (unsigned i = 0; i < override_count; ++i) {
        if (md_prefix(overrides[i], "MAGICDESK_COMMAND_")) md_die("command channel requires --magicdesk", -EINVAL);
        environment_result = md_launch_environment_set(&guest_environment, overrides[i]);
        if (environment_result < 0) md_die("invalid guest environment override", environment_result);
    }
    if (commands) {
        unsigned found = 0;
        for (unsigned i = 0; env[i]; i++) {
            if (md_prefix(env[i], "MAGICDESK_COMMAND_ENDPOINT=") || md_prefix(env[i], "MAGICDESK_COMMAND_BUILD=")) {
                environment_result = md_launch_environment_set(&guest_environment, env[i]);
                if (environment_result < 0) md_die("command environment", environment_result);
                ++found;
            }
        }
        if (found != 2) md_die("--magicdesk requires a live MagicDesk command environment", -ENOTCONN);
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
        struct md_arguments arguments;
        md_arguments_init(&arguments);
        long error=md_arguments_reserve(&arguments,argc-program+32+3*(md_connections.count+attachment_count)
            +md_network_profile_arguments(&network,NULL));
        if (error<0) md_die("launch arguments",error);
        char **args=arguments.values;
        args[0]=supervisor;
        unsigned n = 1;
        if (diagnostics) args[n++] = "--diagnostics";
        if (statistics) args[n++] = "--statistics";
        if (commands) args[n++] = "--magicdesk";
        if (run_deadline) { args[n++] = "--deadline-seconds"; args[n++] = (char *)run_deadline; }
        if (admit) { args[n++] = "--admit-elf"; args[n++] = (char *)admit; }
        if (user) { args[n++] = "--user"; args[n++] = (char *)user; }
        if (groups) { args[n++] = "--groups"; args[n++] = (char *)groups; }
        if (hostname) { args[n++]="--hostname"; args[n++]=(char *)hostname; }
        n+=md_network_profile_arguments(&network,args+n);
        if (gamepad_endpoint) { args[n++]="--gamepad-endpoint"; args[n++]=(char *)gamepad_endpoint; }
        for (unsigned i = 0; i < attachment_count; ++i) {
            args[n++] = attachments[i].flags & MD_FS_ATTACHMENT_READONLY ? "--bind-ro"
                : attachments[i].flags & MD_FS_ATTACHMENT_CREATE ? "--bind-create" : "--bind";
            args[n++] = (char *)attachments[i].source; args[n++] = (char *)attachments[i].target;
        }
        args[n++] = "--store"; args[n++] = (char *)store;
        args[n++] = "--endpoint"; args[n++] = endpoint;
        args[n++] = bootstrap;
        args[n++] = "--cwd"; args[n++] = (char *)cwd;
        for (unsigned i = 0; i < md_connections.count; i++) {
            struct md_socket_route *route = &md_connections.entries[i];
            args[n++] = (char *)md_socket_route_option(route);
            args[n++] = route->source; args[n++] = route->destination;
        }
        args[n++] = "--namespace"; args[n++] = endpoint;
        for (size_t i = program; i <= argc; ++i) args[n + i - program] = argv[i];
        long status = md_process_guard(owner, supervisor, args, guest_environment.values, &signals);
        md_arguments_close(&arguments);
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
