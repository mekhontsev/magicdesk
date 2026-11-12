#ifndef MD_SECCOMP_POLICY_H
#define MD_SECCOMP_POLICY_H
#include "seccomp_program.h"

/* A single coordinator owns publication; immutable chains have thread-safe
 * references so descriptor providers can retire them. Capture copies local
 * bytes, not guest memory, and is not a verifier. The caller must install the
 * exact snapshot through kernel validation before publishing or evaluating it.
 * Birth retains the caller's head; exec retains the executing thread's head;
 * TSYNC replaces peer heads only on confirmed success. */
struct md_seccomp_policy;
struct md_seccomp_gate;
struct md_fd_object;
struct md_seccomp_policy *md_seccomp_policy_retain(struct md_seccomp_policy *);
void md_seccomp_policy_release(struct md_seccomp_policy *);
/* Task attachment is distinct from an object/FD reference. The coordinator
 * replaces a task head atomically with respect to listener readiness; inherited
 * ancestors remain active until their last direct or descendant user leaves. */
void md_seccomp_policy_replace(struct md_seccomp_policy **slot,struct md_seccomp_policy *);
int md_seccomp_policy_capture(struct md_seccomp_policy *parent, const struct sock_fprog *, struct md_seccomp_policy **out);
int md_seccomp_policy_eval(const struct md_seccomp_policy *, const struct seccomp_data *, uint32_t *);
/* Coordinator-local identities are never reused. Export only the suffix above
 * a retained ancestor (zero means the entire chain), avoiding repeated copies
 * on installation. The caller publishes the completed read-only mapping. */
uint64_t md_seccomp_policy_identity(const struct md_seccomp_policy *);
const struct md_seccomp_policy *md_seccomp_policy_parent(const struct md_seccomp_policy *);
int md_seccomp_policy_view_size(const struct md_seccomp_policy *,uint64_t ancestor,size_t *);
int md_seccomp_policy_view_write(const struct md_seccomp_policy *,uint64_t ancestor,
    void *out,size_t capacity,uintptr_t peer,uintptr_t parent);
/* Immutable admission hint: false proves that no captured return can be TRACE.
 * Dynamic returns remain conservative. No BPF walk on ordinary selector stops. */
int md_seccomp_policy_may_trace(const struct md_seccomp_policy *);
int md_seccomp_policy_may_notify(const struct md_seccomp_policy *);
/* A new kernel alias preserves the complete decision only when no ancestor
 * reads its instruction address. Existing mirrored aliases need no such test. */
int md_seccomp_policy_address_independent(const struct md_seccomp_policy *);
/* Entry binding may inherit a portable original policy from another image.
 * It does not prove that all kernel filters admit this image's helper gates. */
uint64_t md_seccomp_policy_entry_image(const struct md_seccomp_policy *);
int md_seccomp_policy_bind_entry(struct md_seccomp_policy *,uint64_t image,
    const struct md_seccomp_gate *,size_t count);
int md_seccomp_policy_entry_compatible(const struct md_seccomp_policy *,uint64_t image);
/* Exact ordered service-gate contract, intersected once at installation across
 * the whole chain. Independent of the image's dynamic SVC pools: exec may reuse
 * identical bootstrap gates, never a changed range, selector or action. */
int md_seccomp_policy_internal_gates(const struct md_seccomp_policy *,
    const struct md_seccomp_gate *,size_t count);
/* An inherited image may reuse stable bootstrap gates only with PC-independent
 * policies and a natively admitted, inert rendezvous syscall. */
int md_seccomp_policy_entry_checkpoint(const struct md_seccomp_policy *);
/* The winning node is borrowed from the retained chain. Equal actions use the
 * newest node, including listeners; ALLOW has no winning node. */
int md_seccomp_policy_decide(const struct md_seccomp_policy *, const struct seccomp_data *,
        uint32_t *, const struct md_seccomp_policy **winner);
/* Single-coordinator binding before publication. A weak OFD identity does not
 * keep the listener alive. A live ancestor listener rejects a second binding;
 * closing it permits a new listener on a later filter, as in the kernel. */
int md_seccomp_policy_bind_listener(struct md_seccomp_policy *,struct md_fd_object *,
    void (*activity)(struct md_fd_object *,int));
/* Owned strong reference for the exact winning node, or NULL after close or
 * when that node has no listener. Never searches ancestors for a substitute. */
struct md_fd_object *md_seccomp_policy_listener(const struct md_seccomp_policy *);

/* Emit a copy for kernel validation with selective TRACE delegation. Callers retain
 * original-argument admission for delegated calls and own all guest storage.
 * Failure leaves output unchanged; returned instruction count is positive. */
struct md_seccomp_gate {
    /* first==end selects one exact PC; otherwise [first,end). A negative
     * number selects all numbers. Gate decisions are ALLOW or TRACE only. */
    uint64_t first,end;
    int64_t number;
    uint32_t decision;
};
struct md_seccomp_route {
    /* Nonzero routes USER_NOTIF returns to this TRACE selector. Original
     * decisions and winning-listener ownership remain in the captured chain.
     * Other actions retain kernel admission, including dynamic RET A. */
    uint16_t notify_cookie;
    /* Zero delegates selected numbers at every PC. An owned service gate
     * leaves the original filter authoritative at application call sites. */
    uint64_t delegation_pc;
    /* Owned service gates. Without resident entry, only non-ALLOW exits consult
     * them. The caller must own every gate and validate admission before any
     * guest effect. Constant-ALLOW paths stay cacheable by the kernel. */
    const struct md_seccomp_gate *gates;
    size_t gate_count;
    /* Stable service prefix, excluding image-local SVC pools. Only an
     * entry-first route may publish this proof after successful installation. */
    size_t internal_gate_count;
    /* A resident entry has already evaluated the original policy. Check its
     * owned gates before the guest program, retaining constant-ALLOW caching. */
    int entry_first;
    uint64_t entry_image;
    /* Zero instruction_pointer disables the optional notification carrier. */
    uint32_t number;
    uint64_t instruction_pointer;
};
/* Optional notification carrier: args[5] is the retained winning node's token.
 * Only that filter emits USER_NOTIF; other filters admit this exact carrier.
 * The controller must validate owned carriers and translate original call data
 * at the listener boundary. Tokens are identities, not secrets or permissions. */
int md_seccomp_policy_route(const struct md_seccomp_policy *, const uint32_t *calls, size_t count,
        uint16_t cookie, struct sock_filter *out, size_t capacity,
        const struct md_seccomp_route *);
#endif
