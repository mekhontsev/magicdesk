#ifndef MD_SOCKET_ROUTES_H
#define MD_SOCKET_ROUTES_H
#include <sys/un.h>
#define MD_SOCKET_ROUTES_MAX 8
struct md_socket_route { char source[108], destination[108]; int abstract; };
struct md_socket_routes { unsigned count; struct md_socket_route entries[MD_SOCKET_ROUTES_MAX]; };
extern struct md_socket_routes md_connections;
int md_socket_route_add(struct md_socket_routes *, const char *option, const char *source, const char *destination);
int md_socket_route_apply(const struct md_socket_routes *, struct sockaddr_un *, unsigned *length);
#endif
