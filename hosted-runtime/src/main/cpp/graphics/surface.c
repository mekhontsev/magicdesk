#include "vulkan_internal.h"
#include <android/native_window.h>
#include <android/log.h>
#include <unistd.h>

enum { MAX_SWAPCHAIN_IMAGES = 8 };
static bool failed(const char *operation, VkResult result) {
    __android_log_print(ANDROID_LOG_WARN, "MagicDeskGraphics", "%s: Vulkan result %d", operation, result);
    return false;
}
struct MdgSurface {
    MdgDevice *device;
    ANativeWindow *window;
    VkSurfaceKHR surface;
    VkSwapchainKHR swapchain;
    VkFence available;
    MdgImage *images[MAX_SWAPCHAIN_IMAGES];
    unsigned count, index, width, height;
    bool acquired, invalid, acquire_pending;
};

static void release_swapchain(MdgSurface *surface) {
    Gpu *gpu = surface->device->gpu;
    if (surface->acquire_pending) {
        // EVENT_WAIT: teardown retains the WSI fence until acquisition completes or the device is lost.
        gpu->WaitForFences(gpu->device, 1, &surface->available, true, UINT64_MAX);
        surface->acquire_pending = false;
    }
    if (surface->swapchain) {
        // EVENT_WAIT: WSI owns queued presentation images until the queue is idle.
        // Only resize/teardown on this output's worker uses this completion wait.
        gpu->QueueWaitIdle(gpu->queue);
        mdg_device_collect(surface->device);
    }
    for (unsigned i = 0; i < surface->count; ++i) {
        mdg_image_unref(surface->images[i]); surface->images[i] = NULL;
    }
    if (surface->swapchain) gpu->DestroySwapchainKHR(gpu->device, surface->swapchain, NULL);
    surface->swapchain = VK_NULL_HANDLE;
    surface->count = 0;
    surface->index = 0;
    surface->acquired = false;
}

static void release_wsi(MdgSurface *surface) {
    release_swapchain(surface);
    Gpu *gpu = surface->device->gpu;
    if (surface->available) gpu->DestroyFence(gpu->device, surface->available, NULL);
    if (surface->surface) gpu->DestroySurfaceKHR(gpu->instance, surface->surface, NULL);
    surface->available = VK_NULL_HANDLE;
    surface->surface = VK_NULL_HANDLE;
}

MdgSurface *mdg_surface_create(MdgDevice *device, ANativeWindow *window) {
    if (!device || !window) return NULL;
    MdgSurface *surface = calloc(1, sizeof(*surface));
    if (!surface) return NULL;
    surface->device = device; surface->window = window;
    ANativeWindow_acquire(window);
    if (!mdg_device_gpu(device)) return surface;
    Gpu *gpu = device->gpu;
    VkAndroidSurfaceCreateInfoKHR info = {.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR, .window = window};
    VkFenceCreateInfo fence = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    VkBool32 supported = false;
    if (gpu->CreateAndroidSurfaceKHR(gpu->instance, &info, NULL, &surface->surface) ||
        gpu->GetPhysicalDeviceSurfaceSupportKHR(gpu->physical, gpu->family, surface->surface, &supported) || !supported ||
        gpu->CreateFence(gpu->device, &fence, NULL, &surface->available)) {
        release_wsi(surface);
    }
    return surface;
}

void mdg_surface_destroy(MdgSurface *surface) {
    if (!surface) return;
    release_wsi(surface);
    ANativeWindow_release(surface->window);
    free(surface);
}

