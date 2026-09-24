#include "wayland_renderer.h"
#include <assert.h>
#include <drm_fourcc.h>
#include <stdio.h>
#include <stdlib.h>
#include <wlr/render/allocator.h>
#include <wlr/render/drm_format_set.h>
#include <wlr/render/interface.h>
#include <wlr/render/pass.h>
#include <wlr/render/pixman.h>

static void render(struct wlr_renderer *renderer, struct wlr_buffer *target, unsigned transform, bool blend) {
    const uint32_t pixels[4] = {0xffee1122, 0xff22dd33, 0xff4433cc, 0xffbb9977};
    struct wlr_texture *texture = wlr_texture_from_pixels(renderer, DRM_FORMAT_ARGB8888, 8, 2, 2, pixels);
    assert(texture);
    struct wlr_render_pass *pass = wlr_renderer_begin_buffer_pass(renderer, target, NULL);
    assert(pass);
    wlr_render_pass_add_rect(pass, &(struct wlr_render_rect_options){
        .color = {.2f, .1f, .3f, .5f}, .blend_mode = WLR_RENDER_BLEND_MODE_NONE});
    pixman_region32_t clip;
    pixman_region32_init_rect(&clip, 2, 1, 11, 9);
    float alpha = blend ? .5f : 1;
    wlr_render_pass_add_texture(pass, &(struct wlr_render_texture_options){
        .texture = texture, .dst_box = {0, 0, 16, 12}, .clip = &clip, .transform = transform, .alpha = &alpha,
        .filter_mode = WLR_SCALE_FILTER_NEAREST, .blend_mode = blend ? WLR_RENDER_BLEND_MODE_PREMULTIPLIED : WLR_RENDER_BLEND_MODE_NONE});
    pixman_region32_fini(&clip);
    assert(wlr_render_pass_submit(pass));
    wlr_texture_destroy(texture);
}
int main(void) {
    struct wlr_renderer *renderer = mdw_renderer_create(), *reference = wlr_pixman_renderer_create();
    assert(renderer && reference);
    struct wlr_allocator *allocator = mdw_allocator_create(renderer);
    assert(allocator);
    const struct wlr_drm_format_set *formats = renderer->impl->get_render_formats(renderer);
    const struct wlr_drm_format *format = wlr_drm_format_set_get(formats, DRM_FORMAT_ABGR8888);
    struct wlr_buffer *actual = wlr_allocator_create_buffer(allocator, 16, 12, format);
    struct wlr_buffer *expected = wlr_allocator_create_buffer(allocator, 16, 12, format);
    assert(actual && expected);
    for (unsigned transform = 0; transform < 8; ++transform) for (unsigned blend = 0; blend < 2; ++blend) {
        render(reference, expected, transform, blend);
        render(renderer, actual, transform, blend);
        uint8_t a[16 * 12 * 4], e[sizeof(a)];
        assert(mdg_image_read(mdw_buffer_image(actual), a, 16 * 4));
        assert(mdg_image_read(mdw_buffer_image(expected), e, 16 * 4));
        for (unsigned i = 0; i < sizeof(a); ++i) if (abs((int)a[i] - e[i]) > 2) {
            fprintf(stderr, "transform=%u blend=%u pixel=%u,%u channel=%u actual=%u expected=%u\n",
                transform, blend, i / 4 % 16, i / 64, i % 4, a[i], e[i]);
            abort();
        }
        mdg_device_collect(mdw_renderer_device(renderer));
    }
    wlr_buffer_drop(actual); wlr_buffer_drop(expected);
    wlr_renderer_destroy(reference); wlr_allocator_destroy(allocator); wlr_renderer_destroy(renderer);
    puts("renderer matches wlroots transforms, clipping, colors and blending");
}
