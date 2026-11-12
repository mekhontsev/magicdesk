#define _GNU_SOURCE
#include "proc_control.h"
#include "control_ioctl.h"
#include "descriptor_capture.h"
#include "fd_capture.h"
#include "file_metadata.h"
#include "proc_file.h"
#include "fd_filter.h"
#include "descriptor_calls.h"
#include "adapter_buffer.h"
#include "fs_buffer.h"
#include "interception.h"
#include "linux_abi.h"
#include "raw.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <sys/uio.h>
#include <sys/stat.h>
#include <sys/vfs.h>
#include <sys/sysmacros.h>
#include <linux/stat.h>
#include <linux/magic.h>
#include <linux/nsfs.h>

long md_proc_control_known(int fd) {
#ifdef MD_USE_LIBC
    (void)fd; return 0;
#else
    long kind=md_descriptor_kind(fd);
    if (kind==MD_DESCRIPTOR_FILESYSTEM) return MD_PROC_FILE_CONTROL+1;
    return kind==MD_DESCRIPTOR_ANONYMOUS ? 0 : kind;
#endif
}
static long control_request(void *memory,unsigned operation,int process,unsigned selector,
        unsigned flags,uint64_t view,const char *interface,int publish) {
#ifndef MD_USE_LIBC
    struct md_proc_open_request *q=memory;
    *q=(struct md_proc_open_request){.operation=operation,.process=process,
        .selector=selector,.flags=flags,.view=view};
    if (interface) memcpy(q->interface,interface,sizeof(q->interface));
    long result=md_guest_buffer_control(MD_BUFFER_PROC_OPEN,0,0);
    return !result && publish ? md_guest_buffer_result(flags,0,memory) : result;
#else
    (void)memory; (void)operation; (void)process; (void)selector; (void)flags;
    (void)view; (void)interface; (void)publish; return -ENOTSUP;
#endif
}
static long control_open(unsigned operation,int process,int selector,int flags,uint64_t view,const char *interface) {
#ifndef MD_USE_LIBC
    long memory=md_adapter_buffer_take();
    if (memory<0) return memory;
    long fd=control_request((void *)memory,operation,process,(unsigned)selector,(unsigned)flags,view,interface,1);
    long released=md_adapter_buffer_release();
    if (released<0) { if (fd>=0) RAW1(close,fd); return released; }
    return fd;
#else
    return control_request(NULL,operation,process,(unsigned)selector,(unsigned)flags,view,interface,1);
#endif
}
long md_proc_control_pidfd(void *memory,int fd,unsigned command) {
    return control_request(memory,MD_PROC_FILE_PIDFD_NAMESPACE,fd,command,O_CLOEXEC,0,NULL,1);
}
long md_proc_control_open(const struct md_proc_path *ref, int flags, uint64_t view) {
    enum md_proc_kind kind=ref->kind==MD_PROC_FOREIGN ? ref->foreign_kind : ref->kind;
    return control_open(MD_PROC_FILE_OPEN,ref->process,kind,flags,view,
        md_proc_network_interface(kind) ? ref->interface : NULL);
}
long md_proc_control_reopen(int process, int fd, int flags) {
    return control_open(MD_PROC_FILE_REOPEN,process,fd,flags,0,NULL);
}
long md_proc_control_link_metadata(const struct md_proc_path *ref,long nr,const unsigned long *a,uint64_t mount) {
#ifdef MD_USE_LIBC
    (void)ref; (void)nr; (void)a; (void)mount; return -ENOTSUP;
#else
    int process=ref->process, fd=ref->descriptor;
    enum md_proc_kind kind=ref->kind==MD_PROC_FOREIGN ? ref->foreign_kind : ref->kind;
    struct md_proc_metadata_request query={.process=process,.descriptor=fd,.kind=kind,.effective=1};
    memcpy(query.interface,ref->interface,sizeof(query.interface));
    int access=nr==SYS_faccessat || nr==SYS_faccessat2;
    if (access) {
        if ((unsigned)a[2]&~7U || (nr==SYS_faccessat2 && (unsigned)a[3]&~(MD_AT_EACCESS|AT_EMPTY_PATH|AT_SYMLINK_NOFOLLOW))) return -EINVAL;
        query.access=(uint32_t)a[2]; query.effective=nr==SYS_faccessat2 && (a[3]&MD_AT_EACCESS);
    } else if (nr==SYS_statx) {
        if (a[2]&~(AT_EMPTY_PATH|AT_SYMLINK_NOFOLLOW|AT_NO_AUTOMOUNT|AT_STATX_SYNC_TYPE)
                || (a[2]&AT_STATX_SYNC_TYPE)==AT_STATX_SYNC_TYPE || (a[3]&STATX__RESERVED)) return -EINVAL;
    } else if (nr==SYS_newfstatat && (a[3]&~(AT_EMPTY_PATH|AT_SYMLINK_NOFOLLOW|AT_NO_AUTOMOUNT))) return -EINVAL;
    query.nofollow=(nr==SYS_newfstatat || nr==SYS_faccessat2) ? !!(a[3]&AT_SYMLINK_NOFOLLOW)
        : nr==SYS_statx ? !!(a[2]&AT_SYMLINK_NOFOLLOW) : 0;
    long memory=md_adapter_buffer_take();
    if (memory<0) return memory;
    memcpy((void *)memory,&query,sizeof(query));
    long result=md_guest_buffer_control(MD_BUFFER_PROC_META,0,0);
    if (!result && !access) {
        struct md_proc_metadata_result metadata; memcpy(&metadata,(void *)memory,sizeof(metadata));
        struct stat st; md_fs_info_stat(&metadata.value,&st);
        int namespace=md_proc_file_namespace(metadata.kind);
        if (nr==SYS_fstat || nr==SYS_newfstatat)
            result=md_write_memory((void *)a[nr==SYS_fstat ? 1 : 2],&st,sizeof(st));
        else if (nr==SYS_statx) {
            struct statx value;
            if (query.nofollow && namespace && mount) metadata.value.mount_id=mount;
            md_fs_info_statx(&metadata.value,&value);
            result=md_write_memory((void *)a[4],&value,sizeof(value));
        } else if (nr==SYS_statfs || nr==SYS_fstatfs) {
            struct statfs value={.f_type=metadata.filesystem ? metadata.filesystem : namespace ? NSFS_MAGIC : PROC_SUPER_MAGIC,
                .f_bsize=md_page_size,.f_namelen=255,.f_frsize=md_page_size};
            result=md_write_memory((void *)a[1],&value,sizeof(value));
        } else if (nr==SYS_readlinkat) {
            if (!namespace) result=kind==MD_PROC_FD ? -EXDEV : kind==MD_PROC_NONE ? -ENOENT : -EINVAL;
            else if (kind==MD_PROC_NONE && !S_ISLNK(st.st_mode)) result=-EINVAL;
            else if (!a[3]) result=-EINVAL;
            /* A namespace opened through a bind mount has a retained pathname,
             * unlike an anonymous /proc/PID/ns handle. Resolve it in the VFS. */
            else if (metadata.value.mount_id) result=-EXDEV;
            else {
                char label[64]; md_copy(label,sizeof(label),md_proc_file_namespace_name(metadata.kind));
                md_append(label,sizeof(label),":["); md_decimal(label+md_length(label),st.st_ino);
                md_append(label,sizeof(label),"]");
                size_t length=md_length(label); if (length>a[3]) length=a[3];
                result=md_write_memory((void *)a[2],label,length);
                if (!result) result=(long)length;
            }
        }
        else if (nr==SYS_fchdir && md_proc_file_directory(metadata.kind)) result=RAW1(fchdir,fd);
        else if (nr==SYS_chdir && md_proc_file_directory(metadata.kind)) {
            char path[64]="/proc/"; md_decimal(path+md_length(path),(unsigned)process);
            if (metadata.kind==MD_PROC_FILE_NS_DIRECTORY) md_append(path,sizeof(path),"/ns");
            result=RAW1(chdir,path);
        }
        else if (nr==SYS_getdents64 || nr==SYS_chdir || nr==SYS_fchdir) result=-ENOTDIR;
        /* Mounted controls own their attributes in the VFS. Proc links and
         * descriptor operations must retain that mount instead of mutating
         * the native carrier or treating it as an immutable proc entry. */
        else if (metadata.kind==MD_PROC_FILE_CONTROL && metadata.value.mount_id
                && (nr==SYS_fchmod || nr==SYS_fchmodat || nr==SYS_fchmodat2
                    || nr==SYS_fchown || nr==SYS_fchownat || nr==SYS_utimensat
                    || nr==SYS_inotify_add_watch)) result=-EXDEV;
        else result=-EPERM;
    }
    long released=md_adapter_buffer_release();
    return result ? result : released;
#endif
}
long md_proc_control_metadata(const struct md_proc_path *ref,long nr,const unsigned long *a) {
    return md_proc_control_link_metadata(ref,nr,a,0);
}
long md_proc_control_call(long nr, const unsigned long *a, int kind) {
#ifdef MD_USE_LIBC
    (void)nr; (void)a; (void)kind; return MD_CALL_NATIVE;
#else
    if (nr==SYS_mmap) {
        long memory=md_adapter_buffer_take();
        if (memory<0) return memory;
        long result=md_guest_buffer_control(MD_BUFFER_CONTROL_MAP,0,0);
        if (!result) result=md_guest_buffer_control(MD_BUFFER_MEMORY_MAP,0,0);
        long released=md_adapter_buffer_release();
        return result<0 ? result : released<0 ? released : result;
    }
    if (nr==SYS_ioctl) {
        if (!md_proc_file_namespace((enum md_proc_file_kind)kind)) {
            struct md_adapter_input input=md_adapter_buffer_begin(MD_BUFFER_CONTROL_IOCTL_LAYOUT,0);
            if (!input.buffer) return input.value;
            long memory=(long)input.buffer,result=input.value;
            struct md_control_ioctl layout={0};
            if (!result) {
                memcpy(&layout,input.buffer,sizeof(layout));
                result=md_control_ioctl_size(&layout);
                if (result>=0) result=0;
            }
            size_t size=layout.size;
            unsigned direction=layout.direction;
            uintptr_t addresses[MD_CONTROL_IOCTL_FIELDS]={0};
            size_t lengths[MD_CONTROL_IOCTL_FIELDS]={0};
            if (!result) {
                memset((void *)memory,0,(size_t)md_control_ioctl_size(&layout));
                if (direction&MD_CONTROL_IN) result=md_read_memory((void *)memory,(void *)a[2],size);
            }
            if (!result && (direction&MD_CONTROL_STRING)) {
                result=md_read_string((void *)memory,size,(void *)a[2]);
                if (result>=0) result=0;
            }
            size_t offset=size;
            for (unsigned i=0;!result && i<layout.fields;++i) {
                const struct md_control_ioctl_field *f=&layout.field[i];
                memcpy(&addresses[i],(char *)memory+f->pointer,sizeof(addresses[i]));
                lengths[i]=addresses[i] ? md_control_ioctl_field_size(&layout,i,(void *)memory) : 0;
                if (lengths[i] && (f->direction&MD_CONTROL_IN))
                    result=md_read_memory((char *)memory+offset,(void *)addresses[i],lengths[i]);
                offset+=f->capacity;
            }
            if (!result && (direction&MD_CONTROL_FD)) {
                int number; memcpy(&number,(char *)memory+layout.descriptor,sizeof(number));
                struct md_fd_capture_frame *frame=(void *)((char *)memory+md_control_ioctl_capture_offset(&layout));
                const struct md_descriptor_capture_protocol protocol={.admit=MD_BUFFER_CONTROL_CAPTURE_ADMIT,
                    .ready=MD_BUFFER_CONTROL_CAPTURE_READY,.sent=MD_BUFFER_CONTROL_CAPTURE_SENT,
                    .observed=MD_BUFFER_CONTROL_CAPTURE_OBSERVED};
                result=md_guest_buffer_control(MD_BUFFER_CONTROL_CAPTURE,0,0);
                if (result>0) result=md_descriptor_capture_inputs(result,frame,&number,1,&protocol);
            }
            if (!result && !md_control_ioctl_constant(&layout,(void *)memory))
                result=md_guest_buffer_control(MD_BUFFER_CONTROL_IOCTL,0,0);
            offset=size;
            for (unsigned i=0;result>=0 && i<layout.fields;++i) {
                const struct md_control_ioctl_field *f=&layout.field[i];
                if (lengths[i] && (f->direction&MD_CONTROL_OUT)) {
                    size_t count=md_control_ioctl_field_size(&layout,i,(void *)memory);
                    if (count>lengths[i]) count=lengths[i];
                    long error=md_write_memory((void *)addresses[i],(char *)memory+offset,count);
                    if (error) result=error;
                }
                offset+=f->capacity;
            }
            if (result>=0 && (direction&MD_CONTROL_OUT)) {
                long error=md_write_memory((void *)a[2],(void *)memory,size);
                if (error) result=error;
            }
            return md_adapter_buffer_finish(0,result);
        }
        long memory=md_adapter_buffer_take();
        if (memory<0) return memory;
        int publish=a[1]==NS_GET_USERNS || a[1]==NS_GET_PARENT;
        long value=control_request((void *)memory,MD_PROC_FILE_NAMESPACE_QUERY,(int)a[0],
            (unsigned)a[1],publish ? O_CLOEXEC : 0,0,NULL,publish);
        long released=md_adapter_buffer_release();
        if (released<0) { if (publish && value>=0) RAW1(close,value); return released; }
        if (value>=0 && a[1]==NS_GET_OWNER_UID) {
            uint32_t uid=(uint32_t)value;
            return md_write_memory((void *)a[2],&uid,sizeof(uid));
        }
        return value;
    }
    int sysctl=md_proc_file_sysctl((enum md_proc_file_kind)kind);
    int oom=md_proc_file_oom((enum md_proc_file_kind)kind);
    int control=kind==MD_PROC_FILE_CONTROL;
    struct md_proc_io_spec spec;
    long decoded=md_proc_io_decode(nr,a,&spec);
    if (decoded) return decoded;
    int writing=spec.writing, vector=spec.vector;
    if (spec.splice) {
        long source=md_proc_control_known((int)a[0]);
        if (source<0) return source;
        long target=md_proc_control_known((int)a[2]);
        if (target<0) return target;
        if (source && target) return -EXDEV;
        writing=!source;
        int pipe=(int)a[writing ? 0 : 2]; struct stat st;
        long result=RAW2(fstat,pipe,&st);
        if (result<0) return result;
        if (!S_ISFIFO(st.st_mode)) return -EINVAL;
        if (!a[4]) return 0;
        long memory=md_adapter_buffer_take(); if (memory<0) return memory;
        /* The caller performs pipe waits in the kernel, not in the filesystem
         * worker. A private staging OFD lets splice retain NONBLOCK semantics. */
        long staging=RAW2(memfd_create,"shroot-splice",1);
        size_t length=a[4]<MD_CONTROL_IO_MAX ? a[4] : MD_CONTROL_IO_MAX;
        result=staging<0 ? staging : 0;
        if (!result && writing) {
            result=RAW6(splice,pipe,0,staging,0,length,a[5]);
            if (result>0) {
                length=(size_t)result;
                result=RAW3(lseek,staging,0,SEEK_SET);
                if (!result) { result=RAW3(read,staging,memory,length); if (result==(long)length) result=0; else if (result>=0) result=-EIO; }
            } else if (!result) length=0;
        }
        long offered=-1;
        if (!result && length) offered=result=md_guest_buffer_control(MD_BUFFER_PROC_IO,length,0);
        if (offered>0 && !writing) {
            result=RAW3(write,staging,memory,(size_t)offered);
            if (result==offered) {
                result=RAW3(lseek,staging,0,SEEK_SET);
                if (!result) result=RAW6(splice,staging,0,pipe,0,(size_t)offered,a[5]);
            } else if (result>=0) result=-EIO;
        }
        if (offered>=0) {
            long acknowledged=md_guest_buffer_control(MD_BUFFER_PROC_ACK,writing ? 0 : result,0);
            if (acknowledged) result=acknowledged;
        }
        if (staging>=0) RAW1(close,staging);
        long released=md_adapter_buffer_release();
        return result<0 ? result : released<0 ? released : result;
    }
    struct iovec vectors[MD_PROC_IO_VECTORS]; unsigned count=1;
    if (spec.seek || spec.command) count=0;
    else if (!vector) vectors[0]=(struct iovec){(void *)a[1],a[2]};
    else {
        if (a[2]>MD_PROC_IO_VECTORS) return -EINVAL;
        count=(unsigned)a[2];
        long result=md_read_memory(vectors,(void *)a[1],count*sizeof(*vectors));
        if (result) return result;
    }
    size_t total=0;
    long sized=md_proc_io_vector_size(vectors,count,&total);
    if (sized) return sized;
    if (vector && !total) return 0;
    /* Legacy proc writev calls the write method for each vector. A successful
     * map write consumes its one publication; another vector cannot be joined
     * into the same map or replay that publication. */
    if (writing && vector && total && !sysctl && !control) {
        unsigned first=0;
        while (first<count && !vectors[first].iov_len) ++first;
        vectors[0]=vectors[first]; count=1; total=vectors[0].iov_len;
    }
    /* Retain the original count for request validation; the proc handler only
     * consumes its bounded prefix, including on oversized writes. */
    if (writing && oom && total>31) vectors[0].iov_len=31;
    _Static_assert(sizeof(struct md_fs_buffer_frame)>=MD_CONTROL_IO_MAX,"control IO fits adapter buffer");
    if (writing && !sysctl && !control && !oom && total>md_page_size-1) return -EINVAL;
    long memory, result=0;
    int ready=!writing;
    if (writing) {
        memory=md_adapter_buffer_take();
        if (memory<0) return memory;
    } else {
        struct md_adapter_input input=md_adapter_buffer_start(total);
        if (!input.buffer) return input.value;
        memory=(long)input.buffer; result=input.value;
    }
    size_t copied=0;
    if (writing) for (unsigned i=0;i<count;++i) {
        if (!sysctl) {
            size_t length=vectors[i].iov_len;
            if (control && length>MD_CONTROL_IO_MAX-copied) length=MD_CONTROL_IO_MAX-copied;
            result=md_read_memory((char *)memory+copied,vectors[i].iov_base,length);
            if (result) break;
            copied+=length;
            if (control && copied==MD_CONTROL_IO_MAX) break;
        } else {
            /* The string handler uses at most 64 bytes, but Linux validates
             * every input vector before publishing a truncated sysctl value. */
            char chunk[512];
            for (size_t offset=0;offset<vectors[i].iov_len;) {
                size_t size=vectors[i].iov_len-offset;
                if (size>sizeof(chunk)) size=sizeof(chunk);
                result=md_read_memory(chunk,(char *)vectors[i].iov_base+offset,size);
                if (result) break;
                size_t retained=copied<64 ? 64-copied : 0;
                if (retained>size) retained=size;
                if (retained) memcpy((char *)memory+copied,chunk,retained);
                copied+=size; offset+=size;
            }
            if (result) break;
        }
    }
    size_t delivered=0, vector_offset=0;
    unsigned at=0;
    int more=0;
    do {
        if (!result && !ready) result=md_guest_buffer_control(MD_BUFFER_PROC_IO,total-delivered,0);
        ready=0;
        more=!writing && !spec.seek && !spec.command && !spec.directory
            && result>=0 && (result&MD_BUFFER_PROC_MORE);
        if (more) result&=~MD_BUFFER_PROC_MORE;
        long offered=result;
        copied=0;
        if (!writing && !spec.seek && !spec.command && result>0) {
            size_t remaining=(size_t)result;
            while (at<count && remaining) {
                size_t n=vectors[at].iov_len-vector_offset;
                if (n>remaining) n=remaining;
                long error=md_write_memory((char *)vectors[at].iov_base+vector_offset,
                    (char *)memory+copied,n);
                if (error) { result=copied ? (long)copied : error; break; }
                copied+=n; remaining-=n; vector_offset+=n;
                if (vector_offset==vectors[at].iov_len) { ++at; vector_offset=0; }
            }
            delivered+=copied;
        }
        more=more && result==offered && result>0;
        if (offered>=0) {
            long copied_result=writing || spec.seek || spec.command ? 0 : result;
            long finished;
            if (more) finished=md_guest_buffer_control(MD_BUFFER_PROC_ACK,(unsigned long)copied_result,1);
            else return md_adapter_buffer_finish(copied_result,delivered ? (long)delivered : result);
            if (finished) { result=finished; more=0; }
        }
        if (more) result=0;
    } while (more);
    if (delivered) result=(long)delivered;
    long released=md_adapter_buffer_release();
    return result<0 ? result : released<0 ? released : result;
#endif
}
