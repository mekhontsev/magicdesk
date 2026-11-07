#ifndef MD_HOST_OPEN_H
#define MD_HOST_OPEN_H
#include <linux/openat2.h>
/* Captured local path/how, under the caller's native identity. Native openat2
 * stays first. The fallback never relaxes unsupported resolution constraints. */
long md_host_open_resolved(int,const char *,const struct open_how *);
#endif
