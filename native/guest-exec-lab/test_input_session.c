#define _GNU_SOURCE
#include "input_session.h"
#include "input_devices.h"
#include "fs_mount_internal.h"
#include "fs_storage.h"
#include "event_wait.h"
#include "task_registry.h"
#include "device_events.h"
#include "sysfs_view.h"
#include "mount_graph.h"
#include "inode_internal.h"
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/joystick.h>
#include <linux/netlink.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <sys/socket.h>
#include <sys/sysmacros.h>
#include <sys/un.h>
#include <sys/syscall.h>
#include <unistd.h>

struct server { int listener,connection,ready; pthread_t thread; };
static void write_all(int fd,const void *data,size_t size) {
    const char *p=data;
    while (size) { ssize_t n=send(fd,p,size,MSG_NOSIGNAL); assert(n>0); p+=n; size-=(size_t)n; }
}
static void send_frame(int fd,unsigned type,const void *payload,size_t size) {
    uint32_t header[]={0x4d444750,1,type,0,(uint32_t)size};
    write_all(fd,header,sizeof(header)); if (size) write_all(fd,payload,size);
}
static void *serve(void *context) {
    struct server *s=context;
    s->connection=accept4(s->listener,NULL,NULL,SOCK_CLOEXEC); assert(s->connection>=0);
    struct md_input_description d={.name="Gamepad fixture",.id={.bustype=6,.vendor=0x45e,.product=0xb20},.rumble=1};
    d.keys[BTN_A/8]|=1U<<(BTN_A%8); d.axes[ABS_X/8]|=1U<<(ABS_X%8);
    d.abs[ABS_X]=(struct input_absinfo){.minimum=-32767,.maximum=32767};
    send_frame(s->connection,1,&d,sizeof(d)); send_frame(s->connection,5,NULL,0);
    uint64_t one=1; assert(write(s->ready,&one,8)==8); return NULL;
}
static int capture(void *context,const struct md_control_file_ops *ops,void *node,const struct md_identity *id,int flags,
        struct md_fs_anchor *origin) {
    (void)id; (void)origin;
    struct md_control_file *file=context;
    void *opened=(flags&O_PATH) || !ops->open ? ops->retain(node) : ops->open(node,&(struct md_control_opener){.identity=id},flags);
    if (!opened) return -errno;
    *file=(struct md_control_file){ops,opened}; return 0;
}
static struct md_fs_result request(struct md_filesystem *fs,unsigned operation,const char *path,unsigned flags,
        struct md_control_file *file,struct md_fs_anchor **anchor) {
    struct md_fs_request q={.operation=operation,.flags=flags,.directory={-1,-1},.path={path,NULL}};
    struct md_fs_output output={.control=capture,.control_context=file,.origin=anchor};
    struct md_fs_result result; md_fs_execute(fs,&q,&result,&output); return result;
}
static void initial_sysfs(struct md_filesystem *fs) {
    const char *dirs[]={"/sys/block","/sys/bus","/sys/dev/block","/sys/dev/char","/sys/fs/cgroup"};
    for (unsigned i=0;i<sizeof(dirs)/sizeof(*dirs);++i) {
        struct md_fs_result st=request(fs,MD_FS_STAT,dirs[i],0,NULL,NULL);
        if (st.error) fprintf(stderr,"initial sysfs %s: %d\n",dirs[i],st.error);
        assert(!st.error && S_ISDIR(st.info.mode));
    }
    assert(request(fs,MD_FS_STAT,"/sys/devices/platform",0,NULL,NULL).error==-ENOENT);
    assert(request(fs,MD_FS_OPEN,"/sys/devices/system/cpu/possible",O_WRONLY,NULL,NULL).error==-EROFS);
    assert(request(fs,MD_FS_OPEN,"/sys/devices/system/cpu/possible",O_RDONLY|O_TRUNC,NULL,NULL).error==-EROFS);
    assert(!md_inode_symlink(fs->store,"/sys/devices/system/cpu/possible",-1,"/cpu-alias"));
    struct md_fs_result alias=request(fs,MD_FS_OPEN,"/cpu-alias",O_RDONLY,NULL,NULL);
    assert(!alias.error && alias.fd>=0); close(alias.fd);
    assert(!md_inode_unlink(fs->store,-1,"/cpu-alias",0));
    struct md_fs_result ordinary=request(fs,MD_FS_CREATE,"/ordinary",0,NULL,NULL);
    assert(!ordinary.error && ordinary.fd>=0); close(ordinary.fd);
    struct md_inode_statistics stats={0}; md_inode_measure(fs->store,&stats);
    struct md_fs_context context={.filesystem=fs};
    struct md_fs_request stat={.operation=MD_FS_STAT,.directory={-1,-1},.path={"/ordinary",NULL}};
    assert(md_fs_context_operation_window(&context,&stat,0)==fs->store);
    for (unsigned i=0;i<32;++i) assert(!request(fs,MD_FS_STAT,"/ordinary",0,NULL,NULL).error);
    assert(!stats.walk_components && !stats.walk_materializations);
    md_inode_measure(fs->store,NULL);
    assert(!request(fs,MD_FS_UNLINK,"/ordinary",0,NULL,NULL).error);
    assert(request(fs,MD_FS_UNLINK,"/sys",AT_REMOVEDIR,NULL,NULL).error==-EBUSY);
    /* Another namespace may rename the covered dentry. Admission uses its
     * inode identity, never the old spelling of the pathname. */
    assert(!md_inode_rename(fs->store,-1,"/sys",-1,"/moved-sys",0));
    alias=request(fs,MD_FS_OPEN,"/moved-sys/devices/system/cpu/possible",O_RDONLY,NULL,NULL);
    assert(!alias.error && alias.fd>=0); close(alias.fd);
    assert(!md_inode_rename(fs->store,-1,"/moved-sys",-1,"/sys",0));
    struct md_mount_graph_parcel parcel={0};
    assert(md_mount_graph_portable(fs->mounts));
    assert(!md_mount_graph_export(fs->mounts,fs->store->lifetime->root,&parcel));
    struct md_mount_namespace *imported=NULL;
    assert(!md_mount_graph_import(&parcel,&imported));
    struct md_filesystem foreign=*fs; foreign.mounts=imported;
    alias=request(&foreign,MD_FS_OPEN,"/sys/devices/system/cpu/possible",O_RDONLY,NULL,NULL);
    assert(!alias.error && alias.fd>=0); close(alias.fd);
    assert(request(&foreign,MD_FS_STAT,"/sys/devices/platform",0,NULL,NULL).error==-ENOENT);
    assert(request(&foreign,MD_FS_OPEN,"/sys/devices/system/cpu/possible",O_WRONLY,NULL,NULL).error==-EROFS);
    md_mount_namespace_release(imported);
    puts("PASS initial sysfs without input, inode fast path, symlink, rename and graph custody");
}
static void mount_request(struct md_filesystem *fs,unsigned mode,unsigned flags,
        const char *target,const char *source) {
    struct md_fs_request q={.operation=MD_FS_MOUNT,.mode=mode,.flags=flags,
        .directory={-1,-1},.path={target,source}};
    struct md_fs_result r; md_fs_execute(fs,&q,&r,NULL);
    if (r.error) fprintf(stderr,"mount %s from %s: %d\n",target,source ? source : "-",r.error);
    assert(!r.error);
}
static void shared_memory(struct md_filesystem *fs,const char *path) {
    struct md_fs_result r=request(fs,MD_FS_OPEN,path,O_RDWR,NULL,NULL);
    if (r.error) fprintf(stderr,"shared memory %s: %d\n",path,r.error);
    assert(!r.error && r.fd>=0);
    char bytes[2]; assert(read(r.fd,bytes,2)==2 && !memcmp(bytes,"ok",2)); close(r.fd);
}
static void initial_devices(struct md_filesystem *fs) {
    struct md_identity identity=md_identity_new(0,0);
    fs->store->identity=&identity;
    const char *dirs[]={"/devices-copy","/devices-again","/devices-flat","/memory-source","/dev/shm/nested"};
    for (unsigned i=0;i<sizeof(dirs)/sizeof(*dirs);++i)
        assert(!md_inode_mkdir(fs->store,-1,dirs[i],0755));
    const char *files[]={"/dev/shm/sentinel","/memory-source/sentinel"};
    for (unsigned i=0;i<2;++i) {
        int fd=md_inode_open(fs->store,-1,files[i],O_CREAT|O_RDWR,0600);
        assert(fd>=0 && write(fd,"ok",2)==2); close(fd);
    }
    mount_request(fs,MD_FS_MOUNT_BIND,MS_BIND,"/dev/shm/nested","/memory-source");
    mount_request(fs,MD_FS_MOUNT_BIND,MS_BIND|MS_REC,"/devices-copy","/dev");
    shared_memory(fs,"/devices-copy/shm/sentinel");
    shared_memory(fs,"/devices-copy/shm/nested/sentinel");
    mount_request(fs,MD_FS_MOUNT_BIND,MS_BIND,"/devices-copy/pts","/memory-source");
    mount_request(fs,MD_FS_MOUNT_BIND,MS_BIND|MS_REC,"/devices-again","/devices-copy");
    shared_memory(fs,"/devices-again/shm/sentinel");
    shared_memory(fs,"/devices-again/shm/nested/sentinel");
    shared_memory(fs,"/devices-again/pts/sentinel");
    struct md_fs_result r=request(fs,MD_FS_OPEN,"/devices-copy/null",O_WRONLY,NULL,NULL);
    assert(!r.error && r.fd>=0 && write(r.fd,"ok",2)==2); close(r.fd);
    mount_request(fs,MD_FS_MOUNT_BIND,MS_BIND,"/devices-flat","/dev");
    assert(request(fs,MD_FS_STAT,"/devices-flat/shm/sentinel",0,NULL,NULL).error==-ENOENT);
    assert(request(fs,MD_FS_STAT,"/devices-flat/shm/nested",0,NULL,NULL).error==-ENOENT);
    struct md_fs_anchor *detached=NULL;
    struct md_fs_request clone={.operation=MD_FS_OPEN_TREE,.mode=AT_RECURSIVE,
        .directory={-1,-1},.path={"/dev",NULL}};
    md_fs_execute(fs,&clone,&r,&(struct md_fs_output){.origin=&detached});
    assert(!r.error && r.fd>=0 && detached);
    assert(fcntl(r.fd,F_GETFL)&O_PATH);
    struct md_fs_request retained={.operation=MD_FS_OPEN,.flags=O_RDONLY,
        .directory={r.fd,-1},.origin={detached,NULL},.path={"shm/sentinel",NULL}};
    int root=r.fd; md_fs_execute(fs,&retained,&r,NULL);
    assert(!r.error && r.fd>=0); char bytes[2];
    assert(read(r.fd,bytes,2)==2 && !memcmp(bytes,"ok",2)); close(r.fd);
    close(root); md_fs_anchor_release(detached); assert(!md_view_mount_collect(fs));
    struct md_mount_graph_parcel parcel={0};
    assert(md_mount_graph_portable(fs->mounts));
    assert(!md_mount_graph_export(fs->mounts,fs->store->lifetime->root,&parcel));
    struct md_mount_namespace *imported=NULL;
    assert(!md_mount_graph_import(&parcel,&imported));
    struct md_filesystem foreign=*fs; foreign.mounts=imported;
    shared_memory(&foreign,"/devices-copy/shm/sentinel");
    shared_memory(&foreign,"/devices-copy/shm/nested/sentinel");
    shared_memory(&foreign,"/devices-again/shm/nested/sentinel");
    shared_memory(&foreign,"/devices-again/pts/sentinel");
    mount_request(fs,MD_FS_MOUNT_BIND,MS_BIND,"/devices-copy/null","/memory-source/sentinel");
    assert(!md_mount_graph_portable(fs->mounts));
    assert(md_mount_graph_export(fs->mounts,fs->store->lifetime->root,&parcel)==-EOPNOTSUPP);
    assert(request(&foreign,MD_FS_STAT,"/devices-copy/null",0,NULL,NULL).error==-EOPNOTSUPP);
    shared_memory(fs,"/devices-copy/null");
    mount_request(fs,MD_FS_MOUNT_UMOUNT,MNT_DETACH,"/devices-copy/null",NULL);
    assert(!md_view_mount_collect(fs));
    assert(md_mount_graph_portable(fs->mounts));
    shared_memory(&foreign,"/devices-again/shm/sentinel");
    md_mount_namespace_release(imported);
    mount_request(fs,MD_FS_MOUNT_UMOUNT,MNT_DETACH,"/devices-copy",NULL);
    mount_request(fs,MD_FS_MOUNT_UMOUNT,MNT_DETACH,"/devices-again",NULL);
    mount_request(fs,MD_FS_MOUNT_UMOUNT,MNT_DETACH,"/devices-flat",NULL);
    mount_request(fs,MD_FS_MOUNT_UMOUNT,MNT_DETACH,"/dev/shm/nested",NULL);
    assert(!md_view_mount_collect(fs));
    fs->store->identity=NULL; md_identity_release(&identity);
    puts("PASS implicit dev recursive bind, guest shm, nested mounts and graph custody");
}
static struct md_control_file sysfs_trigger(struct md_filesystem *fs,int monitor) {
    const char *path="/sys/class/input/event0/uevent";
    struct md_identity root=md_identity_new(0,0),user=md_identity_new(1000,1000);
    fs->store->identity=&root;
    struct md_fs_result st=request(fs,MD_FS_STAT,path,0,NULL,NULL);
    assert(!st.error && st.info.mode==(S_IFREG|0644) && !st.info.uid && st.info.size==4096);
    mount_request(fs,MD_FS_MOUNT_REMOUNT,MS_REMOUNT|MS_BIND|MS_RDONLY,"/sys",NULL);
    struct md_control_file file={0};
    assert(request(fs,MD_FS_OPEN,path,O_WRONLY,&file,NULL).error==-EROFS && !file.ops);
    mount_request(fs,MD_FS_MOUNT_REMOUNT,MS_REMOUNT|MS_BIND|MS_NODEV,"/sys",NULL);
    struct md_fs_result opened=request(fs,MD_FS_OPEN,path,O_RDWR|O_TRUNC,&file,NULL);
    if (opened.error) fprintf(stderr,"uevent open: %d\n",opened.error);
    assert(!opened.error && opened.fd>=0 && file.ops); close(opened.fd);
    char original[512],after[512],packet[4096];
    struct md_control_actor actor={.identity=&root};
    long bytes=file.ops->read(file.context,&actor,original,sizeof(original),0);
    assert(bytes>0 && memmem(original,(size_t)bytes,"DEVNAME=input/event0\n",21));
    assert(file.ops->access(file.context,&user,W_OK)==-EACCES);
    assert(!file.ops->access(file.context,&user,R_OK));
    struct md_control_actor unprivileged={.identity=&user};
    assert(file.ops->write(file.context,&unprivileged,"change",6,0)==-EACCES);
    assert(file.ops->write(file.context,&actor,"change\0forged",13,0)==-EINVAL);
    for (unsigned i=0;i<2;++i) {
        assert(file.ops->write(file.context,&actor,"change\n",7,i*7)==7);
        ssize_t n=recv(monitor,packet,sizeof(packet),MSG_DONTWAIT);
        assert(n>40 && !memcmp(packet,"libudev",8));
        assert(memmem(packet,(size_t)n,"ACTION=change",14));
        assert(memmem(packet,(size_t)n,"SUBSYSTEM=input",16));
        assert(memmem(packet,(size_t)n,"DEVPATH=/devices/virtual/input/input0/event0",43));
        assert(memmem(packet,(size_t)n,"DEVNAME=input/event0",20));
        assert(memmem(packet,(size_t)n,"SYNTH_UUID=0",sizeof("SYNTH_UUID=0")));
    }
    static const char synthetic[]="change 01234567-89ab-cdef-0123-456789abcdef KEY=Value";
    assert(file.ops->write(file.context,&actor,synthetic,sizeof(synthetic),0)==sizeof(synthetic));
    ssize_t n=recv(monitor,packet,sizeof(packet),MSG_DONTWAIT);
    static const char uuid[]="SYNTH_UUID=01234567-89ab-cdef-0123-456789abcdef";
    assert(n>0 && memmem(packet,(size_t)n,uuid,sizeof(uuid)));
    assert(memmem(packet,(size_t)n,"SYNTH_ARG_KEY=Value",sizeof("SYNTH_ARG_KEY=Value")));
    assert(file.ops->write(file.context,&actor,"change invalid",14,0)==-EINVAL);
    assert(file.ops->read(file.context,&actor,after,sizeof(after),0)==bytes && !memcmp(original,after,(size_t)bytes));
    assert(request(fs,MD_FS_OPEN,"/sys/class/input/event0/dev",O_WRONLY,NULL,NULL).error==-EROFS);
    fs->store->identity=NULL; md_identity_release(&root); md_identity_release(&user);
    return file;
}
void md_test_input_session(const char *path) {
    alarm(30); setbuf(stdout,NULL);
    struct md_inode_store *store=NULL; assert(!md_inode_store_open(path,1,&store));
    assert(!md_inode_runtime_prepare(store));
    int made=md_inode_mkdir(store,-1,"/sys",0755); assert(!made || made==-EEXIST);
    struct md_fs_storage_cache storage={0};
    struct md_filesystem fs={.store=store,.storage=&storage}; assert(!md_fs_mounts_open(&fs,NULL,0));
    struct server server={.connection=-1,.ready=eventfd(0,EFD_CLOEXEC)};
    server.listener=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0); assert(server.listener>=0 && server.ready>=0);
    char name[64]; snprintf(name,sizeof(name),"md-input-fixture-%d",getpid());
    struct sockaddr_un address={.sun_family=AF_UNIX}; strcpy(address.sun_path+1,name);
    assert(!bind(server.listener,(void *)&address,offsetof(struct sockaddr_un,sun_path)+strlen(name)+1));
    assert(!listen(server.listener,1)); assert(!pthread_create(&server.thread,NULL,serve,&server));
    int wake=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK); assert(wake>=0);
    struct md_input_launch launch={.endpoint=name,.wake_fd=wake};
    struct md_input_session *session=NULL;
    struct md_provider_tree *sysfs=NULL;
    assert(!md_fs_sysfs_prepare(&fs,&sysfs));
    initial_sysfs(&fs);
    initial_devices(&fs);
    struct md_device_events *bus=md_device_events_new(); assert(bus);
    fs.device_events=bus;
    assert(!md_input_session_start(&fs,NULL,bus,sysfs,&session) && !session);
    fs.tasks=md_task_registry_create(); assert(fs.tasks);
    int error=md_input_session_start(&fs,&launch,bus,sysfs,&session);
    md_task_registry_destroy(fs.tasks); fs.tasks=NULL;
    if (error) fprintf(stderr,"input startup: %d (%s)\n",error,strerror(-error));
    assert(!error); assert(!pthread_join(server.thread,NULL));
    struct md_fs_result st=request(&fs,MD_FS_STAT,"/dev/input/event0",0,NULL,NULL);
    assert(!st.error && S_ISCHR(st.info.mode) && major(st.info.rdev)==13 && minor(st.info.rdev)==64);
    struct md_control_file file={0}; struct md_fs_anchor *origin=NULL;
    struct md_fs_result opened=request(&fs,MD_FS_OPEN,"/dev/input/event0",O_RDWR|O_NONBLOCK,&file,&origin);
    assert(!opened.error && opened.fd>=0 && file.ops && origin); close(opened.fd);
    char name_read[128]={0};
    assert(file.ops->ioctl(file.context,NULL,EVIOCGNAME(sizeof(name_read)),0,name_read,sizeof(name_read))>0);
    assert(!strcmp(name_read,"Gamepad fixture"));
    uint64_t drain; while (read(wake,&drain,8)==8) { }
    struct { uint16_t type,code; int32_t value; } event={EV_KEY,BTN_A,1};
    send_frame(server.connection,3,&event,sizeof(event));
    /* EVENT_WAIT: producer publication wakes the descriptor owner. */
    assert(md_event_wait_fd(wake,POLLIN,md_event_now()+1000000000LL)>=0);
    struct input_event events[8]; long count=file.ops->read(file.context,NULL,events,sizeof(events),0);
    assert(count==2*(long)sizeof(*events) && events[0].code==BTN_A && events[0].value==1 && events[1].type==EV_SYN);
    file.ops->read_commit(file.context,(size_t)count);
    const char *files[]={"/sys/class/input/event0/device/name","/sys/dev/char/13:64/dev","/run/udev/data/c13:64"};
    for (unsigned i=0;i<3;++i) {
        struct md_fs_result info=request(&fs,MD_FS_OPEN,files[i],O_RDONLY,NULL,NULL);
        if (info.error) fprintf(stderr,"open %s: %d\n",files[i],info.error);
        assert(!info.error && info.fd>=0); char bytes[512]; assert(read(info.fd,bytes,sizeof(bytes))>0); close(info.fd);
    }
    struct md_fs_result bad=request(&fs,MD_FS_OPEN,"/sys/class/input/event0/dev",O_WRONLY,NULL,NULL);
    assert(bad.error==-EROFS);
    bad=request(&fs,MD_FS_UNLINK,"/dev/input/event0",0,NULL,NULL); assert(bad.error==-EROFS);
    const char *empty_dirs[]={"/sys/block","/sys/bus","/sys/dev/block"};
    for (unsigned i=0;i<sizeof(empty_dirs)/sizeof(*empty_dirs);++i) {
        struct md_fs_result info=request(&fs,MD_FS_OPEN,empty_dirs[i],O_RDONLY|O_DIRECTORY,NULL,NULL);
        assert(!info.error && info.fd>=0);
        DIR *dir=fdopendir(info.fd); assert(dir);
        struct dirent *entry;
        while ((entry=readdir(dir))) assert(!strcmp(entry->d_name,".") || !strcmp(entry->d_name,".."));
        closedir(dir);
    }
    const char *host_only[]={"/sys/devices/platform","/sys/bus/pci","/sys/class/drm"};
    for (unsigned i=0;i<sizeof(host_only)/sizeof(*host_only);++i) {
        struct md_fs_result hidden=request(&fs,MD_FS_STAT,host_only[i],0,NULL,NULL);
        assert(hidden.error==-ENOENT);
    }
    assert(md_sysfs_host_visible("/devices") && md_sysfs_host_visible("/devices/system"));
    assert(md_sysfs_host_visible("/devices/system/cpu/cpu0/topology/core_id"));
    assert(md_sysfs_host_visible("/devices/system/node/node0/cpulist"));
    assert(!md_sysfs_host_visible("/devices/system/cpux") && !md_sysfs_host_visible("/devices/system/memory"));
    const char *cpu_path="/sys/devices/system/cpu/possible";
    int native=open(cpu_path,O_RDONLY|O_CLOEXEC); assert(native>=0);
    struct md_control_file cpu_control={0};
    struct md_fs_result cpu=request(&fs,MD_FS_OPEN,cpu_path,O_RDONLY,&cpu_control,NULL);
    assert(!cpu.error && cpu.fd>=0 && !cpu_control.ops);
    char host_cpu[4096],guest_cpu[4096];
    ssize_t host_size=read(native,host_cpu,sizeof(host_cpu));
    assert(host_size>0 && read(cpu.fd,guest_cpu,sizeof(guest_cpu))==host_size);
    assert(!memcmp(host_cpu,guest_cpu,(size_t)host_size));
    struct stat host_stat,guest_stat;
    assert(!fstat(native,&host_stat) && !fstat(cpu.fd,&guest_stat));
    assert(host_stat.st_dev==guest_stat.st_dev && host_stat.st_ino==guest_stat.st_ino);
    close(native); close(cpu.fd);
    bad=request(&fs,MD_FS_OPEN,cpu_path,O_RDWR,NULL,NULL); assert(bad.error==-EROFS);
    bad=request(&fs,MD_FS_OPEN,cpu_path,O_RDONLY|O_TRUNC,NULL,NULL); assert(bad.error==-EROFS);
    bad=request(&fs,MD_FS_UNLINK,cpu_path,0,NULL,NULL); assert(bad.error==-EROFS);
    struct md_fs_result shared=request(&fs,MD_FS_OPEN,"/dev/shm/gamepad-test",O_CREAT|O_RDWR,NULL,NULL);
    assert(!shared.error && shared.fd>=0); assert(write(shared.fd,"ok",2)==2); close(shared.fd);
    struct md_fs_result null=request(&fs,MD_FS_OPEN,"/dev/null",O_WRONLY,NULL,NULL);
    if (null.error) fprintf(stderr,"null: %d host=%u\n",null.error,null.host_path);
    assert(!null.error && null.fd>=0); assert(write(null.fd,"ok",2)==2); close(null.fd);
    md_control_file_release(file); file=(struct md_control_file){0};
    md_fs_anchor_release(origin); origin=NULL;
    struct md_fs_result js=request(&fs,MD_FS_OPEN,"/dev/input/js0",O_RDONLY|O_NONBLOCK,&file,&origin);
    /* Release the independently captured open description below. */
    assert(!js.error); close(js.fd);
    md_control_file_release(file); md_fs_anchor_release(origin);
    struct md_fd_object *monitor=NULL; int monitor_fd=-1;
    assert(!md_device_events_socket(bus,SOCK_RAW|SOCK_NONBLOCK,&monitor,&monitor_fd));
    struct sockaddr_nl address_nl={.nl_family=AF_NETLINK,.nl_groups=2};
    unsigned long args[6]={0};
    assert(!md_device_events_call(monitor,SYS_bind,args,&address_nl,sizeof(address_nl),sizeof(address_nl),getpid()));
    struct md_control_file trigger=sysfs_trigger(&fs,monitor_fd);
    send_frame(server.connection,2,NULL,0);
    /* EVENT_WAIT: removal is published only after device metadata is retired. */
    assert(md_event_wait_fd(monitor_fd,POLLIN,md_event_now()+1000000000LL)>=0);
    char notification[4096]; ssize_t n=recv(monitor_fd,notification,sizeof(notification),0);
    assert(n>40 && !memcmp(notification,"libudev",8));
    assert(memmem(notification,(size_t)n,"ACTION=remove",14));
    assert(memmem(notification,(size_t)n,"DEVNAME=/dev/input/event0",25));
    assert(md_device_events_call(monitor,SYS_getsockname,args,&address_nl,sizeof(address_nl),0,getpid())==sizeof(address_nl));
    assert(address_nl.nl_groups==2 && address_nl.nl_pid==(unsigned)getpid());
    close(server.connection); close(server.listener); close(server.ready);
    md_input_session_stop(session); close(wake);
    struct stat retired;
    assert(trigger.ops->stat(trigger.context,&retired)==-ENODEV);
    md_control_file_release(trigger);
    md_provider_tree_release(sysfs);
    while (recv(monitor_fd,notification,sizeof(notification),MSG_DONTWAIT)>0) {}
    assert(errno==EAGAIN);
    assert(!md_device_events_publish(bus,"change","/devices/virtual/test","test",NULL,0));
    n=recv(monitor_fd,notification,sizeof(notification),MSG_DONTWAIT);
    assert(n>40 && memmem(notification,(size_t)n,"SUBSYSTEM=test",15));
    md_device_events_stop(bus); md_device_events_release(bus);
    close(monitor_fd); md_fd_object_release(monitor);
    md_fs_mounts_close(&fs); md_fs_storage_close(&storage); md_inode_store_close(store);
    puts("PASS input transport, discovery, controlled opens, sysfs composition and cleanup");
    puts("PASS sysfs uevent: authoritative properties, repeated triggers, DAC, readonly mounts, nodev and unplug lifetime");
}
