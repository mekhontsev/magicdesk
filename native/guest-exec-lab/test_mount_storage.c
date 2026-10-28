#define _GNU_SOURCE
#include "fs_mount_internal.h"
#include "fs_storage.h"
#include "inode_internal.h"
#include "mount_graph.h"
#include "inode_watch.h"
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/inotify.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

void test_mount_allocation_failure_after(int);
static unsigned descriptors(void) {
    DIR *directory=opendir("/proc/self/fd"); assert(directory);
    unsigned n=0; struct dirent *entry;
    while ((entry=readdir(directory))) if (entry->d_name[0]!='.') ++n;
    assert(!closedir(directory)); return n;
}
static void put(struct md_inode_store *s, const char *path, const char *text) {
    int fd=md_inode_open(s,-1,path,O_CREAT|O_WRONLY|O_TRUNC,0644); assert(fd>=0);
    assert(write(fd,text,strlen(text))==(ssize_t)strlen(text)); close(fd);
}
static struct md_fs_result call(struct md_filesystem *fs, struct md_fs_request q,
        const struct md_fs_output *output) {
    struct md_fs_result r; md_fs_execute(fs,&q,&r,output); return r;
}
static int opened(struct md_filesystem *fs, const char *path, unsigned flags,
        struct md_fs_anchor *base, struct md_fs_anchor **origin) {
    struct md_fs_result r=call(fs,(struct md_fs_request){.operation=MD_FS_OPEN,
        .directory={base ? md_fs_anchor_fd(base) : -1,-1},.origin={base,NULL},
        .path={path,NULL},.flags=flags},&(struct md_fs_output){.origin=origin});
    if (r.error) fprintf(stderr,"storage open %s: %d\n",path,r.error);
    assert(!r.error); return r.fd;
}
static void contents(struct md_filesystem *fs, const char *path,
        struct md_fs_anchor *base, const char *text) {
    int fd=opened(fs,path,O_RDONLY,base,NULL); char data[64]={0};
    assert(read(fd,data,sizeof(data))==(ssize_t)strlen(text) && !memcmp(data,text,strlen(text)));
    close(fd);
}
static void planned(struct md_filesystem *fs,const char *path,unsigned flags,const char *text,int error) {
    struct md_fs_anchor *origin=NULL;
    struct md_fs_result r=call(fs,(struct md_fs_request){.operation=MD_FS_OPEN,
        .directory={-1,-1},.path={path,NULL},.flags=flags},
        &(struct md_fs_output){.origin=&origin,.native_open_plan=1});
    assert(r.error==error && r.fd<0);
    if (error) { assert(!origin); return; }
    assert(origin && r.open_completion.kind==MD_OPEN_PLAN);
    struct stat st; assert(!fstat(md_fs_anchor_fd(origin),&st));
    assert(r.open_completion.backing_device==(uint64_t)st.st_dev
        && r.open_completion.backing_inode==(uint64_t)st.st_ino);
    if (flags&O_PATH) {
        char path[64];
        unsigned native=md_open_plan_path(path,0,(unsigned)md_fs_anchor_fd(origin),flags);
        int fd=open(path,native); assert(fd>=0);
        struct stat actual; assert(!fstat(fd,&actual) && actual.st_ino==st.st_ino && actual.st_dev==st.st_dev);
        assert(fcntl(fd,F_GETFL)==(int)(flags&~O_CLOEXEC)); close(fd);
    }
    if (text) {
        /* A cancelled truncating open must leave the data untouched. */
        int fd=md_open_pinned(md_fs_anchor_fd(origin),O_RDONLY); assert(fd>=0);
        char data[64]; ssize_t n=read(fd,data,sizeof(data)); close(fd);
        assert(n==(ssize_t)strlen(text) && !memcmp(data,text,(size_t)n));
    }
    md_fs_anchor_release(origin);
}
static void mount_call(struct md_filesystem *fs, unsigned mode, unsigned flags,
        const char *target, const char *source) {
    struct md_fs_result r=call(fs,(struct md_fs_request){.operation=MD_FS_MOUNT,
        .mode=mode,.flags=flags,.directory={-1,-1},.path={target,source}},NULL);
    if (r.error) fprintf(stderr,"storage mount %s: %d\n",target,r.error);
    assert(!r.error);
}
static void image_aliases(struct md_filesystem *fs) {
    const char *paths[]={"/volume/program","/volume/dir/program-alias","/volume/program-link",
        "/alias/program","/alias/dir/program-alias","/alias/program-link"};
    char ids[2][33];
    for (unsigned i=0;i<6;++i) {
        struct md_image_identity image;
        struct md_fs_result r=call(fs,(struct md_fs_request){.operation=MD_FS_OPEN_IMAGE,
            .directory={-1,-1},.path={paths[i],NULL}},
            &(struct md_fs_output){.data=&image,.capacity=sizeof(image)});
        assert(!r.error && r.fd>=0);
        assert(!strcmp(image.path,paths[i%3==2 ? i-1 : i]));
        if (i%3==0) memcpy(ids[i/3],image.object,sizeof(image.object));
        else assert(!strcmp(ids[i/3],image.object));
        close(r.fd);
    }
    assert(strcmp(ids[0],ids[1]));
    puts("PASS named image lookup: hardlinks and symlinks retain resolved names and per-mount identities");
}
static void file_binds(const char *base) {
    unsigned before=descriptors();
    char path[PATH_MAX];
    assert(snprintf(path,sizeof(path),"%s/storage-file-binds",base)<PATH_MAX);
    struct md_inode_store *store=NULL;
    assert(!md_inode_store_open(path,1,&store));
    put(store,"/source","file");
    assert(!md_inode_link(store,-1,"/source",-1,"/source-alias",0));
    assert(!md_inode_mkdir(store,-1,"/directory",0700));
    assert(!md_inode_mkdir(store,-1,"/sibling",0700));
    put(store,"/plain","plain"); put(store,"/recursive","recursive");
    struct md_identity root=md_identity_new(0,0); store->identity=&root;
    struct md_fs_storage_cache storage={0};
    struct md_filesystem fs={.store=store,.storage=&storage};
    assert(!md_fs_mounts_open(&fs,NULL,0));
    mount_call(&fs,MD_FS_MOUNT_BIND,MS_BIND,"/sibling","/directory");
    /* A locked sibling is not a child of either hardlink name. */
    fs.mounts->tree->mounts[0].locked=1;
    mount_call(&fs,MD_FS_MOUNT_BIND,MS_BIND,"/plain","/source");
    mount_call(&fs,MD_FS_MOUNT_BIND,MS_BIND|MS_REC,"/recursive","/source-alias");
    assert(fs.mounts->tree->count==3);
    contents(&fs,"/plain",NULL,"file"); contents(&fs,"/recursive",NULL,"file");
    mount_call(&fs,MD_FS_MOUNT_REMOUNT,MS_REMOUNT|MS_BIND|MS_RDONLY,"/recursive",NULL);
    struct md_fs_result r=call(&fs,(struct md_fs_request){.operation=MD_FS_OPEN,
        .directory={-1,-1},.path={"/recursive",NULL},.flags=O_WRONLY},NULL);
    assert(r.error==-EROFS);
    int fd=opened(&fs,"/plain",O_WRONLY|O_TRUNC,NULL,NULL);
    assert(write(fd,"shared",6)==6); close(fd);
    contents(&fs,"/source-alias",NULL,"shared"); contents(&fs,"/recursive",NULL,"shared");
    md_fs_mounts_close(&fs); md_fs_storage_close(&storage);
    md_inode_store_close(store); md_identity_release(&root);
    assert(descriptors()==before);
    puts("PASS file binds: hardlink sources, recursive/plain binds, locked siblings and independent policy");
}
static void socket_binds(const char *base) {
    unsigned before=descriptors();
    char path[PATH_MAX];
    assert(snprintf(path,sizeof(path),"%s/storage-socket-binds",base)<PATH_MAX);
    struct md_inode_store *store=NULL;
    assert(!md_inode_store_open(path,1,&store));
    struct md_identity root=md_identity_new(0,0); store->identity=&root;
    int server=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0); assert(server>=0);
    assert(!md_inode_socket_bind(store,-1,"/socket","/socket",0600,server));
    assert(!listen(server,1));
    assert(!md_inode_link(store,-1,"/socket",-1,"/socket-alias",0));
    put(store,"/mounted","target"); put(store,"/plain","plain");
    char expected[108];
    assert(!md_inode_socket_address(store,-1,"/socket",expected,sizeof(expected)));
    struct md_fs_storage_cache storage={0};
    struct md_filesystem fs={.store=store,.storage=&storage};
    assert(!md_fs_mounts_open(&fs,NULL,0));
    mount_call(&fs,MD_FS_MOUNT_BIND,MS_BIND|MS_REC,"/mounted","/socket-alias");
    mount_call(&fs,MD_FS_MOUNT_REMOUNT,MS_REMOUNT|MS_BIND|MS_RDONLY,"/mounted",NULL);
    assert(!md_inode_unlink(store,-1,"/socket",0));
    assert(!md_inode_unlink(store,-1,"/socket-alias",0));
    char endpoint[108];
    struct md_fs_output output={.data=endpoint,.capacity=sizeof(endpoint)};
    struct md_fs_result r=call(&fs,(struct md_fs_request){.operation=MD_FS_SOCKET_ADDRESS,
        .directory={-1,-1},.path={"/mounted",NULL},.flags=MD_FS_SOCKET_PIN},&output);
    if (r.error) fprintf(stderr,"mounted socket address: %d\n",r.error);
    assert(!r.error && !strcmp(endpoint,expected));
    struct md_identity denied=md_identity_new(1000,1000); store->identity=&denied;
    struct md_fs_result denied_result=call(&fs,(struct md_fs_request){.operation=MD_FS_SOCKET_ADDRESS,
        .directory={-1,-1},.path={"/mounted",NULL}},&output);
    assert(denied_result.error==-EACCES);
    store->identity=&root; md_identity_release(&denied);
    struct sockaddr_un address={.sun_family=AF_UNIX};
    memcpy(address.sun_path+1,endpoint,r.size-1);
    int client=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0); assert(client>=0);
    assert(!connect(client,(struct sockaddr *)&address,offsetof(struct sockaddr_un,sun_path)+r.size));
    int peer=accept4(server,NULL,NULL,SOCK_CLOEXEC); assert(peer>=0);
    close(peer); close(client); close(server);
    r=call(&fs,(struct md_fs_request){.operation=MD_FS_SOCKET_ADDRESS,
        .directory={-1,-1},.path={"/plain",NULL}},&output);
    assert(r.error==-ECONNREFUSED);
    md_fs_mounts_close(&fs); md_fs_storage_close(&storage);
    md_inode_store_close(store); md_identity_release(&root);
    assert(descriptors()==before);
    puts("PASS socket binds: readonly hardlink mount retains endpoint after unlink and permits connections");
}
static void readonly_root(const char *base) {
    unsigned before=descriptors();
    char path[PATH_MAX];
    assert(snprintf(path,sizeof(path),"%s/storage-readonly-root",base)<PATH_MAX);
    struct md_inode_store *store=NULL;
    assert(!md_inode_store_open(path,1,&store));
    put(store,"/target","unchanged");
    assert(!md_inode_mkdir(store,-1,"/writable",0700));
    struct md_identity root=md_identity_new(0,0); store->identity=&root;
    struct md_fs_storage_cache storage={0};
    struct md_filesystem fs={.store=store,.storage=&storage};
    assert(!md_fs_mounts_open(&fs,NULL,0));
    mount_call(&fs,MD_FS_MOUNT_BIND,MS_BIND,"/writable","/writable");
    mount_call(&fs,MD_FS_MOUNT_REMOUNT,MS_REMOUNT|MS_BIND|MS_RDONLY,"/",NULL);
    for (unsigned i=0;i<2;++i) {
        const char *name=i ? "/writable/replacement" : "/writable/link";
        struct md_fs_result r=call(&fs,(struct md_fs_request){.operation=MD_FS_SYMLINK,
            .directory={-1,-1},.path={name,"/target"}},NULL);
        assert(!r.error);
    }
    struct md_fs_result r=call(&fs,(struct md_fs_request){.operation=MD_FS_RENAME,
        .directory={-1,-1},.path={"/writable/replacement","/writable/link"}},NULL);
    if (r.error) fprintf(stderr,"writable child rename under readonly root: %d\n",r.error);
    assert(!r.error);
    r=call(&fs,(struct md_fs_request){.operation=MD_FS_UNLINK,
        .directory={-1,-1},.path={"/writable/link",NULL}},NULL);
    assert(!r.error);
    contents(&fs,"/target",NULL,"unchanged");
    r=call(&fs,(struct md_fs_request){.operation=MD_FS_UNLINK,
        .directory={-1,-1},.path={"/target",NULL}},NULL);
    assert(r.error==-EROFS);
    r=call(&fs,(struct md_fs_request){.operation=MD_FS_RENAME,
        .directory={-1,-1},.path={"/target","/renamed"}},NULL);
    assert(r.error==-EROFS);
    md_fs_mounts_close(&fs); md_fs_storage_close(&storage);
    md_inode_store_close(store); md_identity_release(&root);
    assert(descriptors()==before);
    puts("PASS readonly root: writable child symlink replacement/unlink preserve target and root policy");
}
static int subscribe(struct md_filesystem *fs, int reader, const char *path) {
    struct md_fs_result r=call(fs,(struct md_fs_request){.operation=MD_FS_WATCH_PATH,
        .flags=IN_CREATE|IN_MOVED_FROM|IN_MOVED_TO|IN_DELETE,.directory={-1,reader},
        .path={path,NULL}},NULL);
    assert(!r.error); return (int)r.position;
}
static void watches(struct md_filesystem *fs, const char *extra) {
    /* The Unix WAL VFS retains closed DB FDs while a peer holds POSIX locks.
     * Prime its two-peer high-water mark; the checks below still require exact
     * cleanup, and test_mount_storage checks release after the last owner. */
    struct md_inode_store *peers[2];
    for (unsigned i=0;i<2;++i) assert(!md_inode_store_open(extra,0,&peers[i]));
    for (unsigned i=0;i<2;++i) md_inode_store_close(peers[i]);
    unsigned before=descriptors();
    int reader=md_inode_watch_create(fs->store,IN_CLOEXEC|IN_NONBLOCK); assert(reader>=0);
    int base=subscribe(fs,reader,"/"), mounted=subscribe(fs,reader,"/volume");
    assert(base!=mounted && subscribe(fs,reader,"/alias")==mounted);
    struct md_inode_store *writer=NULL;
    assert(!md_inode_store_open(extra,0,&writer));
    put(writer,"/watch-new","hello");
    assert(!md_inode_rename(writer,-1,"/watch-new",-1,"/watch-moved",0));
    assert(!md_inode_unlink(writer,-1,"/watch-moved",0));
    md_inode_store_close(writer);
    put(fs->store,"/watch-base","base");
    union { struct inotify_event align; char bytes[4096]; } events;
    ssize_t n=md_inode_watch_read(fs->store,reader,events.bytes,sizeof(events.bytes),NULL,NULL);
    assert(n>0);
    unsigned seen=0, cookie=0;
    for (size_t at=0;at<(size_t)n;) {
        struct inotify_event *e=(void *)(events.bytes+at);
        assert(e->len && e->wd>0);
        if (e->wd==base) { assert(e->mask==IN_CREATE && !strcmp(e->name,"watch-base")); seen|=1; }
        else {
            assert(e->wd==mounted);
            if (e->mask==IN_CREATE) { assert(!strcmp(e->name,"watch-new")); seen|=2; }
            else if (e->mask==IN_MOVED_FROM) { cookie=e->cookie; assert(cookie); seen|=4; }
            else if (e->mask==IN_MOVED_TO) { assert(e->cookie==cookie); seen|=8; }
            else { assert(e->mask==IN_DELETE && !strcmp(e->name,"watch-moved")); seen|=16; }
        }
        at+=sizeof(*e)+e->len;
    }
    assert(seen==31);
    close(reader); assert(!md_inode_watch_pump(fs->store));
    assert(descriptors()==before);
    puts("PASS mounted-store watches: one instance, independent sources, alias deduplication, external writer and cleanup");
}
static void storage_cache(struct md_inode_store *main, const char *base) {
    enum { COUNT=MD_FS_STORAGE_CACHE_LIMIT+3 };
    unsigned before=descriptors();
    struct md_inode_lifetime *leases[COUNT];
    struct md_fs_storage_cache cache={0};
    struct md_filesystem fs={.store=main,.storage=&cache};
    struct md_fs_storage_scope held={0};
    char path[PATH_MAX];
    for (unsigned i=0;i<COUNT;++i) {
        assert(snprintf(path,sizeof(path),"%s/cache-%u",base,i)<PATH_MAX);
        struct md_inode_store *created=NULL;
        assert(!md_inode_store_open(path,1,&created));
        put(created,"/value","cache");
        leases[i]=md_inode_lifetime_retain(md_inode_store_lifetime(created));
        md_inode_store_close(created);
        struct md_view_mount mount={.backing=MD_MOUNT_INODE,.storage=leases[i]};
        struct md_fs_storage_scope scope;
        assert(!md_fs_storage_enter(&fs,&mount,&scope));
        if (!i) held=scope; else md_fs_storage_leave(&scope);
        assert(cache.count==(i+1<MD_FS_STORAGE_CACHE_LIMIT ? i+1 : MD_FS_STORAGE_CACHE_LIMIT));
    }
    /* The LRU cannot evict an active connection. Evicted stores remain usable
     * through independent custody and can open a new worker connection. */
    int fd=md_inode_open(held.store,-1,"/value",O_RDONLY,0); assert(fd>=0); close(fd);
    struct md_view_mount mount={.backing=MD_MOUNT_INODE,.storage=leases[1]};
    struct md_inode_store *again=NULL;
    assert(!md_fs_storage_get(&fs,&mount,&again));
    fd=md_inode_open(again,-1,"/value",O_RDONLY,0); assert(fd>=0); close(fd);
    for (unsigned i=0;i<COUNT;++i) md_inode_lifetime_release(leases[i]);
    md_fs_storage_prune(&cache); assert(cache.count==1);
    md_fs_storage_leave(&held); md_fs_storage_prune(&cache);
    assert(!cache.count && !cache.connections);
    for (unsigned i=0;i<COUNT;++i) {
        assert(snprintf(path,sizeof(path),"%s/cache-%u",base,i)<PATH_MAX);
        struct md_inode_store *exclusive=NULL;
        assert(!md_inode_store_open_exclusive(path,&exclusive));
        md_inode_store_close(exclusive);
    }
    assert(descriptors()==before);
    puts("PASS mounted-store cache: bounded LRU, active scopes, reconnect and maintenance-lease release");
}
static void deferred_storage(struct md_inode_store *main,const char *base) {
    enum { COUNT=MD_FS_STORAGE_CACHE_LIMIT*2+3 };
    unsigned before=descriptors();
    struct md_fs_storage_cache cache={0};
    struct md_filesystem fs={.store=main,.storage=&cache};
    struct md_inode_lifetime *leases[COUNT];
    struct md_inode_birth *births[COUNT]={0};
    struct md_fs_storage_scope scopes[COUNT];
    int pins[COUNT];
    char path[PATH_MAX];
    for (unsigned i=0;i<COUNT;++i) {
        snprintf(path,sizeof(path),"%s/deferred-%u",base,i);
        struct md_inode_store *s=NULL;
        assert(!md_inode_store_open(path,1,&s));
        assert(!md_inode_mkdir(s,-1,"/parent",0755));
        leases[i]=md_inode_lifetime_retain(md_inode_store_lifetime(s));
        md_inode_store_close(s);
        struct md_view_mount mount={.backing=MD_MOUNT_INODE,.storage=leases[i]};
        assert(!md_fs_storage_enter(&fs,&mount,&scopes[i]));
        pins[i]=md_inode_plan_open(scopes[i].store,-1,"/parent/born",O_CREAT|O_RDWR,0600,0,
            &(struct md_open_completion){0},1,&births[i]);
        assert(pins[i]>=0 && births[i]);
        md_fs_storage_leave(&scopes[i]);
    }
    /* Deferred native opens must survive arbitrary intervening cache misses. */
    for (unsigned i=0;i<COUNT;++i) {
        struct md_view_mount mount={.backing=MD_MOUNT_INODE,.storage=leases[i]};
        struct md_inode_store *s=NULL;
        assert(!md_fs_storage_get(&fs,&mount,&s));
        if (i%3==0) assert(!md_inode_birth_commit(&births[i]));
        else if (i%3==1) assert(!md_inode_birth_discard(&births[i]));
        else md_inode_birth_forget(&births[i]);
        assert(!births[i]);
        struct stat st;
        assert(md_inode_stat(s,-1,"/parent/born",0,&st)==(i%3 ? -ENOENT : 0));
        assert(!fstat(pins[i],&st));
        if (i%3==1) assert(!st.st_nlink);
        close(pins[i]);
    }
    /* Nested scopes may temporarily exceed idle-cache capacity as well. */
    for (unsigned i=0;i<COUNT;++i) {
        struct md_view_mount mount={.backing=MD_MOUNT_INODE,.storage=leases[i]};
        assert(!md_fs_storage_enter(&fs,&mount,&scopes[i]));
    }
    for (unsigned i=COUNT;i--;) md_fs_storage_leave(&scopes[i]);
    md_fs_storage_prune(&cache);
    assert(cache.count==MD_FS_STORAGE_CACHE_LIMIT);
    for (unsigned i=0;i<COUNT;++i) md_inode_lifetime_release(leases[i]);
    md_fs_storage_prune(&cache);
    assert(!cache.count && !cache.connections);
    assert(descriptors()==before);
    puts("PASS deferred storage: births and nested scopes exceed idle-cache capacity, publish/cancel/forget and release all connections");
}
void test_mount_storage(const char *base) {
    file_binds(base);
    socket_binds(base);
    readonly_root(base);
    unsigned before=descriptors();
    char path[PATH_MAX], extra[PATH_MAX], moved[PATH_MAX];
    assert(snprintf(path,sizeof(path),"%s/storage-main",base)<PATH_MAX);
    assert(snprintf(extra,sizeof(extra),"%s/storage-extra",base)<PATH_MAX);
    assert(snprintf(moved,sizeof(moved),"%s/storage-renamed",base)<PATH_MAX);
    struct md_inode_store *main=NULL, *second=NULL;
    assert(!md_inode_store_open(path,1,&main) && !md_inode_store_open(extra,1,&second));
    assert(!md_inode_mkdir(main,-1,"/volume",0755) && !md_inode_mkdir(main,-1,"/alias",0755));
    assert(!md_inode_mkdir(second,-1,"/dir",0755) && !md_inode_mkdir(second,-1,"/dev",0755));
    put(main,"/value","main"); put(second,"/value","second"); put(second,"/dev/plain","ordinary");
    int program=md_inode_open(second,-1,"/program",O_CREAT|O_WRONLY,0755); assert(program>=0); close(program);
    assert(!md_inode_link(second,-1,"/program",-1,"/dir/program-alias",0));
    assert(!md_inode_symlink(second,"dir/program-alias",-1,"/program-link"));
    int value=md_inode_open(second,-1,"/value",O_PATH,0); assert(value>=0);
    assert(!md_inode_import_metadata(second,value,1001,1002,0640)); close(value);
    assert(!md_inode_symlink(second,"/value",-1,"/absolute"));
    assert(!md_inode_symlink(second,"../value",-1,"/dir/up"));
    struct md_identity root=md_identity_new(0,0); main->identity=&root;
    struct md_fs_storage_cache storage={0};
    struct md_filesystem fs={.store=main,.storage=&storage};
    assert(!md_fs_mounts_open(&fs,NULL,0));
    struct md_view_location target;
    assert(!md_view_walk(&fs,-1,NULL,"/volume",1,0,0,&target));
    int fd=md_inode_open(second,-1,"/",O_PATH|O_DIRECTORY,0); assert(fd>=0);
    struct md_mount_source source={.fd=fd,.backing=MD_MOUNT_INODE,.storage=md_inode_store_lifetime(second)};
    assert(!md_view_attach_source(&fs,&source,&target,NULL));
    close(fd); md_view_location_close(&target);
    md_inode_store_close(second); second=NULL;
    /* A cold connection must use retained custody, not the original pathname. */
    assert(!rename(extra,moved));
    contents(&fs,"/volume/value",NULL,"second"); contents(&fs,"/value",NULL,"main");
    contents(&fs,"/volume/dev/plain",NULL,"ordinary");
    contents(&fs,"/volume/dir/up",NULL,"second"); contents(&fs,"/volume/absolute",NULL,"main");
    contents(&fs,"/volume/../value",NULL,"main");
    assert(!rename(moved,extra));
    mount_call(&fs,MD_FS_MOUNT_BIND,MS_BIND,"/alias","/volume");
    assert(fs.mounts->tree->mounts[0].storage==fs.mounts->tree->mounts[1].storage);
    image_aliases(&fs);
    planned(&fs,"/volume/value",O_RDONLY,"second",0);
    planned(&fs,"/alias/value",O_WRONLY|O_TRUNC,"second",0);
    planned(&fs,"/alias/dir",O_PATH|O_DIRECTORY,NULL,0);
    planned(&fs,"/alias/dir",O_PATH|O_NOFOLLOW,NULL,0);
    planned(&fs,"/alias/dir",O_PATH|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC,NULL,0);
    planned(&fs,"/alias/value",O_PATH|O_NOFOLLOW,NULL,-EXDEV);
    planned(&fs,"/alias/program-link",O_PATH|O_NOFOLLOW,NULL,-EXDEV);
    planned(&fs,"/alias/dir",O_WRONLY,NULL,-EISDIR);
    planned(&fs,"/alias/value",O_CREAT|O_EXCL|O_WRONLY,NULL,-EEXIST);
    struct md_identity user=md_identity_new(2000,2000); main->identity=&user;
    planned(&fs,"/alias/value",O_RDONLY,NULL,-EACCES);
    main->identity=&root; md_identity_release(&user);
    struct md_fs_anchor *held=NULL;
    fd=opened(&fs,"/alias/dir",O_PATH|O_DIRECTORY,NULL,&held); close(fd);
    watches(&fs,extra);
    char actual[PATH_MAX];
    struct md_fs_result r=call(&fs,(struct md_fs_request){.operation=MD_FS_PATH,
        .directory={md_fs_anchor_fd(held),-1},.origin={held,NULL}},
        &(struct md_fs_output){.data=actual,.capacity=sizeof(actual)});
    assert(!r.error && !strcmp(actual,"/alias/dir"));
    struct md_fs_storage_cache other={0};
    struct md_filesystem copy={.store=main,.storage=&other};
    struct md_fs_location copied_root;
    assert(!md_fs_mounts_copy(&fs,&(struct md_fs_location){0},&copy.mounts,&copied_root));
    assert(copy.mounts->tree->mounts[0].storage==fs.mounts->tree->mounts[0].storage);
    assert(copy.mounts->tree->mounts[0].id!=fs.mounts->tree->mounts[0].id);
    fd=opened(&copy,"/volume/value",O_WRONLY|O_TRUNC,NULL,NULL);
    assert(write(fd,"shared",6)==6); close(fd); contents(&fs,"/alias/value",NULL,"shared");
    mount_call(&copy,MD_FS_MOUNT_REMOUNT,MS_REMOUNT|MS_BIND|MS_RDONLY,"/volume",NULL);
    planned(&copy,"/volume/value",O_WRONLY|O_TRUNC,NULL,-EROFS);
    planned(&copy,"/volume/value",O_RDONLY,"shared",0);
    planned(&copy,"/alias/value",O_WRONLY|O_TRUNC,"shared",0);
    r=call(&copy,(struct md_fs_request){.operation=MD_FS_OPEN,.directory={-1,-1},
        .path={"/volume/value",NULL},.flags=O_WRONLY},NULL);
    assert(r.error==-EROFS);
    fd=opened(&copy,"/alias/value",O_WRONLY,NULL,NULL); close(fd);
    fd=opened(&fs,"/volume/value",O_WRONLY,NULL,NULL); close(fd);
    /* Object identities keep mount policy instead of escaping to the base DB. */
    struct md_fs_anchor *file=NULL;
    fd=opened(&copy,"/volume/value",O_PATH,NULL,&file); close(fd);
    char object[33];
    r=call(&copy,(struct md_fs_request){.operation=MD_FS_OBJECT_ID,
        .directory={md_fs_anchor_fd(file),-1},.origin={file,NULL}},
        &(struct md_fs_output){.data=object,.capacity=sizeof(object)});
    assert(!r.error);
    r=call(&copy,(struct md_fs_request){.operation=MD_FS_FSTAT,
        .directory={md_fs_anchor_fd(file),-1},.mode=MD_FS_STAT_MOUNT},NULL);
    assert(!r.error && r.info.uid==1001 && r.info.gid==1002
        && r.info.mount_id==copy.mounts->tree->mounts[0].id);
    r=call(&copy,(struct md_fs_request){.operation=MD_FS_OPEN_OBJECT,.directory={-1,-1},
        .path={object,NULL},.flags=O_RDONLY},NULL);
    assert(!r.error); close(r.fd); md_fs_anchor_release(file);
    struct md_mount_graph_parcel parcel={0};
    assert(!md_mount_graph_export(copy.mounts,copy.store->lifetime->root,&parcel) && parcel.count==4+3*2+2);
    struct md_fs_storage_cache received_storage={0};
    struct md_filesystem received={.store=main,.storage=&received_storage};
    assert(!md_mount_graph_import(&parcel,&received.mounts));
    assert(received.mounts->tree->mounts[0].storage==received.mounts->tree->mounts[1].storage);
    contents(&received,"/alias/value",NULL,"shared");
    unsigned live=descriptors();
    for (unsigned i=0;i<3;++i) {
        assert(!md_mount_graph_export(copy.mounts,copy.store->lifetime->root,&parcel));
        assert(!md_mount_graph_import(&parcel,&received.mounts));
        assert(descriptors()==live);
    }
    /* Even an interned graph validates its complete custody, not only IDs. */
    assert(!md_mount_graph_export(copy.mounts,copy.store->lifetime->root,&parcel));
    close(parcel.fd[parcel.count-2]);
    parcel.fd[parcel.count-2]=fcntl(main->lifetime->root,F_DUPFD_CLOEXEC,3);
    assert(parcel.fd[parcel.count-2]>=0);
    assert(md_mount_graph_import(&parcel,&received.mounts)==-EPROTO);
    assert(descriptors()==live);
    int reached=0;
    for (int n=0;n<16 && !reached;++n) {
        assert(!md_mount_graph_export(copy.mounts,copy.store->lifetime->root,&parcel));
        struct md_mount_namespace *candidate=NULL;
        test_mount_allocation_failure_after(n);
        int error=md_mount_graph_import(&parcel,&candidate);
        test_mount_allocation_failure_after(-1);
        if (!error) { assert(candidate); md_mount_namespace_release(candidate); reached=1; }
        else assert(error==-ENOMEM && !candidate);
        assert(!parcel.fd && !parcel.count && descriptors()==live);
    }
    assert(reached);
    mount_call(&fs,MD_FS_MOUNT_UMOUNT,MNT_DETACH,"/alias",NULL);
    contents(&fs,"../value",held,"shared");
    md_fs_mounts_close(&fs); md_fs_storage_close(&storage);
    contents(&copy,"../value",held,"shared");
    contents(&copy,"/volume/value",NULL,"shared");
    fd=opened(&copy,"/volume",O_PATH|O_DIRECTORY,NULL,&file);
    struct md_fs_location root_location;
    assert(!md_fs_root_capture(&copy,fd,file,&root_location)); close(fd);
    assert(!md_fs_root_select(&copy,&root_location));
    contents(&copy,"/../../value",NULL,"shared");
    contents(&copy,"/absolute",NULL,"shared");
    md_fs_anchor_release(file); md_fs_anchor_release(held);
    md_fs_mounts_close(&copy); md_fs_storage_close(&other);
    /* The receiving view is now the only owner of the extra filesystem. */
    md_fs_storage_close(&received_storage);
    assert(!rename(extra,moved));
    contents(&received,"/volume/value",NULL,"shared");
    r=call(&received,(struct md_fs_request){.operation=MD_FS_OPEN,.directory={-1,-1},
        .path={"/volume/value",NULL},.flags=O_WRONLY},NULL);
    assert(r.error==-EROFS);
    fd=opened(&received,"/alias/value",O_WRONLY,NULL,NULL); close(fd);
    assert(!md_mount_graph_export(received.mounts,received.store->lifetime->root,&parcel));
    struct md_filesystem forwarded={.store=main,.storage=&other};
    assert(!md_mount_graph_import(&parcel,&forwarded.mounts));
    md_fs_mounts_close(&received); md_fs_storage_close(&received_storage);
    contents(&forwarded,"/alias/value",NULL,"shared");
    md_fs_mounts_close(&forwarded); md_fs_storage_close(&other);
    assert(!rename(moved,extra));
    /* A retained parent directory must not admit a symlink replacing the DB. */
    struct md_inode_store *check=NULL;
    assert(!md_inode_store_open(extra,0,&check));
    struct md_inode_lifetime *lease=md_inode_lifetime_retain(md_inode_store_lifetime(check));
    md_inode_store_close(check); check=NULL;
    assert(!renameat(lease->root,"namespace.db",lease->root,"saved.db"));
    assert(!symlinkat("saved.db",lease->root,"namespace.db"));
    assert(md_inode_store_reconnect(lease,&check)<0 && !check);
    assert(!unlinkat(lease->root,"namespace.db",0));
    assert(!renameat(lease->root,"saved.db",lease->root,"namespace.db"));
    assert(!md_inode_store_reconnect(lease,&check));
    md_inode_store_close(check); md_inode_lifetime_release(lease);
    storage_cache(main,base);
    deferred_storage(main,base);
    main->identity=NULL; md_identity_release(&root); md_inode_store_close(main);
    assert(descriptors()==before);
    puts("PASS mounted inode storage: identity, symlinks, bind/copy, policy, portable custody and old dirfd");
}
