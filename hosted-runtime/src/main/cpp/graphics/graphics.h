#ifndef MAGICDESK_GRAPHICS_H
#define MAGICDESK_GRAPHICS_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct MdgDevice MdgDevice;
typedef struct MdgImage MdgImage;
typedef struct MdgPass MdgPass;
typedef struct { uint64_t gpu_frames, software_frames, failed_frames, dma_tail_bytes; } MdgStats;
typedef enum { MDG_SUBMIT_FAILED, MDG_SUBMIT_OK, MDG_SUBMIT_DEFERRED } MdgSubmitResult;
typedef struct AHardwareBuffer AHardwareBuffer;
typedef struct ANativeWindow ANativeWindow;
typedef struct MdgSurface MdgSurface;

typedef enum { MDG_RGBA, MDG_RGBX, MDG_BGRA, MDG_BGRX } MdgFormat;
/* One explicitly linear packed-RGB plane, not an opaque/tiled image FD. */
typedef struct {
    int fd;
    unsigned width, height;
    uint32_t offset, stride;
    MdgFormat format;
} MdgLinearDmaBuf;
/* Borrowed static operation name; code is errno or VkResult for that operation.
 * Size checks publish bytes, not file paths or contents. Filled on failure only. */
typedef struct {
    const char *operation;
    int code;
    uint64_t available, required;
} MdgImportError;
typedef struct { float x, y, width, height; } MdgBox;
typedef struct { int x, y, width, height; } MdgClip;
typedef struct {
    MdgImage *image;
    MdgBox source, destination;
    MdgClip clip;
    float opacity;
    unsigned transform; /* Clockwise quarter turns; bit 2 flips the source horizontally. */
    bool blend, linear, swap_red_blue;
} MdgDraw;

/* One serialized owner per device; no process-global device or rendering mutex. */
MdgDevice *mdg_device_create(bool software_only);
void mdg_device_destroy(MdgDevice *device);
bool mdg_device_gpu(const MdgDevice *device);
/* Optional Vulkan transfer import; individual allocations can still be rejected. */
bool mdg_device_linear_dmabuf(const MdgDevice *device);
const char *mdg_device_name(const MdgDevice *device);
MdgStats mdg_device_stats(const MdgDevice *device);
void mdg_device_collect(MdgDevice *device);

/* Hardware/allocated images retain storage. CPU views borrow pixels through
 * submission or explicit reads; queued GPU work retains its own upload storage. */
MdgImage *mdg_image_create(MdgDevice *device, unsigned width, unsigned height);
MdgImage *mdg_image_cpu(MdgDevice *device, unsigned width, unsigned height,
    size_t stride, MdgFormat format, const void *pixels);
MdgImage *mdg_image_hardware(MdgDevice *device, AHardwareBuffer *buffer);
/* Borrows the FD on entry, retains its own FD on success. GPU sampling only.
 * Producers must honor DMA-BUF implicit fences. Each submission acquires the
 * producer's writes and publishes its read fence before returning. */
MdgImage *mdg_image_linear_dmabuf(MdgDevice *device, const MdgLinearDmaBuf *buffer, MdgImportError *error);
void mdg_image_ref(MdgImage *image);
void mdg_image_unref(MdgImage *image);
unsigned mdg_image_width(const MdgImage *image);
unsigned mdg_image_height(const MdgImage *image);
AHardwareBuffer *mdg_image_buffer(MdgImage *image); /* Borrowed. */
bool mdg_image_read(MdgImage *image, void *rgba, size_t stride);
bool mdg_map(MdgImage *image, bool write, void **pixels, size_t *stride);
void mdg_unmap(MdgImage *image);
bool mdg_device_available(MdgDevice *device);

/* No available command slot returns NULL: the caller waits for a completion event. */
MdgPass *mdg_pass_begin(MdgDevice *device, MdgImage *target, const float clear[4], bool preserve);
bool mdg_pass_draw(MdgPass *pass, const MdgDraw *draw);
bool mdg_pass_rect(MdgPass *pass, MdgBox box, MdgClip clip, const float color[4], bool blend);
/* DEFERRED retains the recording and returns an owned source fence in wait_fd.
 * Observe that fence before retrying, or cancel the recording. No pixels were
 * modified and no completion was published. Other outcomes consume the pass.
 * Producers must retain the consumer's buffer lease through submission. */
MdgSubmitResult mdg_pass_submit(MdgPass *pass, int *wait_fd);
/* Pixel-lock users may wait on their rendering worker, never on a protocol Looper. */
bool mdg_pass_submit_and_wait(MdgPass *pass);
void mdg_pass_cancel(MdgPass *pass);
/* Exports an owned acquire sync_file FD (-1 when complete); failure is never completion. */
bool mdg_image_fence(MdgImage *image, int *owned_fd);
void mdg_image_set_fence(MdgImage *image, int owned_fd);
/* Export an owned pending sync_file for event-loop readiness registration. */
int mdg_device_pending_fence(MdgDevice *device);

/* A Surface has a dedicated serialized graphics owner. Acquisition/presentation
 * may wait for the Android consumer, never on a protocol or UI thread. */
MdgSurface *mdg_surface_create(MdgDevice *device, ANativeWindow *window);
void mdg_surface_destroy(MdgSurface *surface);
MdgImage *mdg_surface_acquire(MdgSurface *surface, unsigned width, unsigned height);
bool mdg_surface_present(MdgSurface *surface);

#ifdef __cplusplus
}
#endif
#endif
