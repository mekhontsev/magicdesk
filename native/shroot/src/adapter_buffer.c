#include "adapter_frame.h"
#include "interception.h"
#include "raw.h"
#include "seccomp_capture.h"
#include <errno.h>
#include <sys/mman.h>

static int enabled, readonly_enabled;
int md_adapter_buffer_ready(void) { return enabled; }
int md_adapter_buffer_install(void) {
    if (enabled) return 0;
    size_t stride=(sizeof(union md_adapter_frame)+md_page_size-1)/md_page_size*md_page_size;
    size_t size=stride*(MD_ADAPTER_BUFFER_SLOTS+MD_ADAPTER_CONTROL_SLOTS+MD_ADAPTER_CHANNEL_SLOTS);
    long fd=RAW2(prctl,MD_GUEST_BUFFER,size);
    if (fd<0) return (int)fd;
    long base=RAW6(mmap,NULL,size,PROT_READ|PROT_WRITE,MAP_SHARED,fd,0);
    long closed=RAW1(close,fd);
    if (base<0 && base>=-4095) return (int)base;
    long result=closed<0 ? closed : md_guest_buffer_control(MD_BUFFER_MAPPED,base,size);
    if (result<0) RAW2(munmap,base,size);
    else enabled=1;
    return (int)result;
}
int md_adapter_readonly_install(void) {
    if (readonly_enabled) return 0;
    size_t stride=(sizeof(struct md_seccomp_image)+md_page_size-1)/md_page_size*md_page_size;
    size_t size=stride*MD_SECCOMP_IMAGE_SLOTS;
    long fd=RAW3(prctl,MD_GUEST_BUFFER,size,1);
    if (fd==-EMFILE) {
        long result=md_guest_buffer_control(MD_BUFFER_READONLY_INSTALL,0,0);
        if (!result) readonly_enabled=1;
        return (int)result;
    }
    if (fd<0) return (int)fd;
    long base=RAW6(mmap,NULL,size,PROT_READ,MAP_SHARED,fd,0);
    long closed=RAW1(close,fd);
    if (base<0 && base>=-4095) return (int)base;
    long result=closed<0 ? closed : md_guest_buffer_control(MD_BUFFER_READONLY_MAPPED,base,size);
    if (result<0) RAW2(munmap,base,size);
    else readonly_enabled=1;
    return (int)result;
}
long md_adapter_buffer_take(void) {
    return enabled ? md_guest_buffer_control(MD_BUFFER_TAKE,0,0) : -ENOTSUP;
}
struct md_adapter_input md_adapter_buffer_begin(unsigned command,unsigned long argument) {
    return enabled ? md_guest_buffer_start(command,argument,0)
        : (struct md_adapter_input){.value=-ENOTSUP};
}
long md_adapter_buffer_release(void) {
    return md_guest_buffer_control(MD_BUFFER_RELEASE,0,0);
}
long md_adapter_buffer_finish(long copied,long result) {
    return md_guest_buffer_control(MD_BUFFER_FINISH,(unsigned long)copied,(unsigned long)result);
}
struct md_adapter_input md_adapter_buffer_start(size_t length) {
    return enabled ? md_guest_buffer_start(MD_BUFFER_PROC_START,length,0)
        : (struct md_adapter_input){.value=-ENOTSUP};
}
