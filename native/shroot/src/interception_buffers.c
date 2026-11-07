#define _GNU_SOURCE
#include "interception_buffers.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

struct slot { uint64_t generation; unsigned references,extent; };
struct md_interception_buffers {
    void *memory;
    size_t size, stride, capacity, clear_prefix;
    unsigned slots, ordinary, channels, references, next, next_reserved, next_channel;
    uint64_t generation;
    int fd, peer_readonly;
    struct slot slot[];
};
static int create(unsigned slots, size_t capacity, size_t clear_prefix,
        unsigned reserved, int readonly, struct md_interception_buffers **out) {
    if (!out || !slots || !capacity || clear_prefix>capacity) return -EINVAL;
    if (reserved>UINT_MAX-slots) return -EOVERFLOW;
    unsigned ordinary=slots; slots+=reserved;
    size_t page=(size_t)getpagesize();
    if (capacity>SIZE_MAX-(page-1)) return -EOVERFLOW;
    size_t stride=(capacity+page-1)/page*page;
    size_t bytes;
    if (stride>PTRDIFF_MAX/slots
            || __builtin_mul_overflow((size_t)slots,sizeof(struct slot),&bytes)
            || __builtin_add_overflow(bytes,sizeof(struct md_interception_buffers),&bytes)) return -EOVERFLOW;
    struct md_interception_buffers *p=calloc(1,bytes);
    if (!p) return -ENOMEM;
    p->fd=-1; p->slots=slots; p->ordinary=ordinary; p->capacity=capacity; p->clear_prefix=clear_prefix;
    p->stride=stride; p->size=stride*slots; p->references=1; p->peer_readonly=readonly;
    p->fd=memfd_create("guest-request-buffers",MFD_CLOEXEC|MFD_ALLOW_SEALING);
    int error=0;
    int seals=F_SEAL_SHRINK|F_SEAL_GROW|F_SEAL_SEAL;
    if (p->fd<0 || ftruncate(p->fd,(off_t)p->size)
            || (!readonly && fcntl(p->fd,F_ADD_SEALS,seals))) error=-errno;
    else {
        void *memory=mmap(NULL,p->size,PROT_READ|PROT_WRITE,MAP_SHARED,p->fd,0);
        if (memory==MAP_FAILED) error=-errno;
        else p->memory=memory;
    }
    if (!error && readonly && fcntl(p->fd,F_ADD_SEALS,seals|F_SEAL_FUTURE_WRITE)) error=-errno;
    if (error) md_buffers_release(p);
    else *out=p;
    return error;
}
int md_buffers_create(unsigned slots,size_t capacity,size_t clear_prefix,
        unsigned reserved,struct md_interception_buffers **out) {
    return create(slots,capacity,clear_prefix,reserved,0,out);
}
int md_buffers_create_readonly(unsigned slots,size_t capacity,struct md_interception_buffers **out) {
    return create(slots,capacity,0,0,1,out);
}
int md_buffers_peer_readonly(const struct md_interception_buffers *p) { return p && p->peer_readonly; }
struct md_interception_buffers *md_buffers_retain(struct md_interception_buffers *p) {
    if (p) {
        if (p->references==UINT_MAX) return NULL;
        ++p->references;
    }
    return p;
}
void md_buffers_release(struct md_interception_buffers *p) {
    if (!p || --p->references) return;
    if (p->memory) munmap(p->memory,p->size);
    if (p->fd>=0) close(p->fd);
    free(p);
}
int md_buffers_fd(const struct md_interception_buffers *p) { return p ? p->fd : -1; }
size_t md_buffers_size(const struct md_interception_buffers *p) { return p ? p->size : 0; }
static int valid(const struct md_interception_buffer *b) {
    if (!b || !b->pool) return -EINVAL;
    const struct md_interception_buffers *p=b->pool;
    return b->index<p->slots && b->generation && p->slot[b->index].extent && p->slot[b->index].generation==b->generation
        && p->slot[b->index].references ? 0 : -ESTALE;
}
static int take(struct md_interception_buffers *p, struct md_interception_buffer *out, int reserved,size_t capacity) {
    if (!p || !out) return -EINVAL;
    if (out->pool) return -EBUSY;
    if (p->generation==UINT64_MAX || p->references==UINT_MAX) return -EOVERFLOW;
    unsigned base=reserved==2 ? p->ordinary-p->channels : reserved ? p->ordinary : 0;
    unsigned count=reserved==2 ? p->channels : reserved ? p->slots-p->ordinary : p->ordinary-p->channels;
    if (!count) return -ENOTSUP;
    if (capacity>(count-1)*p->stride+p->capacity) return -ENOMEM;
    unsigned extent=capacity>p->capacity ? (unsigned)((capacity-p->capacity-1)/p->stride+2) : 1;
    unsigned *next=reserved==2 ? &p->next_channel : reserved ? &p->next_reserved : &p->next;
    for (unsigned n=0;n<count;++n) {
        unsigned i=base+(unsigned)(((uint64_t)*next+n)%count);
        if (extent>base+count-i) continue;
        unsigned j=0; while (j<extent && !p->slot[i+j].references) ++j;
        if (j!=extent) continue;
        ++p->generation;
        for (j=0;j<extent;++j) p->slot[i+j]=(struct slot){.generation=p->generation,.references=1};
        p->slot[i].extent=extent;
        ++p->references; *next=(i-base+extent)%count;
        if (!reserved) memset((char *)p->memory+i*p->stride,0,p->clear_prefix);
        *out=(struct md_interception_buffer){.pool=p,.index=i,.generation=p->generation};
        return 0;
    }
    return -EAGAIN;
}
int md_buffers_take(struct md_interception_buffers *p, struct md_interception_buffer *out) {
    return take(p,out,0,0);
}
int md_buffers_take_sized(struct md_interception_buffers *p,size_t size,struct md_interception_buffer *out) {
    return take(p,out,0,size);
}
int md_buffers_take_reserved(struct md_interception_buffers *p, struct md_interception_buffer *out) {
    return take(p,out,1,0);
}
int md_buffers_partition_channels(struct md_interception_buffers *p,unsigned count) {
    if (!p || count>=p->ordinary) return -EINVAL;
    if (p->generation || p->channels) return -EBUSY;
    p->channels=count; return 0;
}
int md_buffers_take_channel(struct md_interception_buffers *p,struct md_interception_buffer *out) {
    return take(p,out,2,0);
}
int md_buffer_pin(const struct md_interception_buffer *b, struct md_interception_buffer *out) {
    if (!out) return -EINVAL;
    if (out->pool) return -EBUSY;
    int error=valid(b); if (error) return error;
    struct md_interception_buffers *p=b->pool;
    if (p->references==UINT_MAX || p->slot[b->index].references==UINT_MAX) return -EOVERFLOW;
    ++p->references; ++p->slot[b->index].references; *out=*b;
    return 0;
}
int md_buffer_span(const struct md_interception_buffer *b, struct md_buffer_span *out) {
    if (!out) return -EINVAL;
    int error=valid(b); if (error) return error;
    struct md_interception_buffers *p=b->pool;
    *out=(struct md_buffer_span){.data=(char *)p->memory+b->index*p->stride,
        .size=(p->slot[b->index].extent-1)*p->stride+p->capacity,.offset=b->index*p->stride};
    return 0;
}
int md_buffer_release(struct md_interception_buffer *b) {
    if (!b) return -EINVAL;
    if (!b->pool) return 0;
    int error=valid(b); if (error) return error;
    struct md_interception_buffers *p=b->pool;
    if (!--p->slot[b->index].references) {
        unsigned extent=p->slot[b->index].extent;
        for (unsigned i=1;i<extent;++i) p->slot[b->index+i].references=0;
    }
    *b=(struct md_interception_buffer){0};
    md_buffers_release(p);
    return 0;
}
