#define _GNU_SOURCE
#include "dmabuf_producer.h"
#include <assert.h>
#include <dlfcn.h>
#include <errno.h>
#include <linux/dma-buf.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <poll.h>
#include <stddef.h>
#include <stdio.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

enum { FRAMES = 64, TEST_WIDTH = 512, TEST_HEIGHT = 256 };
enum { SOURCE, READY, FRAME, SUBMITTED, RELEASE, VERIFIED };
struct Packet { int kind, frame, count; };

static void deny_implicit_bridge(void) {
    struct sock_filter code[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_ioctl, 0, 5),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[1])),
        BPF_STMT(BPF_ALU | BPF_AND | BPF_K, 0xffff),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, ('b' << 8) | 2, 1, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, ('b' << 8) | 3, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | ENOTTY),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog filter = {.len = sizeof(code) / sizeof(code[0]), .filter = code};
    assert(prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) == 0);
    assert(prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &filter) == 0);
}

static void bridge_is_denied(int fd) {
    struct dma_buf_export_sync_file export = {.flags = DMA_BUF_SYNC_RW, .fd = -1};
    assert(ioctl(fd, DMA_BUF_IOCTL_EXPORT_SYNC_FILE, &export) == -1 && errno == ENOTTY);
    struct dma_buf_import_sync_file import = {.flags = DMA_BUF_SYNC_WRITE, .fd = -1};
    assert(ioctl(fd, DMA_BUF_IOCTL_IMPORT_SYNC_FILE, &import) == -1 && errno == ENOTTY);
}

static void send_packet(int socket, int kind, int frame, const int *fds, unsigned count) {
    assert(count <= 2);
    struct Packet packet = {kind, frame, count};
    struct iovec iov = {&packet, sizeof(packet)};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(2 * sizeof(int))]; } control = {0};
    struct msghdr message = {.msg_iov = &iov, .msg_iovlen = 1};
    if (count) {
        message.msg_control = control.bytes;
        message.msg_controllen = CMSG_SPACE(count * sizeof(int));
        struct cmsghdr *c = CMSG_FIRSTHDR(&message);
        c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS;
        c->cmsg_len = CMSG_LEN(count * sizeof(int));
        memcpy(CMSG_DATA(c), fds, count * sizeof(int));
    }
    assert(sendmsg(socket, &message, MSG_NOSIGNAL) == sizeof(packet));
}

static unsigned receive_packet(int socket, int kind, int frame, int fds[2]) {
    struct pollfd ready = {.fd = socket, .events = POLLIN};
    // EVENT_WAIT: peer submits or verifies a GPU operation; timeout fails the fixture.
    assert(poll(&ready, 1, 5000) == 1 && (ready.revents & POLLIN));
    struct Packet packet;
    struct iovec iov = {&packet, sizeof(packet)};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(2 * sizeof(int))]; } control = {0};
    struct msghdr message = {.msg_iov = &iov, .msg_iovlen = 1,
        .msg_control = control.bytes, .msg_controllen = sizeof(control.bytes)};
    assert(recvmsg(socket, &message, MSG_CMSG_CLOEXEC) == sizeof(packet));
    assert(!(message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)));
    assert(packet.kind == kind && packet.frame == frame);
    unsigned count = 0;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(&message); c; c = CMSG_NXTHDR(&message, c)) {
        assert(c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS);
        unsigned n = (c->cmsg_len - CMSG_LEN(0)) / sizeof(int);
        assert(count + n <= 2);
        memcpy(fds + count, CMSG_DATA(c), n * sizeof(int)); count += n;
    }
    assert(count == (unsigned)packet.count);
    return count;
}

static uint32_t color(unsigned frame, bool bottom) {
    return (bottom ? 0xff553399u : 0xff996622u) ^ (frame * 0x10101u);
}

static void import_source(Gpu *g, int fd, size_t size, VkBuffer *buffer, VkDeviceMemory *memory) {
    VkExternalMemoryBufferCreateInfo external = {.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO,
        .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
    VkBufferCreateInfo bi = {.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .pNext = &external,
        .size = size, .usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT};
    assert(g->CreateBuffer(g->device, &bi, NULL, buffer) == VK_SUCCESS);
    VkMemoryRequirements requirements;
    g->GetBufferMemoryRequirements(g->device, *buffer, &requirements);
    VkMemoryFdPropertiesKHR properties = {.sType = VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR};
    assert(g->GetMemoryFdPropertiesKHR(g->device, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
        fd, &properties) == VK_SUCCESS);
    uint32_t types = requirements.memoryTypeBits & properties.memoryTypeBits; assert(types);
    off_t allocation = lseek(fd, 0, SEEK_END);
    fprintf(stderr, "import: requested=%zu required=%llu allocation=%lld\n", size,
        (unsigned long long)requirements.size, (long long)allocation);
    assert(allocation >= (off_t)requirements.size);
    VkImportMemoryFdInfoKHR import = {.sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR,
        .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, .fd = dup(fd)};
    assert(import.fd >= 0);
    VkMemoryAllocateInfo mi = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .pNext = &import,
        .allocationSize = allocation, .memoryTypeIndex = __builtin_ctz(types)};
    assert(g->AllocateMemory(g->device, &mi, NULL, memory) == VK_SUCCESS);
    assert(g->BindBufferMemory(g->device, *buffer, *memory, 0) == VK_SUCCESS);
}

