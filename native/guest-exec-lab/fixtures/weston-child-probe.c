// SPDX-License-Identifier: MIT
// The build fixture prepends the exact release's surface declarations.
#include <stdio.h>
#include <string.h>

static unsigned calls, unmapped;
static void count_child(struct weston_desktop_surface *child, void *data)
{
    assert(data == &calls);
    calls++;
    if (!weston_desktop_surface_get_user_data(child))
        unmapped++;
}

int main(int argc, char **argv)
{
    assert(argc == 2);
    struct weston_desktop_surface parent = {0}, children[3] = {0};
    wl_list_init(&parent.children_list);
    for (unsigned i = 0; i < 3; i++) {
        wl_list_insert(parent.children_list.prev, &children[i].children_link);
        weston_desktop_surface_set_user_data(&children[i], i == 1 ? NULL : &calls);
    }
    weston_desktop_surface_foreach_child(&parent, count_child, &calls);
    printf("callbacks=%u unmapped=%u\n", calls, unmapped);
    assert(calls == (strcmp(argv[1], "stock") == 0 ? 3 : 2));
    assert(unmapped == (strcmp(argv[1], "stock") == 0 ? 1 : 0));
    return 0;
}
