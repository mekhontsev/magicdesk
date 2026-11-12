#include "seccomp_capture.h"
#include "seccomp_install.h"
#include <errno.h>
#include <stddef.h>

void md_seccomp_capture_discard(struct md_seccomp_capture *c) {
    if (!c) return;
    md_seccomp_policy_release(c->policy);
    md_buffer_release(&c->buffer);
    *c=(struct md_seccomp_capture){0};
}
int md_seccomp_capture_prepare(struct md_seccomp_capture *c,struct md_interception_buffers *pool,
        uintptr_t peer_base,struct md_seccomp_policy *parent,const struct sock_fprog *local,
        const uint32_t *delegated,size_t count,uint16_t cookie,
        const struct md_seccomp_route *notification) {
    if (!c || !peer_base || !md_buffers_peer_readonly(pool)) return -EINVAL;
    if (c->policy || c->buffer.pool || c->program) return -EBUSY;
    struct md_seccomp_capture next={0};
    int error=md_buffers_take(pool,&next.buffer);
    struct md_buffer_span span;
    if (!error) error=md_buffer_span(&next.buffer,&span);
    if (!error && span.size<sizeof(struct md_seccomp_image)) error=-ENOSPC;
    if (!error && (span.offset>UINTPTR_MAX-peer_base
            || sizeof(struct md_seccomp_image)>UINTPTR_MAX-peer_base-span.offset)) error=-EOVERFLOW;
    if (!error) error=md_seccomp_policy_capture(parent,local,&next.policy);
    if (!error) {
        struct md_seccomp_image *image=span.data;
        int length=md_seccomp_policy_route(next.policy,delegated,count,cookie,image->code,BPF_MAXINSNS,notification);
        if (length<0) error=length;
        else {
            if (notification && notification->entry_image)
                error=md_seccomp_policy_bind_entry(next.policy,notification->entry_image,
                    notification->gates,notification->internal_gate_count);
            next.program=peer_base+span.offset;
            image->program=(struct sock_fprog){.len=length,
                .filter=(void *)(next.program+offsetof(struct md_seccomp_image,code))};
        }
    }
    if (error) md_seccomp_capture_discard(&next);
    else *c=next;
    return error;
}
int md_seccomp_capture_complete(struct md_seccomp_capture *c,unsigned flags,long result,
        struct md_seccomp_policy **out) {
    if (!c || !c->policy || !c->buffer.pool || !out || *out) return -EINVAL;
    int published=md_seccomp_filter_committed(flags,result);
    if (published) { *out=c->policy; c->policy=NULL; }
    md_seccomp_capture_discard(c);
    return published;
}
