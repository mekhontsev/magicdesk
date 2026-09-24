#include "graphics.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <poll.h>
#include <fcntl.h>
#include <unistd.h>
#if defined(__ANDROID__) && !defined(MDG_PORTABLE_TEST)
#include <android/hardware_buffer.h>
#endif

static void expect(const uint8_t *pixels, unsigned x, unsigned y, unsigned stride,
        unsigned red, unsigned green, unsigned blue, unsigned alpha) {
    const uint8_t *p = pixels + y * stride + x * 4;
    unsigned expected[] = {red, green, blue, alpha};
    for (unsigned i = 0; i < 4; ++i) {
        if (abs((int)p[i] - (int)expected[i]) > 1) {
            fprintf(stderr, "pixel %u,%u: %u,%u,%u,%u expected %u,%u,%u,%u\n",
                x, y, p[0], p[1], p[2], p[3], red, green, blue, alpha);
            assert(false);
        }
    }
}

int main(int argc, char **argv) {
    bool software = argc > 1 && !strcmp(argv[1], "--software");
    bool require_gpu = argc > 1 && !strcmp(argv[1], "--gpu");
    MdgDevice *device = mdg_device_create(software);
    assert(device);
    printf("renderer=%s\n", mdg_device_name(device)); fflush(stdout);
    if (require_gpu) assert(mdg_device_gpu(device));
    MdgImage *target = mdg_image_create(device, 8, 8);
    assert(target);
    static const uint8_t source[] = {
        255,0,0,255, 0,255,0,255,
        0,0,255,255, 255,255,255,255};
    MdgImage *texture = mdg_image_cpu(device, 2, 2, 8, MDG_RGBA, source);
    assert(texture);
    for (unsigned iteration = 0; iteration < 30; ++iteration) {
        float clear[] = {0, 0, 0, 1};
        MdgPass *pass = mdg_pass_begin(device, target, clear, false);
        assert(pass);
        assert(mdg_pass_draw(pass, &(MdgDraw){.image = texture, .source = {0,0,2,2}, .destination = {0,0,8,8},
            .clip = {0,0,8,8}, .opacity = 1}));
        assert(mdg_pass_rect(pass, (MdgBox){4,4,4,4}, (MdgClip){5,5,2,2}, (float[]){0,0,0,.5f}, true));
        assert(mdg_pass_submit(pass));
        int pending = mdg_device_pending_fence(device);
        int fence = -1;
        assert(mdg_image_fence(target, &fence));
        if (fence >= 0) {
            struct pollfd event = {.fd = fence, .events = POLLIN};
            // EVENT_WAIT: fixture asserts native GPU completion before CPU inspection.
            assert(poll(&event, 1, 5000) == 1); close(fence);
        }
        uint8_t pixels[8 * 8 * 4];
        assert(mdg_image_read(target, pixels, 32));
        expect(pixels, 0, 0, 32, 255,0,0,255);
        expect(pixels, 7, 0, 32, 0,255,0,255);
        expect(pixels, 0, 7, 32, 0,0,255,255);
        expect(pixels, 7, 7, 32, 255,255,255,255);
        expect(pixels, 5, 5, 32, 128,128,128,255);
        mdg_device_collect(device);
        if (pending >= 0) {
            assert(fcntl(pending, F_GETFD) >= 0);
            struct pollfd event = {.fd = pending, .events = POLLIN};
            assert(poll(&event, 1, 0) == 1 && !(event.revents & POLLNVAL));
            close(pending);
        }
    }
    for (unsigned transform = 0; transform < 8; ++transform) {
        MdgPass *pass = mdg_pass_begin(device, target, (float[]){0,0,0,0}, false);
        assert(pass);
        assert(mdg_pass_draw(pass, &(MdgDraw){.image = texture, .source = {0,0,2,2}, .destination = {0,0,8,8},
            .clip = {0,0,8,8}, .opacity = 1, .transform = transform}));
        assert(mdg_pass_submit(pass));
        uint8_t pixels[256]; assert(mdg_image_read(target, pixels, 32));
        for (unsigned y = 0; y < 2; ++y) for (unsigned x = 0; x < 2; ++x) {
            unsigned u = (transform & 4) ? 1 - x : x, v = y;
            for (unsigned q = 0; q < (transform & 3); ++q) { unsigned p = u; u = v; v = 1 - p; }
            const uint8_t *p = source + (v * 2 + u) * 4;
            expect(pixels, x * 7, y * 7, 32, p[0], p[1], p[2], p[3]);
        }
        mdg_device_collect(device);
    }
    unsigned expected_frames = 38;
#if defined(__ANDROID__) && !defined(MDG_PORTABLE_TEST)
    for (unsigned format = 1; format <= 5; ++format) {
        if (format != 1 && format != 2 && format != 5) continue;
        AHardwareBuffer_Desc description = {.width = 8, .height = 8, .layers = 1, .format = format,
            .usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN};
        AHardwareBuffer *buffer = NULL;
        assert(AHardwareBuffer_allocate(&description, &buffer) == 0);
        uint8_t *memory = NULL;
        assert(AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, (void **)&memory) == 0);
        AHardwareBuffer_describe(buffer, &description);
        for (unsigned y = 0; y < 8; ++y) for (unsigned x = 0; x < 8; ++x) {
            uint8_t *pixel = memory + (y * description.stride + x) * 4;
            pixel[0] = format == 5 ? 0 : 255; pixel[1] = 0;
            pixel[2] = format == 5 ? 255 : 0; pixel[3] = 255;
        }
        assert(AHardwareBuffer_unlock(buffer, NULL) == 0);
        MdgImage *external = mdg_image_hardware(device, buffer);
        assert(external);
        MdgPass *pass = mdg_pass_begin(device, external, (float[]){0,0,0,0}, true);
        assert(pass);
        assert(mdg_pass_rect(pass, (MdgBox){2,2,2,2}, (MdgClip){0,0,8,8}, (float[]){0,1,0,1}, false));
        assert(mdg_pass_submit_and_wait(pass));
        ++expected_frames;
        uint8_t pixels[256];
        assert(mdg_image_read(external, pixels, 32));
        expect(pixels, 0, 0, 32, 255,0,0,255);
        expect(pixels, 7, 7, 32, 255,0,0,255);
        expect(pixels, 2, 2, 32, 0,255,0,255);
        pass = mdg_pass_begin(device, target, (float[]){0,0,0,0}, false);
        assert(mdg_pass_draw(pass, &(MdgDraw){.image = external, .source = {0,0,8,8},
            .destination = {0,0,8,8}, .clip = {0,0,8,8}, .opacity = 1}));
        assert(mdg_pass_submit_and_wait(pass));
        ++expected_frames;
        assert(mdg_image_read(target, pixels, 32));
        expect(pixels, 0, 0, 32, 255,0,0,255);
        expect(pixels, 2, 2, 32, 0,255,0,255);
        mdg_image_unref(external);
        AHardwareBuffer_release(buffer);
    }
#endif
    MdgPass *cancel = mdg_pass_begin(device, target, (float[]){0,0,0,0}, false);
    assert(cancel);
    assert(!mdg_pass_draw(cancel, &(MdgDraw){.image = target}));
    mdg_pass_cancel(cancel);
    mdg_image_set_fence(texture, 1000000000);
    int invalid_fence = -1;
    assert(!mdg_image_fence(texture, &invalid_fence) && invalid_fence == -1);
    mdg_image_set_fence(texture, -1);
    MdgStats stats = mdg_device_stats(device);
    printf("frames: gpu=%llu software=%llu failed=%llu\n", (unsigned long long)stats.gpu_frames,
        (unsigned long long)stats.software_frames, (unsigned long long)stats.failed_frames);
    if (require_gpu) assert(stats.gpu_frames == expected_frames && stats.software_frames == 0);
    assert(stats.failed_frames == 0);
    mdg_image_unref(texture); mdg_image_unref(target);
    mdg_device_destroy(device);
    puts("graphics passed");
    return 0;
}
