#define _GNU_SOURCE
#include "image_layer.h"
#include "image_maintenance.h"
#include "image_publish.h"
#include "image_backup.h"
#include "inode_internal.h"
#include "fs_operation.h"
#include <archive.h>
#include <archive_entry.h>
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/file.h>

static int count_work(void *value) { ++*(unsigned long long *)value; return 0; }
static void entry_cap(struct archive *a,const char *name,const char *link,const char *symlink,unsigned cap) {
    struct archive_entry *e=archive_entry_new(); assert(e);
    archive_entry_set_pathname(e,name);
    archive_entry_set_filetype(e,symlink ? S_IFLNK : name[strlen(name)-1]=='/' ? S_IFDIR : S_IFREG);
    archive_entry_set_perm(e,0600);
    if (link) archive_entry_set_hardlink(e,link);
    if (symlink) archive_entry_set_symlink(e,symlink);
    uint32_t capability[]={0x02000001,cap,0,0,0};
    if (cap) archive_entry_xattr_add_entry(e,"security.capability",capability,sizeof(capability));
    assert(archive_write_header(a,e)==ARCHIVE_OK);
    archive_entry_free(e);
}
static void entry(struct archive *a,const char *name,const char *link,const char *symlink) {
    entry_cap(a,name,link,symlink,0);
}
static unsigned long long chain(const char *base,unsigned count,int cycle,int symlinks) {
    char path[PATH_MAX]; snprintf(path,sizeof(path),"%s/%u-%d-%d",base,count,cycle,symlinks);
    struct md_inode_store *s=NULL; assert(!md_inode_store_open(path,1,&s));
    char tar[PATH_MAX]; snprintf(tar,sizeof(tar),"%s/archive",base);
    int fd=open(tar,O_CREAT|O_TRUNC|O_RDWR|O_CLOEXEC,0600); assert(fd>=0);
    struct archive *a=archive_write_new(); assert(a);
    assert(archive_write_set_format_pax_restricted(a)==ARCHIVE_OK);
    assert(archive_write_open_fd(a,fd)==ARCHIVE_OK);
    for (unsigned i=0;i<count;++i) {
        char name[32],target[32];
        snprintf(name,sizeof(name),"link-%u",i);
        snprintf(target,sizeof(target),"link-%u",cycle && i+1==count ? 0 : i+1);
        entry(a,name,target,NULL);
    }
    if (!cycle) {
        char name[32]; snprintf(name,sizeof(name),"link-%u",count);
        entry(a,name,NULL,symlinks ? "destination" : NULL);
    }
    assert(archive_write_close(a)==ARCHIVE_OK); archive_write_free(a);
    uint64_t bytes=0,entries=0; unsigned long long work=0;
    sqlite3_progress_handler(s->db,100,count_work,&work);
    int r=md_image_rootfs(s,fd,&bytes,&entries);
    sqlite3_progress_handler(s->db,0,NULL,NULL);
    assert(r==(cycle ? -ENOENT : 0)); close(fd);
    if (!cycle) {
        struct stat first,last; char name[32]; snprintf(name,sizeof(name),"link-%u",count);
        assert(!md_inode_stat(s,-1,"link-0",AT_SYMLINK_NOFOLLOW,&first));
        assert(!md_inode_stat(s,-1,name,AT_SYMLINK_NOFOLLOW,&last));
        assert(first.st_ino==last.st_ino && first.st_nlink==count+1);
        assert((first.st_mode&S_IFMT)==(symlinks ? S_IFLNK : S_IFREG));
    } else {
        struct stat st; assert(md_inode_stat(s,-1,"link-0",0,&st)==-ENOENT);
    }
    md_inode_store_close(s); return work*100;
}
static void dependency_paths(const char *base) {
    for (unsigned test=0;test<3;++test) {
        char path[PATH_MAX]; snprintf(path,sizeof(path),"%s/paths-%u",base,test);
        struct md_inode_store *s=NULL; assert(!md_inode_store_open(path,1,&s));
        char tar[PATH_MAX]; snprintf(tar,sizeof(tar),"%s/archive",base);
        int fd=open(tar,O_CREAT|O_TRUNC|O_RDWR|O_CLOEXEC,0600); assert(fd>=0);
        struct archive *a=archive_write_new(); assert(a);
        assert(archive_write_set_format_pax_restricted(a)==ARCHIVE_OK);
        assert(archive_write_open_fd(a,fd)==ARCHIVE_OK);
        entry(a,"result","alias/file",NULL);
        entry(a,"second","result",NULL);
        entry(a,"alias","sym",NULL);
        entry(a,"sym",NULL,"dir");
        entry(a,"dir/",NULL,NULL);
        if (test!=1) entry(a,"dir/file",NULL,NULL);
        if (test==2) entry(a,"forbidden","dir",NULL);
        assert(archive_write_close(a)==ARCHIVE_OK); archive_write_free(a);
        uint64_t bytes=0,entries=0;
        assert(md_image_rootfs(s,fd,&bytes,&entries)==(test==1 ? -ENOENT : test==2 ? -EPERM : 0));
        close(fd);
        struct stat st;
        if (test) assert(md_inode_stat(s,-1,"result",0,&st)==-ENOENT);
        else {
            assert(!md_inode_stat(s,-1,"result",0,&st) && st.st_nlink==3);
            struct stat alias; assert(!md_inode_stat(s,-1,"alias",AT_SYMLINK_NOFOLLOW,&alias));
            assert(S_ISLNK(alias.st_mode) && alias.st_nlink==2);
        }
        md_inode_store_close(s);
    }
    puts("PASS image hardlinks: missing intermediate aliases, branching, absent target and directory rejection");
}
static void metadata_order(const char *base) {
    char path[PATH_MAX]; snprintf(path,sizeof(path),"%s/order",base);
    struct md_inode_store *s=NULL; assert(!md_inode_store_open(path,1,&s));
    char tar[PATH_MAX]; snprintf(tar,sizeof(tar),"%s/archive",base);
    int fd=open(tar,O_CREAT|O_TRUNC|O_RDWR|O_CLOEXEC,0600); assert(fd>=0);
    struct archive *a=archive_write_new(); assert(a);
    assert(archive_write_set_format_pax_restricted(a)==ARCHIVE_OK);
    assert(archive_write_open_fd(a,fd)==ARCHIVE_OK);
    entry_cap(a,"first","last",NULL,1);
    entry_cap(a,"middle","file",NULL,2);
    entry_cap(a,"last","file",NULL,4);
    entry(a,"file",NULL,NULL);
    assert(archive_write_close(a)==ARCHIVE_OK); archive_write_free(a);
    uint64_t bytes=0,entries=0; assert(!md_image_rootfs(s,fd,&bytes,&entries)); close(fd);
    fd=md_inode_open(s,-1,"file",O_RDONLY,0); assert(fd>=0);
    uint32_t capability[5];
    struct md_fs_request q={.operation=MD_FS_GETCAP,.directory={fd,-1}};
    assert(md_inode_capability(s,&q,capability,sizeof(capability))==sizeof(capability));
    assert(capability[1]==1); /* first resolves in pass 1, after middle and last. */
    close(fd); md_inode_store_close(s);
    puts("PASS image hardlinks preserve pass/entry capability ordering");
}
static void library_relocation(const char *base) {
    char root[PATH_MAX],target[PATH_MAX],images[PATH_MAX],instances[PATH_MAX],layers[PATH_MAX];
    char image[PATH_MAX],instance[PATH_MAX];
    assert(snprintf(root,sizeof(root),"%s/library",base)<(int)sizeof(root));
    assert(snprintf(target,sizeof(target),"%s/relocated-library",base)<(int)sizeof(target));
    assert(!mkdir(root,0700));
    assert(snprintf(images,sizeof(images),"%s/images",root)<(int)sizeof(images));
    assert(snprintf(instances,sizeof(instances),"%s/instances",root)<(int)sizeof(instances));
    assert(snprintf(layers,sizeof(layers),"%s/layers",root)<(int)sizeof(layers));
    assert(!mkdir(images,0700) && !mkdir(instances,0700) && !mkdir(layers,0700));
    assert(snprintf(image,sizeof(image),"%s/base",images)<(int)sizeof(image));
    assert(snprintf(instance,sizeof(instance),"%s/child",instances)<(int)sizeof(instance));
    struct md_inode_store *a=NULL,*b=NULL;
    assert(!md_inode_store_open(image,1,&a));
    int fd=md_inode_open(a,MD_INODE_ROOT,"/file",O_CREAT|O_RDWR,0600); assert(fd>=0);
    assert(write(fd,"data",4)==4); close(fd);
    assert(!md_inode_store_seal(a));
    struct md_image_publish publication;
    assert(!md_image_publish_begin(instance,&publication));
    assert(!md_inode_store_open(publication.path,1,&b));
    assert(!md_inode_snapshot(a,b)); md_inode_store_close(b); b=NULL;
    assert(!md_image_publish_commit(&publication)); md_image_publish_close(&publication);
    assert(!md_inode_store_open(instance,0,&b));
    fd=md_inode_open(b,MD_INODE_ROOT,"/file",O_RDONLY,0); assert(fd>=0);
    struct stat body; assert(!fstat(fd,&body)); close(fd);
    /* Model an installed absolute-source store before the layout update. */
    char *sql=sqlite3_mprintf("UPDATE sources SET path='%q/objects'",image); assert(sql);
    assert(sqlite3_exec(b->db,sql,NULL,NULL,NULL)==SQLITE_OK); sqlite3_free(sql);
    assert(md_image_relocate_library(root,target)==-EBUSY);
    assert(!access(root,F_OK) && access(target,F_OK));
    md_inode_store_close(a); md_inode_store_close(b);
    assert(!mkdir(target,0700));
    assert(md_image_relocate_library(root,target)==-EEXIST); assert(!rmdir(target));
    int lease=open(image,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(lease>=0);
    assert(!flock(lease,LOCK_SH|LOCK_NB));
    assert(md_image_relocate_library(root,target)==-EBUSY); close(lease);
    char objects[PATH_MAX],external[PATH_MAX];
    assert(snprintf(objects,sizeof(objects),"%s/objects",image)<(int)sizeof(objects));
    assert(snprintf(external,sizeof(external),"%s/external-objects",base)<(int)sizeof(external));
    assert(!rename(objects,external) && !symlink(external,objects));
    assert(md_image_relocate_library(root,target)==-EXDEV);
    assert(!access(root,F_OK) && access(target,F_OK));
    assert(!unlink(objects) && !rename(external,objects));
    assert(!md_image_relocate_library(root,target));
    assert(access(root,F_OK) && !access(target,F_OK));
    assert(snprintf(instance,sizeof(instance),"%s/instances/child",target)<(int)sizeof(instance));
    assert(!md_inode_store_open(instance,0,&b));
    fd=md_inode_open(b,MD_INODE_ROOT,"/file",O_RDONLY,0); assert(fd>=0);
    char value[4]; struct stat moved;
    assert(read(fd,value,4)==4 && !memcmp(value,"data",4) && !fstat(fd,&moved)); close(fd);
    assert(body.st_ino==moved.st_ino && body.st_dev==moved.st_dev);
    sqlite3_stmt *q=NULL;
    assert(sqlite3_prepare_v2(b->db,"SELECT path FROM sources",-1,&q,NULL)==SQLITE_OK);
    assert(sqlite3_step(q)==SQLITE_ROW && *(const char *)sqlite3_column_text(q,0)!='/');
    sqlite3_finalize(q); md_inode_store_close(b);
    char archive[PATH_MAX],restored[PATH_MAX];
    assert(snprintf(archive,sizeof(archive),"%s/backup.tar.zst",base)<(int)sizeof(archive));
    assert(snprintf(restored,sizeof(restored),"%s/restored",base)<(int)sizeof(restored));
    assert(!md_image_backup(instance,archive));
    assert(!md_image_archive_import(archive,restored,1));
    assert(!md_inode_store_open(restored,0,&b));
    fd=md_inode_open(b,MD_INODE_ROOT,"/file",O_RDONLY,0); assert(fd>=0);
    assert(read(fd,value,4)==4 && !memcmp(value,"data",4)); close(fd);
    md_inode_store_close(b);
    puts("PASS offline library relocation retains bodies; live/occupied destinations and escaping sources are refused");
    puts("PASS relocated library backup and independent restore publish regular archives and directory stores");
}

int main(int argc,char **argv) {
    assert(argc==2 && !mkdir(argv[1],0700));
    unsigned long long small=chain(argv[1],128,0,0),large=chain(argv[1],512,0,0);
    printf("hardlink SQL work: 128=%llu 512=%llu steps\n",small,large); fflush(stdout);
    assert(large<small*6);
    chain(argv[1],4096,0,1); chain(argv[1],4096,1,0);
    dependency_paths(argv[1]);
    metadata_order(argv[1]);
    library_relocation(argv[1]);
    puts("PASS image hardlinks: linear dependency resolution, deep symlink aliases and cycle rollback");
    return 0;
}
