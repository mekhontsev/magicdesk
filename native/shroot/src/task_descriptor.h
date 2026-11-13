#ifndef MD_TASK_DESCRIPTOR_H
#define MD_TASK_DESCRIPTOR_H
#include <sys/types.h>

/* Never substitutes the leader for a non-leader. ENOTSUP means the kernel
 * cannot address this task. thread_handle distinguishes exact-thread death
 * from flags-zero group death. The caller retains identity during acquisition. */
int md_task_pidfd_open(pid_t, int *thread_handle);
int md_task_descriptor_needs_export(int error);

/* Caller holds the source table's publication turn. The returned descriptor
 * retains the actual OFD; no pathname or inode identity substitutes for it.
 * Initialize process to -1. Retain it across copies, not across exec or task
 * retirement. Every copy rechecks native access and the current FD slot. */
int md_task_descriptor_copy(pid_t actor, int number, int *process);
void md_task_descriptor_release(int *process);
/* Metadata-only pin under the same table turn. This is not an OFD copy:
 * offsets, access mode and status flags must never be inferred from it. */
int md_task_descriptor_pin(pid_t actor,int number);
#endif
