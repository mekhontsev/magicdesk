#include "identity_transfer.h"
#include "adapter_buffer.h"
#include "interception.h"
#include "raw.h"
#include <errno.h>
#include <linux/capability.h>

static long transfer(unsigned operation,unsigned long offset) {
    return md_guest_buffer_control(MD_BUFFER_IDENTITY,operation,offset);
}

static long capabilities(long nr,const unsigned long *args,union md_identity_transfer *p) {
    long result=md_read_memory(&p->capabilities,(void *)args[0],2*sizeof(uint32_t));
    if (result) return result;
    unsigned version=p->capabilities.version;
    unsigned words=version==_LINUX_CAPABILITY_VERSION_1 ? 1
        : version==_LINUX_CAPABILITY_VERSION_2 || version==_LINUX_CAPABILITY_VERSION_3 ? 2 : 0;
    if (!words) {
        uint32_t supported=_LINUX_CAPABILITY_VERSION_3;
        result=md_write_memory((void *)args[0],&supported,sizeof(supported));
        return result ? result : nr==SYS_capget && !args[1] ? 0 : -EINVAL;
    }
    result=transfer(MD_IDENTITY_PREPARE,0);
    if (result) return result;
    if (nr==SYS_capget)
        return args[1] ? md_write_memory((void *)args[1],p->capabilities.data,words*3*sizeof(uint32_t)) : 0;
    for (unsigned i=0;i<6;++i) p->capabilities.data[i]=0;
    result=md_read_memory(p->capabilities.data,(void *)args[1],words*3*sizeof(uint32_t));
    return result ? result : transfer(MD_IDENTITY_COMMIT,0);
}

static long groups(long nr,const unsigned long *args,union md_identity_transfer *p) {
    long count=transfer(MD_IDENTITY_PREPARE,0);
    if (count<0 || (nr==SYS_getgroups && !args[0])) return count;
    for (unsigned offset=0;offset<(unsigned)count;) {
        unsigned n=(unsigned)count-offset;
        if (n>MD_IDENTITY_TRANSFER_WORDS) n=MD_IDENTITY_TRANSFER_WORDS;
        uintptr_t address=args[1],bytes=(uintptr_t)offset*sizeof(uint32_t);
        if (address>UINTPTR_MAX-bytes-n*sizeof(uint32_t)) return -EFAULT;
        long result;
        if (nr==SYS_setgroups) {
            result=md_read_memory(p->words,(void *)(address+bytes),n*sizeof(uint32_t));
            if (!result) result=transfer(MD_IDENTITY_TRANSFER,offset);
        } else {
            result=transfer(MD_IDENTITY_TRANSFER,offset);
            if (!result) result=md_write_memory((void *)(address+bytes),p->words,n*sizeof(uint32_t));
        }
        if (result) return result;
        offset+=n;
    }
    return nr==SYS_setgroups ? transfer(MD_IDENTITY_COMMIT,0) : count;
}

long md_identity_dispatch(long nr,const unsigned long *args) {
    long address=md_adapter_buffer_take();
    if (address<0) return address;
    union md_identity_transfer *p=(void *)address;
    long result;
    if (nr==SYS_capget || nr==SYS_capset) result=capabilities(nr,args,p);
    else if (nr==SYS_getgroups || nr==SYS_setgroups) result=groups(nr,args,p);
    else if (nr==SYS_getresuid || nr==SYS_getresgid) {
        result=transfer(MD_IDENTITY_PREPARE,0);
        for (unsigned i=0;!result && i<3;++i)
            result=md_write_memory((void *)args[i],p->words+i,sizeof(uint32_t));
    } else {
        uintptr_t input=nr==SYS_prlimit64 ? args[2] : nr==SYS_setrlimit ? args[1] : 0;
        uintptr_t output=nr==SYS_prlimit64 ? args[3] : nr==SYS_getrlimit ? args[1] : 0;
        result=input || nr==SYS_setrlimit ? md_read_memory(&p->limit,(void *)input,sizeof(p->limit)) : 0;
        if (!result) result=transfer(MD_IDENTITY_COMMIT,0);
        if (!result && (output || nr==SYS_getrlimit))
            result=md_write_memory((void *)output,&p->limit,sizeof(p->limit));
    }
    long released=md_adapter_buffer_release();
    return result<0 ? result : released<0 ? released : result;
}
