#include "frame_fd.h"
#include <drm_fourcc.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/memfd.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

int mdw_frame_export(const MdwFrame *frame) {
    if (!frame || !frame->pixels || frame->width < 1 || frame->height < 1 ||
            frame->width > 4096 || frame->height > 4096 ||
            frame->stride < (size_t)frame->width * 4 || frame->stride > SIZE_MAX / frame->height) {
        errno = EINVAL;
        return -1;
    }
    bool swap, alpha;
    switch (frame->format) {
        case DRM_FORMAT_ARGB8888: swap = true; alpha = true; break;
        case DRM_FORMAT_XRGB8888: swap = true; alpha = false; break;
        case DRM_FORMAT_ABGR8888: swap = false; alpha = true; break;
        case DRM_FORMAT_XBGR8888: swap = false; alpha = false; break;
        default: errno = ENOTSUP; return -1;
    }
    size_t size = (size_t)frame->width * frame->height * 4;
    int descriptor = syscall(SYS_memfd_create, "magicdesk-frame", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (descriptor < 0) return -1;
    if (ftruncate(descriptor, size) < 0) goto fail;
    uint8_t *pixels = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, descriptor, 0);
    if (pixels == MAP_FAILED) goto fail;
    for (int row = 0; row < frame->height; ++row) {
        const uint8_t *source = (const uint8_t *)frame->pixels + row * frame->stride;
        uint8_t *target = pixels + (size_t)row * frame->width * 4;
        for (int column = 0; column < frame->width; ++column) {
            target[0] = source[swap ? 2 : 0];
            target[1] = source[1];
            target[2] = source[swap ? 0 : 2];
            target[3] = alpha ? source[3] : 255;
            source += 4;
            target += 4;
        }
    }
    munmap(pixels, size);
    if (fcntl(descriptor, F_ADD_SEALS, F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL) < 0)
        goto fail;
    return descriptor;
fail:
    {
        int error = errno;
        close(descriptor);
        errno = error;
        return -1;
    }
}