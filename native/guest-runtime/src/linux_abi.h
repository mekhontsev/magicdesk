#ifndef MD_LINUX_ABI_H
#define MD_LINUX_ABI_H
// libc headers and faccessat wrappers differ in AT_EACCESS support. The guest
// and our raw faccessat2 calls use the Linux ABI, independent of the build libc.
#define MD_AT_EACCESS 0x200
// pidfd_open's thread flag is absent from older NDK kernel headers.
#define MD_PIDFD_THREAD 0x80
#endif
