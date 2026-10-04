#define _GNU_SOURCE
#include <vulkan/vulkan.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define CHECK(call)                                                                                \
    do {                                                                                           \
        VkResult r = (call);                                                                       \
        if (r != VK_SUCCESS) {                                                                     \
            fprintf(stderr, "%s: %d\n", #call, r);                                                 \
            exit(1);                                                                               \
        }                                                                                          \
    } while (0)
#define REQUIRE(c)                                                                                 \
    do {                                                                                           \
        if (!(c)) {                                                                                \
            fprintf(stderr, "failed: %s\n", #c);                                                   \
            exit(1);                                                                               \
        }                                                                                          \
    } while (0)
static uint64_t now(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC_RAW, &t);
    return (uint64_t)t.tv_sec * 1000000000 + t.tv_nsec;
}

int main(int argc, char **argv) {
    int disabled = argc > 1 && !strcmp(argv[1], "disabled");
    int khr = argc > 1 && !strcmp(argv[1], "khr");
    VkApplicationInfo ai = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
                            .apiVersion = VK_API_VERSION_1_2};
    VkInstanceCreateInfo ii = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
                               .pApplicationInfo = &ai};
    VkInstance instance;
    CHECK(vkCreateInstance(&ii, NULL, &instance));
    uint32_t n = 1;
    VkPhysicalDevice physical;
    CHECK(vkEnumeratePhysicalDevices(instance, &n, &physical));
    REQUIRE(n);
    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(physical, &props);
    CHECK(vkEnumerateDeviceExtensionProperties(physical, NULL, &n, NULL));
    VkExtensionProperties *ext = calloc(n, sizeof(*ext));
    REQUIRE(ext);
    CHECK(vkEnumerateDeviceExtensionProperties(physical, NULL, &n, ext));
    int has = 0, hasKhr = 0;
    for (uint32_t i = 0; i < n; ++i) {
        if (!strcmp(ext[i].extensionName, VK_EXT_CALIBRATED_TIMESTAMPS_EXTENSION_NAME))
            has = 1;
        if (!strcmp(ext[i].extensionName, VK_KHR_CALIBRATED_TIMESTAMPS_EXTENSION_NAME))
            hasKhr = 1;
    }
    free(ext);
    printf("device=%s period=%g calibrated=%d\n", props.deviceName, props.limits.timestampPeriod,
           has);
    REQUIRE(has != disabled);
    REQUIRE(hasKhr == has);
    vkGetPhysicalDeviceQueueFamilyProperties(physical, &n, NULL);
    VkQueueFamilyProperties *families = calloc(n, sizeof(*families));
    REQUIRE(families);
    vkGetPhysicalDeviceQueueFamilyProperties(physical, &n, families);
    uint32_t family = 0;
    while (family < n && !(families[family].queueFlags & VK_QUEUE_GRAPHICS_BIT))
        ++family;
    REQUIRE(family < n && families[family].timestampValidBits);
    uint32_t bits = families[family].timestampValidBits;
    free(families);
    uint64_t mask = bits == 64 ? UINT64_MAX : ((UINT64_C(1) << bits) - 1);
    const char *extension = khr ? VK_KHR_CALIBRATED_TIMESTAMPS_EXTENSION_NAME
                                : VK_EXT_CALIBRATED_TIMESTAMPS_EXTENSION_NAME;
    float priority = 1;
    VkDeviceQueueCreateInfo qi = {.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
                                  .queueFamilyIndex = family,
                                  .queueCount = 1,
                                  .pQueuePriorities = &priority};
    VkDeviceCreateInfo di = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
                             .queueCreateInfoCount = 1,
                             .pQueueCreateInfos = &qi,
                             .enabledExtensionCount = has,
                             .ppEnabledExtensionNames = &extension};
    VkDevice device;
    CHECK(vkCreateDevice(physical, &di, NULL, &device));
    PFN_vkGetCalibratedTimestampsEXT calibrated = (void *)vkGetDeviceProcAddr(
        device, khr ? "vkGetCalibratedTimestampsKHR" : "vkGetCalibratedTimestampsEXT");
    VkQueue queue;
    vkGetDeviceQueue(device, family, 0, &queue);
    VkQueryPoolCreateInfo qpi = {.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO,
                                 .queryType = VK_QUERY_TYPE_TIMESTAMP,
                                 .queryCount = 2};
    VkQueryPool queries;
    CHECK(vkCreateQueryPool(device, &qpi, NULL, &queries));
    VkCommandPoolCreateInfo cpi = {.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
                                   .queueFamilyIndex = family};
    VkCommandPool pool;
    CHECK(vkCreateCommandPool(device, &cpi, NULL, &pool));
    VkCommandBufferAllocateInfo cai = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
                                       .commandPool = pool,
                                       .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
                                       .commandBufferCount = 1};
    VkCommandBuffer cmd;
    CHECK(vkAllocateCommandBuffers(device, &cai, &cmd));
    VkCommandBufferBeginInfo cbi = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    CHECK(vkBeginCommandBuffer(cmd, &cbi));
    vkCmdResetQueryPool(cmd, queries, 0, 2);
    vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queries, 0);
    vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queries, 1);
    CHECK(vkEndCommandBuffer(cmd));
    VkFenceCreateInfo fi = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    VkFence fence;
    CHECK(vkCreateFence(device, &fi, NULL, &fence));
    VkCalibratedTimestampInfoEXT infos[2] = {
        {.sType = VK_STRUCTURE_TYPE_CALIBRATED_TIMESTAMP_INFO_EXT,
         .timeDomain = VK_TIME_DOMAIN_DEVICE_EXT},
        {.sType = VK_STRUCTURE_TYPE_CALIBRATED_TIMESTAMP_INFO_EXT,
         .timeDomain = VK_TIME_DOMAIN_CLOCK_MONOTONIC_RAW_EXT}};
    for (int i = 0; i < 16; ++i) {
        uint64_t before[2] = {0}, after[2] = {0}, deviation = 0, result[2];
        if (has) {
            REQUIRE(calibrated);
            CHECK(calibrated(device, 2, infos, before, &deviation));
            REQUIRE(deviation > 0);
        }
        VkSubmitInfo si = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                           .commandBufferCount = 1,
                           .pCommandBuffers = &cmd};
        CHECK(vkQueueSubmit(queue, 1, &si, fence));
        // EVENT_WAIT: GPU fence; expiry is a failed sample, never completed rendering.
        CHECK(vkWaitForFences(device, 1, &fence, VK_TRUE, UINT64_C(5000000000)));
        CHECK(vkResetFences(device, 1, &fence));
        CHECK(vkGetQueryPoolResults(device, queries, 0, 2, sizeof(result), result, sizeof(uint64_t),
                                    VK_QUERY_RESULT_64_BIT));
        uint64_t cpu0 = now();
        if (has)
            CHECK(calibrated(device, 2, infos, after, &deviation));
        uint64_t cpu1 = now();
        REQUIRE((result[0] & mask) <= (result[1] & mask));
        if (has) {
            REQUIRE((before[0] & mask) <= (result[0] & mask));
            REQUIRE((result[1] & mask) <= (after[0] & mask));
            REQUIRE(after[1] >= cpu0 && after[1] <= cpu1 && before[1] <= after[1]);
        }
        printf("sample=%d gpu=%" PRIu64 "..%" PRIu64 " calibrated=%" PRIu64 "..%" PRIu64
               " deviation=%" PRIu64 "\n",
               i, result[0], result[1], before[0], after[0], deviation);
    }
    vkDestroyFence(device, fence, NULL);
    vkDestroyCommandPool(device, pool, NULL);
    vkDestroyQueryPool(device, queries, NULL);
    vkDestroyDevice(device, NULL);
    vkDestroyInstance(instance, NULL);
    puts("PASS timestamps, queue completion and calibration agreement");
    return 0;
}
