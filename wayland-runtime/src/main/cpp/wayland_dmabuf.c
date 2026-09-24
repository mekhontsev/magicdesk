#include "wayland_dmabuf.h"
#include "wayland_renderer.h"
#include "linux-dmabuf-v1-protocol.h"
#include <drm_fourcc.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <unistd.h>
#include <wayland-server-core.h>
#include <wlr/interfaces/wlr_buffer.h>
#include <wlr/render/dmabuf.h>
#include <wlr/render/drm_format_set.h>
#include <wlr/render/wlr_renderer.h>

struct Params {
    struct wlr_renderer *renderer;
    struct wlr_dmabuf_attributes attributes;
    bool used;
};
struct BufferResource {
    struct wl_resource *resource;
    struct wlr_buffer *buffer;
    struct wl_listener release;
};

static void destroy_resource(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}
static const struct wl_buffer_interface buffer_interface = {.destroy = destroy_resource};
static bool is_buffer(struct wl_resource *resource) {
    return wl_resource_instance_of(resource, &wl_buffer_interface, &buffer_interface);
}
static struct wlr_buffer *from_resource(struct wl_resource *resource) {
    struct BufferResource *buffer = wl_resource_get_user_data(resource);
    return buffer ? buffer->buffer : NULL;
}
static const struct wlr_buffer_resource_interface resource_interface = {
    .name = "magicdesk-linear-dmabuf", .is_instance = is_buffer, .from_resource = from_resource,
};
static void buffer_release(struct wl_listener *listener, void *data) {
    (void)data;
    struct BufferResource *buffer = wl_container_of(listener, buffer, release);
    wl_buffer_send_release(buffer->resource);
}
static void buffer_destroy(struct wl_resource *resource) {
    struct BufferResource *buffer = wl_resource_get_user_data(resource);
    wl_list_remove(&buffer->release.link);
    wlr_buffer_drop(buffer->buffer);
    free(buffer);
}
static void params_destroy(struct wl_resource *resource) {
    struct Params *params = wl_resource_get_user_data(resource);
    wlr_dmabuf_attributes_finish(&params->attributes);
    free(params);
}
static bool unused(struct wl_resource *resource) {
    struct Params *params = wl_resource_get_user_data(resource);
    if (!params->used) return true;
    wl_resource_post_error(resource, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_ALREADY_USED, "buffer params already consumed");
    return false;
}
static void params_add(struct wl_client *client, struct wl_resource *resource, int fd,
        uint32_t plane, uint32_t offset, uint32_t stride, uint32_t hi, uint32_t lo) {
    (void)client;
    struct Params *params = wl_resource_get_user_data(resource);
    if (!unused(resource)) { close(fd); return; }
    if (plane != 0) {
        wl_resource_post_error(resource, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_PLANE_IDX, "only one packed RGB plane is supported");
        close(fd); return;
    }
    if (params->attributes.n_planes) {
        wl_resource_post_error(resource, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_PLANE_SET, "plane already set");
        close(fd); return;
    }
    params->attributes.n_planes = 1;
    params->attributes.fd[0] = fd;
    params->attributes.offset[0] = offset;
    params->attributes.stride[0] = stride;
    params->attributes.modifier = (uint64_t)hi << 32 | lo;
}
static void create_buffer(struct wl_resource *resource, uint32_t id, int32_t width, int32_t height,
        uint32_t format, uint32_t flags) {
    if (!unused(resource)) return;
    struct Params *params = wl_resource_get_user_data(resource);
    params->used = true;
    struct wlr_dmabuf_attributes attributes = params->attributes;
    params->attributes.n_planes = 0;
    attributes.width = width; attributes.height = height; attributes.format = format;
    if (!attributes.n_planes) {
        wl_resource_post_error(resource, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INCOMPLETE, "missing plane"); goto done;
    }
    if (width < 1 || height < 1) {
        wl_resource_post_error(resource, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_DIMENSIONS, "invalid dimensions"); goto done;
    }
    uint32_t known_flags = ZWP_LINUX_BUFFER_PARAMS_V1_FLAGS_Y_INVERT |
        ZWP_LINUX_BUFFER_PARAMS_V1_FLAGS_INTERLACED | ZWP_LINUX_BUFFER_PARAMS_V1_FLAGS_BOTTOM_FIRST;
    if (flags & ~known_flags) {
        wl_resource_post_error(resource, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_FORMAT, "unknown flags"); goto done;
    }
    const struct wlr_drm_format_set *formats = wlr_renderer_get_texture_formats(params->renderer, WLR_BUFFER_CAP_DMABUF);
    /* v1/v2 cannot negotiate an explicit modifier. Do not guess an implicit layout. */
    if (wl_resource_get_version(resource) < 3 || flags || !formats ||
            !wlr_drm_format_set_has(formats, format, attributes.modifier)) goto failed;
    uint64_t end = (uint64_t)attributes.offset[0] + (uint64_t)(height - 1) * attributes.stride[0] + (uint64_t)width * 4;
    struct stat stat;
    if (attributes.stride[0] < (uint64_t)width * 4 ||
            (!fstat(attributes.fd[0], &stat) && stat.st_size > 0 && end > (uint64_t)stat.st_size)) {
        wl_resource_post_error(resource, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_OUT_OF_BOUNDS, "invalid packed RGB extent"); goto done;
    }
    if (attributes.stride[0] % 4 || attributes.offset[0] % 4) goto failed;
    struct wlr_buffer *imported = mdw_renderer_import_dmabuf(params->renderer, &attributes);
    if (!imported) goto failed;
    struct BufferResource *buffer = calloc(1, sizeof(*buffer));
    if (!buffer) { wlr_buffer_drop(imported); wl_resource_post_no_memory(resource); goto done; }
    buffer->resource = wl_resource_create(wl_resource_get_client(resource), &wl_buffer_interface, 1, id);
    if (!buffer->resource) { free(buffer); wlr_buffer_drop(imported); wl_resource_post_no_memory(resource); goto done; }
    buffer->buffer = imported;
    buffer->release.notify = buffer_release;
    wl_signal_add(&imported->events.release, &buffer->release);
    wl_resource_set_implementation(buffer->resource, &buffer_interface, buffer, buffer_destroy);
    if (!id) zwp_linux_buffer_params_v1_send_created(resource, buffer->resource);
    goto done;
failed:
    if (!id) zwp_linux_buffer_params_v1_send_failed(resource);
    else wl_resource_post_error(resource, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_WL_BUFFER, "DMA-BUF import unavailable for this allocation");
done:
    wlr_dmabuf_attributes_finish(&attributes);
}
static void params_create(struct wl_client *client, struct wl_resource *resource,
        int32_t width, int32_t height, uint32_t format, uint32_t flags) {
    (void)client; create_buffer(resource, 0, width, height, format, flags);
}
static void params_immediate(struct wl_client *client, struct wl_resource *resource, uint32_t id,
        int32_t width, int32_t height, uint32_t format, uint32_t flags) {
    (void)client; create_buffer(resource, id, width, height, format, flags);
}
static const struct zwp_linux_buffer_params_v1_interface params_interface = {
    .destroy = destroy_resource, .add = params_add, .create = params_create, .create_immed = params_immediate,
};
static void create_params(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
    struct Params *params = calloc(1, sizeof(*params));
    if (!params) { wl_resource_post_no_memory(resource); return; }
    struct wl_resource *child = wl_resource_create(client, &zwp_linux_buffer_params_v1_interface, wl_resource_get_version(resource), id);
    if (!child) { free(params); wl_resource_post_no_memory(resource); return; }
    params->renderer = wl_resource_get_user_data(resource);
    wl_resource_set_implementation(child, &params_interface, params, params_destroy);
}
static const struct zwp_linux_dmabuf_v1_interface factory_interface = {.destroy = destroy_resource, .create_params = create_params};
static void bind_factory(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    struct wlr_renderer *renderer = data;
    struct wl_resource *resource = wl_resource_create(client, &zwp_linux_dmabuf_v1_interface, version, id);
    if (!resource) { wl_client_post_no_memory(client); return; }
    wl_resource_set_implementation(resource, &factory_interface, renderer, NULL);
    const struct wlr_drm_format_set *formats = wlr_renderer_get_texture_formats(renderer, WLR_BUFFER_CAP_DMABUF);
    if (version < 3 || !formats) return;
    for (size_t i = 0; i < formats->len; ++i) {
        const struct wlr_drm_format *format = &formats->formats[i];
        for (size_t j = 0; j < format->len; ++j)
            zwp_linux_dmabuf_v1_send_modifier(resource, format->format, format->modifiers[j] >> 32, format->modifiers[j]);
    }
}
bool mdw_dmabuf_init(struct wl_display *display, struct wlr_renderer *renderer) {
    const struct wlr_drm_format_set *formats = wlr_renderer_get_texture_formats(renderer, WLR_BUFFER_CAP_DMABUF);
    if (!formats || !formats->len) return true;
    wlr_buffer_register_resource_interface(&resource_interface);
    return wl_global_create(display, &zwp_linux_dmabuf_v1_interface, 3, renderer, bind_factory) != NULL;
}
