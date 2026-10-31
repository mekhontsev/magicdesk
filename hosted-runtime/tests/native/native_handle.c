#include "graphics.h"
#include "fd_stream.h"
#include <android/hardware_buffer.h>
#include <assert.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <unistd.h>

/* VNDK capability probe only: these entry points are not public NDK contracts. */
struct NativeHandle { int version, fds, ints; int data[]; };
struct Packet { AHardwareBuffer_Desc description; int ints, fence; int values[256]; };
typedef const struct NativeHandle *(*GetHandle)(const AHardwareBuffer *);
typedef int (*ImportHandle)(const AHardwareBuffer_Desc *, const struct NativeHandle *, int, AHardwareBuffer **);

static void transfer(int socket, GetHandle get_handle, unsigned format) {
    AHardwareBuffer_Desc description = {.width = 32, .height = 16, .layers = 1, .format = format,
        .usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
            AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN};
    AHardwareBuffer *buffer;
    assert(AHardwareBuffer_allocate(&description, &buffer) == 0);
    MdgDevice *device = mdg_device_create(false);
    assert(device && mdg_device_gpu(device));
    MdgImage *image = mdg_image_hardware(device, buffer);
    assert(image);
    MdgPass *pass = mdg_pass_begin(device, image, (float[]){1,0,0,1}, false);
    assert(pass);
    assert(mdg_pass_rect(pass, (MdgBox){16,0,16,16}, (MdgClip){0,0,32,16}, (float[]){0,1,0,1}, false));
    int wait_fd;
    assert(mdg_pass_submit(pass, &wait_fd) == MDG_SUBMIT_OK && wait_fd == -1);
    int fence = -1;
    assert(mdg_image_fence(image, &fence));
    const struct NativeHandle *handle = get_handle(buffer);
    assert(handle && handle->version == sizeof(*handle) && handle->fds > 0 && handle->fds < MDH_STREAM_FDS);
    assert(handle->ints >= 0 && handle->ints <= 256);
    struct Packet packet = {.ints = handle->ints, .fence = fence >= 0};
    AHardwareBuffer_describe(buffer, &packet.description);
    memcpy(packet.values, handle->data + handle->fds, handle->ints * sizeof(int));
    MdhFdStream stream = {.used = sizeof(packet)};
    memcpy(stream.bytes, &packet, sizeof(packet));
    for (int i = 0; i < handle->fds; ++i) {
        int fd = dup(handle->data[i]);
        assert(fd >= 0);
        stream.fds[stream.count++] = fd;
    }
    if (fence >= 0) stream.fds[stream.count++] = fence;
    assert(mdh_stream_send(&stream, socket) == 0 && stream.used == 0);
    mdh_stream_clear(&stream);
    mdg_image_unref(image);
    AHardwareBuffer_release(buffer);
    mdg_device_destroy(device);
}

static void verify(ImportHandle import_handle, struct Packet *packet, MdhFdStream *stream, bool software) {
    int fds = stream->count - packet->fence;
    assert(fds > 0 && packet->ints >= 0 && packet->ints <= 256);
    struct NativeHandle *handle = calloc(1, sizeof(*handle) + (fds + packet->ints) * sizeof(int));
    assert(handle);
    *handle = (struct NativeHandle){sizeof(*handle), fds, packet->ints};
    memcpy(handle->data, stream->fds, fds * sizeof(int));
    memcpy(handle->data + fds, packet->values, packet->ints * sizeof(int));
    AHardwareBuffer *buffer = NULL;
    /* CLONE=3 owns its duplicated descriptors, not our received handle. */
    int result = import_handle(&packet->description, handle, 3, &buffer);
    free(handle);
    assert(result == 0 && buffer);
    MdgDevice *device = mdg_device_create(software);
    assert(device && (software || mdg_device_gpu(device)));
    MdgImage *image = mdg_image_hardware(device, buffer);
    assert(image);
    if (packet->fence) {
        int fd = dup(stream->fds[fds]);
        assert(fd >= 0);
        mdg_image_set_fence(image, fd);
    }
    mdh_stream_clear(stream);
    AHardwareBuffer_release(buffer);
    MdgImage *target = mdg_image_create(device, 64, 32);
    assert(target);
    MdgPass *pass = mdg_pass_begin(device, target, (float[]){0,0,0,0}, false);
    assert(pass && mdg_pass_draw(pass, &(MdgDraw){.image = image, .source = {0,0,32,16},
        .destination = {0,0,64,32}, .clip = {0,0,64,32}, .opacity = 1}));
    assert(mdg_pass_submit_and_wait(pass));
    mdg_image_unref(image);
    uint8_t pixels[64 * 32 * 4];
    assert(mdg_image_read(target, pixels, 64 * 4));
    for (unsigned y = 0; y < 32; ++y) for (unsigned x = 0; x < 64; ++x) {
        if (x == 31 || x == 32) continue; /* Bilinear boundary. */
        uint8_t *p = pixels + (y * 64 + x) * 4;
        assert(p[0] == (x < 32 ? 255 : 0) && p[1] == (x < 32 ? 0 : 255) && p[2] == 0 && p[3] == 255);
    }
    printf("native_handle format=%u renderer=%s pixels=passed producer_lifetime=passed fence=%d\n",
        packet->description.format, mdg_device_name(device), packet->fence);
    mdg_image_unref(target);
    mdg_device_destroy(device);
}

int main(int argc, char **argv) {
    void *library = dlopen("libnativewindow.so", RTLD_NOW | RTLD_LOCAL);
    GetHandle get_handle = library ? (GetHandle)dlsym(library, "AHardwareBuffer_getNativeHandle") : NULL;
    ImportHandle import_handle = library ? (ImportHandle)dlsym(library, "AHardwareBuffer_createFromHandle") : NULL;
    if (!get_handle || !import_handle) { puts("native_handle=unavailable"); return 77; }
    if (argc == 3) { transfer(atoi(argv[1]), get_handle, (unsigned)atoi(argv[2])); return 0; }
    const unsigned formats[] = {1, 2, 5};
    for (unsigned i = 0; i < sizeof(formats) / sizeof(*formats); ++i) for (unsigned software = 0; software < 2; ++software) {
        int sockets[2];
        assert(socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, sockets) == 0);
        pid_t child = fork();
        assert(child >= 0);
        if (!child) {
            close(sockets[0]);
            assert(fcntl(sockets[1], F_SETFD, 0) == 0);
            char fd[24], format[24];
            snprintf(fd, sizeof(fd), "%d", sockets[1]);
            snprintf(format, sizeof(format), "%u", formats[i]);
            execl(argv[0], argv[0], fd, format, NULL);
            _exit(127);
        }
        close(sockets[1]);
        struct pollfd event = {.fd = sockets[0], .events = POLLIN};
        /* EVENT_WAIT: bounded producer transfer; timeout fails rather than skipping. */
        assert(poll(&event, 1, 10000) == 1 && event.revents & POLLIN);
        MdhFdStream stream = {0};
        assert(mdh_stream_receive(&stream, sockets[0], NULL, NULL) == 0 && stream.used == sizeof(struct Packet));
        close(sockets[0]);
        int status;
        assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
        struct Packet packet;
        memcpy(&packet, stream.bytes, sizeof(packet));
        verify(import_handle, &packet, &stream, software);
        mdh_stream_clear(&stream);
    }
    dlclose(library);
    return 0;
}