static bool create_swapchain(MdgSurface *surface, unsigned width, unsigned height) {
    Gpu *gpu = surface->device->gpu;
    VkSurfaceCapabilitiesKHR capabilities;
    VkSurfaceFormatKHR formats[32]; uint32_t count = 32;
    if (gpu->GetPhysicalDeviceSurfaceCapabilitiesKHR(gpu->physical, surface->surface, &capabilities) ||
        gpu->GetPhysicalDeviceSurfaceFormatsKHR(gpu->physical, surface->surface, &count, formats)) return false;
    unsigned format = 0;
    while (format < count && (formats[format].format != VK_FORMAT_R8G8B8A8_UNORM ||
        formats[format].colorSpace != VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)) ++format;
    if (format == count || !(capabilities.supportedUsageFlags & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)) return false;
    VkExtent2D size = {width, height};
    if (size.width < capabilities.minImageExtent.width) size.width = capabilities.minImageExtent.width;
    if (size.height < capabilities.minImageExtent.height) size.height = capabilities.minImageExtent.height;
    if (size.width > capabilities.maxImageExtent.width) size.width = capabilities.maxImageExtent.width;
    if (size.height > capabilities.maxImageExtent.height) size.height = capabilities.maxImageExtent.height;
    count = capabilities.minImageCount + 1;
    if (capabilities.maxImageCount && count > capabilities.maxImageCount) count = capabilities.maxImageCount;
    if (count > MAX_SWAPCHAIN_IMAGES) return false;
    VkCompositeAlphaFlagBitsKHR alpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    if (capabilities.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR)
        alpha = VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR;
    else if (!(capabilities.supportedCompositeAlpha & alpha))
        alpha = (VkCompositeAlphaFlagBitsKHR)(capabilities.supportedCompositeAlpha & -capabilities.supportedCompositeAlpha);
    VkSwapchainCreateInfoKHR info = {.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
        .surface = surface->surface, .minImageCount = count, .imageFormat = formats[format].format,
        .imageColorSpace = formats[format].colorSpace, .imageExtent = size, .imageArrayLayers = 1,
        .imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .preTransform = capabilities.currentTransform, .compositeAlpha = alpha,
        .presentMode = VK_PRESENT_MODE_FIFO_KHR, .clipped = true};
    VkResult result = gpu->CreateSwapchainKHR(gpu->device, &info, NULL, &surface->swapchain);
    if (result) return failed("create swapchain", result);
    VkImage images[MAX_SWAPCHAIN_IMAGES]; count = MAX_SWAPCHAIN_IMAGES;
    result = gpu->GetSwapchainImagesKHR(gpu->device, surface->swapchain, &count, images);
    if (result) return failed("enumerate swapchain", result);
    for (unsigned i = 0; i < count; ++i) {
        MdgImage *image = mdg_image_new(surface->device, size.width, size.height);
        Image *native = calloc(1, sizeof(*native));
        if (!image || !native) { free(image); free(native); return false; }
        image->gpu = native;
        *native = (Image){.image = images[i], .attachment = true, .swapchain = true};
        surface->images[surface->count++] = image;
        VkImageViewCreateInfo view = {.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
            .image = images[i], .viewType = VK_IMAGE_VIEW_TYPE_2D, .format = formats[format].format,
            .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
        result = gpu->CreateImageView(gpu->device, &view, NULL, &native->view);
        if (result) return failed("swapchain image view", result);
        VkFramebufferCreateInfo frame = {.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
            .renderPass = gpu->render[0][0], .attachmentCount = 1, .pAttachments = &native->view,
            .width = size.width, .height = size.height, .layers = 1};
        result = gpu->CreateFramebuffer(gpu->device, &frame, NULL, &native->framebuffer);
        if (result) return failed("swapchain framebuffer", result);
    }
    return true;
}

MdgImage *mdg_surface_acquire(MdgSurface *surface, unsigned width, unsigned height) {
    if (!surface || surface->acquired || !width || !height || width > MDG_MAX_DIMENSION || height > MDG_MAX_DIMENSION) return NULL;
    if (surface->invalid || surface->width != width || surface->height != height || !surface->count) {
        release_swapchain(surface);
        surface->invalid = false;
        surface->width = width; surface->height = height;
        if (surface->surface) {
            if (!create_swapchain(surface, width, height)) release_wsi(surface);
        }
        if (!surface->surface) {
            surface->images[0] = mdg_image_create(surface->device, width, height);
            if (!surface->images[0]) return NULL;
            surface->count = 1;
        }
    }
    if (surface->surface) {
        Gpu *gpu = surface->device->gpu;
        // EVENT_WAIT: Android's next available swapchain image. Timeout rejects this frame.
        if (gpu->ResetFences(gpu->device, 1, &surface->available)) return NULL;
        VkResult result = gpu->AcquireNextImageKHR(gpu->device, surface->swapchain, 5000000000ULL,
            VK_NULL_HANDLE, surface->available, &surface->index);
        if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
            failed("acquire swapchain", result); surface->invalid = true; return NULL;
        }
        surface->acquire_pending = true;
        // EVENT_WAIT: WSI image ownership, on the output worker before any producer pixel lock.
        result = gpu->WaitForFences(gpu->device, 1, &surface->available, true, 5000000000ULL);
        if (result) { failed("acquire fence", result); surface->invalid = true; return NULL; }
        surface->acquire_pending = false;
        mdg_image_set_fence(surface->images[surface->index], -1);
    }
    surface->acquired = true;
    return surface->images[surface->index];
}

bool mdg_surface_present(MdgSurface *surface) {
    if (!surface || !surface->acquired) return false;
    surface->acquired = false;
    MdgImage *image = surface->images[surface->index];
    bool ok = false;
    if (surface->surface) {
        Gpu *gpu = surface->device->gpu;
        if (!mdg_wait_fence(image->fence)) { surface->invalid = true; return false; }
        mdg_device_collect(surface->device);
        VkPresentInfoKHR present = {.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
            .swapchainCount = 1, .pSwapchains = &surface->swapchain, .pImageIndices = &surface->index};
        VkResult result = gpu->QueuePresentKHR(gpu->queue, &present);
        ok = result == VK_SUCCESS || result == VK_SUBOPTIMAL_KHR;
        surface->invalid = result != VK_SUCCESS;
    } else {
        ANativeWindow_Buffer buffer;
        if (!ANativeWindow_setBuffersGeometry(surface->window, image->width, image->height, WINDOW_FORMAT_RGBA_8888) &&
                !ANativeWindow_lock(surface->window, &buffer, NULL)) {
            ok = buffer.width == (int)image->width && buffer.height == (int)image->height &&
                buffer.format == WINDOW_FORMAT_RGBA_8888 && buffer.stride >= buffer.width &&
                mdg_image_read(image, buffer.bits, (size_t)buffer.stride * 4);
            if (ANativeWindow_unlockAndPost(surface->window)) ok = false;
        }
    }
    return ok;
}
