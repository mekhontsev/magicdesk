#define _GNU_SOURCE
#include "task_registry.h"
#include "task_resources.h"
#include "inode_internal.h"
#include "fs_operation.h"
#include "fs_engine.h"
#include "fs_mounts.h"
#include "guest_accounts.h"
#include "linux_abi.h"
#include "file_permissions.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <sys/prctl.h>
#include <linux/securebits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static void file_permissions(void) {
    struct md_identity reader=md_identity_new(1000,1000);
    reader.caps.effective=UINT64_C(1)<<CAP_DAC_READ_SEARCH;
    struct stat node={.st_uid=1001,.st_gid=1001,.st_mode=S_IFREG};
    for (unsigned directory=0;directory<2;++directory) {
        node.st_mode=directory ? S_IFDIR : S_IFREG;
        for (unsigned mode=0;mode<8;++mode) {
            int expected=mode&W_OK || (!directory && (mode&X_OK)) ? -EACCES : 0;
            assert(md_file_permissions(&node,mode,&reader)==expected);
        }
    }
    reader.caps.effective=UINT64_C(1)<<CAP_DAC_OVERRIDE;
    for (unsigned executable=0;executable<2;++executable) {
        node.st_mode=S_IFREG|(executable ? 0001 : 0);
        for (unsigned mode=0;mode<8;++mode)
            assert(md_file_permissions(&node,mode,&reader)==((mode&X_OK) && !executable ? -EACCES : 0));
    }
    reader.caps.effective=0; node.st_mode=S_IFREG|0640; node.st_uid=1000;
    assert(!md_file_permissions(&node,R_OK|W_OK,&reader));
    assert(md_file_permissions(&node,X_OK,&reader)==-EACCES);
    assert(md_file_permissions(&node,8,&reader)==-EINVAL);
    md_identity_release(&reader);
    puts("PASS shared file DAC: read/search, override, executable bits and ordinary modes");
}
static void capabilities(void) {
    struct md_identity root=md_identity_new(0,0), user=md_identity_new(1000,1000);
    const unsigned fs_caps[]={CAP_CHOWN,CAP_DAC_OVERRIDE,CAP_DAC_READ_SEARCH,CAP_FOWNER,
        CAP_FSETID,CAP_LINUX_IMMUTABLE,CAP_MKNOD,CAP_MAC_OVERRIDE};
    for (unsigned bypass=0;bypass<2;++bypass) {
        struct md_identity fs=root;
        if (bypass) fs.caps.securebits=SECBIT_NO_SETUID_FIXUP;
        assert(md_identity_setfsuid(&fs,1234)==0 && fs.uid.fs==1234);
        for (unsigned i=0;i<sizeof(fs_caps)/sizeof(*fs_caps);++i)
            assert(md_identity_capable(&fs,fs_caps[i])==(int)bypass);
        assert(md_identity_capable(&fs,CAP_SYS_ADMIN) && fs.caps.permitted==root.caps.permitted);
        fs.caps.permitted&=~(UINT64_C(1)<<CAP_MKNOD);
        assert(md_identity_setfsuid(&fs,0)==1234);
        if (!bypass) assert(!md_identity_capable(&fs,CAP_MKNOD));
        for (unsigned i=0;i<sizeof(fs_caps)/sizeof(*fs_caps);++i)
            if (fs_caps[i]!=CAP_MKNOD) assert(md_identity_capable(&fs,fs_caps[i]));
    }
    struct md_identity setid=root;
    setid.uid.real=1000; setid.gid.real=1001;
    struct md_identity real=md_identity_access(&setid,0), effective=md_identity_access(&setid,1);
    assert(real.uid.fs==1000 && real.gid.fs==1001 && !real.caps.effective);
    assert(effective.uid.fs==0 && effective.gid.fs==0 && effective.caps.effective==MD_CAP_VALID);
    assert(setid.uid.fs==0 && setid.caps.effective==MD_CAP_VALID);
    setid=root; setid.caps.effective=0;
    real=md_identity_access(&setid,0); effective=md_identity_access(&setid,1);
    assert(real.caps.effective==MD_CAP_VALID && !effective.caps.effective);
    assert(root.caps.bounding==MD_CAP_VALID && root.caps.permitted==MD_CAP_VALID
        && root.caps.effective==MD_CAP_VALID && !root.caps.inheritable && !root.caps.ambient);
    assert(user.caps.bounding==MD_CAP_VALID && !user.caps.permitted && !user.caps.effective);
    uint64_t selected=(UINT64_C(1)<<CAP_NET_BIND_SERVICE)|(UINT64_C(1)<<CAP_NET_RAW)
        |(UINT64_C(1)<<CAP_MKNOD)|(UINT64_C(1)<<CAP_AUDIT_WRITE)|(UINT64_C(1)<<CAP_SETPCAP);
    assert(!md_identity_capset(&root,selected,selected,selected));
    assert(md_identity_capset(&user,selected,selected,0)==-EPERM);
    assert(md_identity_capset(&root,selected|(UINT64_C(1)<<CAP_SYS_ADMIN),selected,0)==-EPERM);
    unsigned long args[4]={CAP_NET_RAW};
    assert(md_identity_cap_prctl(&root,PR_CAPBSET_READ,args)==1);
    assert(!md_identity_cap_prctl(&root,PR_CAPBSET_DROP,args));
    assert(!md_identity_cap_prctl(&root,PR_CAPBSET_READ,args));
    assert(md_identity_capable(&root,CAP_NET_RAW));
    args[0]=MD_CAP_LAST+1;
    assert(md_identity_cap_prctl(&root,PR_CAPBSET_READ,args)==-EINVAL);
    args[0]=PR_CAP_AMBIENT_RAISE; args[1]=CAP_AUDIT_WRITE;
    assert(!md_identity_cap_prctl(&root,PR_CAP_AMBIENT,args));
    struct md_identity next=md_identity_copy(&root);
    assert(!md_identity_no_new_privs(&root,1));
    md_identity_exec(&root,&next);
    assert(!(next.caps.permitted&~selected));
    md_identity_release(&next);
    assert(!md_identity_enter_user_namespace(&user));
    assert(user.caps.effective==MD_CAP_VALID);
    assert(!md_identity_capable_in(&user,NULL,CAP_NET_ADMIN));
    md_identity_release(&root); md_identity_release(&user);
    puts("PASS capability ABI: namespace-local authority, reduction, bounding, ambient and no-new-privs");
}

