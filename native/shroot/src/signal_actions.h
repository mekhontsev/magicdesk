#ifndef MD_SIGNAL_ACTIONS_H
#define MD_SIGNAL_ACTIONS_H
#include "process_owner.h"

/* Coordinator-owned references, independent of CLONE_VM and CLONE_FILES.
 * Publication and handler entry use the enclosing resource admission turn. */
struct md_signal_actions;
struct md_signal_actions *md_signal_actions_new(void);
struct md_signal_actions *md_signal_actions_fork(struct md_signal_actions *,int shared,int clear);
/* NULL admits the first image after a confirmed native exec. Exec resets all
 * caught handlers, but unknown ignored actions and flags remain unknown. */
struct md_signal_actions *md_signal_actions_exec(const struct md_signal_actions *);
void md_signal_actions_release(struct md_signal_actions *);
/* Only one application task can change this table. Internal copy helpers do
 * not install dispositions; shared guest tables always retain admission. */
int md_signal_actions_exclusive(const struct md_signal_actions *);
void md_signal_actions_entry_configure(struct md_signal_actions *,uintptr_t);
uintptr_t md_signal_actions_entry(const struct md_signal_actions *,int signal);
void md_signal_actions_entry_publish(struct md_signal_actions *,int signal);
/* Unknown initial actions are not synthesized as SIG_DFL. Observe their real
 * kernel state before publishing a translated action or entering a handler. */
int md_signal_actions_read(const struct md_signal_actions *,int signal,struct md_kernel_action *);
int md_signal_actions_caught(const struct md_signal_actions *,int signal);
int md_signal_actions_commit(struct md_signal_actions *,int signal,const struct md_kernel_action *);
int md_signal_actions_enter(struct md_signal_actions *,int signal,struct md_kernel_action *);
/* Decode the kernel's previous-action snapshot, including SA_RESETHAND's
 * reset disposition and normalization of flags owned by the entry veneer. */
struct md_kernel_action md_signal_actions_decode(const struct md_signal_actions *,int signal,
    struct md_kernel_action native,uintptr_t entry);
#endif
