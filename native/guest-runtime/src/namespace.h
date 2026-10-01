#ifndef MD_NAMESPACE_H
#define MD_NAMESPACE_H
#include "fs.h"
#include <linux/openat2.h>

long md_namespace_open_resolved(const struct md_fs *, int, const char *, const struct open_how *);
long md_namespace_open(const struct md_fs *, int, const char *, int, unsigned);
long md_namespace_call(const struct md_fs *, const char *, long, const unsigned long *);
#endif