static void identities(void) {
    struct md_identity root = md_identity_new(0, 0);
    uint32_t groups[] = {12, 34};
    assert(!md_identity_groups(&root, groups, 2));
    struct md_identity child = md_identity_copy(&root);
    assert(!md_identity_groups(&root, NULL, 0));
    assert(md_identity_group_count(&child) == 2 && md_identity_in_group(&child, 34));
    assert(!md_identity_setresuid(&child, 1000, 1000, 0));
    assert(md_identity_setfsuid(&child, 1234) == 1000 && child.uid.fs == 1000);
    assert(!md_identity_setuid(&child, 0) && child.uid.real == 1000 && child.uid.saved == 0);
    assert(!md_identity_setgid(&child, 2000));
    assert(!md_identity_setuid(&child, 1000));
    assert(md_identity_setuid(&child, 0) == -EPERM);
    assert(md_identity_setresgid(&child, 0, 0, 0) == -EPERM);
    assert(md_identity_setreuid(&child, UINT32_MAX, 0) == -EPERM);
    assert(md_identity_setfsuid(&child, UINT32_MAX) == 1000);
    assert(md_identity_no_new_privs(&child, 0) == -EINVAL);
    assert(!md_identity_no_new_privs(&child, 1));
    struct md_task_registry *registry = md_task_registry_create(); assert(registry);
    assert(!md_task_registry_publish(registry, 123, 120, &child, NULL));
    struct md_task_snapshot read;
    assert(md_task_registry_read(registry, 123, 119, &read) == -EPERM);
    assert(!md_task_registry_read(registry, 123, 120, &read));
    md_identity_release(&child);
    assert(read.identity.uid.effective == 1000 && md_identity_group_count(&read.identity) == 2);
    md_task_snapshot_release(&read);
    md_task_registry_forget(registry, 123);
    assert(md_task_registry_read(registry, 123, 120, &read) == -ESRCH);
    md_task_registry_destroy(registry); md_identity_release(&root);
}
struct publications {
    struct md_task_registry *registry;
    struct md_task_resources resources[2];
    struct md_identity identities[2];
    pthread_barrier_t start;
};
static void *publish_snapshots(void *opaque) {
    struct publications *p=opaque;
    /* EVENT_WAIT: reader joins the publication race; the runner bounds failure. */
    pthread_barrier_wait(&p->start);
    for(unsigned i=0;i<10000;++i) {
        unsigned k=i%2;
        assert(!md_task_registry_publish(p->registry,123,120,&p->identities[k],&p->resources[k]));
    }
    return NULL;
}
static void concurrent_snapshots(void) {
    struct publications p={.registry=md_task_registry_create()}; assert(p.registry);
    for(unsigned i=0;i<2;++i) {
        p.identities[i]=md_identity_new(i,i);
        p.resources[i].fs=md_domain_new(); assert(p.resources[i].fs);
        p.resources[i].mounts=md_mount_namespace_new(); assert(p.resources[i].mounts);
        p.resources[i].files=md_fd_origins_new(); assert(p.resources[i].files);
        struct md_fs_location root={.mount=i+1,.object="a"}; root.object[0]+=(char)i;
        assert(!md_domain_set_root(p.resources[i].fs,&root));
    }
    assert(!md_task_registry_publish(p.registry,123,120,&p.identities[0],&p.resources[0]));
    assert(!pthread_barrier_init(&p.start,NULL,2));
    pthread_t writer; assert(!pthread_create(&writer,NULL,publish_snapshots,&p));
    /* EVENT_WAIT: both participants start before exercising concurrent publication;
     * the fixture runner's deadline bounds a stuck barrier or join. */
    pthread_barrier_wait(&p.start);
    for(unsigned i=0;i<10000;++i) {
        struct md_task_snapshot snapshot;
        assert(!md_task_registry_read(p.registry,123,120,&snapshot));
        unsigned k=snapshot.identity.uid.effective; assert(k<2);
        assert(snapshot.fs==p.resources[k].fs && snapshot.mounts==p.resources[k].mounts && snapshot.root.mount==k+1
            && snapshot.root.object[0]=='a'+(char)k && snapshot.files==p.resources[k].files && !snapshot.cwd);
        md_task_snapshot_release(&snapshot);
    }
    assert(!pthread_join(writer,NULL));
    pthread_barrier_destroy(&p.start); md_task_registry_destroy(p.registry);
    for(unsigned i=0;i<2;++i) {
        md_identity_release(&p.identities[i]); md_domain_release(p.resources[i].fs);
        md_mount_namespace_release(p.resources[i].mounts);
        md_fd_origins_release(p.resources[i].files);
    }
    puts("PASS concurrent identity/root/mount/files snapshots are coherent and retained");
}
static int change(struct md_inode_store *s, int fd, unsigned op, unsigned mode, uint32_t uid, uint32_t gid) {
    struct md_fs_request q = {.operation=op,.directory={fd,-1},.mode=mode,.flags=MD_AT_EACCESS};
    if (op != MD_FS_ACCESS) q.flags = 0;
    q.attributes.uid=uid; q.attributes.gid=gid;
    return md_inode_metadata(s, &q);
}
static void account_file(struct md_inode_store *s, const char *path, const char *content) {
    int fd=md_inode_create(s,-1,path,0644); assert(fd>=0);
    size_t n=strlen(content); assert(write(fd,content,n)==(ssize_t)n); close(fd);
}
static struct md_fs_result root_call(struct md_filesystem *fs, pid_t task, unsigned op,
        int fd, const char *path, unsigned flags, char *data, size_t capacity) {
    struct md_fs_request q={.actor=task,.peer=120,.operation=op,.directory={fd,-1},
        .path={path,NULL},.flags=flags,.capacity=(unsigned)capacity};
    struct md_fs_result out;
    struct md_fs_output output={.data=data,.capacity=capacity};
    md_fs_execute(fs,&q,&out,&output); return out;
}
static void root_context(struct md_inode_store *s) {
    assert(!md_inode_mkdir(s,-1,"/jail",0755));
    assert(!md_inode_mkdir(s,-1,"/jail/sub",0755));
    account_file(s,"/jail/value","inside");
    assert(!md_inode_symlink(s,"/value",-1,"/jail/absolute"));
    struct md_identity ids=md_identity_new(0,0);
    struct md_guest_domain *parent=md_domain_new(); assert(parent);
    struct md_guest_domain *shared=md_domain_fork(parent,1); assert(shared==parent);
    struct md_guest_domain *sibling=md_domain_fork(parent,0); assert(sibling);
    struct md_filesystem fs={.store=s,.tasks=md_task_registry_create()}; assert(fs.tasks);
    assert(!md_fs_mounts_open(&fs,NULL,0));
    struct md_task_resources resources={.fs=parent,.mounts=fs.mounts};
    assert(!md_task_registry_publish(fs.tasks,123,120,&ids,&resources));
    resources.fs=shared;
    assert(!md_task_registry_publish(fs.tasks,124,120,&ids,&resources));
    resources.fs=sibling;
    assert(!md_task_registry_publish(fs.tasks,125,120,&ids,&resources));
    struct md_fs_result out=root_call(&fs,123,MD_FS_CHROOT,-1,"/jail",0,NULL,0);
    assert(!out.error && out.fd==-1);
    for(pid_t task=123;task<=125;task++) {
        out=root_call(&fs,task,MD_FS_OPEN,-1,"/absolute",O_RDONLY,NULL,0);
        if(task==125) assert(out.error==-ENOENT);
        else { assert(!out.error && out.fd>=0); close(out.fd); }
    }
    struct md_task_snapshot snap;
    assert(md_task_registry_read(fs.tasks,123,119,&snap)==-EPERM);
    assert(!md_task_registry_read(fs.tasks,123,120,&snap) && snap.root.object[0]);
    assert(snap.mounts==fs.mounts); md_task_snapshot_release(&snap);
    out=root_call(&fs,123,MD_FS_OPEN,-1,"/",O_RDONLY|O_DIRECTORY,NULL,0);
    assert(!out.error); int root=out.fd;
    struct stat st; assert(!md_inode_fstat(s,root,&st));
    char bytes[4096];
    out=root_call(&fs,123,MD_FS_GETDENTS,root,NULL,0,bytes,sizeof(bytes));
    assert(!out.error); unsigned dots=0;
    for(size_t p=0;p<out.size;) {
        struct md_directory_entry *entry=(void *)(bytes+p);
        if(!strcmp(entry->name,".") || !strcmp(entry->name,"..")) {
            assert(entry->inode==st.st_ino); dots++;
        }
        p+=entry->size;
    }
    assert(dots==2);
    assert(!md_inode_rename(s,-1,"/jail",-1,"/moved-jail",0));
    out=root_call(&fs,123,MD_FS_PATH,root,NULL,0,bytes,sizeof(bytes));
    assert(!out.error && !strcmp(bytes,"/"));
    out=root_call(&fs,124,MD_FS_OPEN,-1,"/sub/../../value",O_RDONLY,NULL,0);
    assert(!out.error); close(out.fd);
    struct md_guest_domain *split=md_domain_unshare_prepare(shared); assert(split && split!=shared);
    resources.fs=split;
    assert(!md_task_registry_publish(fs.tasks,124,120,&ids,&resources));
    md_domain_release(shared);
    out=root_call(&fs,124,MD_FS_CHROOT,-1,"/sub",0,NULL,0); assert(!out.error);
    out=root_call(&fs,123,MD_FS_OPEN,-1,"/value",O_RDONLY,NULL,0); assert(!out.error); close(out.fd);
    out=root_call(&fs,124,MD_FS_OPEN,-1,"/value",O_RDONLY,NULL,0); assert(out.error==-ENOENT);
    md_domain_release(parent); md_domain_release(split); md_domain_release(sibling);
    md_task_registry_destroy(fs.tasks); md_fs_mounts_close(&fs);
    md_identity_release(&ids); close(root);
}
static void retained_request_context(struct md_inode_store *s) {
    assert(!md_inode_mkdir(s,-1,"/context-root",0755));
    int fd=md_inode_create(s,-1,"/context-secret",0600); assert(fd>=0); close(fd);
    struct md_identity privileged=md_identity_new(0,0), user=md_identity_new(1000,1000);
    assert(!md_identity_setresuid(&privileged,1000,0,0));
    struct md_filesystem fs={.store=s,.tasks=md_task_registry_create()}; assert(fs.tasks);
    assert(!md_fs_mounts_open(&fs,NULL,0));
    struct md_task_resources original={.fs=md_domain_new(),.mounts=fs.mounts,.files=md_fd_origins_new()};
    struct md_task_resources replacement={.fs=md_domain_new(),.mounts=fs.mounts,.files=md_fd_origins_new()};
    assert(original.fs && original.files && replacement.fs && replacement.files);
    assert(!md_task_registry_publish(fs.tasks,123,120,&privileged,&original));
    struct md_fs_context context;
    assert(md_fs_context_begin(&fs,123,119,&context)==-EPERM && !context.filesystem);
    md_fs_context_end(&context);
    assert(!md_fs_context_begin(&fs,123,120,&context));
    assert(context.task.fs==original.fs && context.task.files==original.files);
    struct md_fs_request q={.actor=123,.peer=120,.operation=MD_FS_ACCESS,
        .directory={-1,-1},.path={"/context-secret",NULL},.mode=R_OK};
    struct md_fs_result out;
    md_fs_context_execute(&context,&q,&out,NULL); assert(out.error==-EACCES);
    q.flags=MD_AT_EACCESS;
    md_fs_context_execute(&context,&q,&out,NULL); assert(!out.error);
    assert(context.task.identity.uid.fs==0 && context.task.identity.uid.real==1000);
    assert(!md_task_registry_publish(fs.tasks,123,120,&user,&replacement));
    md_domain_release(original.fs); md_fd_origins_release(original.files);
    md_fs_execute(&fs,&q,&out,NULL); assert(out.error==-EACCES);
    md_task_registry_forget(fs.tasks,123);
    md_fs_context_execute(&context,&q,&out,NULL); assert(!out.error);
    assert(s->identity && s->identity->uid.fs==0 && !s->root_id && !fs.native_root);
    q.actor=124; md_fs_context_execute(&context,&q,&out,NULL); assert(out.error==-EPERM);
    q.actor=123; q.peer=0;
    md_fs_context_execute(&context,&q,&out,NULL); assert(out.error==-EPERM);
    q.peer=120; q.flags=q.mode=0; q.operation=MD_FS_CHROOT; q.path[0]="/context-root";
    assert(!md_task_registry_publish(fs.tasks,123,120,&user,&replacement));
    md_fs_context_execute(&context,&q,&out,NULL); assert(!out.error);
    struct md_fs_location location;
    struct md_fs_anchor *cwd=NULL;
    md_domain_paths(context.task.fs,&location,&cwd); assert(location.object[0] && !cwd);
    md_domain_paths(replacement.fs,&location,&cwd); assert(!location.object[0] && !location.mount && !cwd);
    md_fs_context_end(&context);
    md_fs_context_execute(&context,&q,&out,NULL); assert(out.error==-EINVAL);
    md_fs_context_end(&context);
    md_task_registry_destroy(fs.tasks); md_fs_mounts_close(&fs);
    md_domain_release(replacement.fs); md_fd_origins_release(replacement.files);
    md_identity_release(&privileged); md_identity_release(&user);
    puts("PASS retained request identity/resources, actor binding, root publication after task replacement and cleanup");
}
static void accounts(struct md_inode_store *s) {
    struct md_filesystem fs={.store=s}; struct md_identity selected={0};
    assert(!md_guest_user_resolve(&fs,"123:456",&selected));
    assert(selected.uid.real==123 && selected.gid.real==456 && !md_identity_group_count(&selected));
    md_identity_release(&selected);
    assert(!md_inode_mkdir(s,-1,"/etc",0755));
    account_file(s,"/etc/passwd","root:x:0:0:Root:/root:/bin/sh\nalice:x:1000:100:Alice:/home/alice:/bin/sh\n");
    account_file(s,"/etc/group","primary:x:100:\nshared:x:200:alice\nother:x:300:bob,alice\n");
    const char *forms[]={"alice","1000","alice:shared","1000:200","1000:shared","root"};
    for (unsigned i=0;i<sizeof(forms)/sizeof(*forms);i++) {
        assert(!md_guest_user_resolve(&fs,forms[i],&selected));
        assert(selected.uid.real==(i==5 ? 0U : 1000U));
        assert(selected.gid.real==(i==5 ? 0U : i<2 ? 100U : 200U));
        assert(md_identity_group_count(&selected)==(i<2 ? 2U : 0U));
        if (i<2) assert(md_identity_in_group(&selected,200) && md_identity_in_group(&selected,300));
        md_identity_release(&selected);
    }
    assert(md_guest_user_resolve(&fs,"missing",&selected)==-ENOENT);
    assert(md_guest_user_resolve(&fs,"alice:missing",&selected)==-ENOENT);
    assert(md_guest_user_resolve(&fs,"4294967295",&selected)==-EINVAL);
    assert(md_guest_user_resolve(&fs,"1000:",&selected)==-EINVAL);
}
int main(int argc, char **argv) {
    assert(argc == 2); identities(); capabilities(); file_permissions(); concurrent_snapshots(); umask(0);
    struct md_inode_store *s = NULL;
    assert(!md_inode_store_open(argv[1], 1, &s));
    struct md_identity root=md_identity_new(0,0), user=md_identity_new(1000,1000), other=md_identity_new(1001,1001);
    s->identity=&root;
    int base=md_inode_open(s,-1,"/",O_PATH|O_DIRECTORY,0); assert(base>=0);
    assert(!change(s,base,MD_FS_CHMOD,0755,0,0));
    accounts(s);
    root_context(s);
    retained_request_context(s);
    assert(!md_inode_mkdir(s,-1,"/tmp",01777));
    int fd=md_inode_create(s,-1,"/tmp/owned",0640); assert(fd>=0);
    assert(write(fd,"data",4)==4);
    assert(!change(s,fd,MD_FS_CHOWN,0,1000,1000));
    struct stat st,native; assert(!md_inode_fstat(s,fd,&st)); assert(!fstat(fd,&native));
    assert(st.st_uid==1000 && st.st_gid==1000 && (st.st_mode&07777)==0640);
    assert(native.st_uid==getuid() && (native.st_mode&06000)==0);
    assert(!md_inode_link(s,-1,"/tmp/owned",-1,"/tmp/alias",0));
    s->identity=&user;
    assert(!change(s,fd,MD_FS_CHMOD,0644,0,0));
    int noatime=md_inode_open(s,-1,"/tmp/owned",O_RDONLY|O_NOATIME,0);
    assert(noatime>=0); close(noatime);
    noatime=md_inode_reopen(s,fd,O_RDONLY|O_NOATIME,0);
    assert(noatime>=0); close(noatime);
    s->identity=&other;
    int readable=md_inode_open(s,-1,"/tmp/owned",O_RDONLY,0);
    assert(readable>=0); close(readable);
    assert(md_inode_open(s,-1,"/tmp/owned",O_RDONLY|O_NOATIME,0)==-EPERM);
    assert(md_inode_reopen(s,fd,O_RDONLY|O_NOATIME,0)==-EPERM);
    s->identity=&root;
    noatime=md_inode_reopen(s,fd,O_RDONLY|O_NOATIME,0);
    assert(noatime>=0); close(noatime);
    s->identity=&user;
    assert(!change(s,fd,MD_FS_CHMOD,0600,0,0));
    assert(change(s,fd,MD_FS_CHOWN,0,0,0)==-EPERM);
    s->identity=&other;
    assert(md_inode_open(s,-1,"/tmp/owned",O_RDONLY,0)==-EACCES);
    assert(md_inode_unlink(s,-1,"/tmp/owned",0)==-EPERM);
    assert(md_inode_rename(s,-1,"/tmp/owned",-1,"/tmp/stolen",0)==-EPERM);
    assert(change(s,fd,MD_FS_CHMOD,0777,0,0)==-EPERM);
    s->identity=&root;
    assert(!change(s,fd,MD_FS_CHMOD,0000,0,0));
    int opened=md_inode_open(s,-1,"/tmp/owned",O_RDONLY,0); assert(opened>=0); close(opened);
    assert(change(s,fd,MD_FS_ACCESS,X_OK,0,0)==-EACCES);
    assert(!change(s,fd,MD_FS_CHMOD,04755,0,0));
    assert(!md_inode_stat(s,-1,"/tmp/alias",0,&st) && (st.st_mode&07777)==04755);
    assert(!change(s,fd,MD_FS_CHOWN,0,1000,1000));
    assert(!md_inode_fstat(s,fd,&st) && !(st.st_mode&06000));
    assert(!md_inode_mkdir(s,-1,"/shared",02777));
    int shared=md_inode_open(s,-1,"/shared",O_PATH|O_DIRECTORY,0); assert(shared>=0);
    assert(!change(s,shared,MD_FS_CHOWN,0,0,2000));
    s->identity=&user;
    uint32_t group=2000; assert(!md_identity_groups(&user,&group,1));
    assert(!md_inode_mkdir(s,-1,"/shared/child",0750));
    assert(!md_inode_stat(s,-1,"/shared/child",0,&st));
    assert(st.st_gid==2000 && (st.st_mode&07777)==02750);
    s->identity=&other;
    assert(!md_inode_mkdir(s,-1,"/shared/nonmember",0750));
    assert(!md_inode_stat(s,-1,"/shared/nonmember",0,&st));
    assert(st.st_gid==2000 && (st.st_mode&07777)==02750);
    s->identity=NULL;
    close(shared); close(fd); close(base); md_inode_store_close(s);
    assert(!md_inode_store_open(argv[1],0,&s));
    assert(!md_inode_stat(s,-1,"/shared/child",0,&st) && st.st_uid==1000 && st.st_gid==2000);
    md_inode_store_close(s); md_identity_release(&user);
    puts("credentials: identity, inheritance, authority, metadata, hardlinks, sticky, setgid, persistence PASS");
}
