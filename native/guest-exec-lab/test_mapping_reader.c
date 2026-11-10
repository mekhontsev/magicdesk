#define _GNU_SOURCE
#include "mapping_reader.h"
#include "mapping_observation.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/sysmacros.h>
#include <unistd.h>

static int source(const char *text) {
    int fd=memfd_create("mapping-reader",MFD_CLOEXEC); assert(fd>=0);
    size_t size=strlen(text);
    assert(write(fd,text,size)==(ssize_t)size && lseek(fd,0,SEEK_SET)==0);
    return fd;
}
static void parse(void) {
    struct md_mapping_record v;
    assert(md_mapping_parse("1000-2FFF r-xp 00001000 01:0a 18446744073709551615 /a b (deleted)",0,&v));
    assert(v.first==0x1000 && v.end==0x2fff && v.offset==4096 && v.inode==UINT64_MAX);
    assert(v.major==1 && v.minor==10 && !v.shared && v.protection==(PROT_READ|PROT_EXEC));
    assert(md_mapping_parse("1000-2000 ---s 0 00:00 0",0,&v) && v.shared && !v.protection);
    assert(md_mapping_parse("1000-2000 rw-p 0 00:00 42 /truncated",1,&v));
    assert(md_mapping_parse("1000-2000 r-xp ffffffffffffffff ffffffff:FFFFFFFF 00018446744073709551615",0,&v));
    assert(v.offset==UINT64_MAX && v.major==UINT32_MAX && v.minor==UINT32_MAX && v.inode==UINT64_MAX);
    assert(!md_mapping_parse("1000-2000 rw-p 0 00:00 42",1,&v));
    const char *bad[]={"", "1000", "1000-1000 r-xp 0 00:00 1", "2000-1000 r-xp 0 00:00 1",
        "1000-2000 rwxp 0 00:00 18446744073709551616", "1000-2000 rwxp 10000000000000000 00:00 1",
        "1000-2000 rwxp 0 100000000:00 1", "1000-2000 rwxp 0 00:00 -1",
        "1000-2000 rwxp 0 00:00 12junk", "1000-2000 r-xq 0 00:00 1",
        "1000-2000 rw-p 0 00:00 18446744073709551610a",
        "1000-2000 rw-p 0 00:00 18446744073709551619",
        "1000-2000 rw-p 1000000000000000f 00:00 1",
        "1000-2000 xxxx 0 00:00 1", "1000-2000 r-xp", "VmFlags: rd ex mr mw me"};
    for (size_t i=0;i<sizeof(bad)/sizeof(*bad);++i) assert(!md_mapping_parse(bad[i],0,&v));
}
static void lines(void) {
    size_t size=sizeof(((struct md_mapping_reader *)0)->bytes)*2;
    char *text=malloc(size+128); assert(text);
    const char *header="1000-2000 r-xp 0 00:00 1 /";
    memcpy(text,header,strlen(header)); memset(text+strlen(header),'a',size-strlen(header));
    strcpy(text+size,"\n2000-3000 r--s 1000 00:00 2\n");
    int fd=source(text); free(text);
    struct md_mapping_reader r={.fd=fd}; struct md_mapping_record v;
    assert(md_mapping_next(&r,&v)==1 && v.first==0x1000);
    assert(md_mapping_next(&r,&v)==1 && v.first==0x2000 && v.shared);
    assert(!md_mapping_next(&r,&v)); close(fd);
    fd=source("1000-2000 r-xp 0 00:00 1"); r=(struct md_mapping_reader){.fd=fd};
    assert(md_mapping_next(&r,&v)==-EPROTO); close(fd);
    r=(struct md_mapping_reader){.fd=-1};
    assert(md_mapping_next(&r,&v)==-EBADF);
    char byte;
    assert(md_mapping_line(&r,&byte,0)==-EINVAL);
}
static void observation(void) {
    struct md_mapping_source backing={.device=makedev(1,2),.inode=7};
    int fd=source("1000-2000 rw-s 0000 01:02 7\n2000-3000 r--s 1000 01:02 7\n");
    assert(md_mapping_observe(fd,&backing,0x1800,0x1000,0x800,1)==1);
    assert(!md_mapping_observe(fd,&backing,0x1800,0x1000,0x800,0));
    assert(!md_mapping_observe(fd,&backing,0x1800,0x1000,0,1));
    assert(!md_mapping_observe(fd,&backing,0x1800,0x2000,0x800,1)); close(fd);
    const char *other[]={"1000-2000 rw-p 0 01:02 7\n", "1000-2000 rw-s 0 01:02 8\n",
        "1000-2000 rw-s 0 01:03 7\n", "1000-1800 rw-s 0 01:02 7\n1900-3000 rw-s 900 01:02 7\n"};
    for (size_t i=0;i<sizeof(other)/sizeof(*other);++i) {
        fd=source(other[i]); assert(!md_mapping_observe(fd,&backing,0x1000,0x1000,0,1)); close(fd);
    }
    fd=source("1000-2000 rw-s 0 01:02 18446744073709551623\n");
    assert(md_mapping_observe(fd,&backing,0x1000,0x1000,0,1)==-EPROTO); close(fd);
    fd=source("1000-2000 rw-p 0000 01:02 7\n2000-3000 r--p 1000 01:02 7\n");
    assert(md_mapping_observe(fd,&backing,0x1800,0x1000,0x800,0)==1);
    assert(!md_mapping_observe(fd,&backing,0x1800,0x1000,0x800,1));
    assert(!md_mapping_observe(fd,&backing,0x1800,0x1000,0,0));
    assert(md_mapping_observe(fd,&backing,0x1800,0x1000,0x800,2)==-EINVAL);
    close(fd);
    fd=source("1000-2000 rw-p 0000 01:02 7\n2000-3000 r--s 1000 01:02 7\n");
    assert(!md_mapping_observe(fd,&backing,0x1800,0x1000,0x800,0)); close(fd);
}
int main(void) {
    parse(); lines(); observation();
    int fd=open("/proc/self/maps",O_RDONLY|O_CLOEXEC); assert(fd>=0);
    struct md_mapping_reader r={.fd=fd}; struct md_mapping_record v;
    int result, found=0;
    while ((result=md_mapping_next(&r,&v))>0)
        if (v.first<=(uintptr_t)main && (uintptr_t)main<v.end) {
            assert((v.protection&PROT_EXEC) && !v.shared); found=1;
        }
    assert(!result && found); close(fd);
    puts("PASS mapping reader: bounded lines, exact identity, permissions and overflow");
    return 0;
}
