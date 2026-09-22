#include "wayland_server.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <wayland-client.h>

int main(void) {
    const char *temporary = getenv("TMPDIR");
    char directory[4096];
    snprintf(directory, sizeof(directory), "%s/mdw-smoke-XXXXXX", temporary ? temporary : "/tmp");
    assert(mkdtemp(directory));
    assert(setenv("XDG_RUNTIME_DIR", directory, 1) == 0);
    for (int iteration = 0; iteration < 2; ++iteration) {
        MdwServer *server = mdw_server_create();
        assert(server);
        struct wl_display *client = wl_display_connect(mdw_server_socket(server));
        assert(client);
        assert(mdw_server_dispatch(server, 0) >= 0);
        wl_display_disconnect(client);
        mdw_server_destroy(server);
    }
    assert(rmdir(directory) == 0);
    puts("Wayland/Pixman server lifecycle passed");
    return 0;
}