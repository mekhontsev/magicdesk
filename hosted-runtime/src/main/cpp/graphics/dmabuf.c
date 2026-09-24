#include "vulkan_internal.h"
#include <fcntl.h>
#include <errno.h>
#include <linux/dma-buf.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
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

static bool rejected(MdgImportError *error, const char *operation, int code) {
    error->operation = operation; error->code = code;
    return false;
}
bool mdg_vk_import_dmabuf(MdgImage *image, const MdgLinearDmaBuf *buffer, MdgImportError *error) {
    Gpu *gpu = image->device->gpu;
    struct stat status;
    uint64_t end = (uint64_t)buffer->offset + (uint64_t)(image->height - 1) * buffer->stride + image->width * 4;
    if (fstat(buffer->fd, &status)) return rejected(error, "fstat", errno);
    error->available = status.st_size > 0 ? status.st_size : 0;
    error->required = end;
    if (status.st_size <= 0 || end > (uint64_t)status.st_size) return rejected(error, "pixel extent", 0);
    image->dmabuf_fd = fcntl(buffer->fd, F_DUPFD_CLOEXEC, 0);
    if (image->dmabuf_fd < 0) return rejected(error, "fcntl", errno);
    int fence = -1;
    /* Validates a real DMA-BUF with kernel fence export, without CPU mapping or waiting. */
    if (!mdg_dmabuf_acquire(image, &fence)) return rejected(error, "DMA_BUF_IOCTL_EXPORT_SYNC_FILE", errno);
    if (fence >= 0) close(fence);
    VkMemoryFdPropertiesKHR properties = {.sType = VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR};
    VkResult result = gpu->GetMemoryFdPropertiesKHR(gpu->device, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
            image->dmabuf_fd, &properties);
    if (result) return rejected(error, "vkGetMemoryFdPropertiesKHR", result);
    if (!mdg_vk_image(image)) return rejected(error, "texture allocation", 0);
    Image *native = image->gpu;
    VkExternalMemoryBufferCreateInfo external = {.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO,
        .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
    VkBufferCreateInfo info = {.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .pNext = &external,
        .size = end, .usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT};
    result = gpu->CreateBuffer(gpu->device, &info, NULL, &native->dma_buffer);
    if (result) return rejected(error, "vkCreateBuffer", result);
    native->dma_size = end;
    native->dma_rows = image->height;
    VkMemoryRequirements requirements;
    gpu->GetBufferMemoryRequirements(gpu->device, native->dma_buffer, &requirements);
    if (requirements.size > (uint64_t)status.st_size) {
        /* Driver padding belongs to the allocation, not to the pixel extent.
         * Import only complete rows whose actual requirements fit the FD. */
        uint64_t overhead = requirements.size - end;
        uint64_t first = (uint64_t)buffer->offset + image->width * 4;
        native->dma_rows = (uint64_t)status.st_size >= overhead + first
            ? ((uint64_t)status.st_size - overhead - first) / buffer->stride + 1 : 0;
        if (native->dma_rows >= image->height) return rejected(error, "Vulkan allocation size", 0);
        gpu->DestroyBuffer(gpu->device, native->dma_buffer, NULL);
        native->dma_buffer = VK_NULL_HANDLE;
        native->dma_size = 0;
        if (native->dma_rows) {
            info.size = (uint64_t)buffer->offset + (uint64_t)(native->dma_rows - 1) * buffer->stride + image->width * 4;
            result = gpu->CreateBuffer(gpu->device, &info, NULL, &native->dma_buffer);
            if (result) return rejected(error, "vkCreateBuffer prefix", result);
            native->dma_size = info.size;
            gpu->GetBufferMemoryRequirements(gpu->device, native->dma_buffer, &requirements);
            error->required = requirements.size;
            if (requirements.size > (uint64_t)status.st_size) return rejected(error, "Vulkan prefix allocation size", 0);
        }
        uint64_t tail = (uint64_t)buffer->offset + (uint64_t)native->dma_rows * buffer->stride;
        long page = sysconf(_SC_PAGESIZE);
        if (page <= 0) return rejected(error, "page size", 0);
        uint64_t aligned = tail / page * page;
        native->dma_mapping_size = end - aligned;
        native->dma_mapping_offset = tail - aligned;
        void *mapping = mmap(NULL, native->dma_mapping_size, PROT_READ, MAP_SHARED, image->dmabuf_fd, aligned);
        if (mapping == MAP_FAILED) return rejected(error, "DMA-BUF tail mmap", errno);
        native->dma_mapping = mapping;
        if (!native->dma_rows) return true;
    }
    uint32_t types = properties.memoryTypeBits & requirements.memoryTypeBits;
    /* Import the allocation whole: never pretend that padding outside the FD exists. */
    error->required = requirements.size;
    if (!types) return rejected(error, "memory type intersection", 0);
    if (requirements.size > (uint64_t)status.st_size) return rejected(error, "Vulkan allocation size", 0);
    int fd = fcntl(image->dmabuf_fd, F_DUPFD_CLOEXEC, 0);
    if (fd < 0) return rejected(error, "fcntl", errno);
    VkImportMemoryFdInfoKHR import = {.sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR,
        .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, .fd = fd};
    VkMemoryAllocateInfo allocate = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .pNext = &import,
        .allocationSize = status.st_size, .memoryTypeIndex = __builtin_ctz(types)};
    result = gpu->AllocateMemory(gpu->device, &allocate, NULL, &native->dma_memory);
    if (result) { close(fd); return rejected(error, "vkAllocateMemory", result); }
    result = gpu->BindBufferMemory(gpu->device, native->dma_buffer, native->dma_memory, 0);
    return result ? rejected(error, "vkBindBufferMemory", result) : true;
}

