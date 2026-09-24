#ifndef MAGICDESK_VULKAN_INTERNAL_H
#define MAGICDESK_VULKAN_INTERNAL_H
#define VK_NO_PROTOTYPES
#define VK_USE_PLATFORM_ANDROID_KHR
#include "graphics_internal.h"
#include <vulkan/vulkan.h>

#define DEVICE_FUNCTIONS(X) \
    X(DestroyDevice) X(GetDeviceQueue) X(DeviceWaitIdle) \
    X(CreateImage) X(DestroyImage) X(GetImageMemoryRequirements) X(BindImageMemory) \
    X(AllocateMemory) X(FreeMemory) X(MapMemory) X(UnmapMemory) \
    X(CreateBuffer) X(DestroyBuffer) X(GetBufferMemoryRequirements) X(BindBufferMemory) \
    X(CreateImageView) X(DestroyImageView) X(CreateSampler) X(DestroySampler) \
    X(CreateRenderPass) X(DestroyRenderPass) X(CreateFramebuffer) X(DestroyFramebuffer) \
    X(CreateShaderModule) X(DestroyShaderModule) X(CreateGraphicsPipelines) X(DestroyPipeline) \
    X(CreatePipelineLayout) X(DestroyPipelineLayout) \
    X(CreateDescriptorSetLayout) X(DestroyDescriptorSetLayout) \
    X(CreateDescriptorPool) X(DestroyDescriptorPool) X(AllocateDescriptorSets) X(UpdateDescriptorSets) \
    X(CreateCommandPool) X(DestroyCommandPool) X(AllocateCommandBuffers) X(ResetCommandBuffer) \
    X(BeginCommandBuffer) X(EndCommandBuffer) X(CmdPipelineBarrier) X(CmdCopyBufferToImage) \
    X(CmdBeginRenderPass) X(CmdEndRenderPass) X(CmdBindPipeline) X(CmdBindDescriptorSets) \
    X(CmdPushConstants) X(CmdSetViewport) X(CmdSetScissor) X(CmdDraw) \
    X(CreateFence) X(DestroyFence) X(ResetFences) X(GetFenceStatus) X(WaitForFences) \
    X(CreateSemaphore) X(DestroySemaphore) X(ImportSemaphoreFdKHR) X(GetSemaphoreFdKHR) \
    X(QueueSubmit) X(GetAndroidHardwareBufferPropertiesANDROID) \
    X(CreateSwapchainKHR) X(DestroySwapchainKHR) X(GetSwapchainImagesKHR) \
    X(AcquireNextImageKHR) X(QueuePresentKHR) X(QueueWaitIdle)

typedef struct {
    void *library;
    VkInstance instance;
    VkDevice device;
    VkPhysicalDevice physical;
    VkQueue queue;
    uint32_t family;
    VkPhysicalDeviceMemoryProperties memory;
    VkCommandPool pool;
    VkDescriptorSetLayout descriptors;
    VkPipelineLayout layout;
    VkRenderPass render[2][2];
    VkPipeline pipelines[2][2];
    VkSampler samplers[2];
    MdgImage *white;
    bool lost;
    PFN_vkDestroyInstance DestroyInstance;
    PFN_vkCreateAndroidSurfaceKHR CreateAndroidSurfaceKHR;
    PFN_vkDestroySurfaceKHR DestroySurfaceKHR;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR GetPhysicalDeviceSurfaceSupportKHR;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR GetPhysicalDeviceSurfaceCapabilitiesKHR;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR GetPhysicalDeviceSurfaceFormatsKHR;
#define DECLARE(name) PFN_vk##name name;
    DEVICE_FUNCTIONS(DECLARE)
#undef DECLARE
} Gpu;
typedef struct {
    VkImage image;
    VkDeviceMemory memory;
    VkImageView view;
    VkFramebuffer framebuffer;
    bool initialized, attachment, swapchain;
    unsigned format_index;
} Image;
typedef struct {
    VkCommandBuffer command;
    VkFence fence;
    VkSemaphore ready;
    VkSemaphore wait[MDG_MAX_DRAWS + 1];
    unsigned waits;
    VkDescriptorPool descriptors;
    VkDescriptorSet sets[MDG_MAX_DRAWS];
    VkBuffer upload;
    VkDeviceMemory memory;
    void *mapped;
    VkDeviceSize capacity;
} Pass;
#endif
