#ifndef MD_SECCOMP_REPLY_H
#define MD_SECCOMP_REPLY_H
#include <linux/seccomp.h>
#include <stdint.h>

/* One coordinator, one retained native notification. The caller identifies it
 * by listener OFD plus kernel ID, never by a recycled descriptor number. */
struct md_seccomp_reply;
struct md_seccomp_replies {
    struct md_seccomp_reply *first;
    uint64_t id, sequence;
};
/* Snapshot an admitted SEND. Valid replies use a private positive return token;
 * the receiver decodes the committed reply without waiting for the sender's
 * exit stop. Invalid flags remain for the kernel to reject unchanged.
 * Returns 1 translated, 0 unchanged or a negative allocation/identity error. */
int md_seccomp_reply_prepare(struct md_seccomp_replies *,const struct seccomp_notif_resp *,
    struct seccomp_notif_resp *native,struct md_seccomp_reply **);
/* Call on exact ioctl completion, or with known=0 on sender death. Cancellation
 * is not failure evidence: an already accepted reply remains receiver-owned. */
void md_seccomp_reply_complete(struct md_seccomp_reply *,int known,long result);
/* A receiver's actual carrier result is the commit witness. A non-token result
 * (cancellation, listener closure or ADDFD) retains its native value. */
int md_seccomp_reply_result(struct md_seccomp_replies *,long native,long *value,int *continuing);
/* After receiver completion/cancellation/death. In-flight senders keep their
 * snapshot ownership; their later exit cannot touch the retired receiver. */
void md_seccomp_replies_clear(struct md_seccomp_replies *);
#endif
