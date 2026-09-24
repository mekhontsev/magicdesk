#include "vulkan_internal.h"
#include <fcntl.h>
#include <linux/dma-buf.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <unistd.h>

bool mdg_vk_linear_dmabuf(const MdgDevice *device) {
    return mdg_vk_ready(device) && ((Gpu *)device->gpu)->linear_dmabuf;
}

bool mdg_dmabuf_acquire(MdgImage *image, int *owned_fd) {
    *owned_fd = -1;
    struct dma_buf_export_sync_file sync = {.flags = DMA_BUF_SYNC_READ, .fd = -1};
    if (ioctl(image->dmabuf_fd, DMA_BUF_IOCTL_EXPORT_SYNC_FILE, &sync) < 0) return false;
    *owned_fd = sync.fd;
    return true;
}

bool mdg_dmabuf_release(MdgImage *image, int completion_fd) {
    /* -1 means that Vulkan has already completed these reads. */
    if (completion_fd < 0) return true;
    struct dma_buf_import_sync_file sync = {.flags = DMA_BUF_SYNC_READ, .fd = completion_fd};
    return ioctl(image->dmabuf_fd, DMA_BUF_IOCTL_IMPORT_SYNC_FILE, &sync) == 0;
}

bool mdg_vk_import_dmabuf(MdgImage *image, const MdgLinearDmaBuf *buffer) {
    Gpu *gpu = image->device->gpu;
    struct stat status;
    uint64_t end = (uint64_t)buffer->offset + (uint64_t)(image->height - 1) * buffer->stride + image->width * 4;
    if (fstat(buffer->fd, &status) || status.st_size <= 0 || end > (uint64_t)status.st_size) return false;
    image->dmabuf_fd = fcntl(buffer->fd, F_DUPFD_CLOEXEC, 0);
    if (image->dmabuf_fd < 0) return false;
    int fence = -1;
    /* Validates a real DMA-BUF with kernel fence export, without CPU mapping or waiting. */
    if (!mdg_dmabuf_acquire(image, &fence)) return false;
    if (fence >= 0) close(fence);
    VkMemoryFdPropertiesKHR properties = {.sType = VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR};
    if (gpu->GetMemoryFdPropertiesKHR(gpu->device, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
            image->dmabuf_fd, &properties) || !mdg_vk_image(image)) return false;
    Image *native = image->gpu;
    VkExternalMemoryBufferCreateInfo external = {.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO,
        .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
    VkBufferCreateInfo info = {.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .pNext = &external,
        .size = end, .usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT};
    if (gpu->CreateBuffer(gpu->device, &info, NULL, &native->dma_buffer)) return false;
    native->dma_size = end;
    VkMemoryRequirements requirements;
    gpu->GetBufferMemoryRequirements(gpu->device, native->dma_buffer, &requirements);
    uint32_t types = properties.memoryTypeBits & requirements.memoryTypeBits;
    /* Import the allocation whole: never pretend that padding outside the FD exists. */
    if (!types || requirements.size > (uint64_t)status.st_size) return false;
    int fd = fcntl(image->dmabuf_fd, F_DUPFD_CLOEXEC, 0);
    if (fd < 0) return false;
    VkImportMemoryFdInfoKHR import = {.sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR,
        .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, .fd = fd};
    VkMemoryAllocateInfo allocate = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .pNext = &import,
        .allocationSize = status.st_size, .memoryTypeIndex = __builtin_ctz(types)};
    if (gpu->AllocateMemory(gpu->device, &allocate, NULL, &native->dma_memory)) { close(fd); return false; }
    return gpu->BindBufferMemory(gpu->device, native->dma_buffer, native->dma_memory, 0) == VK_SUCCESS;
}

void mdg_vk_dmabuf_barrier(Gpu *gpu, Pass *pass, Image *image, bool acquire) {
    VkBufferMemoryBarrier barrier = {.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
        .srcAccessMask = acquire ? 0 : VK_ACCESS_TRANSFER_READ_BIT,
        .dstAccessMask = acquire ? VK_ACCESS_TRANSFER_READ_BIT : 0,
        .srcQueueFamilyIndex = acquire ? VK_QUEUE_FAMILY_FOREIGN_EXT : gpu->family,
        .dstQueueFamilyIndex = acquire ? gpu->family : VK_QUEUE_FAMILY_FOREIGN_EXT,
        .buffer = image->dma_buffer, .offset = 0, .size = image->dma_size};
    gpu->CmdPipelineBarrier(pass->command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        0, 0, NULL, 1, &barrier, 0, NULL);
}
