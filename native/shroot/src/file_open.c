#define _GNU_SOURCE
#include "file_open.h"
#include "interruptible_call.h"
#include <errno.h>
#include <limits.h>
#include <linux/futex.h>
#include <linux/magic.h>
#include <poll.h>
#include <stdint.h>
#include <sys/file.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/vfs.h>
#include <unistd.h>
#if __STDC_HOSTED__
static long call(long nr, long a, long b, long c, long d, long e, long f) {
    long r = syscall(nr, a, b, c, d, e, f);
    return r == -1 ? -errno : r;
}
#else
#include "raw.h"
#define call md_raw
#endif
#define C(n,a,b,c,d,e,f) call(SYS_##n,(long)(a),(long)(b),(long)(c),(long)(d),(long)(e),(long)(f))
#define WAIT(n,a,b,c,d,e) (cancelled ? md_interruptible_syscall(SYS_##n,(long)(a),(long)(b), \
    (long)(c),(long)(d),(long)(e),cancelled) : C(n,a,b,c,d,e,0))

static const struct { unsigned major,minor; const char *path; int caller; } devices[]={
    {1,3,"/dev/null",0},{1,5,"/dev/zero",0},{1,7,"/dev/full",0},
    {1,8,"/dev/random",0},{1,9,"/dev/urandom",0},{5,0,"/dev/tty",1}
};
int md_open_device_node(unsigned index,const char **path,uint64_t *device) {
    if (index>=sizeof(devices)/sizeof(*devices)) return 0;
    *path=devices[index].path; *device=makedev(devices[index].major,devices[index].minor);
    return 1;
}
int md_open_device(unsigned mode,uint64_t device,int flags,struct md_open_completion *completion) {
    *completion=(struct md_open_completion){0};
    if (!S_ISCHR(mode)) return -ENXIO;
    /* Only explicitly supported native endpoints. Metadata-only nodes do not
     * grant arbitrary host device access, even under a root executor. */
    for (unsigned i=0;i<sizeof(devices)/sizeof(*devices);++i) {
        if (major(device)!=devices[i].major || minor(device)!=devices[i].minor) continue;
        long fd=C(openat,AT_FDCWD,devices[i].path,O_PATH|O_CLOEXEC|O_NOFOLLOW,0,0,0);
        if (fd<0) return (int)fd;
        struct stat st;
        long r=C(fstat,fd,&st,0,0,0,0);
        if (!r && (!S_ISCHR(st.st_mode) || st.st_rdev!=device)) r=-ENXIO;
        if (!r && devices[i].caller) {
            completion->kind=MD_OPEN_CALLER; return (int)fd;
        }
        struct md_open_completion opened;
        if (!r) r=md_reopen_file((int)fd,md_open_endpoint_flags(flags),&opened);
        C(close,fd,0,0,0,0,0);
        return (int)r;
    }
    return -ENXIO;
}

