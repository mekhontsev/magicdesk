#define _GNU_SOURCE
#include "fs_continuation.h"
#include "fs_engine.h"
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int md_fs_continuation_pending(struct md_fs_continuation *previous,
        struct md_fs_continuation **next,struct md_fs_result *result) {
    if (previous && previous!=*next) previous->destroy(previous);
    if (*next && result->error==-EINPROGRESS && result->fd<0
            && !result->open_completion.kind && !result->size && !result->host_path)
        return 1;
    if (*next) {
        (*next)->destroy(*next); *next=NULL;
        result->error=-EPROTO;
    } else if (result->error==-EINPROGRESS) result->error=-EPROTO;
    return 0;
}

void md_fs_continuation_clear(struct md_fs_continuation *c) {
    for (unsigned i=0;i<2;++i) {
        if (c->request.directory[i]>=0
                && c->request.directory[i]!=md_fs_anchor_fd(c->request.origin[i])) close(c->request.directory[i]);
        md_fs_anchor_release((struct md_fs_anchor *)c->request.origin[i]);
        c->request.directory[i]=-1; c->request.origin[i]=NULL;
    }
    md_control_file_release(c->request.control); c->request.control=(struct md_control_file){0};
    free(c->arguments); c->arguments=NULL;
}
int md_fs_continuation_capture(struct md_fs_continuation *c,const struct md_fs_request *q) {
    c->request=*q; c->arguments=NULL;
    for (unsigned i=0;i<2;++i) { c->request.directory[i]=-1; c->request.origin[i]=NULL; }
    c->request.control=(struct md_control_file){0};
    const char *strings[]={q->path[0],q->path[1],q->object[0],q->object[1]};
    size_t lengths[4]={0},total=q->value_size;
    if ((q->value_size && !q->value) || q->capture[0].path || q->capture[1].path) return -EINVAL;
    for (unsigned i=0;i<4;++i) if (strings[i]) {
        lengths[i]=strlen(strings[i])+1;
        if (__builtin_add_overflow(total,lengths[i],&total)) return -EOVERFLOW;
    }
    char *data=malloc(total ? total : 1);
    if (!data) return -ENOMEM;
    c->arguments=data;
    const char **destinations[]={&c->request.path[0],&c->request.path[1],&c->request.object[0],&c->request.object[1]};
    for (unsigned i=0;i<4;++i) {
        *destinations[i]=lengths[i] ? data : NULL;
        if (lengths[i]) { memcpy(data,strings[i],lengths[i]); data+=lengths[i]; }
    }
    c->request.value=q->value_size ? data : NULL;
    if (q->value_size) memcpy(data,q->value,q->value_size);
    c->request.control=md_control_file_retain(q->control);
    for (unsigned i=0;i<2;++i) {
        c->request.origin[i]=md_fs_anchor_retain((struct md_fs_anchor *)q->origin[i]);
        /* The retained origin already owns its pin. Preserve that identity
         * across suspension; separately supplied descriptors need a copy. */
        c->request.directory[i]=q->directory[i]<0 || q->directory[i]==md_fs_anchor_fd(q->origin[i])
            ? q->directory[i] : fcntl(q->directory[i],F_DUPFD_CLOEXEC,3);
        if (q->directory[i]>=0 && c->request.directory[i]<0) {
            int error=-errno; md_fs_continuation_clear(c); return error;
        }
    }
    return 0;
}

struct sequence {
    struct md_fs_continuation base,*child;
    md_fs_next_stage next;
    int native_plan;
};
static void sequence_destroy(struct md_fs_continuation *base) {
    struct sequence *s=(void *)base;
    if (s->child) s->child->destroy(s->child);
    md_fs_continuation_clear(base); free(s);
}
static int sequence_ready(struct md_fs_continuation *base) {
    struct sequence *s=(void *)base; return s->child->ready(s->child);
}
static void sequence_finish(struct md_filesystem *fs,const struct md_fs_request *q,
        struct md_fs_result *result,struct md_fs_anchor *origin,
        struct md_fs_result *out,const struct md_fs_output *output,md_fs_next_stage next) {
    next(fs,q,result,&origin,out,output);
    if (result->fd>=0) close(result->fd);
    md_fs_anchor_release(origin);
}
static void sequence_resume(struct md_fs_continuation *base,struct md_filesystem *fs,
        struct md_fs_result *out,const struct md_fs_output *output) {
    struct sequence *s=(void *)base;
    struct md_fs_anchor *origin=NULL;
    struct md_fs_continuation *next=NULL;
    struct md_fs_output capture=output ? *output : (struct md_fs_output){0};
    capture.origin=fs->mounts ? &origin : NULL;
    capture.continuation=&next; capture.native_open_plan=s->native_plan;
    struct md_fs_result result={.fd=-1};
    s->child->resume(s->child,fs,&result,&capture);
    int pending=md_fs_continuation_pending(s->child,&next,&result); s->child=next;
    if (pending) {
        md_fs_anchor_release(origin);
        base->task_input=next->task_input;
        *out=result; *output->continuation=base;
    } else sequence_finish(fs,&base->request,&result,origin,out,output,s->next);
}
void md_fs_then(struct md_filesystem *fs,const struct md_fs_request *q,
        const struct md_fs_request *step,md_fs_stage execute,struct md_fs_result *out,const struct md_fs_output *output,
        md_fs_next_stage next) {
    struct md_fs_anchor *origin=NULL;
    struct md_fs_continuation *child=NULL;
    struct md_fs_output capture=output ? *output : (struct md_fs_output){0};
    capture.origin=fs->mounts ? &origin : NULL;
    capture.continuation=output && output->continuation ? &child : NULL;
    struct md_fs_result result={.fd=-1};
    execute(fs,step,&result,&capture);
    if (!md_fs_continuation_pending(NULL,&child,&result)) {
        sequence_finish(fs,q,&result,origin,out,output,next); return;
    }
    md_fs_anchor_release(origin);
    struct sequence *s=calloc(1,sizeof(*s));
    int error=s ? md_fs_continuation_capture(&s->base,q) : -ENOMEM;
    if (error) { child->destroy(child); free(s); *out=(struct md_fs_result){.fd=-1,.error=error}; return; }
    s->base.ready=sequence_ready; s->base.resume=sequence_resume; s->base.destroy=sequence_destroy;
    s->child=child; s->next=next; s->native_plan=capture.native_open_plan;
    s->base.task_input=child->task_input;
    *out=result; *output->continuation=&s->base;
}
void md_fs_view_then(struct md_filesystem *fs,const struct md_fs_request *q,
        const struct md_fs_request *step,struct md_fs_result *out,const struct md_fs_output *output,
        md_fs_next_stage next) {
    md_fs_then(fs,q,step,md_fs_view_execute,out,output,next);
}
