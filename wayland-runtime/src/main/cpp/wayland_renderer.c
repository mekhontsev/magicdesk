#include "wayland_renderer.h"
#include <assert.h>
#include <drm_fourcc.h>
#include <stdlib.h>
#include <string.h>
#include <wlr/interfaces/wlr_buffer.h>
#include <wlr/render/allocator.h>
#include <wlr/render/drm_format_set.h>
#include <wlr/render/dmabuf.h>
#include <wlr/render/interface.h>
#include <wlr/render/pass.h>

struct Buffer { struct wlr_buffer base; MdgImage *image; struct wlr_dmabuf_attributes dma; };
struct Allocator { struct wlr_allocator base; MdgDevice *device; };
struct Texture {
    struct wlr_texture base;
    struct wl_list link;
    MdgImage *image;
    void *pixels;
    MdgFormat format;
    struct wlr_buffer *borrowed;
};
struct Pass {
    struct wlr_render_pass base;
    struct wlr_buffer *buffer;
    MdgPass *commands;
    bool failed;
};
struct Renderer {
    struct wlr_renderer base;
    MdgDevice *device;
    struct wlr_drm_format_set texture_formats, render_formats, dma_formats;
    struct wl_list textures;
    struct Pass pass;
};

static bool format(uint32_t drm, MdgFormat *result) {
    switch (drm) {
        case DRM_FORMAT_ABGR8888: *result = MDG_RGBA; return true;
        case DRM_FORMAT_XBGR8888: *result = MDG_RGBX; return true;
        case DRM_FORMAT_ARGB8888: *result = MDG_BGRA; return true;
        case DRM_FORMAT_XRGB8888: *result = MDG_BGRX; return true;
        default: return false;
    }
}
static void buffer_destroy(struct wlr_buffer *base) {
    struct Buffer *buffer = (void *)base;
    wlr_dmabuf_attributes_finish(&buffer->dma);
    mdg_image_unref(buffer->image); free(buffer);
}
static bool buffer_dmabuf(struct wlr_buffer *base, struct wlr_dmabuf_attributes *attributes) {
    struct Buffer *buffer = (void *)base;
    if (!buffer->dma.n_planes) return false;
    *attributes = buffer->dma;
    return true;
}
static bool buffer_map(struct wlr_buffer *base, uint32_t flags, void **data, uint32_t *format, size_t *stride) {
    struct Buffer *buffer = (void *)base;
    if (buffer->dma.n_planes) return false;
    *format = DRM_FORMAT_ABGR8888;
    return mdg_map(buffer->image, flags & WLR_BUFFER_DATA_PTR_ACCESS_WRITE, data, stride);
}
static void buffer_unmap(struct wlr_buffer *base) { mdg_unmap(((struct Buffer *)base)->image); }
static const struct wlr_buffer_impl buffer_impl = {
    .destroy = buffer_destroy, .begin_data_ptr_access = buffer_map, .end_data_ptr_access = buffer_unmap,
    .get_dmabuf = buffer_dmabuf,
};
MdgImage *mdw_buffer_image(struct wlr_buffer *base) {
    return base && base->impl == &buffer_impl ? ((struct Buffer *)base)->image : NULL;
}
static struct wlr_buffer *allocate(struct wlr_allocator *base, int width, int height, const struct wlr_drm_format *format) {
    if (format->format != DRM_FORMAT_ABGR8888) return NULL;
    struct Allocator *allocator = (void *)base;
    struct Buffer *buffer = calloc(1, sizeof(*buffer));
    if (!buffer) return NULL;
    buffer->image = mdg_image_create(allocator->device, width, height);
    if (!buffer->image) { free(buffer); return NULL; }
    wlr_buffer_init(&buffer->base, &buffer_impl, width, height);
    return &buffer->base;
}
static void allocator_destroy(struct wlr_allocator *base) { free(base); }
static const struct wlr_allocator_interface allocator_impl = {.create_buffer = allocate, .destroy = allocator_destroy};
struct wlr_allocator *mdw_allocator_create(struct wlr_renderer *base) {
    struct Allocator *allocator = calloc(1, sizeof(*allocator));
    if (!allocator) return NULL;
    allocator->device = mdw_renderer_device(base);
    wlr_allocator_init(&allocator->base, &allocator_impl, WLR_BUFFER_CAP_DATA_PTR);
    return &allocator->base;
}
MdgDevice *mdw_renderer_device(struct wlr_renderer *base) { return ((struct Renderer *)base)->device; }

