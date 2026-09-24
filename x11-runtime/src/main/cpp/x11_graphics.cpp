#include "x11_graphics.h"
#include "graphics/graphics.h"

const LorieGraphics* magicDeskX11Graphics() {
    static const LorieGraphics api = {
        .create = +[]() -> void* { return mdg_device_create(false); },
        .destroy = +[](void* device) { mdg_device_destroy((MdgDevice*)device); },
        .image = +[](void* device, AHardwareBuffer* buffer, const void* pixels,
                unsigned width, unsigned height, size_t stride, unsigned format) -> void* {
            if (buffer) return mdg_image_hardware((MdgDevice*)device, buffer);
            MdgFormat type = format == 5 ? MDG_BGRA : format == 2 ? MDG_RGBX : MDG_RGBA;
            return mdg_image_cpu((MdgDevice*)device, width, height, stride, type, pixels);
        },
        .releaseImage = +[](void* image) { mdg_image_unref((MdgImage*)image); },
        .surface = +[](void* device, ANativeWindow* window) -> void* { return mdg_surface_create((MdgDevice*)device, window); },
        .releaseSurface = +[](void* surface) { mdg_surface_destroy((MdgSurface*)surface); },
        .acquire = +[](void* surface, unsigned width, unsigned height) -> void* {
            return mdg_surface_acquire((MdgSurface*)surface, width, height);
        },
        .begin = +[](void* device, void* target, const float clear[4], bool preserve) -> void* {
            return mdg_pass_begin((MdgDevice*)device, (MdgImage*)target, clear, preserve);
        },
        .draw = +[](void* pass, const LorieGraphicsDraw* draw) {
            MdgDraw command = {.image = (MdgImage*)draw->image,
                .source = {draw->sx, draw->sy, draw->sw, draw->sh},
                .destination = {draw->x, draw->y, draw->width, draw->height},
                .clip = {draw->clipX, draw->clipY, draw->clipWidth, draw->clipHeight},
                .opacity = 1, .blend = draw->blend, .linear = draw->linear, .swap_red_blue = draw->swapRedBlue};
            return mdg_pass_draw((MdgPass*)pass, &command);
        },
        .submit = +[](void* pass) { return mdg_pass_submit_and_wait((MdgPass*)pass); },
        .cancel = +[](void* pass) { mdg_pass_cancel((MdgPass*)pass); },
        .present = +[](void* surface) { return mdg_surface_present((MdgSurface*)surface); },
    };
    return &api;
}
