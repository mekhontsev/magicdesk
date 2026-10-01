#define _GNU_SOURCE
#include "bootstrap.h"
#include "raw.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

struct md_fs md_files;
char md_bootstrap[PATH_MAX], md_executable[PATH_MAX];

static void image(int directory, const char *name, int interpreted) {
    unsigned char bytes[4096] = {0};
    Elf64_Ehdr *header = (void *)bytes;
    *header = (Elf64_Ehdr){.e_type = ET_DYN, .e_machine = EM_AARCH64, .e_version = EV_CURRENT,
        .e_entry = 256, .e_phoff = sizeof(*header), .e_ehsize = sizeof(*header),
        .e_phentsize = sizeof(Elf64_Phdr), .e_phnum = interpreted ? 2 : 1};
    memcpy(header->e_ident, ELFMAG, SELFMAG);
    header->e_ident[EI_CLASS] = ELFCLASS64; header->e_ident[EI_DATA] = ELFDATA2LSB;
    header->e_ident[EI_VERSION] = EV_CURRENT;
    Elf64_Phdr *segment = (void *)(bytes + sizeof(*header));
    segment[0] = (Elf64_Phdr){.p_type = PT_LOAD, .p_flags = PF_R | PF_X,
        .p_filesz = sizeof(bytes), .p_memsz = sizeof(bytes)};
    if (interpreted) {
        strcpy((char *)bytes + 512, "/loader");
        segment[1] = (Elf64_Phdr){.p_type = PT_INTERP, .p_offset = 512, .p_filesz = 8};
    }
    int fd = openat(directory, name, O_WRONLY | O_CREAT | O_EXCL, 0700); assert(fd >= 0);
    assert(write(fd, bytes, sizeof(bytes)) == sizeof(bytes) && !close(fd));
}
int main(int argc, char **argv) {
    md_page_size = (size_t)sysconf(_SC_PAGESIZE);
    assert(argc == 2 && !mkdir(argv[1], 0700));
    assert(!md_copy(md_files.root, sizeof(md_files.root), argv[1]));
    int directory = open(argv[1], O_RDONLY | O_DIRECTORY | O_CLOEXEC); assert(directory >= 0);
    image(directory, "program", 1); image(directory, "loader", 0);
    assert(!symlinkat("program", directory, "alias"));
    struct md_command command;
    char *args[] = {"custom-argv-zero", "argument", NULL};
    int status = md_command_prepare(&md_files, &command, "/alias", args, NULL, 0);
    if (status) fprintf(stderr, "prepare: %s (%d)\n", strerror(-status), status);
    assert(!status);
    assert(command.fd >= 0 && command.interpreter_fd >= 0 && command.fd != command.interpreter_fd);
    assert(!strcmp(command.path, "/program") && command.argc == 2);
    assert(!strcmp(command.argv[0], args[0]) && !strcmp(command.argv[1], args[1]));
    assert(fcntl(command.fd, F_GETFD) == FD_CLOEXEC && fcntl(command.interpreter_fd, F_GETFD) == FD_CLOEXEC);
    struct md_program relative;
    assert(!md_program_acquire(&md_files, directory, "alias", 0, &relative));
    assert(!strcmp(relative.identity.path, "/program"));
    assert(!close(relative.fd));
    assert(!unlinkat(directory, "loader", 0));
    int replacement = openat(directory, "loader", O_WRONLY | O_CREAT | O_EXCL, 0700);
    assert(replacement >= 0 && write(replacement, "invalid", 7) == 7 && !close(replacement));
    assert(!md_elf_validate(command.interpreter_fd, 1));
    int program = command.fd, loader = command.interpreter_fd;
    md_command_close(&command); md_command_close(&command);
    assert(fcntl(program, F_GETFD) == -1 && errno == EBADF);
    assert(fcntl(loader, F_GETFD) == -1 && errno == EBADF);
    assert(md_command_prepare(&md_files, &command, "/program", args, NULL, 0) == -ENOEXEC);
    assert(command.fd == -1 && command.interpreter_fd == -1);
    assert(!unlinkat(directory, "loader", 0)); image(directory, "loader", 0);
    program = openat(directory, "program", O_RDONLY | O_CLOEXEC); assert(program >= 0);
    assert(!unlinkat(directory, "program", 0));
    struct md_program input;
    assert(!md_program_capture(&md_files, program, "/dev/fd/123", &input));
    assert(!md_command_prepare(&md_files, &command, "/dev/fd/123", args, &input, 0));
    assert(!close(input.fd));
    assert(!strcmp(command.path, "/dev/fd/123") && fcntl(program, F_GETFD) == FD_CLOEXEC);
    md_command_close(&command); assert(!close(program));
    assert(!unlinkat(directory, "alias", 0) && !unlinkat(directory, "loader", 0));
    assert(!close(directory) && !rmdir(argv[1]));
    puts("PASS prepared command: pinned loader replacement, borrowed/unlinked ELF, argv, identity and descriptor cleanup");
}