struct wlr_buffer *mdw_renderer_import_dmabuf(struct wlr_renderer *base, const struct wlr_dmabuf_attributes *attributes) {
    MdgFormat pixel_format;
    if (!attributes || attributes->n_planes != 1 || attributes->modifier != DRM_FORMAT_MOD_LINEAR ||
            attributes->width < 1 || attributes->height < 1 || !format(attributes->format, &pixel_format)) return NULL;
    MdgLinearDmaBuf source = {.fd = attributes->fd[0], .width = attributes->width, .height = attributes->height,
        .offset = attributes->offset[0], .stride = attributes->stride[0], .format = pixel_format};
    MdgImage *image = mdg_image_linear_dmabuf(mdw_renderer_device(base), &source);
    if (!image) return NULL;
    struct Buffer *buffer = calloc(1, sizeof(*buffer));
    if (!buffer) { mdg_image_unref(image); return NULL; }
    if (!wlr_dmabuf_attributes_copy(&buffer->dma, attributes)) { free(buffer); mdg_image_unref(image); return NULL; }
    buffer->image = image;
    wlr_buffer_init(&buffer->base, &buffer_impl, source.width, source.height);
    return &buffer->base;
}

static void texture_destroy(struct wlr_texture *base) {
    struct Texture *texture = (void *)base;
    mdg_image_unref(texture->image); free(texture->pixels);
    wlr_buffer_unlock(texture->borrowed);
    wl_list_remove(&texture->link); free(texture);
}
static bool texture_update(struct wlr_texture *base, struct wlr_buffer *buffer, const pixman_region32_t *damage) {
    (void)damage;
    struct Texture *texture = (void *)base;
    if (texture->borrowed) return texture->borrowed == buffer;
    void *pixels; uint32_t drm; size_t stride; MdgFormat pixel_format;
    if (buffer->width != (int)base->width || buffer->height != (int)base->height ||
        !wlr_buffer_begin_data_ptr_access(buffer, WLR_BUFFER_DATA_PTR_ACCESS_READ, &pixels, &drm, &stride)) return false;
    bool ok = format(drm, &pixel_format) && stride >= (size_t)buffer->width * 4;
    if (ok && texture->image && texture->format != pixel_format) ok = false;
    if (ok) {
        size_t row = (size_t)buffer->width * 4;
        if (!texture->pixels) texture->pixels = malloc(row * buffer->height);
        ok = texture->pixels != NULL;
        if (ok) {
            for (int y = 0; y < buffer->height; ++y) memcpy((char *)texture->pixels + y * row, (char *)pixels + y * stride, row);
            if (!texture->image) texture->image = mdg_image_cpu(mdw_renderer_device(base->renderer),
                buffer->width, buffer->height, row, pixel_format, texture->pixels);
            texture->format = pixel_format;
            ok = texture->image != NULL;
        }
    }
    wlr_buffer_end_data_ptr_access(buffer);
    return ok;
}
static const struct wlr_texture_impl texture_impl = {.destroy = texture_destroy, .update_from_buffer = texture_update};
static struct wlr_texture *texture_from_buffer(struct wlr_renderer *base, struct wlr_buffer *buffer) {
    if (buffer->width < 1 || buffer->height < 1 || buffer->width > 8192 || buffer->height > 8192) return NULL;
    struct Renderer *renderer = (void *)base;
    struct Texture *texture = calloc(1, sizeof(*texture));
    if (!texture) return NULL;
    wlr_texture_init(&texture->base, base, &texture_impl, buffer->width, buffer->height);
    wl_list_insert(&renderer->textures, &texture->link);
    MdgImage *image = mdw_buffer_image(buffer);
    if (image) {
        texture->image = image; mdg_image_ref(image);
        texture->borrowed = wlr_buffer_lock(buffer);
    }
    else if (!texture_update(&texture->base, buffer, NULL)) { texture_destroy(&texture->base); return NULL; }
    return &texture->base;
}

