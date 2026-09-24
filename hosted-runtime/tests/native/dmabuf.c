#define _GNU_SOURCE
#include "dmabuf_producer.h"
#include <assert.h>
#include <dirent.h>
#include <fcntl.h>
#include <linux/dma-buf.h>
#include <poll.h>
#include <stdio.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

static unsigned fd_count(void) {
    DIR *dir = opendir("/proc/self/fd"); assert(dir);
    unsigned count = 0;
    while (readdir(dir)) ++count;
    closedir(dir); return count;
}

static void invalid_buffers(MdgDevice *device, int fd) {
    MdgLinearDmaBuf valid = {fd, WIDTH, HEIGHT, OFFSET, STRIDE, MDG_RGBA};
    MdgLinearDmaBuf b = valid; b.width = 8193;
    assert(!mdg_image_linear_dmabuf(device, &b));
    b = valid; b.height = 0; assert(!mdg_image_linear_dmabuf(device, &b));
    b = valid; b.offset = UINT32_MAX - 3; assert(!mdg_image_linear_dmabuf(device, &b));
    b = valid; b.offset++; assert(!mdg_image_linear_dmabuf(device, &b));
    b = valid; b.stride = WIDTH * 4 - 1; assert(!mdg_image_linear_dmabuf(device, &b));
    b = valid; b.format = (MdgFormat)-1; assert(!mdg_image_linear_dmabuf(device, &b));
    int regular = memfd_create("not-a-dma-buf", MFD_CLOEXEC); assert(regular >= 0);
    assert(ftruncate(regular, ALLOCATION) == 0);
    b = valid; b.fd = regular;
    unsigned before = fd_count();
    for (unsigned i = 0; i < 128; ++i) assert(!mdg_image_linear_dmabuf(device, &b));
    assert(fd_count() == before && fcntl(regular, F_GETFD) >= 0);
    close(regular);
    b = valid; b.fd = -1; assert(!mdg_image_linear_dmabuf(device, &b));
    MdgDevice *software = mdg_device_create(true); assert(software);
    assert(!mdg_device_linear_dmabuf(software));
    assert(!mdg_image_linear_dmabuf(software, &valid));
    mdg_device_destroy(software);
}

int main(int argc, char **argv) {
    bool required = argc > 1 && !strcmp(argv[1], "--required");
    struct Producer producer = {.fd = -1};
    if (!producer_create(&producer)) {
        producer_destroy(&producer);
        puts("DMA-BUF export/import unavailable");
        return required ? 1 : 77;
    }
    MdgDevice *device = mdg_device_create(false);
    assert(device && mdg_device_linear_dmabuf(device));
    printf("DMA-BUF consumer=%s, producer=%s\n", mdg_device_name(device), mdg_device_name(producer.owner)); fflush(stdout);
    invalid_buffers(device, producer.fd);
    MdgImage *target = mdg_image_create(device, WIDTH, HEIGHT); assert(target);
    for (unsigned format = MDG_RGBA; format <= MDG_BGRX; ++format) {
        int fd = dup(producer.fd); assert(fd >= 0);
        MdgLinearDmaBuf buffer = {fd, WIDTH, HEIGHT, OFFSET, STRIDE, format};
        MdgImage *source = mdg_image_linear_dmabuf(device, &buffer); assert(source);
        assert(fcntl(fd, F_GETFD) >= 0); close(fd);
        uint8_t pixels[WIDTH * HEIGHT * 4];
        assert(!mdg_image_read(source, pixels, WIDTH * 4));
        for (unsigned frame = 0; frame < 32; ++frame) {
            uint32_t colors[2] = {0xff3311ccu ^ (frame << 8), 0x7fee8822u ^ (frame << 16)};
            producer.gated = format == MDG_RGBA && frame == 0;
            producer_write(&producer, colors[0], colors[1]);
            int acquire = -1;
            assert(mdg_image_fence(source, &acquire));
            if (acquire >= 0) close(acquire);
            MdgPass *pass = mdg_pass_begin(device, target, (float[]){0,0,0,0}, false); assert(pass);
            assert(mdg_pass_draw(pass, &(MdgDraw){.image = source, .source = {0,0,WIDTH,HEIGHT},
                .destination = {0,0,WIDTH,HEIGHT}, .clip = {0,0,WIDTH,HEIGHT}, .opacity = 1}));
            if (frame == 31) mdg_image_unref(source); /* Queued read owns the import. */
            assert(mdg_pass_submit(pass));
            if (producer.gated) {
                /* Submission must return while the producer is still gated. Its
                 * write fence and our consumer read fence must both be pending. */
                struct dma_buf_export_sync_file reads = {.flags = DMA_BUF_SYNC_WRITE, .fd = -1};
                assert(ioctl(producer.fd, DMA_BUF_IOCTL_EXPORT_SYNC_FILE, &reads) == 0 && reads.fd >= 0);
                struct pollfd event = {.fd = reads.fd, .events = POLLIN};
                assert(poll(&event, 1, 0) == 0);
                close(reads.fd);
                int completion = -1;
                assert(mdg_image_fence(target, &completion) && completion >= 0);
                event.fd = completion;
                assert(poll(&event, 1, 0) == 0); close(completion);
                assert(producer.set_event(producer.gpu->device, producer.gate) == VK_SUCCESS);
                producer.gated = false;
            }
            /* Queue the next producer write before waiting for the consumer: it
             * must honor the read fence published by submit, not overwrite pixels. */
            producer_write(&producer, 0xff000000, 0xff000000);
            assert(mdg_image_read(target, pixels, WIDTH * 4));
            for (unsigned y = 0; y < HEIGHT; ++y) for (unsigned x = 0; x < WIDTH; ++x) {
                uint32_t value = colors[y < HEIGHT / 2 ? 0 : 1];
                uint8_t expected[4] = {value, value >> 8, value >> 16, value >> 24};
                if (format == MDG_BGRA || format == MDG_BGRX) { uint8_t t = expected[0]; expected[0] = expected[2]; expected[2] = t; }
                if (format == MDG_RGBX || format == MDG_BGRX) expected[3] = 255;
                assert(!memcmp(pixels + (y * WIDTH + x) * 4, expected, 4));
            }
            mdg_device_collect(device);
        }
    }
    MdgStats stats = mdg_device_stats(device);
    assert(stats.gpu_frames == 128 && stats.software_frames == 0 && stats.failed_frames == 0);
    mdg_image_unref(target); mdg_device_destroy(device); producer_destroy(&producer);
    puts("128 DMA-BUF frames: GPU writes/copies, implicit acquire/release fences, formats, offset/stride and lifetime passed");
    return 0;
}
