#define VK_NO_PROTOTYPES
#define VK_USE_PLATFORM_ANDROID_KHR
#include "graphics_internal.h"
#include "shaders.h"
#include <android/hardware_buffer.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <math.h>
#include <stdio.h>
#include <unistd.h>
#include <vulkan/vulkan.h>

#include "vulkan_internal.h"

static bool has_extension(VkExtensionProperties *properties, uint32_t count, const char *name) {
    for (unsigned i = 0; i < count; ++i) if (!strcmp(properties[i].extensionName, name)) return true;
    return false;
}
static uint32_t memory_type(Gpu *gpu, uint32_t mask, VkMemoryPropertyFlags flags) {
    for (unsigned i = 0; i < gpu->memory.memoryTypeCount; ++i)
        if ((mask & (1u << i)) && (gpu->memory.memoryTypes[i].propertyFlags & flags) == flags) return i;
    return UINT32_MAX;
}

static bool pipeline(Gpu *gpu) {
    VkDescriptorSetLayoutBinding binding = {.binding = 0, .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
        .descriptorCount = 1, .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT};
    VkDescriptorSetLayoutCreateInfo descriptors = {.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .bindingCount = 1, .pBindings = &binding};
    if (gpu->CreateDescriptorSetLayout(gpu->device, &descriptors, NULL, &gpu->descriptors)) return false;
    VkPushConstantRange push = {.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, .size = 64};
    VkPipelineLayoutCreateInfo layout = {.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .setLayoutCount = 1, .pSetLayouts = &gpu->descriptors, .pushConstantRangeCount = 1, .pPushConstantRanges = &push};
    if (gpu->CreatePipelineLayout(gpu->device, &layout, NULL, &gpu->layout)) return false;
    for (unsigned i = 0; i < 2; ++i) {
        VkSamplerCreateInfo sampler = {.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
            .magFilter = i ? VK_FILTER_LINEAR : VK_FILTER_NEAREST, .minFilter = i ? VK_FILTER_LINEAR : VK_FILTER_NEAREST,
            .mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST, .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
            .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE};
        if (gpu->CreateSampler(gpu->device, &sampler, NULL, &gpu->samplers[i])) return false;
        for (unsigned format = 0; format < 2; ++format) {
        VkAttachmentDescription attachment = {.format = format ? VK_FORMAT_B8G8R8A8_UNORM : VK_FORMAT_R8G8B8A8_UNORM, .samples = VK_SAMPLE_COUNT_1_BIT,
            .loadOp = i ? VK_ATTACHMENT_LOAD_OP_LOAD : VK_ATTACHMENT_LOAD_OP_CLEAR, .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
            .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE, .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
            .initialLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, .finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkAttachmentReference reference = {.attachment = 0, .layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription subpass = {.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS,
            .colorAttachmentCount = 1, .pColorAttachments = &reference};
        VkRenderPassCreateInfo render = {.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
            .attachmentCount = 1, .pAttachments = &attachment, .subpassCount = 1, .pSubpasses = &subpass};
        if (gpu->CreateRenderPass(gpu->device, &render, NULL, &gpu->render[format][i])) return false;
        }
    }
    VkShaderModule modules[2] = {0};
    VkShaderModuleCreateInfo code = {.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize = sizeof(mdg_vertex), .pCode = (const uint32_t *)mdg_vertex};
    if (gpu->CreateShaderModule(gpu->device, &code, NULL, &modules[0])) return false;
    code.codeSize = sizeof(mdg_fragment); code.pCode = (const uint32_t *)mdg_fragment;
    bool ok = gpu->CreateShaderModule(gpu->device, &code, NULL, &modules[1]) == VK_SUCCESS;
    VkPipelineShaderStageCreateInfo shaders[2] = {
        {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, .stage = VK_SHADER_STAGE_VERTEX_BIT, .module = modules[0], .pName = "main"},
        {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, .stage = VK_SHADER_STAGE_FRAGMENT_BIT, .module = modules[1], .pName = "main"}};
    VkPipelineVertexInputStateCreateInfo vertices = {.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo assembly = {.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
        .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP};
    VkPipelineViewportStateCreateInfo viewport = {.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
        .viewportCount = 1, .scissorCount = 1};
    VkPipelineRasterizationStateCreateInfo raster = {.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
        .polygonMode = VK_POLYGON_MODE_FILL, .cullMode = VK_CULL_MODE_NONE, .frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE, .lineWidth = 1};
    VkPipelineMultisampleStateCreateInfo samples = {.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
        .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT};
    VkPipelineColorBlendAttachmentState blend = {.srcColorBlendFactor = VK_BLEND_FACTOR_ONE,
        .dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, .colorBlendOp = VK_BLEND_OP_ADD,
        .srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE, .dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
        .alphaBlendOp = VK_BLEND_OP_ADD, .colorWriteMask = 15};
    VkPipelineColorBlendStateCreateInfo blending = {.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
        .attachmentCount = 1, .pAttachments = &blend};
    VkDynamicState states[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamic = {.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO,
        .dynamicStateCount = 2, .pDynamicStates = states};
    VkGraphicsPipelineCreateInfo info = {.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
        .stageCount = 2, .pStages = shaders, .pVertexInputState = &vertices, .pInputAssemblyState = &assembly,
        .pViewportState = &viewport, .pRasterizationState = &raster, .pMultisampleState = &samples,
        .pColorBlendState = &blending, .pDynamicState = &dynamic, .layout = gpu->layout};
    for (unsigned format = 0; format < 2 && ok; ++format) for (unsigned i = 0; i < 2 && ok; ++i) {
        blend.blendEnable = i;
        info.renderPass = gpu->render[format][0];
        ok = gpu->CreateGraphicsPipelines(gpu->device, VK_NULL_HANDLE, 1, &info, NULL, &gpu->pipelines[format][i]) == VK_SUCCESS;
    }
    for (unsigned i = 0; i < 2; ++i) if (modules[i]) gpu->DestroyShaderModule(gpu->device, modules[i], NULL);
    return ok;
}

bool mdg_vk_create(MdgDevice *device) {
    Gpu *gpu = calloc(1, sizeof(*gpu));
    if (!gpu) return false;
    device->gpu = gpu;
    gpu->library = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!gpu->library) goto fail;
    PFN_vkGetInstanceProcAddr get = (PFN_vkGetInstanceProcAddr)dlsym(gpu->library, "vkGetInstanceProcAddr");
    if (!get) goto fail;
    PFN_vkCreateInstance create = (PFN_vkCreateInstance)get(NULL, "vkCreateInstance");
    if (!create) goto fail;
    VkApplicationInfo app = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO, .pApplicationName = "MagicDesk graphics",
        .apiVersion = VK_API_VERSION_1_1};
    const char *instance_extensions[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    VkInstanceCreateInfo instance = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &app,
        .enabledExtensionCount = 2, .ppEnabledExtensionNames = instance_extensions};
    if (create(&instance, NULL, &gpu->instance)) goto fail;
    gpu->DestroyInstance = (PFN_vkDestroyInstance)get(gpu->instance, "vkDestroyInstance");
#define SURFACE(name) gpu->name = (PFN_vk##name)get(gpu->instance, "vk" #name); if (!gpu->name) goto fail
    SURFACE(CreateAndroidSurfaceKHR); SURFACE(DestroySurfaceKHR); SURFACE(GetPhysicalDeviceSurfaceSupportKHR);
    SURFACE(GetPhysicalDeviceSurfaceCapabilitiesKHR); SURFACE(GetPhysicalDeviceSurfaceFormatsKHR);
#undef SURFACE
#define INSTANCE(name) PFN_vk##name name = (PFN_vk##name)get(gpu->instance, "vk" #name); if (!name) goto fail
    INSTANCE(EnumeratePhysicalDevices); INSTANCE(EnumerateDeviceExtensionProperties);
    INSTANCE(GetPhysicalDeviceMemoryProperties); INSTANCE(GetPhysicalDeviceQueueFamilyProperties);
    INSTANCE(GetPhysicalDeviceProperties); INSTANCE(GetPhysicalDeviceExternalSemaphoreProperties);
    INSTANCE(CreateDevice); INSTANCE(GetDeviceProcAddr);
#undef INSTANCE
    uint32_t count = 16;
    VkPhysicalDevice physical[16];
    VkResult enumerated = EnumeratePhysicalDevices(gpu->instance, &count, physical);
    if (enumerated != VK_SUCCESS && enumerated != VK_INCOMPLETE) goto fail;
    const char *required[] = {VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
        VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME, VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME,
        VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    for (unsigned i = 0; i < count && !gpu->device; ++i) {
        VkPhysicalDeviceProperties properties;
        GetPhysicalDeviceProperties(physical[i], &properties);
        if (properties.apiVersion < VK_API_VERSION_1_1) continue;
        uint32_t extensions_count = 0;
        if (EnumerateDeviceExtensionProperties(physical[i], NULL, &extensions_count, NULL)) continue;
        VkExtensionProperties *extensions = calloc(extensions_count, sizeof(*extensions));
        if (!extensions) continue;
        bool supported = EnumerateDeviceExtensionProperties(physical[i], NULL, &extensions_count, extensions) == VK_SUCCESS;
        for (unsigned e = 0; e < sizeof(required) / sizeof(*required); ++e)
            supported &= has_extension(extensions, extensions_count, required[e]);
        free(extensions);
        if (!supported) continue;
        VkPhysicalDeviceExternalSemaphoreInfo sync = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_SEMAPHORE_INFO,
            .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT};
        VkExternalSemaphoreProperties support = {.sType = VK_STRUCTURE_TYPE_EXTERNAL_SEMAPHORE_PROPERTIES};
        GetPhysicalDeviceExternalSemaphoreProperties(physical[i], &sync, &support);
        if ((support.externalSemaphoreFeatures & (VK_EXTERNAL_SEMAPHORE_FEATURE_IMPORTABLE_BIT | VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT)) !=
            (VK_EXTERNAL_SEMAPHORE_FEATURE_IMPORTABLE_BIT | VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT)) continue;
        VkQueueFamilyProperties queues[32]; uint32_t queues_count = 32;
        GetPhysicalDeviceQueueFamilyProperties(physical[i], &queues_count, queues);
        unsigned family = 0;
        while (family < queues_count && !(queues[family].queueFlags & VK_QUEUE_GRAPHICS_BIT)) ++family;
        if (family == queues_count) continue;
        float priority = 1;
        VkDeviceQueueCreateInfo queue = {.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
            .queueFamilyIndex = family, .queueCount = 1, .pQueuePriorities = &priority};
        VkDeviceCreateInfo info = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, .queueCreateInfoCount = 1,
            .pQueueCreateInfos = &queue, .enabledExtensionCount = sizeof(required) / sizeof(*required), .ppEnabledExtensionNames = required};
        if (CreateDevice(physical[i], &info, NULL, &gpu->device)) continue;
        gpu->family = family;
        gpu->physical = physical[i];
        GetPhysicalDeviceMemoryProperties(physical[i], &gpu->memory);
        snprintf(device->name, sizeof(device->name), "Vulkan: %.240s", properties.deviceName);
    }
    if (!gpu->device) goto fail;
#define LOAD(name) gpu->name = (PFN_vk##name)GetDeviceProcAddr(gpu->device, "vk" #name); if (!gpu->name) goto fail;
    DEVICE_FUNCTIONS(LOAD)
#undef LOAD
    gpu->GetDeviceQueue(gpu->device, gpu->family, 0, &gpu->queue);
    VkCommandPoolCreateInfo pool = {.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, .queueFamilyIndex = gpu->family};
    if (gpu->CreateCommandPool(gpu->device, &pool, NULL, &gpu->pool) || !pipeline(gpu)) goto fail;
    static const uint8_t white[] = {255, 255, 255, 255};
    gpu->white = mdg_image_cpu(device, 1, 1, 4, MDG_RGBA, white);
    if (!gpu->white || !gpu->white->gpu) goto fail;
    return true;
fail:
    mdg_vk_destroy(device);
    strcpy(device->name, "software");
    return false;
}

void mdg_vk_destroy(MdgDevice *device) {
    Gpu *gpu = device->gpu;
    if (!gpu) return;
    mdg_image_unref(gpu->white);
    for (unsigned i = 0; i < 2; ++i) {
        for (unsigned format = 0; format < 2; ++format) {
            if (gpu->pipelines[format][i]) gpu->DestroyPipeline(gpu->device, gpu->pipelines[format][i], NULL);
            if (gpu->render[format][i]) gpu->DestroyRenderPass(gpu->device, gpu->render[format][i], NULL);
        }
        if (gpu->samplers[i]) gpu->DestroySampler(gpu->device, gpu->samplers[i], NULL);
    }
    if (gpu->layout) gpu->DestroyPipelineLayout(gpu->device, gpu->layout, NULL);
    if (gpu->descriptors) gpu->DestroyDescriptorSetLayout(gpu->device, gpu->descriptors, NULL);
    if (gpu->pool) gpu->DestroyCommandPool(gpu->device, gpu->pool, NULL);
    if (gpu->device && gpu->DestroyDevice) gpu->DestroyDevice(gpu->device, NULL);
    if (gpu->instance && gpu->DestroyInstance) gpu->DestroyInstance(gpu->instance, NULL);
    if (gpu->library) dlclose(gpu->library);
    free(gpu);
    device->gpu = NULL;
}

bool mdg_vk_ready(const MdgDevice *device) { return device->gpu && !((Gpu *)device->gpu)->lost; }

bool mdg_vk_image(MdgImage *image) {
    Gpu *gpu = image->device->gpu;
    if (!gpu || gpu->lost) return false;
    Image *native = calloc(1, sizeof(*native));
    if (!native) return false;
    image->gpu = native;
    VkFormat format = image->format == MDG_BGRA || image->format == MDG_BGRX ? VK_FORMAT_B8G8R8A8_UNORM : VK_FORMAT_R8G8B8A8_UNORM;
    VkExternalMemoryImageCreateInfo external = {.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
        .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID};
    VkAndroidHardwareBufferFormatPropertiesANDROID ahb_format = {.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
    VkAndroidHardwareBufferPropertiesANDROID ahb = {.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID, .pNext = &ahb_format};
    if (image->hardware) {
        if (gpu->GetAndroidHardwareBufferPropertiesANDROID(gpu->device, image->hardware, &ahb) || !ahb.memoryTypeBits ||
            (ahb_format.format != VK_FORMAT_R8G8B8A8_UNORM && ahb_format.format != VK_FORMAT_B8G8R8A8_UNORM)) goto fail;
        format = ahb_format.format;
        AHardwareBuffer_Desc desc; AHardwareBuffer_describe(image->hardware, &desc);
        native->attachment = (desc.usage & AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT) != 0;
    }
    native->format_index = format == VK_FORMAT_B8G8R8A8_UNORM;
    native->initialized = image->external;
    VkImageCreateInfo info = {.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .pNext = image->hardware ? &external : NULL,
        .imageType = VK_IMAGE_TYPE_2D, .format = format, .extent = {image->width, image->height, 1}, .mipLevels = 1,
        .arrayLayers = 1, .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | (native->attachment ? VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT : 0)};
    if (gpu->CreateImage(gpu->device, &info, NULL, &native->image)) goto fail;
    VkMemoryRequirements requirements = {0};
    if (!image->hardware) gpu->GetImageMemoryRequirements(gpu->device, native->image, &requirements);
    VkImportAndroidHardwareBufferInfoANDROID import = {.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID, .buffer = image->hardware};
    VkMemoryDedicatedAllocateInfo dedicated = {.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO, .pNext = &import, .image = native->image};
    uint32_t type = image->hardware ? __builtin_ctz(ahb.memoryTypeBits) : memory_type(gpu, requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (type == UINT32_MAX) goto fail;
    VkMemoryAllocateInfo allocate = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .pNext = image->hardware ? &dedicated : NULL,
        .allocationSize = image->hardware ? ahb.allocationSize : requirements.size, .memoryTypeIndex = type};
    if (gpu->AllocateMemory(gpu->device, &allocate, NULL, &native->memory) || gpu->BindImageMemory(gpu->device, native->image, native->memory, 0)) goto fail;
    VkImageViewCreateInfo view = {.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, .image = native->image, .viewType = VK_IMAGE_VIEW_TYPE_2D,
        .format = format, .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
    if (gpu->CreateImageView(gpu->device, &view, NULL, &native->view)) goto fail;
    if (native->attachment) {
        VkFramebufferCreateInfo framebuffer = {.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
            .renderPass = gpu->render[native->format_index][0], .attachmentCount = 1, .pAttachments = &native->view,
            .width = image->width, .height = image->height, .layers = 1};
        if (gpu->CreateFramebuffer(gpu->device, &framebuffer, NULL, &native->framebuffer)) goto fail;
    }
    return true;
fail:
    mdg_vk_image_destroy(image);
    return false;
}

void mdg_vk_image_destroy(MdgImage *image) {
    Image *native = image->gpu;
    if (!native) return;
    Gpu *gpu = image->device->gpu;
    if (native->framebuffer) gpu->DestroyFramebuffer(gpu->device, native->framebuffer, NULL);
    if (native->view) gpu->DestroyImageView(gpu->device, native->view, NULL);
    if (native->image && !native->swapchain) gpu->DestroyImage(gpu->device, native->image, NULL);
    if (native->memory) gpu->FreeMemory(gpu->device, native->memory, NULL);
    free(native); image->gpu = NULL;
}

static bool pass_create(MdgPass *pass) {
    Gpu *gpu = pass->device->gpu;
    Pass *native = calloc(1, sizeof(*native));
    if (!native) return false;
    pass->gpu = native;
    VkCommandBufferAllocateInfo command = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = gpu->pool, .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount = 1};
    VkFenceCreateInfo fence = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    VkExportSemaphoreCreateInfo export = {.sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO,
        .handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT};
    VkSemaphoreCreateInfo semaphore = {.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO, .pNext = &export};
    VkDescriptorPoolSize size = {.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, .descriptorCount = MDG_MAX_DRAWS};
    VkDescriptorPoolCreateInfo pool = {.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        .maxSets = MDG_MAX_DRAWS, .poolSizeCount = 1, .pPoolSizes = &size};
    if (gpu->AllocateCommandBuffers(gpu->device, &command, &native->command) ||
        gpu->CreateFence(gpu->device, &fence, NULL, &native->fence) ||
        gpu->CreateSemaphore(gpu->device, &semaphore, NULL, &native->ready) ||
        gpu->CreateDescriptorPool(gpu->device, &pool, NULL, &native->descriptors)) return false;
    VkDescriptorSetLayout layouts[MDG_MAX_DRAWS];
    for (unsigned i = 0; i < MDG_MAX_DRAWS; ++i) layouts[i] = gpu->descriptors;
    VkDescriptorSetAllocateInfo allocate = {.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .descriptorPool = native->descriptors, .descriptorSetCount = MDG_MAX_DRAWS, .pSetLayouts = layouts};
    return gpu->AllocateDescriptorSets(gpu->device, &allocate, native->sets) == VK_SUCCESS;
}

static void upload_destroy(Gpu *gpu, Pass *pass) {
    if (pass->mapped) gpu->UnmapMemory(gpu->device, pass->memory);
    if (pass->upload) gpu->DestroyBuffer(gpu->device, pass->upload, NULL);
    if (pass->memory) gpu->FreeMemory(gpu->device, pass->memory, NULL);
    pass->mapped = NULL; pass->upload = VK_NULL_HANDLE; pass->memory = VK_NULL_HANDLE; pass->capacity = 0;
}

static bool upload_create(Gpu *gpu, Pass *pass, VkDeviceSize size) {
    if (pass->capacity >= size) return true;
    if (size > 128u * 1024u * 1024u) return false;
    upload_destroy(gpu, pass);
    size = (size + 65535) & ~(VkDeviceSize)65535;
    VkBufferCreateInfo buffer = {.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .size = size, .usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT};
    if (gpu->CreateBuffer(gpu->device, &buffer, NULL, &pass->upload)) return false;
    VkMemoryRequirements requirements;
    gpu->GetBufferMemoryRequirements(gpu->device, pass->upload, &requirements);
    uint32_t type = memory_type(gpu, requirements.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (type == UINT32_MAX) return false;
    VkMemoryAllocateInfo allocate = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = requirements.size, .memoryTypeIndex = type};
    if (gpu->AllocateMemory(gpu->device, &allocate, NULL, &pass->memory) ||
        gpu->BindBufferMemory(gpu->device, pass->upload, pass->memory, 0) ||
        gpu->MapMemory(gpu->device, pass->memory, 0, size, 0, &pass->mapped)) return false;
    pass->capacity = size;
    return true;
}

static void barrier(Gpu *gpu, Pass *pass, MdgImage *image, VkImageLayout from, VkImageLayout to,
        VkAccessFlags src, VkAccessFlags dst, bool acquire, bool release) {
    Image *native = image->gpu;
    VkImageMemoryBarrier barrier = {.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, .srcAccessMask = src, .dstAccessMask = dst,
        .oldLayout = from, .newLayout = to, .srcQueueFamilyIndex = image->hardware && acquire ? VK_QUEUE_FAMILY_FOREIGN_EXT : gpu->family,
        .dstQueueFamilyIndex = image->hardware && release ? VK_QUEUE_FAMILY_FOREIGN_EXT : gpu->family,
        .image = native->image, .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
    gpu->CmdPipelineBarrier(pass->command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        0, 0, NULL, 0, NULL, 1, &barrier);
}

bool mdg_vk_submit(MdgPass *pass) {
    Gpu *gpu = pass->device->gpu;
    Image *target = pass->target->gpu;
    if (gpu->lost || !target || !target->attachment) return false;
    MdgImage *images[MDG_MAX_DRAWS + 1];
    unsigned image_count = 0;
    VkDeviceSize upload_size = 0;
    for (unsigned i = 0; i < pass->count; ++i) {
        MdgImage *image = pass->commands[i].solid ? gpu->white : pass->commands[i].draw.image;
        if (!image->gpu) return false;
        unsigned j = 0;
        while (j < image_count && images[j] != image) ++j;
        if (j != image_count) continue;
        images[image_count++] = image;
        if (!image->hardware) upload_size += (VkDeviceSize)image->width * image->height * 4;
    }
    if (!pass->gpu && !pass_create(pass)) { mdg_vk_pass_destroy(pass); return false; }
    Pass *native = pass->gpu;
    if (!upload_create(gpu, native, upload_size)) return false;
    if (gpu->ResetCommandBuffer(native->command, 0) || gpu->ResetFences(gpu->device, 1, &native->fence)) return false;
    VkCommandBufferBeginInfo begin = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
    if (gpu->BeginCommandBuffer(native->command, &begin)) return false;
    VkDeviceSize offset = 0;
    for (unsigned i = 0; i < image_count; ++i) {
        MdgImage *image = images[i]; Image *source = image->gpu;
        if (image->hardware) {
            barrier(gpu, native, image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                0, VK_ACCESS_SHADER_READ_BIT, true, false);
        } else {
            for (unsigned y = 0; y < image->height; ++y)
                memcpy((uint8_t *)native->mapped + offset + (size_t)y * image->width * 4,
                    (const uint8_t *)image->pixels + y * image->stride, (size_t)image->width * 4);
            barrier(gpu, native, image, source->initialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_ACCESS_MEMORY_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, false, false);
            VkBufferImageCopy copy = {.bufferOffset = offset, .imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
                .imageExtent = {image->width, image->height, 1}};
            gpu->CmdCopyBufferToImage(native->command, native->upload, source->image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
            barrier(gpu, native, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, false, false);
            offset += (VkDeviceSize)image->width * image->height * 4;
        }
    }
    barrier(gpu, native, pass->target, pass->preserve && target->initialized ?
        (target->swapchain ? VK_IMAGE_LAYOUT_PRESENT_SRC_KHR : VK_IMAGE_LAYOUT_GENERAL) : VK_IMAGE_LAYOUT_UNDEFINED,
        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, 0, VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, true, false);
    VkClearValue clear;
    memcpy(clear.color.float32, pass->clear, sizeof(pass->clear));
    VkRenderPassBeginInfo render = {.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
        .renderPass = gpu->render[target->format_index][pass->preserve], .framebuffer = target->framebuffer,
        .renderArea = {{0, 0}, {pass->target->width, pass->target->height}}, .clearValueCount = 1, .pClearValues = &clear};
    gpu->CmdBeginRenderPass(native->command, &render, VK_SUBPASS_CONTENTS_INLINE);
    VkViewport viewport = {.width = pass->target->width, .height = pass->target->height, .maxDepth = 1};
    gpu->CmdSetViewport(native->command, 0, 1, &viewport);
    for (unsigned i = 0; i < pass->count; ++i) {
        const MdgCommand *command = &pass->commands[i]; const MdgDraw *draw = &command->draw;
        int left = draw->clip.x > 0 ? draw->clip.x : 0, top = draw->clip.y > 0 ? draw->clip.y : 0;
        int64_t right = (int64_t)draw->clip.x + draw->clip.width, bottom = (int64_t)draw->clip.y + draw->clip.height;
        if (right > pass->target->width) right = pass->target->width;
        if (bottom > pass->target->height) bottom = pass->target->height;
        if (right <= left || bottom <= top) continue;
        VkRect2D clip = {{left, top}, {(uint32_t)(right - left), (uint32_t)(bottom - top)}};
        gpu->CmdSetScissor(native->command, 0, 1, &clip);
        gpu->CmdBindPipeline(native->command, VK_PIPELINE_BIND_POINT_GRAPHICS, gpu->pipelines[target->format_index][draw->blend]);
        float push[16] = {
            draw->destination.x * 2 / viewport.width - 1, draw->destination.y * 2 / viewport.height - 1,
            draw->destination.width * 2 / viewport.width, draw->destination.height * 2 / viewport.height,
            0, 0, 0, 0, command->color[0], command->color[1], command->color[2], command->color[3],
            command->solid, draw->swap_red_blue, draw->transform, 0};
        {
            MdgImage *image = command->solid ? gpu->white : draw->image; Image *source = image->gpu;
            push[4] = draw->source.x / image->width; push[5] = draw->source.y / image->height;
            push[6] = draw->source.width / image->width; push[7] = draw->source.height / image->height;
            push[15] = image->format == MDG_RGBX || image->format == MDG_BGRX;
            VkDescriptorImageInfo texture = {.sampler = gpu->samplers[draw->linear], .imageView = source->view,
                .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
            VkWriteDescriptorSet write = {.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = native->sets[i],
                .dstBinding = 0, .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, .pImageInfo = &texture};
            gpu->UpdateDescriptorSets(gpu->device, 1, &write, 0, NULL);
            gpu->CmdBindDescriptorSets(native->command, VK_PIPELINE_BIND_POINT_GRAPHICS, gpu->layout, 0, 1, &native->sets[i], 0, NULL);
        }
        gpu->CmdPushConstants(native->command, gpu->layout, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(push), push);
        gpu->CmdDraw(native->command, 4, 1, 0, 0);
    }
    gpu->CmdEndRenderPass(native->command);
    for (unsigned i = 0; i < image_count; ++i)
        barrier(gpu, native, images[i], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_ACCESS_SHADER_READ_BIT, 0, false, true);
    barrier(gpu, native, pass->target, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
        target->swapchain ? VK_IMAGE_LAYOUT_PRESENT_SRC_KHR : VK_IMAGE_LAYOUT_GENERAL,
        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, 0, false, true);
    if (gpu->EndCommandBuffer(native->command)) return false;
    images[image_count++] = pass->target;
    unsigned wait_count = 0;
    VkPipelineStageFlags stages[MDG_MAX_DRAWS + 1];
    for (unsigned i = 0; i < image_count; ++i) {
        if (images[i]->fence < 0) continue;
        if (wait_count == native->waits) {
            VkSemaphoreCreateInfo info = {.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
            if (gpu->CreateSemaphore(gpu->device, &info, NULL, &native->wait[wait_count])) return false;
            ++native->waits;
        }
        int fd = -1;
        if (!mdg_image_fence(images[i], &fd)) return false;
        VkImportSemaphoreFdInfoKHR import = {.sType = VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR,
            .semaphore = native->wait[wait_count], .flags = VK_SEMAPHORE_IMPORT_TEMPORARY_BIT,
            .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT, .fd = fd};
        if (gpu->ImportSemaphoreFdKHR(gpu->device, &import)) { close(fd); return false; }
        stages[wait_count++] = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    }
    VkSubmitInfo submit = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .waitSemaphoreCount = wait_count,
        .pWaitSemaphores = native->wait, .pWaitDstStageMask = stages, .commandBufferCount = 1, .pCommandBuffers = &native->command,
        .signalSemaphoreCount = 1, .pSignalSemaphores = &native->ready};
    if (gpu->QueueSubmit(gpu->queue, 1, &submit, native->fence)) { gpu->lost = true; return false; }
    pass->submitted = true;
    for (unsigned i = 0; i < image_count; ++i) ((Image *)images[i]->gpu)->initialized = true;
    VkSemaphoreGetFdInfoKHR export = {.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR,
        .semaphore = native->ready, .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT};
    int fd = -1;
    if (gpu->GetSemaphoreFdKHR(gpu->device, &export, &fd)) { gpu->lost = true; return false; }
    mdg_image_set_fence(pass->target, fd);
    if (!mdg_image_fence(pass->target, &pass->completion)) { gpu->lost = true; return false; }
    return true;
}

bool mdg_vk_complete(MdgPass *pass) {
    Gpu *gpu = pass->device->gpu;
    Pass *native = pass->gpu;
    if (!native) return true;
    VkResult status = gpu->GetFenceStatus(gpu->device, native->fence);
    if (status == VK_ERROR_DEVICE_LOST) gpu->lost = true;
    return status == VK_SUCCESS || status == VK_ERROR_DEVICE_LOST;
}

void mdg_vk_pass_destroy(MdgPass *pass) {
    Pass *native = pass->gpu;
    if (!native) return;
    Gpu *gpu = pass->device->gpu;
    if (pass->submitted) {
        // EVENT_WAIT: submitted GPU work owns this slot until its fence or device loss.
        // Teardown runs on the graphics owner, never under a UI/session mutex.
        gpu->WaitForFences(gpu->device, 1, &native->fence, true, UINT64_MAX);
    }
    upload_destroy(gpu, native);
    if (native->descriptors) gpu->DestroyDescriptorPool(gpu->device, native->descriptors, NULL);
    for (unsigned i = 0; i < native->waits; ++i) gpu->DestroySemaphore(gpu->device, native->wait[i], NULL);
    if (native->ready) gpu->DestroySemaphore(gpu->device, native->ready, NULL);
    if (native->fence) gpu->DestroyFence(gpu->device, native->fence, NULL);
    free(native); pass->gpu = NULL;
}
