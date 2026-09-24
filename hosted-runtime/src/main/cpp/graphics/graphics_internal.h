#ifndef MAGICDESK_GRAPHICS_INTERNAL_H
#define MAGICDESK_GRAPHICS_INTERNAL_H

#include "graphics.h"
#include <stdlib.h>
#include <string.h>

enum { MDG_MAX_DIMENSION = 8192, MDG_MAX_DRAWS = 1024, MDG_SLOTS = 3 };
typedef struct {
    MdgDraw draw;
    float color[4];
    bool solid;
} MdgCommand;

struct MdgImage {
    MdgDevice *device;
    unsigned references, width, height;
    size_t stride;
    MdgFormat format;
    const void *pixels;
    void *owned_pixels;
    AHardwareBuffer *hardware;
    void *gpu;
    int fence;
    int dmabuf_fd;
    uint32_t dmabuf_offset;
    bool external;
};
struct MdgPass {
    MdgDevice *device;
    MdgImage *target;
    MdgCommand commands[MDG_MAX_DRAWS];
    unsigned count;
    float clear[4];
    bool preserve, recording, submitted;
    int completion;
    void *gpu;
};
struct MdgDevice {
    void *gpu;
    char name[256];
    MdgStats stats;
    MdgPass passes[MDG_SLOTS];
};

bool mdg_software_submit(MdgPass *pass);
MdgImage *mdg_image_new(MdgDevice *device, unsigned width, unsigned height);
bool mdg_map(MdgImage *image, bool write, void **pixels, size_t *stride);
void mdg_unmap(MdgImage *image);
bool mdg_wait_fence(int fd);
void mdg_pass_release(MdgPass *pass);
bool mdg_vk_create(MdgDevice *device);
bool mdg_vk_ready(const MdgDevice *device);
void mdg_vk_destroy(MdgDevice *device);
bool mdg_vk_image(MdgImage *image);
bool mdg_vk_linear_dmabuf(const MdgDevice *device);
bool mdg_vk_import_dmabuf(MdgImage *image, const MdgLinearDmaBuf *buffer, MdgImportError *error);
bool mdg_dmabuf_acquire(MdgImage *image, int *owned_fd);
MdgSubmitResult mdg_vk_prepare(MdgPass *pass, int *wait_fd);
void mdg_vk_image_destroy(MdgImage *image);
bool mdg_vk_submit(MdgPass *pass);
bool mdg_vk_complete(MdgPass *pass);
void mdg_vk_pass_destroy(MdgPass *pass);

#endif
