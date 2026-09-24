#define _GNU_SOURCE
#include "wayland_dmabuf.h"
#include "wayland_renderer.h"
#include "linux-dmabuf-v1-client-protocol.h"
#include <assert.h>
#include <dirent.h>
#include <drm_fourcc.h>
#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <wayland-client.h>
#include <wlr/render/allocator.h>
#include <wlr/render/drm_format_set.h>
#include <wlr/render/interface.h>
#include <wlr/render/pass.h>
#include <wlr/render/pixman.h>
#include <wlr/types/wlr_compositor.h>
#ifdef __ANDROID__
#include "dmabuf_producer.h"
#else
enum { WIDTH = 33, HEIGHT = 19, STRIDE = 160, OFFSET = 12, ALLOCATION = 65536 };
#endif

enum { ERROR_CASES = 8, CONNECTIONS = ERROR_CASES + 2 };
struct Server {
    int sockets[CONNECTIONS][2], stop[2];
    struct wl_display *display;
    struct wlr_renderer *renderer;
    struct wlr_buffer *target;
    struct wl_listener new_surface;
    unsigned frames;
    bool capable;
    bool software;
};
struct Surface {
    struct Server *server;
    struct wl_listener commit, destroy;
};
static void commit(struct wl_listener *listener, void *data) {
    struct Surface *surface = wl_container_of(listener, surface, commit);
    struct wlr_surface *source = data;
    struct wlr_texture *texture = wlr_surface_get_texture(source);
    if (!texture) return;
    struct Server *s = surface->server;
    void *pixels; uint32_t format; size_t stride;
    assert(!wlr_buffer_begin_data_ptr_access(source->buffer->source,
        WLR_BUFFER_DATA_PTR_ACCESS_READ, &pixels, &format, &stride));
    struct wlr_render_pass *pass = wlr_renderer_begin_buffer_pass(s->renderer, s->target, NULL);
    assert(pass);
    wlr_render_pass_add_texture(pass, &(struct wlr_render_texture_options){
        .texture = texture, .filter_mode = WLR_SCALE_FILTER_NEAREST, .blend_mode = WLR_RENDER_BLEND_MODE_NONE});
    assert(wlr_render_pass_submit(pass));
    uint8_t result[WIDTH * HEIGHT * 4];
    assert(mdg_image_read(mdw_buffer_image(s->target), result, WIDTH * 4));
    for (unsigned i = 0; i < WIDTH * HEIGHT; ++i)
        assert(!memcmp(result + i * 4, (uint8_t[]){0x22, 0x11, 0x44, 0xff}, 4));
    ++s->frames;
    mdg_device_collect(mdw_renderer_device(s->renderer));
}
static void surface_destroy(struct wl_listener *listener, void *data) {
    (void)data;
    struct Surface *surface = wl_container_of(listener, surface, destroy);
    wl_list_remove(&surface->commit.link); wl_list_remove(&surface->destroy.link); free(surface);
}
static void new_surface(struct wl_listener *listener, void *data) {
    struct Server *s = wl_container_of(listener, s, new_surface);
    struct wlr_surface *source = data;
    struct Surface *surface = calloc(1, sizeof(*surface)); assert(surface);
    surface->server = s; surface->commit.notify = commit; surface->destroy.notify = surface_destroy;
    wl_signal_add(&source->events.commit, &surface->commit);
    wl_signal_add(&source->events.destroy, &surface->destroy);
}
static int stop_server(int fd, uint32_t mask, void *data) {
    (void)fd; (void)mask;
    struct Server *s = data; wl_display_terminate(s->display); return 0;
}
static void *serve(void *data) {
    struct Server *s = data;
    s->display = wl_display_create(); assert(s->display);
    s->renderer = s->software ? wlr_pixman_renderer_create() : mdw_renderer_create(); assert(s->renderer);
    s->capable = !s->software && mdg_device_linear_dmabuf(mdw_renderer_device(s->renderer));
    assert(wlr_renderer_init_wl_display(s->renderer, s->display));
    assert(mdw_dmabuf_init(s->display, s->renderer));
    struct wlr_compositor *compositor = wlr_compositor_create(s->display, 6, s->renderer); assert(compositor);
    s->new_surface.notify = new_surface;
    wl_signal_add(&compositor->events.new_surface, &s->new_surface);
    struct wlr_allocator *allocator = NULL;
    if (s->capable) {
        allocator = mdw_allocator_create(s->renderer); assert(allocator);
        const struct wlr_drm_format *format = wlr_drm_format_set_get(
            s->renderer->impl->get_render_formats(s->renderer), DRM_FORMAT_ABGR8888);
        s->target = wlr_allocator_create_buffer(allocator, WIDTH, HEIGHT, format); assert(s->target);
    }
    for (unsigned i = 0; i < CONNECTIONS; ++i) assert(wl_client_create(s->display, s->sockets[i][0]));
    struct wl_event_source *stop = wl_event_loop_add_fd(wl_display_get_event_loop(s->display),
        s->stop[0], WL_EVENT_READABLE, stop_server, s); assert(stop);
    // EVENT_WAIT: Wayland socket requests drive the fixture; CTest bounds stalled clients.
    wl_display_run(s->display);
    wl_event_source_remove(stop); wl_list_remove(&s->new_surface.link);
    wl_display_destroy_clients(s->display); wl_display_destroy(s->display);
    if (s->capable) {
        MdgStats stats = mdg_device_stats(mdw_renderer_device(s->renderer));
        assert(stats.failed_frames == 0 && stats.gpu_frames == s->frames);
        wlr_buffer_drop(s->target); wlr_allocator_destroy(allocator);
    }
    wlr_renderer_destroy(s->renderer);
    return NULL;
}

