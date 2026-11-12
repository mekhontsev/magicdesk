#include "signal_calls.h"
#include "adapter_buffer.h"
#include "raw.h"
#include <errno.h>
#include <signal.h>

long md_signal_query_call(long number,const unsigned long *args) {
    int pending=number==SYS_rt_sigpending;
    size_t size=pending ? args[1] : args[3];
    void *output=(void *)args[pending ? 0 : 2];
    if (pending ? size>sizeof(uint64_t) : size!=sizeof(uint64_t) || args[1]) return -EINVAL;
    if (pending ? !size : !output) return 0;
    long memory=md_adapter_buffer_take();
    if (memory<0) return memory;
    struct md_signal_query *query=(void *)memory;
    long result=pending ? RAW2(rt_sigpending,&query->value,sizeof(query->value))
        : RAW4(rt_sigprocmask,0,0,&query->value,sizeof(query->value));
    if (!result) result=md_guest_buffer_control(MD_BUFFER_SIGNAL_QUERY,0,0);
    if (!result) result=md_write_memory(output,&query->value,size);
    long released=md_adapter_buffer_release();
    return result ? result : released;
}

long md_signal_queue_call(long number,const unsigned long *args) {
    long memory=md_adapter_buffer_take();
    if (memory<0) return memory;
    long result=md_read_memory((void *)memory,(void *)args[number==SYS_rt_tgsigqueueinfo ? 3 : 2],sizeof(siginfo_t));
    if (!result) result=md_guest_buffer_control(MD_BUFFER_SIGNAL_SEND,0,0);
    long released=md_adapter_buffer_release();
    return result ? result : released;
}
