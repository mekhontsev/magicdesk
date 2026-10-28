#include "fd_admission.h"
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>

struct table {
    struct table *next;
    struct md_fd_origins *files;
    struct md_fd_order turn;
    int *aliases;
    unsigned count;
};
struct owner {
    struct owner *next;
    struct table *table;
    struct md_fd_selector *group;
};
struct md_fd_admission {
    struct md_fd_object *object;
    struct table *tables;
    struct owner *owners,*current;
    struct md_fd_admission_request pending;
    struct md_resource_wakeup *wake;
    unsigned selectors,number,kind;
    int begun,sealed,offered,error;
};
struct md_fd_admission *md_fd_admission_new(struct md_fd_object *object,unsigned selectors) {
    if (!object || !selectors || (selectors&~((1U<<MD_FD_SELECTOR_KINDS)-1))) return NULL;
    struct md_fd_admission *a=calloc(1,sizeof(*a));
    if (a) { a->object=md_fd_object_retain(object); a->selectors=selectors; }
    return a;
}
int md_fd_admission_add(struct md_fd_admission *a,struct md_fd_origins *files,struct md_fd_selector *group) {
    if (!a || !files || !group) return -EINVAL;
    for (struct owner *o=a->owners;o;o=o->next)
        if (o->table->files==files && o->group==group) return 0;
    if (a->sealed) return -EBUSY;
    if (a->error) return a->error;
    struct owner *owner=calloc(1,sizeof(*owner));
    if (!owner) return -ENOMEM;
    struct table **at=&a->tables;
    while (*at && (uintptr_t)(*at)->files<(uintptr_t)files) at=&(*at)->next;
    if (!*at || (*at)->files!=files) {
        struct table *table=calloc(1,sizeof(*table));
        if (!table) { free(owner); return -ENOMEM; }
        table->files=md_fd_origins_pin(files);
        int result=a->begun ? md_fd_order_begin(files,&table->turn,a->wake) : 0;
        if (result<0) { md_fd_origins_unpin(files); free(table); free(owner); return result; }
        table->next=*at; *at=table;
    }
    *owner=(struct owner){a->owners,*at,md_fd_selector_fork(group,1)};
    a->owners=owner; return 0;
}
int md_fd_admission_begin(struct md_fd_admission *a,struct md_resource_wakeup *wake) {
    if (!a) return -EINVAL;
    if (a->begun) return -EBUSY;
    a->begun=1; a->wake=wake;
    for (struct table *t=a->tables;t;t=t->next) {
        int result=md_fd_order_begin(t->files,&t->turn,wake);
        if (result<0) return a->error=result;
    }
    return 0;
}
int md_fd_admission_prepare(struct md_fd_admission *a) {
    if (!a || !a->begun || a->sealed) return -EINVAL;
    if (a->error) return a->error;
    for (struct table *t=a->tables;t;t=t->next)
        if (md_fd_order_state(&t->turn)==MD_RESOURCE_ORDER_QUEUED) return 0;
    for (struct table *t=a->tables;t;t=t->next) {
        int error=md_fd_order_state(&t->turn)==MD_RESOURCE_ORDER_RUNNING ? 0 : md_fd_order_start(&t->turn);
        if (error) return a->error=error;
    }
    return 1;
}
int md_fd_admission_seal(struct md_fd_admission *a) {
    if (!a || !a->begun || a->sealed) return -EINVAL;
    if (a->error) return a->error;
    for (struct table *t=a->tables;t;t=t->next)
        if (md_fd_order_state(&t->turn)!=MD_RESOURCE_ORDER_RUNNING) return -EBUSY;
    for (struct table *t=a->tables;t;t=t->next) {
        int error=md_fd_origins_aliases(&t->turn,a->object,NULL,0,&t->count);
        if (!error && t->count) {
            t->aliases=calloc(t->count,sizeof(*t->aliases));
            error=t->aliases ? md_fd_origins_aliases(&t->turn,a->object,t->aliases,t->count,&t->count) : -ENOMEM;
        }
        if (error) return a->error=error;
    }
    /* Only existing aliases need a frozen table through selector installation.
     * Unrelated owners must progress (including an inferior's debugger). A
     * subsequent transfer into these tables reads the reserved requirements
     * during its ordinary descriptor publication. */
    for (struct table *t=a->tables;t;t=t->next) if (!t->count) {
        int error=md_fd_order_complete(&t->turn);
        if (error) return a->error=error;
    }
    a->sealed=1; a->current=a->owners;
    return 0;
}
int md_fd_admission_next(struct md_fd_admission *a,struct md_fd_admission_request *out) {
    if (!a || !out || !a->sealed) return -EINVAL;
    if (a->error) return a->error;
    if (a->offered) { *out=a->pending; return 1; }
    for (;a->current;a->current=a->current->next,a->number=0) {
        struct owner *o=a->current;
        for (;a->number<o->table->count;++a->number,a->kind=0)
            for (;a->kind<MD_FD_SELECTOR_KINDS;++a->kind) if (a->selectors&(1U<<a->kind)) {
                int fd=o->table->aliases[a->number];
                int ready=md_fd_selector_has(o->group,a->kind,fd);
                if (ready<0) return a->error=ready;
                if (ready) continue;
                a->pending=(struct md_fd_admission_request){o->group,a->kind,fd};
                a->offered=1; *out=a->pending; return 1;
            }
    }
    return 2;
}
int md_fd_admission_observed(struct md_fd_admission *a,const struct md_fd_admission_request *request,long result) {
    if (!a || !request || !a->offered || request->group!=a->pending.group
            || request->kind!=a->pending.kind || request->number!=a->pending.number) return -EINVAL;
    if (a->error) return a->error;
    /* A positive TSYNC result identifies a rejecting sibling, not success.
     * The task owner may wait for an exact retiring helper and retry; no mark
     * is published and the same request remains outstanding. */
    if (result>0) return -EAGAIN;
    if (result<0) return a->error=(int)result;
    int error=md_fd_selector_mark(request->group,request->kind,request->number);
    if (error) return a->error=error;
    a->offered=0; ++a->kind; return 0;
}
int md_fd_admission_retire(struct md_fd_admission *a,struct md_fd_selector *group) {
    if (!a || !group) return -EINVAL;
    struct owner **at=&a->owners;
    while (*at) {
        struct owner *o=*at;
        if (o->group!=group) { at=&o->next; continue; }
        if (a->current==o) {
            a->current=o->next; a->number=a->kind=0; a->offered=0;
        }
        *at=o->next; md_fd_selector_release(o->group); free(o);
    }
    return 0;
}
void md_fd_admission_release(struct md_fd_admission *a) {
    if (!a) return;
    while (a->owners) {
        struct owner *o=a->owners; a->owners=o->next;
        md_fd_selector_release(o->group); free(o);
    }
    while (a->tables) {
        struct table *t=a->tables; a->tables=t->next;
        if (t->turn.table && md_fd_order_cancel(&t->turn)==1) md_fd_order_complete(&t->turn);
        md_fd_origins_unpin(t->files); free(t->aliases); free(t);
    }
    md_fd_object_release(a->object); free(a);
}
