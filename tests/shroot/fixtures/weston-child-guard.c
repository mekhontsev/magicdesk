// SPDX-License-Identifier: MIT
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdlib.h>

struct weston_desktop_surface;
typedef void (*child_fn)(struct weston_desktop_surface *, void *);
typedef void (*foreach_fn)(struct weston_desktop_surface *, child_fn, void *);
static foreach_fn original;
static void *(*user_data)(struct weston_desktop_surface *);

struct visit {
    child_fn callback;
    void *data;
};

static void visit_child(struct weston_desktop_surface *surface, void *data)
{
    struct visit *visit = data;
    if (user_data(surface))
        visit->callback(surface, visit->data);
}

void weston_desktop_surface_foreach_child(struct weston_desktop_surface *surface,
                                        child_fn callback, void *data)
{
    if (!original) {
        original = (foreach_fn)dlsym(RTLD_NEXT, "weston_desktop_surface_foreach_child");
        user_data = dlsym(RTLD_NEXT, "weston_desktop_surface_get_user_data");
        if (!original || !user_data)
            abort();
    }
    struct visit visit = {callback, data};
    original(surface, visit_child, &visit);
}
