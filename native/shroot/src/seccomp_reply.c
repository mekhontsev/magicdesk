#include "seccomp_reply.h"
#include <errno.h>
#include <stdlib.h>

struct md_seccomp_reply {
    struct md_seccomp_replies *owner;
    struct md_seccomp_reply *next;
    struct seccomp_notif_resp response;
    long token;
    unsigned references;
};
static void release(struct md_seccomp_reply *reply) {
    if (!--reply->references) free(reply);
}
static void detach(struct md_seccomp_reply *reply) {
    if (!reply->owner) return;
    struct md_seccomp_reply **slot=&reply->owner->first;
    while (*slot!=reply) slot=&(*slot)->next;
    *slot=reply->next; reply->owner=NULL; reply->next=NULL;
    release(reply);
}
int md_seccomp_reply_prepare(struct md_seccomp_replies *owner,const struct seccomp_notif_resp *response,
        struct seccomp_notif_resp *native,struct md_seccomp_reply **out) {
    if (!owner || !response || !native || !out || *out) return -EINVAL;
    *native=*response;
    if (response->flags&~SECCOMP_USER_NOTIF_FLAG_CONTINUE
            || ((response->flags&SECCOMP_USER_NOTIF_FLAG_CONTINUE) && (response->error || response->val))) return 0;
    if (response->id!=owner->id) return -ENOENT;
    if (owner->sequence==UINT64_C(0x3fffffffffffffff)) return -EOVERFLOW;
    struct md_seccomp_reply *reply=calloc(1,sizeof(*reply));
    if (!reply) return -ENOMEM;
    reply->owner=owner; reply->response=*response; reply->references=2;
    reply->token=(long)(UINT64_C(0x4000000000000000)|++owner->sequence);
    reply->next=owner->first; owner->first=reply;
    *native=(struct seccomp_notif_resp){.id=response->id,.val=reply->token};
    *out=reply; return 1;
}
void md_seccomp_reply_complete(struct md_seccomp_reply *reply,int known,long result) {
    if (!reply) return;
    if (known && result) detach(reply);
    release(reply);
}
int md_seccomp_reply_result(struct md_seccomp_replies *owner,long native,long *value,int *continuing) {
    if (!owner || !value || !continuing) return -EINVAL;
    *value=native; *continuing=0;
    for (struct md_seccomp_reply *reply=owner->first;reply;reply=reply->next) {
        if (reply->token!=native) continue;
        *value=reply->response.error ? reply->response.error : reply->response.val;
        *continuing=!!(reply->response.flags&SECCOMP_USER_NOTIF_FLAG_CONTINUE);
        return 1;
    }
    return 0;
}
void md_seccomp_replies_clear(struct md_seccomp_replies *owner) {
    if (!owner) return;
    while (owner->first) detach(owner->first);
    owner->id=0;
}