static bool submit(struct wlr_render_pass *base) {
    struct Pass *pass = (void *)base;
    bool ok = !pass->failed;
    if (ok) ok = mdg_pass_submit(pass->commands);
    else mdg_pass_cancel(pass->commands);
    wlr_buffer_unlock(pass->buffer);
    pass->commands = NULL; pass->buffer = NULL;
    return ok;
}
static void add_texture(struct wlr_render_pass *base, const struct wlr_render_texture_options *options) {
    struct Pass *pass = (void *)base;
    struct Texture *texture = (void *)options->texture;
    struct wlr_fbox source; struct wlr_box destination;
    wlr_render_texture_options_get_src_box(options, &source);
    wlr_render_texture_options_get_dst_box(options, &destination);
    static const unsigned transforms[] = {0, 3, 2, 1, 4, 5, 6, 7};
    MdgDraw draw = {.image = texture->image, .source = {source.x, source.y, source.width, source.height},
        .destination = {destination.x, destination.y, destination.width, destination.height},
        .opacity = wlr_render_texture_options_get_alpha(options),
        .transform = transforms[options->transform], .linear = options->filter_mode == WLR_SCALE_FILTER_BILINEAR,
        .blend = options->blend_mode == WLR_RENDER_BLEND_MODE_PREMULTIPLIED};
    pixman_box32_t full = {0, 0, pass->buffer->width, pass->buffer->height};
    int count = 1;
    pixman_box32_t *clips = options->clip ? pixman_region32_rectangles((pixman_region32_t *)options->clip, &count) : &full;
    for (int i = 0; i < count && !pass->failed; ++i) {
        draw.clip = (MdgClip){clips[i].x1, clips[i].y1, clips[i].x2 - clips[i].x1, clips[i].y2 - clips[i].y1};
        pass->failed = !mdg_pass_draw(pass->commands, &draw);
    }
}
static void add_rect(struct wlr_render_pass *base, const struct wlr_render_rect_options *options) {
    struct Pass *pass = (void *)base;
    pixman_box32_t full = {0, 0, pass->buffer->width, pass->buffer->height};
    int count = 1;
    pixman_box32_t *clips = options->clip ? pixman_region32_rectangles((pixman_region32_t *)options->clip, &count) : &full;
    struct wlr_box rectangle;
    wlr_render_rect_options_get_box(options, pass->buffer, &rectangle);
    MdgBox box = {rectangle.x, rectangle.y, rectangle.width, rectangle.height};
    float color[] = {options->color.r, options->color.g, options->color.b, options->color.a};
    for (int i = 0; i < count && !pass->failed; ++i) {
        MdgClip clip = {clips[i].x1, clips[i].y1, clips[i].x2 - clips[i].x1, clips[i].y2 - clips[i].y1};
        pass->failed = !mdg_pass_rect(pass->commands, box, clip, color, options->blend_mode == WLR_RENDER_BLEND_MODE_PREMULTIPLIED);
    }
}
static const struct wlr_render_pass_impl pass_impl = {.submit = submit, .add_texture = add_texture, .add_rect = add_rect};
static struct wlr_render_pass *begin(struct wlr_renderer *base, struct wlr_buffer *buffer, const struct wlr_buffer_pass_options *options) {
    struct Renderer *renderer = (void *)base;
    if (renderer->pass.commands || (options && options->color_transform)) return NULL;
    MdgImage *target = mdw_buffer_image(buffer);
    float clear[] = {0, 0, 0, 0};
    MdgPass *commands = mdg_pass_begin(renderer->device, target, clear, true);
    if (!commands) return NULL;
    renderer->pass = (struct Pass){.commands = commands, .buffer = wlr_buffer_lock(buffer)};
    wlr_render_pass_init(&renderer->pass.base, &pass_impl);
    return &renderer->pass.base;
}
static const struct wlr_drm_format_set *texture_formats(struct wlr_renderer *base, uint32_t caps) {
    if (caps & WLR_BUFFER_CAP_DMABUF) {
        struct Renderer *renderer = (void *)base;
        return mdg_device_linear_dmabuf(renderer->device) ? &renderer->dma_formats : NULL;
    }
    return caps & WLR_BUFFER_CAP_DATA_PTR ? &((struct Renderer *)base)->texture_formats : NULL;
}
static const struct wlr_drm_format_set *render_formats(struct wlr_renderer *base) { return &((struct Renderer *)base)->render_formats; }
static void destroy(struct wlr_renderer *base) {
    struct Renderer *renderer = (void *)base;
    struct Texture *texture, *next;
    wl_list_for_each_safe(texture, next, &renderer->textures, link) texture_destroy(&texture->base);
    mdg_device_destroy(renderer->device);
    wlr_drm_format_set_finish(&renderer->texture_formats);
    wlr_drm_format_set_finish(&renderer->render_formats);
    wlr_drm_format_set_finish(&renderer->dma_formats);
    free(renderer);
}
static const struct wlr_renderer_impl renderer_impl = {.destroy = destroy, .texture_from_buffer = texture_from_buffer,
    .get_texture_formats = texture_formats, .get_render_formats = render_formats, .begin_buffer_pass = begin};
struct wlr_renderer *mdw_renderer_create(void) {
    struct Renderer *renderer = calloc(1, sizeof(*renderer));
    if (!renderer) return NULL;
    wl_list_init(&renderer->textures);
    renderer->device = mdg_device_create(false);
    if (!renderer->device) { free(renderer); return NULL; }
    wlr_renderer_init(&renderer->base, &renderer_impl, WLR_BUFFER_CAP_DATA_PTR);
    uint32_t formats[] = {DRM_FORMAT_ABGR8888, DRM_FORMAT_XBGR8888, DRM_FORMAT_ARGB8888, DRM_FORMAT_XRGB8888};
    for (unsigned i = 0; i < sizeof(formats) / sizeof(*formats); ++i)
        if (!wlr_drm_format_set_add(&renderer->texture_formats, formats[i], DRM_FORMAT_MOD_INVALID)) goto fail;
    if (mdg_device_linear_dmabuf(renderer->device))
        for (unsigned i = 0; i < sizeof(formats) / sizeof(*formats); ++i)
            if (!wlr_drm_format_set_add(&renderer->dma_formats, formats[i], DRM_FORMAT_MOD_LINEAR)) goto fail;
    if (!wlr_drm_format_set_add(&renderer->render_formats, DRM_FORMAT_ABGR8888, DRM_FORMAT_MOD_INVALID)) goto fail;
    return &renderer->base;
fail:
    wlr_renderer_destroy(&renderer->base); return NULL;
}
