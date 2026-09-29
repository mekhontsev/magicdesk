#ifndef MD_ELF_H
#define MD_ELF_H
#include <elf.h>
#include <stdint.h>
#include <linux/limits.h>
#define MD_INTERPRETER_MAX PATH_MAX
struct md_image { uintptr_t base, entry, phdr; unsigned phnum; };
int md_elf_interpreter(int fd, char interpreter[MD_INTERPRETER_MAX]);
int md_elf_validate(int fd, int loader);
int md_elf_load(int fd, int loader, struct md_image *image);
#endif
