#define _GNU_SOURCE
#include "wire.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Application adapter only. Shroot receives ordinary attachments, environment
 * and an opaque owner FD; registration never enters its syscall engine. */
int md_command_shroot(int argc, char **argv) {
    if (argc < 3 || argv[0][0] != '/' || argv[1][0] != '/') return 2;
    const char *directory = argv[0];
    --argc; ++argv;
    int prefix = !strcmp(argv[1], "run") || !strcmp(argv[1], "exec") || !strcmp(argv[1], "login") ? 3 : 1;
    if (argc < prefix) return 2;
    char **command = calloc((size_t)argc + 16, sizeof(*command));
    if (!command) return 125;
    int count = 0, enabled = 0, at = prefix;
    for (int i = 0; i < prefix; ++i) command[count++] = argv[i];
    while (at < argc && strcmp(argv[at], "--")) {
        const char *option = argv[at++];
        if (!strcmp(option, "--magicdesk")) {
            if (enabled) { free(command); return 2; }
            enabled = 1; continue;
        }
        int values = !strcmp(option, "--statistics") || !strcmp(option, "--diagnostics")
            || !strcmp(option, "--diagnostics=signals") ? 0
            : !strncmp(option, "--bind", 6) || !strncmp(option, "--socket-", 9) ? 2 : 1;
        if (at + values > argc || (!strcmp(option, "--env") && !strncmp(argv[at], "MAGICDESK_COMMAND_", 18))) {
            free(command); return 2;
        }
        command[count++] = (char *)option;
        while (values--) command[count++] = argv[at++];
    }
    char *authority = NULL, *endpoint = NULL, *build = NULL;
    const char *x11 = getenv("MAGICDESK_X11_AUTHORITY");
    int r = x11 && asprintf(&authority, "MAGICDESK_X11_AUTHORITY=%s", x11) < 0 ? -ENOMEM : 0;
    int owner = -1;
    char fd[32];
    if (!r && enabled) {
        owner = md_command_lease();
        if (owner < 0) r = owner;
        if (!r && (asprintf(&endpoint, "MAGICDESK_COMMAND_ENDPOINT=%s", getenv("MAGICDESK_COMMAND_ENDPOINT")) < 0
                || asprintf(&build, "MAGICDESK_COMMAND_BUILD=%s", getenv("MAGICDESK_COMMAND_BUILD")) < 0)) r = -ENOMEM;
        if (!r && fcntl(owner, F_SETFD, 0)) r = -errno;
        if (!r) {
            snprintf(fd, sizeof(fd), "%d", owner);
            command[count++] = "--owner-fd"; command[count++] = fd;
            command[count++] = "--bind-ro-create"; command[count++] = (char *)directory; command[count++] = "/run/magicdesk";
            command[count++] = "--path-prefix"; command[count++] = "/run/magicdesk";
            command[count++] = "--env"; command[count++] = endpoint;
            command[count++] = "--env"; command[count++] = build;
        }
    }
    if (authority) { command[count++] = "--env"; command[count++] = authority; }
    while (at < argc) command[count++] = argv[at++];
    if (!r) { execv(command[0], command); r = -errno; }
    if (owner >= 0) close(owner);
    free(authority); free(endpoint); free(build); free(command);
    fprintf(stderr, "magicdesk-connect: %s\n", strerror(-r)); return 125;
}
