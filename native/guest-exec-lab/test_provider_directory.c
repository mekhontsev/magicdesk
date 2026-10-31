#define _GNU_SOURCE
#include "provider_tree.h"
#include "directory_record.h"
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static _Thread_local unsigned long reads;
struct dirent *__real_readdir(DIR *);
struct dirent *__wrap_readdir(DIR *dir) {
    ++reads; return __real_readdir(dir);
}
static int visible(const char *path) { return !strstr(path,"hidden"); }
static const struct md_provider_tree_ops ops={.lower_visible=visible};
static void touch(int parent,const char *name) {
    int fd=openat(parent,name,O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC,0600);
    assert(fd>=0 && !close(fd));
}
static unsigned scan(struct md_provider_tree *tree,int fd,size_t capacity) {
    char data[4096]; unsigned count=0,seen[1024]={0};
    assert(capacity<=sizeof(data));
    for (;;) {
        ssize_t n=md_provider_tree_getdents(tree,fd,data,capacity); assert(n>=0);
        if (!n) break;
        for (size_t at=0;at<(size_t)n;) {
            struct md_directory_entry entry;
            assert(md_directory_record_read(data+at,(size_t)n-at,&entry));
            const char *name=data+at+offsetof(struct md_directory_entry,name);
            assert(strcmp(name,".shroot-transient") && !strstr(name,"hidden"));
            unsigned index;
            if (sscanf(name,"item-%u",&index)==1) {
                assert(index<1024 && !seen[index]++); ++count;
            }
            at+=entry.size;
        }
    }
    return count;
}
static void *independent_scan(void *data) {
    struct md_provider_tree *tree=data;
    int fd=openat(md_provider_tree_root(tree),".",O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(fd>=0);
    assert(scan(tree,fd,96)==1024 && !close(fd)); return NULL;
}
void md_test_provider_directory(const char *path) {
    assert(!mkdir(path,0700));
    int parent=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(parent>=0);
    assert(!mkdirat(parent,"lower",0700));
    int lower=openat(parent,"lower",O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(lower>=0);
    struct md_provider_tree *tree=md_provider_tree_new(parent,lower,NULL,0,&ops,NULL); assert(tree);
    int upper=md_provider_tree_root(tree);
    for (unsigned i=0;i<1024;++i) {
        char name[32]; snprintf(name,sizeof(name),"item-%04u",i); touch(lower,name);
        if (!(i%8)) touch(upper,name);
    }
    touch(lower,"hidden");
    int fd=openat(upper,".",O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(fd>=0);
    for (unsigned capacity=96;capacity<=4096;capacity=4096) {
        assert(!lseek(fd,0,SEEK_SET)); reads=0;
        struct timespec start,end; assert(!clock_gettime(CLOCK_MONOTONIC,&start));
        assert(scan(tree,fd,capacity)==1024);
        assert(!clock_gettime(CLOCK_MONOTONIC,&end));
        printf("PROVIDER_DIRECTORY capacity=%u entries=1024 reads=%lu elapsedNs=%lld\n",capacity,reads,
            (long long)(end.tv_sec-start.tv_sec)*1000000000+end.tv_nsec-start.tv_nsec);
        assert(reads<4096);
        if (capacity==4096) break;
    }
    /* Every published cookie is sufficient without a warm enumerator, including
     * shared dup positions and rollback after a rejected output publication. */
    assert(!lseek(fd,0,SEEK_SET));
    char first[96],expected[96],actual[96];
    assert(md_provider_tree_getdents(tree,fd,first,sizeof(first))>0);
    off_t cookie=lseek(fd,0,SEEK_CUR); assert(cookie>0);
    ssize_t n=md_provider_tree_getdents(tree,fd,expected,sizeof(expected)); assert(n>0);
    int copy=dup(fd); assert(copy>=0 && lseek(copy,cookie,SEEK_SET)==cookie);
    assert(md_provider_tree_getdents(tree,copy,actual,sizeof(actual))==n && !memcmp(actual,expected,(size_t)n));
    struct md_provider_tree *cold=md_provider_tree_import(upper,lower,&ops); assert(cold);
    assert(lseek(fd,cookie,SEEK_SET)==cookie);
    assert(md_provider_tree_getdents(cold,fd,actual,sizeof(actual))==n && !memcmp(actual,expected,(size_t)n));
    assert(lseek(fd,cookie,SEEK_SET)==cookie);
    assert(md_provider_tree_getdents(tree,fd,actual,1)==-EINVAL && lseek(fd,0,SEEK_CUR)==cookie);
    /* Changing the upper layer invalidates both shadowing and ordinal hints. */
    touch(upper,"added");
    assert(lseek(fd,cookie,SEEK_SET)==cookie);
    n=md_provider_tree_getdents(tree,fd,expected,sizeof(expected)); assert(n>0);
    assert(lseek(fd,cookie,SEEK_SET)==cookie);
    assert(md_provider_tree_getdents(cold,fd,actual,sizeof(actual))==n && !memcmp(actual,expected,(size_t)n));
    assert(!unlinkat(upper,"added",0));
    /* Each emitted entry, not just the end of a buffer, is a seekable cookie. */
    assert(!lseek(fd,0,SEEK_SET));
    assert(md_provider_tree_getdents(tree,fd,first,sizeof(first))>0);
    struct md_directory_entry entry;
    assert(md_directory_record_read(first,sizeof(first),&entry));
    cookie=entry.next;
    assert(lseek(fd,cookie,SEEK_SET)==cookie);
    n=md_provider_tree_getdents(tree,fd,expected,sizeof(expected)); assert(n>0);
    assert(lseek(fd,cookie,SEEK_SET)==cookie);
    assert(md_provider_tree_getdents(cold,fd,actual,sizeof(actual))==n && !memcmp(actual,expected,(size_t)n));
    pthread_t threads[2];
    for (unsigned i=0;i<2;++i) assert(!pthread_create(&threads[i],NULL,independent_scan,tree));
    for (unsigned i=0;i<2;++i) assert(!pthread_join(threads[i],NULL));
    /* Eviction cannot alter a retained shared description's ordinal. */
    for (unsigned i=0;i<24;++i) {
        char name[32]; snprintf(name,sizeof(name),"other-%u",i); assert(!mkdirat(upper,name,0700));
    }
    assert(!lseek(fd,0,SEEK_SET) && md_provider_tree_getdents(tree,fd,first,sizeof(first))>0);
    cookie=lseek(fd,0,SEEK_CUR); assert(cookie>0);
    for (unsigned i=0;i<24;++i) {
        char name[32]; snprintf(name,sizeof(name),"other-%u",i);
        int other=openat(upper,name,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(other>=0);
        assert(md_provider_tree_getdents(tree,other,actual,24)==24 && !close(other));
    }
    assert(lseek(fd,cookie,SEEK_SET)==cookie);
    n=md_provider_tree_getdents(tree,fd,expected,sizeof(expected)); assert(n>0);
    assert(lseek(fd,cookie,SEEK_SET)==cookie);
    assert(md_provider_tree_getdents(cold,fd,actual,sizeof(actual))==n && !memcmp(actual,expected,(size_t)n));
    md_provider_tree_release(cold);
    assert(!close(copy) && !close(fd));
    for (unsigned i=0;i<1024;++i) {
        char name[32]; snprintf(name,sizeof(name),"item-%04u",i); assert(!unlinkat(lower,name,0));
    }
    assert(!unlinkat(lower,"hidden",0) && !close(lower));
    md_provider_tree_release(tree);
    assert(!unlinkat(parent,"lower",AT_REMOVEDIR) && !close(parent) && !rmdir(path));
    puts("PASS provider directory: linear paging, shadowing, visibility, dup, rollback, mutation, eviction, concurrent and cold cookies");
}
