#define _GNU_SOURCE
#include "mapping_observation.h"
#include "mapping_reader.h"
#include <errno.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <unistd.h>

int md_mapping_source(int fd, struct md_mapping_source *source) {
    if (!source) return -EINVAL;
    struct stat st;
    if (fstat(fd,&st)) return -errno;
    *source=(struct md_mapping_source){.device=st.st_dev,.inode=st.st_ino};
    return 0;
}
int md_mapping_observe(int fd, const struct md_mapping_source *source, uintptr_t address,
        size_t size, uint64_t offset, int shared) {
    if (fd<0 || !source || !size || (shared!=0 && shared!=1)
            || address>UINTPTR_MAX-size || offset>UINT64_MAX-size) return -EINVAL;
    if (lseek(fd,0,SEEK_SET)<0) return -errno;
    uintptr_t cursor=address, end=address+size;
    struct md_mapping_reader reader={.fd=fd}; struct md_mapping_record v;
    int result;
    while ((result=md_mapping_next(&reader,&v))>0) {
        if (v.first>cursor) return 0;
        if (v.end<=cursor) continue;
        uint64_t displacement=cursor-v.first;
        if (v.inode!=source->inode || makedev(v.major,v.minor)!=source->device
                || v.shared!=shared || v.offset>UINT64_MAX-displacement
                || v.offset+displacement!=offset+cursor-address) return 0;
        if (v.end>=end) return 1;
        cursor=v.end;
    }
    return result;
}