struct Client {
    struct wl_display *display;
    struct wl_registry *registry;
    struct wl_compositor *compositor;
    struct zwp_linux_dmabuf_v1 *dma;
    struct wl_buffer *created;
    unsigned formats, failed, releases, version;
};
static void advertised_format(void *data, struct zwp_linux_dmabuf_v1 *dma, uint32_t format) {
    (void)data; (void)dma; (void)format; assert(false); /* Explicit modifiers only. */
}
static void modifier(void *data, struct zwp_linux_dmabuf_v1 *dma, uint32_t format, uint32_t hi, uint32_t lo) {
    (void)dma;
    struct Client *c = data;
    assert(hi == 0 && lo == DRM_FORMAT_MOD_LINEAR);
    uint32_t formats[] = {DRM_FORMAT_ABGR8888, DRM_FORMAT_XBGR8888, DRM_FORMAT_ARGB8888, DRM_FORMAT_XRGB8888};
    unsigned i;
    for (i = 0; i < 4; ++i) if (format == formats[i]) break;
    assert(i < 4 && !(c->formats & (1u << i))); c->formats |= 1u << i;
}
static const struct zwp_linux_dmabuf_v1_listener dma_listener = {advertised_format, modifier};
static void global(void *data, struct wl_registry *registry, uint32_t id, const char *name, uint32_t version) {
    struct Client *c = data;
    if (!strcmp(name, "wl_compositor")) c->compositor = wl_registry_bind(registry, id, &wl_compositor_interface, 4);
    if (!strcmp(name, "zwp_linux_dmabuf_v1")) {
        assert(version == 3 && !c->dma);
        c->dma = wl_registry_bind(registry, id, &zwp_linux_dmabuf_v1_interface, c->version);
        zwp_linux_dmabuf_v1_add_listener(c->dma, &dma_listener, c);
    }
}
static void global_remove(void *data, struct wl_registry *registry, uint32_t id) { (void)data; (void)registry; (void)id; }
static const struct wl_registry_listener registry_listener = {global, global_remove};
static void connected(struct Client *c, int fd, unsigned version) {
    *c = (struct Client){.display = wl_display_connect_to_fd(fd), .version = version}; assert(c->display);
    c->registry = wl_display_get_registry(c->display);
    wl_registry_add_listener(c->registry, &registry_listener, c);
    // EVENT_WAIT: registry roundtrips acknowledge all advertised globals/formats; CTest bounds a stall.
    assert(wl_display_roundtrip(c->display) >= 0 && wl_display_roundtrip(c->display) >= 0);
    assert(c->compositor && (c->dma ? c->formats == (version == 3 ? 15u : 0u) : c->formats == 0));
}
static void disconnected(struct Client *c) {
    if (c->dma) zwp_linux_dmabuf_v1_destroy(c->dma);
    wl_compositor_destroy(c->compositor); wl_registry_destroy(c->registry); wl_display_disconnect(c->display);
}
static void created(void *data, struct zwp_linux_buffer_params_v1 *params, struct wl_buffer *buffer) {
    (void)params; struct Client *c = data; assert(!c->created); c->created = buffer;
}
static void failed(void *data, struct zwp_linux_buffer_params_v1 *params) { (void)params; ++((struct Client *)data)->failed; }
static const struct zwp_linux_buffer_params_v1_listener params_listener = {created, failed};
static struct zwp_linux_buffer_params_v1 *params(struct Client *c, int fd, uint64_t modifier, uint32_t stride) {
    struct zwp_linux_buffer_params_v1 *p = zwp_linux_dmabuf_v1_create_params(c->dma);
    zwp_linux_buffer_params_v1_add_listener(p, &params_listener, c);
    if (fd >= 0) zwp_linux_buffer_params_v1_add(p, fd, 0, OFFSET, stride, modifier >> 32, modifier);
    return p;
}
static void import_failure(struct Client *c, int fd, uint64_t modifier, uint32_t flags) {
    unsigned before = c->failed;
    struct zwp_linux_buffer_params_v1 *p = params(c, fd, modifier, STRIDE);
    zwp_linux_buffer_params_v1_create(p, WIDTH, HEIGHT, DRM_FORMAT_ABGR8888, flags);
    assert(wl_display_roundtrip(c->display) >= 0 && c->failed == before + 1 && !c->created);
    zwp_linux_buffer_params_v1_destroy(p);
}
static void protocol_error(struct Client *c, int fd, unsigned which) {
    struct zwp_linux_buffer_params_v1 *p = params(c, which == 0 ? -1 : fd, 0, which == 3 ? 1 : STRIDE);
    uint32_t expected;
    struct wl_buffer *invalid = NULL;
    switch (which) {
        case 0: expected = ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INCOMPLETE; break;
        case 1:
            expected = ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_PLANE_SET;
            zwp_linux_buffer_params_v1_add(p, fd, 0, 0, STRIDE, 0, 0); break;
        case 2: expected = ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_DIMENSIONS; break;
        case 3: expected = ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_OUT_OF_BOUNDS; break;
        case 4:
            expected = ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_ALREADY_USED;
            zwp_linux_buffer_params_v1_create(p, WIDTH, HEIGHT, DRM_FORMAT_ABGR8888, 0); break;
        case 5:
            expected = ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_WL_BUFFER;
            invalid = zwp_linux_buffer_params_v1_create_immed(p, WIDTH, HEIGHT, DRM_FORMAT_ABGR8888, 0); break;
        case 7: expected = ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_FORMAT; break;
        default:
            expected = ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_PLANE_IDX;
            zwp_linux_buffer_params_v1_add(p, fd, 1, 0, STRIDE, 0, 0); break;
    }
    if (which != 5) zwp_linux_buffer_params_v1_create(p, which == 2 ? -1 : WIDTH, HEIGHT, DRM_FORMAT_ABGR8888,
        which == 7 ? 0x80000000u : 0);
    assert(wl_display_roundtrip(c->display) < 0 && wl_display_get_error(c->display) == EPROTO);
    const struct wl_interface *interface;
    assert(wl_display_get_protocol_error(c->display, &interface, NULL) == expected);
    assert(interface && !strcmp(interface->name, "zwp_linux_buffer_params_v1"));
    if (invalid) wl_buffer_destroy(invalid);
    zwp_linux_buffer_params_v1_destroy(p);
}
#ifdef __ANDROID__
static void release(void *data, struct wl_buffer *buffer) { (void)buffer; ++((struct Client *)data)->releases; }
static const struct wl_buffer_listener buffer_listener = {release};
static void render_client(struct Client *c, struct Producer *producer) {
    uint32_t formats[] = {DRM_FORMAT_ABGR8888, DRM_FORMAT_XBGR8888, DRM_FORMAT_ARGB8888, DRM_FORMAT_XRGB8888};
    struct wl_surface *surface = wl_compositor_create_surface(c->compositor);
    for (unsigned format = 0; format < 4; ++format) {
        struct zwp_linux_buffer_params_v1 *p = params(c, producer->fd, 0, STRIDE);
        struct wl_buffer *buffer;
        if (format % 2) buffer = zwp_linux_buffer_params_v1_create_immed(p, WIDTH, HEIGHT, formats[format], 0);
        else {
            zwp_linux_buffer_params_v1_create(p, WIDTH, HEIGHT, formats[format], 0);
            assert(wl_display_roundtrip(c->display) >= 0 && c->created);
            buffer = c->created; c->created = NULL;
        }
        zwp_linux_buffer_params_v1_destroy(p);
        wl_buffer_add_listener(buffer, &buffer_listener, c);
        for (unsigned frame = 0; frame < 32; ++frame) {
            uint32_t color = format < 2 ? 0xff441122 : 0xff221144;
            producer_write(producer, color, color);
            unsigned releases = c->releases;
            wl_surface_attach(surface, buffer, 0, 0); wl_surface_damage_buffer(surface, 0, 0, WIDTH, HEIGHT);
            wl_surface_commit(surface);
            assert(wl_display_roundtrip(c->display) >= 0 && c->releases == releases);
            wl_surface_attach(surface, NULL, 0, 0); wl_surface_commit(surface);
            assert(wl_display_roundtrip(c->display) >= 0 && c->releases == releases + 1);
        }
        wl_buffer_destroy(buffer);
    }
    /* The committed texture must retain its allocation after the protocol object goes away. */
    producer_write(producer, 0xff441122, 0xff441122);
    struct zwp_linux_buffer_params_v1 *p = params(c, producer->fd, 0, STRIDE);
    struct wl_buffer *buffer = zwp_linux_buffer_params_v1_create_immed(p, WIDTH, HEIGHT, DRM_FORMAT_ABGR8888, 0);
    zwp_linux_buffer_params_v1_destroy(p);
    zwp_linux_dmabuf_v1_destroy(c->dma); c->dma = NULL;
    wl_surface_attach(surface, buffer, 0, 0); wl_surface_commit(surface); wl_buffer_destroy(buffer);
    wl_surface_damage_buffer(surface, 0, 0, WIDTH, HEIGHT); wl_surface_commit(surface);
    assert(wl_display_roundtrip(c->display) >= 0);
    wl_surface_destroy(surface);
    assert(wl_display_roundtrip(c->display) >= 0);
}
#endif
static unsigned fd_count(void) {
    DIR *directory = opendir("/proc/self/fd"); assert(directory);
    unsigned count = 0;
    while (readdir(directory)) ++count;
    closedir(directory); return count;
}
int main(int argc, char **argv) {
    bool required = argc > 1 && !strcmp(argv[1], "--required");
    struct Server server = {.software = argc > 1 && !strcmp(argv[1], "--software")};
    assert(pipe(server.stop) == 0);
    for (unsigned i = 0; i < CONNECTIONS; ++i) assert(socketpair(AF_UNIX, SOCK_STREAM, 0, server.sockets[i]) == 0);
    pthread_t thread; assert(pthread_create(&thread, NULL, serve, &server) == 0);
    struct Client c; connected(&c, server.sockets[0][1], 3);
    bool supported = c.dma != NULL;
    if (supported) {
        int fake = syscall(SYS_memfd_create, "not-dma", MFD_CLOEXEC); assert(fake >= 0 && ftruncate(fake, ALLOCATION) == 0);
        import_failure(&c, fake, 0, 0);
        unsigned before = fd_count();
        for (unsigned i = 0; i < 32; ++i) import_failure(&c, fake, 0, 0);
        assert(fd_count() == before);
        import_failure(&c, fake, DRM_FORMAT_MOD_INVALID, 0); import_failure(&c, fake, 0, 1);
#ifdef __ANDROID__
        struct Producer producer = {.fd = -1}; assert(producer_create(&producer));
        render_client(&c, &producer); producer_destroy(&producer);
#endif
        disconnected(&c);
        for (unsigned i = 0; i < ERROR_CASES; ++i) {
            connected(&c, server.sockets[i + 1][1], 3);
            protocol_error(&c, fake, i); disconnected(&c);
        }
        connected(&c, server.sockets[CONNECTIONS - 1][1], 2);
        import_failure(&c, fake, 0, 0); disconnected(&c); close(fake);
    } else {
        disconnected(&c);
        for (unsigned i = 1; i < CONNECTIONS; ++i) close(server.sockets[i][1]);
    }
    assert(write(server.stop[1], "x", 1) == 1);
    // EVENT_WAIT: server termination releases resources on their owning thread; CTest bounds a stall.
    assert(pthread_join(thread, NULL) == 0);
    close(server.stop[0]); close(server.stop[1]);
    printf("DMA-BUF advertised=%d capable=%d rendered=%u\n", supported, server.capable, server.frames); fflush(stdout);
    assert(server.capable == supported && server.frames == (supported ? 130u : 0u));
    puts(supported ? "Wayland DMA-BUF: 130 GPU frames, four formats, async/immediate creation, retention, release and protocol errors passed" :
        "DMA-BUF global absent on unsupported renderer");
    return supported || server.software ? 0 : required ? 1 : 77;
}
