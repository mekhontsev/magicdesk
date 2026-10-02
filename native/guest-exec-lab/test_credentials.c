#define _GNU_SOURCE
#include "credential_registry.h"
#include "inode_internal.h"
#include "fs_operation.h"
#include "fs_engine.h"
#include "guest_accounts.h"
#include "linux_abi.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

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
    struct md_credentials *registry = md_credentials_create(); assert(registry);
    assert(!md_credentials_publish(registry, 123, 120, &child));
    struct md_identity read;
    assert(md_credentials_read(registry, 123, 119, &read) == -EPERM);
    assert(!md_credentials_read(registry, 123, 120, &read));
    md_identity_release(&child);
    assert(read.uid.effective == 1000 && md_identity_group_count(&read) == 2);
    md_identity_release(&read);
    md_credentials_forget(registry, 123);
    assert(md_credentials_read(registry, 123, 120, &read) == -ESRCH);
    md_credentials_destroy(registry); md_identity_release(&root);
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
    assert(argc == 2); identities(); umask(0);
    struct md_inode_store *s = NULL;
    assert(!md_inode_store_open(argv[1], 1, &s));
    struct md_identity root=md_identity_new(0,0), user=md_identity_new(1000,1000), other=md_identity_new(1001,1001);
    s->identity=&root;
    int base=md_inode_open(s,-1,"/",O_PATH|O_DIRECTORY,0); assert(base>=0);
    assert(!change(s,base,MD_FS_CHMOD,0755,0,0));
    accounts(s);
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
