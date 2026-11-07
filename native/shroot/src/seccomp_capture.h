#ifndef MD_SECCOMP_CAPTURE_H
#define MD_SECCOMP_CAPTURE_H
#include "interception_buffers.h"
#include "seccomp_policy.h"

struct md_seccomp_image {
    struct sock_fprog program;
    struct sock_filter code[BPF_MAXINSNS];
};
enum { MD_SECCOMP_IMAGE_SLOTS=8 };
/* The coordinator owns guest copies, mapping admission and the kernel event.
 * This record retains exact bytes and their slot until confirmed syscall exit
 * or task death. Peer mappings must be shared/read-only and protected from
 * replacement during installation by their existing address-space owner.
 * The same admission boundary must defer peer policy consumers until TSYNC
 * completion is published: the kernel may already have changed their filters
 * before the coordinator receives the installer's exit event.
 * A request to cancel is not a completion and must not retire this record. */
struct md_seccomp_capture {
    struct md_interception_buffer buffer;
    struct md_seccomp_policy *policy;
    uintptr_t program;
};
int md_seccomp_capture_prepare(struct md_seccomp_capture *,struct md_interception_buffers *,
    uintptr_t peer_base,struct md_seccomp_policy *parent,const struct sock_fprog *local,
    const uint32_t *delegated,size_t count,uint16_t cookie,
    const struct md_seccomp_route *);
/* Valid only after kernel verification/installation of the exact published
 * image. Success transfers the chain to out; failure leaves out unchanged.
 * Both outcomes release the image. Zero/one indicates rejection/publication. */
int md_seccomp_capture_complete(struct md_seccomp_capture *,unsigned flags,long result,
    struct md_seccomp_policy **out);
/* Before submission or after confirmed terminal completion, never in flight. */
void md_seccomp_capture_discard(struct md_seccomp_capture *);
#endif