static void consumer(int socket) {
    struct Producer p = {.width = TEST_WIDTH, .height = TEST_HEIGHT, .stride = TEST_WIDTH * 4, .fd = -1};
    if (!producer_create(&p)) { producer_destroy(&p); close(socket); _exit(77); }
    bridge_is_denied(p.fd);
    Gpu *g = p.gpu;
    PFN_vkGetInstanceProcAddr get = (PFN_vkGetInstanceProcAddr)dlsym(g->library, "vkGetInstanceProcAddr");
    PFN_vkGetDeviceProcAddr proc = (PFN_vkGetDeviceProcAddr)get(g->instance, "vkGetDeviceProcAddr");
    PFN_vkCmdCopyBuffer copy = (PFN_vkCmdCopyBuffer)proc(g->device, "vkCmdCopyBuffer"); assert(copy);
    int fds[2];
    assert(receive_packet(socket, SOURCE, 0, fds) == 1);
    VkBuffer source = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    size_t size = (size_t)p.height * p.stride;
    import_source(g, fds[0], size, &source, &memory); close(fds[0]);
    void *pixels = mmap(NULL, p.allocation, PROT_READ, MAP_SHARED, p.fd, 0); assert(pixels != MAP_FAILED);
    send_packet(socket, READY, 0, NULL, 0);
    for (unsigned frame = 0; frame < FRAMES; ++frame) {
        unsigned count = receive_packet(socket, FRAME, frame, fds);
        assert(count <= 1);
        VkImportSemaphoreFdInfoKHR wait = {.sType = VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR,
            .semaphore = p.wait, .flags = VK_SEMAPHORE_IMPORT_TEMPORARY_BIT,
            .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT, .fd = count ? fds[0] : -1};
        assert(g->ImportSemaphoreFdKHR(g->device, &wait) == VK_SUCCESS);
        assert(g->ResetFences(g->device, 1, &p.fence) == VK_SUCCESS);
        assert(g->ResetCommandBuffer(p.commands, 0) == VK_SUCCESS);
        VkCommandBufferBeginInfo begin = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
            .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
        assert(g->BeginCommandBuffer(p.commands, &begin) == VK_SUCCESS);
        if (frame) {
            assert(p.reset_event(g->device, p.gate) == VK_SUCCESS);
            p.wait_event(p.commands, 1, &p.gate, VK_PIPELINE_STAGE_HOST_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, 0, NULL, 0, NULL, 0, NULL);
        }
        VkBufferMemoryBarrier barrier = {.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
            .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT, .srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT,
            .dstQueueFamilyIndex = g->family, .buffer = source, .size = size};
        g->CmdPipelineBarrier(p.commands, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, NULL, 1, &barrier, 0, NULL);
        VkBufferCopy region = {.size = size};
        copy(p.commands, source, p.buffer, 1, &region);
        barrier.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT; barrier.dstAccessMask = 0;
        barrier.srcQueueFamilyIndex = g->family; barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        g->CmdPipelineBarrier(p.commands, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, NULL, 1, &barrier, 0, NULL);
        barrier = (VkBufferMemoryBarrier){.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
            .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT, .dstAccessMask = VK_ACCESS_HOST_READ_BIT,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .buffer = p.buffer, .size = p.allocation};
        g->CmdPipelineBarrier(p.commands, VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_HOST_BIT, 0, 0, NULL, 1, &barrier, 0, NULL);
        assert(g->EndCommandBuffer(p.commands) == VK_SUCCESS);
        VkPipelineStageFlags stage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        VkSubmitInfo submit = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .commandBufferCount = 1,
            .pCommandBuffers = &p.commands, .waitSemaphoreCount = 1, .pWaitSemaphores = &p.wait,
            .pWaitDstStageMask = &stage, .signalSemaphoreCount = 1, .pSignalSemaphores = &p.ready};
        assert(g->QueueSubmit(g->queue, 1, &submit, p.fence) == VK_SUCCESS); p.submitted = true;
        VkSemaphoreGetFdInfoKHR fi = {.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR,
            .semaphore = p.ready, .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT};
        int completion = -1;
        assert(g->GetSemaphoreFdKHR(g->device, &fi, &completion) == VK_SUCCESS);
        send_packet(socket, SUBMITTED, frame, &completion, completion >= 0 ? 1 : 0);
        if (completion >= 0) close(completion);
        assert(receive_packet(socket, RELEASE, frame, fds) == 0);
        if (frame) assert(p.set_event(g->device, p.gate) == VK_SUCCESS);
        // EVENT_WAIT: exact consumer GPU fence protects CPU verification, not a delay.
        assert(g->WaitForFences(g->device, 1, &p.fence, true, 5000000000ull) == VK_SUCCESS);
        struct dma_buf_sync sync = {.flags = DMA_BUF_SYNC_READ | DMA_BUF_SYNC_START};
        assert(ioctl(p.fd, DMA_BUF_IOCTL_SYNC, &sync) == 0);
        for (unsigned y = 0; y < p.height; ++y) for (unsigned x = 0; x < p.width; ++x) {
            uint32_t actual = ((uint32_t *)pixels)[y * p.width + x];
            uint32_t expected = color(frame, y >= p.height / 2);
            if (actual != expected) fprintf(stderr, "frame=%u x=%u y=%u actual=%08x expected=%08x\n",
                frame, x, y, actual, expected);
            assert(actual == expected);
        }
        sync.flags = DMA_BUF_SYNC_READ | DMA_BUF_SYNC_END;
        assert(ioctl(p.fd, DMA_BUF_IOCTL_SYNC, &sync) == 0);
        send_packet(socket, VERIFIED, frame, NULL, 0);
    }
    munmap(pixels, p.allocation);
    g->DestroyBuffer(g->device, source, NULL); g->FreeMemory(g->device, memory, NULL);
    producer_destroy(&p); close(socket);
}

