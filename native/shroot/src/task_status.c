#define _GNU_SOURCE
#include "task_status.h"
#include "raw.h"
#include <errno.h>
#include <fcntl.h>

long md_task_status_open(int tid) {
    if (tid<=0) return -EINVAL;
    char path[64]="/proc/";
    md_decimal(path+md_length(path),(unsigned)tid);
    md_copy(path+md_length(path),sizeof(path)-md_length(path),"/status");
    return RAW4(openat,AT_FDCWD,path,O_RDONLY|O_CLOEXEC,0);
}
static long read_status(int fd,char *text,size_t capacity);
static const char *const limits[]={
    [RLIMIT_CPU]="Max cpu time", [RLIMIT_FSIZE]="Max file size",
    [RLIMIT_DATA]="Max data size", [RLIMIT_STACK]="Max stack size",
    [RLIMIT_CORE]="Max core file size", [RLIMIT_RSS]="Max resident set",
    [RLIMIT_NPROC]="Max processes", [RLIMIT_NOFILE]="Max open files",
    [RLIMIT_MEMLOCK]="Max locked memory", [RLIMIT_AS]="Max address space",
    [RLIMIT_LOCKS]="Max file locks", [RLIMIT_SIGPENDING]="Max pending signals",
    [RLIMIT_MSGQUEUE]="Max msgqueue size", [RLIMIT_NICE]="Max nice priority",
    [RLIMIT_RTPRIO]="Max realtime priority", [RLIMIT_RTTIME]="Max realtime timeout"
};
static int limit_value(const char **cursor,rlim_t *out) {
    const char *p=*cursor;
    while (*p==' ' || *p=='\t') ++p;
    uint64_t value=0; unsigned digits=0;
    if (md_prefix(p,"unlimited")) { value=RLIM_INFINITY; p+=9; digits=1; }
    else while (*p>='0' && *p<='9') {
        unsigned digit=(unsigned)(*p++-'0');
        if (value>(UINT64_MAX-digit)/10) return -EIO;
        value=value*10+digit; ++digits;
    }
    if (!digits || (*p!=' ' && *p!='\t' && *p!='\n' && *p)) return -EIO;
    *out=value; *cursor=p; return 0;
}
long md_task_limit(int tid,unsigned resource,struct rlimit *value) {
    if (tid<=0 || !value || resource>=sizeof(limits)/sizeof(*limits) || !limits[resource]) return -EINVAL;
    long result=RAW4(prlimit64,tid,resource,0,value);
    if (result!=-EACCES && result!=-EPERM) return result;
    char path[64]="/proc/";
    md_decimal(path+md_length(path),(unsigned)tid);
    md_copy(path+md_length(path),sizeof(path)-md_length(path),"/limits");
    long fd=RAW4(openat,AT_FDCWD,path,O_RDONLY|O_CLOEXEC,0);
    /* hidepid can conceal a live protected task. Keep the native permission
     * result; only prlimit's own ESRCH proves that its target is gone. */
    if (fd<0) return fd==-ENOENT ? result : fd;
    result=md_task_limit_fd((int)fd,resource,value);
    RAW1(close,fd);
    return result;
}
long md_task_limit_fd(int fd,unsigned resource,struct rlimit *value) {
    if (fd<0 || !value || resource>=sizeof(limits)/sizeof(*limits) || !limits[resource]) return -EINVAL;
    char text[4096];
    long size=read_status(fd,text,sizeof(text));
    if (size<0) return size;
    for (const char *p=text;*p;++p) {
        if ((p!=text && p[-1]!='\n') || !md_prefix(p,limits[resource])) continue;
        p+=md_length(limits[resource]);
        if (*p!=' ' && *p!='\t') return -EIO;
        struct rlimit parsed;
        int error=limit_value(&p,&parsed.rlim_cur);
        if (!error) error=limit_value(&p,&parsed.rlim_max);
        if (!error) *value=parsed;
        return error;
    }
    return -ENOTSUP;
}
static long read_status(int fd,char *text,size_t capacity) {
    size_t size=0;
    long error=0;
    while (size<capacity-1) {
        long n=RAW4(pread64,fd,text+size,capacity-1-size,size);
        if (n==-EINTR) continue;
        if (n<0) { error=n; break; }
        if (!n) break;
        size+=(size_t)n;
    }
    if (error) return error;
    text[size]=0;
    return (long)size;
}
static long task_status(int tid,char *text,size_t capacity) {
    long fd=md_task_status_open(tid);
    if (fd<0) return fd;
    long result=read_status((int)fd,text,capacity);
    RAW1(close,fd);
    return result;
}
long md_task_umask_fd(int fd) {
    char text[1024];
    long result=read_status(fd,text,sizeof(text));
    if (result<0) return result;
    for (char *p=text;*p;++p) {
        if ((p!=text && p[-1]!='\n') || !md_prefix(p,"Umask:")) continue;
        p+=6;
        while (*p==' ' || *p=='\t') ++p;
        unsigned mask=0,digits=0;
        while (*p>='0' && *p<='7') {
            if (++digits>4) return -EIO;
            mask=8*mask+(unsigned)(*p++-'0');
        }
        return !digits || *p!='\n' || mask>0777 ? -EIO : (long)mask;
    }
    /* Supported kernels always expose Umask while task->fs exists. exit_fs()
     * can remove it before the pinned task itself disappears from procfs. */
    return -ESRCH;
}
long md_task_umask(int tid) {
    long fd=md_task_status_open(tid);
    if (fd<0) return fd;
    long result=md_task_umask_fd((int)fd);
    RAW1(close,fd);
    return result;
}
long md_task_parent(int tid) {
    char text[1024];
    long result=task_status(tid,text,sizeof(text));
    if (result<0) return result;
    for (char *p=text;*p;++p) {
        if ((p!=text && p[-1]!='\n') || !md_prefix(p,"PPid:")) continue;
        p+=5;
        while (*p==' ' || *p=='\t') ++p;
        unsigned value=0,digits=0;
        while (*p>='0' && *p<='9') {
            if (++digits>9) return -EIO;
            value=10*value+(unsigned)(*p++-'0');
        }
        return !digits || *p!='\n' ? -EIO : (long)value;
    }
    return -ENOTSUP;
}
int md_task_child_class(pid_t tid,pid_t *group,int *exit_signal) {
    char path[64]="/proc/",text[2048];
    md_decimal(path+md_length(path),(unsigned)tid);
    md_copy(path+md_length(path),sizeof(path)-md_length(path),"/stat");
    long fd=RAW4(openat,AT_FDCWD,path,O_RDONLY|O_CLOEXEC,0);
    if (fd<0) return (int)fd;
    long size=read_status((int)fd,text,sizeof(text));
    RAW1(close,fd);
    if (size<0) return (int)size;
    /* comm can contain spaces and parentheses. The final ')' precedes field
     * 3; pgrp and exit_signal are stable proc-stat fields 5 and 38. */
    const char *last=NULL;
    for (const char *p=text;*p;++p) if (*p==')') last=p;
    if (!last) return -EIO;
    const char *p=last+1;
    for (unsigned field=3;field<=38;++field) {
        while (*p==' ') ++p;
        if (!*p || *p=='\n') return -EIO;
        if (field==5 || field==38) {
            unsigned value=0,digits=0;
            while (*p>='0' && *p<='9') {
                unsigned digit=(unsigned)(*p++-'0');
                if (value>(INT32_MAX-digit)/10) return -EIO;
                value=value*10+digit; ++digits;
            }
            if (!digits || (*p!=' ' && *p!='\n' && *p)) return -EIO;
            if (field==5) *group=(pid_t)value; else *exit_signal=(int)value;
        } else while (*p && *p!=' ' && *p!='\n') ++p;
    }
    return 0;
}
long md_task_signal_dispositions(int tid,uint64_t *caught,uint64_t *ignored) {
    char text[8192];
    long result=task_status(tid,text,sizeof(text));
    if (result<0) return result;
    unsigned found=0;
    for (char *p=text;*p;++p) {
        if (p!=text && p[-1]!='\n') continue;
        uint64_t *out=md_prefix(p,"SigCgt:") ? caught : md_prefix(p,"SigIgn:") ? ignored : NULL;
        if (!out) continue;
        p+=7;
        while (*p==' ' || *p=='\t') ++p;
        uint64_t value=0; unsigned digits=0;
        while (*p && *p!='\n') {
            unsigned digit=*p>='0' && *p<='9' ? (unsigned)(*p-'0')
                : *p>='a' && *p<='f' ? (unsigned)(*p-'a'+10) : 16;
            if (digit>=16 || ++digits>16) return -EIO;
            value=(value<<4)|digit; ++p;
        }
        if (!digits || *p!='\n') return -EIO;
        *out=value; found|=out==caught ? 1 : 2;
        if (found==3) return 0;
    }
    return -ENOTSUP;
}
