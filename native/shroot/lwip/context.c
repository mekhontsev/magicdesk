#define _GNU_SOURCE
#include "lwip_context.h"
#include "lwip/init.h"
#include "lwip/ip_addr.h"
#include <mbedtls/sha256.h>
#include <assert.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* Bionic's ARM64 executable TLS ABI reserves eight slots before our data. */
_Alignas(64) _Thread_local struct md_lwip_context *md_lwip_current;
struct md_lwip_allocation {
    struct md_lwip_allocation *next, **previous;
    struct md_lwip_context *owner;
    size_t size;
    _Alignas(max_align_t) unsigned char data[];
};
void *md_lwip_malloc(size_t size) {
    struct md_lwip_context *c=md_lwip_current;
    assert(c);
    if (size>MD_LWIP_MEMORY_LIMIT-sizeof(struct md_lwip_allocation)
            || sizeof(struct md_lwip_allocation)+size>MD_LWIP_MEMORY_LIMIT-c->bytes) return NULL;
    struct md_lwip_allocation *a=malloc(sizeof(*a)+size);
    if (!a) return NULL;
    *a=(struct md_lwip_allocation){.next=c->allocations,.previous=&c->allocations,.owner=c,.size=size};
    if (a->next) a->next->previous=&a->next;
    c->allocations=a; c->bytes+=sizeof(*a)+size;
    return a->data;
}
void *md_lwip_calloc(size_t count,size_t size) {
    if (size && count>SIZE_MAX/size) return NULL;
    void *p=md_lwip_malloc(count*size);
    if (p) memset(p,0,count*size);
    return p;
}
void md_lwip_free(void *data) {
    if (!data) return;
    struct md_lwip_allocation *a=(void *)((unsigned char *)data - offsetof(struct md_lwip_allocation,data));
    assert(a->owner==md_lwip_current);
    *a->previous=a->next;
    if (a->next) a->next->previous=a->previous;
    a->owner->bytes-=sizeof(*a)+a->size; free(a);
}
u32_t sys_now(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC,&ts);
    return (u32_t)((uint64_t)ts.tv_sec*1000+(unsigned long)ts.tv_nsec/1000000);
}
u32_t md_lwip_sequence(const ip_addr_t *local,u16_t port,const ip_addr_t *remote,u16_t peer) {
    /* RFC 9293: a per-tuple secret offset plus a four-microsecond clock.
     * Protocol ticks stop while idle and cannot distinguish new incarnations. */
    struct {
        unsigned char key[32],local[16],remote[16];
        u16_t port,peer;
        unsigned char family;
    } __attribute__((packed)) input={.port=port,.peer=peer,.family=IP_GET_TYPE(local)};
    memcpy(input.key,md_lwip_current->sequence_key,sizeof(input.key));
    if(IP_IS_V6(local)) {
        memcpy(input.local,ip_2_ip6(local)->addr,16);
        memcpy(input.remote,ip_2_ip6(remote)->addr,16);
    } else {
        memcpy(input.local,&ip_2_ip4(local)->addr,4);
        memcpy(input.remote,&ip_2_ip4(remote)->addr,4);
    }
    unsigned char hash[32];
    if(mbedtls_sha256((const unsigned char *)&input,sizeof(input),hash,0)) abort();
    u32_t offset; memcpy(&offset,hash,sizeof(offset));
    struct timespec ts; if(clock_gettime(CLOCK_MONOTONIC,&ts)) abort();
    return offset+(u32_t)((uint64_t)ts.tv_sec*250000+(unsigned long)ts.tv_nsec/4000);
}
#define MD_LWIP_DECLARE(name,id) size_t md_lwip_##name##_size(void); void md_lwip_##name##_init(void);
MD_LWIP_MODULES(MD_LWIP_DECLARE)
#undef MD_LWIP_DECLARE
struct md_lwip_context *md_lwip_context_new(void) {
    struct md_lwip_context *c=calloc(1,sizeof(*c));
    if (!c) return NULL;
    arc4random_buf(c->sequence_key,sizeof(c->sequence_key));
#define MD_LWIP_ALLOCATE(name,id) \
    c->modules[MD_LWIP_##id]=calloc(1,md_lwip_##name##_size()); \
    if (!c->modules[MD_LWIP_##id]) { md_lwip_context_free(c); return NULL; }
    MD_LWIP_MODULES(MD_LWIP_ALLOCATE)
#undef MD_LWIP_ALLOCATE
    struct md_lwip_context *previous=md_lwip_enter(c);
#define MD_LWIP_INITIALIZE(name,id) md_lwip_##name##_init();
    MD_LWIP_MODULES(MD_LWIP_INITIALIZE)
#undef MD_LWIP_INITIALIZE
    lwip_init();
    md_lwip_leave(previous); return c;
}
void md_lwip_context_free(struct md_lwip_context *c) {
    if (!c) return;
    /* Namespace retirement has already detached callbacks and packet sources.
     * Reclaim timers, incomplete fragments and retransmissions together. */
    while (c->allocations) {
        struct md_lwip_allocation *a=c->allocations;
        c->allocations=a->next; free(a);
    }
    for (unsigned i=0;i<MD_LWIP_MODULE_COUNT;++i) free(c->modules[i]);
    free(c);
}
