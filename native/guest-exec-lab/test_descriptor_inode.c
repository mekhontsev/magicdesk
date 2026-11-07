#define _GNU_SOURCE
#include "descriptor_inode.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <sys/wait.h>
#include <unistd.h>

static struct stat snapshot(struct md_fd_object *object, const struct md_identity *id) {
    struct stat value={.st_ino=901,.st_dev=17,.st_size=31,.st_mode=S_IFIFO|0600};
    assert(!md_descriptor_inode_stat(object,id,&value));
    assert(value.st_ino==901 && value.st_dev==17 && value.st_size==31 && S_ISFIFO(value.st_mode));
    return value;
}
void test_descriptor_inode(void) {
    struct md_identity root=md_identity_new(0,0), user=md_identity_new(21,22), peer=md_identity_new(31,32);
    struct md_fd_object *object=md_descriptor_inode_new(&user,S_IFIFO|0600); assert(object);
    struct stat value=snapshot(object,&root);
    assert(value.st_uid==21 && value.st_gid==22 && (value.st_mode&07777)==0600);
    assert(md_descriptor_inode_chown(object,&peer,31,32)==-EPERM);
    assert(md_descriptor_inode_chmod(object,&peer,0777)==-EPERM);
    assert(md_descriptor_inode_chown(object,&user,31,22)==-EPERM);
    assert(md_descriptor_inode_chown(object,&user,UINT32_MAX,32)==-EPERM);
    uint32_t group=32; assert(!md_identity_groups(&user,&group,1));
    assert(!md_descriptor_inode_chown(object,&user,UINT32_MAX,32));
    assert(!md_descriptor_inode_chmod(object,&user,06700));
    value=snapshot(object,&root);
    assert(value.st_uid==21 && value.st_gid==32 && (value.st_mode&07777)==06700);
    assert(!md_descriptor_inode_chown(object,&user,UINT32_MAX,UINT32_MAX));
    value=snapshot(object,&root);
    assert((value.st_mode&07777)==02700 && value.st_ctim.tv_sec>0);
    assert(!md_descriptor_inode_chmod(object,&user,06710));
    assert(!md_descriptor_inode_chown(object,&root,42,43));
    value=snapshot(object,&root);
    assert(value.st_uid==42 && value.st_gid==43 && (value.st_mode&07777)==0710);
    assert(!md_identity_enter_user_namespace(&user));
    assert(md_descriptor_inode_chown(object,&user,0,0)==-EINVAL);
    assert(md_descriptor_inode_chmod(object,&user,0777)==-EPERM);
    value=snapshot(object,&user);
    assert(value.st_uid==MD_USER_OVERFLOW_ID && value.st_gid==MD_USER_OVERFLOW_ID);
    struct md_fd_object *same=md_fd_object_retain(object);
    md_fd_object_release(object);
    assert(!md_descriptor_inode_chmod(same,&root,0600));
    value=snapshot(same,&root); assert((value.st_mode&07777)==0600);
    int ends[2], other[2], metadata=-1;
    assert(!pipe2(ends,O_CLOEXEC) && !pipe2(other,O_CLOEXEC));
    int directory=open(".",O_PATH|O_DIRECTORY|O_CLOEXEC); assert(directory>=0);
    int error=md_descriptor_inode_export(same,ends[0],directory,&metadata);
    if (error) fprintf(stderr,"anonymous metadata export error=%d\n",error);
    assert(!error); close(directory);
    struct md_fd_object *received=NULL;
    assert(md_descriptor_inode_import(metadata,other[0],&received)==-EPROTO && !received);
    assert(!md_descriptor_inode_import(metadata,ends[1],&received));
    assert(!md_descriptor_inode_chown(received,&root,81,82));
    value=snapshot(same,&root); assert(value.st_uid==81 && value.st_gid==82);
    assert(!md_descriptor_inode_chmod(same,&root,0640));
    value=snapshot(received,&root); assert((value.st_mode&07777)==0640);
    md_fd_object_release(received); received=NULL;
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!md_descriptor_inode_import(metadata,ends[0],&received));
        for (unsigned i=0;i<200;++i)
            assert(!md_descriptor_inode_chown(received,&root,100+i,100+i));
        md_fd_object_release(received); _exit(0);
    }
    int status;
    /* EVENT_WAIT: the independent writer exits; the fixture alarm bounds failure. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    value=snapshot(same,&root); assert(value.st_uid==299 && value.st_gid==299);
    md_fd_object_release(same); same=NULL;
    assert(!md_descriptor_inode_import(metadata,ends[0],&received));
    close(metadata); close(ends[1]); close(other[0]); close(other[1]);
    char byte; assert(!read(ends[0],&byte,1)); close(ends[0]);
    value=snapshot(received,&root); assert(value.st_uid==299 && value.st_gid==299);
    md_fd_object_release(received);
    md_fd_object_release(same);
    md_identity_release(&root); md_identity_release(&user); md_identity_release(&peer);
    puts("PASS anonymous descriptor metadata: ownership, capabilities, mode bits and namespace IDs");
}
