#include "graphics_internal.h"
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <poll.h>
#include <unistd.h>
#ifdef MDG_ANDROID
#include <android/hardware_buffer.h>
#include <android/log.h>
#endif

MdgDevice *mdg_device_create(bool software_only) {
    MdgDevice *device = calloc(1, sizeof(*device));
    if (!device) return NULL;
    strcpy(device->name, "software");
    if (!software_only) mdg_vk_create(device);
    for (unsigned i = 0; i < MDG_SLOTS; ++i) {
        device->passes[i].device = device;
        device->passes[i].completion = -1;
    }
    return device;
}

bool mdg_device_gpu(const MdgDevice *device) { return device && mdg_vk_ready(device); }
const char *mdg_device_name(const MdgDevice *device) { return device ? device->name : "unavailable"; }
MdgStats mdg_device_stats(const MdgDevice *device) { return device ? device->stats : (MdgStats){0}; }

void mdg_pass_release(MdgPass *pass) {
    if (pass->completion >= 0) close(pass->completion);
    pass->completion = -1;
    for (unsigned i = 0; i < pass->count; ++i) mdg_image_unref(pass->commands[i].draw.image);
    mdg_image_unref(pass->target);
    pass->target = NULL;
    pass->count = 0;
    pass->recording = pass->submitted = false;
}

void mdg_device_collect(MdgDevice *device) {
    if (!device) return;
    for (unsigned i = 0; i < MDG_SLOTS; ++i) {
        MdgPass *pass = &device->passes[i];
        if (pass->submitted && mdg_vk_complete(pass)) mdg_pass_release(pass);
    }
}

void mdg_device_destroy(MdgDevice *device) {
    if (!device) return;
#ifdef MDG_ANDROID
    if (device->stats.gpu_frames || device->stats.software_frames || device->stats.failed_frames)
        __android_log_print(ANDROID_LOG_INFO, "MagicDeskGraphics", "%s: gpu=%llu software=%llu failed=%llu",
            device->name, (unsigned long long)device->stats.gpu_frames,
            (unsigned long long)device->stats.software_frames, (unsigned long long)device->stats.failed_frames);
#endif
    for (unsigned i = 0; i < MDG_SLOTS; ++i) {
        MdgPass *pass = &device->passes[i];
        mdg_vk_pass_destroy(pass);
        mdg_pass_release(pass);
    }
    mdg_vk_destroy(device);
    free(device);
}

static MdgImage *image_new(MdgDevice *device, unsigned width, unsigned height) {
    if (!device || !width || !height || width > MDG_MAX_DIMENSION || height > MDG_MAX_DIMENSION) return NULL;
    MdgImage *image = calloc(1, sizeof(*image));
    if (!image) return NULL;
    image->device = device;
    image->references = 1;
    image->width = width;
    image->height = height;
    image->stride = (size_t)width * 4;
    image->fence = -1;
    return image;
}

MdgImage *mdg_image_create(MdgDevice *device, unsigned width, unsigned height) {
    MdgImage *image = image_new(device, width, height);
    if (!image) return NULL;
#ifdef MDG_ANDROID
    AHardwareBuffer_Desc desc = {.width = width, .height = height, .layers = 1,
        .format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
        .usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
            AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN};
    if (AHardwareBuffer_allocate(&desc, &image->hardware) != 0) image->hardware = NULL;
    if (image->hardware && device->gpu) mdg_vk_image(image);
#endif
    if (!image->hardware) {
        image->owned_pixels = calloc(height, image->stride);
        image->pixels = image->owned_pixels;
        if (!image->pixels) { mdg_image_unref(image); return NULL; }
    }
    return image;
}

MdgImage *mdg_image_cpu(MdgDevice *device, unsigned width, unsigned height,
        size_t stride, MdgFormat format, const void *pixels) {
    if (!pixels || format > MDG_BGRX || stride < (size_t)width * 4 ||
        stride > SIZE_MAX / (height ? height : 1)) return NULL;
    MdgImage *image = image_new(device, width, height);
    if (!image) return NULL;
    image->stride = stride;
    image->format = format;
    image->pixels = pixels;
    if (device->gpu) mdg_vk_image(image);
    return image;
}

