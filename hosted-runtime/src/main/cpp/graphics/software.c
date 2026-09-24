#include "graphics_internal.h"
#include <math.h>

static int clamp(int value, int limit) { return value < 0 ? 0 : value >= limit ? limit - 1 : value; }
static void pixel(const uint8_t *data, size_t stride, MdgImage *image, int x, int y, float value[4]) {
    const uint8_t *p = data + clamp(y, image->height) * stride + clamp(x, image->width) * 4;
    bool bgra = image->format == MDG_BGRA || image->format == MDG_BGRX;
    value[0] = p[bgra ? 2 : 0]; value[1] = p[1]; value[2] = p[bgra ? 0 : 2];
    value[3] = image->format == MDG_RGBX || image->format == MDG_BGRX ? 255 : p[3];
}

static void sample(const uint8_t *data, size_t stride, const MdgDraw *draw, float u, float v, float value[4]) {
    if (draw->transform & 4) u = 1 - u;
    for (unsigned i = 0; i < (draw->transform & 3); ++i) { float q = u; u = v; v = 1 - q; }
    float x = draw->source.x + u * draw->source.width;
    float y = draw->source.y + v * draw->source.height;
    if (!draw->linear) pixel(data, stride, draw->image, floorf(x), floorf(y), value);
    else {
        x -= .5f; y -= .5f;
        int ix = floorf(x), iy = floorf(y);
        float fx = x - ix, fy = y - iy, a[4], b[4], c[4], d[4];
        pixel(data, stride, draw->image, ix, iy, a); pixel(data, stride, draw->image, ix + 1, iy, b);
        pixel(data, stride, draw->image, ix, iy + 1, c); pixel(data, stride, draw->image, ix + 1, iy + 1, d);
        for (unsigned k = 0; k < 4; ++k)
            value[k] = (a[k] * (1 - fx) + b[k] * fx) * (1 - fy) + (c[k] * (1 - fx) + d[k] * fx) * fy;
    }
    if (draw->swap_red_blue) { float r = value[0]; value[0] = value[2]; value[2] = r; }
    for (unsigned i = 0; i < 4; ++i) value[i] *= draw->opacity;
}

bool mdg_software_submit(MdgPass *pass) {
    void *target; size_t stride;
    MdgImage *image = pass->target;
    bool bgra = image->format == MDG_BGRA || image->format == MDG_BGRX;
    if (!mdg_map(image, true, &target, &stride)) return false;
    if (!pass->preserve) {
        uint8_t color[4];
        for (unsigned k = 0; k < 4; ++k) color[k] = lrintf(fminf(1, fmaxf(0, pass->clear[k])) * 255);
        if (bgra) { uint8_t red = color[0]; color[0] = color[2]; color[2] = red; }
        for (unsigned y = 0; y < image->height; ++y)
            for (unsigned x = 0; x < image->width; ++x) memcpy((uint8_t *)target + y * stride + x * 4, color, 4);
    }
    bool ok = true;
    for (unsigned i = 0; i < pass->count; ++i) {
        MdgCommand *command = &pass->commands[i];
        const MdgDraw *draw = &command->draw;
        const MdgBox *box = &draw->destination;
        void *source = NULL; size_t source_stride = 0;
        if (!command->solid && !mdg_map(draw->image, false, &source, &source_stride)) { ok = false; break; }
        int left = fmaxf(fmaxf(0, draw->clip.x), ceilf(box->x - .5f));
        int top = fmaxf(fmaxf(0, draw->clip.y), ceilf(box->y - .5f));
        int right = fminf(fminf(image->width, (int64_t)draw->clip.x + draw->clip.width), ceilf(box->x + box->width - .5f));
        int bottom = fminf(fminf(image->height, (int64_t)draw->clip.y + draw->clip.height), ceilf(box->y + box->height - .5f));
        for (int y = top; y < bottom; ++y) for (int x = left; x < right; ++x) {
            float value[4];
            if (command->solid) for (unsigned k = 0; k < 4; ++k) value[k] = command->color[k] * 255;
            else sample(source, source_stride, draw, (x + .5f - box->x) / box->width,
                (y + .5f - box->y) / box->height, value);
            uint8_t *p = (uint8_t *)target + y * stride + x * 4;
            for (unsigned k = 0; k < 4; ++k) {
                unsigned channel = bgra && k != 1 && k != 3 ? 2 - k : k;
                float result = value[k] + (draw->blend ? p[channel] * (1 - value[3] / 255) : 0);
                p[channel] = lrintf(fminf(255, fmaxf(0, result)));
            }
        }
        if (source) mdg_unmap(draw->image);
    }
    mdg_unmap(image);
    return ok;
}
