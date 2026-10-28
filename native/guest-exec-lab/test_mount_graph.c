#define _GNU_SOURCE
#include "mount_graph.h"
#include "host_fs_view.h"
#include "mount_stream.h"
#include "fs_mount_internal.h"
#include "fs_storage.h"
#include "fs_wire.h"
#include "event_wait.h"
#include "mount_topology.h"
#include "inode_internal.h"
#include "proc_sysctl.h"
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/mount.h>
#include <sys/wait.h>
#include <unistd.h>

static int fail_allocation=-1;
void test_mount_allocation_failure_after(int count) { fail_allocation=count; }
void test_mount_policy_map_failure_after(int count);
void test_mount_reservation_failure(int enabled);
void *__real_calloc(size_t, size_t);
void *__wrap_calloc(size_t n, size_t size) {
    if (fail_allocation==0) { errno=ENOMEM; return NULL; }
    if (fail_allocation>0) --fail_allocation;
    return __real_calloc(n,size);
}
static unsigned descriptors(void) {
    DIR *dir=opendir("/proc/self/fd"); assert(dir);
    unsigned n=0; struct dirent *entry;
    while ((entry=readdir(dir))) if (entry->d_name[0]!='.') ++n;
    assert(!closedir(dir)); return n;
}
static void ready(int fd) {
    /* EVENT_WAIT: exact fixture packet; timeout fails the test. */
    assert(!md_event_wait_fd(fd,POLLIN,md_event_now()+5000000000LL));
}
static void send_byte(int fd, char byte) {
    assert(md_fs_send(fd,&byte,1,&(struct md_fs_rights){0})==1);
}
static char receive_byte(int fd) {
    ready(fd); char byte; struct md_fs_rights rights={0};
    assert(md_fs_receive(fd,&byte,1,&rights)==1 && !rights.count); return byte;
}
static void send_graph(int fd, struct md_mount_graph_parcel *p) {
    assert(md_fs_send(fd,&p->count,sizeof(p->count),&(struct md_fs_rights){0})==sizeof(p->count));
    for (unsigned i=0;i<p->count;) {
        struct md_fs_rights rights={0};
        while (rights.count<MD_FS_MAX_FDS && i<p->count) rights.fd[rights.count++]=p->fd[i++];
        assert(md_fs_send(fd,"g",1,&rights)==1);
    }
    md_mount_graph_parcel_close(p);
}
static struct md_mount_graph_parcel receive_graph(int fd) {
    ready(fd); unsigned count; struct md_fs_rights rights={0};
    assert(md_fs_receive(fd,&count,sizeof(count),&rights)==sizeof(count) && !rights.count);
    assert(count && count<=MD_MOUNT_GRAPH_FDS);
    struct md_mount_graph_parcel p={.fd=calloc(count,sizeof(int))}; assert(p.fd);
    while (p.count<count) {
        ready(fd); char byte;
        assert(md_fs_receive(fd,&byte,1,&rights)==1 && byte=='g' && rights.count
            && rights.count<=count-p.count);
        for (unsigned i=0;i<rights.count;++i) p.fd[p.count++]=rights.fd[i];
    }
    return p;
}
static void channel(int fds[2]) {
    assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_NONBLOCK|SOCK_CLOEXEC,0,fds));
}
static pid_t start(const char *mode, int fd, const char *base) {
    pid_t parent=getpid(), child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL) && getppid()==parent);
        assert(!fcntl(fd,F_SETFD,0)); char number[16]; snprintf(number,sizeof(number),"%d",fd);
        execl("/proc/self/exe","mount-graph",mode,number,base,(char *)NULL); _exit(90);
    }
    return child;
}
static void reap(pid_t child) {
    /* EVENT_WAIT: exact fixture process; outer alarm bounds a stuck child. */
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
}
static void path(char *out, const char *base, const char *suffix) {
    assert(snprintf(out,PATH_MAX,"%s/%s",base,suffix)<PATH_MAX);
}
static void file(const char *path, const char *text) {
    int fd=open(path,O_CREAT|O_WRONLY|O_CLOEXEC,0600); assert(fd>=0);
    assert(write(fd,text,strlen(text))==(ssize_t)strlen(text)); close(fd);
}
static struct md_filesystem filesystem(const char *base, int create) {
    char store[PATH_MAX]; path(store,base,"store");
    struct md_filesystem fs={0}; assert(!md_inode_store_open(store,create,&fs.store));
    if (!create) return fs;
    char host[3][PATH_MAX], name[PATH_MAX];
    for (unsigned i=0;i<3;++i) {
        char leaf[16]; snprintf(leaf,sizeof(leaf),"host%u",i); path(host[i],base,leaf);
        assert(!mkdir(host[i],0700));
    }
    path(name,host[0],"nested"); assert(!mkdir(name,0700));
    path(name,host[0],"parent"); file(name,"native");
    path(name,host[1],"value"); file(name,"value");
    path(name,host[1],"absolute"); assert(!symlink("/parent",name));
    assert(!md_inode_mkdir(fs.store,-1,"/outer",0700));
    assert(!md_inode_mkdir(fs.store,-1,"/alias",0700));
    int fd=md_inode_open(fs.store,-1,"/parent",O_CREAT|O_WRONLY,0600); assert(fd>=0);
    assert(write(fd,"guest",5)==5); close(fd);
    struct md_fs_attachment entries[]={{host[0],"/outer",0},{host[1],"/outer/nested",1},{host[2],"/alias",0}};
    assert(!md_fs_mounts_open(&fs,entries,3));
    /* The engine supports aliases; launch admission stays restricted until
     * application FD provenance is connected. Build one before publication. */
    struct md_view_mount *a=&fs.mounts->tree->mounts[1], *b=&fs.mounts->tree->mounts[2];
    close(b->source); b->source=fcntl(a->source,F_DUPFD_CLOEXEC,3); assert(b->source>=0);
    b->native=a->native; return fs;
}
static void close_filesystem(struct md_filesystem *fs) {
    md_fs_mounts_close(fs); md_inode_store_close(fs->store); fs->store=NULL;
}
static void *deep_paths(void *context) {
    struct md_filesystem *fs=context;
    enum { DEPTH=600 };
    struct md_view_mount *mounts=calloc(DEPTH,sizeof(*mounts)); assert(mounts);
    struct md_view_mount *base=&fs->mounts->tree->mounts[0];
    int target=openat(base->source,"nested",O_PATH|O_DIRECTORY|O_CLOEXEC);
    int leaf=openat(base->source,"parent",O_PATH|O_CLOEXEC);
    assert(target>=0 && leaf>=0);
    for (unsigned i=0;i<DEPTH;++i) {
        mounts[i]=*base;
        mounts[i].parent=i ? &mounts[i-1] : base;
        mounts[i].target=base->source;
    }
    char actual[PATH_MAX],expected[PATH_MAX]="/outer";
    assert(!md_view_path(fs,leaf,&mounts[DEPTH-1],actual,sizeof(actual)));
    assert(!strcmp(actual,"/outer/parent"));
    for (unsigned i=0;i<512;++i) {
        mounts[i].target=target;
        strcat(expected,"/nested");
    }
    strcat(expected,"/parent");
    assert(!md_view_path(fs,leaf,&mounts[511],actual,sizeof(actual)) && !strcmp(actual,expected));
    assert(md_view_path(fs,leaf,&mounts[511],actual,strlen(expected))==-ERANGE);
    mounts[255].detached=1;
    assert(md_view_path(fs,leaf,&mounts[511],actual,sizeof(actual))==-ENOENT);
    mounts[255].detached=0;
    for (unsigned i=512;i<DEPTH;++i) mounts[i].target=target;
    assert(md_view_path(fs,leaf,&mounts[DEPTH-1],actual,sizeof(actual))==-ERANGE);
    close(leaf); close(target); free(mounts);
    return NULL;
}
static void deep_mount_paths(const char *base) {
    unsigned before=descriptors();
    char dir[PATH_MAX]; path(dir,base,"deep-paths"); assert(!mkdir(dir,0700));
    struct md_filesystem fs=filesystem(dir,1);
    pthread_attr_t attr; assert(!pthread_attr_init(&attr));
    assert(!pthread_attr_setstacksize(&attr,128*1024));
    pthread_t thread; assert(!pthread_create(&thread,&attr,deep_paths,&fs));
    assert(!pthread_join(thread,NULL) && !pthread_attr_destroy(&attr));
    close_filesystem(&fs); assert(descriptors()==before);
    puts("PASS deep mount paths: bounded stack, stacked roots, nested suffixes, detach and size limits");
}
static struct md_fs_result open_file(struct md_filesystem *fs, const char *name, unsigned flags,
        struct md_fs_anchor *origin, struct md_fs_anchor **capture) {
    struct md_fs_request q={.operation=MD_FS_OPEN,.directory={origin ? md_fs_anchor_fd(origin) : -1,-1},
        .origin={origin,NULL},.path={name,NULL},.flags=flags};
    struct md_fs_result out;
    md_fs_execute(fs,&q,&out,&(struct md_fs_output){.origin=capture}); return out;
}
static void contents(struct md_filesystem *fs, const char *name, struct md_fs_anchor *origin, const char *expected) {
    struct md_fs_result r=open_file(fs,name,O_RDONLY,origin,NULL);
    if (r.error) fprintf(stderr,"graph open %s: %d\n",name,r.error);
    assert(!r.error); char bytes[32]={0};
    assert(read(r.fd,bytes,sizeof(bytes)-1)==(ssize_t)strlen(expected) && !strcmp(bytes,expected)); close(r.fd);
}
static void checks(struct md_filesystem *fs, unsigned readonly) {
    assert(fs->mounts->tree->count==3);
    struct md_view_mount *m=fs->mounts->tree->mounts;
    assert(m[0].parent==&fs->mounts->base && m[1].parent==m && m[2].parent==&fs->mounts->base);
    assert(m[0].id!=m[1].id && m[1].id!=m[2].id && m[0].id!=m[2].id);
    contents(fs,"/outer/nested/value",NULL,"value");
    contents(fs,"/alias/value",NULL,"value");
    contents(fs,"/outer/nested/../parent",NULL,"native");
    contents(fs,"/alias/../parent",NULL,"guest");
    contents(fs,"/outer/nested/absolute",NULL,"guest");
    struct md_fs_result r=open_file(fs,"/outer/nested/value",O_RDWR,NULL,NULL);
    if (readonly) assert(r.error==-EROFS && r.fd<0);
    else { assert(!r.error); close(r.fd); }
    r=open_file(fs,"/alias/value",O_RDWR,NULL,NULL); assert(!r.error); close(r.fd);
}
static void duplicate_manifest(struct md_mount_graph_parcel *p, unsigned word, uint32_t value,
        int seals, int length_adjustment) {
    uint32_t words[256]; ssize_t n=pread(p->fd[0],words,sizeof(words),0); assert(n>=16);
    words[word]=value;
    int fd=memfd_create("graph-invalid",MFD_CLOEXEC|MFD_ALLOW_SEALING); assert(fd>=0);
    assert(write(fd,words,(size_t)n)==n);
    if (length_adjustment) assert(!ftruncate(fd,n+length_adjustment));
    assert(!fcntl(fd,F_ADD_SEALS,seals)); close(p->fd[0]); p->fd[0]=fd;
}
static void failures(struct md_filesystem *source) {
    puts("CHECK mount graph malformed parcels");
    struct md_mount_graph_parcel p={0};
    assert(!md_mount_graph_export(source->mounts,source->store->lifetime->root,&p)); md_mount_graph_parcel_close(&p);
    unsigned before=descriptors();
    test_mount_reservation_failure(1);
    errno=0; assert(!md_view_tree_new() && errno==ENOMEM);
    struct md_mount_namespace *failed_copy=NULL;
    struct md_fs_location copied_root={0},root={0};
    assert(md_fs_mounts_copy(source,&root,&failed_copy,&copied_root)==-ENOMEM && !failed_copy);
    test_mount_reservation_failure(0);
    assert(descriptors()==before);
    assert(!md_mount_graph_export(source->mounts,source->store->lifetime->root,&p));
    struct md_mount_namespace *failed_view=NULL;
    test_mount_reservation_failure(1);
    int failed_result=md_mount_graph_import(&p,&failed_view);
    test_mount_reservation_failure(0);
    assert(failed_result==-ENOMEM && !failed_view && !p.count && descriptors()==before);
    for (unsigned i=0;i<14;++i) {
        assert(!md_mount_graph_export(source->mounts,source->store->lifetime->root,&p));
        int seals=F_SEAL_WRITE|F_SEAL_GROW|F_SEAL_SHRINK|F_SEAL_SEAL;
        switch (i) {
        case 0: duplicate_manifest(&p,0,0,seals,0); break;
        case 1: duplicate_manifest(&p,1,UINT32_MAX,seals,0); break;
        case 2: duplicate_manifest(&p,2,MD_FS_MOUNTS_MAX+1,seals,0); break;
        case 3: duplicate_manifest(&p,3,1,seals,0); break;
        case 4: duplicate_manifest(&p,36,1,seals,0); break;
        case 5: duplicate_manifest(&p,37,UINT32_MAX,seals,0); break;
        case 6: duplicate_manifest(&p,1,7,seals,1); break;
        case 7: duplicate_manifest(&p,1,7,seals&~F_SEAL_WRITE,0); break;
        case 8: { int fd=p.fd[2]; p.fd[2]=p.fd[3]; p.fd[3]=fd; break; }
        case 9: { int fd=p.fd[4]; p.fd[4]=p.fd[7]; p.fd[7]=fd; break; }
        case 10: close(p.fd[--p.count]); break;
        case 11: assert(!fcntl(p.fd[1],F_SETFD,0)); break;
        case 12: close(p.fd[2]); p.fd[2]=p.fd[1]; break;
        case 13: duplicate_manifest(&p,36,2,seals,0); break;
        }
        struct md_mount_namespace *view=NULL;
        assert(md_mount_graph_import(&p,&view)<0 && !view && !p.count);
        assert(descriptors()==before);
    }
    for (int i=0;i<3;++i) {
        printf("CHECK mount graph allocation failure %d\n",i);
        assert(!md_mount_graph_export(source->mounts,source->store->lifetime->root,&p));
        struct md_mount_namespace *view=NULL; fail_allocation=i;
        int result=md_mount_graph_import(&p,&view);
        fail_allocation=-1;
        assert(result==-ENOMEM && !view && !p.count && descriptors()==before);
    }
    for (int i=0;i<3;++i) {
        printf("CHECK mount graph mapping failure %d\n",i);
        assert(!md_mount_graph_export(source->mounts,source->store->lifetime->root,&p));
        struct md_mount_namespace *view=NULL; test_mount_policy_map_failure_after(i);
        int result=md_mount_graph_import(&p,&view);
        test_mount_policy_map_failure_after(-1);
        assert(result==-ENOMEM && !view && !p.count && descriptors()==before);
    }
    puts("CHECK mount graph descriptor exhaustion");
    int guard=fcntl(source->mounts->tree->mounts[0].source,F_DUPFD_CLOEXEC,3); assert(guard>=3);
    assert(fcntl(3,F_GETFD)>=0);
    struct rlimit old, limited; assert(!getrlimit(RLIMIT_NOFILE,&old)); limited=old; limited.rlim_cur=4;
    assert(!setrlimit(RLIMIT_NOFILE,&limited));
    int result=md_mount_graph_export(source->mounts,source->store->lifetime->root,&p);
    assert(!setrlimit(RLIMIT_NOFILE,&old));
    assert(result==-EMFILE && !p.count); close(guard); assert(descriptors()==before);
    /* Leave exactly one admissible slot: manifest duplication succeeds, source
     * duplication fails, and rollback must release the partial parcel. */
    limited.rlim_cur=128; if (limited.rlim_cur>old.rlim_cur) limited.rlim_cur=old.rlim_cur;
    assert(!setrlimit(RLIMIT_NOFILE,&limited));
    int fill[128]; unsigned filled=0;
    while ((guard=fcntl(source->mounts->tree->mounts[0].source,F_DUPFD_CLOEXEC,3))>=0) {
        assert(filled<128); fill[filled++]=guard;
    }
    assert(errno==EMFILE && filled); close(fill[--filled]);
    result=md_mount_graph_export(source->mounts,source->store->lifetime->root,&p);
    assert(!setrlimit(RLIMIT_NOFILE,&old));
    assert(result==-EMFILE && !p.count);
    while (filled) close(fill[--filled]);
    assert(descriptors()==before);
    struct md_view_mount *alias=&source->mounts->tree->mounts[2];
    alias->parent=&source->mounts->tree->mounts[0];
    assert(md_mount_graph_export(source->mounts,source->store->lifetime->root,&p)==-ESTALE && !p.count);
    alias->parent=&source->mounts->base; assert(descriptors()==before);
    puts("PASS mount graph rollback: malformed graph/rights, allocation and FD exhaustion, unpublished mutation rejection");
}
static int scan_order(unsigned count,const unsigned *parents,unsigned *order) {
    unsigned done[count+1], used=0;
    memset(done,0,sizeof(done));
    for (unsigned i=0;i<count;++i) if (parents[i]>count || parents[i]==i+1) return -EINVAL;
    while (used<count) {
        unsigned before=used;
        for (unsigned i=0;i<count;++i) if (!done[i] && (!parents[i] || done[parents[i]-1])) {
            done[i]=1; order[used++]=i;
        }
        if (used==before) return -ELOOP;
    }
    return 0;
}
static void topology_order(void) {
    assert(!md_mount_topology_order(0,NULL,NULL));
    assert(md_mount_topology_order(MD_FS_MOUNTS_MAX+2,NULL,NULL)==-EINVAL);
    assert(md_mount_topology_order(1,NULL,NULL)==-EINVAL);
    for (unsigned count=1;count<=5;++count) {
        unsigned parents[5]={0}, expected[5], actual[5];
        for (;;) {
            int result=scan_order(count,parents,expected);
            assert(md_mount_topology_order(count,parents,actual)==result);
            if (!result) assert(!memcmp(expected,actual,count*sizeof(*actual)));
            unsigned i=0;
            while (i<count && ++parents[i]>count+1) parents[i++]=0;
            if (i==count) break;
        }
    }
    unsigned count=MD_FS_MOUNTS_MAX+1, parents[count], order[count];
    for (unsigned i=0;i<count;++i) parents[i]=i+1<count ? i+2 : 0;
    assert(!md_mount_topology_order(count,parents,order));
    for (unsigned i=0;i<count;++i) assert(order[i]==count-1-i);
    parents[count-1]=1;
    assert(md_mount_topology_order(count,parents,order)==-ELOOP);
    puts("PASS linear mount topology: exhaustive small graphs preserve scan order/errors; full-limit reversed chain and cycle");
}
static void unordered(const char *base) {
    char directory[PATH_MAX]; path(directory,base,"unordered"); assert(!mkdir(directory,0700));
    unsigned before=descriptors(); struct md_filesystem fs=filesystem(directory,1);
    struct md_view_mount *m=fs.mounts->tree->mounts, saved=m[0];
    m[0]=m[1]; m[1]=saved; m[0].parent=&m[1];
    unsigned order[MD_FS_MOUNTS_MAX+1];
    assert(!md_view_topology_order(fs.mounts,order));
    assert(order[0]==0 && order[1]==2 && order[2]==3 && order[3]==1);
    contents(&fs,"/outer/nested/value",NULL,"value");
    struct md_mount_graph_parcel p={0}; assert(!md_mount_graph_export(fs.mounts,fs.store->lifetime->root,&p));
    struct md_filesystem imported={.store=fs.store};
    assert(!md_mount_graph_import(&p,&imported.mounts));
    assert(imported.mounts->tree->mounts[0].parent==&imported.mounts->tree->mounts[1]);
    contents(&imported,"/outer/nested/../parent",NULL,"native");
    struct md_mount_namespace *copy=NULL; struct md_fs_location root;
    assert(!md_fs_mounts_copy(&fs,&(struct md_fs_location){0},&copy,&root));
    assert(copy->tree->mounts[0].parent==&copy->tree->mounts[1]);
    copy->tree->mounts[1].parent=&copy->tree->mounts[0];
    assert(md_mount_graph_export(copy,fs.store->lifetime->root,&p)==-ELOOP && !p.count);
    md_mount_namespace_release(copy); md_fs_mounts_close(&imported); close_filesystem(&fs);
    assert(descriptors()==before);
    puts("PASS mount topology: child-before-parent placement, portable graph/copy and cycle rejection");
}
static void placement_lookup(void) {
    struct md_mount_namespace view={.base={.source=0}};
    view.tree=md_view_tree_new(); assert(view.tree);
    const unsigned counts[]={0,1,8,9,MD_FS_MOUNTS_MAX};
    for (unsigned c=0;c<sizeof(counts)/sizeof(*counts);++c) {
        view.tree->count=counts[c];
        for (unsigned i=1;i<=counts[c];++i) {
            struct md_view_mount *m=md_view_mount_node(&view,i);
            *m=(struct md_view_mount){.source=i%11 ? 0 : -1,
                .parent=i%7 ? &view.base : NULL,.detached=!(i%13),
                .guest={.st_dev=i%3,.st_ino=i%37}};
        }
        for (unsigned round=0;round<2;++round) {
            if (round && counts[c]) {
                /* Same slot/count, changed parent/target and attachment state. */
                view.tree->mounts[0].parent=md_view_mount_node(&view,counts[c]);
                view.tree->mounts[0].guest.st_ino=77;
                view.tree->mounts[counts[c]-1].detached^=1;
            }
            md_view_mount_lookup_changed(&view);
            for (unsigned p=0;p<3;++p) for (unsigned dev=0;dev<4;++dev)
                for (unsigned ino=0;ino<80;++ino) {
                    struct md_view_mount *parent=p==0 ? NULL : p==1 ? &view.base
                        : md_view_mount_node(&view,counts[c]);
                    struct stat st={.st_dev=dev,.st_ino=ino};
                    struct md_view_mount *expected=NULL;
                    for (unsigned i=0;i<=counts[c];++i) {
                        struct md_view_mount *m=md_view_mount_node(&view,i);
                        if (m->source>=0 && !m->detached && m->parent==parent && md_view_same(&m->guest,&st)) {
                            expected=m; break;
                        }
                    }
                    assert(md_view_mount_lookup(&view,parent,&st)==expected);
                }
        }
    }
    md_view_tree_free(view.tree);
    puts("PASS mount placement index: exact scan order, collisions, base, missing keys and same-count invalidation");
}
static void sizes(const char *base) {
    unsigned before=descriptors();
    struct md_mount_namespace *empty=md_mount_namespace_new(), *received=NULL; assert(empty);
    int root_fd=open(base,O_PATH|O_DIRECTORY|O_CLOEXEC); assert(root_fd>=0);
    assert(!md_mount_namespace_lock_inherited(empty));
    struct md_mount_graph_parcel p={0}; assert(!md_mount_graph_export(empty,root_fd,&p) && p.count==4);
    close(root_fd);
    assert(!md_mount_graph_import(&p,&received) && !received->tree);
    assert(received->base.locked);
    md_mount_namespace_release(empty); md_mount_namespace_release(received);
    char store[PATH_MAX];
    char (*host)[PATH_MAX]=calloc(MD_FS_MOUNTS_MAX,sizeof(*host));
    char (*target)[32]=calloc(MD_FS_MOUNTS_MAX,sizeof(*target));
    struct md_fs_attachment *entries=calloc(MD_FS_MOUNTS_MAX,sizeof(*entries));
    assert(host && target && entries);
    path(store,base,"max-store"); struct md_filesystem fs={0};
    assert(!md_inode_store_open(store,1,&fs.store));
    for (unsigned i=0;i<MD_FS_MOUNTS_MAX;++i) {
        char leaf[32]; snprintf(leaf,sizeof(leaf),"max-host-%u",i); path(host[i],base,leaf);
        snprintf(target[i],sizeof(target[i]),"/volume-%u",i);
        assert(!mkdir(host[i],0700) && !md_inode_mkdir(fs.store,-1,target[i],0700));
        entries[i]=(struct md_fs_attachment){host[i],target[i],i&1};
    }
    puts("CHECK maximum mount graph: create full topology");
    assert(!md_fs_mounts_open(&fs,entries,MD_FS_MOUNTS_MAX));
    struct md_inode_statistics stats={0};
    md_inode_measure(fs.store,&stats);
    assert(!md_view_mount_reconcile(&fs));
    md_inode_measure(fs.store,NULL);
    assert(stats.lock.calls==1 && !fs.store->read_depth);
    for (unsigned i=0;i<MD_FS_MOUNTS_MAX;++i) {
        struct md_view_mount *m=&fs.mounts->tree->mounts[i];
        assert(md_view_mount_lookup(fs.mounts,m->parent,&m->guest)==m);
    }
    fail_allocation=0;
    int collected=md_view_mount_collect(&fs);
    fail_allocation=-1;
    assert(!collected);
    puts("PASS mount reconciliation: 4096 targets share one fresh store snapshot; unchanged collection allocates no candidate");
    fs.mounts->base.root_providers=1;
    fs.mounts->tree->mounts[MD_FS_MOUNTS_MAX-1].root_providers=1;
    puts("CHECK maximum mount graph: export all descriptors");
    assert(!md_mount_graph_export(fs.mounts,fs.store->lifetime->root,&p) && p.count==4+3*MD_FS_MOUNTS_MAX);
    assert(p.count>253); /* Exercises the transport beyond one SCM_RIGHTS message. */
    puts("CHECK maximum mount graph: import full topology");
    received=NULL; assert(!md_mount_graph_import(&p,&received));
    assert(received->tree->count==MD_FS_MOUNTS_MAX);
    assert(received->base.root_providers && received->tree->mounts[MD_FS_MOUNTS_MAX-1].root_providers);
    for (unsigned i=0;i<MD_FS_MOUNTS_MAX;++i) {
        assert(md_view_readonly(&received->tree->mounts[i])==(int)(i&1));
        assert(md_view_same(&received->tree->mounts[i].native,&fs.mounts->tree->mounts[i].native));
        struct md_view_mount *m=&received->tree->mounts[i];
        assert(md_view_mount_lookup(received,m->parent,&m->guest)==m);
    }
    struct md_identity actor=md_identity_new(0,0);
    fs.store->identity=&actor;
    struct md_fs_request extra={.operation=MD_FS_MOUNT,.mode=MD_FS_MOUNT_BIND,
        .directory={-1,-1},.path={target[0],target[1]},.flags=MS_BIND};
    assert(md_fs_mount_change(&fs,NULL,&extra)==-ENOSPC);
    assert(fs.mounts->tree->count==MD_FS_MOUNTS_MAX);
    fs.store->identity=NULL; md_identity_release(&actor);
    md_mount_namespace_release(received); received=NULL;
    struct md_mount_namespace *copy=NULL; struct md_fs_location copied_root;
    puts("CHECK maximum mount graph: copy full topology");
    assert(!md_fs_mounts_copy(&fs,&(struct md_fs_location){0},&copy,&copied_root));
    assert(copy->tree->count==MD_FS_MOUNTS_MAX && copy->tree!=fs.mounts->tree);
    assert(copy->base.root_providers && copy->tree->mounts[MD_FS_MOUNTS_MAX-1].root_providers);
    for (unsigned i=0;i<MD_FS_MOUNTS_MAX;++i) {
        assert(md_view_same(&copy->tree->mounts[i].native,&fs.mounts->tree->mounts[i].native));
        struct md_view_mount *entry=&copy->tree->mounts[i];
        assert(md_view_mount_lookup(copy,entry->parent,&entry->guest)==entry);
    }
    md_mount_namespace_release(copy);
    md_mount_namespace_release(received); close_filesystem(&fs); assert(descriptors()==before);
    free(entries); free(target); free(host);
    puts("PASS mount graph bounds: empty and maximum topology, exact rights count and cleanup");
}
static void models(const char *base) {
    puts("CHECK mount graph source setup");
    unsigned before=descriptors(); struct md_filesystem source=filesystem(base,1);
    assert(!source.mounts->graph);
    struct md_mount_graph_parcel p={0};
    puts("CHECK mount graph lazy publication failure");
    fail_allocation=0;
    int result=md_mount_graph_export(source.mounts,source.store->lifetime->root,&p);
    fail_allocation=-1;
    assert(result==-ENOMEM && !p.count && !source.mounts->graph);
    checks(&source,1); failures(&source);
    assert(!md_mount_graph_export(source.mounts,source.store->lifetime->root,&p));
    struct md_filesystem imported={.store=source.store};
    assert(!md_mount_graph_import(&p,&imported.mounts) && !p.count);
    checks(&imported,1); struct md_mount_namespace *saved=imported.mounts;
    unsigned id=saved->tree->mounts[1].id, objects=saved->tree->objects, current=descriptors();
    assert(id!=source.mounts->tree->mounts[1].id);
    for (unsigned i=0;i<10;++i) {
        assert(!md_mount_graph_export(source.mounts,source.store->lifetime->root,&p));
        assert(!md_mount_graph_import(&p,&imported.mounts));
        assert(imported.mounts==saved && saved->tree->mounts[1].id==id
            && saved->tree->objects==objects && descriptors()==current);
    }
    assert(!md_mount_graph_export(source.mounts,source.store->lifetime->root,&p));
    saved->tree->mounts[2].parent=&saved->tree->mounts[0];
    assert(md_mount_graph_import(&p,&imported.mounts)==-ESTALE && imported.mounts==saved);
    saved->tree->mounts[2].parent=&saved->base; assert(descriptors()==current);
    /* Forwarding keeps the sealed identity; it does not publish a new graph. */
    assert(!md_mount_graph_export(imported.mounts,imported.store->lifetime->root,&p));
    assert(!md_mount_graph_import(&p,&source.mounts));
    struct md_mount_namespace *copy=NULL; struct md_fs_location root;
    assert(!md_fs_mounts_copy(&source,&(struct md_fs_location){0},&copy,&root) && !copy->graph);
    assert(!md_mount_graph_export(copy,source.store->lifetime->root,&p));
    assert(md_mount_graph_import(&p,&imported.mounts)==-ESTALE && imported.mounts==saved);
    md_mount_namespace_release(copy);
    assert(!md_mount_policy_set(&source.mounts->tree->mounts[1].policy,0)); checks(&imported,0);
    assert(md_mount_policy_set(&imported.mounts->tree->mounts[1].policy,1)==-EPERM);
    struct md_fs_anchor *origin=NULL;
    struct md_fs_result r=open_file(&imported,"/outer/nested",O_PATH|O_DIRECTORY,NULL,&origin);
    assert(!r.error && origin); close(r.fd);
    md_fs_mounts_close(&imported); md_fs_mounts_close(&source);
    assert(!md_fs_mounts_open(&imported,NULL,0));
    contents(&imported,"../parent",origin,"native");
    md_fs_anchor_release(origin); md_fs_mounts_close(&imported); md_inode_store_close(source.store);
    assert(descriptors()==before);
    puts("PASS mount graph engine: nested parents, aliases, local IDs, repeated/forwarded import, live policy and retained dirfd");
}
static int mutation(struct md_filesystem *fs, unsigned operation, unsigned flags,
        const char *source, const char *target) {
    struct md_identity identity=md_identity_new(0,0);
    fs->store->identity=&identity;
    struct md_fs_request q={.operation=MD_FS_MOUNT,.mode=operation,.flags=flags,
        .directory={MD_INODE_ROOT,MD_INODE_ROOT},.path={target,source}};
    struct md_fs_result out;
    md_fs_execute(fs,&q,&out,NULL);
    fs->store->identity=NULL; md_identity_release(&identity);
    return out.error;
}
static void indexed_updates(const char *base) {
    unsigned before=descriptors();
    char dir[PATH_MAX]; path(dir,base,"indexed-updates"); assert(!mkdir(dir,0700));
    struct md_filesystem fs=filesystem(dir,1),foreign={.store=fs.store};
    for (unsigned i=0;i<16;++i) {
        char point[32]; snprintf(point,sizeof(point),"/point-%u",i);
        assert(!md_inode_mkdir(fs.store,-1,point,0700));
        assert(!mutation(&fs,MD_FS_MOUNT_BIND,MS_BIND,"/alias",point));
    }
    assert(!md_inode_mkdir(fs.store,-1,"/relocated",0700));
    struct md_mount_graph_parcel parcel={0};
    assert(!md_mount_graph_export(fs.mounts,fs.store->lifetime->root,&parcel));
    assert(!md_mount_graph_import(&parcel,&foreign.mounts));
    unsigned last=fs.mounts->tree->count-1;
    struct md_view_mount *m=&fs.mounts->tree->mounts[last];
    struct stat covered=m->guest;
    contents(&fs,"/point-15/value",NULL,"value");
    contents(&foreign,"/point-15/value",NULL,"value");
    assert(fs.mounts->tree->lookup.valid && foreign.mounts->tree->lookup.valid);
    fail_allocation=0;
    assert(mutation(&fs,MD_FS_MOUNT_MOVE,MS_MOVE,"/point-15","/relocated")==-ENOMEM);
    fail_allocation=-1;
    assert(md_view_mount_lookup(fs.mounts,&fs.mounts->base,&covered)==m);
    assert(!mutation(&fs,MD_FS_MOUNT_MOVE,MS_MOVE,"/point-15","/relocated"));
    assert(!md_view_mount_lookup(fs.mounts,&fs.mounts->base,&covered));
    contents(&fs,"/relocated/value",NULL,"value");
    contents(&foreign,"/relocated/value",NULL,"value");
    assert(!md_view_mount_lookup(foreign.mounts,&foreign.mounts->base,&covered));
    covered=m->guest;
    assert(!mutation(&fs,MD_FS_MOUNT_UMOUNT,0,NULL,"/relocated"));
    assert(!md_view_mount_lookup(fs.mounts,&fs.mounts->base,&covered));
    assert(!md_mount_graph_sync(foreign.mounts));
    assert(!md_view_mount_lookup(foreign.mounts,&foreign.mounts->base,&covered));
    assert(!mutation(&fs,MD_FS_MOUNT_BIND,MS_BIND,"/alias","/point-15"));
    assert(m->source>=0 && !m->detached && md_view_mount_lookup(fs.mounts,m->parent,&m->guest)==m);
    contents(&foreign,"/point-15/value",NULL,"value");
    assert(md_view_mount_lookup(foreign.mounts,&foreign.mounts->base,&m->guest)==&foreign.mounts->tree->mounts[last]);
    md_fs_mounts_close(&foreign); close_filesystem(&fs);
    assert(descriptors()==before);
    puts("PASS indexed mount placement: live move, detach, slot reuse, rollback and foreign graph synchronization");
}
static void live_move(struct md_filesystem *fs) {
    assert(!mutation(fs,MD_FS_MOUNT_MOVE,MS_MOVE,"/outer/nested","/outer"));
}
static void live_add(struct md_filesystem *fs) {
    assert(!mutation(fs,MD_FS_MOUNT_BIND,MS_BIND,"/alias","/outer/nested"));
}
static void live_detach(struct md_filesystem *fs) {
    assert(!mutation(fs,MD_FS_MOUNT_UMOUNT,MNT_DETACH,NULL,"/outer"));
}
static void updates(const char *base) {
    unsigned before=descriptors();
    char directory[PATH_MAX]; path(directory,base,"updates"); assert(!mkdir(directory,0700));
    struct md_filesystem fs=filesystem(directory,1), peer={.store=fs.store};
    struct md_mount_graph_parcel p={0}; assert(!md_mount_graph_export(fs.mounts,fs.store->lifetime->root,&p));
    assert(!md_mount_graph_import(&p,&peer.mounts));
    struct md_fs_anchor *origin=NULL;
    struct md_fs_result opened=open_file(&peer,"/outer/nested",O_PATH|O_DIRECTORY,NULL,&origin);
    assert(!opened.error); close(opened.fd);
    unsigned id=origin->mount->id;
    contents(&peer,"/outer/nested/value",NULL,"value");
    unsigned stable=descriptors();
    int committed=0;
    for (int i=0;i<16 && !committed;++i) {
        fail_allocation=i;
        int r=mutation(&fs,MD_FS_MOUNT_MOVE,MS_MOVE,"/outer/nested","/outer");
        fail_allocation=-1;
        if (!r) committed=1;
        else {
            if (r!=-ENOMEM || descriptors()!=stable)
                fprintf(stderr,"mount publication allocation=%d error=%d descriptors=%u expected=%u\n",i,r,descriptors(),stable);
            assert(r==-ENOMEM && descriptors()==stable);
            contents(&peer,"/outer/nested/value",NULL,"value");
            assert(origin->mount->id==id);
        }
    }
    assert(committed);
    contents(&peer,"/outer/value",NULL,"value");
    contents(&peer,"../parent",origin,"guest");
    assert(origin->mount->id==id && peer.mounts->tree->count==3);
    live_detach(&fs);
    contents(&peer,"/outer/parent",NULL,"native");
    contents(&peer,"value",origin,"value");
    live_add(&fs);
    contents(&peer,"/outer/nested/value",NULL,"value");
    assert(peer.mounts->tree->count==4 && origin->mount->id==id);
    struct md_fs_anchor *latest=NULL;
    opened=open_file(&peer,"/outer/nested",O_PATH|O_DIRECTORY,NULL,&latest);
    assert(!opened.error); close(opened.fd);
    assert(!md_mount_graph_export(peer.mounts,peer.store->lifetime->root,&p));
    struct md_mount_namespace *forward=NULL; assert(!md_mount_graph_import(&p,&forward));
    md_mount_namespace_release(forward);
    md_fs_mounts_close(&fs);
    contents(&peer,"value",origin,"value");
    contents(&peer,"value",latest,"value");
    md_fs_anchor_release(origin); md_fs_anchor_release(latest);
    md_fs_mounts_close(&peer); md_inode_store_close(fs.store);
    assert(descriptors()==before);
    puts("PASS live mount graph: move, detach, append, stable old dirfd, forwarding and writer exit");
}
static void private_publication(const char *base) {
    unsigned before=descriptors();
    char directory[PATH_MAX]; path(directory,base,"private-publication"); assert(!mkdir(directory,0700));
    struct md_filesystem fs=filesystem(directory,1), peer={.store=fs.store};
    struct md_mount_graph_parcel p={0};
    assert(!md_mount_graph_export(fs.mounts,fs.store->lifetime->root,&p));
    assert(!md_mount_graph_import(&p,&peer.mounts));
    unsigned id=peer.mounts->tree->mounts[0].id;
    struct md_view_mount next[3];
    assert(fs.mounts->tree->count==3);
    memcpy(next,fs.mounts->tree->mounts,fs.mounts->tree->count*sizeof(*next));
    next[2].file=md_proc_sysctl_new(MD_PROC_FILE_HOSTNAME,NULL,NULL); assert(next[2].file.ops);
    struct md_mount_layout layout={.base=&fs.mounts->base,.root=fs.mounts->root,
        .entries=next,.count=fs.mounts->tree->count};
    contents(&peer,"/outer/nested/value",NULL,"value");
    unsigned stable=descriptors(); int committed=0;
    for (int i=0;i<16 && !committed;++i) {
        fail_allocation=i;
        int r=md_mount_graph_update(fs.mounts,&layout);
        fail_allocation=-1;
        if (!r) committed=1;
        else {
            if (r!=-ENOMEM || descriptors()!=stable)
                fprintf(stderr,"private publication allocation=%d error=%d descriptors=%u expected=%u\n",i,r,descriptors(),stable);
            assert(r==-ENOMEM && descriptors()==stable);
            contents(&peer,"/outer/nested/value",NULL,"value");
        }
    }
    assert(committed);
    fs.mounts->tree->mounts[2].file=next[2].file;
    contents(&fs,"/outer/nested/value",NULL,"value");
    assert(md_mount_graph_export(fs.mounts,fs.store->lifetime->root,&p)==-EOPNOTSUPP && !p.count);
    stable=descriptors();
    for (unsigned i=0;i<16;++i) {
        assert(md_mount_graph_sync(peer.mounts)==-EOPNOTSUPP);
        struct md_fs_result result=open_file(&peer,"/outer/nested/value",O_RDONLY,NULL,NULL);
        assert(result.error==-EOPNOTSUPP && result.fd<0 && descriptors()==stable);
    }
    next[2].file=(struct md_control_file){0};
    assert(!md_mount_graph_update(fs.mounts,&layout));
    md_control_file_release(fs.mounts->tree->mounts[2].file);
    fs.mounts->tree->mounts[2].file=(struct md_control_file){0};
    contents(&peer,"/outer/nested/value",NULL,"value");
    assert(peer.mounts->tree->mounts[0].id==id);
    assert(!md_mount_graph_export(fs.mounts,fs.store->lifetime->root,&p));
    assert(!md_mount_graph_import(&p,&peer.mounts));
    md_fs_mounts_close(&peer); close_filesystem(&fs);
    assert(descriptors()==before);
    puts("PASS private mount publication: local authority, explicit foreign rejection, recovery and rollback");
}
static void retirement(const char *base, int exported) {
    unsigned before=descriptors();
    char directory[PATH_MAX]; path(directory,base,exported ? "retire-shared" : "retire-local");
    assert(!mkdir(directory,0700));
    struct md_filesystem fs=filesystem(directory,1), peer={.store=fs.store};
    struct md_filesystem *reader=&fs;
    struct md_mount_graph_parcel p={0}, old={0};
    if (exported) {
        assert(!md_mount_graph_export(fs.mounts,fs.store->lifetime->root,&p));
        assert(!md_mount_graph_import(&p,&peer.mounts)); reader=&peer;
        assert(!md_mount_graph_export(fs.mounts,fs.store->lifetime->root,&old));
    }
    struct md_fs_anchor *origin=NULL;
    struct md_fs_result opened=open_file(reader,"/alias",O_PATH|O_DIRECTORY,NULL,&origin);
    assert(!opened.error && origin); close(opened.fd);
    unsigned id=origin->mount->id;
    uint64_t generation=origin->mount->generation;
    assert(!mutation(&fs,MD_FS_MOUNT_UMOUNT,MNT_DETACH,NULL,"/alias"));
    assert(!mutation(&fs,MD_FS_MOUNT_BIND,MS_BIND,"/outer","/alias"));
    assert(fs.mounts->tree->count==4 && fs.mounts->tree->mounts[2].generation==generation);
    contents(reader,"/alias/parent",NULL,"native"); contents(reader,"value",origin,"value");
    assert(origin->mount->id==id); md_fs_anchor_release(origin);
    unsigned stable=0;
    for (unsigned i=0;i<64;++i) {
        assert(!mutation(&fs,MD_FS_MOUNT_UMOUNT,0,NULL,"/alias"));
        assert(!mutation(&fs,MD_FS_MOUNT_BIND,MS_BIND,"/outer/nested","/alias"));
        contents(reader,"/alias/value",NULL,"value");
        assert(fs.mounts->tree->count==4 && fs.mounts->tree->mounts[2].generation>generation);
        assert(!fs.mounts->tree->mounts[3].id && !reader->mounts->tree->mounts[3].id);
        if (!i) stable=descriptors(); else assert(descriptors()==stable);
        if (exported) {
            assert(!md_mount_graph_export(fs.mounts,fs.store->lifetime->root,&p) && p.count==13);
            assert(!md_mount_graph_import(&p,&peer.mounts) && descriptors()==stable);
        }
    }
    if (exported) {
        generation=peer.mounts->tree->mounts[2].generation;
        assert(!md_mount_graph_import(&old,&peer.mounts));
        assert(peer.mounts->tree->mounts[2].generation==generation && peer.mounts->tree->count==4);
        int lease=-1;
        assert(!md_mount_graph_exclude(fs.mounts,&fs.mounts->tree->mounts[2],&lease) && lease>=0);
        int result=md_mount_graph_acquire_pin(peer.mounts,&peer.mounts->tree->mounts[2]);
        assert(result==-EAGAIN || result==-EACCES); close(lease);
        /* A bare old snapshot has no origin lease. Attempting to materialize
         * its cached mount after reuse must fail, not adopt the new mount. */
        struct md_filesystem stale={.store=fs.store};
        assert(!md_mount_graph_export(fs.mounts,fs.store->lifetime->root,&p));
        assert(!md_mount_graph_import(&p,&stale.mounts));
        struct md_view_mount *m=&stale.mounts->tree->mounts[2];
        int fd=fcntl(m->source,F_DUPFD_CLOEXEC,3); assert(fd>=0);
        assert(!mutation(&fs,MD_FS_MOUNT_UMOUNT,0,NULL,"/alias"));
        assert(!mutation(&fs,MD_FS_MOUNT_BIND,MS_BIND,"/outer","/alias"));
        errno=0;
        origin=md_view_anchor_new(md_inode_store_lifetime(fs.store),stale.mounts,m,fd,
            m->native.st_dev,m->native.st_ino);
        assert(!origin && errno==ESTALE); close(fd);
        contents(&stale,"/alias/parent",NULL,"native");
        md_fs_mounts_close(&stale);
        md_fs_mounts_close(&peer);
    }
    close_filesystem(&fs); assert(descriptors()==before);
    printf("PASS mount retirement (%s): live old FD, 64 slot generations, sparse parcels and exact FD balance\n",
        exported ? "published" : "local");
}
int test_mount_graph_child(int argc, char **argv) {
    if (argc!=4 || (strcmp(argv[1],"--graph-reader") && strcmp(argv[1],"--graph-writer")
            && strcmp(argv[1],"--host-view-reader"))) return -1;
    alarm(30); int fd=atoi(argv[2]); assert(!fcntl(fd,F_SETFD,FD_CLOEXEC));
    unsigned before=descriptors();
    if (!strcmp(argv[1],"--host-view-reader")) {
        struct md_filesystem fs=filesystem(argv[3],0);
        struct md_mount_graph_parcel p=receive_graph(fd);
        assert(!md_mount_graph_import(&p,&fs.mounts));
        for (unsigned pass=0;pass<2;++pass) {
            contents(&fs,"/devices/shm/sentinel",NULL,"shared");
            struct md_fs_result cpu=open_file(&fs,"/sys/devices/system/cpu/possible",O_RDONLY,NULL,NULL);
            assert(!cpu.error && cpu.fd>=0); char bytes[64]; assert(read(cpu.fd,bytes,sizeof(bytes))>0); close(cpu.fd);
            assert(open_file(&fs,"/sys/devices/system/cpu/possible",O_WRONLY,NULL,NULL).error==-EROFS);
            assert(open_file(&fs,"/sys/devices/platform",O_PATH,NULL,NULL).error==-ENOENT);
            struct md_view_mount *sys=NULL;
            for (unsigned i=0;fs.mounts->tree && i<fs.mounts->tree->count;++i) {
                struct md_view_mount *m=&fs.mounts->tree->mounts[i];
                if (m->provider && md_host_fs_kind(m->provider)==MD_HOST_FS_SYS) sys=m;
            }
            assert(sys);
            assert(!!md_provider_tree_super_flags(sys->provider)==!!pass);
            assert(md_mount_policy_set(md_provider_tree_super_policy(sys->provider),0)==-EPERM);
            if (!pass) { send_byte(fd,'r'); assert(receive_byte(fd)=='x'); }
        }
        close_filesystem(&fs);
    } else if (!strcmp(argv[1],"--graph-writer")) {
        struct md_filesystem fs=filesystem(argv[3],1); struct md_mount_graph_parcel p={0};
        assert(!md_mount_graph_export(fs.mounts,fs.store->lifetime->root,&p)); send_graph(fd,&p); close_filesystem(&fs);
    } else {
        struct md_filesystem fs=filesystem(argv[3],0);
        struct md_mount_graph_parcel p=receive_graph(fd);
        assert(!md_mount_graph_import(&p,&fs.mounts)); checks(&fs,1); send_byte(fd,'r');
        assert(receive_byte(fd)=='u'); checks(&fs,0);
        struct md_fs_anchor *origin=NULL;
        struct md_fs_result opened=open_file(&fs,"/outer/nested",O_PATH|O_DIRECTORY,NULL,&origin);
        assert(!opened.error); close(opened.fd);
        send_byte(fd,'r');
        assert(receive_byte(fd)=='m'); contents(&fs,"/outer/value",NULL,"value");
        contents(&fs,"../parent",origin,"guest"); send_byte(fd,'r');
        assert(receive_byte(fd)=='d'); contents(&fs,"/outer/parent",NULL,"native");
        contents(&fs,"value",origin,"value"); send_byte(fd,'r');
        assert(receive_byte(fd)=='a'); contents(&fs,"/outer/nested/value",NULL,"value"); send_byte(fd,'r');
        assert(receive_byte(fd)=='x'); contents(&fs,"/outer/nested/value",NULL,"value");
        contents(&fs,"value",origin,"value"); md_fs_anchor_release(origin); close_filesystem(&fs);
    }
    assert(descriptors()==before); close(fd); return 0;
}
static void host_projections(const char *base) {
    unsigned before=descriptors(); char directory[PATH_MAX];
    path(directory,base,"host-views"); assert(!mkdir(directory,0700));
    struct md_filesystem fs=filesystem(directory,1);
    struct md_fs_storage_cache storage={0}; fs.storage=&storage;
    assert(!md_inode_runtime_prepare(fs.store));
    assert(!md_inode_mkdir(fs.store,-1,"/devices",0755));
    int sentinel=md_inode_open(fs.store,-1,"/dev/shm/sentinel",O_CREAT|O_WRONLY,0600);
    assert(sentinel>=0 && write(sentinel,"shared",6)==6); close(sentinel);
    struct md_provider_tree *sysfs=NULL; assert(!md_fs_sysfs_prepare(&fs,&sysfs));
    struct md_identity identity=md_identity_new(0,0); fs.store->identity=&identity;
    struct md_fs_request q={.operation=MD_FS_MOUNT,.mode=MD_FS_MOUNT_BIND,.flags=MS_BIND|MS_REC,
        .directory={-1,-1},.path={"/devices","/dev"}};
    struct md_fs_result result; md_fs_execute(&fs,&q,&result,NULL); assert(!result.error);
    fs.store->identity=NULL; md_identity_release(&identity);
    int pair[2]; channel(pair);
    pid_t child=start("--host-view-reader",pair[1],directory); close(pair[1]);
    struct md_mount_graph_parcel parcel={0};
    assert(!md_mount_graph_export(fs.mounts,fs.store->lifetime->root,&parcel)); send_graph(pair[0],&parcel);
    assert(receive_byte(pair[0])=='r');
    assert(!md_mount_policy_set(md_provider_tree_super_policy(sysfs),MD_MOUNT_READONLY));
    md_fs_storage_close(&storage); close_filesystem(&fs); md_provider_tree_release(sysfs);
    send_byte(pair[0],'x'); reap(child); close(pair[0]);
    assert(descriptors()==before);
    puts("PASS host projection exec peer: sysfs policy, dev/shm data, publisher teardown and exact FD balance");
}
static void processes(const char *base) {
    unsigned before=descriptors(); char path1[PATH_MAX], path2[PATH_MAX];
    path(path1,base,"live"); path(path2,base,"exited");
    assert(!mkdir(path1,0700) && !mkdir(path2,0700));
    struct md_filesystem fs=filesystem(path1,1); int pair[2]; channel(pair);
    pid_t child=start("--graph-reader",pair[1],path1); close(pair[1]);
    struct md_mount_graph_parcel p={0}; assert(!md_mount_graph_export(fs.mounts,fs.store->lifetime->root,&p));
    /* Replace the old path before import. Neither source nor covered dentries
     * may be rediscovered by a pathname in the receiving process. */
    char old[PATH_MAX], moved[PATH_MAX], wrong[PATH_MAX];
    path(old,path1,"host0"); path(moved,path1,"moved");
    assert(!rename(old,moved) && !mkdir(old,0700)); path(wrong,old,"parent"); file(wrong,"wrong");
    send_graph(pair[0],&p); assert(receive_byte(pair[0])=='r');
    assert(!md_mount_policy_set(&fs.mounts->tree->mounts[1].policy,0));
    send_byte(pair[0],'u'); assert(receive_byte(pair[0])=='r');
    assert(mutation(&fs,MD_FS_MOUNT_UMOUNT,0,NULL,"/outer/nested")==-EBUSY);
    live_move(&fs); send_byte(pair[0],'m'); assert(receive_byte(pair[0])=='r');
    live_detach(&fs); send_byte(pair[0],'d'); assert(receive_byte(pair[0])=='r');
    live_add(&fs); send_byte(pair[0],'a'); assert(receive_byte(pair[0])=='r');
    close_filesystem(&fs); send_byte(pair[0],'x'); reap(child); close(pair[0]);
    channel(pair); child=start("--graph-writer",pair[1],path2); close(pair[1]);
    reap(child); p=receive_graph(pair[0]); close(pair[0]);
    fs=filesystem(path2,0); assert(!md_mount_graph_import(&p,&fs.mounts));
    checks(&fs,1); close_filesystem(&fs); assert(descriptors()==before);
    puts("PASS mount graph exec peers: real rights, replaced source path, live policy, publisher teardown/exit and exact FD balance");
}
static void initial_pivot(const char *base) {
    unsigned before=descriptors();
    char fixture[PATH_MAX], old[PATH_MAX]; path(fixture,base,"initial-pivot");
    assert(!mkdir(fixture,0700));
    struct md_filesystem fs=filesystem(fixture,1), peer=filesystem(fixture,0);
    path(old,fixture,"host0/old"); assert(!mkdir(old,0700));
    struct md_mount_graph_parcel p={0}, prior={0};
    assert(!md_mount_graph_export(fs.mounts,fs.store->lifetime->root,&p));
    assert(!md_mount_graph_import(&p,&peer.mounts));
    assert(!md_mount_graph_export(fs.mounts,fs.store->lifetime->root,&prior));
    struct md_fs_anchor *held=NULL;
    struct md_fs_result opened=open_file(&peer,"/",O_PATH|O_DIRECTORY,NULL,&held);
    assert(!opened.error && held->mount==&peer.mounts->base); close(opened.fd);
    opened=open_file(&fs,"/",O_PATH|O_DIRECTORY,NULL,NULL); assert(!opened.error);
    struct md_view_object *cached;
    assert(!md_view_record(&fs,opened.fd,&fs.mounts->base,&cached));
    struct md_fs_location initial;
    assert(!md_fs_root_capture(&fs,opened.fd,NULL,&initial) && !initial.mount && !initial.object[0]);
    close(opened.fd);
    struct md_guest_domain *domain=md_domain_new(); assert(domain);
    md_domain_bind_mounts(domain,fs.mounts);
    struct md_identity identity=md_identity_new(0,0); fs.store->identity=&identity;
    struct md_fs_request q={.operation=MD_FS_MOUNT,.mode=MD_FS_MOUNT_PIVOT,
        .directory={-1,-1},.path={"/outer","/outer/old"}};
    int complete=0;
    for (int n=0;n<24 && !complete;++n) {
        fail_allocation=n;
        int r=md_fs_mount_change(&fs,domain,&q);
        fail_allocation=-1;
        if (!r) { complete=1; break; }
        assert(r==-ENOMEM && fs.mounts->root==&fs.mounts->base && fs.mounts->base.source==-1);
        contents(&peer,"/parent",NULL,"guest");
    }
    assert(complete);
    fs.store->identity=NULL; md_identity_release(&identity);
    assert(fs.mounts->root==&fs.mounts->tree->mounts[0]);
    int table=md_fs_mount_table(&fs,1,0); assert(table>=0);
    FILE *mountinfo=fdopen(table,"r"); assert(mountinfo);
    char line[PATH_MAX+256], target[PATH_MAX]; unsigned root_entries=0, base_entries=0, id, parent;
    while (fgets(line,sizeof(line),mountinfo)) {
        assert(sscanf(line,"%u %u %*s %*s %4095s",&id,&parent,target)==3);
        if (!strcmp(target,"/")) {
            assert(id==fs.mounts->root->id && parent==id); ++root_entries;
        } else if (!strcmp(target,"/old")) {
            assert(id==fs.mounts->base.id && parent==fs.mounts->root->id); ++base_entries;
        }
    }
    assert(!ferror(mountinfo) && root_entries==1 && base_entries==1); fclose(mountinfo);
    contents(&peer,"/parent",NULL,"native");
    contents(&peer,"/old/parent",NULL,"guest");
    contents(&peer,"parent",held,"guest");
    assert(!md_mount_graph_import(&prior,&peer.mounts));
    contents(&peer,"/parent",NULL,"native");
    struct md_filesystem copy={.store=fs.store}; struct md_fs_location copied;
    assert(!md_fs_mounts_copy(&fs,&(struct md_fs_location){0},&copy.mounts,&copied));
    contents(&copy,"/old/parent",NULL,"guest"); contents(&copy,"/parent",NULL,"native");
    assert(!md_mount_graph_export(copy.mounts,copy.store->lifetime->root,&p));
    struct md_filesystem forwarded={.store=fs.store};
    assert(!md_mount_graph_import(&p,&forwarded.mounts));
    md_fs_mounts_close(&copy);
    contents(&forwarded,"/old/parent",NULL,"guest"); md_fs_mounts_close(&forwarded);
    path(old,fixture,"host0/clone"); assert(!mkdir(old,0700));
    complete=0;
    for (int n=0;n<32 && !complete;++n) {
        fail_allocation=n;
        int r=mutation(&fs,MD_FS_MOUNT_BIND,MS_BIND|MS_REC,"/","/clone");
        fail_allocation=-1;
        if (!r) { complete=1; break; }
        assert(r==-ENOMEM && fs.mounts->tree->count==3);
        opened=open_file(&peer,"/clone/parent",O_RDONLY,NULL,NULL);
        assert(opened.error==-ENOENT);
    }
    assert(complete);
    contents(&peer,"/clone/parent",NULL,"native");
    contents(&peer,"/clone/old/parent",NULL,"guest");
    contents(&peer,"/clone/old/alias/value",NULL,"value");
    contents(&peer,"/clone/nested/value",NULL,"value");
    assert(mutation(&fs,MD_FS_MOUNT_UMOUNT,0,NULL,"/clone")==-EBUSY);
    assert(!mutation(&fs,MD_FS_MOUNT_UMOUNT,MNT_DETACH,NULL,"/clone"));
    path(old,fixture,"host0/holder"); assert(!mkdir(old,0700));
    path(old,fixture,"host0/holder/old"); assert(!mkdir(old,0700));
    assert(!mutation(&fs,MD_FS_MOUNT_BIND,MS_BIND,"/holder","/holder"));
    assert(!mutation(&fs,MD_FS_MOUNT_PROPAGATION,MS_SHARED,NULL,"/holder"));
    struct md_filesystem shared={.store=fs.store};
    assert(!md_fs_mounts_copy(&fs,&(struct md_fs_location){0},&shared.mounts,&copied));
    complete=0;
    for (int n=0;n<160 && !complete;++n) {
        fail_allocation=n;
        int r=mutation(&fs,MD_FS_MOUNT_MOVE,MS_MOVE,"/old","/holder/old");
        fail_allocation=-1;
        if (!r) { complete=1; break; }
        assert(r==-ENOMEM);
        contents(&peer,"/old/parent",NULL,"guest");
        opened=open_file(&shared,"/holder/old/parent",O_RDONLY,NULL,NULL);
        assert(opened.error==-ENOENT);
    }
    assert(complete);
    contents(&peer,"/holder/old/parent",NULL,"guest");
    contents(&shared,"/holder/old/parent",NULL,"guest");
    contents(&shared,"/old/parent",NULL,"guest");
    assert(mutation(&fs,MD_FS_MOUNT_UMOUNT,0,NULL,"/holder")==-EBUSY);
    assert(mutation(&fs,MD_FS_MOUNT_MOVE,MS_MOVE,"/holder/old","/old")==-EINVAL);
    assert(!mutation(&fs,MD_FS_MOUNT_PROPAGATION,MS_PRIVATE,NULL,"/holder"));
    md_fs_mounts_close(&shared);
    assert(!mutation(&fs,MD_FS_MOUNT_MOVE,MS_MOVE,"/holder/old","/old"));
    assert(!mutation(&fs,MD_FS_MOUNT_UMOUNT,0,NULL,"/holder"));
    assert(mutation(&fs,MD_FS_MOUNT_UMOUNT,0,NULL,"/old")==-EBUSY);
    assert(!mutation(&fs,MD_FS_MOUNT_UMOUNT,MNT_DETACH,NULL,"/old"));
    contents(&peer,"parent",held,"guest");
    opened=open_file(&peer,"/old/parent",O_RDONLY,NULL,NULL);
    assert(opened.error==-ENOENT);
    md_fs_anchor_release(held); md_domain_release(domain);
    close_filesystem(&peer); close_filesystem(&fs);
    assert(descriptors()==before);
    puts("PASS initial-root graph: rollback, live peer, old base FD/pin, copy/forward, recursive bind, move and child-busy detach");
}
static void removed_points(const char *base) {
    unsigned before=descriptors();
    char fixture[PATH_MAX], covered[PATH_MAX], renamed[PATH_MAX], store[PATH_MAX];
    path(fixture,base,"removed-points"); assert(!mkdir(fixture,0700));
    struct md_filesystem fs=filesystem(fixture,1), peer=filesystem(fixture,0);
    struct md_mount_graph_parcel parcel={0};
    assert(!md_mount_graph_export(fs.mounts,fs.store->lifetime->root,&parcel));
    assert(!md_mount_graph_import(&parcel,&peer.mounts));
    struct md_fs_anchor *held=NULL;
    struct md_fs_result opened=open_file(&peer,"/outer/nested",O_PATH|O_DIRECTORY,NULL,&held);
    assert(!opened.error && held); close(opened.fd);
    unsigned retained_id=held->mount->id;
    path(covered,fixture,"host0/nested"); path(renamed,fixture,"host0/moved");
    assert(!rename(covered,renamed));
    assert(!md_view_mount_reconcile(&peer) && !held->mount->detached);
    contents(&peer,"/outer/moved/value",NULL,"value");
    assert(!rmdir(renamed));
    int table=md_fs_mount_table(&peer,1,0); assert(table>=0); close(table);
    assert(held->mount->detached && held->mount->id==retained_id);
    assert(!fs.mounts->tree->mounts[1].detached);
    assert(md_view_mount_detach(&peer,&peer.mounts->tree->mounts[2])==-EPERM);
    contents(&peer,"value",held,"value");
    assert(!md_view_mount_reconcile(&fs) && fs.mounts->tree->mounts[1].detached);
    assert(!md_mount_graph_sync(peer.mounts));
    assert(held->mount->detached && held->mount->id==retained_id);
    assert(!mkdir(renamed,0700));
    opened=open_file(&peer,"/outer/moved/value",O_RDONLY,NULL,NULL);
    assert(opened.error==-ENOENT);

    /* Another connection removes the covered logical root. Native st_nlink
     * still describes its backing file, not its logical namespace membership. */
    path(store,fixture,"store"); struct md_inode_store *writer=NULL;
    assert(!md_inode_store_open(store,0,&writer));
    assert(!md_inode_unlink(writer,-1,"/outer",AT_REMOVEDIR));
    md_inode_store_close(writer);
    assert(!md_view_mount_reconcile(&peer));
    assert(peer.mounts->tree->mounts[0].detached && held->mount->detached);
    assert(!peer.mounts->tree->mounts[2].detached);
    contents(&peer,"/alias/value",NULL,"value");
    contents(&peer,"value",held,"value");
    assert(!md_inode_mkdir(fs.store,-1,"/outer",0700));
    assert(!md_view_mount_reconcile(&fs) && !md_mount_graph_sync(peer.mounts));
    assert(held->mount->detached && held->mount->id==retained_id);
    md_fs_anchor_release(held);
    close_filesystem(&peer); close_filesystem(&fs);
    assert(descriptors()==before);
    puts("PASS removed mountpoints: native/logical targets, rename, foreign views, retained origins and cleanup");
}
static void branch_reconciliation(const char *base) {
    unsigned before=descriptors();
    char fixture[PATH_MAX],covered[PATH_MAX],renamed[PATH_MAX];
    path(fixture,base,"branch-reconcile"); assert(!mkdir(fixture,0700));
    struct md_filesystem fs=filesystem(fixture,1),peer=filesystem(fixture,0);
    struct md_mount_graph_parcel parcel={0};
    assert(!md_mount_graph_export(fs.mounts,fs.store->lifetime->root,&parcel));
    assert(!md_mount_graph_import(&parcel,&peer.mounts));
    struct md_fs_anchor *held=NULL;
    struct md_fs_result opened=open_file(&peer,"/outer/nested",O_PATH|O_DIRECTORY,NULL,&held);
    assert(!opened.error && held); close(opened.fd);
    path(covered,fixture,"host0/nested"); path(renamed,fixture,"host0/moved");
    assert(!rename(covered,renamed));
    assert(!md_view_mount_reconcile_branch(&peer,held->mount) && !held->mount->detached);
    contents(&peer,"/outer/moved/value",NULL,"value");
    assert(!rmdir(renamed));
    /* A sibling operation does not visit unrelated placement targets. */
    assert(!mutation(&fs,MD_FS_MOUNT_REMOUNT,MS_REMOUNT|MS_BIND,NULL,"/alias"));
    assert(!fs.mounts->tree->mounts[1].detached);
    assert(!md_view_mount_reconcile_branch(&peer,held->mount));
    assert(held->mount->detached && !peer.mounts->tree->mounts[2].detached);
    contents(&peer,"value",held,"value");
    /* A recursive source in another view must not turn target admission into
     * a full reconciliation of unrelated target-view placements. */
    assert(!md_inode_mkdir(fs.store,-1,"/copy",0700));
    struct md_fs_anchor *source=NULL;
    struct md_fs_result input=open_file(&peer,"/alias",O_PATH|O_DIRECTORY,NULL,&source);
    assert(!input.error && source);
    struct md_fs_request bind={.operation=MD_FS_MOUNT,.mode=MD_FS_MOUNT_BIND,
        .flags=MS_BIND|MS_REC,.directory={-1,input.fd},.origin={NULL,source},.path={"/copy","."}};
    struct md_fs_result result;
    struct md_identity identity=md_identity_new(0,0); fs.store->identity=&identity;
    md_fs_execute(&fs,&bind,&result,NULL);
    fs.store->identity=NULL; md_identity_release(&identity);
    if (result.error || fs.mounts->tree->mounts[1].detached)
        fprintf(stderr,"recursive bind target: error=%d unrelatedDetached=%d\n",
            result.error,fs.mounts->tree->mounts[1].detached);
    assert(!result.error && !fs.mounts->tree->mounts[1].detached);
    close(input.fd); md_fs_anchor_release(source);
    contents(&fs,"/copy/value",NULL,"value");
    /* Unmount still reconciles children before its EBUSY decision. */
    assert(!mutation(&fs,MD_FS_MOUNT_UMOUNT,0,NULL,"/outer"));
    assert(!md_mount_graph_sync(peer.mounts));
    contents(&peer,"value",held,"value");
    md_fs_anchor_release(held);
    assert(!md_inode_unlink(fs.store,-1,"/alias",AT_REMOVEDIR));
    assert(!md_view_mount_reconcile_branch(&peer,&peer.mounts->tree->mounts[2]));
    assert(peer.mounts->tree->mounts[2].detached);
    assert(!md_view_mount_collect(&fs));
    close_filesystem(&peer); close_filesystem(&fs);
    assert(descriptors()==before);
    puts("PASS branch reconciliation: live rename, unrelated sibling, recursive bind target, foreign deletion, retained origin and child retirement");
}
static void unlinked_propagation(const char *base) {
    unsigned before=descriptors();
    char fixture[PATH_MAX]; path(fixture,base,"unlinked-propagation"); assert(!mkdir(fixture,0700));
    struct md_filesystem fs=filesystem(fixture,1);
    assert(!md_inode_mkdir(fs.store,-1,"/source",0700));
    assert(!md_inode_mkdir(fs.store,-1,"/copy",0700));
    assert(!md_inode_mkdir(fs.store,-1,"/fresh",0700));
    assert(!mutation(&fs,MD_FS_MOUNT_PROPAGATION,MS_SHARED,NULL,"/"));
    assert(!mutation(&fs,MD_FS_MOUNT_BIND,MS_BIND,"/source","/copy"));
    struct md_fs_anchor *held=NULL;
    struct md_fs_result opened=open_file(&fs,"/copy",O_PATH|O_DIRECTORY,NULL,&held);
    assert(!opened.error && held); close(opened.fd);
    int child=md_inode_open(fs.store,-1,"/source/file",O_CREAT|O_RDWR,0600); assert(child>=0);
    int fresh=md_inode_open(fs.store,-1,"/fresh",O_PATH|O_DIRECTORY,0); assert(fresh>=0);
    assert(md_inode_contains(fs.store,held->fd,child)==1);
    assert(md_inode_contains(fs.store,child,child)==1);
    assert(md_inode_contains(fs.store,child,held->fd)==0);
    assert(md_inode_contains(fs.store,fresh,child)==0);
    assert(!md_inode_unlink(fs.store,-1,"/source/file",0)); close(child);
    assert(!md_inode_unlink(fs.store,-1,"/source",AT_REMOVEDIR));
    assert(md_inode_contains(fs.store,held->fd,held->fd)==1);
    assert(md_inode_contains(fs.store,held->fd,fresh)==0); close(fresh);
    int result=mutation(&fs,MD_FS_MOUNT_BIND,MS_BIND,"/fresh","/fresh");
    if (result) fprintf(stderr,"unlinked propagation: %d\n",result);
    assert(!result && !held->mount->detached);
    md_fs_anchor_release(held); close_filesystem(&fs);
    assert(descriptors()==before);
    puts("PASS shared propagation: unlinked bind source does not reject unrelated mounts");
}
static void stream_value(int reader,uint64_t expected,int value) {
    uint64_t revision=0; int *rights=NULL,settled=0; unsigned count=0;
    assert(!md_mount_stream_read(reader,&revision,&rights,&count,&settled)
        && settled && revision==expected && count==1);
    int actual=-1; assert(pread(rights[0],&actual,sizeof(actual),0)==sizeof(actual) && actual==value);
    close(rights[0]); free(rights);
}
static void stream_transactions(void) {
    unsigned before=descriptors();
    int values[2],readers[2],writers[2];
    for (int i=0;i<2;++i) {
        values[i]=memfd_create("mount-value",MFD_CLOEXEC); assert(values[i]>=0);
        assert(write(values[i],&i,sizeof(i))==sizeof(i));
        assert(!md_mount_stream_new(readers+i,writers+i));
        assert(!md_mount_stream_publish(readers[i],writers[i],0,values,1));
    }
    struct md_mount_stream_change changes[2];
    for (unsigned i=0;i<2;++i) changes[i]=(struct md_mount_stream_change){readers[i],writers[i],1,values+1,1};
    assert(!md_mount_stream_publish_many(changes,2));
    for (unsigned i=0;i<2;++i) {
        stream_value(readers[i],2,1); changes[i].previous=2; changes[i].rights=values;
    }
    /* A later writer cannot enqueue. The already staged first stream must
     * still select its old snapshot, and remain usable for the next commit. */
    int saved=writers[1];
    changes[1].writer=readers[1];
    /* A read endpoint is a valid socket, but its peer's receive direction is
     * shut down; preparation succeeds and the second send fails with EPIPE. */
    assert(!shutdown(writers[1],SHUT_RD));
    assert(md_mount_stream_publish_many(changes,2)==-EPIPE);
    for (unsigned i=0;i<2;++i) stream_value(readers[i],2,1);
    changes[1].writer=saved;
    assert(!md_mount_stream_publish_many(changes,2));
    for (unsigned i=0;i<2;++i) {
        stream_value(readers[i],3,0);
        close(readers[i]); close(writers[i]); close(values[i]);
    }
    assert(descriptors()==before);
    puts("PASS mount stream transaction: two-view commit, late staging failure, retry and exact cleanup");
}
static void propagation_stack(const char *base) {
    unsigned before=descriptors();
    char dir[PATH_MAX],name[PATH_MAX]; path(dir,base,"propagation-stack"); assert(!mkdir(dir,0700));
    struct md_filesystem fs=filesystem(dir,1),peer=filesystem(dir,0);
    path(name,dir,"host0/fresh"); assert(!mkdir(name,0700));
    assert(!md_inode_mkdir(fs.store,-1,"/source",0700));
    int fd=md_inode_open(fs.store,-1,"/source/value",O_CREAT|O_WRONLY,0600); assert(fd>=0);
    assert(write(fd,"under",5)==5); close(fd);
    assert(!mutation(&fs,MD_FS_MOUNT_PROPAGATION,MS_SHARED,NULL,"/outer"));
    struct md_fs_location root={0},selected;
    assert(!md_fs_mounts_copy(&fs,&root,&peer.mounts,&selected));
    assert(!mutation(&peer,MD_FS_MOUNT_PROPAGATION,MS_SLAVE,NULL,"/outer"));
    assert(!mutation(&peer,MD_FS_MOUNT_BIND,MS_BIND,"/alias","/outer/fresh"));
    assert(!mutation(&fs,MD_FS_MOUNT_BIND,MS_BIND,"/source","/outer/fresh"));
    contents(&fs,"/outer/fresh/value",NULL,"under");
    contents(&peer,"/outer/fresh/value",NULL,"value");
    assert(!mutation(&peer,MD_FS_MOUNT_UMOUNT,0,NULL,"/outer/fresh"));
    contents(&peer,"/outer/fresh/value",NULL,"under");
    assert(!mutation(&fs,MD_FS_MOUNT_UMOUNT,0,NULL,"/outer/fresh"));
    struct md_fs_result absent=open_file(&peer,"/outer/fresh/value",O_RDONLY,NULL,NULL);
    assert(absent.error==-ENOENT);
    /* Detach propagates through the parent even after the new mount itself
     * has become private; its slave copies must not accumulate. */
    for (unsigned i=0;i<16;++i) {
        assert(!mutation(&fs,MD_FS_MOUNT_BIND,MS_BIND,"/source","/outer/fresh"));
        assert(!mutation(&fs,MD_FS_MOUNT_PROPAGATION,MS_PRIVATE,NULL,"/outer/fresh"));
        contents(&peer,"/outer/fresh/value",NULL,"under");
        assert(!mutation(&fs,MD_FS_MOUNT_UMOUNT,0,NULL,"/outer/fresh"));
        absent=open_file(&peer,"/outer/fresh/value",O_RDONLY,NULL,NULL);
        assert(absent.error==-ENOENT);
    }
    close_filesystem(&peer); close_filesystem(&fs);
    assert(descriptors()==before);
    puts("PASS propagated mount inserts below a private overmount and preserves ordered detach");
}
static void propagation(const char *base) {
    unsigned before=descriptors();
    char dir[PATH_MAX],name[PATH_MAX]; path(dir,base,"propagation"); assert(!mkdir(dir,0700));
    struct md_filesystem fs=filesystem(dir,1),peer=filesystem(dir,0),slave=filesystem(dir,0),foreign=filesystem(dir,0);
    path(name,dir,"host0/fresh"); assert(!mkdir(name,0700));
    path(name,dir,"host0/local"); assert(!mkdir(name,0700));
    assert(!mutation(&fs,MD_FS_MOUNT_PROPAGATION,MS_SHARED,NULL,"/outer"));
    struct md_fs_location root={0},selected;
    assert(!md_fs_mounts_copy(&fs,&root,&peer.mounts,&selected));
    assert(!md_fs_mounts_copy(&fs,&root,&slave.mounts,&selected));
    assert(!mutation(&slave,MD_FS_MOUNT_PROPAGATION,MS_SLAVE,NULL,"/outer"));
    assert(!mutation(&slave,MD_FS_MOUNT_PROPAGATION,MS_SHARED,NULL,"/outer"));
    struct md_view_mount *a=&fs.mounts->tree->mounts[0],*b=&peer.mounts->tree->mounts[0],*c=&slave.mounts->tree->mounts[0];
    assert(a->links.shared && a->links.shared==b->links.shared && c->links.shared!=a->links.shared
        && c->links.master==a->links.shared);
    struct md_mount_graph_parcel parcel={0};
    assert(!md_mount_graph_export(fs.mounts,fs.store->lifetime->root,&parcel));
    assert(!md_mount_graph_import(&parcel,&foreign.mounts));
    assert(md_mount_group_id(foreign.mounts->tree->mounts[0].links.shared)==md_mount_group_id(a->links.shared));
    assert(!md_mount_graph_export(peer.mounts,peer.store->lifetime->root,&parcel)); md_mount_graph_parcel_close(&parcel);
    assert(!md_mount_graph_export(slave.mounts,slave.store->lifetime->root,&parcel)); md_mount_graph_parcel_close(&parcel);
    struct md_identity identity=md_identity_new(0,0); fs.store->identity=&identity;
    struct md_fs_request q={.operation=MD_FS_MOUNT,.mode=MD_FS_MOUNT_BIND,.flags=MS_BIND,
        .directory={-1,-1},.path={"/outer/fresh","/alias"}};
    int complete=0;
    for (int n=0;n<160 && !complete;++n) {
        fail_allocation=n; int r=md_fs_mount_change(&fs,NULL,&q); fail_allocation=-1;
        if (!r) { complete=1; break; }
        if (r!=-ENOMEM) fprintf(stderr,"propagation allocation %d: %d\n",n,r);
        assert(r==-ENOMEM);
        struct md_filesystem *views[]={&fs,&peer,&slave,&foreign};
        for (unsigned i=0;i<4;++i) {
            struct md_fs_result out=open_file(views[i],"/outer/fresh/value",O_RDONLY,NULL,NULL);
            assert(out.error==-ENOENT && out.fd<0);
        }
    }
    fs.store->identity=NULL; md_identity_release(&identity); assert(complete);
    contents(&fs,"/outer/fresh/value",NULL,"value"); contents(&peer,"/outer/fresh/value",NULL,"value");
    contents(&slave,"/outer/fresh/value",NULL,"value"); contents(&foreign,"/outer/fresh/value",NULL,"value");
    assert(!mutation(&slave,MD_FS_MOUNT_BIND,MS_BIND,"/alias","/outer/local"));
    contents(&slave,"/outer/local/value",NULL,"value");
    struct md_fs_result absent=open_file(&fs,"/outer/local/value",O_RDONLY,NULL,NULL); assert(absent.error==-ENOENT);
    assert(!mutation(&peer,MD_FS_MOUNT_UMOUNT,0,NULL,"/outer/fresh"));
    struct md_filesystem *views[]={&fs,&peer,&slave,&foreign};
    for (unsigned i=0;i<4;++i) {
        absent=open_file(views[i],"/outer/fresh/value",O_RDONLY,NULL,NULL); assert(absent.error==-ENOENT);
    }
    assert(!mutation(&peer,MD_FS_MOUNT_PROPAGATION,MS_PRIVATE,NULL,"/outer"));
    assert(!mutation(&fs,MD_FS_MOUNT_BIND,MS_BIND,"/alias","/outer/fresh"));
    absent=open_file(&peer,"/outer/fresh/value",O_RDONLY,NULL,NULL); assert(absent.error==-ENOENT);
    contents(&slave,"/outer/fresh/value",NULL,"value");
    int table=md_fs_mount_table(&slave,1,0); assert(table>=0);
    char bytes[32768]; ssize_t n=read(table,bytes,sizeof(bytes)-1); assert(n>0); bytes[n]=0; close(table);
    char master[32]; snprintf(master,sizeof(master),"master:%u",md_mount_group_id(a->links.shared));
    assert(strstr(bytes,"shared:") && strstr(bytes,master));
    assert(!mutation(&fs,MD_FS_MOUNT_UMOUNT,0,NULL,"/outer/fresh"));
    /* A moved instance preserves its old origin while gaining the destination
     * propagation policy; a later lazy detach preserves only that origin. */
    struct md_fs_anchor *held=NULL;
    struct md_fs_result opened=open_file(&fs,"/alias",O_PATH|O_DIRECTORY,NULL,&held);
    assert(!opened.error); close(opened.fd); unsigned old_id=held->mount->id;
    assert(!mutation(&fs,MD_FS_MOUNT_MOVE,MS_MOVE,"/alias","/outer/fresh"));
    assert(held->mount->id==old_id);
    contents(&slave,"/outer/fresh/value",NULL,"value");
    assert(mutation(&fs,MD_FS_MOUNT_MOVE,MS_MOVE,"/outer/fresh","/alias")==-EINVAL);
    assert(mutation(&fs,MD_FS_MOUNT_UMOUNT,0,NULL,"/outer/fresh")==-EBUSY);
    assert(!mutation(&fs,MD_FS_MOUNT_UMOUNT,MNT_DETACH,NULL,"/outer/fresh"));
    contents(&fs,"value",held,"value"); md_fs_anchor_release(held);
    absent=open_file(&slave,"/outer/fresh/value",O_RDONLY,NULL,NULL); assert(absent.error==-ENOENT);
    /* Recursive binds prune unbindable children instead of accepting aliases
     * to them. The original mount remains accessible in its namespace. */
    assert(!mutation(&fs,MD_FS_MOUNT_PROPAGATION,MS_UNBINDABLE,NULL,"/outer/nested"));
    assert(mutation(&fs,MD_FS_MOUNT_BIND,MS_BIND,"/outer/nested","/alias")==-EINVAL);
    assert(!mutation(&fs,MD_FS_MOUNT_BIND,MS_BIND|MS_REC,"/outer","/alias"));
    absent=open_file(&fs,"/alias/nested/value",O_RDONLY,NULL,NULL); assert(absent.error==-ENOENT);
    contents(&fs,"/outer/nested/value",NULL,"value");
    close_filesystem(&foreign); close_filesystem(&slave); close_filesystem(&peer); close_filesystem(&fs);
    assert(descriptors()==before);
    puts("PASS mount propagation: peer/slave attach and detach, private isolation, graph publication and allocation rollback");
}
void test_mount_graph(const char *base) {
    stream_transactions(); topology_order(); placement_lookup();
    /* EVENT_WAIT: full-limit graph transfer/copy plus failure injection. The
     * capacity stress case now exercises 4096 real mounts, not 512. */
    alarm(300); char model[PATH_MAX], peers[PATH_MAX];
    path(model,base,"graph-model"); path(peers,base,"graph-peers");
    assert(!mkdir(model,0700) && !mkdir(peers,0700));
    models(model); deep_mount_paths(base); sizes(base); unordered(base); indexed_updates(base); updates(base); private_publication(base);
    retirement(base,0); retirement(base,1); processes(peers); host_projections(base);
    initial_pivot(base); removed_points(base); branch_reconciliation(base); unlinked_propagation(base); propagation_stack(base); propagation(base); alarm(0);
}
