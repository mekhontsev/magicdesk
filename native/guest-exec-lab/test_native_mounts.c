#define _GNU_SOURCE
#include "native_mounts.h"
#include <assert.h>
#include <errno.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/sysmacros.h>
#include <unistd.h>

static FILE *source;
static unsigned opens,seeks,observations,change_at;
static int event,poll_error,seek_error,open_error,mock=1;
static const char initial[]=
    "1 1 0:1 / / rw - rootfs root rw\n"
    "2 1 0:2 / /proc rw - proc proc rw\n"
    "3 1 0:3 / /dev rw - tmpfs tmpfs rw\n"
    "4 3 0:4 / /dev/shm rw - tmpfs tmpfs rw\n"
    "5 1 0:5 / /device rw - tmpfs tmpfs rw\n"
    "6 1 253:17 / /data rw - f2fs /dev/block/private rw\n"
    "7 1 253:17 /bind /hidden rw - f2fs /dev/block/private rw\n";
FILE *__real_fopen(const char *,const char *);
int __real_fseek(FILE *,long,int);
int __real_poll(struct pollfd *,nfds_t,int);
FILE *__wrap_fopen(const char *path,const char *mode) {
    if (!mock || strcmp(path,"/proc/self/mountinfo")) return __real_fopen(path,mode);
    ++opens;
    if (open_error) { errno=open_error; return NULL; }
    int fd=memfd_create("mount-snapshot-test",MFD_CLOEXEC); assert(fd>=0);
    source=fdopen(fd,"w+"); assert(source);
    assert(fwrite(initial,1,sizeof(initial)-1,source)==sizeof(initial)-1);
    assert(!fflush(source)); return source;
}
int __wrap_fseek(FILE *stream,long offset,int whence) {
    if (mock && stream==source) {
        ++seeks;
        if (seek_error) { errno=seek_error; return -1; }
    }
    return __real_fseek(stream,offset,whence);
}
int __wrap_poll(struct pollfd *p,nfds_t count,int timeout) {
    if (!mock || !source || count!=1 || p->fd!=fileno(source)) return __real_poll(p,count,timeout);
    assert(timeout==0 && p->events==POLLPRI);
    ++observations;
    if (poll_error) { errno=poll_error; return -1; }
    p->revents=event || observations==change_at ? POLLPRI|POLLERR : 0;
    event=0; return !!p->revents;
}
static void replace(const char *text) {
    size_t size=strlen(text);
    assert(!ftruncate(fileno(source),0));
    assert(pwrite(fileno(source),text,size,0)==(ssize_t)size);
}
int main(void) {
    struct md_native_mounts *snapshot=NULL;
    const char *bytes=NULL; size_t size=0;
    open_error=EACCES;
    assert(md_native_mounts_read(&snapshot,&bytes,&size)==-EACCES && !snapshot);
    open_error=0;
    assert(!md_native_mounts_read(&snapshot,&bytes,&size));
    assert(opens==2 && seeks==1 && size==strlen(bytes));
    assert(strstr(bytes,"/proc ") && strstr(bytes,"/dev "));
    assert(!strstr(bytes,"/dev/shm") && !strstr(bytes,"/device") && !strstr(bytes,"rootfs"));
    assert(!strstr(bytes,"/data") && !strstr(bytes,"/hidden") && !strstr(bytes,"private"));
    assert(!strcmp(md_native_mounts_type(snapshot,makedev(253,17)),"f2fs"));
    assert(!strcmp(md_native_mounts_type(snapshot,makedev(0,1)),"rootfs"));
    assert(!md_native_mounts_type(snapshot,makedev(9,9)));
    const char *saved=bytes;
    for (unsigned i=0;i<50;++i) {
        assert(!md_native_mounts_read(&snapshot,&bytes,&size));
        assert(bytes==saved && seeks==1 && opens==2);
    }
    replace("8 3 0:8 / /dev/pts rw - devpts devpts rw\n"); event=1;
    assert(!md_native_mounts_read(&snapshot,&bytes,&size) && seeks==2);
    assert(strstr(bytes,"/dev/pts") && !strstr(bytes,"/proc"));
    assert(!md_native_mounts_type(snapshot,makedev(253,17)));
    assert(!strcmp(md_native_mounts_type(snapshot,makedev(0,8)),"devpts"));
    event=1; change_at=observations+2;
    assert(!md_native_mounts_read(&snapshot,&bytes,&size) && seeks==3);
    assert(!md_native_mounts_read(&snapshot,&bytes,&size) && seeks==4);
    assert(!md_native_mounts_read(&snapshot,&bytes,&size) && seeks==4);
    poll_error=EINTR;
    assert(md_native_mounts_read(&snapshot,&bytes,&size)==-EINTR);
    poll_error=0;
    assert(!md_native_mounts_read(&snapshot,&bytes,&size) && seeks==5);
    event=1; seek_error=EIO;
    assert(md_native_mounts_read(&snapshot,&bytes,&size)==-EIO);
    seek_error=0;
    assert(!md_native_mounts_read(&snapshot,&bytes,&size) && seeks==7);
    replace("invalid\n"); event=1;
    assert(md_native_mounts_read(&snapshot,&bytes,&size)==-EPROTO);
    replace("");
    assert(!md_native_mounts_read(&snapshot,&bytes,&size) && !size);
    assert(!md_native_mounts_type(snapshot,makedev(0,8)));
    int fd=fileno(source);
    md_native_mounts_release(snapshot);
    assert(read(fd,&fd,1)<0 && errno==EBADF);
    mock=0; snapshot=NULL;
    assert(!md_native_mounts_read(&snapshot,&bytes,&size) && strstr(bytes," /proc "));
    assert(!md_native_mounts_read(&snapshot,&bytes,&size));
    md_native_mounts_release(snapshot); md_native_mounts_release(NULL);
    puts("PASS native mount snapshots: reuse, event invalidation, mid-read change, error recovery, teardown and kernel source");
}
