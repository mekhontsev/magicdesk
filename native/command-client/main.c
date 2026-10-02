#define _GNU_SOURCE
#include "wire.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int argument_input(int socket) {
    char source[4096];
    int r = md_command_read_text(socket, source, sizeof(source));
    if (r) return r;
    int fd = !strcmp(source, "-") ? STDIN_FILENO : source[0] == '@' ? open(source+1, O_RDONLY | O_CLOEXEC) : -1;
    int error = fd < 0 ? errno ? -errno : -EINVAL : 0;
    char *buffer = !error ? malloc(MD_COMMAND_REQUEST_LIMIT+1) : NULL;
    if (!error && !buffer) error = -ENOMEM;
    size_t size = 0;
    while (!error) {
        ssize_t n = read(fd, buffer+size, MD_COMMAND_REQUEST_LIMIT+1-size);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0) { error = -errno; break; }
        if (!n) break;
        size += (size_t)n;
        if (size > MD_COMMAND_REQUEST_LIMIT) error = -E2BIG;
    }
    if (fd >= 0 && fd != STDIN_FILENO) close(fd);
    r = md_command_number(socket, error ? UINT32_MAX : (uint32_t)size);
    if (!r && !error) r = md_command_send(socket, buffer, size);
    free(buffer); return r;
}
int main(int argc, char **argv) {
    if (argc > 4097) return 2;
    size_t total = 0;
    for (int i = 1; i < argc; i++) if ((total += strlen(argv[i])) > MD_COMMAND_REQUEST_LIMIT) return 2;
    const char *endpoint = getenv("MAGICDESK_COMMAND_ENDPOINT"), *build = getenv("MAGICDESK_COMMAND_BUILD");
    if (!endpoint || !build) {
        fputs("magicdesk: no command access; launch with --magicdesk\n", stderr); return 3;
    }
    int fd = md_command_connect(endpoint, build, MD_COMMAND_INVOKE);
    int r = fd < 0 ? fd : md_command_number(fd, (uint32_t)argc-1);
    for (int i = 1; !r && i < argc; i++) r = md_command_text(fd, argv[i]);
    size_t remaining = MD_COMMAND_RESPONSE_LIMIT;
    while (!r) {
        uint32_t kind, size;
        r = md_command_read_number(fd, &kind);
        if (r) break;
        if (kind == MD_COMMAND_READ) { r = argument_input(fd); continue; }
        r = md_command_read_number(fd, &size);
        if (r) break;
        if (kind == MD_COMMAND_EXIT) {
            close(fd); return size <= 3 ? (int)size : 3;
        }
        if ((kind != MD_COMMAND_OUT && kind != MD_COMMAND_ERR) || size > remaining) { r=-EPROTO; break; }
        remaining -= size;
        int output = kind == MD_COMMAND_OUT ? STDOUT_FILENO : STDERR_FILENO;
        char bytes[8192];
        while (!r && size) {
            size_t n = size < sizeof(bytes) ? size : sizeof(bytes);
            r = md_command_receive(fd, bytes, n);
            size_t done = 0;
            while (!r && done < n) {
                ssize_t written = write(output, bytes+done, n-done);
                if (written < 0 && errno == EINTR) continue;
                if (written <= 0) r = written ? -errno : -EIO;
                else done += (size_t)written;
            }
            size -= (uint32_t)n;
        }
    }
    if (fd >= 0) close(fd);
    fprintf(stderr, "magicdesk: command channel failed (%s); action outcome may be unknown; not retried\n", strerror(-r));
    return 3;
}
