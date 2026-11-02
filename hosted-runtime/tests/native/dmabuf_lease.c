#define _GNU_SOURCE
#include "dmabuf_producer.h"
#include "dmabuf_test_filter.h"
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <sys/mman.h>
#include <unistd.h>

struct Lease { unsigned held, acquired, released, notified; };
static bool pending(void *context) { ++((struct Lease *)context)->notified; return true; }
static void retain(void *context) {
    struct Lease *lease = context;
    ++lease->held; ++lease->acquired;
}
static void release(void *context) {
    struct Lease *lease = context;
    assert(lease->held);
    --lease->held; ++lease->released;
}
static void wait_fd(int fd) {
    if (fd < 0) return;
    struct pollfd ready = {.fd = fd, .events = POLLIN};
    /* EVENT_WAIT: actual GPU completion; expiry fails the fixture. */
    assert(poll(&ready, 1, 5000) == 1 && ready.revents == POLLIN);
    close(fd);
}
static MdgPass *draw(MdgDevice *device, MdgImage *target, MdgImage *source) {
    MdgPass *pass = mdg_pass_begin(device, target, (float[]){0,0,0,0}, false);
    assert(pass);
    assert(mdg_pass_draw(pass, &(MdgDraw){.image = source, .source = {0,0,WIDTH,HEIGHT},
        .destination = {0,0,WIDTH,HEIGHT}, .clip = {0,0,WIDTH,HEIGHT}, .opacity = 1}));
    return pass;
}
int main(int argc, char **argv) {
    unsigned denied = argc > 1 ? (unsigned)atoi(argv[1]) : ENOTTY;
    if (denied) dmabuf_test_deny_bridge(denied);
    struct Producer producer = {0};
    assert(producer_create(&producer));
    MdgDevice *device = mdg_device_create(false);
    assert(device && mdg_device_linear_dmabuf(device));
    struct Lease lease = {0};
    mdg_device_set_completion_notify(device, pending, &lease);
    MdgLinearDmaBuf buffer = {.fd = producer.fd, .width = WIDTH, .height = HEIGHT,
        .stride = STRIDE, .offset = OFFSET, .format = MDG_RGBA};
    MdgImportError error;
    if (denied) assert(!mdg_image_linear_dmabuf(device, &buffer, &error));
    buffer.lease = (MdgBufferLease){&lease, retain, release};
    MdgImage *source = mdg_image_linear_dmabuf(device, &buffer, &error);
    if (!source) fprintf(stderr, "import: %s code=%d\n", error.operation, error.code);
    if (denied == EPERM) {
        assert(!source && error.code == EPERM);
        producer_destroy(&producer); mdg_device_destroy(device);
        puts("permission denial is not capability absence"); return 0;
    }
    assert(source && mdg_image_completion_owned(source) == (denied != 0));
    int regular = memfd_create("not-dma", MFD_CLOEXEC); assert(regular >= 0);
    assert(!ftruncate(regular, ALLOCATION));
    buffer.fd = regular;
    assert(!mdg_image_linear_dmabuf(device, &buffer, &error)); close(regular);
    MdgImage *target = mdg_image_create(device, WIDTH, HEIGHT); assert(target);
    MdgPass *cancelled = draw(device, target, source);
    assert(lease.held == (denied ? 1u : 0u));
    mdg_pass_cancel(cancelled); assert(!lease.held);

    /* A producer completes before handoff, not just before a CPU pixel read. */
    wait_fd(producer_write_explicit(&producer, 0xff441122, 0xff441122, -1));
    for (unsigned frame = 0; frame < 64; ++frame) {
        MdgPass *pass = draw(device, target, source);
        int fd = -1;
        assert(mdg_pass_submit(pass, &fd) == MDG_SUBMIT_OK && fd == -1);
        assert(lease.held == (denied ? 1u : 0u));
        int completion;
        assert(mdg_device_pending_lease_fence(device, &completion));
        if (!denied) assert(completion == -1 && lease.acquired == 0);
        else wait_fd(completion);
        uint8_t pixels[WIDTH * HEIGHT * 4];
        assert(mdg_image_read(target, pixels, WIDTH * 4));
        for (unsigned i = 0; i < WIDTH * HEIGHT; ++i)
            assert(!memcmp(pixels + i * 4, (uint8_t[]){0x22,0x11,0x44,0xff}, 4));
        mdg_device_collect(device); assert(!lease.held);
    }
    /* Gate the target dependency: submitting and cancelling a readback must
     * neither wait on the owner thread nor release the source while GPU-owned. */
    struct Producer gate = {0}; assert(producer_create(&gate)); gate.gated = true;
    int gate_fd = producer_write_explicit(&gate, 0, 0, -1);
    mdg_image_set_fence(target, gate_fd);
    MdgPass *pending = draw(device, target, source);
    int unused;
    assert(mdg_pass_submit(pending, &unused) == MDG_SUBMIT_OK);
    if (denied) assert(lease.held == 1);
    mdg_device_collect(device);
    if (denied) assert(lease.held == 1);
    assert(gate.set_event(gate.gpu->device, gate.gate) == VK_SUCCESS);
    int fence; assert(mdg_image_fence(target, &fence)); wait_fd(fence);
    mdg_device_collect(device); assert(!lease.held);
    producer_destroy(&gate);

    MdgReadback *readback = mdg_readback_create(device); assert(readback);
    assert(mdg_readback_start(readback, source));
    MdgReadbackStatus status = mdg_readback_poll(readback, &fence);
    assert(status != MDG_READBACK_FAILED);
    mdg_readback_cancel(readback);
    if (fence >= 0) wait_fd(fence);
    mdg_device_collect(device);
    mdg_readback_destroy(readback);
    mdg_image_unref(source);
    mdg_image_unref(target);
    mdg_device_destroy(device);
    assert(!lease.held && lease.acquired == lease.released);
    assert(lease.notified == (denied ? 66u : 0u));
    producer_destroy(&producer);
    printf("completion-owned DMA-BUF: 64 frames, denial=%u, leases=%u\n", denied, lease.acquired);
    return 0;
}
