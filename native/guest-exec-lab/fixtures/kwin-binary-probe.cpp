// SPDX-License-Identifier: GPL-2.0-or-later
#include <core/gbmgraphicsbufferallocator.h>
#include <core/graphicsbuffer.h>
#include <opengl/eglcontext.h>
#include <opengl/egldisplay.h>
#include <opengl/eglnativefence.h>
#include <opengl/eglswapchain.h>
#include <opengl/glframebuffer.h>

#include <QCoreApplication>
#include <QPointer>
#include <drm_fourcc.h>
#include <dlfcn.h>
#include <sys/mman.h>
#include <sys/ioctl.h>
#include <linux/dma-buf.h>
#include <cmath>
#include <climits>
#include <cstdio>
#include <cstdlib>
#include <cstring>

static void require(bool valid, const char *operation)
{
    if (!valid) {
        fprintf(stderr, "FAIL %s egl=0x%x\n", operation, eglGetError());
        std::exit(1);
    }
}

int main(int argc, char **argv)
{
    setvbuf(stdout, nullptr, _IONBF, 0);
    QCoreApplication app(argc, argv);
    KWin::GbmGraphicsBufferAllocator allocator(nullptr);
    KWin::GraphicsBufferAllocator *base = &allocator;
    void *hook = dlsym(RTLD_DEFAULT, "md_kwin_allocate");
    void *dispatch = (*reinterpret_cast<void ***>(&allocator))[2];
    Dl_info origin{};
    require(dladdr(dispatch, &origin), "allocator dispatch identity");
    printf("allocator=%s symbol=%s objectSize=%zu loadBase=%p\n", origin.dli_fname,
           origin.dli_sname ? origin.dli_sname : "hidden", sizeof(allocator), origin.dli_fbase);
    if (argc == 2 && !std::strcmp(argv[1], "--control")) {
        require(!hook && std::strstr(origin.dli_fname, "libkwin.so"), "stock dispatch");
        puts("PASS stock library control");
        return 0;
    }
    require(hook && dispatch == hook, "rewritten vtable resolves to compiled fragment");
    auto acquire = reinterpret_cast<bool (*)(KWin::EglDisplay *, KWin::GraphicsBuffer *)>(
        dlsym(RTLD_DEFAULT, "md_kwin_acquire"));
    auto release = reinterpret_cast<bool (*)(KWin::GraphicsBuffer *, int)>(
        dlsym(RTLD_DEFAULT, "md_kwin_release"));
    require(acquire && release, "fence entrypoints");
    auto display = KWin::EglDisplay::create(eglGetPlatformDisplayEXT(
        EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, nullptr));
    require(bool(display), "stock EglDisplay");
    auto context = KWin::EglContext::create(display.get(), EGL_NO_CONFIG_KHR, EGL_NO_CONTEXT);
    require(context && context->makeCurrent(), "stock EglContext");
    const char *renderer = reinterpret_cast<const char *>(glGetString(GL_RENDERER));
    printf("renderer=%s\n", renderer ? renderer : "none");
    require(renderer && std::strstr(renderer, "Adreno") && std::strstr(renderer, "TU"), "Turnip hardware renderer");
    for (uint32_t format : {DRM_FORMAT_ARGB8888, DRM_FORMAT_XRGB8888,
                            DRM_FORMAT_ABGR8888, DRM_FORMAT_XBGR8888}) {
        auto chain = KWin::EglSwapchain::create(base, context.get(), QSize(97, 61), format,
                                              {DRM_FORMAT_MOD_LINEAR});
        require(bool(chain), "stock swapchain imports injected subclass");
        for (int round = 0; round != 24; ++round) {
            auto slot = chain->acquire();
            require(slot && acquire(display.get(), slot->buffer()), "acquire DMA-BUF fence");
            KWin::GLFramebuffer::pushFramebuffer(slot->framebuffer());
            glViewport(0, 0, 97, 61);
            const unsigned char expected[] = {static_cast<unsigned char>(round & 1 ? 64 : 192), 128, 32, 255};
            glClearColor(expected[0] / 255.f, expected[1] / 255.f, expected[2] / 255.f, 1.f);
            glClear(GL_COLOR_BUFFER_BIT);
            glFlush();
            KWin::EGLNativeFence fence(display.get());
            require(fence.isValid() && release(slot->buffer(), fence.fileDescriptor().get()), "publish DMA-BUF fence");
            const auto *attributes = slot->buffer()->dmabufAttributes();
            require(attributes && attributes->pitch[0] > 97 * 4, "padded row layout");
            const size_t bytes = attributes->pitch[0] * attributes->height;
            const int fd = attributes->fd[0].get();
            void *mapping = mmap(nullptr, bytes, PROT_READ, MAP_SHARED, fd, 0);
            require(mapping != MAP_FAILED, "DMA-BUF mapping");
            dma_buf_sync sync{.flags = DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ};
            require(ioctl(fd, DMA_BUF_IOCTL_SYNC, &sync) == 0, "CPU read waits for producer");
            const auto *cpu = static_cast<unsigned char *>(mapping) + 17 * attributes->pitch[0] + 13 * 4;
            const bool bgra = format == DRM_FORMAT_ARGB8888 || format == DRM_FORMAT_XRGB8888;
            require(std::abs(int(cpu[bgra ? 2 : 0]) - expected[0]) <= 1
                    && std::abs(int(cpu[1]) - expected[1]) <= 1
                    && std::abs(int(cpu[bgra ? 0 : 2]) - expected[2]) <= 1, "independent mapped DMA-BUF pixels");
            sync.flags = DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ;
            require(ioctl(fd, DMA_BUF_IOCTL_SYNC, &sync) == 0, "CPU read completion");
            require(munmap(mapping, bytes) == 0, "mapping cleanup");
            unsigned char pixel[4]{};
            glReadPixels(13, 17, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            require(glGetError() == GL_NO_ERROR, "GPU readback");
            for (int channel = 0; channel != 4; ++channel) {
                require(std::abs(int(pixel[channel]) - expected[channel]) <= 1, "GPU pixel contents");
            }
            KWin::GLFramebuffer::popFramebuffer();
            chain->release(slot, fence.takeFileDescriptor());
        }
        printf("PASS format=%08x rounds=24 stock swapchain + DMA-BUF/fences/pixels\n", format);
    }
    KWin::GraphicsBufferOptions invalid{.size = QSize(1, 1), .format = DRM_FORMAT_XRGB8888,
                                       .modifiers = {DRM_FORMAT_MOD_LINEAR}, .software = true};
    puts("checking direct allocation and destruction");
    require(!base->allocate(invalid), "unsupported allocation rejection");
    invalid.software = false;
    auto buffer = base->allocate(invalid);
    require(buffer != nullptr, "standalone allocation");
    QPointer<KWin::GraphicsBuffer> lifetime(buffer);
    buffer->ref();
    buffer->drop();
    require(!lifetime.isNull(), "stock ref/drop retains injected subclass");
    buffer->unref();
    require(lifetime.isNull(), "stock unref destroys injected subclass");
    invalid.format = DRM_FORMAT_NV12;
    require(!base->allocate(invalid), "unsupported format rejection");
    invalid.format = DRM_FORMAT_XRGB8888;
    invalid.size = QSize(0, 1);
    require(!base->allocate(invalid), "empty allocation rejection");
    invalid.size = QSize(INT_MAX, INT_MAX);
    require(!base->allocate(invalid), "oversized allocation rejection");
    puts("PASS allocator, stock C++ ownership, GPU import, four formats and synchronization");
}
