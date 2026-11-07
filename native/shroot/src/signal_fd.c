#define _GNU_SOURCE
#include "signal_fd.h"
#include "fd_selector.h"
#include "signal_protocol.h"
#include "signal_pending.h"
#include "signal_view.h"
#include <poll.h>
#include <stdlib.h>

struct md_signal_fd { struct md_fd_object object; uint64_t mask, revision; };
static void destroy(struct md_fd_object *object) { free(object); }
static long task_poll(struct md_fd_object *object,const struct md_control_actor *actor) {
    struct md_signal_fd *s=(void *)object;
    return actor && (s->mask&MD_SIGNAL_SYS_BIT)
        && (md_signal_pending_has(actor->signals,MD_SIGNAL_PRIVATE)
            || md_signal_pending_has(actor->signals,MD_SIGNAL_SHARED)) ? POLLIN : 0;
}
static struct md_task_readiness_epoch task_epoch(struct md_fd_object *object,const struct md_control_actor *actor) {
    return (struct md_task_readiness_epoch){.domain=md_signal_pending_domain(actor ? actor->signals : NULL),
        .pending=md_signal_pending_revision(actor ? actor->signals : NULL),
        .configuration=((struct md_signal_fd *)object)->revision};
}
static const struct md_fd_object_type type={.destroy=destroy,.task_poll=task_poll,.task_poll_epoch=task_epoch,
    .selectors=(1U<<MD_FD_SELECTOR_PROC)|(1U<<MD_FD_SELECTOR_EPOLL_TARGET)|(1U<<MD_FD_SELECTOR_POLL)};
struct md_signal_fd *md_signal_fd_new(void) {
    struct md_signal_fd *s=calloc(1,sizeof(*s));
    if (s) md_fd_object_init(&s->object,&type);
    return s;
}
struct md_signal_fd *md_signal_fd_from_object(struct md_fd_object *object) {
    return object && object->type==&type ? (void *)object : NULL;
}
struct md_fd_object *md_signal_fd_object(struct md_signal_fd *s) { return s ? &s->object : NULL; }
uint64_t md_signal_fd_mask(const struct md_signal_fd *s) { return s ? s->mask : 0; }
void md_signal_fd_update(struct md_signal_fd *s,uint64_t mask) {
    s->mask=mask&md_signal_blockable_mask(); ++s->revision;
}
void md_signal_fd_info(const siginfo_t *in,struct signalfd_siginfo *out) {
    *out=(struct signalfd_siginfo){.ssi_signo=in->si_signo,.ssi_errno=in->si_errno,.ssi_code=in->si_code};
    static const unsigned limits[]={
        [SIGILL]=NSIGILL,[SIGFPE]=NSIGFPE,[SIGSEGV]=NSIGSEGV,[SIGBUS]=NSIGBUS,
        [SIGTRAP]=NSIGTRAP,[SIGCHLD]=NSIGCHLD,[SIGSYS]=NSIGSYS};
    int specific=in->si_signo>0 && (unsigned)in->si_signo<sizeof(limits)/sizeof(*limits)
        && in->si_code>0 && (unsigned)in->si_code<=limits[in->si_signo];
    if (in->si_code==SI_TIMER) {
        out->ssi_tid=in->si_timerid; out->ssi_overrun=in->si_overrun;
        out->ssi_ptr=(uintptr_t)in->si_value.sival_ptr; out->ssi_int=in->si_value.sival_int;
    } else if (in->si_code==SI_SIGIO || (!specific && in->si_code>0 && in->si_code<=NSIGPOLL)) {
        out->ssi_band=in->si_band; out->ssi_fd=in->si_fd;
    } else if (specific && in->si_signo==SIGCHLD) {
        out->ssi_pid=in->si_pid; out->ssi_uid=in->si_uid; out->ssi_status=in->si_status;
        out->ssi_utime=in->si_utime; out->ssi_stime=in->si_stime;
    } else if (specific && in->si_signo==SIGSYS) {
        out->ssi_call_addr=(uintptr_t)in->si_call_addr; out->ssi_syscall=in->si_syscall; out->ssi_arch=in->si_arch;
    } else if (specific) {
        out->ssi_addr=(uintptr_t)in->si_addr;
        if (in->si_signo==SIGBUS && (in->si_code==BUS_MCEERR_AR || in->si_code==BUS_MCEERR_AO))
            out->ssi_addr_lsb=in->si_addr_lsb;
    } else {
        out->ssi_pid=in->si_pid; out->ssi_uid=in->si_uid;
        if (in->si_code<0) {
            out->ssi_ptr=(uintptr_t)in->si_value.sival_ptr; out->ssi_int=in->si_value.sival_int;
        }
    }
}
