#ifndef MD_ELF_H
#define MD_ELF_H
#include <elf.h>
#include <stdint.h>
struct md_image { uintptr_t entry, phdr; unsigned phnum; };
int md_elf_check(int fd, int loader);
int md_elf_load(int fd, struct md_image *image);
#endif
