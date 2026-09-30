#define _GNU_SOURCE
#include "elf.h"
#include "raw.h"
#include <errno.h>
#include <sys/mman.h>
#include <sys/stat.h>

struct elf_data { Elf64_Ehdr header; Elf64_Phdr ph[128]; struct stat st; char interpreter[MD_INTERPRETER_MAX]; };
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
            || h->e_ident[EI_VERSION] != EV_CURRENT
            || h->e_machine != EM_AARCH64 || h->e_version != EV_CURRENT
            || h->e_ehsize != sizeof(*h) || h->e_phentsize != sizeof(Elf64_Phdr)
            || !h->e_phnum || h->e_phnum > 128
            || (h->e_type != ET_DYN && (loader || h->e_type != ET_EXEC))) return -ENOEXEC;
    size_t size = h->e_phnum * sizeof(Elf64_Phdr);
    if (data->st.st_size < 0 || h->e_phoff > (uint64_t)data->st.st_size
            || size > (uint64_t)data->st.st_size - h->e_phoff
            || RAW4(pread64, fd, data->ph, size, h->e_phoff) != (long)size) return -ENOEXEC;
    int interpreted = 0;
    data->interpreter[0] = 0;
    for (unsigned i = 0; i < h->e_phnum; ++i) {
        Elf64_Phdr *p = data->ph + i;
        if (p->p_offset > (uint64_t)data->st.st_size
                || p->p_filesz > (uint64_t)data->st.st_size - p->p_offset) return -ENOEXEC;
        if (p->p_type == PT_INTERP) {
            char *interpreter = data->interpreter;
            if (loader || interpreted++ || p->p_filesz < 2 || p->p_filesz > sizeof(data->interpreter)
                    || RAW4(pread64, fd, interpreter, p->p_filesz, p->p_offset) != (long)p->p_filesz
                    || interpreter[p->p_filesz - 1] || md_length(interpreter) != p->p_filesz - 1
                    || interpreter[0] != '/') return -ENOEXEC;
        }
    }
    return 0;
}
struct elf_layout { uintptr_t low, high, phdr, alignment; };
static int layout(const struct elf_data *data, struct elf_layout *out) {
    uintptr_t low = UINTPTR_MAX, high = 0, previous = 0;
    int executable_entry = 0;
    size_t phsize = data->header.e_phnum * sizeof(Elf64_Phdr);
    uintptr_t phdr = 0;
    uintptr_t alignment = md_page_size;
    for (unsigned i = 0; i < data->header.e_phnum; ++i) {
        const Elf64_Phdr *p = data->ph + i;
        if (p->p_type != PT_LOAD || !p->p_memsz) continue;
        if (p->p_filesz > p->p_memsz || p->p_memsz > UINTPTR_MAX - (md_page_size - 1)
                || p->p_vaddr > UINTPTR_MAX - (md_page_size - 1) - p->p_memsz
                || (p->p_vaddr % md_page_size != p->p_offset % md_page_size)
                || ((p->p_flags & (PF_W | PF_X)) == (PF_W | PF_X))) return -ENOEXEC;
        if (p->p_align > 1 && ((p->p_align & (p->p_align - 1))
                || (p->p_vaddr % p->p_align != p->p_offset % p->p_align))) return -ENOEXEC;
        if (p->p_align > alignment) alignment = p->p_align;
        uintptr_t start = down(p->p_vaddr), end = up(p->p_vaddr + p->p_memsz);
        if (start < previous) return -ENOEXEC;
        previous = end;
        if (start < low) low = start;
        if (end > high) high = end;
        if ((p->p_flags & PF_X) && data->header.e_entry >= p->p_vaddr
                && data->header.e_entry - p->p_vaddr < p->p_memsz) executable_entry = 1;
        if (data->header.e_phoff >= p->p_offset && data->header.e_phoff - p->p_offset <= p->p_filesz
                && phsize <= p->p_filesz - (data->header.e_phoff - p->p_offset))
            phdr = p->p_vaddr + data->header.e_phoff - p->p_offset;
    }
    if (!executable_entry || high <= low) return -ENOEXEC;
    if (alignment - md_page_size > SIZE_MAX - (high - low)) return -ENOEXEC;
    *out = (struct elf_layout){low, high, phdr, alignment};
    return 0;
}
int md_elf_interpreter(int fd, char interpreter[MD_INTERPRETER_MAX]) {
    struct elf_data data;
    struct elf_layout range;
    int r = read_elf(fd, &data, 0);
    if (!r) r = layout(&data, &range);
    if (!r) md_copy(interpreter, MD_INTERPRETER_MAX, data.interpreter);
    return r;
}
int md_elf_validate(int fd, int loader) {
    struct elf_data data;
    struct elf_layout range;
    int r = read_elf(fd, &data, loader);
    return r ? r : layout(&data, &range);
}
int md_elf_load(int fd, int loader, struct md_image *image) {
    struct elf_data data;
    struct elf_layout range;
    int r = read_elf(fd, &data, loader);
    if (!r) r = layout(&data, &range);
    if (r) return r;
    uintptr_t low = range.low, high = range.high, phdr = range.phdr;
    int fixed = data.header.e_type == ET_EXEC;
    size_t span = high - low;
    size_t reserved = span + (fixed ? 0 : range.alignment - md_page_size);
    long allocation = RAW6(mmap, fixed ? low : 0, reserved, PROT_NONE,
        MAP_PRIVATE | MAP_ANONYMOUS | (fixed ? MAP_FIXED_NOREPLACE : 0), -1, 0);
    if (allocation < 0) return (int)allocation;
    if (fixed && (uintptr_t)allocation != low) {
        RAW2(munmap, allocation, reserved);
        return -EEXIST;
    }
    if (!fixed) {
        // ELF alignment applies to the load bias, not just each mapped page.
        size_t prefix = (low - (uintptr_t)allocation) & (range.alignment - 1);
        size_t suffix = reserved - prefix - span;
        if (prefix) RAW2(munmap, allocation, prefix);
        allocation += prefix;
        if (suffix) RAW2(munmap, allocation + span, suffix);
    }
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
    *image = (struct md_image){base, base + data.header.e_entry, base + phdr, data.header.e_phnum};
    return 0;
fail:
    RAW2(munmap, allocation, high - low);
    return r;
}