static char *decimal(char *path, unsigned n) {
    unsigned digits = 0;
    char reversed[16];
    do { reversed[digits++] = (char)('0' + n % 10); n /= 10; } while (n);
    while (digits) *path++ = reversed[--digits];
    return path;
}
void md_descriptor_path(char path[64], unsigned pid, unsigned fd) {
    const char *prefix="/proc/";
    while (*prefix) *path++=*prefix++;
    if (pid) path=decimal(path,pid);
    else { prefix="self"; while (*prefix) *path++=*prefix++; }
    prefix="/fd/"; while (*prefix) *path++=*prefix++;
    *decimal(path,fd)=0;
}
unsigned md_open_plan_path(char path[64],unsigned pid,unsigned fd,unsigned flags,unsigned mode) {
    md_descriptor_path(path,pid,fd);
    if (flags&O_PATH) flags&=O_PATH|O_CLOEXEC|O_DIRECTORY|O_NOFOLLOW;
    if ((flags&O_NOFOLLOW) && S_ISDIR(mode)) {
        char *end=path; while (*end) ++end;
        /* The trailing slash follows the private magic link, not an extra
         * '.' lookup requiring search permission on the admitted directory. */
        *end++='/'; *end=0;
        return flags&~(O_CREAT|O_EXCL);
    }
    return md_open_endpoint_flags(flags);
}
static long native_open(void *context,const char *path,int flags) {
    (void)context;
    return C(openat,AT_FDCWD,path,flags,0,0,0);
}
static int native_terminal(int fd,const struct stat *st) {
    struct statfs fs;
    return S_ISCHR(st->st_mode) && !C(fstatfs,fd,&fs,0,0,0,0) && fs.f_type==DEVPTS_SUPER_MAGIC;
}
int md_open_pinned_path(const char *path,int flags,md_open_path_call opener,void *context) {
    if (!opener) opener=native_open;
    long result=opener(context,path,flags);
    if ((result!=-EACCES && result!=-EPERM) || !(flags&O_APPEND) || (flags&O_PATH)) return (int)result;
    /* Android may admit PTY write but not append at open time. Appending has
     * no offset semantics on a terminal. Restore the real OFD flag before
     * publication; ordinary files and successful native opens stay untouched. */
    long pin=C(openat,AT_FDCWD,path,O_PATH|O_CLOEXEC,0,0,0);
    if (pin<0) return (int)result;
    struct stat st;
    int terminal=!C(fstat,pin,&st,0,0,0,0) && native_terminal((int)pin,&st);
    C(close,pin,0,0,0,0,0);
    if (!terminal) return (int)result;
    result=opener(context,path,flags&~O_APPEND);
    if (result<0) return (int)result;
    long status=C(fcntl,result,F_GETFL,0,0,0,0);
    long error=status<0 ? status : C(fcntl,result,F_SETFL,status|O_APPEND,0,0,0);
    if (error<0) { C(close,result,0,0,0,0,0); return (int)error; }
    return (int)result;
}
static int open_pinned(int fd,int flags,unsigned mode) {
    char path[64]; md_descriptor_path(path,0,(unsigned)fd);
    if ((flags&(O_PATH|O_NOFOLLOW))==(O_PATH|O_NOFOLLOW)) {
        /* O_PATH has no offset or mutable status flags. An exact pin can be
         * duplicated without losing NOFOLLOW to the procfs magic link. */
        long current=C(fcntl,fd,F_GETFL,0,0,0,0);
        if (current<0) return (int)current;
        if (current==(flags&(O_PATH|O_DIRECTORY|O_NOFOLLOW)))
            return (int)C(fcntl,fd,F_DUPFD_CLOEXEC,3,0,0,0);
    }
    if (flags&O_NOFOLLOW) {
        if (!mode) {
            struct stat st;
            long error=C(fstat,fd,&st,0,0,0,0);
            if (error) return (int)error;
            mode=st.st_mode;
        }
        if (S_ISDIR(mode)) {
            flags=md_open_plan_path(path,0,(unsigned)fd,flags,mode);
            return (int)C(openat,AT_FDCWD,path,flags|O_CLOEXEC,0,0,0);
        }
    }
    return md_open_pinned_path(path,md_open_endpoint_flags(flags)|O_CLOEXEC,NULL,NULL);
}
int md_open_pinned(int fd,int flags) { return open_pinned(fd,flags,0); }
int md_reopen_file(int fd, int flags, struct md_open_completion *completion) {
    *completion=(struct md_open_completion){0};
    if (flags & ~(O_PATH|O_CLOEXEC|O_NONBLOCK|O_DIRECTORY|O_LARGEFILE|O_ACCMODE
            |O_APPEND|O_TRUNC|O_NOFOLLOW|O_SYNC|O_DSYNC|O_DIRECT|O_NOCTTY|O_NOATIME)) return -EINVAL;
    if ((flags&O_ACCMODE)==O_ACCMODE || ((flags&O_TRUNC) && !(flags&O_ACCMODE))) return -EINVAL;
    if (flags&O_PATH) flags&=O_PATH|O_CLOEXEC|O_DIRECTORY|O_NOFOLLOW;
    struct stat st;
    long error=C(fstat,fd,&st,0,0,0,0);
    if (error) return (int)error;
    if ((flags&O_DIRECTORY) && !S_ISDIR(st.st_mode)) return -ENOTDIR;
    int caller=!(flags&O_PATH) && S_ISCHR(st.st_mode) && major(st.st_rdev)==5 && minor(st.st_rdev)==0;
    if (!caller && !(flags&O_PATH) && (flags&O_APPEND) && native_terminal(fd,&st)) {
        char path[64]; md_descriptor_path(path,0,(unsigned)fd);
        long opened=native_open(NULL,path,md_open_endpoint_flags(flags)|O_CLOEXEC);
        if (opened!=-EACCES && opened!=-EPERM) return (int)opened;
        caller=1;
    }
    if (caller) {
        /* /dev/tty resolves the opener's controlling terminal, even with
         * O_NOCTTY. Provider/bind aliases must not open it in the worker. */
        /* PTY append also belongs to the receiver: Android can reject the
         * transfer of an append OFD even when its own open/fcntl are allowed. */
        long pin=C(fcntl,fd,F_DUPFD_CLOEXEC,3,0,0,0);
        if (pin>=0) completion->kind=MD_OPEN_CALLER;
        return (int)pin;
    }
    if (S_ISFIFO(st.st_mode) && !(flags&O_PATH)) {
        long pin=C(fcntl,fd,F_DUPFD_CLOEXEC,3,0,0,0);
        if (pin>=0) completion->kind=MD_OPEN_FIFO;
        return (int)pin;
    }
    if (S_ISLNK(st.st_mode)) {
        if (!(flags&O_PATH) || !(flags&O_NOFOLLOW)) return -ELOOP;
        return (int)C(fcntl,fd,F_DUPFD_CLOEXEC,3,0,0,0);
    }
    return open_pinned(fd,flags,st.st_mode);
}
static long interruptible_open(void *context,const char *path,int flags) {
    const atomic_int *cancelled=context;
    return WAIT(openat,AT_FDCWD,path,flags,0,0);
}
int md_complete_open_interruptible(int pin, int flags, struct md_open_completion completion,
        struct md_open_observation *observation,const atomic_int *cancelled) {
    if (observation) *observation=(struct md_open_observation){0};
    if (pin < 0 || completion.kind == MD_OPEN_READY) return pin;
    if (completion.kind==MD_OPEN_DEVICE) {
        struct md_open_completion next;
        int fd=md_open_device(completion.mode,completion.device,flags,&next);
        C(close,pin,0,0,0,0,0);
        return md_complete_open(fd,flags,next);
    }
    char path[64];
    flags = md_open_plan_path(path,0,(unsigned)pin,flags,completion.mode);
    if (completion.kind == MD_OPEN_FIFO || completion.kind==MD_OPEN_CALLER) {
        /* EVENT_WAIT: native FIFO peer rendezvous or terminal open; a caller
         * signal cancels it. No namespace worker transaction is retained. */
        long r = md_open_pinned_path(path,flags,interruptible_open,(void *)cancelled);
        C(close, pin, 0, 0, 0, 0, 0);
        return (int)r;
    }
    int control = completion.control, fd = -1, locked = 0;
    uint32_t *generations = NULL;
    long mapping = C(mmap, 0, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, control, 0);
    long r = mapping < 0 && mapping >= -4095 ? mapping : 0;
    if (!r) generations = (void *)mapping;
    if (!r) { r = WAIT(flock, control, LOCK_EX, 0, 0, 0); locked = !r; }
    unsigned mode = flags & O_ACCMODE;
    unsigned peer = mode == O_RDONLY ? 1 : 0;
    uint32_t before = r ? 0 : __atomic_load_n(&generations[peer], __ATOMIC_ACQUIRE);
    if (!r) {
        r = C(openat, AT_FDCWD, path, flags, 0, 0, 0);
        if (r >= 0) { fd = (int)r; r = 0; }
    }
    struct pollfd state = {.fd = fd, .events = POLLIN | POLLOUT};
    struct timespec zero = {0};
    if (!r && mode != O_RDWR) {
        long ready = C(ppoll, &state, 1, &zero, 0, 0, 0);
        if (ready < 0) r = ready;
    }
    int absent = mode == O_RDONLY ? !!(state.revents & POLLHUP) : !!(state.revents & POLLERR);
    if (!r && observation && mode==O_RDONLY && absent && (flags&O_NONBLOCK))
        *observation=(struct md_open_observation){.initial_reader=1,.writers=before};
    if (!r && mode == O_WRONLY && absent && (flags & O_NONBLOCK)) r = -ENXIO;
    if (!r && absent && !(flags&O_NONBLOCK) && cancelled
            && atomic_load_explicit(cancelled,memory_order_acquire)) r=-EINTR;
    if (!r) {
        for (unsigned side = 0; side < 2; side++) {
            if ((side == 0 && mode != O_WRONLY) || (side == 1 && mode != O_RDONLY)) {
                __atomic_store_n(&generations[side], __atomic_load_n(&generations[side], __ATOMIC_RELAXED) + 1,
                    __ATOMIC_RELEASE);
                C(futex, &generations[side], FUTEX_WAKE, INT_MAX, 0, 0, 0);
            }
        }
    }
    if (r && fd>=0) { C(close,fd,0,0,0,0,0); fd=-1; }
    if (locked) C(flock, control, LOCK_UN, 0, 0, 0, 0);
    /* EVENT_WAIT: another opener publishes its endpoint generation. Signals or
     * process cancellation terminate the wait; no SQLite/store lock is held. */
    while (!r && mode != O_RDWR && absent && !(flags & O_NONBLOCK)
            && __atomic_load_n(&generations[peer], __ATOMIC_ACQUIRE) == before) {
        r = WAIT(futex, &generations[peer], FUTEX_WAIT, before, 0, 0);
        if (r == -EAGAIN) r = 0;
    }
    if (r==-EINTR && fd>=0) {
        /* Cancellation and peer admission serialize through the same gate.
         * A peer which already observed our endpoint must not lose it merely
         * because the owner has not consumed the helper's result yet. */
        long lock;
        do { lock=C(flock,control,LOCK_EX,0,0,0,0); } while (lock==-EINTR);
        if (!lock && __atomic_load_n(&generations[peer],__ATOMIC_ACQUIRE)!=before) r=0;
        if (r) { C(close,fd,0,0,0,0,0); fd=-1; }
        if (!lock) C(flock,control,LOCK_UN,0,0,0,0);
    }
    if (generations) C(munmap, generations, 4096, 0, 0, 0, 0);
    C(close, control, 0, 0, 0, 0, 0);
    C(close, pin, 0, 0, 0, 0, 0);
    if (r && fd >= 0) C(close, fd, 0, 0, 0, 0, 0);
    return r ? (int)r : fd;
}
int md_complete_open_observed(int pin,int flags,struct md_open_completion completion,
        struct md_open_observation *observation) {
    return md_complete_open_interruptible(pin,flags,completion,observation,NULL);
}
int md_complete_open(int pin,int flags,struct md_open_completion completion) {
    return md_complete_open_observed(pin,flags,completion,NULL);
}
