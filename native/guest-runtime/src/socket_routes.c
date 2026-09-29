#include "socket_routes.h"
#include "raw.h"
#include <errno.h>
#include <stddef.h>
#include <sys/socket.h>

struct md_socket_routes md_connections;

int md_socket_route_add(struct md_socket_routes *routes, const char *option,
                        const char *source, const char *destination) {
    int abstract = md_equal(option, "--socket-abstract");
    if (!abstract && !md_equal(option, "--socket-path")) return -EINVAL;
    size_t n = md_length(source), d = md_length(destination);
    if (!n || n >= 108 || !d || d >= 108 || (!abstract && source[0] != '/')) return -EINVAL;
    for (unsigned i = 0; i < routes->count; i++)
        if (routes->entries[i].abstract == abstract && md_equal(routes->entries[i].source, source)) return -EEXIST;
    if (routes->count == MD_SOCKET_ROUTES_MAX) return -E2BIG;
    struct md_socket_route *route = &routes->entries[routes->count++];
    md_copy(route->source, sizeof(route->source), source);
    md_copy(route->destination, sizeof(route->destination), destination);
    route->abstract = abstract;
    return 0;
}

int md_socket_route_apply(const struct md_socket_routes *routes, struct sockaddr_un *address, unsigned *length) {
    size_t prefix = offsetof(struct sockaddr_un, sun_path);
    if (address->sun_family != AF_UNIX || *length <= prefix || *length > sizeof(*address)) return 0;
    int abstract = address->sun_path[0] == 0;
    size_t bytes = *length - prefix - abstract;
    const char *source = address->sun_path + abstract;
    if (!abstract) {
        size_t n = 0;
        while (n < bytes && source[n]) n++;
        bytes = n;
    }
    for (unsigned i = 0; i < routes->count; i++) {
        const struct md_socket_route *route = &routes->entries[i];
        if (route->abstract != abstract || md_length(route->source) != bytes) continue;
        size_t equal = 0;
        while (equal < bytes && source[equal] == route->source[equal]) equal++;
        if (equal != bytes) continue;
        size_t n = md_length(route->destination);
        memset(address->sun_path, 0, sizeof(address->sun_path));
        memcpy(address->sun_path + 1, route->destination, n);
        *length = (unsigned)(prefix + 1 + n);
        return 1;
    }
    return 0;
}
