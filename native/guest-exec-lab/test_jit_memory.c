#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/userfaultfd.h>
#include <linux/openat2.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <linux/shm.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>
#include <sys/prctl.h>
#include <sys/uio.h>
#include <pthread.h>
#include <sched.h>
#include <stdatomic.h>
#include "private_memory.h"
#include "memory_replace.h"

static size_t page;
static int executable(void *address, const char *label) {
    errno=0;
    int result=mprotect(address,page,PROT_READ|PROT_EXEC);
    printf("%s: RX=%d errno=%d\n",label,result,result ? errno : 0);
    return result;
}
static void matrix(int fd, const char *name, int flags, int dirty) {
    unsigned char *p=mmap(NULL,page,PROT_READ|PROT_WRITE,flags,fd,0);
    assert(p!=MAP_FAILED);
    if (dirty) p[0]=37;
    char label[128];
    snprintf(label,sizeof(label),"%s %s %s",name,
        flags&MAP_SHARED ? "shared" : "private",dirty ? "dirty" : "clean");
    (void)executable(p,label);
    assert(!munmap(p,page));
}
static void wait_child(pid_t child) {
    /* EVENT_WAIT: this exact fork reports its memory assertions through exit;
     * the fixture alarm fails a stalled child without accepting a delay. */
    int status;
    while (waitpid(child,&status,0)<0) assert(errno==EINTR);
    assert(WIFEXITED(status) && !WEXITSTATUS(status));
}
static void semantics(int fd) {
    unsigned char original=11;
    assert(pwrite(fd,&original,1,0)==1);
    unsigned char *p=mmap(NULL,page,PROT_READ|PROT_WRITE,MAP_PRIVATE,fd,0);
    assert(p!=MAP_FAILED && *p==11);
    *p=37;
    unsigned char readback;
    assert(pread(fd,&readback,1,0)==1 && readback==11);
    fflush(stdout);
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(*p==37); *p=43;
        assert(pread(fd,&readback,1,0)==1 && readback==11);
        _exit(0);
    }
    wait_child(child);
    assert(*p==37);
    assert(!madvise(p,page,MADV_DONTNEED) && *p==11);
    puts("PASS private: original FD, fork isolation and DONTNEED backing reload");
    assert(!munmap(p,page));
}
static void anonymous_replacement(int fd) {
    unsigned char *p=mmap(NULL,page,PROT_READ|PROT_WRITE,MAP_PRIVATE,fd,0);
    assert(p!=MAP_FAILED); *p=37;
    unsigned char *copy=mmap(NULL,page,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(copy!=MAP_FAILED); memcpy(copy,p,page);
    assert(!munmap(p,page));
    (void)executable(copy,"anonymous snapshot");
    assert(!mprotect(copy,page,PROT_READ|PROT_WRITE));
    assert(!madvise(copy,page,MADV_DONTNEED) && *copy==0);
    unsigned char original;
    assert(pread(fd,&original,1,0)==1 && original==11);
    puts("REJECT anonymous replacement: DONTNEED loses original backing bytes");
    assert(!munmap(copy,page));
}
static void shared_replacement(int fd) {
    unsigned char *p=mmap(NULL,page,PROT_READ|PROT_WRITE,MAP_SHARED,fd,0);
    assert(p!=MAP_FAILED); *p=37;
    unsigned char original;
    assert(pread(fd,&original,1,0)==1 && original==37);
    fflush(stdout);
    pid_t child=fork(); assert(child>=0);
    if (!child) { *p=43; _exit(0); }
    wait_child(child); assert(*p==43);
    puts("REJECT shared replacement: writes change original FD and fork parent");
    assert(!munmap(p,page));
}
static void snapshot_replacement(int fd) {
    unsigned char original=11;
    assert(pwrite(fd,&original,1,0)==1);
    unsigned char *p=mmap(NULL,page,PROT_READ|PROT_WRITE,MAP_PRIVATE,fd,0);
    assert(p!=MAP_FAILED); *p=37;
    int snapshot=syscall(SYS_memfd_create,"jit-snapshot",MFD_CLOEXEC|MFD_ALLOW_SEALING);
    assert(snapshot>=0 && pwrite(snapshot,p,page,0)==(ssize_t)page);
    assert(!munmap(p,page));
    p=mmap(NULL,page,PROT_READ|PROT_EXEC,MAP_PRIVATE,snapshot,0);
    assert(p!=MAP_FAILED && *p==37);
    assert(!madvise(p,page,MADV_DONTNEED) && *p==37);
    puts("REJECT backed snapshot: DONTNEED reloads snapshot, not original bytes");
    assert(!mprotect(p,page,PROT_READ|PROT_WRITE));
    *p=43;
    (void)executable(p,"backed snapshot second JIT cycle");
    assert(!munmap(p,page)); close(snapshot);
}
static void fault_capabilities(void) {
    errno=0;
    int full=syscall(SYS_userfaultfd,O_CLOEXEC|O_NONBLOCK);
    printf("userfaultfd kernel faults: fd=%d errno=%d\n",full,full<0 ? errno : 0);
    if (full>=0) close(full);
    errno=0;
    int fd=syscall(SYS_userfaultfd,O_CLOEXEC|O_NONBLOCK|UFFD_USER_MODE_ONLY);
    int error=fd<0 ? errno : 0;
    printf("userfaultfd user-mode: fd=%d errno=%d\n",fd,error);
    if (fd<0) return;
    struct uffdio_api api={.api=UFFD_API};
    int result=ioctl(fd,UFFDIO_API,&api);
    printf("userfaultfd API: result=%d errno=%d features=0x%llx\n",
        result,result ? errno : 0,(unsigned long long)api.features);
    close(fd);
}
/* This is a single-threaded mechanism experiment, not a syscall adapter.
 * A runtime must quiesce CLONE_VM peers and own the full mapping lifecycle
 * before it can publish a replacement while retaining file-backed semantics. */
static int replace_view(void *address, int backing, int reload) {
    unsigned char *next=mmap(NULL,page,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    if (next==MAP_FAILED) return -errno;
    int error=0;
    if (reload) {
        ssize_t got=pread(backing,next,page,0);
        if (got!=(ssize_t)page) error=got<0 ? -errno : -EIO;
    } else memcpy(next,address,page);
    if (!error) {
        __builtin___clear_cache((char *)next,(char *)next+page);
        if (mprotect(next,page,PROT_READ|PROT_EXEC)) error=-errno;
    }
    if (!error && mremap(next,page,page,MREMAP_MAYMOVE|MREMAP_FIXED,address)==MAP_FAILED) error=-errno;
    if (error) munmap(next,page);
    return error;
}
static void emit(void *p, unsigned value) {
#if defined(__aarch64__)
    uint32_t instructions[]={0x52800000u|(value<<5),0xd65f03c0u};
#elif defined(__x86_64__)
    unsigned char instructions[]={0xb8,(unsigned char)value,0,0,0,0xc3};
#else
#error Unsupported JIT fixture architecture
#endif
    memcpy(p,instructions,sizeof(instructions));
    __builtin___clear_cache((char *)p,(char *)p+sizeof(instructions));
}
static void private_view_prototype(void) {
    int fd=syscall(SYS_memfd_create,"private-view-prototype",MFD_CLOEXEC|MFD_ALLOW_SEALING);
    assert(fd>=0 && !ftruncate(fd,page));
    unsigned char code[32]={0}; emit(code,11);
    assert(pwrite(fd,code,sizeof(code),0)==sizeof(code));
    void *original=mmap(NULL,page,PROT_READ|PROT_WRITE,MAP_PRIVATE,fd,0);
    void *alias=mmap(NULL,page,PROT_READ|PROT_EXEC,MAP_PRIVATE,fd,0);
    assert(original!=MAP_FAILED && alias!=MAP_FAILED);
    int backing=fcntl(fd,F_DUPFD_CLOEXEC,0); assert(backing>=0); close(fd);
    emit(original,37);
    (void)executable(original,"prototype native JIT admission");
    assert(!replace_view(original,backing,0));
    int (*run)(void)=original, (*unchanged)(void)=alias;
    assert(run()==37 && unchanged()==11);
    assert(replace_view(original,-1,1)==-EBADF && run()==37);
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!mprotect(original,page,PROT_READ|PROT_WRITE));
        emit(original,43);
        assert(!mprotect(original,page,PROT_READ|PROT_EXEC) && run()==43);
        _exit(0);
    }
    wait_child(child); assert(run()==37 && unchanged()==11);
    child=fork(); assert(child>=0);
    if (!child) { *(volatile unsigned char *)original=0; _exit(1); }
    /* EVENT_WAIT: the protection test must fault, not leave writable code;
     * the outer alarm bounds a child that fails to reach the memory access. */
    int status; assert(waitpid(child,&status,0)==child);
    assert(WIFSIGNALED(status) && WTERMSIG(status)==SIGSEGV);
    int channel[2]; assert(!pipe(channel));
    assert(write(channel[1],original,8)==8);
    assert(!mprotect(original,page,PROT_READ|PROT_WRITE));
    assert(read(channel[0],original,8)==8);
    assert(!mprotect(original,page,PROT_READ|PROT_EXEC) && run()==37);
    close(channel[0]); close(channel[1]);
    /* Logical DONTNEED reloads the retained original description, rather than
     * forwarding MADV_DONTNEED to the anonymous implementation mapping. */
    assert(!replace_view(original,backing,1) && run()==11);
    void *destination=mmap(NULL,page,PROT_NONE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(destination!=MAP_FAILED);
    assert(mremap(original,page,page,MREMAP_MAYMOVE|MREMAP_FIXED,destination)==destination);
    run=destination; assert(run()==11);
    assert(!munmap(destination,page) && !munmap(alias,page)); close(backing);
    puts("PASS private-view prototype: real JIT, W^X, original alias, fork, kernel IO, backing reload, move and failed preparation");
}
/* Admit execute on a clean private file VMA, then materialize its private page
 * through the kernel's debugger-memory interface. Unlike an anonymous copy,
 * the kernel still owns EOF faults and discarding back to the original file. */
static void backed_view_prototype(void) {
    int fd=syscall(SYS_memfd_create,"backed-view-prototype",MFD_CLOEXEC|MFD_ALLOW_SEALING);
    assert(fd>=0 && !ftruncate(fd,page));
    unsigned char code[32]={0}; emit(code,11);
    assert(pwrite(fd,code,sizeof(code),0)==sizeof(code));
    void *original=mmap(NULL,page,PROT_READ|PROT_WRITE,MAP_PRIVATE,fd,0);
    void *next=mmap(NULL,page,PROT_READ|PROT_EXEC,MAP_PRIVATE,fd,0);
    assert(original!=MAP_FAILED && next!=MAP_FAILED);
    emit(original,37);
    int memory=open("/proc/self/mem",O_RDWR|O_CLOEXEC);
    if (memory<0) {
        printf("backed-view proc memory unavailable: errno=%d\n",errno);
        assert(!munmap(original,page) && !munmap(next,page)); close(fd); return;
    }
    ssize_t copied=pwrite(memory,original,page,(off_t)(uintptr_t)next);
    printf("backed-view forced private copy: bytes=%zd errno=%d\n",copied,copied<0 ? errno : 0);
    assert(copied==(ssize_t)page);
    assert(!mprotect(next,page,PROT_READ|PROT_EXEC));
    assert(mremap(next,page,page,MREMAP_MAYMOVE|MREMAP_FIXED,original)==original);
    int (*run)(void)=original;
    assert(run()==37);
    assert(pread(fd,code,sizeof(code),0)==sizeof(code));
    assert(!madvise(original,page,MADV_DONTNEED) && run()==11);
    pid_t child=fork(); assert(child>=0);
    if (!child) { assert(!ftruncate(fd,0)); (void)run(); _exit(1); }
    /* EVENT_WAIT: truncation must retain native SIGBUS, not stale copied code;
     * the outer fixture alarm fails a child that never reaches the access. */
    int status; assert(waitpid(child,&status,0)==child);
    assert(WIFSIGNALED(status) && WTERMSIG(status)==SIGBUS);
    close(memory); assert(!munmap(original,page)); close(fd);
    puts("PASS backed-view prototype: real JIT, native DONTNEED and truncation SIGBUS");
}
static void runtime_preparation(int protected) {
    struct md_private_memory_access access={-1,-1,-1};
    assert(!md_private_memory_access_open(&access));
    int saved=prctl(PR_GET_DUMPABLE);
    if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
    int fd=memfd_create("runtime-private-view",MFD_CLOEXEC|MFD_ALLOW_SEALING);
    assert(fd>=0 && !ftruncate(fd,3*page));
    unsigned char code[32]={0}; emit(code,11);
    assert(pwrite(fd,code,sizeof(code),page)==sizeof(code));
    unsigned char *original=mmap(NULL,2*page,PROT_READ|PROT_WRITE,MAP_PRIVATE,fd,page);
    assert(original!=MAP_FAILED);
    emit(original,37);
    assert(original[page]==0);
    assert(!madvise(original,2*page,MADV_DONTDUMP));
    struct md_private_memory view={.address=(uintptr_t)original,.length=2*page,
        .page_size=page,.offset=page,.source=fd,.protection=PROT_READ|PROT_EXEC};
    long result=md_private_memory_prepare(&view,&access);
    printf("runtime backed preparation: protected=%d result=%ld copied=%zu\n",protected,result,view.copied_pages);
    if (result) {
        FILE *maps=fopen("/proc/self/smaps","r");
        char line[512]; int selected=0;
        while (maps && fgets(line,sizeof(line),maps)) {
            unsigned long first,last;
            if (sscanf(line,"%lx-%lx",&first,&last)==2) selected=first<=(uintptr_t)original && last>(uintptr_t)original;
            if (selected && !strncmp(line,"VmFlags:",8)) fputs(line,stdout);
        }
        if (maps) fclose(maps);
    }
    assert(!result && view.candidate && view.copied_pages==1);
    int (*run)(void)=(void *)view.candidate;
    assert(run()==37);
    unsigned char next=73;
    assert(pwrite(fd,&next,1,2*page)==1);
    assert(original[page]==73 && ((unsigned char *)view.candidate)[page]==73);
    assert(!madvise((void *)view.candidate,page,MADV_DONTNEED) && run()==11);
    md_private_memory_discard(&view); assert(!view.candidate);
    view.offset=0;
    assert(md_private_memory_prepare(&view,&access)==-ESTALE && !view.candidate && original[0]);
    view.offset=page;
    assert(!md_private_memory_prepare(&view,&access));
    assert(mremap((void *)view.candidate,2*page,2*page,MREMAP_MAYMOVE|MREMAP_FIXED,original)==original);
    view.candidate=0;
    run=(void *)original; assert(run()==37);
    assert(!mprotect(original,2*page,PROT_READ|PROT_WRITE)); emit(original,43);
    assert(!md_private_memory_prepare(&view,&access));
    run=(void *)view.candidate; assert(run()==43);
    md_private_memory_discard(&view);
    emit(original+page,91);
    struct md_private_memory partial=view;
    partial.address+=page; partial.offset+=page; partial.length=page;
    assert(!md_private_memory_prepare(&partial,&access) && partial.copied_pages==1);
    run=(void *)partial.candidate; assert(run()==91);
    md_private_memory_discard(&partial);
    assert(!mprotect(original,2*page,PROT_NONE));
    assert(!md_private_memory_prepare(&view,&access) && view.copied_pages==2);
    run=(void *)view.candidate; assert(run()==43);
    md_private_memory_discard(&view);
    assert(!mprotect(original,2*page,PROT_READ|PROT_WRITE));
    assert(!madvise(original,2*page,MADV_DONTNEED));
    assert(!md_private_memory_prepare(&view,&access) && !view.copied_pages);
    run=(void *)view.candidate; assert(run()==11);
    md_private_memory_discard(&view);
    assert(!munmap(original,2*page));
    size_t pages=513;
    assert(!ftruncate(fd,pages*page));
    original=mmap(NULL,pages*page,PROT_READ|PROT_WRITE,MAP_PRIVATE,fd,0);
    assert(original!=MAP_FAILED);
    const size_t dirty[]={0,255,256,512};
    for (unsigned i=0;i<sizeof(dirty)/sizeof(*dirty);++i) original[dirty[i]*page]=(unsigned char)(41+i);
    view=(struct md_private_memory){.address=(uintptr_t)original,.length=pages*page,
        .page_size=page,.source=fd,.protection=PROT_READ|PROT_EXEC};
    assert(!md_private_memory_prepare(&view,&access) && view.copied_pages==4);
    for (unsigned i=0;i<sizeof(dirty)/sizeof(*dirty);++i)
        assert(((unsigned char *)view.candidate)[dirty[i]*page]==41+i);
    next=97; assert(pwrite(fd,&next,1,2*page)==1);
    assert(((unsigned char *)view.candidate)[2*page]==next);
    md_private_memory_discard(&view);
    size_t dirty_count=0;
    for (size_t i=0;i<pages;++i) if (i%65!=64) { original[i*page]=(unsigned char)i; ++dirty_count; }
    struct md_private_memory_plan plan;
    struct md_private_memory_scan *scan=NULL;
    assert(!md_private_memory_scan_begin(&view,&access,&scan));
    long ready;
    do { ready=md_private_memory_scan_step(scan,&plan); } while (!ready);
    assert(ready==1); md_private_memory_scan_end(scan);
    view.candidate=(uintptr_t)mmap(NULL,pages*page,view.protection,plan.flags,fd,0);
    assert((void *)view.candidate!=MAP_FAILED);
    void *scratch=malloc(MD_PRIVATE_MEMORY_COPY_BYTES); assert(scratch);
    size_t position=0; unsigned turns=0;
    do {
        size_t previous=position;
        ready=md_private_memory_copy_step(&view,&access,&plan,&position,scratch,MD_PRIVATE_MEMORY_COPY_BYTES);
        assert(ready>=0 && position>previous && position-previous<=256*page); ++turns;
    } while (!ready);
    assert(turns==3 && view.copied_pages==dirty_count);
    for (size_t i=0;i<pages;++i) if (i%65!=64) assert(((unsigned char *)view.candidate)[i*page]==(unsigned char)i);
    next=103; assert(pwrite(fd,&next,1,64*page)==1);
    assert(((unsigned char *)view.candidate)[64*page]==next);
    free(scratch); md_private_memory_discard(&view);
    assert(!munmap(original,pages*page)); close(fd);
    if (protected) assert(!prctl(PR_SET_DUMPABLE,saved));
    md_private_memory_access_close(&access);
    puts("PASS runtime private preparation: COW only, clean alias, DONTNEED, offset, partial/PROT_NONE views, repeated JIT, pagemap batch boundaries and stale source rejection");
}
static void replacement_contract(void) {
    const uintptr_t original=0x100000, candidate=0x200000, backup=0x300000;
    struct md_memory_replace invalid={0};
    assert(md_memory_replace_begin(&invalid,original,original,page,page)==-EINVAL);
    assert(md_memory_replace_begin(&invalid,original,candidate,SIZE_MAX,page)==-EINVAL);
    /* Every syscall has a terminal result, including cancellation while that
     * command is in flight. Missing observation must not guess ownership. */
    for (int fault=MD_MEMORY_REPLACE_EMPTY;fault<=MD_MEMORY_REPLACE_DROP_BACKUP;++fault)
        for (int cancel=-1;cancel<7;++cancel)
            for (int pending=0;pending<2;++pending) {
                struct md_memory_replace r={0}; struct md_memory_replace_call call;
                assert(!md_memory_replace_begin(&r,original,candidate,page,page));
                int step=0;
                for (;;) {
                    if (!pending && cancel==step) md_memory_replace_cancel(&r);
                    int next=md_memory_replace_next(&r,&call);
                    if (next<=0) { assert(next==0 || r.phase==MD_MEMORY_REPLACE_BROKEN); break; }
                    assert(md_memory_replace_next(&r,&call)==-EBUSY);
                    if (pending && cancel==step) md_memory_replace_cancel(&r);
                    long value=call.number==SYS_mmap ? (long)backup :
                        call.number==SYS_mremap ? (long)call.args[4] : 0;
                    if ((int)r.phase==fault || (fault==MD_MEMORY_REPLACE_RESTORE && r.phase==MD_MEMORY_REPLACE_INSTALL))
                        value=-ENOMEM;
                    int result=md_memory_replace_observed(&r,value);
                    if (result) assert(r.phase==MD_MEMORY_REPLACE_BROKEN && result==-EIO);
                    assert(++step<10);
                }
                if (r.phase==MD_MEMORY_REPLACE_DONE) {
                    assert(!r.candidate && !r.backup && !r.displaced);
                    assert(md_memory_replace_result(&r)==r.error);
                    if (r.committed) assert(!r.error);
                } else assert(md_memory_replace_result(&r)==-EIO && (r.candidate || r.backup));
            }
    for (int lost=0;lost<5;++lost) {
        struct md_memory_replace r={0}; struct md_memory_replace_call call;
        assert(!md_memory_replace_begin(&r,original,candidate,page,page));
        for (int step=0;step<=lost;++step) {
            assert(md_memory_replace_next(&r,&call)==1);
            if (step==lost) {
                md_memory_replace_lost(&r);
                assert(md_memory_replace_next(&r,&call)==-EIO && md_memory_replace_result(&r)==-EIO);
            } else assert(!md_memory_replace_observed(&r,
                call.number==SYS_mmap ? (long)backup : call.number==SYS_mremap ? (long)call.args[4] : 0));
        }
    }
    puts("PASS replacement contract: cancellation at every boundary, failed save/install/restore/cleanup and missing terminal observation");
}
static void candidate_policy(int protected) {
    struct md_private_memory_access access={-1,-1,-1};
    assert(!md_private_memory_access_open(&access));
    int saved=prctl(PR_GET_DUMPABLE);
    if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
    int fd=memfd_create("candidate-policy",MFD_CLOEXEC);
    assert(fd>=0 && !ftruncate(fd,page));
    unsigned char *original=mmap(NULL,page,PROT_READ|PROT_WRITE,MAP_PRIVATE,fd,0);
    assert(original!=MAP_FAILED); emit(original,37);
    struct md_private_memory view={.address=(uintptr_t)original,.length=page,
        .page_size=page,.source=fd,.protection=PROT_READ|PROT_EXEC};
    struct md_private_memory_plan plan;
    assert(!md_private_memory_plan(&view,&access,&plan));
    void *candidate=mmap(NULL,page,view.protection,plan.flags,fd,0);
    assert(candidate!=MAP_FAILED); view.candidate=(uintptr_t)candidate;
    uint64_t entry=0;
    assert(pread(access.pagemap,&entry,sizeof(entry),(view.candidate/page)*sizeof(entry))==sizeof(entry));
    assert(!(entry&(UINT64_C(3)<<62)));
    /* Simulate a soft-dirty-cleared original. Candidate admission needs only
     * its pagemap entry, never a second smaps description or root PFNs. */
    plan.advice=0;
    struct md_private_memory_access copy_access=access; copy_access.maps=-1;
    long result=md_private_memory_copy(&view,&copy_access,&plan);
    assert(result==((entry&(UINT64_C(1)<<55)) ? -EOPNOTSUPP : 0));
    if (!result) { int (*run)(void)=candidate; assert(run()==37 && view.copied_pages==1); }
    md_private_memory_discard(&view);
    assert(!munmap(original,page)); close(fd);
    if (protected) assert(!prctl(PR_SET_DUMPABLE,saved));
    md_private_memory_access_close(&access);
    printf("PASS candidate policy: protected=%d, exact pagemap soft-dirty admission without smaps rescan\n",protected);
}
static void runtime_replacement(int protected) {
    struct md_private_memory_access access={-1,-1,-1};
    assert(!md_private_memory_access_open(&access));
    int saved=prctl(PR_GET_DUMPABLE);
    if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
    int fd=memfd_create("runtime-replacement",MFD_CLOEXEC|MFD_ALLOW_SEALING);
    assert(fd>=0 && !ftruncate(fd,page));
    unsigned char code[32]={0}; emit(code,11);
    assert(pwrite(fd,code,sizeof(code),0)==sizeof(code));
    for (int fault=MD_MEMORY_REPLACE_EMPTY;fault<=MD_MEMORY_REPLACE_INSTALL;++fault)
        for (int cancel=-1;cancel<6;++cancel) {
            unsigned char *original=mmap(NULL,page,PROT_READ|PROT_WRITE,MAP_PRIVATE,fd,0);
            assert(original!=MAP_FAILED); emit(original,37);
            struct md_private_memory view={.address=(uintptr_t)original,.length=page,
                .page_size=page,.source=fd,.protection=PROT_READ|PROT_EXEC};
            assert(!md_private_memory_prepare(&view,&access));
            uintptr_t candidate=view.candidate, backup=0;
            struct md_memory_replace r={0}; struct md_memory_replace_call call;
            assert(!md_memory_replace_begin(&r,view.address,view.candidate,page,page));
            view.candidate=0;
            int step=0, injected=0;
            for (;;) {
                if (cancel==step) md_memory_replace_cancel(&r);
                int next=md_memory_replace_next(&r,&call);
                if (!next) break;
                assert(next==1);
                long value;
                if ((int)r.phase==fault) {
                    /* Linux may remove the destination before a fixed move
                     * fails. Model that late failure, not only preflight. */
                    if (call.number==SYS_mremap) assert(!munmap((void *)call.args[4],page));
                    value=-ENOMEM; injected=1;
                } else {
                    errno=0;
                    value=syscall(call.number,call.args[0],call.args[1],call.args[2],
                        call.args[3],call.args[4],call.args[5]);
                    if (value==-1) value=-errno;
                }
                assert(!md_memory_replace_observed(&r,value));
                if (r.backup) backup=r.backup;
                assert(++step<10);
            }
            assert(!r.candidate && !r.backup && !r.displaced);
            long result=md_memory_replace_result(&r);
            if (r.committed) {
                assert(!result);
                int (*run)(void)=(void *)original; assert(run()==37);
                assert(!madvise(original,page,MADV_DONTNEED) && run()==11);
            } else {
                assert(result==(injected ? -ENOMEM : -ECANCELED));
                unsigned char expected[32]={0}; emit(expected,37);
                assert(!memcmp(original,expected,8));
                emit(original,43);
                assert(!madvise(original,page,MADV_DONTNEED) && !memcmp(original,code,8));
            }
            unsigned char residency;
            assert(mincore((void *)candidate,page,&residency)==-1 && errno==ENOMEM);
            if (backup) assert(mincore((void *)backup,page,&residency)==-1 && errno==ENOMEM);
            assert(!munmap(original,page));
        }
    close(fd);
    if (protected) assert(!prctl(PR_SET_DUMPABLE,saved));
    md_private_memory_access_close(&access);
    printf("PASS runtime replacement: protected=%d, JIT commit, cancellation, destructive destination failure, rollback and no scratch mappings\n",protected);
}
static void *automatic_jit(void *unused) {
    (void)unused;
    for (unsigned n=0;n<32;++n) {
        char name[64]; snprintf(name,sizeof(name),"automatic-jit-%ld-%u",syscall(SYS_gettid),n);
        int fd=memfd_create(name,MFD_CLOEXEC);
        if (fd<0) perror("automatic memfd_create");
        assert(fd>=0);
        int truncated=ftruncate(fd,page*2);
        if (truncated) {
            perror("automatic ftruncate");
            char path[64],target[512];
            snprintf(path,sizeof(path),"/proc/self/fd/%d",fd);
            ssize_t count=readlink(path,target,sizeof(target)-1);
            if (count>=0) target[count]=0; else strcpy(target,"unavailable");
            struct stat st={0}; fstat(fd,&st);
            fprintf(stderr,"ftruncate fd=%d name=%s target=%s mode=%o size=%ld\n",fd,name,target,st.st_mode,(long)st.st_size);
        }
        assert(!truncated);
        unsigned char code[32]={0}; emit(code,11);
        assert(pwrite(fd,code,sizeof(code),page)==sizeof(code));
        unsigned char *p=mmap(NULL,page,PROT_READ|PROT_WRITE,MAP_PRIVATE,fd,page);
        assert(p!=MAP_FAILED); close(fd);
        emit(p,37);
        assert(!mprotect(p,page,PROT_READ|PROT_EXEC));
        int (*run)(void)=(void *)p; assert(run()==37);
        assert(!madvise(p,page,MADV_DONTNEED) && run()==11);
        assert(!mprotect(p,page,PROT_READ|PROT_WRITE)); emit(p,43);
        assert(!mprotect(p,page,PROT_READ|PROT_EXEC) && run()==43);
        assert(!munmap(p,page));
    }
    return NULL;
}
static atomic_int io_done;
static void *automatic_io(void *unused) {
    (void)unused;
    while (!atomic_load(&io_done)) {
        int fd=open("/proc/self/status",O_RDONLY|O_CLOEXEC);
        if (fd<0) perror("automatic proc status open");
        assert(fd>=0);
        char data[256]; assert(read(fd,data,sizeof(data))>0); close(fd);
    }
    return NULL;
}
struct jit_vfork {
    int ready[2], release[2];
    int exec, wait_parent;
    void *stack;
    size_t stack_size;
};
static int jit_vfork_child(void *context) {
    struct jit_vfork *v=context;
    char byte='r';
    if (syscall(SYS_write,v->ready[1],&byte,1)!=1) return 1;
    /* EVENT_WAIT: the JIT caller releases this child only after repair. The
     * fixture alarm bounds a barrier waiting on its kernel-blocked parent. */
    if (syscall(SYS_read,v->release[0],&byte,1)!=1) return 1;
    if (v->exec) {
        char *args[]={"/proc/self/exe","--automatic",NULL};
        execv(args[0],args);
        return 2;
    }
    return 0;
}
static void *jit_vfork_parent(void *context) {
    struct jit_vfork *v=context;
    pid_t child=clone(jit_vfork_child,(char *)v->stack+v->stack_size,
        CLONE_VM|(v->wait_parent ? CLONE_VFORK : 0)|SIGCHLD,v);
    assert(child>0);
    wait_child(child);
    return NULL;
}
static void automatic_vfork(int exec,int wait_parent) {
    struct jit_vfork v={.stack_size=16*page,.exec=exec,.wait_parent=wait_parent};
    assert(!pipe2(v.ready,O_CLOEXEC) && !pipe2(v.release,O_CLOEXEC));
    v.stack=mmap(NULL,v.stack_size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(v.stack!=MAP_FAILED);
    pthread_t parent; assert(!pthread_create(&parent,NULL,jit_vfork_parent,&v));
    char byte;
    /* EVENT_WAIT: readiness proves the vfork child exists before JIT repair. */
    assert(read(v.ready[0],&byte,1)==1);
    automatic_jit(NULL);
    assert(write(v.release[1],&byte,1)==1 && !pthread_join(parent,NULL));
    assert(!munmap(v.stack,v.stack_size));
    for (unsigned i=0;i<2;++i) { close(v.ready[i]); close(v.release[i]); }
    automatic_jit(NULL);
    printf("PASS automatic private-file JIT with %s: exec=%d\n",
        wait_parent ? "a kernel-blocked vfork parent" : "a CLONE_VM process",exec);
}
struct jit_peer {
    atomic_int stop;
    atomic_ulong completed;
};
static void failed_memory_opens(void) {
    char path[64]; snprintf(path,sizeof(path),"/proc/%d/mem",getpid());
    errno=0;
    assert(open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC)==-1 && errno==ENOTDIR);
    struct open_how how={.flags=O_RDONLY|O_DIRECTORY|O_CLOEXEC,.resolve=RESOLVE_NO_SYMLINKS};
    errno=0;
    assert(syscall(SYS_openat2,AT_FDCWD,path,&how,sizeof(how))==-1 && errno==ENOTDIR);
    puts("PASS failed proc-memory opens: native openat/openat2 errors preserved");
}
static void automatic_process(int writer) {
    struct jit_peer *state=mmap(NULL,page,PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(state!=MAP_FAILED);
    size_t length=2048*page;
    int fd=memfd_create("jit-peer",MFD_CLOEXEC);
    assert(fd>=0 && !ftruncate(fd,length));
    unsigned char *p=mmap(NULL,length,PROT_READ|PROT_WRITE,MAP_PRIVATE,fd,0);
    assert(p!=MAP_FAILED); close(fd);
    for (size_t i=0;i<length;i+=page) p[i]=17;
    unsigned long *value=(void *)(p+length-sizeof(*value));
    *value=0;
    int memory=-1;
    if (writer==2) {
        /* An O_PATH lease can become a writable memory descriptor after fork.
         * Exposure belongs to the target address space, not the opening TID. */
        memory=open("/proc/self/mem",O_PATH|O_CLOEXEC);
        assert(memory>=0);
    } else if (writer==3) {
        memory=open("/proc/self/mem",O_RDWR|O_CLOEXEC); assert(memory>=0);
    } else if (writer==4) {
        struct open_how how={.flags=O_RDWR|O_CLOEXEC};
        memory=syscall(SYS_openat2,AT_FDCWD,"/proc/self/mem",&how,sizeof(how)); assert(memory>=0);
    }
    pid_t parent=getpid(),child=fork(); assert(child>=0);
    if (!child) {
        if (writer==2) {
            char path[64]; snprintf(path,sizeof(path),"/proc/self/fd/%d",memory);
            int reopened=open(path,O_RDWR|O_CLOEXEC);
            assert(reopened>=0); close(memory); memory=reopened;
        }
        unsigned long n=0;
        while (!atomic_load(&state->stop)) {
            ++n;
            if (writer==1) {
                struct iovec local={&n,sizeof(n)},remote={value,sizeof(n)};
                assert(process_vm_writev(parent,&local,1,&remote,1,0)==sizeof(n));
            } else if (writer>=2) {
                assert(pwrite(memory,&n,sizeof(n),(off_t)(uintptr_t)value)==sizeof(n));
            } else {
                int input=open("/proc/self/status",O_RDONLY|O_CLOEXEC);
                assert(input>=0);
                char data[256]; assert(read(input,data,sizeof(data))>0); close(input);
            }
            atomic_store(&state->completed,n);
        }
        if (memory>=0) close(memory);
        _exit(0);
    }
    /* EVENT_WAIT: a confirmed peer operation precedes the repair. The outer
     * alarm bounds lack of progress; no settling interval establishes readiness. */
    while (!atomic_load(&state->completed)) sched_yield();
    for (unsigned i=0;i<8;++i) {
        emit(p,37+i);
        assert(!mprotect(p,length,PROT_READ|PROT_WRITE|PROT_EXEC));
        int (*run)(void)=(void *)p; assert(run()==37+(int)i);
        assert(!mprotect(p,length,PROT_READ|PROT_WRITE));
    }
    atomic_store(&state->stop,1); wait_child(child);
    unsigned long completed=atomic_load(&state->completed);
    assert(completed>0 && (!writer || *value==completed));
    if (memory>=0) close(memory);
    assert(!munmap(p,length) && !munmap(state,page));
    printf("PASS automatic private-file JIT with independent process: writer=%d completed=%lu\n",writer,completed);
}
int main(int argc, char **argv) {
    if (argc!=2) return 2;
    alarm(30); setvbuf(stdout,NULL,_IOLBF,0);
    page=(size_t)getpagesize();
    printf("uid=%u page=%zu\n",(unsigned)getuid(),page);
    int fd=syscall(SYS_memfd_create,"jit-memory-policy",MFD_CLOEXEC|MFD_ALLOW_SEALING);
    assert(fd>=0 && !ftruncate(fd,page));
    if (!strcmp(argv[1],"--automatic-process")) {
        close(fd); automatic_process(0); return 0;
    }
    if (!strcmp(argv[1],"--automatic-failed-mem")) {
        close(fd); failed_memory_opens(); automatic_process(0); return 0;
    }
    if (!strcmp(argv[1],"--automatic-remote")) {
        close(fd); automatic_process(1); return 0;
    }
    if (!strcmp(argv[1],"--automatic-mem-fd")) {
        close(fd); automatic_process(2); automatic_process(3); automatic_process(4); return 0;
    }
    if (!strcmp(argv[1],"--automatic-vfork")) {
        close(fd);
        automatic_vfork(0,1); automatic_vfork(1,1);
        automatic_vfork(0,0); automatic_vfork(1,0);
        return 0;
    }
    if (!strcmp(argv[1],"--automatic-threads") || !strcmp(argv[1],"--automatic-writers")) {
        int with_io=!strcmp(argv[1],"--automatic-threads");
        close(fd);
        for (int protected=0;protected<=1;++protected) {
            if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
            pthread_t writers[4],io;
            atomic_store(&io_done,0);
            if (with_io) assert(!pthread_create(&io,NULL,automatic_io,NULL));
            for (unsigned i=0;i<4;++i) assert(!pthread_create(writers+i,NULL,automatic_jit,NULL));
            for (unsigned i=0;i<4;++i) assert(!pthread_join(writers[i],NULL));
            atomic_store(&io_done,1);
            if (with_io) assert(!pthread_join(io,NULL));
            if (protected) assert(!prctl(PR_SET_DUMPABLE,1));
            printf("PASS automatic concurrent private-file JIT: protected=%d, four writers%s\n",
                    protected,with_io ? " and namespace IO" : "");
        }
        return 0;
    }
    if (!strcmp(argv[1],"--automatic") || !strcmp(argv[1],"--automatic-shm")) {
        int segment=-1;
        void *shared=NULL;
        if (!strcmp(argv[1],"--automatic-shm")) {
            segment=syscall(SYS_shmget,IPC_PRIVATE,page,0600); assert(segment>=0);
            shared=(void *)syscall(SYS_shmat,segment,NULL,0); assert(shared!=(void *)-1);
            *(unsigned char *)shared=71;
        }
        unsigned char code[32]={0}; emit(code,11);
        assert(pwrite(fd,code,sizeof(code),0)==sizeof(code));
        unsigned char *p=mmap(NULL,page,PROT_READ|PROT_WRITE,MAP_PRIVATE,fd,0);
        assert(p!=MAP_FAILED); close(fd);
        emit(p,37);
        assert(!mprotect(p,page,PROT_READ|PROT_EXEC));
        int (*run)(void)=(void *)p;
        assert(run()==37);
        assert(!madvise(p,page,MADV_DONTNEED) && run()==11);
        assert(!mprotect(p,page,PROT_READ|PROT_WRITE)); emit(p,43);
        assert(!mprotect(p,page,PROT_READ|PROT_EXEC) && run()==43);
        assert(!munmap(p,page));
        if (segment>=0) {
            pid_t child=fork(); assert(child>=0);
            if (!child) {
                struct shmid64_ds st;
                assert(!syscall(SYS_shmctl,segment,IPC_STAT,&st) && st.shm_nattch==2);
                assert(*(unsigned char *)shared==71);
                assert(!syscall(SYS_shmdt,shared)); _exit(0);
            }
            wait_child(child);
            struct shmid64_ds st;
            assert(!syscall(SYS_shmctl,segment,IPC_STAT,&st) && st.shm_nattch==1);
            assert(!munmap(shared,page));
            assert(!syscall(SYS_shmctl,segment,IPC_STAT,&st) && !st.shm_nattch);
            assert(!syscall(SYS_shmctl,segment,IPC_RMID,NULL));
            puts("PASS private-file JIT with SysV attachment, fork and native unmap");
        }
        puts("PASS automatic private-file JIT: closed source FD, generated code, DONTNEED and repeated RX");
        return 0;
    }
    matrix(-1,"anonymous",MAP_PRIVATE|MAP_ANONYMOUS,1);
    matrix(fd,"memfd",MAP_PRIVATE,0);
    matrix(fd,"memfd",MAP_PRIVATE,1);
    matrix(fd,"memfd",MAP_SHARED,1);
    char path[4096]; assert(snprintf(path,sizeof(path),"%s/jit-policy.XXXXXX",argv[1])<(int)sizeof(path));
    int disk=mkstemp(path); assert(disk>=0);
    assert(!unlink(path) && !ftruncate(disk,page));
    matrix(disk,"regular file",MAP_PRIVATE,1); close(disk);
    semantics(fd);
    anonymous_replacement(fd);
    shared_replacement(fd);
    snapshot_replacement(fd);
    fault_capabilities();
    private_view_prototype();
    backed_view_prototype();
    runtime_preparation(0);
    runtime_preparation(1);
    replacement_contract();
    candidate_policy(0);
    candidate_policy(1);
    runtime_replacement(0);
    runtime_replacement(1);
    close(fd);
    puts("PASS policy matrix and rejected-replacement regressions");
    return 0;
}
