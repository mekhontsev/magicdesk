#define _GNU_SOURCE
#include "vulkan_internal.h"
#include <assert.h>
#include <dlfcn.h>
#include <stdio.h>
#include <unistd.h>

static pid_t producer_pid;
__attribute__((constructor)) static void capture_producer(void) { producer_pid = getpid(); }

MdgDevice *__real_mdg_device_create(bool software);

/* Optional test-only headless loader: guest Mesa need not expose Android
 * surfaces/AHardwareBuffer. The default still uses the production device. */
MdgDevice *__wrap_mdg_device_create(bool software) {
    bool producer = getpid() == producer_pid;
    const char *path = getenv(producer ? "MDG_TEST_PRODUCER_VULKAN" : "MDG_TEST_CONSUMER_VULKAN");
    if (!path || !*path) return __real_mdg_device_create(software);
    assert(!software && path[0] == '/');
    MdgDevice *d = calloc(1, sizeof(*d));
    Gpu *g = calloc(1, sizeof(*g)); assert(d && g); d->gpu = g;
    g->library = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!g->library) fprintf(stderr, "%s\n", dlerror());
    assert(g->library);
    PFN_vkGetInstanceProcAddr get = (PFN_vkGetInstanceProcAddr)dlsym(g->library, "vkGetInstanceProcAddr"); assert(get);
    PFN_vkCreateInstance create = (PFN_vkCreateInstance)get(NULL, "vkCreateInstance"); assert(create);
    VkApplicationInfo app = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO, .apiVersion = VK_API_VERSION_1_1};
    VkInstanceCreateInfo instance = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &app};
    assert(create(&instance, NULL, &g->instance) == VK_SUCCESS);
#define LOAD_INSTANCE(name) PFN_vk##name name = (PFN_vk##name)get(g->instance, "vk" #name); assert(name)
    LOAD_INSTANCE(EnumeratePhysicalDevices);
    LOAD_INSTANCE(GetPhysicalDeviceQueueFamilyProperties);
    LOAD_INSTANCE(GetPhysicalDeviceMemoryProperties);
    LOAD_INSTANCE(GetPhysicalDeviceProperties);
    LOAD_INSTANCE(CreateDevice);
    LOAD_INSTANCE(GetDeviceProcAddr);
#undef LOAD_INSTANCE
    uint32_t count = 1;
    assert(EnumeratePhysicalDevices(g->instance, &count, &g->physical) == VK_SUCCESS && count == 1);
    VkPhysicalDeviceProperties properties; GetPhysicalDeviceProperties(g->physical, &properties);
    snprintf(d->name, sizeof(d->name), "%.240s", properties.deviceName);
    printf("%s headless GPU=%s driver=%08x\n", producer ? "producer" : "consumer", d->name, properties.driverVersion);
    fflush(stdout);
    VkQueueFamilyProperties queues[32]; count = 32;
    GetPhysicalDeviceQueueFamilyProperties(g->physical, &count, queues);
    while (g->family < count && !(queues[g->family].queueFlags & VK_QUEUE_GRAPHICS_BIT)) ++g->family;
    assert(g->family < count);
    float priority = 1;
    VkDeviceQueueCreateInfo queue = {.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, .queueFamilyIndex = g->family,
        .queueCount = 1, .pQueuePriorities = &priority};
    const char *extensions[] = {VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME, VK_EXT_EXTERNAL_MEMORY_DMA_BUF_EXTENSION_NAME,
        VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME};
    VkDeviceCreateInfo device = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, .queueCreateInfoCount = 1,
        .pQueueCreateInfos = &queue, .enabledExtensionCount = 4, .ppEnabledExtensionNames = extensions};
    assert(CreateDevice(g->physical, &device, NULL, &g->device) == VK_SUCCESS);
#define LOAD_DEVICE(name) g->name = (PFN_vk##name)GetDeviceProcAddr(g->device, "vk" #name);
    DEVICE_FUNCTIONS(LOAD_DEVICE)
#undef LOAD_DEVICE
    g->DestroyInstance = (PFN_vkDestroyInstance)get(g->instance, "vkDestroyInstance");
    g->GetMemoryFdPropertiesKHR = (PFN_vkGetMemoryFdPropertiesKHR)GetDeviceProcAddr(g->device, "vkGetMemoryFdPropertiesKHR");
    assert(g->GetMemoryFdPropertiesKHR); g->linear_dmabuf = true;
    GetPhysicalDeviceMemoryProperties(g->physical, &g->memory);
    g->GetDeviceQueue(g->device, g->family, 0, &g->queue);
    VkCommandPoolCreateInfo pool = {.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, .queueFamilyIndex = g->family};
    assert(g->CreateCommandPool(g->device, &pool, NULL, &g->pool) == VK_SUCCESS);
    return d;
}