MdgImage *mdg_image_hardware(MdgDevice *device, AHardwareBuffer *buffer) {
#ifdef MDG_ANDROID
    if (!buffer) return NULL;
    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(buffer, &desc);
    if (desc.layers != 1 || (desc.format != 1 && desc.format != 2 && desc.format != 5)) return NULL;
    MdgImage *image = image_new(device, desc.width, desc.height);
    if (!image) return NULL;
    image->format = desc.format == 5 ? MDG_BGRA : desc.format == 2 ? MDG_RGBX : MDG_RGBA;
    image->stride = (size_t)desc.stride * 4;
    image->hardware = buffer;
    image->external = true;
    AHardwareBuffer_acquire(buffer);
    if (device->gpu) mdg_vk_image(image);
    return image;
#else
    (void)device; (void)buffer;
    return NULL;
#endif
}

void mdg_image_ref(MdgImage *image) { if (image) ++image->references; }
void mdg_image_unref(MdgImage *image) {
    if (!image || --image->references) return;
    mdg_vk_image_destroy(image);
#ifdef MDG_ANDROID
    if (image->hardware) AHardwareBuffer_release(image->hardware);
#endif
    if (image->fence >= 0) close(image->fence);
    free(image->owned_pixels);
    free(image);
}
unsigned mdg_image_width(const MdgImage *image) { return image ? image->width : 0; }
unsigned mdg_image_height(const MdgImage *image) { return image ? image->height : 0; }
AHardwareBuffer *mdg_image_buffer(MdgImage *image) { return image ? image->hardware : NULL; }
bool mdg_image_fence(MdgImage *image, int *owned_fd) {
    if (!image || !owned_fd) return false;
    *owned_fd = image->fence >= 0 ? fcntl(image->fence, F_DUPFD_CLOEXEC, 0) : -1;
    return image->fence < 0 || *owned_fd >= 0;
}
void mdg_image_set_fence(MdgImage *image, int owned_fd) {
    if (!image) { if (owned_fd >= 0) close(owned_fd); return; }
    if (image->fence >= 0) close(image->fence);
    image->fence = owned_fd;
}

bool mdg_wait_fence(int fd) {
    if (fd < 0) return true;
    struct pollfd event = {.fd = fd, .events = POLLIN};
    int result;
    // EVENT_WAIT: sync_file completion; a GPU timeout rejects access, never permits buffer reuse.
    do { result = poll(&event, 1, 5000); } while (result < 0 && errno == EINTR);
    return result == 1 && (event.revents & POLLIN) && !(event.revents & (POLLERR | POLLNVAL));
}

bool mdg_map(MdgImage *image, bool write, void **pixels, size_t *stride) {
    if (!image) return false;
    if (image->hardware) {
#ifdef MDG_ANDROID
        if (!mdg_wait_fence(image->fence)) return false;
        uint64_t usage = write ? AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN
            : AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN;
        if (AHardwareBuffer_lock(image->hardware, usage, -1, NULL, pixels) != 0) return false;
        AHardwareBuffer_Desc desc;
        AHardwareBuffer_describe(image->hardware, &desc);
        *stride = (size_t)desc.stride * 4;
        return true;
#else
        return false;
#endif
    }
    if (write && !image->owned_pixels) return false;
    *pixels = (void *)image->pixels;
    *stride = image->stride;
    return *pixels != NULL;
}

void mdg_unmap(MdgImage *image) {
#ifdef MDG_ANDROID
    if (image->hardware) {
        int fence = -1;
        if (AHardwareBuffer_unlock(image->hardware, &fence) == 0) mdg_image_set_fence(image, fence);
    }
#else
    (void)image;
#endif
}

bool mdg_image_read(MdgImage *image, void *rgba, size_t stride) {
    if (!image || !rgba || stride < (size_t)image->width * 4) return false;
    void *pixels; size_t source_stride;
    if (!mdg_map(image, false, &pixels, &source_stride)) return false;
    for (unsigned y = 0; y < image->height; ++y) {
        const uint8_t *source = (uint8_t *)pixels + y * source_stride;
        uint8_t *target = (uint8_t *)rgba + y * stride;
        for (unsigned x = 0; x < image->width; ++x, source += 4, target += 4) {
            bool bgra = image->format == MDG_BGRA || image->format == MDG_BGRX;
            target[0] = source[bgra ? 2 : 0]; target[1] = source[1]; target[2] = source[bgra ? 0 : 2];
            target[3] = image->format == MDG_RGBX || image->format == MDG_BGRX ? 255 : source[3];
        }
    }
    mdg_unmap(image);
    return true;
}

