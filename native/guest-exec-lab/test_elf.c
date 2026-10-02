#define _GNU_SOURCE
#include "elf.h"
#include "raw.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

static unsigned char bytes[32768];
static int descriptor;
static Elf64_Ehdr *header;
static Elf64_Phdr *segments;
static void seed(void) {
    memset(bytes, 0, sizeof(bytes));
    header = (void *)bytes;
    *header = (Elf64_Ehdr){.e_type = ET_DYN, .e_machine = EM_AARCH64, .e_version = EV_CURRENT,
        .e_entry = 256, .e_phoff = sizeof(*header), .e_ehsize = sizeof(*header),
        .e_phentsize = sizeof(Elf64_Phdr), .e_phnum = 3};
    memcpy(header->e_ident, ELFMAG, SELFMAG);
    header->e_ident[EI_CLASS] = ELFCLASS64; header->e_ident[EI_DATA] = ELFDATA2LSB;
    header->e_ident[EI_VERSION] = EV_CURRENT;
    segments = (void *)(bytes + sizeof(*header));
    segments[0] = (Elf64_Phdr){.p_type = PT_LOAD, .p_flags = PF_R | PF_X,
        .p_filesz = md_page_size, .p_memsz = md_page_size};
    const char *path = "/opt/another-libc/loader.so";
    strcpy((char *)bytes + 512, path);
    segments[1] = (Elf64_Phdr){.p_type = PT_INTERP, .p_offset = 512, .p_filesz = strlen(path) + 1};
    segments[2] = (Elf64_Phdr){.p_type = PT_LOAD, .p_flags = PF_R | PF_W,
        .p_offset = md_page_size, .p_vaddr = md_page_size,
        .p_filesz = md_page_size, .p_memsz = md_page_size};
}
static void check(int error, int loader) {
    assert(pwrite(descriptor, bytes, sizeof(bytes), 0) == sizeof(bytes));
    int r = md_elf_validate(descriptor, loader);
    if (r != error) fprintf(stderr, "ELF page=%zu: expected=%d actual=%d\n", md_page_size, error, r);
    assert(r == error);
    if (error) {
        struct md_image image;
        assert(md_elf_load(descriptor, loader, &image) == error);
    }
}
int main(int argc, char **argv) {
    assert(argc == 2);
    descriptor = open(argv[1], O_CREAT | O_EXCL | O_RDWR, 0700); assert(descriptor >= 0);
    for (size_t page = 4096; page <= 16384; page *= 4) {
        md_page_size = page;
        seed(); check(0, 0);
        char path[MD_INTERPRETER_MAX]; assert(!md_elf_interpreter(descriptor, path));
        assert(!strcmp(path, "/opt/another-libc/loader.so"));
        check(-ENOEXEC, 1);
        seed(); header->e_phnum = 1; check(0, 1); check(0, 0);
        seed(); bytes[512] = 'x'; check(-ENOEXEC, 0);
        seed(); bytes[513] = 0; check(-ENOEXEC, 0);
        seed(); segments[1].p_filesz = PATH_MAX + 1; check(-ENOEXEC, 0);
        seed(); segments[0].p_flags |= PF_W; check(-ENOEXEC, 0);
        seed(); segments[2].p_vaddr = 0; check(-ENOEXEC, 0);
        seed(); segments[2].p_filesz++; check(-ENOEXEC, 0);
        seed(); segments[2].p_offset++; check(-ENOEXEC, 0);
        seed(); header->e_entry = page; check(-ENOEXEC, 0);
        seed(); header->e_type = ET_EXEC; check(0, 0); check(-ENOEXEC, 1);
        seed(); header->e_type = ET_EXEC; header->e_entry += 0x40000000;
        segments[0].p_vaddr += 0x40000000; segments[2].p_vaddr += 0x40000000;
        check(0, 0);
        seed(); segments[2].p_vaddr = UINT64_MAX - page + 1; check(-ENOEXEC, 0);
        seed(); header->e_machine = EM_X86_64; check(-ENOEXEC, 0);
        seed(); header->e_phnum = 129; check(-ENOEXEC, 0);
        seed(); header->e_phoff = UINT64_MAX; check(-ENOEXEC, 0);
        seed(); segments[2].p_memsz = UINT64_MAX; check(-ENOEXEC, 0);
        seed(); header->e_phnum = 1;
        segments[0].p_memsz = UINT64_MAX - page + 2; check(-ENOEXEC, 0);
        seed(); header->e_phnum = 1;
        segments[0].p_memsz = (UINT64_C(1) << 63) + page;
        segments[0].p_align = UINT64_C(1) << 63; check(-ENOEXEC, 0);
        seed(); segments[2] = segments[1]; check(-ENOEXEC, 0);
        seed(); bytes[512 + segments[1].p_filesz - 1] = 'x'; check(-ENOEXEC, 0);
        seed(); header->e_phoff = sizeof(bytes) - sizeof(Elf64_Phdr); check(-ENOEXEC, 0);
        seed(); segments[0].p_align = 3 * page; check(-ENOEXEC, 0);
        seed(); segments[2].p_align = 2 * page;
        segments[2].p_vaddr += page; check(-ENOEXEC, 0);
        seed(); segments[0].p_align = 2 * 1024 * 1024; check(0, 0);
        seed(); segments[2].p_memsz = 512UL * 1024 * 1024; check(0, 0);
        seed(); header->e_phnum = 1; segments[0].p_offset = page;
        check(0, 0);
    }
    md_page_size = (size_t)sysconf(_SC_PAGESIZE);
    seed(); segments[0].p_align = 2 * 1024 * 1024; check(0, 0);
    struct md_image aligned;
    assert(!md_elf_load(descriptor, 0, &aligned));
    assert(aligned.base % segments[0].p_align == 0);
    assert(!memcmp((void *)aligned.base, bytes, md_page_size));
    assert(!munmap((void *)aligned.base, 2 * md_page_size));
    seed();
    memset(bytes + md_page_size, 0xa5, md_page_size);
    segments[2].p_filesz = 17; segments[2].p_memsz = 2 * md_page_size;
    check(0, 0); assert(!md_elf_load(descriptor, 0, &aligned));
    unsigned char *bss = (void *)(aligned.base + md_page_size);
    for (size_t i = 0; i < 2 * md_page_size; i++) assert(bss[i] == (i < 17 ? 0xa5 : 0));
    assert(!munmap((void *)aligned.base, 3 * md_page_size));
    unsigned char *owned = mmap(NULL, 2 * md_page_size, PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(owned != MAP_FAILED); owned[0] = 0x5a; owned[md_page_size] = 0xa5;
    seed(); header->e_type = ET_EXEC; header->e_entry += (uintptr_t)owned;
    segments[0].p_vaddr += (uintptr_t)owned; segments[2].p_vaddr += (uintptr_t)owned;
    check(0, 0);
    struct md_image image;
    assert(md_elf_load(descriptor, 0, &image) == -EEXIST);
    assert(owned[0] == 0x5a && owned[md_page_size] == 0xa5);
    assert(!munmap(owned, 2 * md_page_size));
    close(descriptor); assert(!unlink(argv[1]));
    puts("PASS ELF preflight: generic interpreter, ABI, segments, entry and malformed ranges at 4/16 KiB alignment");
    return 0;
}
