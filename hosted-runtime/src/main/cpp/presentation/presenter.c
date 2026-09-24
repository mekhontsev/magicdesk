#include <jni.h>
#include <android/hardware_buffer_jni.h>
#include <android/native_window_jni.h>
#include <fcntl.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <graphics.h>

#define JNI(method) Java_io_github_mekhontsev_magicdesk_hosted_HostedFramePresenter_##method
enum { CACHE_SIZE = 4 };
struct Presenter {
    ANativeWindow *window;
    MdgDevice *device;
    MdgSurface *surface;
    MdgImage *images[CACHE_SIZE];
    uint64_t buffers[CACHE_SIZE];
    unsigned next;
};

static void reset(struct Presenter *presenter) {
    mdg_surface_destroy(presenter->surface); presenter->surface = NULL;
    for (unsigned i = 0; i < CACHE_SIZE; ++i) {
        mdg_image_unref(presenter->images[i]); presenter->images[i] = NULL; presenter->buffers[i] = 0;
    }
    mdg_device_destroy(presenter->device); presenter->device = NULL;
}
static bool initialize(struct Presenter *presenter, bool software) {
    if (!presenter->device) presenter->device = mdg_device_create(software);
    if (!presenter->device) return false;
    if (!presenter->surface) presenter->surface = mdg_surface_create(presenter->device, presenter->window);
    return presenter->surface != NULL;
}
static MdgImage *import(struct Presenter *presenter, AHardwareBuffer *buffer) {
    uint64_t id;
    if (AHardwareBuffer_getId(buffer, &id)) return NULL;
    for (unsigned i = 0; i < CACHE_SIZE; ++i)
        if (presenter->buffers[i] == id) return presenter->images[i];
    MdgImage *image = mdg_image_hardware(presenter->device, buffer);
    if (!image) return NULL;
    unsigned slot = presenter->next++ % CACHE_SIZE;
    mdg_image_unref(presenter->images[slot]); presenter->images[slot] = image; presenter->buffers[slot] = id;
    return image;
}

JNIEXPORT jlong JNICALL JNI(nativeAcquire)(JNIEnv *env, jclass type, jobject surface) {
    (void)type;
    struct Presenter *presenter = calloc(1, sizeof(*presenter));
    if (!presenter) return 0;
    presenter->window = ANativeWindow_fromSurface(env, surface);
    if (!presenter->window) { free(presenter); return 0; }
    return (jlong)(intptr_t)presenter;
}
JNIEXPORT void JNICALL JNI(nativeRelease)(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type;
    struct Presenter *presenter = (void *)(intptr_t)handle;
    if (!presenter) return;
    reset(presenter); ANativeWindow_release(presenter->window); free(presenter);
}
static bool compose(struct Presenter *presenter, MdgImage *source, unsigned width, unsigned height) {
    MdgImage *target = mdg_surface_acquire(presenter->surface, width, height);
    if (!target) return false;
    MdgPass *pass = mdg_pass_begin(presenter->device, target, (float[]){0, 0, 0, 0}, false);
    if (!pass) return false;
    if (source) {
        MdgDraw draw = {.image = source, .source = {0, 0, width, height},
            .destination = {0, 0, mdg_image_width(target), mdg_image_height(target)},
            .clip = {0, 0, mdg_image_width(target), mdg_image_height(target)}, .opacity = 1, .linear = true};
        if (!mdg_pass_draw(pass, &draw)) { mdg_pass_cancel(pass); return false; }
    }
    int wait_fd;
    MdgSubmitResult result = mdg_pass_submit(pass, &wait_fd);
    if (result == MDG_SUBMIT_DEFERRED) { close(wait_fd); mdg_pass_cancel(pass); }
    return result == MDG_SUBMIT_OK && mdg_surface_present(presenter->surface);
}
JNIEXPORT jboolean JNICALL JNI(nativePresent)(JNIEnv *env, jclass type, jlong handle,
        jobject hardware, jint descriptor, jint fence, jint width, jint height) {
    (void)type;
    struct Presenter *presenter = (void *)(intptr_t)handle;
    if (!presenter || width < 1 || height < 1 || width > 4096 || height > 4096) return false;
    AHardwareBuffer *buffer = hardware ? AHardwareBuffer_fromHardwareBuffer(env, hardware) : NULL;
    void *pixels = NULL;
    size_t size = (size_t)width * height * 4;
    if (!buffer) {
        struct stat status;
        int required = F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK;
        int seals = fcntl(descriptor, F_GET_SEALS);
        if (seals < 0 || (seals & required) != required || fstat(descriptor, &status) < 0 ||
            !S_ISREG(status.st_mode) || status.st_size != (off_t)size) return false;
        pixels = mmap(NULL, size, PROT_READ, MAP_SHARED, descriptor, 0);
        if (pixels == MAP_FAILED) return false;
    }
    bool ok = false;
    for (unsigned attempt = 0; attempt < 2; ++attempt) {
        if (initialize(presenter, attempt != 0)) {
            MdgImage *image = buffer ? import(presenter, buffer)
                : mdg_image_cpu(presenter->device, width, height, (size_t)width * 4, MDG_RGBA, pixels);
            if (image) {
                int acquire = fence < 0 ? -1 : fcntl(fence, F_DUPFD_CLOEXEC, 0);
                mdg_image_set_fence(image, acquire);
                if (fence < 0 || acquire >= 0) ok = compose(presenter, image, width, height);
                if (!buffer) mdg_image_unref(image);
            }
        }
        if (ok) break;
        bool accelerated = mdg_device_gpu(presenter->device);
        reset(presenter);
        if (!accelerated) break;
    }
    if (pixels) munmap(pixels, size);
    return ok;
}
JNIEXPORT void JNICALL JNI(nativeClear)(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type;
    struct Presenter *presenter = (void *)(intptr_t)handle;
    if (presenter && initialize(presenter, false)) {
        int width = ANativeWindow_getWidth(presenter->window), height = ANativeWindow_getHeight(presenter->window);
        if (!compose(presenter, NULL, width, height)) reset(presenter);
    }
}
