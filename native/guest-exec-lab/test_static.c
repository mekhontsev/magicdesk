#define _GNU_SOURCE
#include <assert.h>
#include <elf.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

static _Thread_local int local = 17;
static int initialized;
__attribute__((constructor)) static void initialize(void) { initialized = 73; }
static void *thread(void *unused) {
    (void)unused;
    assert(local == 17); local = 29;
    int fd = open("/etc/os-release", O_RDONLY); assert(fd >= 0); close(fd);
    return (void *)29;
}
int main(int argc, char **argv) {
    assert(initialized == 73 && local == 17 && getuid() == 2000);
    assert(getauxval(AT_BASE) == 0 && getauxval(AT_PHENT) == sizeof(Elf64_Phdr));
    const Elf64_Phdr *ph = (void *)getauxval(AT_PHDR);
    size_t n = getauxval(AT_PHNUM);
    assert(n && ph);
    int tls = 0;
    uintptr_t bias = 0;
    int found_phdr = 0;
    for (size_t i = 0; i < n; ++i) if (ph[i].p_type == PT_PHDR) {
        bias = (uintptr_t)ph - ph[i].p_vaddr;
        found_phdr = 1;
    }
    assert(found_phdr);
    for (size_t i = 0; i < n; ++i) {
        assert(ph[i].p_type != PT_INTERP);
        tls |= ph[i].p_type == PT_TLS;
        if (ph[i].p_type == PT_LOAD && ph[i].p_align > 1)
            assert(bias % ph[i].p_align == 0);
    }
    assert(tls);
    char *memory = malloc(8 * 1024 * 1024); assert(memory);
    for (size_t i = 0; i < 8 * 1024 * 1024; i += 4096) memory[i] = (char)i;
    free(memory);
    pthread_t worker;
    assert(!pthread_create(&worker, NULL, thread, NULL));
    void *result;
    /* EVENT_WAIT: native thread completion; outer runner bounds a hung runtime. */
    assert(!pthread_join(worker, &result) && result == (void *)29 && local == 17);
    if (argc == 2 && !strcmp(argv[1], "child")) return 0;
    pid_t child = fork(); assert(child >= 0);
    if (!child) {
        int fd = open(argv[0], O_RDONLY | O_CLOEXEC); assert(fd >= 0);
        char *args[] = {argv[0], "child", NULL};
        syscall(SYS_execveat, fd, "", args, environ, AT_EMPTY_PATH);
        abort();
    }
    int status;
    /* EVENT_WAIT: descriptor exec completes; never infer success from a delay. */
    assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
    puts("PASS static ELF: constructors, TLS, pthreads, heap, auxv, file calls, fork/descriptor exec");
    return 0;
}
