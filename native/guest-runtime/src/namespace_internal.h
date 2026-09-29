#ifndef MD_NAMESPACE_INTERNAL_H
#define MD_NAMESPACE_INTERNAL_H
#include "namespace.h"
#include "fs_rpc.h"

/* Paths are adapter-owned; other syscall pointers are validated when used. */
long md_namespace_request(const struct md_fs *, struct md_fs_request *, struct md_fs_result *);
long md_namespace_inspect(const struct md_fs *, int, const char *, int, struct md_fs_result *);
long md_namespace_xattr(const struct md_fs *, long, int, const char *, const unsigned long *);
long md_namespace_path_call(const struct md_fs *, long, const unsigned long *, int, const char *);
long md_namespace_host_call(const struct md_fs *, const char *, long, const unsigned long *,
                            unsigned, const char *);
#endif