int main(int argc, char **argv) {
    bool required = argc > 1 && !strcmp(argv[1], "--required");
    deny_implicit_bridge();
    int sockets[2]; assert(socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, sockets) == 0);
    pid_t child = fork(); assert(child >= 0);
    if (!child) { close(sockets[0]); consumer(sockets[1]); _exit(0); }
    close(sockets[1]);
    /* Leave allocation padding for importers with stricter buffer alignment. */
    struct Producer p = {.width = TEST_WIDTH, .height = TEST_HEIGHT, .stride = TEST_WIDTH * 4,
        .allocation = TEST_WIDTH * TEST_HEIGHT * 4 + ALLOCATION, .fd = -1};
    if (!producer_create(&p)) {
        close(sockets[0]); kill(child, SIGTERM); waitpid(child, NULL, 0);
        producer_destroy(&p); puts("DMA-BUF export/import unavailable");
        return required ? 1 : 77;
    }
    bridge_is_denied(p.fd);
    send_packet(sockets[0], SOURCE, 0, &p.fd, 1);
    int fds[2]; assert(receive_packet(sockets[0], READY, 0, fds) == 0);
    p.gated = true;
    int completion = producer_write_explicit(&p, color(0, false), color(0, true), -1);
    unsigned pending_reuses = 0;
    for (unsigned frame = 0; frame < FRAMES; ++frame) {
        send_packet(sockets[0], FRAME, frame, &completion, completion >= 0 ? 1 : 0);
        unsigned count = receive_packet(sockets[0], SUBMITTED, frame, fds);
        assert(count <= 1);
        int read_fence = count ? fds[0] : -1;
        if (!frame) {
            struct pollfd checks[] = {{completion, POLLIN, 0}, {read_fence, POLLIN, 0}, {p.fd, POLLIN, 0}};
            int result = poll(checks, 3, 0);
            assert(p.set_event(p.gpu->device, p.gate) == VK_SUCCESS); p.gated = false;
            assert(completion >= 0 && read_fence >= 0 && result >= 0);
            assert(checks[0].revents == 0 && checks[1].revents == 0);
            printf("Gated producer/consumer fences pending; DMA-BUF poll reports %s\n",
                checks[2].revents & POLLIN ? "ready (not a synchronization substitute)" : "not ready");
            fflush(stdout);
        }
        if (completion >= 0) close(completion);
        /* Reuse is queued before the reader finishes, using its real release FD.
         * No DMA-BUF reservation-object fence is published by either process. */
        completion = producer_write_explicit(&p, color(frame + 1, false), color(frame + 1, true), read_fence);
        struct pollfd pending = {.fd = completion, .events = POLLIN};
        int result = poll(&pending, 1, 0);
        send_packet(sockets[0], RELEASE, frame, NULL, 0);
        if (frame) { assert(completion >= 0 && result == 0); ++pending_reuses; }
        assert(receive_packet(sockets[0], VERIFIED, frame, fds) == 0);
    }
    if (completion >= 0) close(completion);
    producer_destroy(&p); close(sockets[0]);
    int status; assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    printf("%u explicit GPU frames, %u gated reuses, %u verified pixels; new DMA-BUF ioctls denied\n",
        FRAMES, pending_reuses, FRAMES * TEST_WIDTH * TEST_HEIGHT);
    return 0;
}
