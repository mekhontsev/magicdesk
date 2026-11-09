#include "robust_futex.h"
#include <linux/futex.h>
#include <string.h>

enum { HEAD, NEXT, WORD, EXCHANGE, WAKE };
static uintptr_t address(uintptr_t tagged) { return tagged&~(uintptr_t)1; }
static void finish(struct md_robust_cleanup *c) { c->request=(struct md_robust_request){0}; }
static void read_at(struct md_robust_cleanup *c,unsigned stage,uintptr_t at,size_t size) {
    c->stage=stage;
    c->request=(struct md_robust_request){.operation=MD_ROBUST_READ,.address=at,.size=size};
}
static void word(struct md_robust_cleanup *c) {
    uintptr_t at=address(c->entry)+c->offset;
    if (at%sizeof(uint32_t)) { finish(c); return; }
    read_at(c,WORD,at,sizeof(uint32_t));
}
static void pending(struct md_robust_cleanup *c) {
    if (!address(c->pending)) { finish(c); return; }
    c->processing_pending=1; c->entry=c->pending; word(c);
}
static void next(struct md_robust_cleanup *c) {
    if (c->processing_pending || c->next_error) { finish(c); return; }
    c->entry=c->next;
    if (!--c->remaining || address(c->entry)==c->head) { pending(c); return; }
    read_at(c,NEXT,address(c->entry),sizeof(uint64_t));
}
static void wake(struct md_robust_cleanup *c) {
    c->stage=WAKE; c->request.operation=MD_ROBUST_WAKE;
}
static void value(struct md_robust_cleanup *c,uint32_t old) {
    int pi=c->entry&1;
    c->value=old;
    /* A dying unlocker may have cleared the word but not issued its wake. */
    if (c->processing_pending && !pi && !old) { wake(c); return; }
    if ((old&FUTEX_TID_MASK)!=c->owner) { next(c); return; }
    c->stage=EXCHANGE;
    c->request.operation=MD_ROBUST_CMPXCHG;
    c->request.expected=old;
    c->request.replacement=(old&FUTEX_WAITERS)|FUTEX_OWNER_DIED;
}
void md_robust_begin(struct md_robust_cleanup *c,uintptr_t head,uint32_t owner) {
    *c=(struct md_robust_cleanup){.head=head,.owner=owner,.remaining=ROBUST_LIST_LIMIT};
    if (head) read_at(c,HEAD,head,sizeof(struct md_robust_head));
}
void md_robust_complete(struct md_robust_cleanup *c,int error,const void *result) {
    if (!c->request.operation) return;
    if (error && c->stage!=NEXT && c->stage!=WAKE) { finish(c); return; }
    switch (c->stage) {
    case HEAD: {
        struct md_robust_head head; memcpy(&head,result,sizeof(head));
        c->entry=head.next; c->offset=head.offset; c->pending=head.pending;
        if (address(c->entry)==c->head) pending(c);
        else read_at(c,NEXT,address(c->entry),sizeof(uint64_t));
        break;
    }
    case NEXT:
        /* Even an unreadable successor must not suppress the current lock's
         * cleanup. Its word may precede the inaccessible list link. */
        c->next_error=error;
        if (!error) memcpy(&c->next,result,sizeof(uint64_t));
        if (address(c->entry)==address(c->pending)) next(c);
        else word(c);
        break;
    case WORD: {
        uint32_t old; memcpy(&old,result,sizeof(old)); value(c,old); break;
    }
    case EXCHANGE: {
        uint32_t observed; memcpy(&observed,result,sizeof(observed));
        if (observed!=c->value) value(c,observed);
        else if (!(c->entry&1) && (observed&FUTEX_WAITERS)) wake(c);
        else next(c);
        break;
    }
    case WAKE: next(c); break;
    }
}
