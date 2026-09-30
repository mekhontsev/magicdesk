#define _GNU_SOURCE
#include "fs.h"
#include "proc_paths.h"
#include "socket_calls.h"
#include "socket_routes.h"
#include "event_wait.h"
#include "raw.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

static void snapshot(const struct md_fs *fs, enum md_proc_kind kind, const void *expected, size_t size) {
    long fd = md_proc_image_open(fs, kind, O_RDONLY | O_CLOEXEC);
    assert(fd >= 0 && fcntl((int)fd, F_GETFD) == FD_CLOEXEC);
    char buffer[128]; assert(size < sizeof(buffer));
    assert(read((int)fd, buffer, sizeof(buffer)) == (ssize_t)size && !memcmp(buffer, expected, size));
    assert(lseek((int)fd, 0, SEEK_SET) == 0);
    assert(read((int)fd, buffer, sizeof(buffer)) == (ssize_t)size && !memcmp(buffer, expected, size));
    assert(write((int)fd, "x", 1) == -1 && errno == EBADF);
    close((int)fd);
}
static int listener(char *name, unsigned id) {
    snprintf(name, 64, "md-context-%ld-%u", (long)getpid(), id);
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    strcpy(address.sun_path + 1, name);
    socklen_t size = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(name);
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    assert(fd >= 0 && !bind(fd, (struct sockaddr *)&address, size) && !listen(fd, 4));
    return fd;
}
static void routed(const struct md_fs *fs, int expected, int other) {
    int client = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    assert(client >= 0);
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    strcpy(address.sun_path, "/same/guest/socket");
    unsigned long args[6] = {client, (unsigned long)&address,
        offsetof(struct sockaddr_un, sun_path) + strlen(address.sun_path) + 1};
    assert(md_socket_call(fs, "/test", SYS_connect, args) == 0);
    /* EVENT_WAIT: the selected listener must receive this exact connection. */
    assert(md_event_wait_fd(expected, POLLIN, md_event_now() + 5000000000LL) == 0);
    int accepted = accept4(expected, NULL, NULL, SOCK_CLOEXEC | SOCK_NONBLOCK);
    assert(accepted >= 0);
    assert(accept4(other, NULL, NULL, SOCK_CLOEXEC | SOCK_NONBLOCK) < 0 && errno == EAGAIN);
    close(accepted); close(client);
}
int main(int argc, char **argv) {
    assert(argc == 2);
    assert(!mkdir(argv[1], 0700));
    struct md_process_image images[2] = {0};
    struct md_socket_routes connections[2] = {0};
    struct md_fs fs[2] = {{.image = &images[0], .connections = &connections[0]},
        {.image = &images[1], .connections = &connections[1]}};
    char first[] = "first", second[] = "other";
    char *args1[] = {first, "a"}, *args2[] = {second, "b"};
    uint64_t aux1[] = {6, 4096, 0, 0}, aux2[] = {6, 16384, 0, 0};
    md_proc_image_init(&images[0], 2, args1, aux1, sizeof(aux1));
    md_proc_image_init(&images[1], 2, args2, aux2, sizeof(aux2));
    for (unsigned i = 0; i < 2; i++) assert(!md_copy(fs[i].root, sizeof(fs[i].root), argv[1]));
    snapshot(&fs[0], MD_PROC_CMDLINE, "first\0a", sizeof("first\0a"));
    snapshot(&fs[1], MD_PROC_CMDLINE, "other\0b", sizeof("other\0b"));
    snapshot(&fs[0], MD_PROC_AUXV, aux1, sizeof(aux1));
    snapshot(&fs[1], MD_PROC_AUXV, aux2, sizeof(aux2));
    first[0] = 'F';
    snapshot(&fs[0], MD_PROC_CMDLINE, "First\0a", sizeof("First\0a"));
    snapshot(&fs[1], MD_PROC_CMDLINE, "other\0b", sizeof("other\0b"));
    struct md_fs absent = {0};
    assert(md_proc_image_open(&absent, MD_PROC_CMDLINE, O_RDONLY) == -ENOTSUP);
    assert(md_proc_image_open(&fs[0], MD_PROC_CMDLINE, O_WRONLY) == -EACCES);
    char names[2][64];
    int sockets[] = {listener(names[0], 0), listener(names[1], 1)};
    for (unsigned i = 0; i < 2; i++)
        assert(!md_socket_route_add(&connections[i], "--socket-path", "/same/guest/socket", names[i]));
    routed(&fs[0], sockets[0], sockets[1]);
    routed(&fs[1], sockets[1], sockets[0]);
    routed(&fs[0], sockets[0], sockets[1]);
    struct sockaddr_un unchanged = {.sun_family = AF_UNIX};
    strcpy(unchanged.sun_path, "/same/guest/socket");
    unsigned size = offsetof(struct sockaddr_un, sun_path) + strlen(unchanged.sun_path) + 1;
    assert(!md_socket_route_apply(NULL, &unchanged, &size));
    assert(!strcmp(unchanged.sun_path, "/same/guest/socket"));
    close(sockets[0]); close(sockets[1]);
    assert(!rmdir(argv[1]));
    puts("PASS process context: independent cmdline/auxv snapshots and same-name socket routes; no global fallback");
    return 0;
}
