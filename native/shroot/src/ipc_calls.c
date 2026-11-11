#include "sysv_ipc.h"
#include "interception.h"
#include "adapter_buffer.h"
#include "resource_control.h"
#include "raw.h"
#include <errno.h>

long md_ipc_dispatch(long nr, const unsigned long *args) {
    struct md_adapter_input input=md_adapter_buffer_begin(MD_BUFFER_IPC_PREPARE,0);
    if (!input.buffer) {
        md_guest_resource_control(MD_GUEST_IPC,MD_IPC_CLOSE,1,0,0,0);
        return input.value;
    }
    long result=input.value;
    if (!result) {
        struct md_ipc_packet *packet=input.buffer;
        struct md_ipc_packet header=*packet;
        if (header.length<sizeof(header) || header.length>sizeof(header)+MD_IPC_VALUE_MAX
                || header.input_size>header.length-sizeof(header)
                || header.timeout_offset>header.input_size
                || (header.timeout_offset && header.input_size-header.timeout_offset!=sizeof(struct timespec)))
            result=-EPROTO;
        uintptr_t address=nr==SYS_semctl ? args[3] : nr==SYS_msgctl ? args[2] : args[1];
        size_t first=header.timeout_offset ? header.timeout_offset : header.input_size;
        if (!result && first) result=md_read_memory(packet->data,(void *)address,first);
        if (!result && header.timeout_offset)
            result=md_read_memory(packet->data+first,(void *)args[3],sizeof(struct timespec));
        if (!result) result=md_guest_resource_control(MD_GUEST_IPC,MD_IPC_EXECUTE,0,0,0,0);
        if (result>=0 && packet->output_size) {
            long copy=packet->output_size>header.length-sizeof(header) ? -EPROTO
                : md_write_memory((void *)address,packet->data,packet->output_size);
            if (copy) result=copy;
        }
    }
    long close=md_guest_resource_control(MD_GUEST_IPC,MD_IPC_CLOSE,result!=MD_IPC_WAIT,0,0,0);
    if (close<0) result=close;
    long released=md_adapter_buffer_release();
    return released<0 ? released : result;
}
