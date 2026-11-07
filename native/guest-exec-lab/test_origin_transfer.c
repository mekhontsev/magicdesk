#define _GNU_SOURCE
#include "fs_origin_transfer.h"
#include "fs_mount_internal.h"
#include "fs_storage.h"
#include "mount_graph.h"
#include "inode_internal.h"
#include "sealed_manifest.h"
#include "fs_wire.h"
#include "event_wait.h"
#include "ipc_capsule.h"
#include "fd_bundle.h"
#include "fd_selector.h"
#include "descriptor_inode.h"
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/mman.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/sysmacros.h>
#include <sys/wait.h>
#include <unistd.h>

void test_mount_allocation_failure_after(int);
/* Manifest, six distinct anchors with pins, root/lower custody and two views. */
enum { TEST_RIGHTS=1+2*6+4+2*(4+3*3) };
struct source {
    struct md_filesystem fs, copy;
    struct md_fs_anchor *anchor[6];
    struct md_fd_message *message;
    struct md_fd_messages *messages;
    struct md_fd_message_route route;
    int carrier[2], writer;
};
static unsigned descriptors(void) {
    DIR *d=opendir("/proc/self/fd"); assert(d); unsigned n=0; struct dirent *e;
    while ((e=readdir(d))) if (e->d_name[0]!='.') ++n;
    assert(!closedir(d)); return n;
}
static void path(char *out, const char *base, const char *name) {
    assert(snprintf(out,PATH_MAX,"%s/%s",base,name)<PATH_MAX);
}
static void file(const char *name, const char *text) {
    int fd=open(name,O_WRONLY|O_CREAT|O_CLOEXEC,0600); assert(fd>=0);
    assert(write(fd,text,strlen(text))==(ssize_t)strlen(text)); close(fd);
}
static struct md_fs_result request(struct md_filesystem *fs, struct md_fs_anchor *a,
        unsigned op, const char *name, unsigned flags, struct md_fs_anchor **out) {
    struct md_fs_request q={.operation=op,.path={name,NULL},.flags=flags,.origin={a,NULL},
        .directory={a ? md_fs_anchor_fd(a) : -1,-1},.mode=op==MD_FS_FSTAT ? MD_FS_STAT_MOUNT : 0};
    struct md_fs_result r; md_fs_execute(fs,&q,&r,&(struct md_fs_output){.origin=out}); return r;
}
static struct md_fs_anchor *anchor(struct md_filesystem *fs, const char *name) {
    struct md_fs_anchor *a=NULL; struct md_fs_result r=request(fs,NULL,MD_FS_OPEN,name,O_PATH,&a);
    assert(!r.error && r.fd>=0 && a); close(r.fd); return a;
}
static void pair(int fd[2]) {
    assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC|SOCK_NONBLOCK,0,fd));
}
static struct md_fd_message *capture_objects(struct md_fd_object *const *objects, unsigned count) {
    struct md_fd_origins *table=md_fd_origins_new(); assert(table);
    int numbers[253]; struct md_fd_origin_update updates[253];
    for (unsigned i=0;i<count;++i) numbers[i]=(int)i+10;
    assert(!md_fd_origins_prepare_batch(table,objects,0,count,updates));
    assert(!md_fd_origins_finish_batch(updates,count,numbers,count));
    struct md_fd_order turn={0};
    assert(md_fd_order_begin(table,&turn,NULL)==1 && !md_fd_order_start(&turn));
    struct md_fd_message *message=NULL;
    assert(!md_fd_message_capture(table,numbers,count,&message));
    assert(!md_fd_order_complete(&turn)); md_fd_origins_release(table);
    return message;
}
static struct md_fd_message *capture(struct md_fs_anchor *const *entries, unsigned count) {
    struct md_fd_object *objects[253];
    for (unsigned i=0;i<count;++i) objects[i]=md_fs_anchor_object(entries[i]);
    return capture_objects(objects,count);
}
static void publish(struct source *s, const char *store, struct md_fs_anchor *const *entries, unsigned count) {
    s->message=capture(entries,count);
    struct md_ipc_credentials *authority=NULL;
    assert(!md_ipc_credentials_open(store,MD_IPC_TRANSPORT_ORIGINS_V1,&authority));
    int app[2]; pair(app); struct md_identity id=md_identity_new(0,0);
    struct md_fs_request q={.operation=MD_FS_IPC,.flags=MD_IPC_PAIR,.directory={app[0],app[1]},.peer=getpid()};
    struct md_fs_result r; md_ipc_credentials_execute(authority,&id,NULL,&q,&r,NULL);
    assert(!r.error); md_identity_release(&id);
    struct md_ipc_binding sender={0}, receiver={0};
    assert(!md_ipc_credentials_bind(authority,app[0],&sender));
    assert(!md_ipc_credentials_bind(authority,app[1],&receiver));
    assert(!md_fd_message_bind(s->message,&sender,"0123456789abcdef0123456789abcdef"));
    int life[2]; assert(!pipe2(life,O_NONBLOCK|O_CLOEXEC)); s->writer=life[1]; pair(s->carrier);
    assert(!md_fd_messages_new(&s->messages));
    assert(!md_fd_messages_publish(s->messages,s->message,&receiver.connection,s->carrier[1],life[0]));
    assert(!md_fd_message_route(s->message,&s->route));
    struct md_fd_message *claim=NULL;
    assert(!md_fd_messages_claim(s->messages,&receiver,s->carrier[1],&s->route,&claim));
    md_fd_message_release(s->message); s->message=claim;
    md_ipc_binding_release(&sender); md_ipc_binding_release(&receiver);
    close(app[0]); close(app[1]); md_ipc_credentials_close(authority);
}
static struct source source(const char *base, unsigned count) {
    struct source s={0}; char lower[PATH_MAX], store[PATH_MAX], host[3][PATH_MAX], name[PATH_MAX];
    path(lower,base,"lower"); path(store,base,"store"); struct md_inode_store *image=NULL;
    assert(!md_inode_store_open(lower,1,&image) && !md_inode_store_open(store,1,&s.fs.store));
    assert(!md_inode_mkdir(image,-1,"/outer",0700) && !md_inode_mkdir(image,-1,"/alias",0700));
    int fd=md_inode_open(image,-1,"/value",O_WRONLY|O_CREAT,0600); assert(fd>=0);
    assert(write(fd,"lower",5)==5); close(fd);
    assert(!md_inode_link(image,-1,"/value",-1,"/value-alias",0));
    assert(!md_inode_store_seal(image) && !md_inode_snapshot(image,s.fs.store)); md_inode_store_close(image);
    for (unsigned i=0;i<3;++i) {
        char leaf[16]; snprintf(leaf,sizeof(leaf),"host%u",i); path(host[i],base,leaf); assert(!mkdir(host[i],0700));
    }
    path(name,host[0],"nested"); assert(!mkdir(name,0700));
    path(name,host[0],"value"); file(name,"outer"); path(name,host[1],"value"); file(name,"inner");
    struct md_fs_attachment attachment[]={{host[0],"/outer",0},{host[1],"/outer/nested",1},{host[2],"/alias",0}};
    assert(!md_fs_mounts_open(&s.fs,attachment,3));
    struct md_view_mount *a=&s.fs.mounts->tree->mounts[1], *b=&s.fs.mounts->tree->mounts[2];
    close(b->source); b->source=fcntl(a->source,F_DUPFD_CLOEXEC,3); assert(b->source>=0); b->native=a->native;
    s.copy.store=s.fs.store; struct md_fs_location root={0}, rebased;
    assert(!md_fs_mounts_copy(&s.fs,&root,&s.copy.mounts,&rebased));
    assert(!md_mount_policy_set(&s.copy.mounts->tree->mounts[0].policy,MD_MOUNT_READONLY));
    const char *names[]={"/","/outer","/alias","/outer/nested","/outer","/value"};
    for (unsigned i=0;i<6;++i) s.anchor[i]=anchor(i==4 ? &s.copy : &s.fs,names[i]);
    struct md_fs_anchor *entries[253];
    for (unsigned i=0;i<count;++i) entries[i]=i%8==7 ? NULL : s.anchor[i%8==6 ? 0 : i%8];
    publish(&s,store,entries,count); return s;
}
static void message_close(struct source *s) {
    md_fd_messages_free(s->messages); md_fd_message_release(s->message);
    close(s->carrier[0]); close(s->carrier[1]); close(s->writer);
}
static void source_close(struct source *s) {
    message_close(s);
    for (unsigned i=0;i<6;++i) md_fs_anchor_release(s->anchor[i]);
    md_fs_mounts_close(&s->copy); md_fs_mounts_close(&s->fs); md_inode_store_close(s->fs.store);
    *s=(struct source){0};
}
static struct md_fs_origin_imports *imports(const char *base) {
    char name[PATH_MAX]; path(name,base,"store"); int fd=open(name,O_PATH|O_DIRECTORY|O_CLOEXEC); assert(fd>=0);
    struct md_fs_origin_imports *p=NULL; assert(!md_fs_origin_imports_new(fd,&p)); close(fd); return p;
}
static void maintenance(const char *base, int blocked) {
    const char *names[]={"store","lower"};
    for (unsigned i=0;i<2;++i) {
        char name[PATH_MAX]; path(name,base,names[i]); int fd=open(name,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(fd>=0);
        int r=flock(fd,LOCK_EX|LOCK_NB);
        if (blocked) assert(r<0 && errno==EWOULDBLOCK); else assert(!r);
        close(fd);
    }
}
static void contents(struct md_filesystem *fs, struct md_fs_anchor *a, const char *name, const char *expected) {
    struct md_fs_result r=request(fs,a,MD_FS_OPEN,name,O_RDONLY,NULL);
    assert(!r.error && r.fd>=0); char bytes[16]={0};
    assert(read(r.fd,bytes,sizeof(bytes))==(ssize_t)strlen(expected) && !strcmp(bytes,expected)); close(r.fd);
}
static void display_path(struct md_filesystem *fs, struct md_fs_anchor *a, const char *expected) {
    struct md_fs_request q={.operation=MD_FS_PATH,.mode=MD_FS_PATH_DISPLAY,
        .directory={md_fs_anchor_fd(a),-1},.origin={a,NULL}};
    struct md_fs_result r; char text[PATH_MAX];
    md_fs_execute(fs,&q,&r,&(struct md_fs_output){.data=text,.capacity=sizeof(text)});
    assert(!r.error && !strcmp(text,expected));
}
static void engine_entries(const char *base, struct md_fs_anchor *const *a, int changed) {
    for (unsigned i=0;i<253;++i) assert(a[i]==a[i%8]);
    assert(a[0]==a[6] && !a[7] && a[1]->view==a[2]->view && a[1]->view!=a[4]->view);
    assert(a[2]->device==a[3]->device && a[2]->inode==a[3]->inode && a[2]->mount!=a[3]->mount);
    char store[PATH_MAX]; path(store,base,"store"); struct md_filesystem fs={0};
    assert(!md_inode_store_open(store,0,&fs.store) && !md_fs_mounts_open(&fs,NULL,0));
    display_path(&fs,a[5],"/value");
    contents(&fs,a[0],"value","lower"); contents(&fs,a[1],"value","outer");
    contents(&fs,a[3],"value","inner"); contents(&fs,a[3],"../value","outer");
    for (unsigned i=1;i<=4;++i) {
        struct md_fs_result r=request(&fs,a[i],MD_FS_OPEN,"value",O_RDWR,NULL);
        int readonly=i>=3 || (i==1 && changed);
        if (readonly) assert(r.error==-EROFS && r.fd<0);
        else { assert(!r.error); close(r.fd); }
    }
    struct md_fs_result r=request(&fs,a[3],MD_FS_FSTAT,NULL,0,NULL);
    assert(!r.error && r.info.mount_id==a[3]->mount->id);
    md_fs_mounts_close(&fs); md_inode_store_close(fs.store);
}
static void engine(const char *base, struct md_fs_origin_vector *v, int changed) {
    assert(md_fs_origin_vector_count(v)==253);
    engine_entries(base,md_fs_origin_vector_entries(v),changed);
}
static void engine_objects(const char *base, struct md_fd_object *const *objects) {
    struct md_fs_anchor *anchors[253];
    for (unsigned i=0;i<253;++i) {
        anchors[i]=md_fs_anchor_from_object(objects[i]);
        assert(!objects[i] || anchors[i]);
    }
    engine_entries(base,anchors,0);
}
static void copy_unlink(const char *base, struct md_fs_anchor *origin) {
    char store[PATH_MAX]; path(store,base,"store"); struct md_filesystem fs={0};
    assert(!md_inode_store_open(store,0,&fs.store) && !md_fs_mounts_open(&fs,NULL,0));
    struct md_fs_result old=request(&fs,origin,MD_FS_REOPEN,NULL,O_RDONLY,NULL);
    assert(!old.error && old.fd>=0);
    int fd=md_inode_open(fs.store,-1,"/value",O_RDWR,0); assert(fd>=0);
    assert(write(fd,"upper",5)==5); close(fd); assert(!md_inode_unlink(fs.store,-1,"/value",0));
    struct md_fs_result current=request(&fs,origin,MD_FS_REOPEN,NULL,O_RDONLY,NULL);
    assert(!current.error && current.fd>=0); char bytes[5];
    assert(read(current.fd,bytes,5)==5 && !memcmp(bytes,"upper",5)); close(current.fd);
    assert(read(old.fd,bytes,5)==5 && !memcmp(bytes,"lower",5)); close(old.fd);
    display_path(&fs,origin,"/value (deleted)");
    md_fs_mounts_close(&fs); md_inode_store_close(fs.store);
}
static struct md_fs_origin_vector *receive(struct source *s, struct md_fs_origin_imports *i) {
    struct md_fs_origin_parcel p={0}; assert(!md_fs_origin_export(s->message,&p) && p.count==TEST_RIGHTS);
    struct md_fs_origin_vector *v=NULL; assert(!md_fs_origin_import(i,&s->route,&p,&v) && !p.count && !p.fd);
    return v;
}
static void local(const char *base) {
    unsigned before=descriptors(); struct source s=source(base,253); struct md_fs_origin_imports *i=imports(base);
    struct md_fs_origin_vector *a=receive(&s,i), *b=receive(&s,i); engine(base,a,0); engine(base,b,0);
    struct md_fs_anchor *const *aa=md_fs_origin_vector_entries(a), *const *bb=md_fs_origin_vector_entries(b);
    assert(aa[1]->view==bb[1]->view && aa[1]->mount==bb[1]->mount);
    assert(!md_mount_policy_set(&s.fs.mounts->tree->mounts[0].policy,MD_MOUNT_READONLY)); engine(base,b,1);
    /* Imported origins use the ordinary table publication, fork and exec paths. */
    struct md_fd_origins *table=md_fd_origins_new(); assert(table); struct md_fd_origin_update updates[253];
    int numbers[253]; for (unsigned n=0;n<253;++n) numbers[n]=1000+(int)n;
    struct md_fd_object *objects[253];
    for (unsigned n=0;n<253;++n) objects[n]=md_fs_anchor_object(aa[n]);
    assert(!md_fd_origins_prepare_batch(table,objects,FD_CLOEXEC,253,updates));
    assert(!md_fd_origins_finish_batch(updates,253,numbers,253));
    struct md_fd_origins *copy=md_fd_origins_fork(table,0); assert(copy);
    md_fd_origins_release(table); md_fd_origins_exec(copy); md_fd_origins_release(copy);
    md_fs_origin_vector_free(a); source_close(&s); maintenance(base,1);
    md_fs_origin_imports_free(i); engine(base,b,1); maintenance(base,1);
    char store[PATH_MAX]; path(store,base,"store"); struct source forward={0};
    publish(&forward,store,bb,253); i=imports(base);
    struct md_fs_origin_vector *c=receive(&forward,i);
    message_close(&forward); md_fs_origin_vector_free(b); engine(base,c,1); maintenance(base,1);
    copy_unlink(base,md_fs_origin_vector_entries(c)[5]);
    md_fs_origin_vector_free(c); maintenance(base,0); md_fs_origin_imports_collect(i);
    md_fs_origin_imports_free(i); assert(descriptors()==before);
    puts("PASS origin transfer: 253 entries, repeated/unknown origins, mixed graphs, live policy, interned IDs and ordinary table publication");
    puts("PASS origin forwarding after sender/SQLite/cache teardown retains graph policy, COW/unlinked identity and backing locks");
}
static void mutate(struct md_fs_origin_parcel *p, unsigned word, uint32_t value, int seals) {
    uint32_t bytes[6000]; ssize_t n=pread(p->fd[0],bytes,sizeof(bytes),0); assert(n>0 && word<(unsigned)n/4);
    bytes[word]=value; int fd=memfd_create("bad-origins",MFD_CLOEXEC|MFD_ALLOW_SEALING); assert(fd>=0);
    assert(write(fd,bytes,(size_t)n)==n && !fcntl(fd,F_ADD_SEALS,seals)); close(p->fd[0]); p->fd[0]=fd;
}
static void failures(const char *base) {
    unsigned before=descriptors(); struct source s=source(base,253); struct md_fs_origin_imports *i=imports(base);
    struct md_fs_origin_vector *good=receive(&s,i); engine(base,good,0); unsigned live=descriptors();
    for (unsigned n=0;n<17;++n) {
        struct md_fs_origin_parcel p={0}; assert(!md_fs_origin_export(s.message,&p));
        struct md_fd_message_route expected=s.route;
        int seals=F_SEAL_WRITE|F_SEAL_SEAL|F_SEAL_SHRINK|F_SEAL_GROW;
        switch (n) {
        case 0: mutate(&p,0,0,seals); break;
        case 1: mutate(&p,1,UINT_MAX,seals); break;
        /* Header (34 words), vector (253), then first origin's new fields. */
        case 14: mutate(&p,34+253+23,UINT_MAX,seals); break;
        case 15: mutate(&p,34+253+24,2,seals); break;
        case 16: mutate(&p,34+253+25,1,seals); break;
        case 2: mutate(&p,2,254,seals); break;
        case 3: mutate(&p,3,254,seals); break;
        case 4: mutate(&p,7,1,seals); break;
        case 5: mutate(&p,34,2,seals); break; /* First vector index must be 1. */
        case 6: mutate(&p,30,0,seals); break; /* Store device identity. */
        case 7: mutate(&p,0,0x4d444f56U,seals&~F_SEAL_WRITE); break;
        case 8: { int fd=p.fd[5]; p.fd[5]=p.fd[15]; p.fd[15]=fd; break; }
        case 9: { int fd=p.fd[25]; p.fd[25]=p.fd[26]; p.fd[26]=fd; break; }
        case 10: close(p.fd[26]); p.fd[26]=p.fd[25]; break;
        case 11: close(p.fd[--p.count]); break;
        case 12: expected.nonce[0]^=1; break;
        case 13: mutate(&p,34+253+2,UINT_MAX,seals); break; /* First origin mount slot. */
        }
        struct md_fs_origin_vector *v=NULL; assert(md_fs_origin_import(i,&expected,&p,&v)<0 && !p.fd && !v);
        assert(descriptors()==live); engine(base,good,0);
    }
    char foreign[PATH_MAX]; path(foreign,base,"foreign"); assert(!mkdir(foreign,0700));
    int scope=open(foreign,O_PATH|O_DIRECTORY|O_CLOEXEC); assert(scope>=0);
    struct md_fs_origin_imports *other=NULL; assert(!md_fs_origin_imports_new(scope,&other)); close(scope);
    struct md_fs_origin_parcel rejected={0}; assert(!md_fs_origin_export(s.message,&rejected));
    struct md_fs_origin_vector *wrong=NULL;
    assert(md_fs_origin_import(other,&s.route,&rejected,&wrong)==-EXDEV && !wrong && !rejected.fd);
    md_fs_origin_imports_free(other); assert(descriptors()==live);
    assert(!md_fs_origin_export(s.message,&rejected)); wrong=good;
    assert(md_fs_origin_import(i,&s.route,&rejected,&wrong)==-EINVAL && wrong==good && !rejected.fd);
    assert(descriptors()==live);
    md_fs_origin_vector_free(good); md_fs_origin_imports_collect(i); live=descriptors();
    int reached=0;
    for (int n=0;n<32;++n) {
        struct md_fs_origin_parcel p={0}; assert(!md_fs_origin_export(s.message,&p));
        struct md_fs_origin_vector *v=NULL; test_mount_allocation_failure_after(n);
        int r=md_fs_origin_import(i,&s.route,&p,&v); test_mount_allocation_failure_after(-1);
        assert(!p.fd);
        if (!r) { assert(v); md_fs_origin_vector_free(v); md_fs_origin_imports_collect(i); reached=1; }
        else assert(r==-ENOMEM && !v);
        assert(descriptors()==live);
        if (reached) break;
    }
    assert(reached);
    /* Export failure halfway through its duplicated rights must not publish. */
    struct rlimit old, limit; assert(!getrlimit(RLIMIT_NOFILE,&old)); limit=old;
    if (limit.rlim_cur>256) limit.rlim_cur=256;
    assert(!setrlimit(RLIMIT_NOFILE,&limit)); int fds[256], fd; unsigned count=0;
    while ((fd=fcntl(md_fs_anchor_fd(s.anchor[0]),F_DUPFD_CLOEXEC,3))>=0) { assert(count<256); fds[count++]=fd; }
    assert(errno==EMFILE && count>=6); for (unsigned n=0;n<6;++n) close(fds[--count]);
    struct md_fs_origin_parcel p={0}; int r=md_fs_origin_export(s.message,&p);
    assert(!setrlimit(RLIMIT_NOFILE,&old)); while (count) close(fds[--count]);
    assert(r==-EMFILE && !p.fd && descriptors()==live);
    md_fs_origin_imports_free(i); source_close(&s); maintenance(base,0); assert(descriptors()==before);
    puts("PASS origin transfer rollback: malformed/seal/route/store/rights rejection, every allocation boundary and partial FD exhaustion");
}
static void ready(int fd, short events) {
    /* EVENT_WAIT: bounded fixture channel progress; expiry fails the test. */
    assert(!md_event_wait_fd(fd,events,md_event_now()+5000000000LL));
}
static void packet(int fd, const void *bytes, size_t n, struct md_fs_rights *rights) {
    long r; while ((r=md_fs_send(fd,bytes,n,rights))==-EAGAIN) ready(fd,POLLOUT);
    assert(r==(long)n);
}
static void send_parcel(int fd, struct md_fs_origin_parcel *p) {
    packet(fd,&p->count,sizeof(p->count),&(struct md_fs_rights){0});
    for (unsigned n=0;n<p->count;) {
        struct md_fs_rights rights={0};
        while (rights.count<MD_FS_MAX_FDS && n<p->count) rights.fd[rights.count++]=p->fd[n++];
        packet(fd,"o",1,&rights);
    }
    md_fs_origin_parcel_close(p);
}
static struct md_fs_origin_parcel receive_parcel(int fd) {
    ready(fd,POLLIN); struct md_fs_rights rights={0}; struct md_fs_origin_parcel p={0};
    assert(md_fs_receive(fd,&p.count,sizeof(p.count),&rights)==sizeof(p.count) && !rights.count
        && p.count>0 && p.count<=MD_FS_ORIGIN_MAX_RIGHTS);
    p.fd=calloc(p.count,sizeof(int)); assert(p.fd);
    for (unsigned n=0;n<p.count;) {
        ready(fd,POLLIN); char value;
        assert(md_fs_receive(fd,&value,1,&rights)==1 && value=='o' && rights.count && rights.count<=p.count-n);
        for (unsigned j=0;j<rights.count;++j) p.fd[n++]=rights.fd[j];
    }
    return p;
}
static void reap(pid_t child, int killed) {
    /* EVENT_WAIT: exact fixture process; outer alarm bounds a stuck child. */
    int status; assert(waitpid(child,&status,0)==child);
    if (killed) assert(WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
    else assert(WIFEXITED(status) && !WEXITSTATUS(status));
}
static void capsule_publish_failures(struct md_ipc_capsules *owner, const struct md_ipc_binding *binding,
        struct md_fs_anchor *const *entries, const int *fds) {
    unsigned before=descriptors(); int reached=0;
    for (int i=0;i<64;++i) {
        struct md_fd_message *message=capture(entries,253); int carrier=-1;
        test_mount_allocation_failure_after(i);
        int error=md_ipc_capsules_publish(owner,binding,"0123456789abcdef0123456789abcdef",
            message,fds,253,&carrier);
        test_mount_allocation_failure_after(-1);
        if (!error) { assert(carrier>=0); close(carrier); reached=1; }
        else assert(error==-ENOMEM && carrier==-1);
        md_fd_message_release(message); assert(descriptors()==before);
        for (unsigned n=0;n<253;++n) assert(fcntl(fds[n],F_GETFD)>=0);
        if (reached) break;
    }
    assert(reached);
    for (unsigned slots=0;slots<=16;slots+=8) {
        struct md_fd_message *message=capture(entries,253); int carrier=-1;
        struct rlimit old, limit; assert(!getrlimit(RLIMIT_NOFILE,&old)); limit=old;
        if (limit.rlim_cur>256) limit.rlim_cur=256;
        assert(!setrlimit(RLIMIT_NOFILE,&limit)); int extra[256], fd; unsigned count=0;
        while ((fd=fcntl(fds[0],F_DUPFD_CLOEXEC,3))>=0) { assert(count<256); extra[count++]=fd; }
        assert(errno==EMFILE && count>=slots);
        for (unsigned n=0;n<slots;++n) close(extra[--count]);
        int error=md_ipc_capsules_publish(owner,binding,"0123456789abcdef0123456789abcdef",
            message,fds,253,&carrier);
        assert(!setrlimit(RLIMIT_NOFILE,&old)); while (count) close(extra[--count]);
        assert(error==-EMFILE && carrier==-1);
        md_fd_message_release(message); assert(descriptors()==before);
    }
    puts("PASS capsule publication: allocation/partial-FD failures retain caller data and release incomplete queues");
}
static void capsule_sender(int channel, const char *base, int unknown) {
    unsigned before=descriptors(); struct source s=source(base,253);
    char store[PATH_MAX]; path(store,base,"store");
    struct md_ipc_credentials *authority=NULL;
    assert(!md_ipc_credentials_open(store,MD_IPC_TRANSPORT_ORIGINS_V1,&authority));
    int app[2]; pair(app); struct md_identity id=md_identity_new(0,0);
    struct md_fs_request q={.operation=MD_FS_IPC,.flags=MD_IPC_PAIR,.directory={app[0],app[1]},.peer=getpid()};
    struct md_fs_result r; md_ipc_credentials_execute(authority,&id,NULL,&q,&r,NULL);
    assert(!r.error); md_identity_release(&id);
    struct md_ipc_binding sender={0}; assert(!md_ipc_credentials_bind(authority,app[0],&sender));
    char pending[PATH_MAX]; path(pending,store,"ipc/auth.pending"); file(pending,"partial");
    struct md_ipc_capsules *owner=NULL; assert(!md_ipc_capsules_new(&sender,&owner));
    assert(access(pending,F_OK)<0 && errno==ENOENT);
    struct md_fs_anchor *entries[253]; int fds[253];
    int lifetime[2]; assert(!pipe2(lifetime,O_NONBLOCK|O_CLOEXEC)); int plain=lifetime[1];
    r=request(&s.fs,s.anchor[5],MD_FS_REOPEN,NULL,O_RDONLY,NULL); assert(!r.error && r.fd>=0);
    for (unsigned i=0;i<253;++i) {
        entries[i]=unknown || i%8==7 ? NULL : s.anchor[i%8==6 ? 0 : i%8];
        fds[i]=!entries[i] ? plain : i%8==5 ? r.fd : md_fs_anchor_fd(entries[i]);
    }
    struct md_fd_message *message=capture(entries,253); int carrier=-1;
    assert(!md_ipc_capsules_publish(owner,&sender,"0123456789abcdef0123456789abcdef",message,fds,253,&carrier));
    if (!unknown) capsule_publish_failures(owner,&sender,entries,fds);
    packet(channel,"C",1,&(struct md_fs_rights){.count=2,.fd={carrier,app[1]}});
    packet(channel,"R",1,&(struct md_fs_rights){.count=1,.fd={lifetime[0]}});
    md_fd_message_release(message); close(carrier); close(plain); close(r.fd);
    md_ipc_capsules_free(owner); md_ipc_binding_release(&sender); md_ipc_credentials_close(authority);
    close(app[0]); close(app[1]); close(lifetime[0]); source_close(&s); assert(descriptors()==before);
}
static void storage_sender(int channel, const char *base) {
    unsigned before=descriptors(); struct source s={0}; struct md_fs_storage_cache cache={0};
    struct md_inode_store *extra=NULL; char store[PATH_MAX], mounted[PATH_MAX];
    path(store,base,"store"); path(mounted,base,"mounted"); s.fs.storage=&cache;
    assert(!md_inode_store_open(store,1,&s.fs.store) && !md_inode_store_open(mounted,1,&extra));
    int fd=md_inode_open(extra,-1,"/value",O_CREAT|O_WRONLY,0644); assert(fd>=0);
    assert(write(fd,"mounted",7)==7); close(fd);
    assert(!md_fs_mounts_open(&s.fs,NULL,0));
    const char *targets[]={"/volume","/readonly"};
    for (unsigned i=0;i<2;++i) {
        assert(!md_inode_mkdir(s.fs.store,-1,targets[i],0755));
        struct md_view_location target;
        assert(!md_view_walk(&s.fs,-1,NULL,targets[i],1,0,0,&target));
        fd=md_inode_open(extra,-1,"/",O_PATH|O_DIRECTORY,0); assert(fd>=0);
        struct md_mount_source source={.fd=fd,.backing=MD_MOUNT_INODE,
            .storage=md_inode_store_lifetime(extra),.policy=i ? MD_MOUNT_READONLY : 0};
        assert(!md_view_attach_source(&s.fs,&source,&target,NULL));
        close(fd); md_view_location_close(&target); s.anchor[i]=anchor(&s.fs,targets[i]);
    }
    md_inode_store_close(extra);
    publish(&s,store,s.anchor,2); struct md_fs_origin_parcel p={0};
    assert(!md_fs_origin_export(s.message,&p) && p.count==19);
    packet(channel,&s.route,sizeof(s.route),&(struct md_fs_rights){0}); send_parcel(channel,&p);
    md_fs_storage_close(&cache); source_close(&s); assert(descriptors()==before);
}
int test_origin_transfer_child(int argc, char **argv) {
    if (argc!=4 || (strcmp(argv[1],"--origin-sender") && strcmp(argv[1],"--capsule-sender")
            && strcmp(argv[1],"--unknown-capsule-sender") && strcmp(argv[1],"--storage-sender"))) return -1;
    alarm(60); int fd=atoi(argv[2]); assert(!fcntl(fd,F_SETFD,FD_CLOEXEC)); unsigned before=descriptors();
    if (!strcmp(argv[1],"--storage-sender")) { storage_sender(fd,argv[3]); close(fd); return 0; }
    if (strcmp(argv[1],"--origin-sender")) {
        capsule_sender(fd,argv[3],!strcmp(argv[1],"--unknown-capsule-sender")); close(fd); return 0;
    }
    struct source s=source(argv[3],253); struct md_fs_origin_parcel p={0};
    assert(!md_fs_origin_export(s.message,&p)); packet(fd,&s.route,sizeof(s.route),&(struct md_fs_rights){0});
    send_parcel(fd,&p); source_close(&s); assert(descriptors()==before); close(fd); return 0;
}
static void processes(const char *base) {
    unsigned before=descriptors(); int fd[2]; pair(fd);
    pid_t parent=getpid(), child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL) && getppid()==parent);
        assert(!fcntl(fd[1],F_SETFD,0)); char number[16]; snprintf(number,sizeof(number),"%d",fd[1]);
        execl("/proc/self/exe","origin-transfer","--origin-sender",number,base,(char *)NULL); _exit(90);
    }
    close(fd[1]); ready(fd[0],POLLIN); struct md_fd_message_route expected; struct md_fs_rights rights={0};
    assert(md_fs_receive(fd[0],&expected,sizeof(expected),&rights)==sizeof(expected) && !rights.count);
    reap(child,0); maintenance(base,1);
    char old[PATH_MAX], moved[PATH_MAX], name[PATH_MAX]; path(old,base,"host0"); path(moved,base,"moved-host0");
    assert(!rename(old,moved) && !mkdir(old,0700)); path(name,old,"value"); file(name,"wrong");
    struct md_fs_origin_imports *i=imports(base); struct md_fs_origin_parcel p=receive_parcel(fd[0]); close(fd[0]);
    struct md_fs_origin_vector *v=NULL; assert(!md_fs_origin_import(i,&expected,&p,&v)); engine(base,v,0);
    md_fs_origin_vector_free(v); maintenance(base,0); md_fs_origin_imports_collect(i);
    md_fs_origin_imports_free(i); assert(descriptors()==before);
    puts("PASS origin transfer exec: queued SCM_RIGHTS after sender exit, replaced source path, working mixed-view origins and final lease/FD release");
}
static void storage_process(const char *base) {
    unsigned before=descriptors(); int fd[2]; pair(fd);
    pid_t parent=getpid(), child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL) && getppid()==parent);
        assert(!fcntl(fd[1],F_SETFD,0)); char number[16]; snprintf(number,sizeof(number),"%d",fd[1]);
        execl("/proc/self/exe","origin-transfer","--storage-sender",number,base,(char *)NULL); _exit(90);
    }
    close(fd[1]); ready(fd[0],POLLIN); struct md_fd_message_route expected; struct md_fs_rights rights={0};
    assert(md_fs_receive(fd[0],&expected,sizeof(expected),&rights)==sizeof(expected) && !rights.count);
    reap(child,0);
    char old[PATH_MAX], moved[PATH_MAX], store[PATH_MAX];
    path(old,base,"mounted"); path(moved,base,"moved"); path(store,base,"store");
    assert(!rename(old,moved));
    struct md_inode_store *replacement=NULL;
    assert(!md_inode_store_open(old,1,&replacement)); md_inode_store_close(replacement);
    int lease=open(moved,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(lease>=0);
    assert(flock(lease,LOCK_EX|LOCK_NB)<0 && errno==EWOULDBLOCK);
    struct md_fs_origin_imports *i=imports(base); struct md_fs_origin_parcel p=receive_parcel(fd[0]); close(fd[0]);
    assert(p.count==19);
    struct md_fs_origin_vector *v=NULL; assert(!md_fs_origin_import(i,&expected,&p,&v));
    assert(md_fs_origin_vector_count(v)==2); struct md_fs_anchor *const *a=md_fs_origin_vector_entries(v);
    assert(a[0]->mount->storage==a[1]->mount->storage && a[0]->mount!=a[1]->mount);
    md_fs_origin_imports_free(i);
    struct md_fs_storage_cache cache={0}; struct md_filesystem fs={.storage=&cache};
    assert(!md_inode_store_open(store,0,&fs.store) && !md_fs_mounts_open(&fs,NULL,0));
    contents(&fs,a[0],"value","mounted"); contents(&fs,a[1],"value","mounted");
    struct md_fs_result r=request(&fs,a[1],MD_FS_OPEN,"value",O_WRONLY,NULL); assert(r.error==-EROFS);
    r=request(&fs,a[0],MD_FS_OPEN,"value",O_WRONLY,NULL); assert(!r.error); close(r.fd);
    md_fs_origin_vector_free(v); md_fs_mounts_close(&fs); md_fs_storage_close(&cache); md_inode_store_close(fs.store);
    assert(!flock(lease,LOCK_EX|LOCK_NB)); close(lease); assert(descriptors()==before);
    puts("PASS mounted-store origins: exec sender exit, replaced store path, alias policy and final backing-lease release");
}
static void capsule_failures(struct md_ipc_capsules *owner, const struct md_ipc_binding *binding, int carrier) {
    struct md_received_fds *result=NULL; struct md_ipc_binding wrong=*binding;
    unsigned before=descriptors();
    wrong.transport=MD_IPC_TRANSPORT_NATIVE;
    assert(md_ipc_capsules_receive(owner,&wrong,-1,&result)==-ENOTSUP && !result);
    wrong=*binding; wrong.connection.side^=1;
    assert(md_ipc_capsules_receive(owner,&wrong,carrier,&result)==-EXDEV && !result);
    wrong=*binding; wrong.connection.link[0]^=1;
    assert(md_ipc_capsules_receive(owner,&wrong,carrier,&result)==-EXDEV && !result);
    /* Independent construction cannot reuse the authenticated kernel identity,
     * nor fix its cookie without invalidating the authentication tag. */
    struct {
        uint32_t magic, version, metadata, reserved;
        struct md_fd_message_route route;
        unsigned char tag[32];
    } wire;
    assert(recv(carrier,&wire,sizeof(wire),MSG_PEEK|MSG_DONTWAIT)==sizeof(wire));
    for (unsigned i=0;i<2;++i) {
        int fake[2]; pair(fake);
        if (i) {
            socklen_t size=sizeof(wire.route.cookie);
            assert(!getsockopt(fake[1],SOL_SOCKET,SO_COOKIE,&wire.route.cookie,&size));
        }
        assert(send(fake[0],&wire,sizeof(wire),MSG_NOSIGNAL)==sizeof(wire));
        assert(md_ipc_capsules_receive(owner,binding,fake[1],&result)==(i ? -EACCES : -EXDEV) && !result);
        close(fake[0]); close(fake[1]);
    }
    assert(descriptors()==before);
}
static void capsule_rollback(const struct md_ipc_binding *binding, int carrier) {
    unsigned before=descriptors(); int reached=0;
    for (int i=0;i<64;++i) {
        struct md_ipc_capsules *owner=NULL; struct md_received_fds *received=NULL;
        assert(!md_ipc_capsules_new(binding,&owner));
        test_mount_allocation_failure_after(i);
        int error=md_ipc_capsules_receive(owner,binding,carrier,&received);
        test_mount_allocation_failure_after(-1);
        if (!error) { assert(received && received->count==253); reached=1; }
        else assert(error==-ENOMEM && !received);
        md_received_fds_release(received); md_ipc_capsules_free(owner);
        assert(descriptors()==before);
        if (reached) break;
    }
    assert(reached);
    const unsigned free_slots[]={0,1,4,16,96};
    for (unsigned i=0;i<sizeof(free_slots)/sizeof(*free_slots);++i) {
        struct md_ipc_capsules *owner=NULL; struct md_received_fds *received=NULL;
        assert(!md_ipc_capsules_new(binding,&owner));
        struct rlimit old, limit; assert(!getrlimit(RLIMIT_NOFILE,&old)); limit=old;
        if (limit.rlim_cur>256) limit.rlim_cur=256;
        assert(!setrlimit(RLIMIT_NOFILE,&limit)); int fds[256], fd; unsigned count=0;
        while ((fd=fcntl(carrier,F_DUPFD_CLOEXEC,3))>=0) { assert(count<256); fds[count++]=fd; }
        assert(errno==EMFILE && count>=free_slots[i]);
        for (unsigned j=0;j<free_slots[i];++j) close(fds[--count]);
        int error=md_ipc_capsules_receive(owner,binding,carrier,&received);
        assert(!setrlimit(RLIMIT_NOFILE,&old));
        while (count) close(fds[--count]);
        assert((error==-EMFILE || error==-EPROTO) && !received);
        md_ipc_capsules_free(owner); assert(descriptors()==before);
    }
    puts("PASS capsule rollback: allocation boundaries and five partial-installation FD limits preserve queue and descriptor balance");
}
static void capsule_concurrent(struct md_ipc_capsules *owner, const struct md_ipc_binding *binding, int carrier) {
    int start[2]; assert(!pipe2(start,O_CLOEXEC)); pid_t children[2], parent=getpid();
    for (unsigned i=0;i<2;++i) {
        children[i]=fork(); assert(children[i]>=0);
        if (!children[i]) {
            assert(!prctl(PR_SET_PDEATHSIG,SIGKILL) && getppid()==parent); close(start[1]);
            /* EVENT_WAIT: parent releases both readers; the fixture alarm bounds failure. */
            char c; assert(read(start[0],&c,1)==1); close(start[0]);
            for (unsigned j=0;j<4;++j) {
                struct md_received_fds *received=NULL;
                assert(!md_ipc_capsules_receive(owner,binding,carrier,&received) && received->count==253);
                md_received_fds_release(received);
            }
            _exit(0);
        }
    }
    close(start[0]); assert(write(start[1],"PP",2)==2); close(start[1]);
    reap(children[0],0); reap(children[1],0);
}
static void capsules(const char *base, int mode) {
    int unknown=mode==1;
    unsigned before=descriptors(); int fd[2]; pair(fd);
    pid_t parent=getpid(), child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL) && getppid()==parent);
        assert(!fcntl(fd[1],F_SETFD,0)); char number[16]; snprintf(number,sizeof(number),"%d",fd[1]);
        execl("/proc/self/exe","origin-transfer",unknown ? "--unknown-capsule-sender" : "--capsule-sender",
            number,base,(char *)NULL); _exit(90);
    }
    close(fd[1]); ready(fd[0],POLLIN); reap(child,0);
    if (!unknown) maintenance(base,1);
    char store[PATH_MAX]; path(store,base,"store");
    int root=open(store,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(root>=0);
    assert(flock(root,LOCK_EX|LOCK_NB)<0 && errno==EWOULDBLOCK); close(root);
    /* Nothing from the issuer exists in this process. The queued carrier alone
     * keeps the authority epoch alive until this fresh owner opens the store. */
    struct md_ipc_credentials *authority=NULL;
    assert(!md_ipc_credentials_open(store,MD_IPC_TRANSPORT_ORIGINS_V1,&authority));
    struct md_fs_rights rights={0}; char c;
    assert(md_fs_receive(fd[0],&c,1,&rights)==1 && c=='C' && rights.count==2);
    int carrier=rights.fd[0], app=rights.fd[1]; struct md_ipc_binding receiver={0};
    assert(md_fs_receive(fd[0],&c,1,&rights)==1 && c=='R' && rights.count==1); close(fd[0]);
    int lifetime=rights.fd[0]; assert(read(lifetime,&c,1)<0 && errno==EAGAIN);
    if (mode==2) {
        close(carrier); close(app); md_ipc_credentials_close(authority);
        ready(lifetime,POLLIN); assert(!read(lifetime,&c,1)); close(lifetime);
        maintenance(base,0); assert(descriptors()==before);
        puts("PASS discarded capsule: no receiver/importer, exact data EOF and maintenance release after sender exit"); return;
    }
    assert(!md_ipc_credentials_bind(authority,app,&receiver)); close(app);
    struct md_ipc_capsules *owner=NULL; assert(!md_ipc_capsules_new(&receiver,&owner));
    capsule_failures(owner,&receiver,carrier);
    if (!unknown) { capsule_rollback(&receiver,carrier); capsule_concurrent(owner,&receiver,carrier); }
    struct md_received_fds *a=NULL, *b=NULL;
    assert(!md_ipc_capsules_receive(owner,&receiver,carrier,&a));
    assert(!md_ipc_capsules_receive(owner,&receiver,carrier,&b));
    assert(a->count==253 && b->count==253);
    for (unsigned i=0;i<253;++i) assert(fcntl(a->fds[i],F_GETFD)&FD_CLOEXEC);
    if (unknown) assert(!a->origins && !b->origins);
    else {
        engine_objects(base,a->origins); engine_objects(base,b->origins);
        assert(md_fs_anchor_from_object(a->origins[1])->view==md_fs_anchor_from_object(b->origins[1])->view);
        char bytes[2]; assert(read(a->fds[5],bytes,1)==1 && bytes[0]=='l');
        assert(read(b->fds[5],bytes+1,1)==1 && bytes[1]=='o');
    }
    md_received_fds_release(a); close(carrier);
    md_ipc_capsules_free(owner); md_ipc_binding_release(&receiver); md_ipc_credentials_close(authority);
    if (!unknown) { maintenance(base,1); engine_objects(base,b->origins); }
    md_received_fds_release(b); maintenance(base,0);
    ready(lifetime,POLLIN); assert(!read(lifetime,&c,1)); close(lifetime); assert(descriptors()==before);
    assert(!md_ipc_credentials_open(store,MD_IPC_TRANSPORT_ORIGINS_V1,&authority));
    char key[PATH_MAX]; path(key,store,"ipc/auth");
    assert(access(key,F_OK)<0 && errno==ENOENT); md_ipc_credentials_close(authority);
    assert(descriptors()==before);
    puts(unknown ? "PASS unknown capsule: epoch survives sender exit without origin leases; fresh epoch retires key"
        : "PASS authenticated capsule: exec sender exit, 253 rights, repeated peek, shared OFD, imported views, authentication rejection and final cleanup");
}
static void bundles(void) {
    unsigned before=descriptors(); int fd=open("/dev/null",O_RDONLY|O_CLOEXEC); assert(fd>=0);
    int source[600], received[600]; for (unsigned i=0;i<600;++i) source[i]=fd;
    assert(md_fs_rights_transferable(source,1)==1);
    assert(md_fs_rights_transferable(source,2)==1);
    int invalid=-1;
    assert(md_fs_rights_transferable(&invalid,1)==-EBADF);
    assert(md_fs_rights_transferable(NULL,1)==-EINVAL);
    assert(descriptors()==before+1);
    for (unsigned count=253;count<=600;count+=347) {
        int bundle=-1; assert(!md_fd_bundle_pack(source,count,&bundle));
        for (unsigned n=0;n<2;++n) {
            assert(!md_fd_bundle_peek(bundle,count,received));
            for (unsigned i=0;i<count;++i) { assert(fcntl(received[i],F_GETFD)&FD_CLOEXEC); close(received[i]); }
        }
        assert(md_fd_bundle_peek(bundle,count-1,received)<0);
        for (unsigned i=0;i<count-1;++i) assert(received[i]==-1);
        close(bundle);
    }
    source[599]=-1; int bundle=-1;
    assert(md_fd_bundle_pack(source,600,&bundle)==-EBADF && bundle==-1);
    close(fd); assert(descriptors()==before);
    puts("PASS immutable FD bundles: 253/600 rights, repeated peek, malformed size and partial construction cleanup");
}
struct typed_origin {
    struct md_fd_object object;
    unsigned *destroyed;
    struct md_fs_anchor *placement;
};
static void typed_destroy(struct md_fd_object *object) {
    struct typed_origin *p=(void *)object;
    md_fs_anchor_release(p->placement); ++*p->destroyed; free(p);
}
static struct md_fs_anchor *typed_placement(struct md_fd_object *object) {
    return ((struct typed_origin *)object)->placement;
}
static void typed_capsules(const char *base, int mode) {
    int private_view=mode==1, placed=mode==2;
    unsigned before=descriptors(), destroyed=0;
    char store[PATH_MAX]; path(store,base,"store");
    struct source source_state={0};
    if (private_view || placed) {
        source_state=source(base,1);
        assert(md_fs_anchor_portable(source_state.anchor[5]));
    }
    if (private_view) {
        int parent=open(base,O_PATH|O_DIRECTORY|O_CLOEXEC); assert(parent>=0);
        struct md_view_mount *mount=&source_state.fs.mounts->tree->mounts[0];
        mount->provider=md_provider_tree_new(parent,mount->source,NULL,0,NULL,NULL);
        close(parent); assert(mount->provider);
        assert(!md_fs_anchor_portable(source_state.anchor[5]));
        struct md_fs_origin_parcel parcel={0};
        assert(md_fs_origin_export(source_state.message,&parcel)==-EOPNOTSUPP && !parcel.count);
    }
    if (!private_view && !placed) {
        struct md_inode_store *inode=NULL; assert(!md_inode_store_open(store,1,&inode));
        md_inode_store_close(inode);
    }
    struct md_ipc_credentials *authority=NULL, *foreign=NULL;
    assert(!md_ipc_credentials_open(store,MD_IPC_TRANSPORT_ORIGINS_V1,&authority));
    assert(!md_ipc_credentials_open(store,MD_IPC_TRANSPORT_ORIGINS_V1,&foreign));
    int app[2]; pair(app);
    struct md_identity identity=md_identity_new(0,0);
    struct md_fs_request q={.operation=MD_FS_IPC,.flags=MD_IPC_PAIR,.directory={app[0],app[1]},.peer=getpid()};
    struct md_fs_result result; md_ipc_credentials_execute(authority,&identity,NULL,&q,&result,NULL);
    assert(!result.error); md_identity_release(&identity);
    struct md_ipc_binding sender={0},receiver={0},other={0};
    assert(!md_ipc_credentials_bind(authority,app[0],&sender));
    assert(!md_ipc_credentials_bind(authority,app[1],&receiver));
    assert(!md_ipc_credentials_bind(foreign,app[1],&other));
    struct md_ipc_capsules *owner=NULL, *outsider=NULL;
    assert(!md_ipc_capsules_new(&sender,&owner) && !md_ipc_capsules_new(&other,&outsider));
    static const struct md_fd_object_type type={.destroy=typed_destroy,.origin=typed_placement,
        .selectors=1U<<MD_FD_SELECTOR_PROC};
    struct typed_origin *p=calloc(1,sizeof(*p)); assert(p);
    md_fd_object_init(&p->object,&type); p->destroyed=&destroyed;
    if (placed) p->placement=md_fs_anchor_retain(source_state.anchor[5]);
    struct md_fd_object *objects[]={private_view ? md_fs_anchor_object(source_state.anchor[5]) : &p->object};
    int fd=private_view ? fcntl(md_fs_anchor_fd(source_state.anchor[5]),F_DUPFD_CLOEXEC,3)
        : open("/dev/null",O_RDWR|O_CLOEXEC); assert(fd>=0);
    unsigned warmed=0;
    for (unsigned i=0;i<64;++i) {
        struct md_fd_message *message=capture_objects(objects,1);
        int carrier=-1;
        assert(!md_ipc_capsules_publish(owner,&sender,"0123456789abcdef0123456789abcdef",message,&fd,1,&carrier));
        md_fd_message_release(message);
        assert(md_ipc_credentials_pollfd(authority)>=0 && !md_ipc_credentials_dispatch(authority));
        struct md_received_fds *received=NULL;
        unsigned held=descriptors();
        assert(md_ipc_capsules_receive(outsider,&other,carrier,&received)==-EXDEV && !received);
        assert(descriptors()==held);
        if (i%2) {
            assert(!md_ipc_capsules_receive(owner,&receiver,carrier,&received));
            assert(received->count==1 && received->origins && received->origins[0]==objects[0]);
        }
        close(carrier);
        ready(md_ipc_credentials_pollfd(authority),POLLIN);
        assert(md_ipc_credentials_dispatch(authority)==1 && !md_ipc_credentials_dispatch(authority));
        assert(!destroyed); md_received_fds_release(received);
        if (!i) warmed=descriptors();
        else assert(descriptors()==warmed);
    }
    int reached=0;
    for (int i=0;i<64 && !reached;++i) {
        struct md_fd_message *message=capture_objects(objects,1); int carrier=-1;
        test_mount_allocation_failure_after(i);
        int error=md_ipc_capsules_publish(owner,&sender,"0123456789abcdef0123456789abcdef",message,&fd,1,&carrier);
        test_mount_allocation_failure_after(-1);
        if (!error) { reached=1; close(carrier); ready(md_ipc_credentials_pollfd(authority),POLLIN); }
        else assert(error==-ENOMEM && carrier==-1);
        assert(md_ipc_credentials_dispatch(authority)==(!error));
        md_fd_message_release(message); assert(descriptors()==warmed);
    }
    assert(reached); md_fd_object_release(&p->object); assert(destroyed==1);
    close(fd); close(app[0]); close(app[1]);
    md_ipc_capsules_free(owner); md_ipc_capsules_free(outsider);
    md_ipc_binding_release(&sender); md_ipc_binding_release(&receiver); md_ipc_binding_release(&other);
    md_ipc_credentials_close(foreign); md_ipc_credentials_close(authority);
    if (private_view || placed) source_close(&source_state);
    assert(descriptors()==before);
    puts(private_view
        ? "PASS private-view file capsules: exact anchor, foreign rejection, queue discard, allocation rollback and final release"
        : placed ? "PASS placed typed capsules: independent carrier, exact authority, foreign rejection, discard, allocation rollback and final release"
        : "PASS typed local capsules: exact authority, foreign rejection, queue discard, partial allocation, 64 cycles and final release");
}
static void unknown(const char *base) {
    unsigned before=descriptors(); char store[PATH_MAX]; path(store,base,"store");
    struct md_inode_store *inode=NULL; assert(!md_inode_store_open(store,1,&inode));
    md_inode_store_close(inode); struct source s={0}; struct md_fs_anchor *entries[253]={0};
    publish(&s,store,entries,253); struct md_fs_origin_parcel p={0};
    assert(!md_fs_origin_export(s.message,&p) && p.count==1);
    struct md_fs_origin_imports *i=imports(base); struct md_fs_origin_vector *v=NULL;
    assert(!md_fs_origin_import(i,&s.route,&p,&v) && md_fs_origin_vector_count(v)==253);
    for (unsigned n=0;n<253;++n) assert(!md_fs_origin_vector_entries(v)[n]);
    md_fs_origin_vector_free(v); md_fs_origin_imports_free(i); message_close(&s);
    assert(descriptors()==before); puts("PASS unknown origin vector: one manifest, no invented mount or backing owner");
}
static void anonymous_capsules(const char *base) {
    unsigned before=descriptors(); struct source source_state=source(base,1);
    char store[PATH_MAX]; path(store,base,"store");
    struct md_ipc_credentials *authority=NULL,*foreign=NULL;
    assert(!md_ipc_credentials_open(store,MD_IPC_TRANSPORT_ORIGINS_V1,&authority));
    assert(!md_ipc_credentials_open(store,MD_IPC_TRANSPORT_ORIGINS_V1,&foreign));
    int app[2],ends[2]; pair(app); assert(!pipe2(ends,O_CLOEXEC|O_NONBLOCK));
    struct md_identity root=md_identity_new(0,0);
    struct md_fs_request q={.operation=MD_FS_IPC,.flags=MD_IPC_PAIR,.directory={app[0],app[1]},.peer=getpid()};
    struct md_fs_result result; md_ipc_credentials_execute(authority,&root,NULL,&q,&result,NULL);
    assert(!result.error);
    struct md_ipc_binding sender={0},receiver={0};
    assert(!md_ipc_credentials_bind(authority,app[0],&sender));
    assert(!md_ipc_credentials_bind(foreign,app[1],&receiver));
    struct md_ipc_capsules *owner=NULL,*reader=NULL;
    assert(!md_ipc_capsules_new(&sender,&owner) && !md_ipc_capsules_new(&receiver,&reader));
    struct md_fd_object *inode=md_descriptor_inode_new(&root,S_IFIFO|0600); assert(inode);
    struct md_fd_object *objects[]={inode,md_fs_anchor_object(source_state.anchor[0]),inode};
    int fds[]={ends[0],md_fs_anchor_fd(source_state.anchor[0]),ends[1]};
    struct md_fd_message *message=capture_objects(objects,3); int carrier=-1;
    assert(!md_ipc_capsules_publish(owner,&sender,"0123456789abcdef0123456789abcdef",message,fds,3,&carrier));
    md_fd_message_release(message);
    struct md_received_fds *received=NULL;
    assert(!md_ipc_capsules_receive(reader,&receiver,carrier,&received));
    /* Retain the warmed origins: collect may otherwise legitimately release
     * their backing cache during an early failed receive. */
    struct md_received_fds *warm=received; received=NULL;
    unsigned warmed=descriptors();
    int reached=0;
    for (int i=0;i<128 && !reached;++i) {
        test_mount_allocation_failure_after(i);
        int error=md_ipc_capsules_receive(reader,&receiver,carrier,&received);
        test_mount_allocation_failure_after(-1);
        if (!error) {
            reached=1; assert(received->count==3);
            assert(md_descriptor_inode_is(received->origins[0]) && md_descriptor_inode_is(received->origins[2]));
            assert(md_fs_anchor_from_object(received->origins[1]));
            assert(!md_descriptor_inode_chown(received->origins[2],&root,121,122));
            struct stat st={0}; assert(!md_descriptor_inode_stat(inode,&root,&st));
            assert(st.st_uid==121 && st.st_gid==122);
            assert(!md_descriptor_inode_chmod(inode,&root,0640));
            assert(!md_descriptor_inode_stat(received->origins[0],&root,&st));
            assert((st.st_mode&07777)==0640);
            md_received_fds_release(received); received=NULL;
        } else assert(error==-ENOMEM && !received);
        unsigned current=descriptors();
        if (current!=warmed) fprintf(stderr,"anonymous import allocation=%d error=%d fds=%u expected=%u\n",i,error,current,warmed);
        assert(current==warmed);
    }
    assert(reached);
    md_received_fds_release(warm);
    md_fd_object_release(inode);
    md_ipc_capsules_free(owner); md_ipc_binding_release(&sender); md_ipc_credentials_close(authority);
    source_close(&source_state); close(ends[1]); close(app[0]);
    assert(!md_ipc_capsules_receive(reader,&receiver,carrier,&received));
    close(carrier);
    assert(write(received->fds[2],"p",1)==1);
    char byte; assert(read(ends[0],&byte,1)==1 && byte=='p');
    md_received_fds_release(received);
    assert(!read(ends[0],&byte,1));
    close(ends[0]); close(app[1]);
    md_ipc_capsules_free(reader); md_ipc_binding_release(&receiver); md_ipc_credentials_close(foreign);
    md_identity_release(&root);
    assert(descriptors()==before);
    puts("PASS anonymous capsules: mixed origins, foreign authority, live metadata, allocation rollback, sender exit and EOF");
}
static int unmount_alias(struct md_filesystem *fs) {
    struct md_identity identity=md_identity_new(0,0);
    fs->store->identity=&identity;
    struct md_fs_request q={.operation=MD_FS_MOUNT,.mode=MD_FS_MOUNT_UMOUNT,
        .directory={MD_INODE_ROOT,MD_INODE_ROOT},.path={"/alias",NULL}};
    struct md_fs_result r; md_fs_execute(fs,&q,&r,NULL);
    fs->store->identity=NULL; md_identity_release(&identity); return r.error;
}
static void mount_pins(const char *base) {
    unsigned before=descriptors(); struct source s=source(base,3);
    struct md_fs_origin_parcel p={0}; assert(!md_fs_origin_export(s.message,&p));
    int channel[2]; pair(channel); send_parcel(channel[0],&p);
    message_close(&s);
    for (unsigned n=0;n<6;++n) { md_fs_anchor_release(s.anchor[n]); s.anchor[n]=NULL; }
    assert(!atomic_load(&s.fs.mounts->tree->mounts[2].anchors));
    assert(unmount_alias(&s.fs)==-EBUSY);
    p=receive_parcel(channel[1]); close(channel[0]); close(channel[1]);
    struct md_fs_origin_imports *i=imports(base); struct md_fs_origin_vector *v=NULL;
    assert(!md_fs_origin_import(i,&s.route,&p,&v));
    assert(unmount_alias(&s.fs)==-EBUSY);
    struct md_fs_anchor *a=md_fs_origin_vector_entries(v)[2], *derived=NULL;
    struct md_fs_result r=request(&s.fs,a,MD_FS_OPEN,"value",O_PATH,&derived);
    assert(!r.error && derived); close(r.fd);
    md_fs_origin_vector_free(v); md_fs_origin_imports_free(i);
    assert(unmount_alias(&s.fs)==-EBUSY);
    md_fs_anchor_release(derived);
    assert(!unmount_alias(&s.fs));
    md_fs_mounts_close(&s.copy); md_fs_mounts_close(&s.fs); md_inode_store_close(s.fs.store);
    assert(descriptors()==before);
    puts("PASS mount busy lifetime: queued rights, imported and derived origins, final close and exact cleanup");
}
static void device_origin(const char *base) {
    unsigned before=descriptors(); struct source s=source(base,1);
    message_close(&s); s.message=NULL; s.messages=NULL;
    assert(!md_inode_mknod(s.fs.store,-1,"/null",S_IFCHR|0600,makedev(1,3)));
    struct md_fs_anchor *a=NULL;
    struct md_fs_result opened=request(&s.fs,NULL,MD_FS_OPEN,"/null",O_RDWR,&a);
    assert(!opened.error && opened.fd>=0 && a && a->endpoint.inode);
    struct md_fs_result expected=request(&s.fs,a,MD_FS_FSTAT,NULL,0,NULL);
    assert(!expected.error && S_ISCHR(expected.info.mode));
    char store[PATH_MAX]; path(store,base,"store");
    publish(&s,store,&a,1);
    struct md_fs_origin_parcel p={0}; assert(!md_fs_origin_export(s.message,&p));
    struct md_fd_message_route route=s.route;
    assert(!md_inode_unlink(s.fs.store,-1,"/null",0));
    md_fs_anchor_release(a); source_close(&s);
    struct md_fs_origin_imports *i=imports(base); struct md_fs_origin_vector *v=NULL;
    assert(!md_fs_origin_import(i,&route,&p,&v));
    a=md_fs_origin_vector_entries(v)[0];
    struct md_filesystem fs={0};
    assert(!md_inode_store_open(store,0,&fs.store) && !md_fs_mounts_open(&fs,NULL,0));
    struct md_fs_request q={.operation=MD_FS_FSTAT,.origin={a,NULL},.directory={opened.fd,-1}};
    struct md_fs_result st; md_fs_execute(&fs,&q,&st,NULL);
    assert(!st.error && st.info.mode==expected.info.mode && st.info.inode==expected.info.inode
        && st.info.rdev==makedev(1,3) && !st.info.links);
    assert(write(opened.fd,"x",1)==1);
    struct md_fs_result again=request(&fs,a,MD_FS_REOPEN,NULL,O_RDWR,NULL);
    assert(!again.error && again.fd>=0 && write(again.fd,"x",1)==1); close(again.fd);
    md_fs_mounts_close(&fs); md_inode_store_close(fs.store);
    md_fs_origin_vector_free(v); md_fs_origin_imports_free(i); close(opened.fd);
    assert(descriptors()==before);
    puts("PASS device origin: cold import after sender teardown, unlinked metadata, native IO and reopen");
}
void test_origin_transfer(const char *base) {
    alarm(120); bundles();
    const char *names[]={"origin-local","origin-faults","origin-processes","origin-unknown",
        "origin-capsules","origin-capsules-unknown","origin-capsules-discard","origin-storage","origin-mount-pins",
        "origin-typed-capsules","origin-anonymous-capsules","origin-private-view-capsules","origin-device",
        "origin-placed-capsules"}; char name[PATH_MAX];
    for (unsigned n=0;n<sizeof(names)/sizeof(*names);++n) {
        path(name,base,names[n]); assert(!mkdir(name,0700));
        if (!n) local(name); else if (n==1) failures(name); else if (n==2) processes(name);
        else if (n==3) unknown(name); else if (n==7) storage_process(name);
        else if (n==8) mount_pins(name); else if (n==9) typed_capsules(name,0);
        else if (n==10) anonymous_capsules(name); else if (n==11) typed_capsules(name,1);
        else if (n==12) device_origin(name);
        else if (n==13) typed_capsules(name,2);
        else capsules(name,(int)n-4);
    }
    alarm(0); puts("PASS portable message origins");
}
