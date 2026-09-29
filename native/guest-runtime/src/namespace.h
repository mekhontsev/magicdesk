#ifndef MD_NAMESPACE_H
#define MD_NAMESPACE_H
#include "fs.h"
long md_namespace_open(const struct md_fs *, int, const char *, int, unsigned);
long md_namespace_identity(const struct md_fs *, const char *, char *);
long md_namespace_call(const struct md_fs *, const char *, long, const unsigned long *);
long md_program_open(const struct md_fs *, const char *, int);
long md_program_identity(const struct md_fs *, const char *, char *);
#endif
