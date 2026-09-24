#define _GNU_SOURCE
#include "vulkan_internal.h"
#include <assert.h>
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <linux/dma-buf.h>
#include <poll.h>
#include <stdio.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

enum { WIDTH = 33, HEIGHT = 19, STRIDE = 160, OFFSET = 12, ALLOCATION = 65536 };
struct Producer {
    MdgDevice *owner;
    Gpu *gpu;
    VkBuffer buffer;
    VkDeviceMemory memory;
    VkCommandBuffer commands;
    VkFence fence;
    VkSemaphore wait, ready;
    VkEvent gate;
    int fd;
    bool submitted, gated;
    PFN_vkCmdFillBuffer fill;
    PFN_vkGetMemoryFdKHR get_fd;
    PFN_vkCreateEvent create_event;
    PFN_vkDestroyEvent destroy_event;
    PFN_vkCmdWaitEvents wait_event;
    PFN_vkSetEvent set_event;
};

static bool producer_create(struct Producer *p) {
    p->owner = mdg_device_create(false);
    assert(p->owner);
    if (!mdg_device_linear_dmabuf(p->owner)) return false;
    p->gpu = p->owner->gpu;
    Gpu *g = p->gpu;
    PFN_vkGetInstanceProcAddr get = (PFN_vkGetInstanceProcAddr)dlsym(g->library, "vkGetInstanceProcAddr");
    PFN_vkGetDeviceProcAddr proc = (PFN_vkGetDeviceProcAddr)get(g->instance, "vkGetDeviceProcAddr");
    p->fill = (PFN_vkCmdFillBuffer)proc(g->device, "vkCmdFillBuffer");
    p->get_fd = (PFN_vkGetMemoryFdKHR)proc(g->device, "vkGetMemoryFdKHR");
    p->create_event = (PFN_vkCreateEvent)proc(g->device, "vkCreateEvent");
    p->destroy_event = (PFN_vkDestroyEvent)proc(g->device, "vkDestroyEvent");
    p->wait_event = (PFN_vkCmdWaitEvents)proc(g->device, "vkCmdWaitEvents");
    p->set_event = (PFN_vkSetEvent)proc(g->device, "vkSetEvent");
    PFN_vkGetPhysicalDeviceExternalBufferProperties properties = (PFN_vkGetPhysicalDeviceExternalBufferProperties)
        get(g->instance, "vkGetPhysicalDeviceExternalBufferProperties");
    VkPhysicalDeviceExternalBufferInfo bi = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_BUFFER_INFO,
        .usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT, .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
    VkExternalBufferProperties bp = {.sType = VK_STRUCTURE_TYPE_EXTERNAL_BUFFER_PROPERTIES};
    properties(g->physical, &bi, &bp);
    if (!(bp.externalMemoryProperties.externalMemoryFeatures & VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT)) return false;
    VkExternalMemoryBufferCreateInfo external = {.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO,
        .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
    VkBufferCreateInfo buffer = {.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .pNext = &external,
        .size = ALLOCATION, .usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT};
    assert(g->CreateBuffer(g->device, &buffer, NULL, &p->buffer) == VK_SUCCESS);
    VkMemoryRequirements requirements;
    g->GetBufferMemoryRequirements(g->device, p->buffer, &requirements);
    assert(requirements.memoryTypeBits);
    VkExportMemoryAllocateInfo export = {.sType = VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO,
        .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
    VkMemoryAllocateInfo memory = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .pNext = &export,
        .allocationSize = requirements.size, .memoryTypeIndex = __builtin_ctz(requirements.memoryTypeBits)};
    assert(g->AllocateMemory(g->device, &memory, NULL, &p->memory) == VK_SUCCESS);
    assert(g->BindBufferMemory(g->device, p->buffer, p->memory, 0) == VK_SUCCESS);
    VkMemoryGetFdInfoKHR fi = {.sType = VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR,
        .memory = p->memory, .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
    assert(p->get_fd(g->device, &fi, &p->fd) == VK_SUCCESS);
    VkCommandBufferAllocateInfo command = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = g->pool, .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount = 1};
    assert(g->AllocateCommandBuffers(g->device, &command, &p->commands) == VK_SUCCESS);
    VkFenceCreateInfo fence = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    assert(g->CreateFence(g->device, &fence, NULL, &p->fence) == VK_SUCCESS);
    VkSemaphoreCreateInfo semaphore = {.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    assert(g->CreateSemaphore(g->device, &semaphore, NULL, &p->wait) == VK_SUCCESS);
    VkExportSemaphoreCreateInfo es = {.sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO,
        .handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT};
    semaphore.pNext = &es;
    assert(g->CreateSemaphore(g->device, &semaphore, NULL, &p->ready) == VK_SUCCESS);
    VkEventCreateInfo gate = {.sType = VK_STRUCTURE_TYPE_EVENT_CREATE_INFO};
    assert(p->create_event(g->device, &gate, NULL, &p->gate) == VK_SUCCESS);
    return true;
}

static void producer_write(struct Producer *p, uint32_t a, uint32_t b) {
    Gpu *g = p->gpu;
    if (p->submitted) {
        // EVENT_WAIT: fixture reuses its producer command buffer only after GPU completion; timeout fails.
        assert(g->WaitForFences(g->device, 1, &p->fence, true, 5000000000ull) == VK_SUCCESS);
    }
    struct dma_buf_export_sync_file previous = {.flags = DMA_BUF_SYNC_WRITE, .fd = -1};
    assert(ioctl(p->fd, DMA_BUF_IOCTL_EXPORT_SYNC_FILE, &previous) == 0);
    VkImportSemaphoreFdInfoKHR wait = {.sType = VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR,
        .semaphore = p->wait, .flags = VK_SEMAPHORE_IMPORT_TEMPORARY_BIT,
        .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT, .fd = previous.fd};
    assert(g->ImportSemaphoreFdKHR(g->device, &wait) == VK_SUCCESS);
    assert(g->ResetFences(g->device, 1, &p->fence) == VK_SUCCESS);
    assert(g->ResetCommandBuffer(p->commands, 0) == VK_SUCCESS);
    VkCommandBufferBeginInfo begin = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
    assert(g->BeginCommandBuffer(p->commands, &begin) == VK_SUCCESS);
    if (p->gated) {
        // EVENT_WAIT: fixture GPU gate is released after consumer submission; CTest bounds a deadlock.
        p->wait_event(p->commands, 1, &p->gate, VK_PIPELINE_STAGE_HOST_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, 0, NULL, 0, NULL, 0, NULL);
    }
    VkBufferMemoryBarrier barrier = {.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
        .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT, .srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT,
        .dstQueueFamilyIndex = g->family, .buffer = p->buffer, .size = ALLOCATION};
    if (p->submitted) g->CmdPipelineBarrier(p->commands, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, NULL, 1, &barrier, 0, NULL);
    for (unsigned y = 0; y < HEIGHT; ++y)
        p->fill(p->commands, p->buffer, OFFSET + y * STRIDE, WIDTH * 4, y < HEIGHT / 2 ? a : b);
    barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; barrier.dstAccessMask = 0;
    barrier.srcQueueFamilyIndex = g->family; barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    g->CmdPipelineBarrier(p->commands, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, NULL, 1, &barrier, 0, NULL);
    assert(g->EndCommandBuffer(p->commands) == VK_SUCCESS);
    VkPipelineStageFlags stage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    VkSubmitInfo submit = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .commandBufferCount = 1, .pCommandBuffers = &p->commands,
        .waitSemaphoreCount = 1, .pWaitSemaphores = &p->wait, .pWaitDstStageMask = &stage,
        .signalSemaphoreCount = 1, .pSignalSemaphores = &p->ready};
    assert(g->QueueSubmit(g->queue, 1, &submit, p->fence) == VK_SUCCESS);
    p->submitted = true;
    VkSemaphoreGetFdInfoKHR fd = {.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR,
        .semaphore = p->ready, .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT};
    int completion = -1;
    assert(g->GetSemaphoreFdKHR(g->device, &fd, &completion) == VK_SUCCESS);
    if (completion >= 0) {
        struct dma_buf_import_sync_file ready = {.flags = DMA_BUF_SYNC_WRITE, .fd = completion};
        assert(ioctl(p->fd, DMA_BUF_IOCTL_IMPORT_SYNC_FILE, &ready) == 0);
        close(completion);
    }
}

static void producer_destroy(struct Producer *p) {
    Gpu *g = p->gpu;
    if (p->submitted) {
        // EVENT_WAIT: producer teardown retains allocations until the queued writes complete; timeout fails.
        assert(g->WaitForFences(g->device, 1, &p->fence, true, 5000000000ull) == VK_SUCCESS);
    }
    if (p->fd >= 0) close(p->fd);
    if (p->buffer) g->DestroyBuffer(g->device, p->buffer, NULL);
    if (p->memory) g->FreeMemory(g->device, p->memory, NULL);
    if (p->fence) g->DestroyFence(g->device, p->fence, NULL);
    if (p->wait) g->DestroySemaphore(g->device, p->wait, NULL);
    if (p->ready) g->DestroySemaphore(g->device, p->ready, NULL);
    if (p->gate) p->destroy_event(g->device, p->gate, NULL);
    mdg_device_destroy(p->owner);
}

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
