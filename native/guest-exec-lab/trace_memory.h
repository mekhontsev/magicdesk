#ifndef MD_FIXTURE_TRACE_MEMORY_H
#define MD_FIXTURE_TRACE_MEMORY_H
#include <asm/ptrace.h>
#include <assert.h>
#include <elf.h>
#include <errno.h>
#include <stdint.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/uio.h>

/* Fixture-owned addresses only. Guest EFAULT admission belongs to the runtime. */
static inline struct user_pt_regs registers(pid_t pid) {
    struct user_pt_regs r; struct iovec io = {&r, sizeof(r)};
    assert(!ptrace(PTRACE_GETREGSET, pid, (void *)NT_PRSTATUS, &io)); return r;
}
static inline void set_registers(pid_t pid, struct user_pt_regs *r) {
    struct iovec io = {r, sizeof(*r)};
    assert(!ptrace(PTRACE_SETREGSET, pid, (void *)NT_PRSTATUS, &io));
}
static inline void copy_memory(pid_t pid, uintptr_t address, void *buffer, size_t size, int write) {
    for (size_t at = 0; at < size; at += sizeof(long)) {
        size_t n = size - at < sizeof(long) ? size - at : sizeof(long);
        long word;
        if (!write || n != sizeof(word)) {
            errno = 0;
            word = ptrace(PTRACE_PEEKDATA, pid, (void *)(address + at), NULL);
            assert(!errno);
        }
        if (write) {
            memcpy(&word, (char *)buffer + at, n);
            assert(!ptrace(PTRACE_POKEDATA, pid, (void *)(address + at), (void *)word));
        } else memcpy((char *)buffer + at, &word, n);
    }
}
#endif
