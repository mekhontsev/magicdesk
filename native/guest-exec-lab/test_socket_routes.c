#include "socket_routes.h"
#include <assert.h>
#include <errno.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>

int main(void) {
    struct md_socket_routes routes = {0};
    assert(md_socket_route_add(&routes, "--socket-path", "/tmp/wayland", "md-wayland") == 0);
    assert(md_socket_route_add(&routes, "--socket-abstract", "/tmp/wayland", "md-x11") == 0);
    assert(md_socket_route_add(&routes, "--socket-path", "/tmp/wayland", "other") == -EEXIST);
    assert(md_socket_route_add(&routes, "--socket-path", "relative", "other") == -EINVAL);
    assert(md_socket_route_add(&routes, "--socket-path", "/", "") == -EINVAL);
    assert(md_socket_route_add(&routes, "--unknown", "/", "target") == -EINVAL);
    char oversized[109]; memset(oversized, 'x', sizeof(oversized)); oversized[108] = 0;
    assert(md_socket_route_add(&routes, "--socket-abstract", oversized, "target") == -EINVAL);
    assert(md_socket_route_add(&routes, "--socket-path", "/another", oversized) == -EINVAL);
    for (int abstract = 0; abstract < 2; abstract++) {
        struct sockaddr_un address = {.sun_family = AF_UNIX};
        strcpy(address.sun_path + abstract, "/tmp/wayland");
        unsigned length = offsetof(struct sockaddr_un, sun_path) + abstract + strlen("/tmp/wayland") + !abstract;
        assert(md_socket_route_apply(&routes, &address, &length) == 1);
        const char *expected = abstract ? "md-x11" : "md-wayland";
        assert(!address.sun_path[0] && !strcmp(address.sun_path + 1, expected));
        assert(length == offsetof(struct sockaddr_un, sun_path) + 1 + strlen(expected));
    }
    struct sockaddr_un binary = {.sun_family = AF_UNIX};
    strcpy(binary.sun_path + 1, "/tmp/wayland");
    unsigned length = offsetof(struct sockaddr_un, sun_path) + 1 + strlen("/tmp/wayland") + 1;
    struct sockaddr_un before = binary;
    unsigned original_length = length;
    assert(md_socket_route_apply(&routes, &binary, &length) == 0);
    assert(!memcmp(&before, &binary, sizeof(binary)) && original_length == length);
    for (unsigned i = routes.count; i < MD_SOCKET_ROUTES_MAX; i++) {
        char name[32]; snprintf(name, sizeof(name), "extra-%u", i);
        assert(md_socket_route_add(&routes, "--socket-abstract", name, "target") == 0);
    }
    assert(md_socket_route_add(&routes, "--socket-abstract", "overflow", "target") == -E2BIG);
    puts("PASS socket routes: explicit paths, exact binary abstracts, duplicates and bounds");
}
