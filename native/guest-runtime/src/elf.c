#define _GNU_SOURCE
#include "elf.h"
#include "raw.h"
#include <errno.h>
#include <sys/mman.h>
#include <sys/stat.h>

#define IMAGE_LIMIT (128UL * 1024 * 1024)
struct elf_data { Elf64_Ehdr header; Elf64_Phdr ph[128]; struct stat st; };
static uintptr_t down(uintptr_t n) { return n & ~(md_page_size - 1); }
static uintptr_t up(uintptr_t n) { return (n + md_page_size - 1) & ~(md_page_size - 1); }

static int read_elf(int fd, struct elf_data *data, int loader) {
    Elf64_Ehdr *h = &data->header;
    long r = RAW2(fstat, fd, &data->st);
    if (r < 0) return (int)r;
    if (!S_ISREG(data->st.st_mode) || (data->st.st_mode & (S_ISUID | S_ISGID))) return -EACCES;
    if (RAW4(pread64, fd, h, sizeof(*h), 0) != sizeof(*h)) return -ENOEXEC;
    if (h->e_ident[0] != 0x7f || h->e_ident[1] != 'E' || h->e_ident[2] != 'L' || h->e_ident[3] != 'F'
            || h->e_ident[EI_CLASS] != ELFCLASS64 || h->e_ident[EI_DATA] != ELFDATA2LSB
            || h->e_machine != EM_AARCH64 || h->e_version != EV_CURRENT
            || h->e_ehsize != sizeof(*h) || h->e_phentsize != sizeof(Elf64_Phdr)
            || !h->e_phnum || h->e_phnum > 128 || h->e_type != ET_DYN) return -ENOEXEC;
    size_t size = h->e_phnum * sizeof(Elf64_Phdr);
    if (data->st.st_size < 0 || h->e_phoff > (uint64_t)data->st.st_size
            || size > (uint64_t)data->st.st_size - h->e_phoff
            || RAW4(pread64, fd, data->ph, size, h->e_phoff) != (long)size) return -ENOEXEC;
    int interpreted = 0;
    for (unsigned i = 0; i < h->e_phnum; ++i) {
        Elf64_Phdr *p = data->ph + i;
        if (p->p_offset > (uint64_t)data->st.st_size
                || p->p_filesz > (uint64_t)data->st.st_size - p->p_offset) return -ENOEXEC;
        if (p->p_type == PT_INTERP) {
            char interpreter[128];
            if (loader || interpreted++ || p->p_filesz < 2 || p->p_filesz > sizeof(interpreter)
                    || RAW4(pread64, fd, interpreter, p->p_filesz, p->p_offset) != (long)p->p_filesz
                    || interpreter[p->p_filesz - 1]
                    || !md_equal(interpreter, "/lib/ld-linux-aarch64.so.1")) return -ENOEXEC;
        }
    }
    return loader || interpreted ? 0 : -ENOTSUP;
}
int md_elf_check(int fd, int loader) {
    struct elf_data data;
    return read_elf(fd, &data, loader);
}
int md_elf_load(int fd, struct md_image *image) {
    struct elf_data data;
    int r = read_elf(fd, &data, 1);
    if (r) return r;
    uintptr_t low = UINTPTR_MAX, high = 0, previous = 0;
    int executable_entry = 0;
    size_t phsize = data.header.e_phnum * sizeof(Elf64_Phdr);
    uintptr_t phdr = 0;
    for (unsigned i = 0; i < data.header.e_phnum; ++i) {
        Elf64_Phdr *p = data.ph + i;
        if (p->p_type != PT_LOAD || !p->p_memsz) continue;
        if (p->p_filesz > p->p_memsz || p->p_vaddr > IMAGE_LIMIT
                || p->p_memsz > IMAGE_LIMIT - p->p_vaddr
                || (p->p_vaddr % md_page_size != p->p_offset % md_page_size)
                || ((p->p_flags & (PF_W | PF_X)) == (PF_W | PF_X))) return -ENOEXEC;
        uintptr_t start = down(p->p_vaddr), end = up(p->p_vaddr + p->p_memsz);
        if (start < previous) return -ENOEXEC;
        previous = end;
        if (start < low) low = start;
        if (end > high) high = end;
        if ((p->p_flags & PF_X) && data.header.e_entry >= p->p_vaddr
                && data.header.e_entry - p->p_vaddr < p->p_memsz) executable_entry = 1;
        if (data.header.e_phoff >= p->p_offset && data.header.e_phoff - p->p_offset <= p->p_filesz
                && phsize <= p->p_filesz - (data.header.e_phoff - p->p_offset))
            phdr = p->p_vaddr + data.header.e_phoff - p->p_offset;
    }
    if (!executable_entry || !phdr || high <= low || high - low > IMAGE_LIMIT) return -ENOEXEC;
    long allocation = RAW6(mmap, 0, high - low, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (allocation < 0) return (int)allocation;
    uintptr_t base = (uintptr_t)allocation - low;
    for (unsigned i = 0; i < data.header.e_phnum; ++i) {
        Elf64_Phdr *p = data.ph + i;
        if (p->p_type != PT_LOAD || !p->p_memsz) continue;
        uintptr_t start = down(p->p_vaddr), end = up(p->p_vaddr + p->p_memsz);
        uintptr_t file_end = p->p_filesz ? up(p->p_vaddr + p->p_filesz) : start;
        // MAP_FIXED is restricted to the range reserved by this loader above.
        if (file_end > start) {
            long mapped = RAW6(mmap, base + start, file_end - start, PROT_READ | PROT_WRITE,
                    MAP_PRIVATE | MAP_FIXED, fd, down(p->p_offset));
            if (mapped < 0) { r = (int)mapped; goto fail; }
            if (p->p_memsz > p->p_filesz)
                memset((void *)(base + p->p_vaddr + p->p_filesz), 0, file_end - p->p_vaddr - p->p_filesz);
        }
        if (end > file_end) {
            long mapped = RAW6(mmap, base + file_end, end - file_end, PROT_READ | PROT_WRITE,
                    MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
            if (mapped < 0) { r = (int)mapped; goto fail; }
        }
        int prot = (p->p_flags & PF_R ? PROT_READ : 0) | (p->p_flags & PF_W ? PROT_WRITE : 0)
                | (p->p_flags & PF_X ? PROT_EXEC : 0);
        r = (int)RAW3(mprotect, base + start, end - start, prot);
        if (r < 0) goto fail;
    }
    *image = (struct md_image){base + data.header.e_entry, base + phdr, data.header.e_phnum};
    return 0;
fail:
    RAW2(munmap, allocation, high - low);
    return r;
}
