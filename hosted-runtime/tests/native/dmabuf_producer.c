#define _GNU_SOURCE
#include "dmabuf_producer.h"
#include <assert.h>
#include <dlfcn.h>
#include <linux/dma-buf.h>
#include <sys/ioctl.h>
#include <unistd.h>

bool producer_create(struct Producer *p) {
    if (!p->width) { p->width = WIDTH; p->height = HEIGHT; p->stride = STRIDE; p->offset = OFFSET; }
    assert(p->width <= 8192 && p->height && p->height <= 8192 && p->stride >= p->width * 4);
    p->allocation = ((size_t)p->offset + (size_t)p->height * p->stride + ALLOCATION - 1) / ALLOCATION * ALLOCATION;
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
        .size = p->allocation, .usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT};
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

void producer_write(struct Producer *p, uint32_t a, uint32_t b) {
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
        .dstQueueFamilyIndex = g->family, .buffer = p->buffer, .size = p->allocation};
    if (p->submitted) g->CmdPipelineBarrier(p->commands, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, NULL, 1, &barrier, 0, NULL);
    for (unsigned y = 0; y < p->height; ++y)
        p->fill(p->commands, p->buffer, p->offset + y * p->stride, p->width * 4, y < p->height / 2 ? a : b);
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

void producer_destroy(struct Producer *p) {
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
