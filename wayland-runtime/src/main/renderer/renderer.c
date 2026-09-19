#include <jni.h>
#include <android/native_window_jni.h>
#include <fcntl.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>

#define JNI(method) Java_io_github_mekhontsev_magicdesk_wayland_WaylandSession_##method

JNIEXPORT jlong JNICALL JNI(nativeAcquire)(JNIEnv *env, jclass type, jobject surface) {
    (void)type;
    return (jlong)(intptr_t)ANativeWindow_fromSurface(env, surface);
}

JNIEXPORT void JNICALL JNI(nativeRelease)(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type;
    if (handle) ANativeWindow_release((void *)(intptr_t)handle);
}

JNIEXPORT jboolean JNICALL JNI(nativePresent)(JNIEnv *env, jclass type, jlong handle,
        jint descriptor, jint width, jint height) {
    (void)env; (void)type;
    if (width < 1 || height < 1 || width > 4096 || height > 4096) return false;
    size_t size = (size_t)width * height * 4;
    struct stat status;
    int required = F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK;
    int seals = fcntl(descriptor, F_GET_SEALS);
    if (seals < 0 || (seals & required) != required || fstat(descriptor, &status) < 0 ||
            !S_ISREG(status.st_mode) || status.st_size != (off_t)size) return false;
    if (!handle) return true;
    const uint8_t *pixels = mmap(NULL, size, PROT_READ, MAP_SHARED, descriptor, 0);
    if (pixels == MAP_FAILED) return false;
    ANativeWindow *window = (void *)(intptr_t)handle;
    ANativeWindow_Buffer buffer;
    bool presented = false;
    if (ANativeWindow_setBuffersGeometry(window, width, height, WINDOW_FORMAT_RGBA_8888) == 0 &&
            ANativeWindow_lock(window, &buffer, NULL) == 0) {
        if (buffer.width == width && buffer.height == height && buffer.stride >= width &&
                buffer.format == WINDOW_FORMAT_RGBA_8888) {
            for (int row = 0; row < height; ++row)
                memcpy((uint8_t *)buffer.bits + (size_t)row * buffer.stride * 4,
                    pixels + (size_t)row * width * 4, (size_t)width * 4);
            presented = true;
        }
        if (ANativeWindow_unlockAndPost(window) < 0) presented = false;
    }
    munmap((void *)pixels, size);
    return presented;
}

JNIEXPORT void JNICALL JNI(nativeClear)(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type;
    if (!handle) return;
    ANativeWindow *window = (void *)(intptr_t)handle;
    ANativeWindow_Buffer buffer;
    if (ANativeWindow_setBuffersGeometry(window, 0, 0, WINDOW_FORMAT_RGBA_8888) == 0 &&
            ANativeWindow_lock(window, &buffer, NULL) == 0) {
        if (buffer.format == WINDOW_FORMAT_RGBA_8888)
            memset(buffer.bits, 0, (size_t)buffer.stride * buffer.height * 4);
        ANativeWindow_unlockAndPost(window);
    }
}