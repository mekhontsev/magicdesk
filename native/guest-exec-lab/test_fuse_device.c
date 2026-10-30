#define _GNU_SOURCE
#include "device_tree.h"
#include "fuse_device.h"
#include "fuse_transport.h"
#include "fuse_session.h"
#include "fs_mount_internal.h"
#include "fs_storage.h"
#include "fs_continuation.h"
#include "inode_internal.h"
#include "mount_graph.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/sysmacros.h>
#include <sys/mount.h>
#include <unistd.h>
void md_test_fs_provider_async(struct md_filesystem *);
void md_test_fuse_filesystem(struct md_filesystem *);

static int capture(void *context,const struct md_control_file_ops *ops,void *node,
        const struct md_identity *id,int flags,struct md_fs_anchor *origin) {
    (void)id; (void)origin;
    void *opened=flags&O_PATH ? ops->retain(node) : ops->open(node,&(struct md_control_opener){.identity=id},flags);
    if (!opened) return -errno;
    *(struct md_control_file *)context=(struct md_control_file){ops,opened}; return 0;
}
static struct md_fs_result request(struct md_filesystem *fs,unsigned operation,const char *path,
        unsigned flags,struct md_control_file *control) {
    struct md_fs_request q={.operation=operation,.flags=flags,.mode=flags&O_CREAT ? 0600 : 0,
        .directory={-1,-1},.path={path,NULL}};
    struct md_fs_output output={.control=control ? capture : NULL,.control_context=control};
    struct md_fs_result r; md_fs_execute(fs,&q,&r,&output); return r;
}
static void check_device(struct md_filesystem *fs) {
    struct md_fs_result r=request(fs,MD_FS_STAT,"/dev/fuse",0,NULL);
    if (r.error) fprintf(stderr,"stat fuse: %d\n",r.error);
    assert(!r.error && S_ISCHR(r.info.mode) && major(r.info.rdev)==10 && minor(r.info.rdev)==229);
    struct md_control_file file={0};
    r=request(fs,MD_FS_OPEN,"/dev/fuse",O_RDONLY,&file);
    assert(r.error==-EINVAL && !file.ops && r.fd<0);
    r=request(fs,MD_FS_OPEN,"/dev/fuse",O_PATH,&file);
    assert(!r.error && r.fd>=0 && md_fuse_device_is_node(file));
    assert(!md_fuse_device_connection(file)); close(r.fd); md_control_file_release(file); file=(struct md_control_file){0};
    r=request(fs,MD_FS_OPEN,"/dev/fuse",O_RDWR,&file);
    assert(!r.error && r.fd>=0 && file.ops); close(r.fd);
    struct md_fuse_connection *connection=md_fuse_connection_retain(md_fuse_device_connection(file));
    assert(connection && !md_fuse_device_is_node(file));
    struct md_fuse_request *pending=NULL;
    struct fuse_in_header h={.opcode=FUSE_GETATTR,.nodeid=FUSE_ROOT_ID};
    assert(!md_fuse_request_submit(connection,&h,NULL,0,0,0,NULL,&pending));
    struct fuse_in_header read;
    assert(file.ops->read(file.context,NULL,&read,sizeof(read),0)==sizeof(read));
    file.ops->read_commit(file.context,sizeof(read));
    struct fuse_out_header out={.len=sizeof(out),.unique=read.unique,.error=-ENOENT};
    assert(file.ops->write(file.context,NULL,&out,sizeof(out),0)==sizeof(out));
    assert(md_fuse_request_result(pending,NULL,0)==-ENOENT); md_fuse_request_release(pending);
    assert(!md_fuse_request_submit(connection,&h,NULL,0,0,0,NULL,&pending));
    md_control_file_release(file);
    assert(md_fuse_request_result(pending,NULL,0)==-ECONNABORTED);
    md_fuse_request_release(pending); md_fuse_connection_release(connection);
}
static void portable_metadata(struct md_filesystem *fs,int imported) {
    struct stat host_before,host_after;
    assert(!stat("/dev/urandom",&host_before));
    struct md_fs_context root={.filesystem=fs,.has_identity=1,.task={.identity=md_identity_new(0,0)}};
    struct md_fs_request q={.operation=MD_FS_STAT,.directory={-1,-1},.path={"/dev/urandom",NULL}};
    struct md_fs_result r;
    md_fs_context_execute(&root,&q,&r,NULL);
    assert(!r.error && S_ISCHR(r.info.mode) && r.info.rdev==makedev(1,9));
    if (imported) assert(r.info.uid==123 && r.info.gid==456 && (r.info.mode&0777)==0600);
    q.operation=MD_FS_CHOWN; q.attributes.uid=123; q.attributes.gid=456;
    md_fs_context_execute(&root,&q,&r,NULL); assert(!r.error);
    q.operation=MD_FS_CHMOD; q.mode=0600;
    md_fs_context_execute(&root,&q,&r,NULL); assert(!r.error);
    struct md_fs_context owner={.filesystem=fs,.has_identity=1,.task={.identity=md_identity_new(123,456)}};
    struct md_fs_context other={.filesystem=fs,.has_identity=1,.task={.identity=md_identity_new(124,456)}};
    q.operation=MD_FS_OPEN; q.flags=O_RDONLY; q.mode=0;
    md_fs_context_execute(&other,&q,&r,NULL); assert(r.error==-EACCES);
    md_fs_context_execute(&owner,&q,&r,NULL);
    if (r.error || r.fd<0) fprintf(stderr,"portable device open: error=%d fd=%d completion=%u\n",
        r.error,r.fd,r.open_completion.kind);
    assert(!r.error && r.fd>=0);
    char byte; assert(read(r.fd,&byte,1)==1); close(r.fd);
    q=(struct md_fs_request){.operation=MD_FS_MOUNT,.mode=MD_FS_MOUNT_REMOUNT,
        .flags=MS_REMOUNT|MS_BIND|MS_RDONLY,.directory={-1,-1},.path={"/dev/urandom",NULL}};
    md_fs_context_execute(&root,&q,&r,NULL);
    if (imported) {
        /* Graph import borrows policy; only the original namespace may mutate it. */
        assert(r.error==-EPERM);
        goto checked;
    }
    if (r.error) fprintf(stderr,"portable device remount: %d\n",r.error);
    assert(!r.error);
    struct md_fs_request chown={.operation=MD_FS_CHOWN,.directory={-1,-1},.path={"/dev/urandom",NULL},
        .attributes={.uid=0,.gid=0}};
    md_fs_context_execute(&root,&chown,&r,NULL); assert(r.error==-EROFS);
    q.flags=MS_REMOUNT|MS_BIND;
    md_fs_context_execute(&root,&q,&r,NULL); assert(!r.error);
checked:
    assert(!stat("/dev/urandom",&host_after));
    assert(host_before.st_uid==host_after.st_uid && host_before.st_gid==host_after.st_gid
        && host_before.st_mode==host_after.st_mode);
    md_fs_context_end(&other); md_fs_context_end(&owner); md_fs_context_end(&root);
}
static void portable_directories(struct md_filesystem *fs) {
    const char *paths[]={"/dev","/dev/pts","/sys","/sys/class","/sys/fs"};
    for (unsigned i=0;i<sizeof(paths)/sizeof(*paths);++i) {
        struct md_fs_result r=request(fs,MD_FS_STAT,paths[i],0,NULL);
        assert(!r.error && S_ISDIR(r.info.mode) && !r.info.uid && !r.info.gid);
        unsigned mode=i<2 ? 0755 : 0555;
        assert((r.info.mode&07777)==mode);
        struct md_fs_result opened=request(fs,MD_FS_OPEN,paths[i],O_PATH|O_DIRECTORY,NULL);
        assert(!opened.error && opened.fd>=0);
        struct md_fs_request q={.operation=MD_FS_FSTAT,.directory={opened.fd,-1}};
        md_fs_execute(fs,&q,&r,NULL);
        assert(!r.error && !r.info.uid && !r.info.gid && (r.info.mode&07777)==mode);
        close(opened.fd);
    }
    struct md_fs_result r=request(fs,MD_FS_UNLINK,"/dev",AT_REMOVEDIR,NULL);
    assert(r.error==-EBUSY);
}
struct resumed { struct md_fs_continuation base; unsigned *destroyed; };
static int ready(struct md_fs_continuation *c) { (void)c; return 1; }
static void resume(struct md_fs_continuation *c,struct md_filesystem *fs,
        struct md_fs_result *r,const struct md_fs_output *output) {
    (void)output;
    assert(fs->store->identity && fs->store->identity->uid.effective==123);
    assert(!strcmp(c->request.path[0],"owned"));
    assert(c->request.value_size==4 && !memcmp(c->request.value,"data",4));
    assert(fcntl(c->request.directory[0],F_GETFD)>=0);
    *r=(struct md_fs_result){.fd=-1,.position=42};
}
static void destroy(struct md_fs_continuation *c) {
    struct resumed *r=(void *)c; ++*r->destroyed;
    md_fs_continuation_clear(c); free(r);
}
static void continuations(struct md_filesystem *fs) {
    unsigned destroyed=0;
    struct resumed *r=calloc(1,sizeof(*r)); assert(r);
    r->destroyed=&destroyed; r->base.ready=ready; r->base.resume=resume; r->base.destroy=destroy;
    int directory=open("/",O_PATH|O_CLOEXEC); assert(directory>=0);
    char path[]="owned",value[]="data";
    struct md_fs_request q={.operation=MD_FS_STAT,.directory={directory,-1},.path={path,NULL},.value=value,.value_size=4};
    assert(!md_fs_continuation_capture(&r->base,&q));
    close(directory); memset(path,'x',sizeof(path)-1); memset(value,0,sizeof(value));
    struct md_fs_context context={.filesystem=fs,.has_identity=1,.task={.identity=md_identity_new(123,456)}};
    struct md_fs_continuation *next=&r->base;
    struct md_fs_output output={.continuation=&next};
    struct md_fs_result result;
    const struct md_identity *before=fs->store->identity;
    md_fs_context_resume(&context,&r->base,&result,&output);
    assert(!result.error && result.position==42 && !next && fs->store->identity==before);
    assert(!md_fs_continuation_pending(&r->base,&next,&result));
    assert(destroyed==1);
    result.error=-EINPROGRESS;
    assert(!md_fs_continuation_pending(NULL,&next,&result) && result.error==-EPROTO);
    md_fs_context_end(&context);
}
static void sessions(void) {
    struct md_fuse_options options;
    assert(!md_fuse_options_parse("fd=7,rootmode=40000,user_id=123,group_id=456,default_permissions,max_read=8192",&options));
    assert(options.descriptor==7 && options.uid==123 && options.gid==456 && options.default_permissions
        && options.max_read==8192 && !options.allow_other);
    struct md_fuse_options invalid;
    const char *bad[]={"", "fd=7", "fd=-1,rootmode=40000,user_id=123,group_id=456",
        "fd=2147483648,rootmode=40000,user_id=123,group_id=456", "fd=7,rootmode=100000,user_id=123,group_id=456",
        "fd=7,rootmode=40000,user_id=123,group_id=456,", "fd=7,rootmode=40000,user_id=123,group_id=456,unknown"};
    for (unsigned i=0;i<sizeof(bad)/sizeof(*bad);++i) assert(md_fuse_options_parse(bad[i],&invalid)==-EINVAL);
    for (unsigned sample=0;sample<3;++sample) {
        int malformed=sample==2;
        struct md_fuse_connection *c=NULL; assert(!md_fuse_connection_new(&c));
        struct md_identity identity=md_identity_new(123,456);
        struct md_fuse_session *s=NULL,*duplicate=NULL;
        assert(!md_fuse_session_new(c,&options,&identity,999,NULL,&s));
        assert(md_fuse_session_new(c,&options,&identity,999,NULL,&duplicate)==-EBUSY && !duplicate);
        assert(md_fuse_session_ready(s)==0);
        struct { struct fuse_in_header header; uint32_t init[4]; } request;
        assert(md_fuse_connection_read(c,&request,sizeof(request))==sizeof(request));
        assert(request.header.opcode==FUSE_INIT && request.header.uid==123 && request.header.gid==456
            && request.header.pid==999 && request.init[0]==7 && request.init[1]==MD_FUSE_PROTOCOL_MINOR
            && !request.init[2] && request.init[3]==(FUSE_ATOMIC_O_TRUNC|FUSE_SPLICE_READ|FUSE_SPLICE_WRITE|FUSE_SPLICE_MOVE));
        md_fuse_connection_read_commit(c,sizeof(request));
        struct { struct fuse_out_header header; struct fuse_init_out body; } reply={
            .header={.len=sizeof(reply),.unique=request.header.unique},
            .body={.major=malformed ? 8 : 7,.minor=sample==1 ? 19 : MD_FUSE_PROTOCOL_MINOR,
                .max_write=65536,.flags=sample==0 ? FUSE_ATOMIC_O_TRUNC : 0}};
        if (sample==1) reply.header.len=sizeof(reply.header)+24;
        assert(md_fuse_connection_write(c,&reply,reply.header.len)==reply.header.len);
        assert(md_fuse_session_ready(s)==(malformed ? -EPROTO : 1));
        if (!malformed) {
            assert(md_fuse_session_minor(s)==reply.body.minor);
            assert(md_fuse_session_atomic_truncate(s)==(sample==0));
            assert(md_fuse_session_read_size(s)==8192 && md_fuse_session_write_size(s)==MD_FUSE_IO_SIZE);
            struct md_fuse_request *r=NULL;
            struct md_identity other=md_identity_new(1000,1000);
            assert(md_fuse_session_request(s,&other,999,FUSE_GETATTR,1,NULL,0,0,0,NULL,&r)==-EACCES && !r);
            md_identity_release(&other);
            assert(!md_fuse_session_request(s,&identity,999,FUSE_GETATTR,1,NULL,0,0,0,NULL,&r));
            md_fuse_session_release(s); s=NULL;
            assert(md_fuse_request_result(r,NULL,0)==-ECONNABORTED); md_fuse_request_release(r);
        }
        md_fuse_session_release(s); md_fuse_connection_release(c); md_identity_release(&identity);
    }
}
void md_test_fuse_device(const char *path) {
    alarm(30); setbuf(stdout,NULL);
    struct md_inode_store *store=NULL; assert(!md_inode_store_open(path,1,&store));
    assert(!md_inode_runtime_prepare(store));
    struct md_fs_request root_mode={.operation=MD_FS_CHMOD,.directory={-1,-1},.path={"/",NULL},.mode=0755};
    assert(!md_inode_metadata(store,&root_mode));
    struct md_fs_storage_cache storage={0};
    struct md_filesystem fs={.store=store,.storage=&storage};
    assert(!md_fs_mounts_open(&fs,NULL,0));
    struct md_provider_tree *sys=NULL,*dev=NULL;
    assert(!md_fs_sysfs_prepare(&fs,&sys));
    assert(!md_fs_devices_prepare(&fs,0,&dev)); fs.devices=dev;
    struct md_fs_context context={.filesystem=&fs};
    struct md_fs_request stat={.operation=MD_FS_STAT,.directory={-1,-1},.path={"/",NULL}};
    assert(md_fs_context_operation_window(&context,&stat,0)==store);
    struct md_inode_statistics stats={0}; md_inode_measure(store,&stats);
    for (unsigned i=0;i<32;++i) assert(!request(&fs,MD_FS_STAT,"/",0,NULL).error);
    assert(!stats.walk_components && !stats.walk_materializations); md_inode_measure(store,NULL);
    check_device(&fs); portable_metadata(&fs,0); portable_directories(&fs); continuations(&fs); sessions();
    struct md_fs_result shm=request(&fs,MD_FS_OPEN,"/dev/shm/test",O_CREAT|O_RDWR,NULL);
    assert(!shm.error && shm.fd>=0 && write(shm.fd,"ok",2)==2); close(shm.fd);
    assert(md_mount_graph_portable(fs.mounts));
    struct md_mount_graph_parcel parcel={0};
    assert(!md_mount_graph_export(fs.mounts,store->lifetime->root,&parcel));
    struct md_mount_namespace *imported=NULL;
    assert(!md_mount_graph_import(&parcel,&imported));
    struct md_filesystem foreign=fs; foreign.mounts=imported;
    check_device(&foreign);
    portable_metadata(&foreign,1);
    portable_directories(&foreign);
    shm=request(&foreign,MD_FS_OPEN,"/dev/shm/test",O_RDONLY,NULL);
    if (shm.error || shm.fd<0) fprintf(stderr,"imported shm: error=%d fd=%d host=%u\n",shm.error,shm.fd,shm.host_path);
    assert(!shm.error && shm.fd>=0); char bytes[2]; assert(read(shm.fd,bytes,2)==2 && !memcmp(bytes,"ok",2)); close(shm.fd);
    md_mount_namespace_release(imported);
    md_test_fs_provider_async(&fs);
    md_test_fuse_filesystem(&fs);
    md_provider_tree_release(dev); md_provider_tree_release(sys);
    md_fs_mounts_close(&fs); md_fs_storage_close(&storage); md_inode_store_close(store);
    puts("PASS FUSE device: typed authority, disconnect, portable namespace, shared memory, inode fast path and owned continuations");
    puts("PASS portable devices: guest ownership/DAC, native IO, read-only bind policy, graph transfer and unchanged host metadata");
}
