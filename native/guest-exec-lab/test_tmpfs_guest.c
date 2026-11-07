#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/mman.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/vfs.h>
#include <sys/wait.h>
#include <unistd.h>

static void file(const char *path, const char *data) {
    int fd=open(path,O_CREAT|O_EXCL|O_RDWR,0600); assert(fd>=0);
    assert(write(fd,data,strlen(data))==(ssize_t)strlen(data)); close(fd);
}
static void mapping(const char *path, const char *data) {
    int fd=open(path,O_WRONLY); assert(fd>=0);
    assert(write(fd,data,strlen(data))==(ssize_t)strlen(data)); assert(!close(fd));
}
static void finished(pid_t pid) {
    /* EVENT_WAIT: exact test child exits; the fixture alarm bounds failure. */
    int status; assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status));
}
static void private_owner(void) {
    assert(!mkdir("/tmp/tmpfs-user",0777));
    assert(!chown("/tmp/tmpfs-user",1000,1000));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!setgroups(0,NULL));
        assert(!setresgid(1000,1000,1000) && !setresuid(1000,1000,1000));
        /* Credential drops reset dumpability and proc map ownership. */
        assert(prctl(PR_GET_DUMPABLE)==0 && !prctl(PR_SET_DUMPABLE,1));
        assert(!unshare(CLONE_NEWUSER|CLONE_NEWNS));
        mapping("/proc/self/uid_map","0 1000 1\n");
        mapping("/proc/self/setgroups","deny\n");
        mapping("/proc/self/gid_map","0 1000 1\n");
        assert(!mount("tmpfs","/tmp/tmpfs-user","tmpfs",MS_NOSUID|MS_NODEV,"mode=0700,uid=0,gid=0"));
        struct stat st;
        assert(!stat("/tmp/tmpfs-user",&st) && !st.st_uid && !st.st_gid && (st.st_mode&07777)==0700);
        file("/tmp/tmpfs-user/data","user");
        int fd=open("/tmp/tmpfs-user/data",O_RDONLY); assert(fd>=0); close(fd);
        assert(!chmod("/tmp/tmpfs-user/data",0000));
        fd=open("/tmp/tmpfs-user/data",O_RDONLY); assert(fd>=0); close(fd);
        assert(!unshare(CLONE_NEWNS));
        assert(!umount2("/tmp/tmpfs-user",0));
        assert(open("/tmp/tmpfs-user/data",O_RDONLY)<0 && errno==ENOENT);
        _exit(0);
    }
    finished(child);
    assert(open("/tmp/tmpfs-user/data",O_RDONLY)<0 && errno==ENOENT);
    puts("PASS tmpfs user owner: mapped metadata, namespace capabilities, copied mount lifetime and parent isolation");
}
void test_tmpfs_guest(void) {
    setbuf(stdout,NULL); alarm(60);
    assert(!chmod("/",0755) && !chmod("/tmp",01777));
    assert(!mkdir("/tmp/temporary",0755));
    file("/tmp/temporary/covered","base");
    const char *bad[]={"mode=888","uid=4294967295","gid=4294967296","mode=1777,","uid=","mode=10000",
        "size=","size=-1","size=1mb","size=1%%","size=18446744073709551616","size=16E",
        "nr_blocks=1%","nr_inodes=invalid","size=1m,"};
    for (unsigned i=0;i<sizeof(bad)/sizeof(*bad);++i) {
        assert(mount("tmpfs","/tmp/temporary","tmpfs",0,bad[i])<0 && errno==EINVAL);
        assert(!access("/tmp/temporary/covered",F_OK));
    }
    assert(mount("tmpfs","/tmp/temporary","tmpfs",0,"unknown=1")<0 && errno==EINVAL);
    assert(!access("/tmp/temporary/covered",F_OK));
    const char *capacity[]={"size=0","size=65536k","size=50%","size=0x10M","nr_blocks=1,nr_inodes=1"};
    for (unsigned i=0;i<sizeof(capacity)/sizeof(*capacity);++i) {
        assert(!mount("tmpfs","/tmp/temporary","tmpfs",MS_STRICTATIME,capacity[i]));
        assert(!umount2("/tmp/temporary",0));
    }
    assert(mount("tmpfs","/tmp/temporary/covered","tmpfs",0,NULL)<0 && errno==ENOTDIR);
    assert(!mount("tmpfs","/tmp/temporary","tmpfs",MS_NOSUID|MS_NODEV|MS_STRICTATIME,
        "mode=0700,mode=01770,uid=1000,gid=2000,size=1,nr_blocks=1,nr_inodes=1"));
    struct stat st;
    assert(!stat("/tmp/temporary",&st) && st.st_uid==1000 && st.st_gid==2000 && (st.st_mode&07777)==01770);
    assert(open("/tmp/temporary/covered",O_RDONLY)<0 && errno==ENOENT);
    int dir=open("/tmp/temporary",O_PATH|O_DIRECTORY); assert(dir>=0);
    struct statfs info;
    assert(!fstatfs(dir,&info) && info.f_type==0x01021994 && (info.f_flags&(MS_NOSUID|MS_NODEV))==(MS_NOSUID|MS_NODEV));
    file("/tmp/temporary/data","hello");
    assert(!link("/tmp/temporary/data","/tmp/temporary/link"));
    assert(!mkdir("/tmp/temporary/sub",0755));
    assert(!symlink("../link","/tmp/temporary/sub/relative"));
    int fd=open("/tmp/temporary/sub/relative",O_RDWR); assert(fd>=0);
    assert(!fstat(fd,&st) && st.st_nlink==2);
    assert(!ftruncate(fd,4096));
    char *memory=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED,fd,0); assert(memory!=MAP_FAILED);
    memory[0]='H'; assert(!msync(memory,4096,MS_SYNC) && !munmap(memory,4096));
    char data[8]; assert(pread(fd,data,5,0)==5 && !memcmp(data,"Hello",5));
    /* Capacity requests are permissive, as are resource-less cgroups. Both
     * ordinary writes and shared mappings stay native, not a partial quota. */
    assert(!ftruncate(fd,16384) && pwrite(fd,"beyond",6,8192)==6);
    assert(!fstatfs(fd,&info) && info.f_blocks>1);
    assert(!mkdir("/tmp/temporary-alias",0755));
    assert(!mount("/tmp/temporary","/tmp/temporary-alias",NULL,MS_BIND,NULL));
    assert(!mount(NULL,"/tmp/temporary-alias",NULL,MS_REMOUNT|MS_BIND|MS_RDONLY|MS_NOSUID|MS_NODEV|MS_RELATIME,NULL));
    assert(open("/tmp/temporary-alias/data",O_WRONLY)<0 && errno==EROFS);
    int writable=open("/tmp/temporary/data",O_WRONLY); assert(writable>=0); close(writable);
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!unshare(CLONE_NEWNS));
        assert(!umount2("/tmp/temporary",MNT_DETACH));
        assert(!access("/tmp/temporary/covered",F_OK));
        assert(pread(fd,data,5,0)==5 && !memcmp(data,"Hello",5)); _exit(0);
    }
    finished(child);
    assert(!access("/tmp/temporary/data",F_OK));
    assert(!umount2("/tmp/temporary-alias",0));
    assert(umount2("/tmp/temporary",0)<0 && errno==EBUSY);
    assert(!umount2("/tmp/temporary",MNT_DETACH));
    int retained=openat(dir,"link",O_RDONLY); assert(retained>=0); close(retained);
    assert(!access("/tmp/temporary/covered",F_OK));
    assert(!mount("tmpfs","/tmp/temporary","tmpfs",0,NULL));
    assert(open("/tmp/temporary/data",O_RDONLY)<0 && errno==ENOENT);
    assert(pread(fd,data,5,0)==5 && !memcmp(data,"Hello",5));
    close(fd); close(dir);
    assert(!umount2("/tmp/temporary",0));
    private_owner();
    puts("PASS guest tmpfs: options, independent store, links, mmap, bind, readonly, copied namespace and retained detached descriptors");
}
