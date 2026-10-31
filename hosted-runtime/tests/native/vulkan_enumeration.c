#define VK_NO_PROTOTYPES
#include "graphics.h"
#include <vulkan/vulkan.h>
#include <assert.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static struct {
    uint32_t available, written;
    VkResult query_result, fill_result;
    bool null_handle;
    unsigned opened, queried, filled, inspected, destroyed, closed;
} state;
static char library, instance, physical[32];

static VKAPI_ATTR VkResult VKAPI_CALL create_instance(const VkInstanceCreateInfo *info,
        const VkAllocationCallbacks *allocator, VkInstance *output) {
    assert(info && info->sType == VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO && !allocator);
    *output = (VkInstance)&instance;
    return VK_SUCCESS;
}

static VKAPI_ATTR void VKAPI_CALL destroy_instance(VkInstance value, const VkAllocationCallbacks *allocator) {
    assert(value == (VkInstance)&instance && !allocator);
    ++state.destroyed;
}

static VKAPI_ATTR VkResult VKAPI_CALL enumerate(VkInstance value, uint32_t *count, VkPhysicalDevice *devices) {
    assert(value == (VkInstance)&instance && count);
    if (!devices) {
        assert(++state.queried == 1 && *count == 0);
        *count = state.available;
        return state.query_result;
    }
    assert(state.queried == 1 && state.query_result == VK_SUCCESS && state.available > 0);
    assert(++state.filled == 1 && *count == state.available);
    assert(state.available <= sizeof(physical));
    if (state.fill_result == VK_SUCCESS || state.fill_result == VK_INCOMPLETE) {
        for (uint32_t i = 0; i < state.written && i < *count; ++i)
            devices[i] = state.null_handle ? VK_NULL_HANDLE : (VkPhysicalDevice)&physical[i];
    }
    *count = state.written;
    return state.fill_result;
}

static VKAPI_ATTR void VKAPI_CALL properties(VkPhysicalDevice device, VkPhysicalDeviceProperties *output) {
    assert(state.filled == 1 && state.inspected < state.written);
    assert(device == (VkPhysicalDevice)&physical[state.inspected++]);
    // Stop device selection after enumeration; real GPU coverage uses graphics-test.
    *output = (VkPhysicalDeviceProperties){.apiVersion = VK_API_VERSION_1_0};
}

static VKAPI_ATTR void VKAPI_CALL unexpected(void) {
    abort();
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL get_proc(VkInstance value, const char *name) {
    if (!strcmp(name, "vkCreateInstance")) {
        assert(value == VK_NULL_HANDLE);
        return (PFN_vkVoidFunction)create_instance;
    }
    assert(value == (VkInstance)&instance);
    if (!strcmp(name, "vkDestroyInstance")) return (PFN_vkVoidFunction)destroy_instance;
    if (!strcmp(name, "vkEnumeratePhysicalDevices")) return (PFN_vkVoidFunction)enumerate;
    if (!strcmp(name, "vkGetPhysicalDeviceProperties")) return (PFN_vkVoidFunction)properties;
    return unexpected;
}

void *__wrap_dlopen(const char *name, int flags) {
    assert(!strcmp(name, "libvulkan.so") && flags == (RTLD_NOW | RTLD_LOCAL));
    ++state.opened;
    return &library;
}

void *__wrap_dlsym(void *handle, const char *name) {
    assert(handle == &library && !strcmp(name, "vkGetInstanceProcAddr"));
    return (void *)get_proc;
}

int __wrap_dlclose(void *handle) {
    assert(handle == &library);
    ++state.closed;
    return 0;
}

int main(int argc, char **argv) {
    assert(argc == 2);
    unsigned expected_fill = 0, expected_properties = 0;
    bool software = false;
    if (!strcmp(argv[1], "software")) software = true;
    else if (!strcmp(argv[1], "empty")) { }
    else if (!strcmp(argv[1], "query-error")) state.query_result = VK_ERROR_INITIALIZATION_FAILED;
    else {
        state.available = state.written = 3;
        expected_fill = 1;
        if (!strcmp(argv[1], "complete")) expected_properties = 3;
        else if (!strcmp(argv[1], "many")) state.available = state.written = expected_properties = 20;
        else if (!strcmp(argv[1], "shrink")) state.written = expected_properties = 1;
        else if (!strcmp(argv[1], "incomplete")) {
            state.fill_result = VK_INCOMPLETE;
            state.written = expected_properties = 2;
        } else if (!strcmp(argv[1], "disappeared")) state.written = 0;
        else if (!strcmp(argv[1], "fill-error")) state.fill_result = VK_ERROR_INITIALIZATION_FAILED;
        else if (!strcmp(argv[1], "invalid-count")) state.written = state.available + 1;
        else if (!strcmp(argv[1], "null-handle")) state.null_handle = true;
        else abort();
    }
    MdgDevice *device = mdg_device_create(software);
    assert(device && !mdg_device_gpu(device) && !strcmp(mdg_device_name(device), "software"));
    unsigned opened = state.opened, queried = state.queried, inspected = state.inspected;
    for (unsigned i = 0; i < 100; ++i) {
        assert(!strcmp(mdg_device_description(device), software ? "backend=software, selection=explicit"
            : "backend=software, selection=automatic-fallback"));
    }
    assert(state.opened == opened && state.queried == queried && state.inspected == inspected);
    mdg_device_destroy(device);
    assert(state.opened == (software ? 0u : 1u));
    assert(state.queried == state.opened && state.filled == expected_fill);
    assert(state.inspected == expected_properties);
    assert(state.destroyed == state.opened && state.closed == state.opened);
    printf("Vulkan enumeration %s: passed\n", argv[1]);
    return 0;
}
