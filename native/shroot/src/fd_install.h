#ifndef MD_FD_INSTALL_H
#define MD_FD_INSTALL_H
#include "fd_origins.h"
#include <stdint.h>

enum { MD_FD_INSTALL_WAIT=-4096, MD_FD_INSTALL_CANCELLED=-4097 };
/* The caller retains the source FD until completion/cancellation. The record
 * retains its origin independently until then.
 * One owner serializes each record, including cancellation. No guest
 * acknowledgement or guessed close is needed: atomic ADDFD_SEND publishes the kernel result under
 * the destination table turn before any queued mutation can run. */
struct md_fd_install {
    struct md_fd_order files;
    struct md_fd_order *borrowed;
    struct md_fd_object *origin;
    struct md_fd_loan *loan;
    uint64_t notification;
    int listener, source;
    int retire_error;
    unsigned flags;
    unsigned add_flags,number;
    struct md_fd_closed closed;
};
/* A new notification may replace an expired queued admission for the same
 * retained result. A live admission or a different result returns EBUSY. */
int md_fd_install_begin(struct md_fd_install *, struct md_fd_origins *,
        int listener, uint64_t notification, int source, struct md_fd_object *,
        unsigned flags, struct md_resource_wakeup *);
/* The caller already owns the exact running table turn. Publication borrows
 * it without acquiring a second turn or releasing the caller's authority. */
int md_fd_install_borrow(struct md_fd_install *,struct md_fd_order *,
    int listener,uint64_t notification,int source,struct md_fd_object *,unsigned flags);
/* WAIT retains the record; any other result completes it. CANCELLED means the
 * notification expired before installation, not that the source was consumed.
 * Other negative results still need an error reply; only kernel acceptance of
 * that reply completes delivery. A stopped live receiver may restart its call.
 * Expired queued notifications retire without waiting for the table turn.
 * retire_error reports an internal turn/wakeup failure separately: it cannot
 * replace a committed kernel result or authorize a second notification reply. */
int md_fd_install_progress(struct md_fd_install *);
/* Guest notification ADDFD options. Default remains atomic SEND into a new
 * slot. SETFD replacement returns provider closures in closed, after the turn. */
int md_fd_install_target(struct md_fd_install *,unsigned flags,unsigned number);
int md_fd_install_cancel(struct md_fd_install *);
/* Set before progress while the caller retains the operation loan. */
int md_fd_install_loan(struct md_fd_install *,struct md_fd_loan *);
#endif