void mdg_vk_dmabuf_barrier(Gpu *gpu, Pass *pass, Image *image, bool acquire) {
    if (!image->dma_buffer) return;
    VkBufferMemoryBarrier barrier = {.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
        .srcAccessMask = acquire ? 0 : VK_ACCESS_TRANSFER_READ_BIT,
        .dstAccessMask = acquire ? VK_ACCESS_TRANSFER_READ_BIT : 0,
        .srcQueueFamilyIndex = acquire ? VK_QUEUE_FAMILY_FOREIGN_EXT : gpu->family,
        .dstQueueFamilyIndex = acquire ? gpu->family : VK_QUEUE_FAMILY_FOREIGN_EXT,
        .buffer = image->dma_buffer, .offset = 0, .size = image->dma_size};
    gpu->CmdPipelineBarrier(pass->command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        0, 0, NULL, 1, &barrier, 0, NULL);
}

MdgSubmitResult mdg_vk_prepare(MdgPass *pass, int *wait_fd) {
    *wait_fd = -1;
    for (unsigned i = 0; i < pass->count; ++i) {
        MdgImage *image = pass->commands[i].draw.image;
        if (!image || image->dmabuf_fd < 0) continue;
        Image *native = image->gpu;
        if (!native || !native->dma_mapping) continue;
        int fd;
        if (!mdg_dmabuf_acquire(image, &fd)) return MDG_SUBMIT_FAILED;
        if (fd < 0) continue;
        struct pollfd event = {.fd = fd, .events = POLLIN};
        int result;
        do result = poll(&event, 1, 0); while (result < 0 && errno == EINTR);
        if (!result) { *wait_fd = fd; return MDG_SUBMIT_DEFERRED; }
        close(fd);
        if (result < 0 || event.revents != POLLIN) return MDG_SUBMIT_FAILED;
    }
    return MDG_SUBMIT_OK;
}

bool mdg_vk_dmabuf_tail(MdgImage *image, void *destination) {
    Image *native = image->gpu;
    if (!native->dma_mapping) return true;
    /* Source readiness was observed before recording. The protocol's consumer
     * lease excludes new producer writes until we publish the read fence. */
    struct dma_buf_sync sync = {.flags = DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ};
    if (ioctl(image->dmabuf_fd, DMA_BUF_IOCTL_SYNC, &sync)) return false;
    const char *source = (char *)native->dma_mapping + native->dma_mapping_offset;
    size_t row = image->width * 4;
    for (unsigned y = 0; y < image->height - native->dma_rows; ++y)
        memcpy((char *)destination + y * row, source + y * image->stride, row);
    sync.flags = DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ;
    if (ioctl(image->dmabuf_fd, DMA_BUF_IOCTL_SYNC, &sync)) return false;
    image->device->stats.dma_tail_bytes += row * (image->height - native->dma_rows);
    return true;
}
