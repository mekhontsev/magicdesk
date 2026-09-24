#include "frame_fd.h"
#include <drm_fourcc.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/memfd.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

int mdw_frame_export(const MdwFrame *frame) {
    if (!frame || !frame->image || frame->width < 1 || frame->height < 1 ||
            frame->width > 4096 || frame->height > 4096) {
        errno = EINVAL;
        return -1;
    }
    size_t size = (size_t)frame->width * frame->height * 4;
    int descriptor = syscall(SYS_memfd_create, "magicdesk-frame", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (descriptor < 0) return -1;
    if (ftruncate(descriptor, size) < 0) goto fail;
    uint8_t *pixels = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, descriptor, 0);
    if (pixels == MAP_FAILED) goto fail;
    bool copied = mdg_image_read(frame->image, pixels, (size_t)frame->width * 4);
    munmap(pixels, size);
    if (!copied) { errno = EIO; goto fail; }
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
