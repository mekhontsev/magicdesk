#ifndef MD_LINUX_ABI_H
#define MD_LINUX_ABI_H
#include <stdint.h>
#include <sys/syscall.h>
/* Linux 6.8 mount queries need a guest namespace provider, not host passthrough. */
#ifndef SYS_statmount
#define SYS_statmount 457
#endif
#ifndef SYS_listmount
#define SYS_listmount 458
#endif
/* Linux 6.13 guest ABI; the adapter uses existing xattr providers on older hosts. */
#ifndef SYS_setxattrat
#define SYS_setxattrat 463
#endif
#ifndef SYS_getxattrat
#define SYS_getxattrat 464
#endif
#ifndef SYS_listxattrat
#define SYS_listxattrat 465
#endif
#ifndef SYS_removexattrat
#define SYS_removexattrat 466
#endif
#ifndef SYS_open_tree_attr
#define SYS_open_tree_attr 467
#endif
struct md_xattr_args { uint64_t value; uint32_t size, flags; };
// libc headers and faccessat wrappers differ in AT_EACCESS support. The guest
// and our raw faccessat2 calls use the Linux ABI, independent of the build libc.
#define MD_AT_EACCESS 0x200
// pidfd_open's thread flag is absent from older NDK kernel headers.
#define MD_PIDFD_THREAD 0x80
/* Linux 6.11 pidfs namespace queries; implemented against guest namespaces. */
static inline int md_pidfd_namespace_command(unsigned command) {
    return command>=0xff01 && command<=0xff0a;
}
#endif
