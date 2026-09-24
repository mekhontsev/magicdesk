#include "graphics_internal.h"
#include <errno.h>
#include <poll.h>
#include <unistd.h>

struct MdgReadback {
    MdgDevice *device;
    MdgImage *source, *target;
    bool submitted, ready;
};

MdgReadback *mdg_readback_create(MdgDevice *device) {
    if (!device) return NULL;
    MdgReadback *readback = calloc(1, sizeof(*readback));
    if (readback) readback->device = device;
    return readback;
}

void mdg_readback_cancel(MdgReadback *readback) {
    if (!readback) return;
    mdg_image_unref(readback->source);
    readback->source = NULL;
    readback->submitted = readback->ready = false;
}

void mdg_readback_destroy(MdgReadback *readback) {
    if (!readback) return;
    mdg_readback_cancel(readback);
    mdg_image_unref(readback->target);
    free(readback);
}

bool mdg_readback_start(MdgReadback *readback, MdgImage *source) {
    if (!readback || !source || source->device != readback->device) return false;
    mdg_image_ref(source);
    mdg_readback_cancel(readback);
    readback->source = source;
    if (readback->target && (readback->target->width != source->width || readback->target->height != source->height)) {
        mdg_image_unref(readback->target);
        readback->target = NULL;
    }
    if (!readback->target) readback->target = mdg_image_create(readback->device, source->width, source->height);
    if (readback->target) return true;
    mdg_readback_cancel(readback);
    return false;
}

static MdgReadbackStatus observe(MdgImage *image, int *wait_fd) {
    int fd;
    if (!mdg_image_fence(image, &fd)) return MDG_READBACK_FAILED;
    if (fd < 0) return MDG_READBACK_READY;
    struct pollfd event = {.fd = fd, .events = POLLIN};
    int result;
    do result = poll(&event, 1, 0); while (result < 0 && errno == EINTR);
    if (!result) { *wait_fd = fd; return MDG_READBACK_PENDING; }
    close(fd);
    return result > 0 && event.revents == POLLIN ? MDG_READBACK_READY : MDG_READBACK_FAILED;
}

MdgReadbackStatus mdg_readback_poll(MdgReadback *readback, int *wait_fd) {
    if (!wait_fd) return MDG_READBACK_FAILED;
    *wait_fd = -1;
    if (!readback || !readback->source) return MDG_READBACK_FAILED;
    if (readback->ready) return MDG_READBACK_READY;
    mdg_device_collect(readback->device);
    /* A replaced request may still own this cached target on the GPU. */
    MdgReadbackStatus status = observe(readback->target, wait_fd);
    if (status != MDG_READBACK_READY) return status;
    if (!readback->submitted) {
        /* Do not queue an unfinished producer ahead of unrelated output work. */
        status = observe(readback->source, wait_fd);
        if (status != MDG_READBACK_READY) return status;
        if (!mdg_device_available(readback->device)) {
            *wait_fd = mdg_device_pending_fence(readback->device);
            return *wait_fd >= 0 ? MDG_READBACK_PENDING : MDG_READBACK_FAILED;
        }
        MdgImage *source = readback->source;
        MdgPass *pass = mdg_pass_begin(readback->device, readback->target, (float[]){0,0,0,0}, false);
        if (!pass) return MDG_READBACK_FAILED;
        if (!mdg_pass_draw(pass, &(MdgDraw){.image = source, .source = {0,0,source->width,source->height},
                .destination = {0,0,source->width,source->height}, .clip = {0,0,source->width,source->height}, .opacity = 1})) {
            mdg_pass_cancel(pass);
            return MDG_READBACK_FAILED;
        }
        MdgSubmitResult result = mdg_pass_submit(pass, wait_fd);
        if (result == MDG_SUBMIT_DEFERRED) { mdg_pass_cancel(pass); return MDG_READBACK_PENDING; }
        if (result != MDG_SUBMIT_OK) return MDG_READBACK_FAILED;
        readback->submitted = true;
        status = observe(readback->target, wait_fd);
        if (status != MDG_READBACK_READY) return status;
    }
    readback->ready = true;
    return MDG_READBACK_READY;
}

bool mdg_readback_read(MdgReadback *readback, void *rgba, size_t stride) {
    if (!readback || !readback->ready) return false;
    readback->ready = false;
    return mdg_image_read(readback->target, rgba, stride);
}
