#define _GNU_SOURCE
#include "raw.h"
#include "event_wait.h"
#include "process_owner.h"
#include "launch_environment.h"
#include "launch_identity.h"
#include "socket_routes.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/limits.h>
#include <signal.h>
#include <sys/wait.h>

static int finish_service(long server, int fd, int signals, int stop) {
    if (stop >= 0) RAW1(close, stop);
    /* EVENT_WAIT: service shutdown after lifetime pipe EOF; timeout escalates and fails. */
    long deadline = md_event_now() + 10000000000LL;
    struct pollfd fds[] = {{fd, POLLIN, 0}, {signals, POLLIN, 0}};
    int failed = 0, status;
    for (;;) {
        long pid = RAW4(wait4, server, &status, WNOHANG, 0);
        if (pid == server) return failed ? 125 : md_process_status(status);
        if (pid < 0 && pid != -EINTR) return 125;
        md_process_signals_read(signals);
        long r = md_event_wait(fds, 2, deadline);
        if (r < 0) {
            if (failed) return 125;
            md_process_signal(fd, SIGKILL);
            failed = 1;
            deadline = md_event_now() + 10000000000LL;
        }
    }
}
void md_boot(uintptr_t *stack) {
    size_t argc = *stack;
    char **argv = (char **)(stack + 1), **env = argv + argc + 1;
    if (!md_launch_identity(RAW0(getuid), RAW0(geteuid), RAW0(getgid), RAW0(getegid)))
        md_die("guest runtime requires the selected shell or root identity", -EACCES);
    if (argc < 5 || argc > 1000)
        md_die("usage: guest-run --store HOST_PATH [--cwd GUEST_PATH] -- PROGRAM [ARGS]", -EINVAL);
    const char *store = NULL, *cwd = "/", *home = "/tmp";
    size_t program = 1;
    while (program < argc && !md_equal(argv[program], "--")) {
        if (program + 1 >= argc) md_die("missing launch option value", -EINVAL);
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
        else md_die("unknown launch option", -EINVAL);
        program += 2;
    }
    if (++program >= argc || !store || store[0] != '/' || cwd[0] != '/' || home[0] != '/')
        md_die("invalid guest launch plan", -EINVAL);
    struct md_launch_environment guest_environment;
    long environment_result = md_launch_environment(&guest_environment, home, env);
    if (environment_result < 0) md_die("guest environment", environment_result);
    char bootstrap[PATH_MAX], service_path[PATH_MAX], endpoint[96] = "md-namespace-", ready_text[32], stop_text[32];
    long executable = RAW4(readlinkat, AT_FDCWD, "/proc/self/exe", bootstrap, sizeof(bootstrap) - 1);
    if (executable <= 0 || executable == sizeof(bootstrap) - 1) md_die("runner identity", -EIO);
    bootstrap[executable] = 0;
    while (executable && bootstrap[executable - 1] != '/') --executable;
    bootstrap[executable] = 0;
    if (!executable || md_copy(service_path, sizeof(service_path), bootstrap) ||
        md_append(bootstrap, sizeof(bootstrap), "libmagicdesk_guest_bootstrap.so") ||
        md_append(service_path, sizeof(service_path), "libmagicdesk_guest_service.so"))
        md_die("runtime path", -ENAMETOOLONG);
    md_decimal(endpoint + md_length(endpoint), (unsigned long)RAW0(getpid));
    struct md_process_signals signals;
    if (md_process_signals_open(&signals) < 0) md_die("supervisor signals", -EIO);
    int ready[2], stop[2];
    if (RAW2(pipe2, ready, O_CLOEXEC) < 0 || RAW2(pipe2, stop, O_CLOEXEC) < 0)
        md_die("service pipes", -EIO);
    md_decimal(ready_text, (unsigned)ready[1]);
    md_decimal(stop_text, (unsigned)stop[0]);
    long server = RAW5(clone, SIGCHLD, 0, 0, 0, 0);
    if (server < 0) md_die("start filesystem service", server);
    if (!server) {
        int keep[] = {ready[1], stop[0]};
        if (RAW0(setsid) < 0 || md_process_signals_restore(&signals) < 0)
            RAW1(exit_group, 125);
        if (md_process_close_fds(keep, 2) < 0) RAW1(exit_group, 125);
        RAW3(fcntl, ready[1], F_SETFD, 0);
        RAW3(fcntl, stop[0], F_SETFD, 0);
        char *args[] = {service_path, (char *)store, endpoint, ready_text,
                        stop_text, NULL};
        md_die("execute filesystem service", RAW3(execve, service_path, args, env));
    }
    RAW1(close, ready[1]);
    RAW1(close, stop[0]);
    long service = RAW2(pidfd_open, server, 0);
    if (service < 0) {
        RAW1(close, stop[1]);
        /* server is an unreaped direct child; this PID cannot have been reused. */
        RAW2(kill, server, SIGKILL);
        md_die("service pidfd", service);
    }
    int result = 125;
    long deadline = md_event_now() + 30000000000LL;
    struct pollfd startup[] = {{ready[0], POLLIN, 0}, {signals.fd, POLLIN, 0}, {service, POLLIN, 0}};
    long r;
    for (;;) {
        /* EVENT_WAIT: explicit service readiness, cancellation or death; no startup settling delay. */
        r = md_event_wait(startup, 3, deadline);
        if (r < 0) break;
        long cancel = md_process_signals_read(signals.fd);
        if (cancel) { result = cancel > 0 ? 128 + cancel : 125; r = -ECANCELED; break; }
        if (startup[0].revents) {
            int remote = -EIO;
            long n = RAW3(read, ready[0], &remote, sizeof(remote));
            r = n == sizeof(remote) ? remote : -EIO;
            break;
        }
        if (startup[2].revents) { r = -EIO; break; }
    }
    RAW1(close, ready[0]);
    if (r < 0) md_error("start guest filesystem service", r);
    if (!r) {
        long owner = RAW0(getpid);
        long guard = RAW5(clone, SIGCHLD, 0, 0, 0, 0);
        if (!guard) {
            char *args[1024 + MD_SOCKET_ROUTES_MAX * 3] = {bootstrap, "--cwd", (char *)cwd};
            unsigned n = 3;
            for (unsigned i = 0; i < md_connections.count; i++) {
                struct md_socket_route *route = &md_connections.entries[i];
                args[n++] = route->abstract ? "--socket-abstract" : "--socket-path";
                args[n++] = route->source;
                args[n++] = route->destination;
            }
            args[n++] = "--namespace";
            args[n++] = endpoint;
            for (size_t i = program; i <= argc; ++i) args[n + i - program] = argv[i];
            long status = md_process_guard(owner, service, stop[1], bootstrap, args, guest_environment.values, &signals);
            RAW1(exit_group, status);
        }
        if (guard > 0) {
            /* The guardian exclusively owns service lifetime, even if this frontend dies. */
            RAW1(close, stop[1]);
            stop[1] = -1;
            long guard_fd = RAW2(pidfd_open, guard, 0);
            if (guard_fd < 0) RAW2(kill, guard, SIGTERM);
            int keep[] = {(int)service, (int)guard_fd, signals.fd};
            if (md_process_close_fds(keep, 3) < 0) md_process_signal(guard_fd, SIGTERM);
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
            if (guard_fd >= 0) RAW1(close, guard_fd);
        }
    }
    if (finish_service(server, service, signals.fd, stop[1])) {
        if (!r) md_error("guest filesystem service shutdown", -EIO);
        result = 125;
    }
    RAW1(close, service);
    RAW1(exit_group, result);
}
