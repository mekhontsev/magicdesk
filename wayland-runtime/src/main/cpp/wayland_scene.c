#include "wayland_internal.h"
#include <drm_fourcc.h>
#include <math.h>
#include <wlr/render/pass.h>
#include <wlr/types/wlr_buffer.h>
#include <wlr/types/wlr_compositor.h>
#include <wlr/types/wlr_output.h>
#include <wlr/util/transform.h>

struct Render {
    struct wlr_render_pass *pass;
    struct wlr_scene_output *output;
    bool valid;
    struct wlr_surface *owner;
    bool dependents;
};

static void render_buffer(struct wlr_scene_buffer *buffer, int x, int y, void *data) {
    struct Render *render = data;
    if (!buffer->buffer) return;
    if (render->owner) {
        struct wlr_scene_surface *scene = wlr_scene_surface_try_from_buffer(buffer);
        if (!scene || (wlr_surface_get_root_surface(scene->surface) != render->owner) != render->dependents) return;
    }
    struct wlr_client_buffer *client = wlr_client_buffer_get(buffer->buffer);
    if (!client) { render->valid = false; return; }
    if (!client->texture) return;
    int width = buffer->buffer->width, height = buffer->buffer->height;
    wlr_output_transform_coords(buffer->transform, &width, &height);
    if (buffer->dst_width > 0) width = buffer->dst_width;
    if (buffer->dst_height > 0) height = buffer->dst_height;
    double scale = render->output->output->scale;
    int left = (int)round((x - render->output->x) * scale);
    int top = (int)round((y - render->output->y) * scale);
    wlr_render_pass_add_texture(render->pass, &(struct wlr_render_texture_options) {
        .texture = client->texture,
        .src_box = buffer->src_box,
        .dst_box = {left, top, (int)round((x - render->output->x + width) * scale) - left,
            (int)round((y - render->output->y + height) * scale) - top},
        .alpha = &buffer->opacity,
        .transform = wlr_output_transform_invert(buffer->transform),
        .filter_mode = buffer->filter_mode,
    });
}

bool mdw_scene_render_family(struct wlr_scene_output *output, struct wlr_surface *owner, bool dependents) {
    // Borrowed outputs are untransformed views of client-only scene trees.
    // wlroots 0.18's scene pass clears opaque black; use its render API and cached
    // client textures for alpha composition, retaining the scene's layout and damage events.
    if (output->output->transform != WL_OUTPUT_TRANSFORM_NORMAL)
        return false;
    if (!output->output->needs_frame && !pixman_region32_not_empty(&output->damage_ring.current))
        return true;
    struct wlr_output_state state;
    wlr_output_state_init(&state);
    wlr_output_state_set_render_format(&state, DRM_FORMAT_ABGR8888);
    struct wlr_render_pass *pass = wlr_output_begin_render_pass(output->output, &state, NULL, NULL);
    if (!pass) { wlr_output_state_finish(&state); return false; }
    // This pass paints the whole borrowed buffer. Retire current damage before
    // commit callbacks can enqueue new damage; failure must invalidate it again.
    wlr_damage_ring_rotate(&output->damage_ring);
    wlr_render_pass_add_rect(pass, &(struct wlr_render_rect_options) {
        .box = {.width = output->output->width, .height = output->output->height},
        .color = {0}, .blend_mode = WLR_RENDER_BLEND_MODE_NONE,
    });
    struct Render render = {.pass = pass, .output = output, .valid = true, .owner = owner, .dependents = dependents};
    wlr_scene_node_for_each_buffer(&output->scene->tree.node, render_buffer, &render);
    bool submitted = wlr_render_pass_submit(pass);
    bool committed = submitted && render.valid && wlr_output_commit_state(output->output, &state);
    if (!committed) wlr_damage_ring_add_whole(&output->damage_ring);
    wlr_output_state_finish(&state);
    return committed;
}

bool mdw_scene_render_transparent(struct wlr_scene_output *output) {
    return mdw_scene_render_family(output, NULL, false);
}