int mdg_device_pending_fence(MdgDevice *device) {
    if (!device) return -1;
    for (unsigned i = 0; i < MDG_SLOTS; ++i)
        if (device->passes[i].submitted && device->passes[i].completion >= 0)
            return fcntl(device->passes[i].completion, F_DUPFD_CLOEXEC, 0);
    return -1;
}

bool mdg_device_available(MdgDevice *device) {
    if (!device) return false;
    mdg_device_collect(device);
    for (unsigned i = 0; i < MDG_SLOTS; ++i)
        if (!device->passes[i].submitted && !device->passes[i].recording) return true;
    return false;
}

MdgPass *mdg_pass_begin(MdgDevice *device, MdgImage *target, const float clear[4], bool preserve) {
    if (!device || !target || target->device != device || !clear) return NULL;
    for (unsigned i = 0; i < 4; ++i) if (!isfinite(clear[i])) return NULL;
    mdg_device_collect(device);
    for (unsigned i = 0; i < MDG_SLOTS; ++i) {
        MdgPass *pass = &device->passes[i];
        if (pass->recording || pass->submitted) continue;
        pass->target = target;
        mdg_image_ref(target);
        memcpy(pass->clear, clear, sizeof(pass->clear));
        pass->preserve = preserve;
        pass->recording = true;
        return pass;
    }
    return NULL;
}

static bool valid_box(MdgBox b) {
    return isfinite(b.x) && isfinite(b.y) && isfinite(b.width) && isfinite(b.height) &&
        b.width > 0 && b.height > 0 && fabsf(b.x) + b.width <= 1073741824.f &&
        fabsf(b.y) + b.height <= 1073741824.f;
}

bool mdg_pass_draw(MdgPass *pass, const MdgDraw *draw) {
    if (!pass || !pass->recording || !draw || !draw->image || draw->image == pass->target ||
        draw->image->device != pass->device || pass->count == MDG_MAX_DRAWS ||
        !valid_box(draw->source) || !valid_box(draw->destination) || draw->transform > 7 ||
        !isfinite(draw->opacity) || draw->opacity < 0 || draw->opacity > 1 ||
        draw->clip.width < 0 || draw->clip.height < 0) return false;
    MdgCommand *command = &pass->commands[pass->count++];
    *command = (MdgCommand){.draw = *draw};
    command->color[0] = command->color[1] = command->color[2] = 1;
    command->color[3] = draw->opacity;
    mdg_image_ref(draw->image);
    return true;
}

bool mdg_pass_rect(MdgPass *pass, MdgBox box, MdgClip clip, const float color[4], bool blend) {
    if (!pass || !pass->recording || !color || !valid_box(box) || pass->count == MDG_MAX_DRAWS ||
        clip.width < 0 || clip.height < 0) return false;
    for (unsigned i = 0; i < 4; ++i) if (!isfinite(color[i]) || color[i] < 0 || color[i] > 1) return false;
    MdgCommand *command = &pass->commands[pass->count++];
    *command = (MdgCommand){.solid = true, .draw = {.destination = box, .clip = clip, .opacity = 1, .blend = blend}};
    memcpy(command->color, color, sizeof(command->color));
    return true;
}

bool mdg_pass_submit(MdgPass *pass) {
    if (!pass || !pass->recording) return false;
    if (pass->device->gpu && mdg_vk_submit(pass)) {
        ++pass->device->stats.gpu_frames; pass->recording = false; return true;
    }
    if (pass->submitted || (pass->device->gpu && !mdg_vk_ready(pass->device))) {
        ++pass->device->stats.failed_frames;
        if (pass->submitted) pass->recording = false;
        else mdg_pass_release(pass);
        return false;
    }
    bool result = mdg_software_submit(pass);
    if (result) ++pass->device->stats.software_frames;
    else ++pass->device->stats.failed_frames;
    mdg_pass_release(pass);
    return result;
}

void mdg_pass_cancel(MdgPass *pass) { if (pass && pass->recording) mdg_pass_release(pass); }

bool mdg_pass_submit_and_wait(MdgPass *pass) {
    if (!pass || !pass->recording) return false;
    MdgImage *target = pass->target;
    MdgDevice *device = pass->device;
    mdg_image_ref(target);
    bool ok = mdg_pass_submit(pass) && mdg_wait_fence(target->fence);
    mdg_device_collect(device);
    mdg_image_unref(target);
    return ok;
}
