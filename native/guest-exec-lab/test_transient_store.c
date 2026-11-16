#define _GNU_SOURCE
#include "inode_internal.h"
#include "inode_custody.h"
#include "temporary_directory.h"
#include "fs_wire.h"
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <unistd.h>

void test_mount_allocation_failure_after(int count);
static unsigned descriptors(void) {
    DIR *d=opendir("/proc/self/fd"); assert(d); unsigned count=0;
    struct dirent *entry;
    while ((entry=readdir(d))) if (entry->d_name[0]!='.') ++count;
    assert(!closedir(d)); return count;
}
static unsigned entries(int parent) {
    int fd=openat(parent,".",O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(fd>=0);
    DIR *d=fdopendir(fd); assert(d); unsigned count=0; struct dirent *entry;
    while ((entry=readdir(d)))
        if (strcmp(entry->d_name,".") && strcmp(entry->d_name,"..")) ++count;
    assert(!closedir(d)); return count;
}
static void temporary_recovery(int parent) {
    assert(!mkdirat(parent,"unrelated",0700));
    assert(!mkdirat(parent,".md-transient-not-owned",0700));
    for (unsigned i=0;i<10;++i) {
        unsigned variant=i%5;
        int root=md_temporary_directory_new(parent); assert(root>=0);
        assert(!symlinkat("../unrelated",root,"outside"));
        assert(!fchmodat(root,MD_TEMPORARY_DIRECTORY_MARKER,0600,0));
        int marker=openat(root,MD_TEMPORARY_DIRECTORY_MARKER,O_RDWR|O_CLOEXEC);
        assert(marker>=0);
        unsigned char saved[64];
        ssize_t size=pread(marker,saved,sizeof(saved),0);
        assert(size>1 && size<(ssize_t)sizeof(saved));
        if (!variant) assert(!unlinkat(root,MD_TEMPORARY_DIRECTORY_MARKER,0));
        else if (variant==1) assert(!ftruncate(marker,0));
        else if (variant==2) assert(!ftruncate(marker,size-1));
        else {
            if (variant==3) memset(saved,0,(size_t)size);
            else saved[size-1]^=1;
            assert(pwrite(marker,saved,(size_t)size,0)==size);
        }
        assert(!fchmod(marker,0400) && !close(marker));
        assert(md_temporary_directory_check(root)==(!variant ? -ENOENT : -ESTALE));
        /* A damaged marker never revokes a live lease, including one retained
         * while admitting a different provider. Final close models lost owners. */
        assert(!md_temporary_directory_collect(parent) && entries(parent)==3);
        int other=md_temporary_directory_new(parent); assert(other>=0 && entries(parent)==4);
        md_temporary_directory_release(other);
        assert(entries(parent)==3);
        if (i<5) assert(!close(root));
        else { md_temporary_directory_release(root); assert(entries(parent)==2); }
        assert(!md_temporary_directory_collect(parent) && entries(parent)==2);
        assert(!faccessat(parent,"unrelated",F_OK,0));
        assert(!faccessat(parent,".md-transient-not-owned",F_OK,0));
    }
    int root=md_temporary_directory_new(parent); assert(root>=0);
    assert(!fchmod(root,0755));
    assert(md_temporary_directory_collect(parent)==-EACCES && entries(parent)==3);
    assert(!fchmod(root,0700));
    md_temporary_directory_release(root);
    assert(entries(parent)==2);
    assert(!unlinkat(parent,"unrelated",AT_REMOVEDIR));
    assert(!unlinkat(parent,".md-transient-not-owned",AT_REMOVEDIR));
    puts("PASS transient recovery: missing, torn, zero and stale markers; live leases and unrelated trees retained");
}
static void content(struct md_inode_store *store) {
    sqlite3_stmt *q=NULL;
    assert(sqlite3_prepare_v2(store->db,"PRAGMA synchronous",-1,&q,NULL)==SQLITE_OK);
    assert(sqlite3_step(q)==SQLITE_ROW && !sqlite3_column_int(q,0));
    sqlite3_finalize(q);
    assert(!mdi_sync(store,-1));
    int fd=md_inode_open(store,-1,"/file",O_RDONLY,0); assert(fd>=0);
    char text[16]; assert(read(fd,text,sizeof(text))==5 && !memcmp(text,"hello",5));
    assert(!close(fd));
    assert(!md_inode_link(store,-1,"/file",-1,"/linked",0));
    fd=md_inode_open(store,-1,"/linked",O_RDWR,0); assert(fd>=0);
    assert(pwrite(fd,"!",1,4)==1 && !close(fd));
}
int test_transient_store_child(int argc, char **argv) {
    if (argc!=4 || strcmp(argv[1],"--transient-receiver")) return -1;
    alarm(30);
    int socket=atoi(argv[2]), gate=atoi(argv[3]); char byte;
    /* EVENT_WAIT: parent has released its store before receipt begins. */
    assert(read(gate,&byte,1)==1 && byte=='g'); close(gate);
    struct md_inode_custody_parcel parcel={0};
    assert(md_fs_receive_fds(socket,&byte,1,parcel.fd,3,&parcel.count)==1);
    assert(parcel.count==3);
    struct md_inode_lifetime *owner=NULL;
    assert(!md_inode_custody_import(&parcel,parcel.fd[1],&owner) && owner->temporary);
    assert(owner->owner && md_user_namespace_into(owner->owner,0,0)==1000);
    assert(md_user_namespace_into(owner->owner,1,0)==2000);
    assert(md_user_namespace_from(owner->owner,0,0)==UINT32_MAX);
    struct md_inode_store *store=NULL;
    assert(!md_inode_store_reconnect(owner,&store));
    content(store);
    md_inode_store_close(store);
    md_inode_lifetime_release(owner);
    assert(write(socket,"d",1)==1); close(socket); return 0;
}
static void transferred(int parent) {
    struct md_user_namespace *namespace=md_user_namespace_new(NULL,1000,2000,0); assert(namespace);
    struct md_user_authority authority={.uid=1000,.gid=2000,.caps=UINT64_MAX};
    struct md_id_extent map={0,1000,1};
    assert(!md_user_namespace_map(namespace,0,&authority,&authority,&map,1));
    map.outside=2000;
    assert(!md_user_namespace_map(namespace,1,&authority,&authority,&map,1));
    struct md_inode_store *store=NULL; assert(!md_inode_store_transient(parent,namespace,&store));
    md_user_namespace_release(namespace);
    int file=md_inode_open(store,-1,"/file",O_CREAT|O_WRONLY,0600); assert(file>=0);
    assert(write(file,"hello",5)==5 && !close(file));
    struct md_inode_custody_parcel parcel={0};
    assert(!md_inode_custody_export(store->lifetime,&parcel));
    int pair[2], gate[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET,0,pair) && !pipe(gate));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(pair[0]); close(gate[1]);
        char number[32], barrier[32]; snprintf(number,sizeof(number),"%d",pair[1]);
        snprintf(barrier,sizeof(barrier),"%d",gate[0]);
        execl("/proc/self/exe","test","--transient-receiver",number,barrier,NULL); _exit(120);
    }
    close(pair[1]); close(gate[0]);
    /* The command packet is separate from queued rights. Child cannot receive
     * its object before the parent has dropped all local ownership. */
    assert(md_fs_send_fds(pair[0],"p",1,parcel.fd,parcel.count)==1);
    md_inode_custody_parcel_close(&parcel); md_inode_store_close(store);
    assert(!md_temporary_directory_collect(parent) && entries(parent)==1);
    assert(write(gate[1],"g",1)==1); close(gate[1]);
    char byte;
    /* EVENT_WAIT: receiver completed IO and released its final owner. */
    assert(read(pair[0],&byte,1)==1 && byte=='d'); close(pair[0]);
    int status;
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!entries(parent));
}
struct crash_gate { int ready, hold; enum md_inode_checkpoint point; };
static void crash_checkpoint(enum md_inode_checkpoint point,void *context) {
    struct crash_gate *gate=context;
    if (point!=gate->point) return;
    assert(write(gate->ready,"r",1)==1);
    char byte;
    /* EVENT_WAIT: test owner kills this child at the exact journal boundary. */
    assert(read(gate->hold,&byte,1)==1);
    abort();
}
static void recovery(int parent) {
    const enum md_inode_checkpoint points[]={MD_OBJECT_SYNCED,MD_NAMESPACE_STAGED,MD_NAMESPACE_COMMITTED};
    for (unsigned i=0;i<sizeof(points)/sizeof(points[0]);++i) {
        struct md_inode_store *s=NULL; assert(!md_inode_store_transient(parent,NULL,&s));
        struct md_inode_lifetime *owner=md_inode_lifetime_retain(s->lifetime);
        md_inode_store_close(s);
        int ready[2],hold[2]; assert(!pipe(ready) && !pipe(hold));
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            close(ready[0]); close(hold[1]);
            assert(!md_inode_store_reconnect(owner,&s));
            struct crash_gate gate={ready[1],hold[0],points[i]};
            md_inode_observe(s,crash_checkpoint,&gate);
            md_inode_create(s,-1,"/new",0600);
            _exit(99);
        }
        close(ready[1]); close(hold[0]); char byte;
        /* EVENT_WAIT: exact publication boundary; runner deadline catches hangs. */
        assert(read(ready[0],&byte,1)==1 && byte=='r');
        if (points[i]==MD_NAMESPACE_STAGED) {
            struct stat st;
            assert(!fstatat(owner->root,"namespace.db-wal",&st,0) && st.st_size>0);
        }
        assert(!kill(child,SIGKILL)); int status;
        assert(waitpid(child,&status,0)==child && WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
        close(ready[0]); close(hold[1]);
        assert(!md_inode_store_reconnect(owner,&s));
        struct stat st; int committed=points[i]==MD_NAMESPACE_COMMITTED;
        assert(md_inode_stat(s,-1,"/new",0,&st)==(committed ? 0 : -ENOENT));
        struct md_inode_audit audit; assert(!md_inode_audit(s,&audit));
        assert(audit.objects==(unsigned)(1+committed) && audit.untracked==(unsigned)!committed);
        md_inode_store_close(s); md_inode_lifetime_release(owner);
        assert(!entries(parent));
    }
    puts("PASS transient journals: process death before/after commit, intact rollback and final retirement");
}
static void reconfiguration(int parent) {
    struct md_inode_store *s=NULL;
    assert(!md_inode_store_transient(parent,NULL,&s));
    s->lifetime->filesystem_kind=MD_FILESYSTEM_TMPFS;
    int fd=md_inode_create(s,-1,"/file",0600); assert(fd>=0 && !close(fd));
    int pair[2]; assert(!socketpair(AF_UNIX,SOCK_STREAM,0,pair));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(pair[0]);
        struct md_inode_store *peer=NULL;
        assert(!md_inode_store_reconnect(s->lifetime,&peer));
        int writer=md_inode_open(peer,-1,"/file",O_WRONLY,0); assert(writer>=0);
        assert(write(pair[1],"w",1)==1);
        char byte;
        /* EVENT_WAIT: parent tests the writer, then asks for its exact release. */
        assert(read(pair[1],&byte,1)==1 && byte=='c');
        assert(!close(writer) && write(pair[1],"c",1)==1);
        /* EVENT_WAIT: parent publishes readonly through another connection. */
        assert(read(pair[1],&byte,1)==1 && byte=='r');
        assert(md_inode_store_readonly(peer)==1);
        assert(md_inode_open(peer,-1,"/file",O_WRONLY,0)==-EROFS);
        assert(md_inode_mkdir(peer,-1,"/blocked",0700)==-EROFS);
        assert(write(pair[1],"r",1)==1);
        /* EVENT_WAIT: parent clears only superblock policy. */
        assert(read(pair[1],&byte,1)==1 && byte=='w');
        assert(!md_inode_store_readonly(peer));
        writer=md_inode_open(peer,-1,"/file",O_WRONLY,0); assert(writer>=0 && !close(writer));
        md_inode_store_close(peer); close(pair[1]); _exit(0);
    }
    close(pair[1]); char byte;
    /* EVENT_WAIT: peer retains writable FD; no timing-based writer inference. */
    assert(read(pair[0],&byte,1)==1 && byte=='w');
    assert(md_inode_store_reconfigure(s,1)==-EBUSY);
    assert(!md_inode_store_readonly(s));
    assert(write(pair[0],"c",1)==1 && read(pair[0],&byte,1)==1 && byte=='c');
    assert(!md_inode_store_reconfigure(s,1) && md_inode_store_readonly(s)==1);
    assert(write(pair[0],"r",1)==1 && read(pair[0],&byte,1)==1 && byte=='r');
    assert(!md_inode_store_reconfigure(s,0));
    assert(write(pair[0],"w",1)==1); close(pair[0]);
    int status;
    /* EVENT_WAIT: independent connection closes after observing writable. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    md_inode_store_close(s);
    assert(!entries(parent));
    puts("PASS transient superblock: native writer exclusion and live cross-process readonly policy");
}
void test_transient_store(const char *base) {
    char path[PATH_MAX]; snprintf(path,sizeof(path),"%s/transient",base);
    assert(!mkdir(path,0700));
    int parent=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(parent>=0);
    unsigned before=descriptors();
    temporary_recovery(parent);
    recovery(parent);
    reconfiguration(parent);
    assert(descriptors()==before);
    for (unsigned i=0;i<32;++i) {
        struct md_inode_store *s=NULL; assert(!md_inode_store_transient(parent,NULL,&s));
        assert(s->lifetime->temporary && !md_temporary_directory_check(s->lifetime->root));
        int fd=md_inode_open(s,-1,"/file",O_CREAT|O_WRONLY,0600); assert(fd>=0);
        assert(write(fd,"hello",5)==5 && !close(fd));
        struct md_inode_lifetime *owner=md_inode_lifetime_retain(s->lifetime);
        md_inode_store_close(s);
        assert(entries(parent)==1 && !md_temporary_directory_collect(parent));
        assert(!md_inode_store_reconnect(owner,&s)); content(s);
        md_inode_store_close(s); md_inode_lifetime_release(owner);
        assert(entries(parent)==0 && descriptors()==before);
    }
    puts("PASS transient stores: retained connection reopen, shared data and exact final retirement");
    for (unsigned drop=0;drop<2;++drop) {
        struct md_inode_store *s=NULL; assert(!md_inode_store_transient(parent,NULL,&s));
        struct md_inode_custody_parcel p={0}; assert(!md_inode_custody_export(s->lifetime,&p));
        md_inode_store_close(s);
        assert(entries(parent)==1 && !md_temporary_directory_collect(parent));
        if (drop) {
            md_inode_custody_parcel_close(&p);
            assert(entries(parent)==1 && !md_temporary_directory_collect(parent));
        } else {
            struct md_inode_lifetime *owner=NULL;
            assert(!md_inode_custody_import(&p,p.fd[1],&owner) && owner->temporary);
            md_inode_lifetime_release(owner);
        }
        assert(!entries(parent) && descriptors()==before);
    }
    puts("PASS transient custody: import, unpublished parcel discard and admission recovery");
    transferred(parent);
    assert(descriptors()==before);
    puts("PASS transient store: queued rights outlive publisher, fresh exec importer owns final cleanup");
    for (int i=0;i<4;++i) {
        struct md_inode_store *s=NULL;
        test_mount_allocation_failure_after(i);
        int r=md_inode_store_transient(parent,NULL,&s);
        test_mount_allocation_failure_after(-1);
        if (!r) md_inode_store_close(s);
        else assert(r==-ENOMEM && !s);
        assert(!md_temporary_directory_collect(parent) && !entries(parent) && descriptors()==before);
    }
    assert(!close(parent));
}
