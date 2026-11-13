#include "fd_capture.h"
#include "task_descriptor.h"
#include <errno.h>
#include <unistd.h>

int md_fd_capture_native(pid_t actor,int *process,const int *numbers,unsigned count,struct md_received_fds **out) {
    if (!process || !numbers || !count || count>254 || !out || *out) return -EINVAL;
    struct md_received_fds *received=NULL;
    int error=md_received_fds_reserve(&received,count);
    for (unsigned i=0;!error && i<count;++i) {
        int fd=md_task_descriptor_copy(actor,numbers[i],process);
        if (fd<0) error=fd;
        else received->fds[received->count++]=fd;
    }
    if (error) {
        md_received_fds_release(received);
        return md_task_descriptor_needs_export(error) ? 0 : error;
    }
    *out=received;
    return 1;
}

void md_fd_capture_cancel(struct md_fd_capture *c) {
    md_fd_channel_capture_cancel(&c->packet);
    md_received_fds_release(c->result);
    md_fd_channel_release(c->channel);
    *c=(struct md_fd_capture){0};
}
int md_fd_capture_progress(struct md_fd_capture *c, struct md_fd_origins *files, pid_t group,
        unsigned count, struct md_fd_capture_frame *out) {
    if (!count || count>254 || c->packet.channel || (c->count && c->count!=count)) return -EINVAL;
    int error=0;
    if (!c->result) {
        error=md_received_fds_reserve(&c->result,count);
        if (error) return error;
        c->count=count; c->endpoint=-1;
    }
    if (!c->channel) {
        error=md_fd_origins_channel(files,&c->endpoint,&c->channel);
        if (error==-ESTALE) {
            error=md_fd_origins_channel_candidate(files,&c->endpoint,&c->channel);
            if (!error) c->checking=1;
        }
        if (!error && !c->channel) error=md_fd_channel_new(&c->channel);
        if (error) return error;
    }
    *out=(struct md_fd_capture_frame){.endpoint=c->endpoint,.offset=c->offset};
    if (c->checking) return MD_FD_CAPTURE_CHECK;
    if (c->endpoint<0) return MD_FD_CAPTURE_ADMIT;
    unsigned n=count-c->offset;
    if (!n) return 0;
    if (n>253) n=253;
    error=md_fd_channel_capture_vector_begin(c->channel,group,n,c->result->fds+c->offset,&c->packet);
    if (error) return error;
    out->count=n; out->token=c->packet.request;
    return MD_FD_CAPTURE_SEND;
}
int md_fd_capture_observed(struct md_fd_capture *c, struct md_fd_origins *files, const struct md_fd_capture_frame *f) {
    if (!c->checking || !c->channel || f->endpoint!=c->endpoint) return -EINVAL;
    int error=md_fd_channel_resolve(c->channel,files,c->endpoint,&f->observation);
    if (!error) { md_fd_channel_release(c->channel); c->channel=NULL; c->endpoint=-1; c->checking=0; }
    return error;
}
int md_fd_capture_admit(struct md_fd_capture *c, struct md_fd_origins *files, int listener, uint64_t id) {
    if (!c->channel || c->endpoint>=0 || c->checking || c->packet.channel) return -EPERM;
    int fd=md_fd_channel_admit(c->channel,files,listener,id);
    if (fd>=0) c->endpoint=fd;
    return fd;
}
int md_fd_capture_sent(struct md_fd_capture *c, long sent) {
    if (sent<0) return (int)sent;
    if (!c->packet.channel) return -EINVAL;
    unsigned count=c->packet.expected;
    if (sent!=sizeof(uint64_t)) return -EPROTO;
    int error=md_fd_channel_capture_vector_finish(&c->packet);
    if (!error) { c->offset+=count; c->result->count=c->offset; }
    return error;
}
struct md_received_fds *md_fd_capture_take(struct md_fd_capture *c) {
    if (c->offset!=c->count || c->packet.channel) return NULL;
    struct md_received_fds *result=c->result; c->result=NULL;
    md_fd_capture_cancel(c); return result;
}
