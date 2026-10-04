#define _GNU_SOURCE
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <wayland-client.h>
#include <libdrm/drm_fourcc.h>
#include <linux/dma-heap.h>
#include <linux/dma-buf.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <poll.h>

#define REQUIRE(c)                                                                                 \
    do {                                                                                           \
        if (!(c)) {                                                                                \
            fprintf(stderr, "FAIL %s egl=%x gl=%x\n", #c, eglGetError(), glGetError());            \
            exit(1);                                                                               \
        }                                                                                          \
    } while (0)
static void sync_fd(int fd, uint64_t flags) {
    struct dma_buf_sync sync = {.flags = flags};
    REQUIRE(ioctl(fd, DMA_BUF_IOCTL_SYNC, &sync) == 0);
}
int main(void) {
    struct wl_display *wl = wl_display_connect(NULL);
    REQUIRE(wl);
    EGLDisplay display = eglGetPlatformDisplay(EGL_PLATFORM_WAYLAND_KHR, wl, NULL);
    REQUIRE(display != EGL_NO_DISPLAY);
    EGLint major, minor;
    REQUIRE(eglInitialize(display, &major, &minor));
    const char *extensions = eglQueryString(display, EGL_EXTENSIONS);
    printf("EGL %d.%d %s\nextensions=%s\n", major, minor, eglQueryString(display, EGL_VENDOR),
           extensions);
    REQUIRE(strstr(extensions, "EGL_KHR_no_config_context"));
    REQUIRE(strstr(extensions, "EGL_KHR_surfaceless_context"));
    REQUIRE(strstr(extensions, "EGL_EXT_image_dma_buf_import"));
    REQUIRE(strstr(extensions, "EGL_ANDROID_native_fence_sync"));
    REQUIRE(eglBindAPI(EGL_OPENGL_ES_API));
    EGLint contextAttrs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    EGLContext context = eglCreateContext(display, EGL_NO_CONFIG_KHR, EGL_NO_CONTEXT, contextAttrs);
    REQUIRE(context != EGL_NO_CONTEXT);
    REQUIRE(eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, context));
    printf("renderer=%s\n", glGetString(GL_RENDERER));
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC target =
        (void *)eglGetProcAddress("glEGLImageTargetTexture2DOES");
    PFNEGLCREATEIMAGEKHRPROC create = (void *)eglGetProcAddress("eglCreateImageKHR");
    PFNEGLDESTROYIMAGEKHRPROC destroy = (void *)eglGetProcAddress("eglDestroyImageKHR");
    PFNEGLCREATESYNCKHRPROC create_sync = (void *)eglGetProcAddress("eglCreateSyncKHR");
    PFNEGLDESTROYSYNCKHRPROC destroy_sync = (void *)eglGetProcAddress("eglDestroySyncKHR");
    PFNEGLDUPNATIVEFENCEFDANDROIDPROC dup_fence = (void *)eglGetProcAddress("eglDupNativeFenceFDANDROID");
    REQUIRE(target && create && destroy && create_sync && destroy_sync && dup_fence);
    int heap = open("/dev/dma_heap/system", O_RDONLY | O_CLOEXEC);
    REQUIRE(heap >= 0);
    struct dma_heap_allocation_data allocation = {.len = 4096, .fd_flags = O_RDWR | O_CLOEXEC};
    REQUIRE(ioctl(heap, DMA_HEAP_IOCTL_ALLOC, &allocation) == 0);
    close(heap);
    int fd = allocation.fd;
    uint32_t *pixels = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    REQUIRE(pixels != MAP_FAILED);
    sync_fd(fd, DMA_BUF_SYNC_START | DMA_BUF_SYNC_WRITE);
    for (int i = 0; i < 1024; ++i)
        pixels[i] = 0xff2b7fc1;
    sync_fd(fd, DMA_BUF_SYNC_END | DMA_BUF_SYNC_WRITE);
    EGLint attrs[] = {EGL_WIDTH,
                      64,
                      EGL_HEIGHT,
                      16,
                      EGL_LINUX_DRM_FOURCC_EXT,
                      DRM_FORMAT_ARGB8888,
                      EGL_DMA_BUF_PLANE0_FD_EXT,
                      fd,
                      EGL_DMA_BUF_PLANE0_OFFSET_EXT,
                      0,
                      EGL_DMA_BUF_PLANE0_PITCH_EXT,
                      256,
                      EGL_DMA_BUF_PLANE0_MODIFIER_LO_EXT,
                      0,
                      EGL_DMA_BUF_PLANE0_MODIFIER_HI_EXT,
                      0,
                      EGL_NONE};
    EGLImageKHR image = create(display, EGL_NO_CONTEXT, EGL_LINUX_DMA_BUF_EXT, NULL, attrs);
    REQUIRE(image != EGL_NO_IMAGE_KHR);
    GLuint texture, fbo;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    target(GL_TEXTURE_2D, image);
    REQUIRE(glGetError() == GL_NO_ERROR);
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
    REQUIRE(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE);
    unsigned char sample[4];
    glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, sample);
    REQUIRE(glGetError() == GL_NO_ERROR);
    printf("imported RGBA=%u,%u,%u,%u\n", sample[0], sample[1], sample[2], sample[3]);
    REQUIRE(sample[0] == 0x2b && sample[1] == 0x7f && sample[2] == 0xc1 && sample[3] == 0xff);
    glViewport(0, 0, 64, 16);
    for (int frame = 0; frame < 64; ++frame) {
        struct dma_buf_export_sync_file acquire = {.flags = DMA_BUF_SYNC_RW, .fd = -1};
        REQUIRE(ioctl(fd, DMA_BUF_IOCTL_EXPORT_SYNC_FILE, &acquire) == 0);
        EGLint sync_attrs[] = {EGL_SYNC_NATIVE_FENCE_FD_ANDROID, acquire.fd, EGL_NONE};
        EGLSyncKHR dependency = create_sync(display, EGL_SYNC_NATIVE_FENCE_ANDROID, sync_attrs);
        REQUIRE(dependency != EGL_NO_SYNC_KHR);
        REQUIRE(eglWaitSync(display, dependency, 0));
        REQUIRE(destroy_sync(display, dependency));
        glClearColor(frame % 2 == 0, frame % 2 != 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        EGLSyncKHR completion = create_sync(display, EGL_SYNC_NATIVE_FENCE_ANDROID, NULL);
        REQUIRE(completion != EGL_NO_SYNC_KHR);
        glFlush();
        int completion_fd = dup_fence(display, completion);
        REQUIRE(completion_fd >= 0);
        struct dma_buf_import_sync_file publish = {.flags = DMA_BUF_SYNC_WRITE, .fd = completion_fd};
        REQUIRE(ioctl(fd, DMA_BUF_IOCTL_IMPORT_SYNC_FILE, &publish) == 0);
        close(completion_fd);
        REQUIRE(destroy_sync(display, completion));

        struct dma_buf_export_sync_file read = {.flags = DMA_BUF_SYNC_READ, .fd = -1};
        REQUIRE(ioctl(fd, DMA_BUF_IOCTL_EXPORT_SYNC_FILE, &read) == 0);
        struct pollfd poll_fd = {.fd = read.fd, .events = POLLIN};
        // EVENT_WAIT: published GPU write fence; timeout fails, never permits CPU access.
        REQUIRE(poll(&poll_fd, 1, 5000) == 1 && (poll_fd.revents & POLLIN));
        close(read.fd);
        sync_fd(fd, DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ);
        uint32_t rendered = pixels[0];
        sync_fd(fd, DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ);
        REQUIRE(rendered == (frame % 2 == 0 ? 0xffff0000u : 0xff00ff00u));
    }
    puts("PASS 64 asynchronous GPU frames, reservation fences and exact CPU pixels");
    glDeleteFramebuffers(1, &fbo);
    glDeleteTextures(1, &texture);
    destroy(display, image);
    munmap(pixels, 4096);
    close(fd);
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(display, context);
    eglTerminate(display);
    wl_display_disconnect(wl);
    puts("PASS Wayland EGL no-config, surfaceless, DMA-BUF import and GPU writeback");
}
