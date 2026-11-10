#ifndef MD_MAPPING_OBSERVATION_H
#define MD_MAPPING_OBSERVATION_H
#include <stdint.h>
#include <stddef.h>
#include <sys/types.h>

/* A retained /proc/thread-self/maps description, opened by the mapping task.
 * Read before that task leaves PTRACE_EVENT_EXIT, including nondumpable tasks.
 * Identity and file offsets, not a pathname or syscall return register, prove
 * that the complete requested range has the expected sharing mode and backing. */
struct md_mapping_source { dev_t device; ino_t inode; };
int md_mapping_source(int fd, struct md_mapping_source *);
int md_mapping_observe(int maps, const struct md_mapping_source *, uintptr_t address,
        size_t size, uint64_t offset, int shared);
#endif
