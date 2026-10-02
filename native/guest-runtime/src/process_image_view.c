#define _GNU_SOURCE
#include "process_image_view.h"
#include "proc_paths.h"
#include "interception.h"
#include "fs_rpc.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/uio.h>
#include <unistd.h>

static int copy(pid_t pid, uintptr_t address, void *data, size_t size) {
    struct iovec local={data,size}, remote={(void *)address,size};
    return process_vm_readv(pid,&local,1,&remote,1,0)==(ssize_t)size ? 0 : -EFAULT;
}
int md_process_image_view(pid_t pid, uintptr_t image, unsigned kind, unsigned flags, const char *endpoint, pid_t caller) {
    if (!image) return -ENOENT;
    if (kind!=MD_PROC_AUXV && kind!=MD_PROC_CMDLINE && kind!=MD_PROC_EXE) return -ENOTSUP;
    int link=!!(flags&MD_PROC_IMAGE_LINK); flags &= ~MD_PROC_IMAGE_LINK;
    if (link && kind!=MD_PROC_EXE) return -EINVAL;
    if (flags&O_DIRECTORY) return -ENOTDIR;
    if ((flags&O_ACCMODE)!=O_RDONLY || (flags&O_TRUNC)) return -EACCES;
    if ((flags&(O_CREAT|O_EXCL))==(O_CREAT|O_EXCL)) return -EEXIST;
    struct md_process_image *view=malloc(sizeof(*view));
    if (!view) return -ENOMEM;
    int r=copy(pid,image,view,sizeof(*view));
    if (!r && (view->argc>1040 || view->auxv_bytes>sizeof(view->auxv) || view->executable_object[32])) r=-EPROTO;
    struct md_fs_request q={.actor=caller,.operation=kind==MD_PROC_EXE && !link ? MD_FS_OPEN_OBJECT : MD_FS_TEMPORARY,
        .directory={-1,-1},.path={view->executable_object,NULL}};
    if (q.operation==MD_FS_TEMPORARY) q.path[0]=NULL;
    else q.flags=flags&~(O_CREAT|O_EXCL|O_NOFOLLOW);
    struct md_fs_response response;
    if (!r) r=(int)md_fs_call(endpoint,5000,&q,&response);
    if (!r) r=response.result.error;
    int fd=r ? -1 : response.result.fd;
    if (!r && q.operation==MD_FS_TEMPORARY) {
        if (kind==MD_PROC_AUXV) {
            if (write(fd,view->auxv,view->auxv_bytes)!=(ssize_t)view->auxv_bytes) r=-EIO;
        } else {
            char bytes[4096];
            for (unsigned i=0; !r && i<(link ? 1 : view->argc); ++i) {
                uintptr_t address=link ? (uintptr_t)view->executable_path : view->argv[i];
                size_t count=link ? sizeof(bytes) : view->arg_lengths[i];
                if (count>sizeof(bytes)) { r=-E2BIG; break; }
                if (link) {
                    count=0;
                    while (count<sizeof(bytes)) {
                        r=copy(pid,address+count,bytes+count,1);
                        if (r || !bytes[count]) break;
                        count++;
                    }
                    if (count==sizeof(bytes)) r=-ENAMETOOLONG;
                } else r=copy(pid,address,bytes,count);
                if (!r && write(fd,bytes,count)!=(ssize_t)count) r=-EIO;
            }
        }
        if (!r) {
            char path[64]; snprintf(path,sizeof(path),"/proc/self/fd/%d",fd);
            int readable=open(path,(flags&~(O_CREAT|O_EXCL|O_NOFOLLOW))|O_CLOEXEC);
            if (readable<0) r=-errno;
            else { close(fd); fd=readable; }
        }
    }
    free(view);
    if (r && fd>=0) close(fd);
    return r ? r : fd;
}
