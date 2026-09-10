#include <assert.h>
#include <errno.h>
#include <linux/uinput.h>
#include <stdarg.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

static int test_ioctl(int fd, unsigned long request, ...);
static ssize_t test_write(int fd, const void *data, size_t size);
#define ioctl test_ioctl
#define write test_write
#define main mouse_helper_main
#include "../magicdesk_uinput_bridge.c"
#undef main
#undef write
#undef ioctl

static unsigned int kernel_version;
static unsigned long failed_request;
static int failure_errno;
static int setup_calls;
static int descriptor_writes;
static int create_calls;
static bool short_write;

static void check_identity(const struct input_id *id, const char *name) {
    assert(id->bustype == BUS_VIRTUAL);
    assert(id->vendor == MAGICDESK_VENDOR_ID);
    assert(id->product == MAGICDESK_MOUSE_PRODUCT_ID);
    assert(id->version == 1);
    assert(strcmp(name, "MagicDesk Mouse") == 0);
}

static int test_ioctl(int fd, unsigned long request, ...) {
    assert(fd == 99);
    if (request == failed_request) {
        errno = failure_errno;
        return -1;
    }
    va_list args;
    va_start(args, request);
    if (request == UI_GET_VERSION) {
        *va_arg(args, unsigned int *) = kernel_version;
    } else if (request == UI_DEV_SETUP) {
        assert(kernel_version >= 5);
        const struct uinput_setup *setup = va_arg(args, struct uinput_setup *);
        check_identity(&setup->id, setup->name);
        assert(setup->ff_effects_max == 0);
        setup_calls++;
    } else if (request == UI_DEV_CREATE) {
        assert(setup_calls + descriptor_writes == 1);
        create_calls++;
    }
    va_end(args);
    return 0;
}

static ssize_t test_write(int fd, const void *data, size_t size) {
    assert(fd == 99);
    assert(kernel_version < 5);
    assert(size == sizeof(struct uinput_user_dev));
    const struct uinput_user_dev *device = data;
    check_identity(&device->id, device->name);
    assert(device->ff_effects_max == 0);
    for (int axis = 0; axis < ABS_CNT; axis++) {
        assert(device->absmin[axis] == 0 && device->absmax[axis] == 0);
        assert(device->absfuzz[axis] == 0 && device->absflat[axis] == 0);
    }
    descriptor_writes++;
    return short_write ? (ssize_t)size - 1 : (ssize_t)size;
}

static void reset(unsigned int version) {
    kernel_version = version;
    failed_request = 0;
    failure_errno = 0;
    setup_calls = descriptor_writes = create_calls = 0;
    short_write = false;
}

int main(void) {
    const char *stage;
    reset(4);
    assert(create_virtual_mouse(99, &stage) == 0);
    assert(descriptor_writes == 1 && setup_calls == 0 && create_calls == 1);

    reset(5);
    assert(create_virtual_mouse(99, &stage) == 0);
    assert(descriptor_writes == 0 && setup_calls == 1 && create_calls == 1);

    reset(5);
    failed_request = UI_DEV_SETUP;
    failure_errno = EINVAL;
    assert(create_virtual_mouse(99, &stage) == -1 && errno == EINVAL);
    assert(strcmp(stage, "setup") == 0);
    assert(descriptor_writes == 0 && create_calls == 0);

    reset(4);
    failed_request = UI_GET_VERSION;
    failure_errno = EACCES;
    assert(create_virtual_mouse(99, &stage) == -1 && errno == EACCES);
    assert(descriptor_writes == 0 && setup_calls == 0 && create_calls == 0);

    reset(4);
    short_write = true;
    assert(create_virtual_mouse(99, &stage) == -1 && errno == EIO);
    assert(create_calls == 0);

    reset(5);
    failed_request = UI_DEV_CREATE;
    failure_errno = ENOMEM;
    assert(create_virtual_mouse(99, &stage) == -1 && errno == ENOMEM);
    assert(strcmp(stage, "create") == 0);
    assert(descriptor_writes == 0 && setup_calls == 1);
    puts("Virtual mouse: uinput version negotiation and setup failures verified");
    return 0;
}
