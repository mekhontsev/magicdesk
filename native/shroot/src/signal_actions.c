#include "signal_actions.h"
#include "signal_view.h"
#include <errno.h>
#include <signal.h>
#include <stdlib.h>

struct md_signal_actions {
    unsigned references;
    int after_exec;
    uintptr_t entry;
    struct md_kernel_action values[NSIG];
    unsigned char known[NSIG], reset[NSIG];
    unsigned char wrapped[NSIG];
};
static int valid(int signal) { return signal>0 && signal<NSIG; }
struct md_signal_actions *md_signal_actions_new(void) {
    struct md_signal_actions *actions=calloc(1,sizeof(*actions));
    if (actions) actions->references=1;
    return actions;
}
struct md_signal_actions *md_signal_actions_fork(struct md_signal_actions *parent,int shared,int clear) {
    if (!parent || (shared && clear)) { errno=EINVAL; return NULL; }
    if (shared) { ++parent->references; return parent; }
    if (clear) return md_signal_actions_exec(parent);
    struct md_signal_actions *actions=malloc(sizeof(*actions));
    if (!actions) return NULL;
    *actions=*parent; actions->references=1;
    return actions;
}
struct md_signal_actions *md_signal_actions_exec(const struct md_signal_actions *parent) {
    struct md_signal_actions *actions=md_signal_actions_new();
    if (!actions) return NULL;
    actions->after_exec=1;
    if (!parent) return actions;
    for (int signal=1;signal<NSIG;++signal) {
        actions->known[signal]=1;
        if (!parent->known[signal]) actions->known[signal]=0;
        else if (!parent->reset[signal] && parent->values[signal].handler==(uintptr_t)SIG_IGN)
            actions->values[signal].handler=(uintptr_t)SIG_IGN;
    }
    return actions;
}
void md_signal_actions_release(struct md_signal_actions *actions) {
    if (actions && !--actions->references) free(actions);
}
int md_signal_actions_exclusive(const struct md_signal_actions *actions) {
    return actions && actions->references==1;
}
void md_signal_actions_entry_configure(struct md_signal_actions *actions,uintptr_t entry) {
    if (actions) actions->entry=entry;
}
uintptr_t md_signal_actions_entry(const struct md_signal_actions *actions,int signal) {
    return actions && (!signal || (valid(signal) && actions->wrapped[signal])) ? actions->entry : 0;
}
void md_signal_actions_entry_publish(struct md_signal_actions *actions,int signal) {
    if (actions && valid(signal)) actions->wrapped[signal]=!!actions->entry;
}
int md_signal_actions_read(const struct md_signal_actions *actions,int signal,struct md_kernel_action *out) {
    if (!actions || !valid(signal) || !out) return -EINVAL;
    if (!actions->known[signal]) return -ENODATA;
    *out=actions->values[signal];
    if (actions->reset[signal]) out->handler=(uintptr_t)SIG_DFL;
    return 0;
}
int md_signal_actions_caught(const struct md_signal_actions *actions,int signal) {
    if (!actions || !valid(signal)) return -EINVAL;
    struct md_kernel_action action;
    int error=md_signal_actions_read(actions,signal,&action);
    if (error) return error==-ENODATA && actions->after_exec ? 0 : error;
    return action.handler!=(uintptr_t)SIG_DFL && action.handler!=(uintptr_t)SIG_IGN;
}
int md_signal_actions_commit(struct md_signal_actions *actions,int signal,const struct md_kernel_action *value) {
    if (!actions || !value || !valid(signal) || signal==SIGKILL || signal==SIGSTOP) return -EINVAL;
    actions->values[signal]=*value; actions->known[signal]=1; actions->reset[signal]=0;
    return 0;
}
int md_signal_actions_enter(struct md_signal_actions *actions,int signal,struct md_kernel_action *out) {
    int error=md_signal_actions_read(actions,signal,out);
    if (error) return error;
    if (out->handler==(uintptr_t)SIG_DFL || out->handler==(uintptr_t)SIG_IGN) return -EINVAL;
    if (out->flags&SA_RESETHAND) actions->reset[signal]=1;
    return 0;
}
struct md_kernel_action md_signal_actions_decode(const struct md_signal_actions *actions,int signal,
        struct md_kernel_action native,uintptr_t entry) {
    if (!actions || !valid(signal) || !actions->known[signal]) return native;
    return md_signal_view_action(signal,native,&actions->values[signal],entry);
}
