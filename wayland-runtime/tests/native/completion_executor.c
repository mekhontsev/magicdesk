#include "wayland_server.h"
#include "dmabuf_test_filter.h"

MdwServer *__real_mdw_server_create(bool software);
MdwServer *__wrap_mdw_server_create(bool software) {
    /* Restrict only the test executor's compositor owner before GPU creation.
     * The APK process and unrelated Android services are not filtered. */
    dmabuf_test_deny_bridge(ENOTTY);
    return __real_mdw_server_create(software);
}
