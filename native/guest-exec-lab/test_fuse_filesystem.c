#define _GNU_SOURCE
#include "fs_mount_internal.h"
#include "fs_continuation.h"
#include "fuse_device.h"
#include "fuse_transport.h"
#include "fuse_view_internal.h"
#include "proc_file.h"
#include "inode_watch.h"
#include "linux_abi.h"
#include "image_identity.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/magic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/inotify.h>
#include <sys/wait.h>
#include <signal.h>
#include <unistd.h>

static unsigned requests;
static unsigned lookups,getattrs;
static uint64_t entry_seconds;
static uint32_t entry_nanos;
static uint64_t attr_seconds;
static uint32_t attr_nanos;
static mode_t root_mode=0755,file_mode=0644;
static unsigned releases;
static unsigned flushes;
static int flush_error;
static int write_error;
static int read_error;
static unsigned read_requests,open_flags;
static int open_error;
static unsigned write_limit,defer_opcode;
static unsigned char deferred_reply[512];
static size_t deferred_size;
static char contents[32768]="initial contents";
static size_t content_size=42;
static struct { char name[256]; uint64_t node; mode_t mode; } entries[16];
static uint64_t next_node=10;
static char attribute[256];
static size_t attribute_size;
static int attribute_present,malformed_names;
static int capture(void *context,const struct md_control_file_ops *ops,void *node,
        const struct md_identity *id,int flags,struct md_fs_anchor *origin) {
    (void)id; (void)origin;
    void *opened=ops->open(node,&(struct md_control_opener){.identity=id},flags); if (!opened) return -errno;
    *(struct md_control_file *)context=(struct md_control_file){ops,opened}; return 0;
}
static struct fuse_attr attr(uint64_t node) {
    for (unsigned i=0;i<16;++i) if (entries[i].node==node)
        return (struct fuse_attr){.ino=node,.mode=entries[i].mode,
            .nlink=S_ISDIR(entries[i].mode) ? 2 : 1,.blksize=4096};
    return (struct fuse_attr){.ino=node,.mode=(node==1 ? S_IFDIR|root_mode : node==3 ? S_IFLNK|0777 : S_IFREG|file_mode),
        .nlink=1,.uid=0,.gid=0,.size=node==2 ? content_size : 0,.blksize=4096};
}
static unsigned serve(struct md_fuse_connection *connection) {
    union { uint64_t align; char data[65536]; } input,output;
    long n=md_fuse_connection_read(connection,input.data,sizeof(input.data));
    if (n==-EAGAIN) return 0;
    assert(n>=(long)sizeof(struct fuse_in_header));
    struct fuse_in_header *h=(void *)input.data;
    assert(h->len==(unsigned)n && !h->uid && !h->gid);
    md_fuse_connection_read_commit(connection,n); ++requests;
    struct fuse_out_header *out=(void *)output.data;
    *out=(struct fuse_out_header){.len=sizeof(*out),.unique=h->unique};
    void *payload=out+1;
    switch (h->opcode) {
    case FUSE_INIT: {
        struct fuse_init_out init={.major=7,.minor=38,.max_write=16384};
        memcpy(payload,&init,sizeof(init)); out->len+=sizeof(init); break;
    }
    case FUSE_LOOKUP: {
        ++lookups;
        const char *name=(const char *)(h+1);
        struct fuse_entry_out entry={.generation=1,.entry_valid=entry_seconds,.entry_valid_nsec=entry_nanos,
            .attr_valid=attr_seconds,.attr_valid_nsec=attr_nanos};
        entry.nodeid=!strcmp(name,"file") ? 2 : !strcmp(name,"link") ? 3 : 0;
        if (!strncmp(name,"bulk-",5)) entry.nodeid=1000+strtoul(name+5,NULL,10);
        for (unsigned i=0;i<16;++i) if (!strcmp(entries[i].name,name)) entry.nodeid=entries[i].node;
        if (!entry.nodeid) out->error=-ENOENT;
        else { entry.attr=attr(entry.nodeid); memcpy(payload,&entry,sizeof(entry)); out->len+=sizeof(entry); }
        break;
    }
    case FUSE_GETATTR: {
        ++getattrs;
        struct fuse_attr_out result={.attr=attr(h->nodeid),.attr_valid=attr_seconds,.attr_valid_nsec=attr_nanos};
        memcpy(payload,&result,sizeof(result)); out->len+=sizeof(result); break;
    }
    case FUSE_READLINK: memcpy(payload,"file",4); out->len+=4; break;
    case FUSE_CREATE: case FUSE_MKDIR: case FUSE_SYMLINK: case FUSE_LINK: {
        const char *name=(const void *)(h+1); mode_t mode=0; uint64_t node=next_node++;
        if (h->opcode==FUSE_CREATE) { const struct fuse_create_in *in=(const void *)name;
            mode=in->mode; name=(const void *)(in+1); }
        else if (h->opcode==FUSE_MKDIR) { const struct fuse_mkdir_in *in=(const void *)name;
            mode=in->mode; name=(const void *)(in+1); }
        else if (h->opcode==FUSE_LINK) { const struct fuse_link_in *in=(const void *)name;
            node=in->oldnodeid; mode=attr(node).mode; name=(const void *)(in+1); }
        else mode=S_IFLNK|0777;
        unsigned slot=0; while (slot<16 && entries[slot].node) ++slot; assert(slot<16);
        entries[slot].node=node; entries[slot].mode=mode; strcpy(entries[slot].name,name);
        struct fuse_entry_out entry={.nodeid=node,.generation=1,.attr=attr(node)};
        memcpy(payload,&entry,sizeof(entry)); out->len+=sizeof(entry);
        if (h->opcode==FUSE_CREATE) { struct fuse_open_out opened={.fh=node*10};
            memcpy(output.data+out->len,&opened,sizeof(opened)); out->len+=sizeof(opened); }
        break;
    }
    case FUSE_UNLINK: case FUSE_RMDIR: {
        const char *name=(const void *)(h+1); int found=0;
        for (unsigned i=0;i<16;++i) if (entries[i].node && !strcmp(entries[i].name,name)) {
            entries[i].node=0; *entries[i].name=0; found=1; break;
        }
        if (!found) out->error=-ENOENT;
        break;
    }
    case FUSE_RENAME: case FUSE_RENAME2: {
        const char *name=(const char *)(h+1)+(h->opcode==FUSE_RENAME ? sizeof(struct fuse_rename_in) : sizeof(struct fuse_rename2_in));
        const char *target=name+strlen(name)+1; int source=-1,destination=-1;
        for (unsigned i=0;i<16;++i) if (entries[i].node) {
            if (!strcmp(entries[i].name,name)) source=i;
            if (!strcmp(entries[i].name,target)) destination=i;
        }
        unsigned flags=h->opcode==FUSE_RENAME2 ? ((const struct fuse_rename2_in *)(h+1))->flags : 0;
        if (source<0 || ((flags&RENAME_EXCHANGE) && destination<0)) out->error=-ENOENT;
        else if (source!=destination) {
            if (destination>=0) {
                if (flags&RENAME_EXCHANGE) strcpy(entries[destination].name,name);
                else { entries[destination].node=0; *entries[destination].name=0; }
            }
            strcpy(entries[source].name,target);
        }
        break;
    }
    case FUSE_SETATTR: {
        const struct fuse_setattr_in *in=(const void *)(h+1);
        if (in->valid&FATTR_SIZE) {
            assert(in->fh==h->nodeid*10 && (in->valid&FATTR_FH) && in->size<=sizeof(contents));
            content_size=in->size;
        }
        for (unsigned i=0;i<16;++i) if (entries[i].node==h->nodeid && (in->valid&FATTR_MODE)) entries[i].mode=in->mode;
        struct fuse_attr_out result={.attr=attr(h->nodeid)};
        if (in->valid&FATTR_SIZE) result.attr.size=in->size;
        memcpy(payload,&result,sizeof(result)); out->len+=sizeof(result); break;
    }
    case FUSE_FSYNC: case FUSE_FSYNCDIR: {
        const struct fuse_fsync_in *in=(const void *)(h+1);
        assert(in->fh==h->nodeid*10 && in->fsync_flags<=1);
        if (in->fsync_flags) out->error=-ENOSYS;
        break;
    }
    case FUSE_ACCESS: break;
    case FUSE_SETXATTR: {
        const uint32_t *words=(const void *)(h+1);
        const char *name=(const char *)(h+1)+8;
        assert(!strcmp(name,"user.test") && words[0]<=sizeof(attribute));
        if ((words[1]&1) && attribute_present) out->error=-EEXIST;
        else if ((words[1]&2) && !attribute_present) out->error=-ENODATA;
        else { attribute_size=words[0]; attribute_present=1;
            memcpy(attribute,name+strlen(name)+1,attribute_size); }
        break;
    }
    case FUSE_GETXATTR: case FUSE_LISTXATTR: {
        const struct fuse_getxattr_in *in=(const void *)(h+1);
        int list=h->opcode==FUSE_LISTXATTR;
        const char *name=(const void *)(in+1);
        if (!list && !strcmp(name,"user.unsupported")) { out->error=-ENOSYS; break; }
        if (!list && !attribute_present) { out->error=-ENODATA; break; }
        size_t size=list ? (attribute_present ? sizeof("user.test") : 0) : attribute_size;
        if (!in->size) { struct fuse_getxattr_out result={.size=size};
            memcpy(payload,&result,sizeof(result)); out->len+=sizeof(result); }
        else if (in->size<size) out->error=-ERANGE;
        else { memcpy(payload,list ? "user.test" : attribute,size); out->len+=size;
            if (list && malformed_names && size) ((char *)payload)[size-1]='x'; }
        break;
    }
    case FUSE_REMOVEXATTR:
        if (!attribute_present) out->error=-ENODATA;
        else attribute_present=0;
        break;
    case FUSE_STATFS: {
        struct fuse_statfs_out result={.st={.blocks=100,.bfree=50,.bavail=40,.files=10,.ffree=5,.bsize=4096,.namelen=255,.frsize=4096}};
        memcpy(payload,&result,sizeof(result)); out->len+=sizeof(result); break;
    }
    case FUSE_OPEN: case FUSE_OPENDIR: {
        if (open_error) { out->error=open_error; break; }
        struct fuse_open_out result={.fh=h->nodeid*10,.open_flags=open_flags};
        memcpy(payload,&result,sizeof(result)); out->len+=sizeof(result); break;
    }
    case FUSE_READ: {
        ++read_requests;
        const struct fuse_read_in *in=(const void *)(h+1);
        assert(in->fh==20 && in->size<=16384);
        if (read_error) { out->error=read_error; break; }
        size_t size=in->offset>=content_size ? 0 : content_size-in->offset;
        if (size>in->size) size=in->size;
        memcpy(payload,contents+((in->offset<content_size) ? in->offset : 0),size); out->len+=size; break;
    }
    case FUSE_WRITE: {
        const struct fuse_write_in *in=(const void *)(h+1);
        assert(in->fh==20 && in->size<=16384 && in->offset+in->size<=sizeof(contents));
        assert(n==(long)(sizeof(*h)+sizeof(*in)+in->size));
        if (write_error) { out->error=write_error; break; }
        if (in->flags&O_APPEND) assert(in->offset==content_size);
        unsigned written=write_limit && write_limit<in->size ? write_limit : in->size;
        memcpy(contents+in->offset,in+1,written);
        if (content_size<in->offset+written) content_size=in->offset+written;
        struct fuse_write_out result={.size=written};
        memcpy(payload,&result,sizeof(result)); out->len+=sizeof(result); break;
    }
    case FUSE_READDIR: {
        const struct fuse_read_in *in=(const void *)(h+1);
        const char *names[]={".","..","file","link"};
        for (uint64_t i=in->offset;i<4;++i) {
            struct fuse_dirent *d=(void *)(output.data+out->len);
            *d=(struct fuse_dirent){.ino=i<2 ? 1 : i,.off=i+1,.namelen=strlen(names[i]),.type=i<2 ? 4 : i==2 ? 8 : 10};
            memcpy(d->name,names[i],d->namelen); out->len+=FUSE_DIRENT_SIZE(d);
        }
        break;
    }
    case FUSE_FLUSH: {
        const struct fuse_flush_in *in=(const void *)(h+1);
        assert(in->fh==h->nodeid*10);
        ++flushes; out->error=flush_error; break;
    }
    case FUSE_RELEASE: case FUSE_RELEASEDIR: ++releases; break;
    case FUSE_FORGET: return h->opcode;
    default: assert(!"unexpected FUSE request");
    }
    if (h->opcode==defer_opcode) {
        assert(!deferred_size && out->len<=sizeof(deferred_reply));
        deferred_size=out->len; memcpy(deferred_reply,output.data,deferred_size); defer_opcode=0;
    } else assert(md_fuse_connection_write(connection,output.data,out->len)==out->len);
    return h->opcode;
}
static struct md_fs_result run_request(struct md_fs_context *context,struct md_fuse_connection *connection,
        const struct md_fs_request *q,struct md_fs_output output) {
    struct md_fs_continuation *pending=NULL;
    output.continuation=&pending;
    struct md_fs_result r; md_fs_context_execute(context,q,&r,&output);
    for (unsigned step=0;r.error==-EINPROGRESS;++step) {
        assert(step<64 && pending);
        if (!pending->ready(pending)) assert(serve(connection));
        assert(pending->ready(pending));
        struct md_fs_continuation *old=pending; pending=NULL;
        md_fs_context_resume(context,old,&r,&output);
        assert(md_fs_continuation_pending(old,&pending,&r)==(r.error==-EINPROGRESS));
    }
    assert(!pending); return r;
}
static struct md_fs_result run(struct md_fs_context *context,struct md_fuse_connection *connection,
        unsigned operation,const char *path,unsigned flags,void *data,size_t capacity) {
    struct md_fs_request q={.operation=operation,.directory={-1,-1},.path={path,NULL},.flags=flags};
    return run_request(context,connection,&q,(struct md_fs_output){.data=data,.capacity=capacity});
}
static int capture_file(void *context,const struct md_control_file_ops *ops,void *node,
        const struct md_identity *id,int flags,struct md_fs_anchor *origin) {
    (void)origin;
    struct md_proc_file *file=md_proc_file_open_control(ops,node,&(struct md_control_opener){.identity=id},flags);
    if (!file) return -errno;
    *(struct md_proc_file **)context=file; return 0;
}
static long io_buffer(struct md_proc_file *file,struct md_fs_context *context,struct md_fuse_connection *connection,
        enum md_proc_file_operation operation,void *data,size_t size,size_t capacity,long acknowledge) {
    struct md_proc_file_io io={0};
    assert(md_proc_file_io_begin(&io,file,operation,0,0,0,size,NULL)==1);
    io.actor=(struct md_control_actor){.identity=&context->task.identity};
    long result=md_proc_file_io_execute(&io,&context->task.identity,NULL,data,capacity,4096);
    assert(result==-EINPROGRESS && io.pending);
    for (unsigned step=0; result==-EINPROGRESS; ++step) {
        assert(step<8 && serve(connection));
        result=md_proc_file_io_execute(&io,&context->task.identity,NULL,data,capacity,4096);
    }
    assert(result>=0 && !io.pending);
    assert(!md_proc_file_io_offer(&io));
    assert(!md_proc_file_io_finish(&io,acknowledge<0 ? result : acknowledge));
    return result;
}
static long io(struct md_proc_file *file,struct md_fs_context *context,struct md_fuse_connection *connection,
        enum md_proc_file_operation operation,void *data,size_t size,long acknowledge) {
    return io_buffer(file,context,connection,operation,data,size,size,acknowledge);
}
static long position(struct md_proc_file *file) {
    struct md_proc_file_io io={0};
    assert(md_proc_file_io_begin(&io,file,MD_PROC_FILE_SEEK,0,0,SEEK_CUR,0,NULL)==1);
    long result=md_proc_file_io_execute(&io,NULL,NULL,NULL,0,4096);
    assert(!md_proc_file_io_finish(&io,0)); return result;
}
static void command(struct md_proc_file *file,struct md_fs_context *context,
        struct md_fuse_connection *connection,unsigned command,int64_t value) {
    long before=position(file);
    struct md_proc_file_io io={0};
    assert(md_proc_file_io_begin(&io,file,MD_PROC_FILE_COMMAND,0,value,command,0,NULL)==1);
    io.actor=(struct md_control_actor){.identity=&context->task.identity};
    assert(md_proc_file_io_execute(&io,&context->task.identity,NULL,NULL,0,4096)==-EINPROGRESS);
    assert(serve(connection));
    assert(md_proc_file_io_execute(&io,&context->task.identity,NULL,NULL,0,4096)==0);
    assert(!md_proc_file_io_offer(&io) && !md_proc_file_io_finish(&io,0));
    assert(position(file)==before);
}
static int mapping_flags(struct md_proc_file *file,struct md_fs_context *context,struct md_fuse_connection *connection,unsigned flags) {
    struct md_control_file control=md_proc_file_control(file);
    struct md_control_actor actor={.identity=&context->task.identity};
    struct md_control_pending *pending=NULL;
    assert(control.ops->mapping(control.context,&actor,PROT_READ,flags,&pending)==-EINPROGRESS && pending);
    assert(pending->retry_preparation);
    long result;
    for (;;) {
        while (serve(connection)) {}
        result=pending->resume(pending,NULL,0);
        if (result!=-EINPROGRESS) break;
    }
    pending->dispose(pending);
    if (result<0) fprintf(stderr,"FUSE mapping flags=%u: %ld\n",flags,result);
    assert(result>=0); return (int)result;
}
static int mapping(struct md_proc_file *file,struct md_fs_context *context,struct md_fuse_connection *connection) {
    return mapping_flags(file,context,connection,MAP_PRIVATE);
}
static void shared_mappings(struct md_fs_context *context,struct md_fuse_connection *connection) {
    struct md_proc_file *files[2]={0};
    struct md_fs_request q={.operation=MD_FS_OPEN,.directory={-1,-1},.path={"/fuse/file",NULL},.flags=O_RDWR};
    for (unsigned i=0;i<2;++i) {
        struct md_fs_result r=run_request(context,connection,&q,(struct md_fs_output){.control=capture_file,.control_context=&files[i]});
        assert(!r.error && r.fd>=0 && files[i]); close(r.fd);
    }
    long page=sysconf(_SC_PAGESIZE); assert(page>0 && page*2<=(long)sizeof(contents));
    command(files[0],context,connection,MD_CONTROL_TRUNCATE,page*2);
    int a=mapping_flags(files[0],context,connection,MAP_SHARED);
    int b=mapping_flags(files[1],context,connection,MAP_SHARED_VALIDATE);
    struct stat first,second;
    assert(!fstat(a,&first) && !fstat(b,&second) && first.st_dev==second.st_dev && first.st_ino==second.st_ino);
    assert((fcntl(a,F_GETFL)&O_ACCMODE)==O_RDONLY);
    char *mapped=mmap(NULL,page*2,PROT_READ,MAP_SHARED,a,0); assert(mapped!=MAP_FAILED);
    assert(mprotect(mapped,page,PROT_READ|PROT_WRITE)<0 && errno==EACCES);
    assert(pwrite(a,"!",1,0)<0 && errno==EBADF);
    close(a); close(b);
    int channel[2]; assert(!pipe(channel));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(channel[1]); char byte;
        assert(read(channel[0],&byte,1)==1 && !memcmp(mapped,"shared",6));
        _exit(0);
    }
    close(channel[0]);
    assert(io(files[1],context,connection,MD_PROC_FILE_WRITE,"shared",6,0)==6);
    assert(!memcmp(mapped,"shared",6));
    assert(write(channel[1],"!",1)==1); close(channel[1]);
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    struct md_control_file control=md_proc_file_control(files[0]);
    struct md_control_actor actor={.identity=&context->task.identity};
    struct md_control_pending *pending=NULL;
    assert(control.ops->mapping(control.context,&actor,PROT_READ|PROT_WRITE,MAP_SHARED,&pending)==-EACCES && !pending);
    struct { struct fuse_out_header header; struct fuse_notify_inval_inode_out inode; } notification={
        .header={.len=sizeof(notification),.error=FUSE_NOTIFY_INVAL_INODE},.inode={.ino=2,.off=0,.len=0}};
    assert(md_fuse_connection_write(connection,&notification,sizeof(notification))==-EOPNOTSUPP);
    notification.inode.off=-1;
    assert(md_fuse_connection_write(connection,&notification,sizeof(notification))==sizeof(notification));
    command(files[0],context,connection,MD_CONTROL_TRUNCATE,page);
    child=fork(); assert(child>=0);
    if (!child) { volatile char byte=mapped[page]; (void)byte; _exit(1); }
    assert(waitpid(child,&status,0)==child && WIFSIGNALED(status) && WTERMSIG(status)==SIGBUS);
    command(files[0],context,connection,MD_CONTROL_TRUNCATE,page*2);
    assert(mapped[page]==0);
    md_proc_file_release(files[0]); md_proc_file_release(files[1]);
    while (serve(connection)) {}
    assert(!memcmp(mapped,"shared",6) && !munmap(mapped,page*2));
    puts("PASS FUSE shared read mappings: common inode, confirmed writes, fork/close lifetime, truncate and write protection");
}
static void files(struct md_fs_context *context,struct md_fuse_connection *connection) {
    char link[PATH_MAX];
    assert(run(context,connection,MD_FS_READLINK,"/fuse",0,link,sizeof(link)).error==-EINVAL);
    assert(run(context,connection,MD_FS_READLINK,"/fuse/file",0,link,sizeof(link)).error==-EINVAL);
    struct md_fs_anchor *origin=NULL;
    struct md_fs_request path={.operation=MD_FS_TASK_PATH,.directory={-1,-1},
        .path={"/fuse",NULL},.mode=MD_FS_TASK_CHDIR};
    struct md_fs_result opened=run_request(context,connection,&path,(struct md_fs_output){.origin=&origin});
    assert(!opened.error && opened.fd>=0 && origin);
    assert((fcntl(opened.fd,F_GETFL)&O_PATH)==O_PATH);
    close(opened.fd); md_fs_anchor_release(origin);
    struct md_proc_file *file=NULL;
    struct md_fs_request q={.operation=MD_FS_OPEN,.directory={-1,-1},.path={"/fuse/file",NULL},.flags=O_RDWR};
    struct md_fs_result r=run_request(context,connection,&q,(struct md_fs_output){.control=capture_file,.control_context=&file});
    assert(!r.error && r.fd>=0 && file); close(r.fd);
    char data[32768]={0};
    assert(io(file,context,connection,MD_PROC_FILE_READ,data,7,2)==7 && !memcmp(data,"initial",7));
    assert(position(file)==2);
    memset(data,'W',sizeof(data));
    assert(io(file,context,connection,MD_PROC_FILE_WRITE,data,sizeof(data),0)==16384);
    assert(position(file)==16386 && contents[0]=='i' && contents[1]=='n' && contents[2]=='W');
    command(file,context,connection,MD_CONTROL_TRUNCATE,6);
    assert(content_size==6);
    command(file,context,connection,MD_CONTROL_SYNC,0);
    command(file,context,connection,MD_CONTROL_DATASYNC,0);
    long status;
    assert(md_proc_file_fcntl(file,F_SETFL,O_APPEND,&status) && !status);
    assert(io(file,context,connection,MD_PROC_FILE_WRITE,"append",6,0)==6);
    assert(content_size==12 && !memcmp(contents+6,"append",6) && position(file)==12);
    assert(md_proc_file_fcntl(file,F_SETFL,0,&status) && !status);
    command(file,context,connection,MD_CONTROL_TRUNCATE,6);
    int snapshot=mapping(file,context,connection);
    char mapped[6]; assert(pread(snapshot,mapped,sizeof(mapped),0)==sizeof(mapped));
    assert(!memcmp(mapped,contents,sizeof(mapped)));
    unsigned before=requests;
    int copy=mapping(file,context,connection);
    assert(requests==before+1); /* fresh GETATTR, same sealed snapshot */
    close(copy);
    assert(io(file,context,connection,MD_PROC_FILE_WRITE,"Z",1,0)==1);
    copy=mapping(file,context,connection);
    assert(pread(copy,mapped,sizeof(mapped),0)==sizeof(mapped) && !memcmp(mapped,contents,sizeof(mapped)));
    close(copy);
    /* Notify invalidation must also defeat the cache when attrs are unchanged. */
    struct { struct fuse_out_header header; struct fuse_notify_inval_inode_out inode; } notification={
        .header={.len=sizeof(notification),.error=FUSE_NOTIFY_INVAL_INODE},.inode={.ino=2,.off=0,.len=0}};
    contents[0]='N';
    assert(md_fuse_connection_write(connection,&notification,sizeof(notification))==sizeof(notification));
    copy=mapping(file,context,connection);
    assert(pread(copy,mapped,sizeof(mapped),0)==sizeof(mapped) && !memcmp(mapped,contents,sizeof(mapped)));
    assert(pread(snapshot,mapped,sizeof(mapped),0)==sizeof(mapped) && mapped[0]!='N');
    close(copy); close(snapshot);
    md_proc_file_release(file); file=NULL;
    assert(serve(connection)==FUSE_RELEASE && releases==1);
    q.path[0]="/fuse"; q.flags=O_RDONLY|O_DIRECTORY;
    r=run_request(context,connection,&q,(struct md_fs_output){.control=capture_file,.control_context=&file});
    assert(!r.error && r.fd>=0 && file); close(r.fd);
    memset(data,0x5a,sizeof(data));
    assert(io_buffer(file,context,connection,MD_PROC_FILE_GETDENTS,data,24,sizeof(data),-1)==24
        && position(file)==1);
    for (size_t i=24;i<sizeof(data);++i) assert(data[i]==0x5a);
    assert(io(file,context,connection,MD_PROC_FILE_GETDENTS,data,sizeof(data),0)>0 && position(file)==1);
    assert(io(file,context,connection,MD_PROC_FILE_GETDENTS,data,sizeof(data),-1)>0 && position(file)==4);
    assert(io(file,context,connection,MD_PROC_FILE_GETDENTS,data,sizeof(data),-1)==0);
    md_proc_file_release(file);
    assert(serve(connection)==FUSE_RELEASEDIR && releases==2);
}
static void carrier_capacity(struct md_fs_context *context,struct md_fuse_connection *connection) {
    struct rlimit saved;
    assert(!getrlimit(RLIMIT_NOFILE,&saved));
    struct rlimit bounded=saved; if (bounded.rlim_cur>256) bounded.rlim_cur=256;
    assert(!setrlimit(RLIMIT_NOFILE,&bounded));
    entry_seconds=UINT64_MAX;
    for (unsigned pass=0;pass<2;++pass) {
        unsigned before=lookups;
        for (unsigned i=0;i<2048;++i) {
            char path[64]; snprintf(path,sizeof(path),"/fuse/bulk-%u",i);
            struct md_fs_result result=run(context,connection,MD_FS_STAT,path,0,NULL,0);
            assert(!result.error && result.info.inode==1000+i);
        }
        assert(lookups==before+(pass ? 0 : 2048));
    }
    entry_seconds=0;
    assert(!setrlimit(RLIMIT_NOFILE,&saved));
    puts("PASS FUSE carrier capacity: 2048 indexed names with 256 native descriptors and cached revisit");
}
static int executable_image(struct md_fs_context *context,struct md_fuse_connection *connection) {
    struct md_image_identity identity;
    struct md_fs_request q={.operation=MD_FS_OPEN_IMAGE,.directory={-1,-1},.path={"/fuse/file",NULL}};
    struct md_fs_result r=run_request(context,connection,&q,
        (struct md_fs_output){.data=&identity,.capacity=sizeof(identity)});
    assert(!r.error && r.fd>=0 && !strcmp(identity.path,"/fuse/file"));
    assert(serve(connection)==FUSE_RELEASE);
    return r.fd;
}
static void image_cache(struct md_fs_context *context,struct md_fuse_connection *connection) {
    file_mode=0755; open_flags=FOPEN_KEEP_CACHE;
    int first=executable_image(context,connection);
    unsigned before=read_requests;
    int second=executable_image(context,connection);
    assert(read_requests==before);
    struct stat a,b;
    assert(!fstat(first,&a) && !fstat(second,&b) && a.st_ino==b.st_ino && a.st_dev==b.st_dev);
    close(second);
    open_error=-EACCES;
    struct md_image_identity identity;
    struct md_fs_request denied={.operation=MD_FS_OPEN_IMAGE,.directory={-1,-1},.path={"/fuse/file",NULL}};
    struct md_fs_result denied_result=run_request(context,connection,&denied,
        (struct md_fs_output){.data=&identity,.capacity=sizeof(identity)});
    assert(denied_result.error==-EACCES && denied_result.fd<0 && read_requests==before);
    open_error=0;
    struct md_proc_file *file=NULL;
    struct md_fs_request q={.operation=MD_FS_OPEN,.directory={-1,-1},.path={"/fuse/file",NULL},.flags=O_RDONLY};
    struct md_fs_result r=run_request(context,connection,&q,
        (struct md_fs_output){.control=capture_file,.control_context=&file});
    assert(!r.error && r.fd>=0 && file); close(r.fd);
    second=mapping(file,context,connection);
    assert(read_requests==before && !fstat(second,&b) && a.st_ino==b.st_ino);
    close(second); md_proc_file_release(file); assert(serve(connection)==FUSE_RELEASE);
    /* A daemon may keep attrs unchanged, but must invalidate cached contents. */
    struct { struct fuse_out_header header; struct fuse_notify_inval_inode_out inode; } invalid={
        .header={.len=sizeof(invalid),.error=FUSE_NOTIFY_INVAL_INODE},.inode={.ino=2,.off=0}};
    char old=contents[0]; contents[0]=old=='I' ? 'J' : 'I';
    assert(md_fuse_connection_write(connection,&invalid,sizeof(invalid))==sizeof(invalid));
    second=executable_image(context,connection);
    char byte;
    assert(read_requests>before && pread(second,&byte,1,0)==1 && byte==contents[0]);
    assert(pread(first,&byte,1,0)==1 && byte==old);
    close(first); close(second);
    before=read_requests; open_flags=0;
    second=executable_image(context,connection); close(second);
    assert(read_requests>before);
    before=read_requests; open_flags=FOPEN_DIRECT_IO|FOPEN_KEEP_CACHE;
    second=executable_image(context,connection); close(second);
    assert(read_requests>before);
    before=read_requests;
    second=executable_image(context,connection); close(second);
    assert(read_requests>before);
    file_mode=0644; open_flags=0;
    puts("PASS FUSE image cache: exec/mmap inode reuse, invalidation, open flags and immutable old mappings");
}
static void image_cache_bounds(void) {
    struct fuse_view view={.lock=PTHREAD_MUTEX_INITIALIZER};
    struct fuse_node nodes[65]={0};
    int fd=memfd_create("fuse-cache-test",MFD_CLOEXEC); assert(fd>=0);
    struct fuse_attr small={.size=1};
    for (unsigned i=0;i<64;++i) assert(!md_fuse_snapshot_store(&view,nodes+i,&small,0,fd));
    assert(view.images_count==64 && view.images_bytes==64);
    int retained=md_fuse_snapshot_get(&view,nodes,&small,0); assert(retained>=0);
    assert(!md_fuse_snapshot_store(&view,nodes+64,&small,0,fd));
    assert(nodes[0].snapshot && !nodes[1].snapshot && nodes[64].snapshot && view.images_count==64);
    assert(md_fuse_snapshot_get(&view,nodes+1,&small,0)==-ENOENT);
    assert(md_fuse_snapshot_open(&view,nodes,0)==1);
    assert(!nodes[0].snapshot && view.images_count==63);
    assert(md_fuse_snapshot_store(&view,nodes,&small,0,fd)==-EAGAIN);
    assert(md_fuse_snapshot_get(&view,nodes,&small,0)==-EAGAIN);
    assert(!md_fuse_snapshot_store(&view,nodes,&small,1,fd));
    struct fuse_attr changed=small; ++changed.mtime;
    assert(md_fuse_snapshot_get(&view,nodes,&changed,1)==-ENOENT && !nodes[0].snapshot);
    md_fuse_snapshot_clear(&view);
    struct fuse_attr large={.size=128ULL*1024*1024};
    for (unsigned i=1;i<=3;++i) assert(!md_fuse_snapshot_store(&view,nodes+i,&large,0,fd));
    assert(!nodes[1].snapshot && nodes[2].snapshot && nodes[3].snapshot);
    assert(view.images_count==2 && view.images_bytes==256ULL*1024*1024);
    large.size=view.images_bytes+1;
    assert(!md_fuse_snapshot_store(&view,nodes+4,&large,0,fd));
    assert(!nodes[4].snapshot && view.images_count==2);
    md_fuse_snapshot_clear(&view);
    assert(!view.images_count && !view.images_bytes && !view.images_first && !view.images_last);
    assert(fcntl(retained,F_GETFD)>=0); close(retained); close(fd);
    pthread_mutex_destroy(&view.lock);
    puts("PASS FUSE image cache: count/byte bounds, LRU, stale publication and retained descriptors");
}
static long read_page(struct md_proc_file_io *op,struct md_fs_context *context,
        struct md_fuse_connection *connection,void *data,size_t capacity) {
    op->actor=(struct md_control_actor){.identity=&context->task.identity};
    long result=md_proc_file_io_execute(op,&context->task.identity,NULL,data,capacity,4096);
    for (unsigned step=0;result==-EINPROGRESS;++step) {
        assert(step<8 && serve(connection));
        result=md_proc_file_io_execute(op,&context->task.identity,NULL,data,capacity,4096);
    }
    if (result>=0) assert(!md_proc_file_io_offer(op));
    return result;
}
static void paged_reads(struct md_fs_context *context,struct md_fuse_connection *connection) {
    char saved[sizeof(contents)],data[sizeof(contents)];
    memcpy(saved,contents,sizeof(saved)); size_t saved_size=content_size;
    for (size_t i=0;i<sizeof(contents);++i) contents[i]=(char)i;
    content_size=sizeof(contents);
    struct md_proc_file *file=NULL;
    struct md_fs_request q={.operation=MD_FS_OPEN,.directory={-1,-1},.path={"/fuse/file",NULL},.flags=O_RDONLY};
    struct md_fs_result r=run_request(context,connection,&q,
        (struct md_fs_output){.control=capture_file,.control_context=&file});
    assert(!r.error && r.fd>=0); close(r.fd);
    for (unsigned variant=0;variant<7;++variant) {
        struct md_proc_file_io seek={0},read={0},queued={0};
        assert(md_proc_file_io_begin(&seek,file,MD_PROC_FILE_SEEK,0,0,SEEK_SET,0,NULL)==1);
        assert(!md_proc_file_io_execute(&seek,NULL,NULL,NULL,0,4096));
        assert(!md_proc_file_io_finish(&seek,0));
        int positional=variant==1;
        size_t start=positional ? 10 : 0;
        assert(md_proc_file_io_begin(&read,file,MD_PROC_FILE_READ,positional,start,0,65536,NULL)==1);
        assert(read_page(&read,context,connection,data,sizeof(data))==16384);
        assert(!memcmp(data,contents+start,16384) && md_proc_file_io_more(&read));
        assert(md_proc_file_io_advance(&read,16383)==-EINVAL);
        assert(!md_proc_file_io_begin(&queued,file,MD_PROC_FILE_SEEK,0,0,SEEK_CUR,0,NULL));
        assert(!md_proc_file_io_advance(&read,16384));
        assert(read.transferred==16384 && read.length==49152 && !read.executed);
        assert(md_resource_order_state(&queued.turn)==MD_RESOURCE_ORDER_QUEUED);
        long expected=16384;
        if (variant==2) {
            /* Cancellation at a page boundary must retire the still-running turn. */
            assert(!md_proc_file_io_cancel(&read));
        } else if (variant==3) {
            read_error=-EIO;
            assert(read_page(&read,context,connection,data,sizeof(data))==-EIO);
            read_error=0;
            assert(!md_proc_file_io_more(&read) && !md_proc_file_io_cancel(&read));
        } else if (variant==6) {
            read.actor=(struct md_control_actor){.identity=&context->task.identity};
            assert(md_proc_file_io_execute(&read,&context->task.identity,NULL,data,sizeof(data),4096)==-EINPROGRESS);
            assert(!md_proc_file_io_cancel(&read) && !serve(connection));
        } else {
            long count=read_page(&read,context,connection,data,sizeof(data));
            assert(count==(long)(16384-start) && !memcmp(data,contents+start+16384,(size_t)count));
            if (variant==4 || variant==5) {
                /* A user-memory fault acknowledges only the copied prefix. */
                long copied=variant==4 ? 7 : -EFAULT;
                assert(!md_proc_file_io_finish(&read,copied));
                expected+=copied>0 ? copied : 0;
            } else if (positional) {
                assert(!md_proc_file_io_more(&read) && !md_proc_file_io_finish(&read,count));
                expected=0;
            } else {
                assert(md_proc_file_io_more(&read) && !md_proc_file_io_advance(&read,count));
                assert(md_resource_order_state(&queued.turn)==MD_RESOURCE_ORDER_QUEUED);
                assert(!read_page(&read,context,connection,data,sizeof(data)));
                assert(!md_proc_file_io_more(&read) && !md_proc_file_io_finish(&read,0));
                expected=sizeof(contents);
            }
        }
        assert(md_resource_order_state(&queued.turn)==MD_RESOURCE_ORDER_READY);
        assert(md_proc_file_io_execute(&queued,NULL,NULL,NULL,0,4096)==expected);
        assert(!md_proc_file_io_finish(&queued,0) && position(file)==expected);
    }
    unsigned before=read_requests;
    struct md_proc_file_io end={0};
    assert(md_proc_file_io_begin(&end,file,MD_PROC_FILE_READ,1,UINT64_C(1)<<32,0,1,NULL)==1);
    assert(!read_page(&end,context,connection,data,sizeof(data)) && read_requests==before);
    assert(!md_proc_file_io_finish(&end,0));
    md_proc_file_release(file); assert(serve(connection)==FUSE_RELEASE);
    /* An old size must not hide growth outside this mount. */
    content_size=42;
    r=run_request(context,connection,&q,(struct md_fs_output){.control=capture_file,.control_context=&file});
    assert(!r.error && r.fd>=0); close(r.fd);
    content_size=sizeof(contents);
    assert(md_proc_file_io_begin(&end,file,MD_PROC_FILE_READ,1,42,0,7,NULL)==1);
    assert(read_page(&end,context,connection,data,sizeof(data))==7 && !memcmp(data,contents+42,7));
    assert(!md_proc_file_io_finish(&end,7));
    md_proc_file_release(file); assert(serve(connection)==FUSE_RELEASE);
    /* Direct-IO handles can expose bytes beyond st_size; do not invent EOF. */
    open_flags=FOPEN_DIRECT_IO;
    r=run_request(context,connection,&q,(struct md_fs_output){.control=capture_file,.control_context=&file});
    open_flags=0;
    assert(!r.error && r.fd>=0); close(r.fd);
    assert(md_proc_file_io_begin(&end,file,MD_PROC_FILE_READ,1,UINT64_C(1)<<32,0,1,NULL)==1);
    before=read_requests; read_error=-EIO;
    assert(read_page(&end,context,connection,data,sizeof(data))==-EIO && read_requests==before+1);
    read_error=0; assert(!md_proc_file_io_cancel(&end));
    md_proc_file_release(file); assert(serve(connection)==FUSE_RELEASE);
    memcpy(contents,saved,sizeof(contents)); content_size=saved_size;
    puts("PASS FUSE paged reads: one OFD turn, positional IO, EOF, error, copy fault and cancellation");
}
static void close_completion(struct md_fs_context *context,struct md_fuse_connection *connection) {
    unsigned start_release=releases,start_flush=flushes;
    struct md_proc_file *file=NULL;
    struct md_fs_request q={.operation=MD_FS_OPEN,.directory={-1,-1},.path={"/fuse/file",NULL},.flags=O_RDWR};
    struct md_fs_result r=run_request(context,connection,&q,
        (struct md_fs_output){.control=capture_file,.control_context=&file});
    assert(!r.error && r.fd>=0); close(r.fd);
    struct md_control_actor actor={.identity=&context->task.identity};
    struct md_fd_object *object=md_proc_file_object(file);
    for (unsigned i=0;i<2;++i) {
        struct md_control_pending *pending=NULL;
        assert(object->type->close(object,&actor,&pending)==-EINPROGRESS && pending);
        assert(pending->resume(pending,NULL,0)==-EINPROGRESS);
        flush_error=i==1 ? -EIO : 0;
        assert(serve(connection)==FUSE_FLUSH); flush_error=0;
        assert(pending->resume(pending,NULL,0)==(i==1 ? -EIO : 0));
        pending->dispose(pending);
        assert(releases==start_release && !serve(connection));
    }
    struct md_control_pending *pending=NULL;
    assert(object->type->close(object,&actor,&pending)==-EINPROGRESS);
    pending->dispose(pending); /* Interrupted close keeps even an unread FLUSH. */
    flush_error=-ENOSYS;
    assert(serve(connection)==FUSE_FLUSH && flushes==start_flush+3); flush_error=0;
    pending=NULL;
    assert(!object->type->close(object,&actor,&pending) && !pending && !serve(connection));
    md_proc_file_release(file);
    assert(serve(connection)==FUSE_RELEASE && releases==start_release+1 && !serve(connection));
    q.path[0]="/fuse"; q.flags=O_RDONLY|O_DIRECTORY;
    r=run_request(context,connection,&q,(struct md_fs_output){.control=capture_file,.control_context=&file});
    assert(!r.error && r.fd>=0); close(r.fd);
    pending=NULL; object=md_proc_file_object(file);
    assert(!object->type->close(object,&actor,&pending) && !pending && !serve(connection));
    md_proc_file_release(file); assert(serve(connection)==FUSE_RELEASEDIR);
    q.path[0]="/fuse/file"; q.flags=O_RDONLY;
    r=run_request(context,connection,&q,(struct md_fs_output){.control=capture_file,.control_context=&file});
    assert(!r.error && r.fd>=0); close(r.fd); object=md_proc_file_object(file);
    assert(!object->type->close(object,&actor,&pending) && !pending && !serve(connection));
    md_proc_file_release(file); assert(serve(connection)==FUSE_RELEASE);
    puts("PASS FUSE close: per-alias FLUSH, EIO, ENOSYS, queued retirement and final RELEASE");
}
static long execute_io(struct md_proc_file_io *op,struct md_fs_context *context) {
    return md_proc_file_io_execute(op,&context->task.identity,NULL,"record",6,4096);
}
static void append_ordering(struct md_fs_context *context,struct md_fuse_connection *connection) {
    struct md_proc_file *files[2]={0};
    struct md_fs_request q={.operation=MD_FS_OPEN,.directory={-1,-1},.path={"/fuse/file",NULL},.flags=O_RDWR|O_APPEND};
    for (unsigned i=0;i<2;++i) {
        struct md_fs_result r=run_request(context,connection,&q,
            (struct md_fs_output){.control=capture_file,.control_context=&files[i]});
        assert(!r.error && r.fd>=0); close(r.fd);
    }
    struct md_proc_file_io first={0},second={0},same={0};
    struct md_control_actor actor={.identity=&context->task.identity};
    assert(md_proc_file_io_begin(&first,files[0],MD_PROC_FILE_WRITE,0,0,0,6,NULL)==1);
    first.actor=actor;
    assert(execute_io(&first,context)==-EINPROGRESS);
    assert(serve(connection)==FUSE_GETATTR);
    assert(execute_io(&first,context)==-EINPROGRESS);
    defer_opcode=FUSE_WRITE;
    assert(serve(connection)==FUSE_WRITE && deferred_size);
    size_t after_first=content_size;
    assert(md_proc_file_io_begin(&second,files[1],MD_PROC_FILE_WRITE,0,0,0,6,NULL)==1);
    second.actor=actor;
    assert(execute_io(&second,context)==-EINPROGRESS && !serve(connection));
    assert(!md_proc_file_io_cancel(&first));
    assert(!md_proc_file_io_begin(&same,files[0],MD_PROC_FILE_SEEK,0,0,SEEK_CUR,0,NULL));
    assert(md_resource_order_state(&same.turn)==MD_RESOURCE_ORDER_QUEUED);
    assert(execute_io(&second,context)==-EINPROGRESS && !serve(connection));
    assert(md_fuse_connection_write(connection,deferred_reply,deferred_size)==(long)deferred_size);
    deferred_size=0;
    assert(md_resource_order_state(&same.turn)==MD_RESOURCE_ORDER_READY);
    assert(md_proc_file_io_execute(&same,NULL,NULL,NULL,0,4096)==(long)after_first);
    assert(!md_proc_file_io_finish(&same,0));
    assert(execute_io(&second,context)==-EINPROGRESS);
    assert(serve(connection)==FUSE_GETATTR);
    assert(execute_io(&second,context)==-EINPROGRESS);
    write_limit=3; assert(serve(connection)==FUSE_WRITE); write_limit=0;
    assert(execute_io(&second,context)==3);
    assert(!md_proc_file_io_offer(&second) && !md_proc_file_io_finish(&second,0));
    assert(content_size==after_first+3 && position(files[1])==(long)content_size);
    assert(md_proc_file_io_begin(&first,files[0],MD_PROC_FILE_WRITE,0,0,0,6,NULL)==1);
    first.actor=actor;
    assert(execute_io(&first,context)==-EINPROGRESS && serve(connection)==FUSE_GETATTR);
    assert(execute_io(&first,context)==-EINPROGRESS);
    write_error=-ENOSPC; assert(serve(connection)==FUSE_WRITE); write_error=0;
    assert(execute_io(&first,context)==-ENOSPC && !md_proc_file_io_finish(&first,0));
    assert(content_size==after_first+3);
    for (unsigned i=0;i<2;++i) { md_proc_file_release(files[i]); assert(serve(connection)==FUSE_RELEASE); }
    puts("PASS FUSE append ordering: independent opens, delayed cancellation, partial writes and ENOSPC");
}
static void mutations(struct md_fs_context *context,struct md_fuse_connection *connection) {
    int watch=md_inode_watch_create(context->filesystem->store,IN_NONBLOCK|IN_CLOEXEC);
    assert(watch>=0);
    struct md_fs_request watch_request={.operation=MD_FS_WATCH_PATH,.directory={-1,watch},
        .path={"/fuse",NULL},.flags=IN_ALL_EVENTS};
    struct md_fs_result watched=run_request(context,connection,&watch_request,(struct md_fs_output){0});
    assert(!watched.error && watched.position>0);
    int wd=watched.position;
    struct md_proc_file *file=NULL;
    struct md_fs_request q={.operation=MD_FS_OPEN,.directory={-1,-1},.path={"/fuse/created",NULL},
        .flags=O_RDWR|O_CREAT|O_EXCL,.mode=0666,.attributes={.creation_mask=0022}};
    struct md_fs_result r=run_request(context,connection,&q,(struct md_fs_output){.control=capture_file,.control_context=&file});
    if (r.error) fprintf(stderr,"FUSE create: %d\n",r.error);
    assert(!r.error && r.fd>=0 && file && md_proc_file_filesystem_paths(file)); close(r.fd);
    md_proc_file_release(file); assert(serve(connection)==FUSE_RELEASE);
    r=run(context,connection,MD_FS_STAT,"/fuse/created",0,NULL,0); assert(!r.error && (r.info.mode&0777)==0644);
    q=(struct md_fs_request){.operation=MD_FS_CHMOD,.directory={-1,-1},.path={"/fuse/created",NULL},.mode=0600};
    r=run_request(context,connection,&q,(struct md_fs_output){0}); assert(!r.error);
    r=run(context,connection,MD_FS_STAT,"/fuse/created",0,NULL,0); assert(!r.error && (r.info.mode&0777)==0600);
    uint64_t ino=r.info.inode;
    q=(struct md_fs_request){.operation=MD_FS_RENAME,.directory={-1,-1},.path={"/fuse/created","/fuse/renamed"}};
    r=run_request(context,connection,&q,(struct md_fs_output){0}); assert(!r.error);
    r=run(context,connection,MD_FS_STAT,"/fuse/created",0,NULL,0); assert(r.error==-ENOENT);
    r=run(context,connection,MD_FS_STAT,"/fuse/renamed",0,NULL,0); assert(!r.error && r.info.inode==ino);
    q=(struct md_fs_request){.operation=MD_FS_LINK,.directory={-1,-1},.path={"/fuse/renamed","/fuse/alias"}};
    r=run_request(context,connection,&q,(struct md_fs_output){0}); assert(!r.error);
    r=run(context,connection,MD_FS_STAT,"/fuse/alias",0,NULL,0); assert(!r.error && r.info.inode==ino);
    r=run(context,connection,MD_FS_UNLINK,"/fuse/alias",0,NULL,0); assert(!r.error);
    r=run(context,connection,MD_FS_UNLINK,"/fuse/renamed",0,NULL,0); assert(!r.error);
    q=(struct md_fs_request){.operation=MD_FS_MKDIR,.directory={-1,-1},.path={"/fuse/directory",NULL},.mode=0700};
    r=run_request(context,connection,&q,(struct md_fs_output){0}); assert(!r.error);
    r=run(context,connection,MD_FS_STAT,"/fuse/directory",0,NULL,0); assert(!r.error && S_ISDIR(r.info.mode));
    r=run(context,connection,MD_FS_UNLINK,"/fuse/directory",AT_REMOVEDIR,NULL,0); assert(!r.error);
    q=(struct md_fs_request){.operation=MD_FS_SYMLINK,.directory={-1,-1},.path={"/fuse/newlink","file"}};
    r=run_request(context,connection,&q,(struct md_fs_output){0}); assert(!r.error);
    r=run(context,connection,MD_FS_STAT,"/fuse/newlink",0,NULL,0); assert(!r.error && r.info.inode==2);
    r=run(context,connection,MD_FS_UNLINK,"/fuse/newlink",0,NULL,0); assert(!r.error);
    union { uint64_t alignment; char bytes[8192]; } events;
    ssize_t size=md_inode_watch_read(context->filesystem->store,watch,events.bytes,sizeof(events.bytes),NULL,NULL);
    assert(size>0);
    unsigned masks=0,cookie=0; int rename_pair=0;
    for (size_t at=0;at<(size_t)size;) {
        struct inotify_event *event=(void *)(events.bytes+at);
        assert(event->wd==wd && event->len);
        masks|=event->mask;
        if ((event->mask&IN_MOVED_FROM) && !strcmp(event->name,"created")) { cookie=event->cookie; assert(cookie); }
        if ((event->mask&IN_MOVED_TO) && !strcmp(event->name,"renamed")) { assert(event->cookie==cookie); rename_pair=1; }
        at+=sizeof(*event)+event->len;
    }
    unsigned expected=IN_CREATE|IN_OPEN|IN_CLOSE_WRITE|IN_ATTRIB|IN_DELETE|IN_MOVED_FROM|IN_MOVED_TO|IN_ISDIR;
    assert((masks&expected)==expected && rename_pair);
    assert(!md_inode_watch_remove(context->filesystem->store,watch,wd));
    size=md_inode_watch_read(context->filesystem->store,watch,events.bytes,sizeof(events.bytes),NULL,NULL);
    assert(size==sizeof(struct inotify_event) && ((struct inotify_event *)events.bytes)->mask==IN_IGNORED);
    close(watch); assert(!md_inode_watch_pump(context->filesystem->store));
    puts("PASS FUSE watches: create/open/close, attributes, rename cookie, delete, directory and removal");
}
static void xattrs(struct md_fs_context *context,struct md_fuse_connection *connection) {
    char data[256];
    struct md_fs_output output={.data=data,.capacity=sizeof(data)};
    struct md_fs_request q={.operation=MD_FS_XATTR,.directory={-1,-1},
        .path={"/fuse/file","user.test"},.mode=MD_FS_XATTR_SET,
        .flags=1,.value="value",.value_size=5,.capacity=5};
    struct md_fs_result r=run_request(context,connection,&q,output); assert(!r.error);
    r=run_request(context,connection,&q,output); assert(r.error==-EEXIST);
    q.mode=MD_FS_XATTR_GET; q.flags=0; q.value=NULL; q.value_size=q.capacity=0;
    r=run_request(context,connection,&q,output); assert(!r.error && r.position==5 && !r.size);
    q.capacity=2;
    r=run_request(context,connection,&q,output); assert(r.error==-ERANGE);
    q.capacity=sizeof(data);
    r=run_request(context,connection,&q,output); assert(!r.error && r.size==5 && !memcmp(data,"value",5));
    q.path[1]="user.unsupported";
    r=run_request(context,connection,&q,output); assert(r.error==-EOPNOTSUPP);
    q.mode=MD_FS_XATTR_LIST; q.path[1]="";
    r=run_request(context,connection,&q,output); assert(!r.error && r.size==sizeof("user.test") && !strcmp(data,"user.test"));
    malformed_names=1;
    r=run_request(context,connection,&q,output); assert(r.error==-EPROTO);
    malformed_names=0;
    q.mode=MD_FS_XATTR_REMOVE; q.path[1]="user.test"; q.capacity=0;
    r=run_request(context,connection,&q,output); assert(!r.error);
    q.mode=MD_FS_XATTR_GET;
    r=run_request(context,connection,&q,output); assert(r.error==-ENODATA);
}
static void notifications(struct md_fs_context *context,struct md_fuse_connection *connection) {
    struct {
        struct fuse_out_header header;
        struct fuse_notify_inval_inode_out inode;
    } inode={.header={.len=sizeof(inode),.error=FUSE_NOTIFY_INVAL_INODE},.inode={.ino=2,.off=0,.len=0}};
    assert(md_fuse_connection_write(connection,&inode,sizeof(inode))==sizeof(inode));
    inode.inode.ino=99999;
    assert(md_fuse_connection_write(connection,&inode,sizeof(inode))==-ENOENT);
    struct {
        struct fuse_out_header header;
        struct fuse_notify_inval_entry_out entry;
        char name[5];
    } entry={.header={.error=FUSE_NOTIFY_INVAL_ENTRY},.entry={.parent=1,.namelen=4},.name="file"};
    size_t size=offsetof(__typeof__(entry),name)+5; entry.header.len=size;
    assert(md_fuse_connection_write(connection,&entry,size)==(long)size);
    struct md_fs_result r=run(context,connection,MD_FS_STAT,"/fuse/file",0,NULL,0);
    assert(!r.error && r.info.inode==2);
    entry.name[4]='x';
    assert(md_fuse_connection_write(connection,&entry,size)==-EINVAL);
    entry.header.error=FUSE_NOTIFY_STORE;
    assert(md_fuse_connection_write(connection,&entry,size)==-EOPNOTSUPP);
    r=run(context,connection,MD_FS_STAT,"/fuse/file",0,NULL,0);
    assert(!r.error);
    struct md_fs_request q={.operation=MD_FS_MKDIR,.directory={-1,-1},
        .path={"/fuse/notified",NULL},.mode=0700};
    r=run_request(context,connection,&q,(struct md_fs_output){0}); assert(!r.error);
    r=run(context,connection,MD_FS_STAT,"/fuse/notified",0,NULL,0);
    assert(!r.error && S_ISDIR(r.info.mode));
    uint64_t child=r.info.inode;
    int watch=md_inode_watch_create(context->filesystem->store,IN_NONBLOCK|IN_CLOEXEC);
    assert(watch>=0);
    q=(struct md_fs_request){.operation=MD_FS_WATCH_PATH,.directory={-1,watch},
        .path={"/fuse/notified",NULL},.flags=IN_ALL_EVENTS};
    r=run_request(context,connection,&q,(struct md_fs_output){0});
    assert(!r.error && r.position>0);
    int wd=r.position;
    struct {
        struct fuse_out_header header;
        struct fuse_notify_delete_out deleted;
        char name[9];
    } removed={.header={.error=FUSE_NOTIFY_DELETE},
        .deleted={.parent=1,.child=child,.namelen=8},.name="notified"};
    size=offsetof(__typeof__(removed),name)+sizeof(removed.name); removed.header.len=size;
    for (unsigned i=0;i<16;++i) if (entries[i].node==child) entries[i].node=0;
    assert(md_fuse_connection_write(connection,&removed,size)==(long)size);
    union { uint64_t alignment; char bytes[512]; } events;
    ssize_t count=md_inode_watch_read(context->filesystem->store,watch,events.bytes,sizeof(events.bytes),NULL,NULL);
    assert(count>0);
    unsigned masks=0;
    for (size_t at=0;at<(size_t)count;) {
        const struct inotify_event *event=(const void *)(events.bytes+at);
        assert(event->wd==wd); masks|=event->mask;
        at+=sizeof(*event)+event->len;
    }
    assert((masks&(IN_DELETE_SELF|IN_IGNORED))==(IN_DELETE_SELF|IN_IGNORED));
    assert(md_inode_watch_remove(context->filesystem->store,watch,wd)==-EINVAL);
    close(watch); assert(!md_inode_watch_pump(context->filesystem->store));
}
static void name_leases(struct md_fs_context *context,struct md_fuse_connection *connection) {
    struct {
        struct fuse_out_header header;
        struct fuse_notify_inval_entry_out entry;
        char name[5];
    } invalid={.header={.error=FUSE_NOTIFY_INVAL_ENTRY},.entry={.parent=1,.namelen=4},.name="file"};
    size_t size=offsetof(__typeof__(invalid),name)+sizeof(invalid.name); invalid.header.len=size;
    entry_seconds=60;
    assert(!run(context,connection,MD_FS_STAT,"/fuse/file",0,NULL,0).error);
    unsigned names=lookups,attrs=getattrs;
    size_t old_size=content_size; ++content_size;
    struct md_fs_result r=run(context,connection,MD_FS_STAT,"/fuse/file",0,NULL,0);
    assert(!r.error && r.info.size==(int64_t)content_size && lookups==names && getattrs>attrs);
    content_size=old_size;
    assert(md_fuse_connection_write(connection,&invalid,size)==(long)size);
    assert(!run(context,connection,MD_FS_STAT,"/fuse/file",0,NULL,0).error && lookups==names+1);
    names=lookups;
    assert(!run(context,connection,MD_FS_STAT,"/fuse/link",AT_SYMLINK_NOFOLLOW,NULL,0).error);
    assert(!run(context,connection,MD_FS_STAT,"/fuse/link",AT_SYMLINK_NOFOLLOW,NULL,0).error);
    assert(lookups==names+2); /* A name lease does not lease a symlink target. */
    for (unsigned mode=0;mode<3;++mode) {
        assert(md_fuse_connection_write(connection,&invalid,size)==(long)size);
        entry_seconds=0; entry_nanos=mode==0 ? 0 : mode==1 ? 1 : 1000000000U;
        assert(!run(context,connection,MD_FS_STAT,"/fuse/file",0,NULL,0).error);
        names=lookups;
        assert(!run(context,connection,MD_FS_STAT,"/fuse/file",0,NULL,0).error && lookups==names+1);
    }
    entry_nanos=0; entry_seconds=UINT64_MAX;
    assert(!run(context,connection,MD_FS_STAT,"/fuse/file",0,NULL,0).error);
    names=lookups;
    assert(!run(context,connection,MD_FS_STAT,"/fuse/file",0,NULL,0).error && lookups==names);
    assert(md_fuse_connection_write(connection,&invalid,size)==(long)size);
    defer_opcode=FUSE_LOOKUP;
    struct md_fs_request q={.operation=MD_FS_STAT,.directory={-1,-1},.path={"/fuse/file",NULL}};
    struct md_fs_continuation *pending=NULL;
    struct md_fs_output output={.continuation=&pending};
    md_fs_context_execute(context,&q,&r,&output);
    assert(r.error==-EINPROGRESS && pending && serve(connection)==FUSE_LOOKUP && deferred_size);
    assert(md_fuse_connection_write(connection,&invalid,size)==(long)size);
    assert(md_fuse_connection_write(connection,deferred_reply,deferred_size)==(long)deferred_size);
    deferred_size=0;
    while (r.error==-EINPROGRESS) {
        if (!pending->ready(pending)) assert(serve(connection));
        struct md_fs_continuation *old=pending; pending=NULL;
        md_fs_context_resume(context,old,&r,&output);
        assert(md_fs_continuation_pending(old,&pending,&r)==(r.error==-EINPROGRESS));
    }
    assert(!r.error);
    names=lookups;
    assert(!run(context,connection,MD_FS_STAT,"/fuse/file",0,NULL,0).error && lookups==names+1);
    q=(struct md_fs_request){.operation=MD_FS_MKDIR,.directory={-1,-1},.path={"/fuse/leased",NULL},.mode=0755};
    assert(!run_request(context,connection,&q,(struct md_fs_output){0}).error);
    assert(!run(context,connection,MD_FS_STAT,"/fuse/leased",0,NULL,0).error);
    names=lookups;
    assert(!run(context,connection,MD_FS_STAT,"/fuse/leased",0,NULL,0).error && lookups==names);
    q.operation=MD_FS_RENAME; q.mode=0; q.path[1]="/fuse/renamed-lease";
    assert(!run_request(context,connection,&q,(struct md_fs_output){0}).error);
    assert(run(context,connection,MD_FS_STAT,"/fuse/leased",0,NULL,0).error==-ENOENT);
    assert(!run(context,connection,MD_FS_STAT,"/fuse/renamed-lease",0,NULL,0).error);
    q.operation=MD_FS_UNLINK; q.flags=AT_REMOVEDIR; q.path[0]=q.path[1]; q.path[1]=NULL;
    assert(!run_request(context,connection,&q,(struct md_fs_output){0}).error);
    assert(run(context,connection,MD_FS_STAT,"/fuse/renamed-lease",0,NULL,0).error==-ENOENT);
    q=(struct md_fs_request){.operation=MD_FS_MKDIR,.directory={-1,-1},.path={"/fuse/first",NULL},.mode=0755};
    assert(!run_request(context,connection,&q,(struct md_fs_output){0}).error);
    q.path[0]="/fuse/second";
    assert(!run_request(context,connection,&q,(struct md_fs_output){0}).error);
    uint64_t first=run(context,connection,MD_FS_STAT,"/fuse/first",0,NULL,0).info.inode;
    uint64_t second=run(context,connection,MD_FS_STAT,"/fuse/second",0,NULL,0).info.inode;
    assert(first && second && first!=second);
    q=(struct md_fs_request){.operation=MD_FS_RENAME,.directory={-1,-1},
        .path={"/fuse/first","/fuse/second"},.flags=RENAME_EXCHANGE};
    assert(!run_request(context,connection,&q,(struct md_fs_output){0}).error);
    r=run(context,connection,MD_FS_STAT,"/fuse/first",0,NULL,0); assert(!r.error && r.info.inode==second);
    r=run(context,connection,MD_FS_STAT,"/fuse/second",0,NULL,0); assert(!r.error && r.info.inode==first);
    q.flags=0;
    assert(!run_request(context,connection,&q,(struct md_fs_output){0}).error);
    assert(run(context,connection,MD_FS_STAT,"/fuse/first",0,NULL,0).error==-ENOENT);
    r=run(context,connection,MD_FS_STAT,"/fuse/second",0,NULL,0); assert(!r.error && r.info.inode==second);
    q.operation=MD_FS_UNLINK; q.flags=AT_REMOVEDIR; q.path[0]=q.path[1]; q.path[1]=NULL;
    assert(!run_request(context,connection,&q,(struct md_fs_output){0}).error);
    entry_seconds=0;
    assert(md_fuse_connection_write(connection,&invalid,size)==(long)size);
    puts("PASS FUSE name leases: daemon TTL, fresh attributes, expiry/overflow, symlinks, mutations and in-flight invalidation");
}
static void attribute_leases(struct md_fs_context *context,struct md_fuse_connection *connection) {
    struct { struct fuse_out_header header; struct fuse_notify_inval_inode_out inode; } invalid={
        .header={.len=sizeof(invalid),.error=FUSE_NOTIFY_INVAL_INODE},.inode={.ino=1,.off=-1}};
    attr_seconds=60;
    struct md_fs_result r=run(context,connection,MD_FS_STAT,"/fuse",0,NULL,0);
    assert(!r.error && (r.info.mode&0777)==0755);
    unsigned before=getattrs; root_mode=0711;
    r=run(context,connection,MD_FS_STAT,"/fuse",0,NULL,0);
    assert(!r.error && (r.info.mode&0777)==0755 && getattrs==before);
    assert(md_fuse_connection_write(connection,&invalid,sizeof(invalid))==sizeof(invalid));
    r=run(context,connection,MD_FS_STAT,"/fuse",0,NULL,0);
    assert(!r.error && (r.info.mode&0777)==0711 && getattrs==before+1);
    root_mode=0755;
    for (unsigned mode=0;mode<4;++mode) {
        assert(md_fuse_connection_write(connection,&invalid,sizeof(invalid))==sizeof(invalid));
        attr_seconds=mode==3 ? UINT64_MAX : 0; attr_nanos=mode==1 ? 1 : mode==2 ? 1000000000U : 0;
        assert(!run(context,connection,MD_FS_STAT,"/fuse",0,NULL,0).error);
        before=getattrs;
        assert(!run(context,connection,MD_FS_STAT,"/fuse",0,NULL,0).error);
        assert(getattrs==before+(mode==3 ? 0 : 1));
    }
    attr_seconds=60; attr_nanos=0;
    assert(md_fuse_connection_write(connection,&invalid,sizeof(invalid))==sizeof(invalid));
    defer_opcode=FUSE_GETATTR;
    struct md_fs_request q={.operation=MD_FS_STAT,.directory={-1,-1},.path={"/fuse",NULL}};
    struct md_fs_continuation *pending=NULL;
    struct md_fs_output output={.continuation=&pending};
    md_fs_context_execute(context,&q,&r,&output);
    assert(r.error==-EINPROGRESS && pending && serve(connection)==FUSE_GETATTR && deferred_size);
    assert(md_fuse_connection_write(connection,&invalid,sizeof(invalid))==sizeof(invalid));
    assert(md_fuse_connection_write(connection,deferred_reply,deferred_size)==(long)deferred_size);
    deferred_size=0;
    struct md_fs_continuation *old=pending; pending=NULL;
    md_fs_context_resume(context,old,&r,&output);
    assert(!md_fs_continuation_pending(old,&pending,&r) && !r.error);
    before=getattrs;
    assert(!run(context,connection,MD_FS_STAT,"/fuse",0,NULL,0).error && getattrs==before+1);
    /* The name and attribute leases from one LOOKUP are independent. */
    entry_seconds=60;
    invalid.inode.ino=2;
    assert(md_fuse_connection_write(connection,&invalid,sizeof(invalid))==sizeof(invalid));
    before=getattrs;
    assert(!run(context,connection,MD_FS_STAT,"/fuse/file",0,NULL,0).error && getattrs==before);
    assert(!run(context,connection,MD_FS_STAT,"/fuse/file",0,NULL,0).error && getattrs==before);
    attr_seconds=0; entry_seconds=0;
    assert(md_fuse_connection_write(connection,&invalid,sizeof(invalid))==sizeof(invalid));
    puts("PASS FUSE attribute leases: independent TTL, invalidation, late replies, expiry and overflow");
}
static void lease_permissions(struct md_fs_context *context) {
    struct md_fs_request q={.operation=MD_FS_MKDIR,.directory={-1,-1},.path={"/fuse-permissions",NULL},.mode=0755};
    struct md_fs_result r;
    md_fs_context_execute(context,&q,&r,NULL); assert(!r.error);
    struct md_control_file device={0};
    q=(struct md_fs_request){.operation=MD_FS_OPEN,.directory={-1,-1},.path={"/dev/fuse",NULL},.flags=O_RDWR};
    struct md_fs_output output={.control=capture,.control_context=&device};
    md_fs_context_execute(context,&q,&r,&output); assert(!r.error && r.fd>=0);
    int fd=r.fd;
    char options[128]="fuse\0";
    const size_t prefix=sizeof("fuse\0");
    size_t size=prefix+snprintf(options+prefix,sizeof(options)-prefix,
        "fd=%d,rootmode=40000,user_id=0,group_id=0,default_permissions",fd)+1;
    q=(struct md_fs_request){.operation=MD_FS_MOUNT,.mode=MD_FS_MOUNT_FUSE,.directory={-1,fd},
        .path={"/fuse-permissions",""},.control=device,.value=options,.value_size=size};
    md_fs_context_execute(context,&q,&r,NULL); assert(!r.error); close(fd);
    struct md_fuse_connection *connection=md_fuse_device_connection(device); assert(connection);
    r=run(context,connection,MD_FS_STAT,"/",0,NULL,0);
    assert(!r.error); unsigned parent_mode=r.info.mode&07777;
    /* This empty fixture's outer root belongs to the Android shell UID.
     * Permit traversal so the capability-free checks reach the FUSE provider. */
    q=(struct md_fs_request){.operation=MD_FS_CHMOD,.directory={-1,-1},.path={"/",NULL},.mode=0755};
    assert(!run_request(context,connection,&q,(struct md_fs_output){0}).error);
    uint64_t caps=context->task.identity.caps.effective;
    context->task.identity.caps.effective=0;
    entry_seconds=60;
    r=run(context,connection,MD_FS_STAT,"/fuse-permissions/file",0,NULL,0);
    if (r.error) fprintf(stderr,"FUSE lease initial permissions: %d\n",r.error);
    assert(!r.error);
    unsigned names=lookups,attrs=getattrs;
    assert(!run(context,connection,MD_FS_STAT,"/fuse-permissions/file",0,NULL,0).error);
    assert(lookups==names && getattrs>attrs);
    root_mode=0600;
    assert(run(context,connection,MD_FS_STAT,"/fuse-permissions/file",0,NULL,0).error==-EACCES);
    assert(lookups==names);
    root_mode=0755; file_mode=0;
    r=run(context,connection,MD_FS_STAT,"/fuse-permissions/file",0,NULL,0);
    assert(!r.error && !(r.info.mode&0777));
    q=(struct md_fs_request){.operation=MD_FS_ACCESS,.directory={-1,-1},
        .path={"/fuse-permissions/file",NULL},.mode=R_OK,.flags=MD_AT_EACCESS};
    r=run_request(context,connection,&q,(struct md_fs_output){0});
    if (r.error!=-EACCES) fprintf(stderr,"FUSE lease access denial: %d\n",r.error);
    assert(r.error==-EACCES);
    assert(lookups==names);
    q.flags=0; /* Real-ID access uses root's permitted, not effective, capabilities. */
    assert(!run_request(context,connection,&q,(struct md_fs_output){0}).error);
    q.flags=MD_AT_EACCESS;
    file_mode=0644;
    assert(!run_request(context,connection,&q,(struct md_fs_output){0}).error);
    attr_seconds=60;
    assert(!run_request(context,connection,&q,(struct md_fs_output){0}).error);
    attrs=getattrs;
    context->task.identity.uid.fs=1234;
    q.mode=W_OK;
    assert(run_request(context,connection,&q,(struct md_fs_output){0}).error==-EACCES);
    assert(getattrs==attrs); /* Leased metadata is not a cached permission decision. */
    context->task.identity.uid.fs=0;
    assert(!run_request(context,connection,&q,(struct md_fs_output){0}).error && getattrs==attrs);
    md_control_file_release(device);
    assert(run(context,NULL,MD_FS_STAT,"/fuse-permissions/file",0,NULL,0).error==-ENOTCONN);
    struct md_proc_file *opened=NULL;
    q=(struct md_fs_request){.operation=MD_FS_OPEN,.directory={-1,-1},
        .path={"/fuse-permissions",NULL},.flags=O_RDONLY|O_DIRECTORY};
    r=run_request(context,NULL,&q,(struct md_fs_output){.control=capture_file,.control_context=&opened});
    assert(r.error==-ENOTCONN && !opened);
    context->task.identity.caps.effective=caps; entry_seconds=0; attr_seconds=0;
    q=(struct md_fs_request){.operation=MD_FS_CHMOD,.directory={-1,-1},.path={"/",NULL},.mode=parent_mode};
    assert(!run_request(context,NULL,&q,(struct md_fs_output){0}).error);
    puts("PASS FUSE name leases preserve parent access, file permissions and daemon disconnect");
}
static void mount_identity(struct md_filesystem *fs) {
    for (unsigned info=0;info<2;++info) {
        int fd=md_fs_mount_table(fs,info,0); assert(fd>=0);
        FILE *table=fdopen(fd,"r"); assert(table);
        char *line=NULL; size_t size=0; unsigned found=0;
        while (getline(&line,&size,table)>0) {
            if (!strstr(line," /fuse ") && !strstr(line," /fuse-readonly ")) continue;
            assert(strstr(line,info ? " - fuse.fixture fixture\\040\\011\\012\\134 "
                                   : "fixture\\040\\011\\012\\134 /fuse"));
            if (!info) assert(strstr(line," fuse.fixture "));
            ++found;
        }
        assert(!ferror(table) && found==2); free(line); fclose(table);
    }
    puts("PASS FUSE mount tables preserve subtype and escaped source across bind mounts");
}
void md_test_fuse_filesystem(struct md_filesystem *fs) {
    image_cache_bounds();
    extern void md_test_watch_source(void);
    md_test_watch_source();
    struct md_fs_context context={.filesystem=fs,.has_identity=1,.task={.identity=md_identity_new(0,0)}};
    struct md_fs_request q={.operation=MD_FS_MKDIR,.directory={-1,-1},.path={"/fuse",NULL},.mode=0755};
    struct md_fs_result r; md_fs_context_execute(&context,&q,&r,NULL); assert(!r.error);
    struct md_control_file device={0};
    q=(struct md_fs_request){.operation=MD_FS_OPEN,.directory={-1,-1},.path={"/dev/fuse",NULL},.flags=O_RDWR};
    struct md_fs_output output={.control=capture,.control_context=&device};
    md_fs_context_execute(&context,&q,&r,&output); assert(!r.error && r.fd>=0 && device.ops);
    int fd=r.fd;
    char options[128]="fuse.fixture\0fixture \t\n\\";
    const size_t prefix=sizeof("fuse.fixture\0fixture \t\n\\");
    size_t size=prefix+snprintf(options+prefix,sizeof(options)-prefix,
        "fd=%d,rootmode=40000,user_id=0,group_id=0",fd)+1;
    q=(struct md_fs_request){.operation=MD_FS_MOUNT,.mode=MD_FS_MOUNT_FUSE,.directory={-1,fd},
        .path={"/fuse",""},.control=device,.value=options,.value_size=size};
    for (unsigned n=0;n<size;++n) { q.value_size=n; assert(md_fs_request_validate(&q)==-EINVAL); }
    q.value_size=size+1; assert(md_fs_request_validate(&q)==-EINVAL);
    q.value_size=size; options[0]='x'; assert(md_fs_request_validate(&q)==-EINVAL); options[0]='f';
    assert(!md_fs_request_validate(&q));
    md_fs_context_execute(&context,&q,&r,NULL);
    if (r.error) fprintf(stderr,"FUSE mount: %d\n",r.error);
    assert(!r.error); close(fd);
    struct md_fuse_connection *connection=md_fuse_device_connection(device); assert(connection);
    r=run(&context,connection,MD_FS_STAT,"/fuse",0,NULL,0); assert(!r.error && S_ISDIR(r.info.mode));
    r=run(&context,connection,MD_FS_STAT,"/fuse/file",0,NULL,0); assert(!r.error && r.info.size==42 && r.info.inode==2);
    r=run(&context,connection,MD_FS_STAT,"/fuse/link",0,NULL,0); assert(!r.error && r.info.size==42);
    r=run(&context,connection,MD_FS_STAT,"/fuse/link",AT_SYMLINK_NOFOLLOW,NULL,0); assert(!r.error && S_ISLNK(r.info.mode));
    char link[128]; r=run(&context,connection,MD_FS_READLINK,"/fuse/link",0,link,sizeof(link));
    assert(!r.error && r.size==4 && !memcmp(link,"file",4));
    r=run(&context,connection,MD_FS_STAT,"/fuse/absent",0,NULL,0); assert(r.error==-ENOENT);
    struct md_fs_statfs statfs;
    r=run(&context,connection,MD_FS_STATFS,"/fuse",0,&statfs,sizeof(statfs));
    assert(!r.error && statfs.type==FUSE_SUPER_MAGIC && statfs.available_blocks==40);
    assert((statfs.flags&(MS_RDONLY|MS_NOSUID|MS_NODEV))==(MS_NOSUID|MS_NODEV));
    struct md_fs_statfs original=statfs;
    q=(struct md_fs_request){.operation=MD_FS_MKDIR,.directory={-1,-1},.path={"/fuse-readonly",NULL},.mode=0755};
    assert(!run_request(&context,connection,&q,(struct md_fs_output){0}).error);
    q=(struct md_fs_request){.operation=MD_FS_MOUNT,.mode=MD_FS_MOUNT_BIND,.flags=MS_BIND,
        .directory={-1,-1},.path={"/fuse-readonly","/fuse"}};
    assert(!run_request(&context,connection,&q,(struct md_fs_output){0}).error);
    mount_identity(fs);
    struct md_fs_anchor *retained=NULL;
    struct md_fs_request capture_path={.operation=MD_FS_TASK_PATH,.directory={-1,-1},
        .path={"/fuse-readonly",NULL},.mode=MD_FS_TASK_CHDIR};
    struct md_fs_result retained_fd=run_request(&context,connection,&capture_path,
        (struct md_fs_output){.origin=&retained});
    assert(!retained_fd.error && retained_fd.fd>=0 && retained);
    q.mode=MD_FS_MOUNT_REMOUNT; q.flags=MS_REMOUNT|MS_BIND|MS_RDONLY|MS_NOSUID|MS_NODEV|MS_NOEXEC;
    q.path[1]=NULL;
    assert(!run_request(&context,connection,&q,(struct md_fs_output){0}).error);
    r=run(&context,connection,MD_FS_STATFS,"/fuse-readonly/file",0,&statfs,sizeof(statfs));
    assert(!r.error && statfs.flags==(MS_RDONLY|MS_NOSUID|MS_NODEV|MS_NOEXEC));
    assert(!memcmp(statfs.fsid,original.fsid,sizeof(statfs.fsid)));
    struct md_fs_request by_fd={.operation=MD_FS_STATFS,.directory={retained_fd.fd,-1},.origin={retained,NULL}};
    r=run_request(&context,connection,&by_fd,(struct md_fs_output){.data=&statfs,.capacity=sizeof(statfs)});
    assert(!r.error && statfs.flags==(MS_RDONLY|MS_NOSUID|MS_NODEV|MS_NOEXEC));
    close(retained_fd.fd); md_fs_anchor_release(retained);
    r=run(&context,connection,MD_FS_STATFS,"/fuse/file",0,&statfs,sizeof(statfs));
    assert(!r.error && statfs.flags==original.flags);
    /* Until an operation has a protocol implementation it cannot use carriers. */
    r=run(&context,connection,MD_FS_OPEN,"/fuse/file",O_RDONLY,NULL,0); assert(r.error==-EOPNOTSUPP && r.fd<0);
    files(&context,connection);
    image_cache(&context,connection);
    paged_reads(&context,connection);
    close_completion(&context,connection);
    append_ordering(&context,connection);
    carrier_capacity(&context,connection);
    mutations(&context,connection);
    xattrs(&context,connection);
    notifications(&context,connection);
    name_leases(&context,connection);
    attribute_leases(&context,connection);
    lease_permissions(&context);
    shared_mappings(&context,connection);
    q=(struct md_fs_request){.operation=MD_FS_STAT,.directory={-1,-1},.path={"/fuse/file",NULL}};
    struct md_fs_continuation *pending=NULL; output=(struct md_fs_output){.continuation=&pending};
    md_fs_context_execute(&context,&q,&r,&output); assert(r.error==-EINPROGRESS && pending);
    md_control_file_release(device);
    assert(pending->ready(pending));
    struct md_fs_continuation *old=pending; pending=NULL;
    md_fs_context_resume(&context,old,&r,&output);
    assert(!md_fs_continuation_pending(old,&pending,&r) && r.error==-ECONNABORTED);
    assert(run(&context,NULL,MD_FS_STAT,"/fuse/file",0,NULL,0).error==-ENOTCONN);
    assert(run(&context,NULL,MD_FS_STAT,"/fuse-readonly/file",0,NULL,0).error==-ENOTCONN);
    r=run(&context,NULL,MD_FS_STAT,"/",0,NULL,0); assert(!r.error);
    md_fs_context_end(&context);
    printf("PASS FUSE filesystem: mount, INIT, lookup, symlinks, metadata, statfs and daemon exit (%u packets)\n",requests);
}
