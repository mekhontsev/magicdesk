#define _GNU_SOURCE
#include "fs_mount_internal.h"
#include "fs_continuation.h"
#include "procfs_view.h"
#include "proc_file.h"
#include "task_resources.h"
#include "inode_internal.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

static struct { unsigned admitted,completed,cancelled; int ready,capture; } calls;
struct lookup {
    struct md_provider_lookup base;
    struct md_fs_task_input input;
    int directory,done;
    char name[NAME_MAX+1],path[PATH_MAX];
};
static int ready(struct md_provider_lookup *p) {
    return p->task_input ? p->task_input->completed : calls.ready;
}
static int complete(struct md_provider_lookup *base) {
    struct lookup *p=(void *)base;
    assert(ready(base) && !p->done); p->done=1; ++calls.completed;
    if (base->task_input) {
        if (p->input.error) return p->input.error;
        int fd=p->input.descriptor; p->input.descriptor=-1; return fd;
    }
    int fd=openat(p->directory,p->name,O_PATH|O_NOFOLLOW|O_CLOEXEC);
    return fd<0 ? -errno : fd;
}
static void destroy(struct md_provider_lookup *base) {
    struct lookup *p=(void *)base;
    if (!p->done) ++calls.cancelled;
    if (p->input.descriptor>=0) close(p->input.descriptor);
    close(p->directory); free(p);
}
static int lookup(void *context,int directory,const char *name,const struct md_control_actor *actor,
        struct md_provider_lookup **out) {
    (void)context; (void)actor;
    struct lookup *p=calloc(1,sizeof(*p)); assert(p);
    p->base=(struct md_provider_lookup){.ready=ready,.complete=complete,.destroy=destroy};
    p->input.descriptor=-1;
    p->directory=fcntl(directory,F_DUPFD_CLOEXEC,3); assert(p->directory>=0);
    assert(strlen(name)<=NAME_MAX); strcpy(p->name,name);
    if (calls.capture) {
        snprintf(p->path,sizeof(p->path),"/proc/self/fd/%d/%s",p->directory,name);
        p->input.request=(struct md_fs_task_capture){p->path,O_PATH|O_NOFOLLOW|O_CLOEXEC};
        p->base.task_input=&p->input; p->base.metadata=&p->input.metadata;
    }
    ++calls.admitted; calls.ready=0; *out=&p->base; return -EINPROGRESS;
}
static const struct md_provider_tree_ops operations={.lookup=lookup};
static void proc_capture_admission(void) {
    struct md_identity root=md_identity_new(0,0);
    struct md_inode_store store={.identity=&root};
    struct md_task_registry *tasks=md_task_registry_create(); assert(tasks);
    struct md_task_resources resources={0};
    assert(!md_task_registry_publish(tasks,INT_MAX,INT_MAX,&root,&resources));
    struct md_task_snapshot actor={.tid=getpid(),.tgid=getpid()};
    struct md_filesystem fs={.tasks=tasks,.store=&store,.actor=&actor};
    struct md_fs_request request={.operation=MD_FS_OPEN,.directory={-1,-1},.flags=O_RDONLY};
    struct md_fs_result result;
    struct md_fs_output output={0};
    char path[64]; snprintf(path,sizeof(path),"/proc/%d/stat",INT_MAX);
    assert(md_fs_proc_task(&fs,NULL,path,&request,&result,&output));
    assert(result.error==-ENOENT && result.requires_task_capture);
    root.caps.effective=0;
    assert(md_fs_proc_task(&fs,NULL,path,&request,&result,&output));
    assert(result.error==-ENOENT && !result.requires_task_capture);
    md_task_registry_destroy(tasks); md_identity_release(&root);
    puts("PASS proc capture admission: denied native observation requests task context only with guest authority");
}
static void captured_proc_identity(enum md_proc_file_kind kind,int snapshot) {
    const pid_t number=INT_MAX;
    struct md_task_registry *tasks=md_task_registry_create(); assert(tasks);
    struct md_filesystem fs={.tasks=tasks};
    struct md_identity before=md_identity_new(1234,1235),after=md_identity_new(5678,5679);
    struct md_task_resources resources={0};
    assert(!md_task_registry_publish(tasks,number,number,&before,&resources));
    struct md_task_snapshot target={0};
    assert(!md_task_registry_proc(tasks,number,&target));
    uint64_t generation=target.generation; assert(generation);
    md_task_snapshot_release(&target);
    int directory=md_proc_file_directory(kind);
    int flags=directory ? O_RDONLY|O_DIRECTORY : O_RDONLY;
    int fd=open(directory ? "/proc/self" : "/proc/self/status",flags|O_CLOEXEC); assert(fd>=0);
    struct stat raw,metadata;
    assert(!fstat(fd,&raw)); metadata=raw;
    struct md_proc_file_node node;
    md_proc_file_task_metadata(number,&before,NULL,kind,&node,&metadata);
    struct md_proc_file *file=md_proc_file_open(NULL,kind,&before,flags); assert(file);
    md_proc_file_node(file,node);
    assert(!md_proc_file_backing(file,fd,&raw,generation));
    if (snapshot) {
        char entries[16384];
        ssize_t size=syscall(SYS_getdents64,fd,entries,sizeof(entries)); assert(size>0);
        assert(md_proc_file_directory_snapshot(file,entries,1)==-EIO);
        assert(!md_proc_file_directory_snapshot(file,entries,(size_t)size));
    }
    close(fd);
    struct stat st;
    assert(!md_fs_proc_control_stat(&fs,md_proc_file_control(file),&st) && st.st_uid==1234);
    assert(!!S_ISDIR(st.st_mode)==directory);
    uint64_t inode=st.st_ino;
    assert(!md_proc_file_retained_stat(file,tasks,1,&st) && st.st_uid==1234 && st.st_ino==inode);
    struct md_control_opener opener={.identity=&before};
    struct md_proc_file *reopened=md_proc_file_reopen(file,&opener,flags); assert(reopened);
    md_proc_file_release(file); file=reopened;
    assert(md_proc_file_backing_generation(file)==generation);
    if (directory) {
        struct md_proc_file_io io={0};
        char entries[16384];
        assert(md_proc_file_io_begin(&io,file,MD_PROC_FILE_GETDENTS,0,0,0,sizeof(entries),NULL)>=0);
        io.tasks=tasks;
        long size=md_proc_file_io_execute(&io,&before,NULL,entries,sizeof(entries),(size_t)getpagesize());
        assert(size>0);
        assert(!md_proc_file_io_cancel(&io));
    }
    assert(!md_task_registry_publish(tasks,number,number,&after,&resources));
    assert(!md_fs_proc_control_stat(&fs,md_proc_file_control(file),&st) && st.st_uid==5678);
    assert(!md_proc_file_retained_stat(file,tasks,1,&st) && st.st_uid==5678);
    md_task_registry_forget(tasks,number);
    assert(!md_fs_proc_control_stat(&fs,md_proc_file_control(file),&st) && st.st_uid==0);
    assert(md_proc_file_retained_stat(file,tasks,1,&st)==-ESRCH);
    if (directory) {
        struct md_proc_file_io io={0};
        char entries[16384];
        assert(md_proc_file_io_begin(&io,file,MD_PROC_FILE_GETDENTS,0,0,0,sizeof(entries),NULL)>=0);
        io.tasks=tasks;
        assert(md_proc_file_io_execute(&io,&before,NULL,entries,sizeof(entries),(size_t)getpagesize())==-ESRCH);
        assert(!md_proc_file_io_cancel(&io));
    }
    assert(!md_task_registry_publish(tasks,number,number,&after,&resources));
    assert(!md_fs_proc_control_stat(&fs,md_proc_file_control(file),&st) && st.st_uid==0 && st.st_ino==inode);
    assert(md_proc_file_retained_stat(file,tasks,1,&st)==-ESRCH);
    md_proc_file_release(file); md_task_registry_destroy(tasks);
    md_identity_release(&before); md_identity_release(&after);
    puts("PASS captured proc metadata: credentials refresh, reopen, retirement and PID reuse");
}
static struct md_fs_result run(struct md_filesystem *fs,const char *path,unsigned flags,unsigned expected) {
    struct md_fs_context context={.filesystem=fs};
    struct md_fs_request q={.operation=MD_FS_STAT,.directory={-1,-1},.path={path,NULL},.flags=flags};
    struct md_fs_result r;
    struct md_fs_continuation *pending=NULL;
    struct md_fs_output output={.continuation=&pending};
    unsigned before=calls.admitted;
    md_fs_context_execute(&context,&q,&r,&output);
    for (unsigned waits=0;r.error==-EINPROGRESS;++waits) {
        assert(waits<32 && pending && !pending->ready(pending));
        /* Another operation progresses while the provider has not replied. */
        struct md_fs_request other={.operation=MD_FS_STAT,.directory={-1,-1},.path={"/",NULL}};
        struct md_fs_result result; md_fs_execute(fs,&other,&result,NULL); assert(!result.error);
        calls.ready=1;
        assert(!!pending->task_input==calls.capture);
        if (pending->task_input) {
            struct md_fs_task_input *input=pending->task_input;
            input->descriptor=open(input->request.path,input->request.flags);
            input->error=input->descriptor<0 ? -errno : fstat(input->descriptor,&input->metadata) ? -errno : 0;
            input->completed=1;
        }
        assert(pending->ready(pending));
        struct md_fs_continuation *old=pending; pending=NULL;
        md_fs_context_resume(&context,old,&r,&output);
        int suspended=md_fs_continuation_pending(old,&pending,&r);
        assert(suspended==(r.error==-EINPROGRESS));
    }
    assert(!pending && calls.admitted-before==expected);
    return r;
}
static void continuation_descriptors(struct md_filesystem *fs) {
    int fd=open("/proc/self",O_PATH|O_DIRECTORY|O_CLOEXEC); assert(fd>=0);
    struct stat st; assert(!fstat(fd,&st));
    struct md_fs_anchor *origin=NULL;
    assert(!md_view_anchor_pin(fs,NULL,NULL,fd,&st,&origin));
    int pin=md_fs_anchor_fd(origin);
    struct md_fs_request q={.operation=MD_FS_ACCESS,.directory={pin,fd},.origin={origin,origin}};
    struct md_fs_continuation c={0};
    assert(!md_fs_continuation_capture(&c,&q));
    assert(c.request.directory[0]==pin && c.request.directory[1]!=fd && c.request.directory[1]!=pin);
    int copy=c.request.directory[1];
    assert(!close(fd)); md_fs_anchor_release(origin);
    assert(!fstat(pin,&st) && !md_fs_anchor_check_fd(c.request.origin[1],copy));
    md_fs_continuation_clear(&c);
    assert(fcntl(pin,F_GETFD)==-1 && errno==EBADF);
    assert(fcntl(copy,F_GETFD)==-1 && errno==EBADF);
    puts("PASS continuation descriptors: owned pin reuse, external copy and exact release");
}
void md_test_fs_provider_async(struct md_filesystem *fs) {
    continuation_descriptors(fs);
    proc_capture_admission();
    captured_proc_identity(MD_PROC_FILE_STATUS,0);
    captured_proc_identity(MD_PROC_FILE_PROCESS_DIRECTORY,0);
    captured_proc_identity(MD_PROC_FILE_PROCESS_DIRECTORY,1);
    captured_proc_identity(MD_PROC_FILE_CGROUP,0);
    captured_proc_identity(MD_PROC_FILE_UID_MAP,0);
    struct md_fs_request mkdir={.operation=MD_FS_MKDIR,.directory={-1,-1},.path={"/deferred",NULL},.mode=0755};
    struct md_fs_result result; md_fs_execute(fs,&mkdir,&result,NULL); assert(!result.error);
    int parent=md_fs_private_mounts_parent(fs->store); assert(parent>=0);
    struct md_provider_tree *tree=md_provider_tree_new(parent,-1,NULL,0,&operations,NULL); close(parent); assert(tree);
    int root=md_provider_tree_root(tree); assert(!mkdirat(root,"dir",0700));
    int fd=openat(root,"dir/leaf",O_CREAT|O_RDWR|O_CLOEXEC,0600); assert(fd>=0);
    assert(write(fd,"data",4)==4); close(fd); assert(!symlinkat("dir/leaf",root,"link"));
    assert(!md_fs_provider_mount(fs,tree,"/deferred"));
    result=run(fs,"/deferred/dir/leaf",0,2); assert(!result.error && result.info.size==4);
    result=run(fs,"/deferred/dir/../link",0,5); assert(!result.error && result.info.size==4);
    result=run(fs,"/deferred/link",AT_SYMLINK_NOFOLLOW,1); assert(!result.error && S_ISLNK(result.info.mode));
    result=run(fs,"/deferred/dir/leaf/",0,2); assert(result.error==-ENOTDIR);
    result=run(fs,"/deferred/absent",0,1); assert(result.error==-ENOENT);
    unsigned before=calls.admitted;
    struct md_fs_request q={.operation=MD_FS_STAT,.directory={-1,-1},.path={"/deferred/dir/leaf",NULL}};
    md_fs_execute(fs,&q,&result,NULL); assert(result.error==-EOPNOTSUPP && calls.admitted==before);
    struct md_fs_continuation *pending=NULL;
    struct md_fs_output output={.continuation=&pending};
    md_fs_execute(fs,&q,&result,&output); assert(result.error==-EINPROGRESS && pending);
    pending->destroy(pending); assert(calls.cancelled==1 && calls.completed+calls.cancelled==calls.admitted);
    calls.capture=1;
    result=run(fs,"/deferred/dir/leaf",0,2); assert(!result.error && result.info.size==4);
    result=run(fs,"/deferred/dir/../link",0,5); assert(!result.error && result.info.size==4);
    result=run(fs,"/deferred/absent",0,1); assert(result.error==-ENOENT);
    pending=NULL;
    md_fs_execute(fs,&q,&result,&output); assert(result.error==-EINPROGRESS && pending && pending->task_input);
    struct md_fs_task_input *input=pending->task_input;
    input->descriptor=open(input->request.path,input->request.flags); assert(input->descriptor>=0);
    fd=input->descriptor;
    pending->destroy(pending);
    assert(fcntl(fd,F_GETFD)==-1 && errno==EBADF);
    assert(calls.cancelled==2 && calls.completed+calls.cancelled==calls.admitted);
    md_provider_tree_release(tree);
    puts("PASS asynchronous path walk: exact-stage resume, symlinks, errors, cancellation and unrelated progress");
    puts("PASS task-affine lookup: metadata receipt, chained suspension, captured FD cancellation and exact ownership");
}
