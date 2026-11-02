#include <assert.h>
#include <errno.h>
#include <poll.h>
#include <stdbool.h>
#include <stdint.h>
#include <sys/ioctl.h>
#include <xf86drm.h>
#include "src/util/dmabuf_completion.h"

static int denial, validation = EINVAL, poll_error, interrupts;
static unsigned exports, imports, validations, waits;
static short expected_events, reported_events;

int drmIoctl(int fd, unsigned long command, void *argument) {
    assert(fd == 42);
    if (command == DMA_BUF_IOCTL_SYNC) {
        assert(((struct dma_buf_sync *)argument)->flags == UINT64_MAX);
        ++validations; errno = validation; return -1;
    }
    if (command == DMA_BUF_IOCTL_EXPORT_SYNC_FILE) {
        ++exports;
        if (!denial) ((struct dma_buf_export_sync_file *)argument)->fd = 43;
    } else {
        assert(command == DMA_BUF_IOCTL_IMPORT_SYNC_FILE);
        assert(((struct dma_buf_import_sync_file *)argument)->fd == 43);
        ++imports;
    }
    errno = denial;
    return denial ? -1 : 0;
}

int poll(struct pollfd *fds, nfds_t count, int timeout) {
    assert(count == 1 && timeout == -1);
    assert(fds->fd == (imports ? 43 : 42) && fds->events == expected_events);
    ++waits;
    if (interrupts) { --interrupts; errno = EINTR; return -1; }
    if (poll_error) { errno = poll_error; return -1; }
    fds->revents = reported_events;
    return 1;
}

static void reset(int error, short events) {
    exports = imports = validations = waits = 0;
    denial = error; validation = EINVAL; poll_error = interrupts = 0;
    expected_events = reported_events = events;
}

int main(void) {
    struct dma_buf_export_sync_file acquire = {.flags = DMA_BUF_SYNC_READ, .fd = -1};
    struct dma_buf_import_sync_file release = {.flags = DMA_BUF_SYNC_WRITE, .fd = 43};
    reset(0, 0);
    assert(!mesa_dmabuf_export_sync_file(42, &acquire) && acquire.fd == 43);
    assert(!mesa_dmabuf_import_sync_file(42, &release));
    assert(exports == 1 && imports == 1 && !validations && !waits);
    int errors[] = {ENOTTY, ENOSYS};
    for (unsigned i = 0; i < 2; ++i) {
        reset(errors[i], POLLIN); acquire.flags = DMA_BUF_SYNC_READ;
        interrupts = 1;
        assert(!mesa_dmabuf_export_sync_file(42, &acquire) && acquire.fd == -1);
        assert(exports == 1 && validations == 1 && waits == 2);
        reset(errors[i], POLLOUT); acquire.flags = DMA_BUF_SYNC_RW;
        assert(!mesa_dmabuf_export_sync_file(42, &acquire) && waits == 1);
        reset(errors[i], POLLIN);
        assert(!mesa_dmabuf_import_sync_file(42, &release) && imports == 1 && waits == 1);
    }
    reset(EPERM, 0);
    assert(mesa_dmabuf_export_sync_file(42, &acquire) == -1 && errno == EPERM);
    assert(mesa_dmabuf_import_sync_file(42, &release) == -1 && errno == EPERM);
    assert(!validations && !waits);
    reset(ENOTTY, POLLOUT); validation = ENOTTY;
    assert(mesa_dmabuf_export_sync_file(42, &acquire) == -1 && !waits);
    assert(mesa_dmabuf_import_sync_file(42, &release) == -1 && !waits);
    reset(ENOTTY, POLLOUT); reported_events = POLLOUT | POLLERR;
    acquire.fd = 44;
    assert(mesa_dmabuf_export_sync_file(42, &acquire) == -1 && errno == EIO && acquire.fd == 44);
    reset(ENOSYS, POLLIN); poll_error = EBADF;
    assert(mesa_dmabuf_import_sync_file(42, &release) == -1 && errno == EBADF);
    reset(ENOTTY, 0); release.fd = -1;
    assert(!mesa_dmabuf_import_sync_file(42, &release) && !imports && !waits);
    return 0;
}
