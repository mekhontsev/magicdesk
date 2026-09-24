#pragma once
#include "vulkan_internal.h"

enum { WIDTH = 33, HEIGHT = 19, STRIDE = 160, OFFSET = 12, ALLOCATION = 65536 };
struct Producer {
    unsigned width, height, stride, offset;
    size_t allocation;
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
    PFN_vkResetEvent reset_event;
};

bool producer_create(struct Producer *producer);
void producer_write(struct Producer *producer, uint32_t top, uint32_t bottom);
void producer_destroy(struct Producer *producer);
