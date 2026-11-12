#ifndef MD_IDENTITY_TRANSFER_H
#define MD_IDENTITY_TRANSFER_H
#include <stdint.h>
#include <sys/resource.h>
#include <sys/syscall.h>

enum { MD_IDENTITY_PREPARE, MD_IDENTITY_TRANSFER, MD_IDENTITY_COMMIT };
enum { MD_IDENTITY_TRANSFER_WORDS=1024 };
/* A bounded view of an existing adapter buffer. Larger group lists are
 * captured in chunks and committed only after every input copy succeeds. */
union md_identity_transfer {
    uint32_t words[MD_IDENTITY_TRANSFER_WORDS];
    struct { uint32_t version; int32_t pid; uint32_t data[6]; } capabilities;
    struct rlimit limit;
};
static inline int md_identity_transfer_call(long nr) {
    return nr==SYS_getresuid || nr==SYS_getresgid || nr==SYS_getgroups || nr==SYS_setgroups
        || nr==SYS_capget || nr==SYS_capset || nr==SYS_getrlimit || nr==SYS_setrlimit || nr==SYS_prlimit64;
}
long md_identity_dispatch(long,const unsigned long *);
#endif
