#define _GNU_SOURCE
#include "process_oom.h"
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int space(unsigned char c) { return c==' ' || (c>='\t' && c<='\r'); }
static int number(const void *data,size_t size,int legacy,int *value) {
    char text[32];
    if (size>=sizeof(text)) size=sizeof(text)-1;
    memcpy(text,data,size); text[size]=0;
    char *start=text,*end;
    while (space(*start)) ++start;
    errno=0;
    long n=strtol(start,&end,0);
    if (errno==ERANGE || n<INT_MIN || n>INT_MAX) return -ERANGE;
    if (start==end || errno) return -EINVAL;
    while (space(*end)) ++end;
    if (*end || (legacy ? n < -17 || n > 15 : n < -1000 || n > 1000)) return -EINVAL;
    *value=legacy ? n==15 ? 1000 : (int)n*1000/17 : (int)n;
    return 0;
}
long md_process_oom_io(struct md_process_oom *state,const struct md_identity *actor,int legacy,
        int writing,int fd,int update,int write_error,void *data,size_t size,int64_t offset) {
    if (!state) return -ESRCH;
    if (offset<0) return -EINVAL;
    /* Reading the retained process entry also rejects an exited incarnation.
     * Never substitute a cached value for a dead native task. */
    char text[32];
    ssize_t count;
    do count=pread(fd,text,sizeof(text)-1,0); while (count<0 && errno==EINTR);
    if (count<0) return -errno;
    int native;
    if (!count || number(text,(size_t)count,0,&native)) return -EIO;
    if (!state->virtualized) state->value=native;
    if (!writing) {
        int value=state->value;
        if (legacy) { value=value*17/1000; if (value>15) value=15; }
        count=snprintf(text,sizeof(text),"%d\n",value);
        if (offset>=count) return 0;
        size_t n=(size_t)(count-offset); if (n>size) n=size;
        if (n) memcpy(data,text+offset,n);
        return (long)n;
    }
    int value,error=number(data,size,legacy,&value);
    if (error) return error;
    int privileged=md_identity_capable_in(actor,NULL,CAP_SYS_RESOURCE);
    if (!privileged && value<(legacy ? state->value : state->minimum)) return -EACCES;
    if (!state->virtualized) {
        if (!write_error) {
            size_t n=size<sizeof(text) ? size : sizeof(text)-1;
            do count=pwrite(update,data,n,0); while (count<0 && errno==EINTR);
            write_error=count<0 ? -errno : (size_t)count!=n ? -EIO : 0;
        }
        if (write_error && write_error!=-EPERM && write_error!=-EACCES) return write_error;
        state->virtualized=!!write_error;
    }
    state->value=value;
    if (privileged && !legacy) state->minimum=value;
    return (long)(size<sizeof(text) ? size : sizeof(text)-1);
}
