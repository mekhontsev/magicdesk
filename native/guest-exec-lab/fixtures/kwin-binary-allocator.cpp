// SPDX-License-Identifier: GPL-2.0-or-later
// Lab-only allocator compiled against the distribution's unmodified KWin headers.
#include <core/graphicsbuffer.h>
#include <core/graphicsbufferallocator.h>
#include <opengl/egldisplay.h>
#include <opengl/eglnativefence.h>

#include <drm_fourcc.h>
#include <fcntl.h>
#include <linux/dma-buf.h>
#include <linux/dma-heap.h>
#include <sys/ioctl.h>
#include <climits>

namespace {
class Buffer final : public KWin::GraphicsBuffer
{
public:
    explicit Buffer(KWin::DmaBufAttributes attributes)
        : attributes_(std::move(attributes)) {}
    QSize size() const override { return {attributes_.width, attributes_.height}; }
    bool hasAlphaChannel() const override { return alphaChannelFromDrmFormat(attributes_.format); }
    const KWin::DmaBufAttributes *dmabufAttributes() const override { return &attributes_; }
private:
    KWin::DmaBufAttributes attributes_;
};
}

extern "C" KWin::GraphicsBuffer *md_kwin_allocate(
    void *, const KWin::GraphicsBufferOptions &options)
{
    if (options.software || options.size.isEmpty()
        || !options.modifiers.contains(DRM_FORMAT_MOD_LINEAR)) {
        return nullptr;
    }
    switch (options.format) {
    case DRM_FORMAT_XRGB8888:
    case DRM_FORMAT_ARGB8888:
    case DRM_FORMAT_XBGR8888:
    case DRM_FORMAT_ABGR8888:
        break;
    default:
        return nullptr;
    }
    const uint64_t stride = (uint64_t(options.size.width()) * 4 + 255) & ~uint64_t(255);
    const uint64_t bytes = stride * options.size.height();
    if (stride > UINT32_MAX || bytes > INT32_MAX) {
        return nullptr;
    }
    static thread_local KWin::FileDescriptor heap(open("/dev/dma_heap/system", O_RDONLY | O_CLOEXEC));
    dma_heap_allocation_data allocation{};
    allocation.len = bytes;
    allocation.fd_flags = O_RDWR | O_CLOEXEC;
    if (!heap.isValid() || ioctl(heap.get(), DMA_HEAP_IOCTL_ALLOC, &allocation) < 0) {
        return nullptr;
    }
    KWin::DmaBufAttributes attributes;
    attributes.width = options.size.width();
    attributes.height = options.size.height();
    attributes.format = options.format;
    attributes.modifier = DRM_FORMAT_MOD_LINEAR;
    attributes.planeCount = 1;
    attributes.fd[0] = KWin::FileDescriptor(allocation.fd);
    attributes.pitch[0] = stride;
    return new Buffer(std::move(attributes));
}

extern "C" bool md_kwin_acquire(KWin::EglDisplay *display, KWin::GraphicsBuffer *buffer)
{
    const auto *attributes = buffer->dmabufAttributes();
    if (!attributes) {
        return false;
    }
    for (int plane = 0; plane < attributes->planeCount; ++plane) {
        dma_buf_export_sync_file sync{.flags = DMA_BUF_SYNC_RW, .fd = -1};
        if (ioctl(attributes->fd[plane].get(), DMA_BUF_IOCTL_EXPORT_SYNC_FILE, &sync) < 0) {
            return false;
        }
        auto fence = KWin::EGLNativeFence::importFence(display, KWin::FileDescriptor(sync.fd));
        if (!fence.waitSync()) {
            return false;
        }
    }
    return true;
}

extern "C" bool md_kwin_release(KWin::GraphicsBuffer *buffer, int fence)
{
    const auto *attributes = buffer->dmabufAttributes();
    if (!attributes || fence < 0) {
        return false;
    }
    for (int plane = 0; plane < attributes->planeCount; ++plane) {
        dma_buf_import_sync_file sync{.flags = DMA_BUF_SYNC_WRITE, .fd = fence};
        if (ioctl(attributes->fd[plane].get(), DMA_BUF_IOCTL_IMPORT_SYNC_FILE, &sync) < 0) {
            return false;
        }
    }
    return true;
}
