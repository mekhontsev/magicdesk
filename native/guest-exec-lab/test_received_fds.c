#define _GNU_SOURCE
#include "received_fds.h"
#include "fd_object.h"
#include "socket_control.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

static int budget=-1;
static unsigned allocations,destroyed;
void *__real_calloc(size_t,size_t);
void *__real_realloc(void *,size_t);
static int admitted(void) {
    ++allocations;
    if (!budget) { errno=ENOMEM; return 0; }
    if (budget>0) --budget;
    return 1;
}
void *__wrap_calloc(size_t n,size_t size) { return admitted() ? __real_calloc(n,size) : NULL; }
void *__wrap_realloc(void *p,size_t size) { return admitted() ? __real_realloc(p,size) : NULL; }
static void destroy(struct md_fd_object *o) { (void)o; ++destroyed; }
static const struct md_fd_object_type type={.destroy=destroy};
static struct md_received_fds *vector(unsigned count,struct md_fd_object *origin) {
    struct md_received_fds *p=NULL;
    assert(!md_received_fds_reserve(&p,count));
    for (unsigned i=0;i<count;++i) {
        int fd=open("/dev/null",O_RDONLY|O_CLOEXEC); assert(fd>=0);
        p->fds[p->count++]=fd;
    }
    if (origin) {
        struct md_fd_object *origins[count];
        for (unsigned i=0;i<count;++i) origins[i]=i%2 ? NULL : origin;
        assert(!md_received_fds_annotate(p,origins,count));
    }
    return p;
}
static void append(struct md_fd_object *origin) {
    struct md_received_fds *a=NULL;
    unsigned growth=0;
    for (unsigned i=0;i<253;++i) {
        struct md_received_fds *b=vector(1,origin);
        int fd=b->fds[0]; unsigned before=allocations;
        assert(!md_received_fds_join(&a,&b) && !b);
        growth+=allocations-before;
        assert(a->count==i+1 && a->fds[i]==fd && fcntl(fd,F_GETFD)>=0);
        assert(origin ? a->origins[i]==origin : !a->origins);
    }
    assert(growth<=14);
    assert(!origin || atomic_load(&origin->references)==254);
    int last=a->fds[252];
    md_received_fds_release(a);
    assert(fcntl(last,F_GETFD)==-1 && errno==EBADF);
    assert(!origin || atomic_load(&origin->references)==1);
}
static void rollback(struct md_fd_object *origin) {
    for (int fail=0;fail<2;++fail) {
        struct md_received_fds *a=vector(1,origin),*b=vector(4,origin),*old=a;
        struct md_fd_object **annotations=a->origins;
        unsigned references=atomic_load(&origin->references),capacity=a->capacity;
        int fd=a->fds[0],tail=b->fds[3];
        budget=fail;
        assert(md_received_fds_join(&a,&b)==-ENOMEM);
        budget=-1;
        assert(a==old && a->origins==annotations && a->capacity==capacity && a->count==1 && b->count==4);
        assert(a->fds[0]==fd && b->fds[3]==tail && fcntl(fd,F_GETFD)>=0 && fcntl(tail,F_GETFD)>=0);
        assert(atomic_load(&origin->references)==references);
        assert(!md_received_fds_join(&a,&b) && !b && a->count==5);
        assert(a->origins[0]==origin && a->origins[1]==origin && !a->origins[2]
            && a->origins[3]==origin && !a->origins[4]);
        md_received_fds_release(a);
        assert(atomic_load(&origin->references)==1);
    }
}
int main(void) {
    assert(!md_socket_control_capacity(0));
    assert(!md_socket_control_capacity(CMSG_LEN(0)));
    assert(!md_socket_control_capacity(CMSG_LEN(sizeof(int))-1));
    assert(md_socket_control_capacity(CMSG_LEN(sizeof(int)))==1);
    assert(md_socket_control_capacity(CMSG_LEN(253*sizeof(int)))==253);
    assert(md_socket_control_capacity(CMSG_SPACE(253*sizeof(int))+CMSG_SPACE(sizeof(int)))==254);
    assert(md_socket_control_capacity(SIZE_MAX)==254);
    struct md_fd_object origin; md_fd_object_init(&origin,&type);
    append(NULL); append(&origin); rollback(&origin);
    for (unsigned annotated=0;annotated<2;++annotated) {
        struct md_received_fds *a=vector(1,annotated ? &origin : NULL),*b=vector(1,annotated ? NULL : &origin);
        struct md_received_fds *pin=md_received_fds_retain(a),*tail=b;
        assert(md_received_fds_join(&a,&a)==-EINVAL);
        assert(md_received_fds_join(&a,&b)==-EBUSY && b==tail);
        md_received_fds_release(pin);
        if (!annotated) {
            budget=0; assert(md_received_fds_join(&a,&b)==-ENOMEM && a==pin && b==tail);
        }
        budget=annotated ? 0 : -1;
        assert(!md_received_fds_join(&a,&b) && !b && a==pin);
        budget=-1;
        assert(a->origins[annotated ? 0 : 1]==&origin && !a->origins[annotated ? 1 : 0]);
        md_received_fds_release(a);
    }
    md_fd_object_release(&origin); assert(destroyed==1);
    puts("PASS received FD accumulation: bounded geometric growth, mixed origins, exclusive transfer and allocation rollback");
}
